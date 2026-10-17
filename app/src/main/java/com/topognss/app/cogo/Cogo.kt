package com.topognss.app.cogo

import com.topognss.app.geo.normalize180
import com.topognss.app.geo.normalize360
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Punto plano para cálculo: E (x), N (y), cota (z, opcional). */
data class P3(val e: Double, val n: Double, val z: Double? = null)

data class Segment(
    val from: Int,
    val to: Int,
    val horizontal: Double,
    val deltaZ: Double?,
    val slope: Double?,
    val azimuthDeg: Double,
    val gradePercent: Double?,
)

data class VertexAngle(
    val at: Int,
    /** Ángulo horario medido desde la alineación hacia atrás hasta la alineación hacia adelante (0..360). */
    val clockwiseAngleDeg: Double,
    /** Deflexión: + derecha, − izquierda (-180..180). */
    val deflectionDeg: Double,
)

data class PolygonResult(val area: Double, val perimeter: Double, val clockwise: Boolean)

/**
 * Cálculos COGO sobre coordenadas planas (de cuadrícula) del proyecto.
 * Convención topográfica: azimut desde el Norte, sentido horario; E = x, N = y.
 */
object Cogo {

    fun azimuth(a: P3, b: P3): Double = normalize360(Math.toDegrees(atan2(b.e - a.e, b.n - a.n)))

    fun horizontalDistance(a: P3, b: P3): Double = hypot(b.e - a.e, b.n - a.n)

    fun segment(points: List<P3>, i: Int, j: Int): Segment {
        val a = points[i]; val b = points[j]
        val h = horizontalDistance(a, b)
        val dz = if (a.z != null && b.z != null) b.z - a.z else null
        return Segment(
            from = i, to = j,
            horizontal = h,
            deltaZ = dz,
            slope = dz?.let { sqrt(h * h + it * it) },
            azimuthDeg = azimuth(a, b),
            gradePercent = if (dz != null && h > 1e-9) dz / h * 100.0 else null,
        )
    }

    /** Segmentos consecutivos de una poligonal abierta. */
    fun traverse(points: List<P3>): List<Segment> = (0 until points.size - 1).map { segment(points, it, it + 1) }

    /** Ángulos en los vértices intermedios (o en todos si [closed]). */
    fun vertexAngles(points: List<P3>, closed: Boolean): List<VertexAngle> {
        val n = points.size
        if (n < 3) return emptyList()
        val idx = if (closed) (0 until n) else (1 until n - 1)
        return idx.map { k ->
            val prev = points[(k - 1 + n) % n]
            val cur = points[k]
            val next = points[(k + 1) % n]
            val back = azimuth(cur, prev)
            val fwd = azimuth(cur, next)
            val incoming = azimuth(prev, cur)
            VertexAngle(
                at = k,
                clockwiseAngleDeg = normalize360(fwd - back),
                deflectionDeg = normalize180(fwd - incoming),
            )
        }
    }

    /** Superficie por fórmula de Gauss (shoelace) y perímetro del polígono cerrado. */
    fun polygon(points: List<P3>): PolygonResult? {
        if (points.size < 3) return null
        // Reducimos a un origen local para no perder precisión con coordenadas UTM grandes.
        val e0 = points[0].e; val n0 = points[0].n
        var twiceArea = 0.0
        var perimeter = 0.0
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            twiceArea += (a.e - e0) * (b.n - n0) - (b.e - e0) * (a.n - n0)
            perimeter += horizontalDistance(a, b)
        }
        // En (E,N) con área "positiva antihoraria", área negativa = recorrido horario.
        return PolygonResult(area = abs(twiceArea) / 2.0, perimeter = perimeter, clockwise = twiceArea < 0)
    }

    // ------------------------------------------------------------------------------------
    // Generación de puntos
    // ------------------------------------------------------------------------------------

    fun midpoint(a: P3, b: P3) = P3(
        (a.e + b.e) / 2, (a.n + b.n) / 2,
        if (a.z != null && b.z != null) (a.z + b.z) / 2 else a.z ?: b.z,
    )

    /**
     * Prolongación: punto a [distance] desde B en la dirección A→B (distancia negativa = hacia A).
     * Si [extrapolateGrade], la cota sigue la pendiente A→B; si no, se copia la de B.
     */
    fun extend(a: P3, b: P3, distance: Double, extrapolateGrade: Boolean): P3 {
        val l = horizontalDistance(a, b)
        require(l > 1e-6) { "Los puntos base coinciden" }
        val ue = (b.e - a.e) / l; val un = (b.n - a.n) / l
        val z = when {
            b.z == null -> null
            extrapolateGrade && a.z != null -> b.z + (b.z - a.z) / l * distance
            else -> b.z
        }
        return P3(b.e + ue * distance, b.n + un * distance, z)
    }

    /**
     * Estación y desplazamiento respecto de la línea A→B:
     * [station] = distancia a lo largo de la línea desde A, [offset] = perpendicular (+ derecha, − izquierda
     * mirando de A hacia B). La cota se interpola linealmente sobre la línea.
     */
    fun stationOffset(a: P3, b: P3, station: Double, offset: Double): P3 {
        val l = horizontalDistance(a, b)
        require(l > 1e-6) { "Los puntos base coinciden" }
        val ue = (b.e - a.e) / l; val un = (b.n - a.n) / l
        // Normal derecha = dirección rotada -90°: (un, -ue). Ej.: línea al Norte (0,1) → derecha = Este (1,0).
        val re = un; val rn = -ue
        val z = if (a.z != null && b.z != null) a.z + (b.z - a.z) * station / l else a.z
        return P3(a.e + ue * station + re * offset, a.n + un * station + rn * offset, z)
    }

    /** Inverso: estación/desplazamiento de un punto P respecto de la línea A→B. */
    fun inverseStationOffset(a: P3, b: P3, p: P3): Pair<Double, Double> {
        val l = horizontalDistance(a, b)
        val ue = (b.e - a.e) / l; val un = (b.n - a.n) / l
        val de = p.e - a.e; val dn = p.n - a.n
        return Pair(de * ue + dn * un, de * un - dn * ue)
    }

    /** Punto por azimut y distancia desde A (radiación). */
    fun polar(a: P3, azimuthDeg: Double, distance: Double, dz: Double? = null): P3 {
        val r = Math.toRadians(azimuthDeg)
        return P3(a.e + distance * sin(r), a.n + distance * cos(r), if (a.z != null && dz != null) a.z + dz else a.z)
    }
}

/** Formato topográfico de ángulos: 123°45'06.7" */
fun formatDms(deg: Double, decimals: Int = 1): String {
    val sign = if (deg < 0) "-" else ""
    var v = abs(deg)
    var d = v.toInt()
    v = (v - d) * 60
    var m = v.toInt()
    var s = (v - m) * 60
    val factor = Math.pow(10.0, decimals.toDouble())
    s = Math.round(s * factor) / factor
    if (s >= 60.0) { s -= 60.0; m += 1 }
    if (m >= 60) { m -= 60; d += 1 }
    return "%s%d°%02d'%0${3 + decimals}.${decimals}f\"".format(java.util.Locale.US, sign, d, m, s)
}
