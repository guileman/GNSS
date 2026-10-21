package com.topognss.app.cogo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Intersección directa: punto donde se cruzan dos visuales (azimuts) lanzadas desde dos
 * puntos conocidos [a] y [b]. Es el método clásico de "intersección angular" para ubicar un
 * punto inaccesible observando su dirección desde dos estaciones conocidas.
 * Devuelve null si las visuales son paralelas (no se cruzan).
 */
fun intersectionByAzimuths(a: P3, azimuthFromADeg: Double, b: P3, azimuthFromBDeg: Double): P3? {
    val ra = Math.toRadians(azimuthFromADeg)
    val rb = Math.toRadians(azimuthFromBDeg)
    // Dirección unitaria de cada visual en (E,N): (sin az, cos az).
    val dae = sin(ra); val dan = cos(ra)
    val dbe = sin(rb); val dbn = cos(rb)
    // Resolver a.pos + t*da = b.pos + s*db
    val denom = dae * dbn - dan * dbe
    if (abs(denom) < 1e-12) return null // visuales paralelas
    val ex = b.e - a.e; val nx = b.n - a.n
    val t = (ex * dbn - nx * dbe) / denom
    val z = if (a.z != null && b.z != null) (a.z + b.z) / 2 else a.z ?: b.z
    return P3(a.e + dae * t, a.n + dan * t, z)
}

/**
 * Intersección por distancias (trilateración/bilateración): punto a distancia [distFromA] de
 * [a] y [distFromB] de [b]. Hay en general dos soluciones simétricas respecto de la recta A-B;
 * se devuelve la de la derecha mirando de A hacia B, salvo que [preferRight] sea falso.
 * Devuelve null si las circunferencias no se cruzan (distancias incompatibles con la base).
 */
fun intersectionByDistances(a: P3, distFromA: Double, b: P3, distFromB: Double, preferRight: Boolean = true): P3? {
    val d = Cogo.horizontalDistance(a, b)
    if (d < 1e-9) return null
    // Punto a lo largo de la línea A->B, y altura h perpendicular (teorema de Pitágoras generalizado).
    val x = (distFromA * distFromA - distFromB * distFromB + d * d) / (2 * d)
    val h2 = distFromA * distFromA - x * x
    if (h2 < 0) return null // las distancias no permiten que ambas circunferencias se toquen
    val h = sqrt(h2)
    val ue = (b.e - a.e) / d; val un = (b.n - a.n) / d
    // Normal derecha mirando de A hacia B: (un, -ue) (misma convención que Cogo.stationOffset).
    val re = un; val rn = -ue
    val sign = if (preferRight) 1.0 else -1.0
    val z = if (a.z != null && b.z != null) (a.z + b.z) / 2 else a.z ?: b.z
    return P3(a.e + ue * x + re * h * sign, a.n + un * x + rn * h * sign, z)
}

/**
 * Resección de tres puntos (problema de Pothenot / método de Tienstra): a partir de los
 * ángulos horizontales medidos desde un punto desconocido P hacia tres puntos conocidos
 * [p1], [p2], [p3] (en ese orden, tal como se observan desde P girando el instrumento siempre
 * en sentido HORARIO, igual que el círculo horizontal de un total station). [angle12] es el
 * ángulo horario P1-P-P2 y [angle23] el ángulo horario P2-P-P3 (en grados). Es el método
 * estándar de "estación libre" con un total station y tres puntos conocidos, sin necesidad de
 * medir distancias.
 * Devuelve null si la configuración es degenerada (P sobre el círculo que pasa por los 3 puntos).
 */
fun resectionTienstra(p1: P3, p2: P3, p3: P3, angle12: Double, angle23: Double): P3? {
    val angle31 = 360.0 - angle12 - angle23
    if (angle12 <= 0 || angle23 <= 0 || angle31 <= 0) return null

    fun angleAt(at: P3, from: P3, to: P3): Double {
        val a1 = atan2(from.e - at.e, from.n - at.n)
        val a2 = atan2(to.e - at.e, to.n - at.n)
        var d = Math.toDegrees(a2 - a1)
        while (d < 0) d += 360.0
        while (d >= 360.0) d -= 360.0
        return d
    }
    // Ángulos del triángulo P1P2P3 en cada vértice.
    val triAngle1 = angleAt(p1, p2, p3).let { if (it > 180) 360 - it else it }
    val triAngle2 = angleAt(p2, p3, p1).let { if (it > 180) 360 - it else it }
    val triAngle3 = angleAt(p3, p1, p2).let { if (it > 180) 360 - it else it }

    fun cot(deg: Double): Double = 1.0 / tan(Math.toRadians(deg))

    // El ángulo del triángulo en cada vértice se empareja con el ángulo observado en P que
    // NO involucra a ese vértice (el "opuesto"): triAngle1 (en P1) con angle23 (P2-P-P3), etc.
    val k1 = 1.0 / (cot(triAngle1) - cot(angle23))
    val k2 = 1.0 / (cot(triAngle2) - cot(angle31))
    val k3 = 1.0 / (cot(triAngle3) - cot(angle12))
    val ksum = k1 + k2 + k3
    if (abs(ksum) < 1e-9 || ksum.isNaN()) return null

    val e = (k1 * p1.e + k2 * p2.e + k3 * p3.e) / ksum
    val n = (k1 * p1.n + k2 * p2.n + k3 * p3.n) / ksum
    if (e.isNaN() || n.isNaN()) return null
    val z = listOfNotNull(p1.z, p2.z, p3.z).let { if (it.isEmpty()) null else it.average() }
    return P3(e, n, z)
}

/** Azimut desde [at] observando hacia [target] (grados, 0-360, igual convención que Cogo.azimuth). */
fun observedAzimuth(at: P3, target: P3): Double = Cogo.azimuth(at, target)

/** Distancia horizontal observada entre dos puntos (misma convención que Cogo.horizontalDistance). */
fun observedDistance(at: P3, target: P3): Double = hypot(target.e - at.e, target.n - at.n)
