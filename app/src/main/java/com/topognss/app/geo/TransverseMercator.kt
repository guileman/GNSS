package com.topognss.app.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** Elipsoide de referencia. WGS84 y GRS80 (POSGAR 2007) difieren < 0.1 mm en la práctica. */
data class Ellipsoid(val a: Double, val invF: Double) {
    val f: Double get() = 1.0 / invF
    val e2: Double get() = f * (2 - f)
    val e: Double get() = sqrt(e2)
    val n: Double get() = f / (2 - f)

    companion object {
        val WGS84 = Ellipsoid(6378137.0, 298.257223563)
        val GRS80 = Ellipsoid(6378137.0, 298.257222101)
    }
}

/**
 * Proyección Transversa de Mercator con las series de Krüger a orden n^6
 * (Karney 2011). Precisión sub-milimétrica dentro de ±4000 km del meridiano
 * central, suficiente para UTM, Gauss-Krüger POSGAR y planos locales.
 *
 * @param lon0Deg  meridiano central (grados)
 * @param lat0Deg  latitud de origen (grados). UTM = 0; POSGAR GK = -90.
 * @param k0       factor de escala en el meridiano central
 * @param falseEasting  falso este (m)
 * @param falseNorthing falso norte (m)
 */
class TransverseMercator(
    val lon0Deg: Double,
    val lat0Deg: Double,
    val k0: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
    val ellipsoid: Ellipsoid = Ellipsoid.WGS84,
) {
    private val e = ellipsoid.e
    private val n = ellipsoid.n
    private val lon0 = Math.toRadians(lon0Deg)

    /** Radio rectificante A = a/(1+n)·(1 + n²/4 + n⁴/64 + n⁶/256) */
    private val bigA: Double

    private val alpha = DoubleArray(7)
    private val beta = DoubleArray(7)

    /** Ordenada ξ·k0·A de la latitud de origen, para aplicar el falso norte. */
    private val northingOfOrigin: Double

    init {
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        bigA = ellipsoid.a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)

        alpha[1] = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800
        alpha[2] = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360
        alpha[3] = 61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440
        alpha[4] = 49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600
        alpha[5] = 34729 * n5 / 80640 - 3418889 * n6 / 1995840
        alpha[6] = 212378941 * n6 / 319334400

        beta[1] = n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800
        beta[2] = n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720
        beta[3] = 17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720
        beta[4] = 4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600
        beta[5] = 4583 * n5 / 161280 - 108847 * n6 / 3991680
        beta[6] = 20648693 * n6 / 638668800

        northingOfOrigin = k0 * bigA * xiOnMeridian(Math.toRadians(lat0Deg))
    }

    /** ξ para un punto sobre el meridiano central (η = 0). */
    private fun xiOnMeridian(phi: Double): Double {
        if (abs(phi) >= PI / 2 - 1e-12) return if (phi > 0) PI / 2 else -PI / 2
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = atan(t)
        var xi = xiP
        for (j in 1..6) xi += alpha[j] * sin(2 * j * xiP)
        return xi
    }

    /** Geográficas (grados) → planas (E, N) en metros. */
    fun forward(latDeg: Double, lonDeg: Double): PlaneXY {
        val phi = Math.toRadians(latDeg)
        val lam = normalizeLon(Math.toRadians(lonDeg) - lon0)

        // Latitud conforme expresada como t = tan(χ)
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1 + t * t))

        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += alpha[j] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        val east = falseEasting + k0 * bigA * eta
        val north = falseNorthing + k0 * bigA * xi - northingOfOrigin
        return PlaneXY(east, north)
    }

    /** Planas (E, N) → geográficas (grados). */
    fun inverse(east: Double, north: Double): LatLon {
        val xi = (north - falseNorthing + northingOfOrigin) / (k0 * bigA)
        val eta = (east - falseEasting) / (k0 * bigA)

        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))           // latitud conforme
        val lam = atan2(sinh(etaP), cos(xiP))

        // Latitud conforme → geodésica (iteración de punto fijo, converge en ~5 pasos)
        var phi = chi
        repeat(12) {
            val es = e * sin(phi)
            val next = 2 * atan(tan(PI / 4 + chi / 2) * ((1 + es) / (1 - es)).pow(e / 2)) - PI / 2
            if (abs(next - phi) < 1e-14) { phi = next; return@repeat }
            phi = next
        }
        return LatLon(Math.toDegrees(phi), Math.toDegrees(normalizeLon(lam + lon0)))
    }

    /**
     * Factor de escala puntual k (cuadrícula / elipsoide), calculado numéricamente.
     * Distancia_terreno ≈ Distancia_plana / k (ignorando el factor de altura).
     */
    fun pointScale(latDeg: Double, lonDeg: Double): Double {
        val d = 1e-5 // grados
        val p1 = forward(latDeg, lonDeg - d)
        val p2 = forward(latDeg, lonDeg + d)
        val grid = hypot(p2.east - p1.east, p2.north - p1.north)
        val phi = Math.toRadians(latDeg)
        val nu = ellipsoid.a / sqrt(1 - ellipsoid.e2 * sin(phi) * sin(phi))
        val arc = nu * cos(phi) * Math.toRadians(2 * d)
        return grid / arc
    }

    /** Convergencia de meridianos γ (grados): ángulo entre Norte de cuadrícula y Norte verdadero. */
    fun gridConvergence(latDeg: Double, lonDeg: Double): Double {
        val d = 1e-5
        val p1 = forward(latDeg - d, lonDeg)
        val p2 = forward(latDeg + d, lonDeg)
        return Math.toDegrees(atan2(p2.east - p1.east, p2.north - p1.north))
    }

    private fun normalizeLon(l: Double): Double {
        var x = l
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}

data class PlaneXY(val east: Double, val north: Double)
data class LatLon(val lat: Double, val lon: Double)
