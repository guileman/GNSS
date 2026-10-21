package com.topognss.app.cogo

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Un tramo observado de poligonal: azimut y distancia (p. ej. tomados con estación total). */
data class TraverseLeg(val name: String, val azimuthDeg: Double, val distance: Double)

/** Vértice de la poligonal con su posición provisoria (sin corregir) y la corregida (Bowditch). */
data class AdjustedVertex(val name: String, val raw: P3, val adjusted: P3)

data class TraverseAdjustment(
    val vertices: List<AdjustedVertex>,
    /** Error de cierre en E y N (provisorio - conocido en el punto de cierre). */
    val closureE: Double,
    val closureN: Double,
    val closureLinear: Double,
    val perimeter: Double,
    /** Precisión relativa de la poligonal, expresada como 1 : [precisionDenominator]. */
    val precisionDenominator: Double,
)

/**
 * Calcula una poligonal a partir de un punto de arranque conocido y una serie de tramos
 * (azimut + distancia, p. ej. observados con estación total), que debe cerrar en [knownEnd]
 * (puede ser el mismo punto de arranque para una poligonal cerrada).
 *
 * El error de cierre se distribuye entre los vértices de forma proporcional a la distancia
 * acumulada desde el arranque (regla de Bowditch / del compás), que es la compensación
 * estándar cuando no se dispone de las precisiones individuales de cada tramo.
 */
fun adjustTraverse(start: P3, legs: List<TraverseLeg>, knownEnd: P3): TraverseAdjustment {
    require(legs.isNotEmpty()) { "La poligonal necesita al menos un tramo" }
    var e = start.e
    var n = start.n
    val raw = ArrayList<P3>(legs.size)
    for (leg in legs) {
        val r = Math.toRadians(leg.azimuthDeg)
        e += leg.distance * sin(r)
        n += leg.distance * cos(r)
        raw.add(P3(e, n))
    }
    val perimeter = legs.sumOf { it.distance }
    val closureE = knownEnd.e - raw.last().e
    val closureN = knownEnd.n - raw.last().n
    val closureLinear = hypot(closureE, closureN)

    var cumulative = 0.0
    val vertices = ArrayList<AdjustedVertex>(legs.size)
    for (i in legs.indices) {
        cumulative += legs[i].distance
        val f = if (perimeter > 1e-9) cumulative / perimeter else 1.0
        val r3 = raw[i]
        val z = start.z?.let { sz -> knownEnd.z?.let { ez -> sz + (ez - sz) * f } } ?: start.z ?: knownEnd.z
        vertices.add(
            AdjustedVertex(
                name = legs[i].name,
                raw = r3,
                adjusted = P3(r3.e + closureE * f, r3.n + closureN * f, z),
            ),
        )
    }
    return TraverseAdjustment(
        vertices = vertices,
        closureE = closureE,
        closureN = closureN,
        closureLinear = closureLinear,
        perimeter = perimeter,
        precisionDenominator = if (closureLinear > 1e-9) perimeter / closureLinear else Double.POSITIVE_INFINITY,
    )
}
