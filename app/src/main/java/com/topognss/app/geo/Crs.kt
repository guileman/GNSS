package com.topognss.app.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Tipos de sistema de coordenadas planas soportados por un proyecto. */
enum class CrsType(val label: String) {
    UTM("UTM WGS84"),
    POSGAR_GK("POSGAR 2007 / Gauss-Krüger (Argentina)"),
    LOCAL_TM("Plano local TM (k=1)"),
}

/**
 * Definición del sistema de coordenadas de un proyecto.
 *
 * - UTM: [zone] 1..60, [south] hemisferio sur (falso norte 10 000 000 m).
 * - POSGAR_GK: [zone] = faja 1..7 (MC = -72° + 3°·(faja-1)), k0 = 1,
 *   falso este = faja·1 000 000 + 500 000, latitud de origen -90° (EPSG 5343..5349).
 * - LOCAL_TM: TM con k0 = 1 centrada en ([originLat], [originLon]), falso E/N = 10 000 m.
 *   Las distancias planas son prácticamente distancias sobre el elipsoide en el entorno de la obra.
 */
data class ProjectCrs(
    val type: CrsType,
    val zone: Int = 0,
    val south: Boolean = true,
    val originLat: Double = 0.0,
    val originLon: Double = 0.0,
) {
    val projection: TransverseMercator by lazy {
        when (type) {
            CrsType.UTM -> TransverseMercator(
                lon0Deg = zone * 6.0 - 183.0, lat0Deg = 0.0, k0 = 0.9996,
                falseEasting = 500_000.0, falseNorthing = if (south) 10_000_000.0 else 0.0,
            )
            CrsType.POSGAR_GK -> TransverseMercator(
                lon0Deg = -72.0 + 3.0 * (zone - 1), lat0Deg = -90.0, k0 = 1.0,
                falseEasting = zone * 1_000_000.0 + 500_000.0, falseNorthing = 0.0,
                ellipsoid = Ellipsoid.GRS80,
            )
            CrsType.LOCAL_TM -> TransverseMercator(
                lon0Deg = originLon, lat0Deg = originLat, k0 = 1.0,
                falseEasting = 10_000.0, falseNorthing = 10_000.0,
            )
        }
    }

    fun describe(): String = when (type) {
        CrsType.UTM -> "UTM ${zone}${if (south) "S" else "N"} (WGS84)"
        CrsType.POSGAR_GK -> "POSGAR 2007 faja $zone (MC ${-72 + 3 * (zone - 1)}°)"
        CrsType.LOCAL_TM -> "TM local %.5f, %.5f".format(originLat, originLon)
    }

    companion object {
        fun utmZoneFor(lon: Double): Int = (floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)

        /** Faja POSGAR más cercana a la longitud (Argentina: fajas 1..7). */
        fun posgarFajaFor(lon: Double): Int =
            (Math.round((lon + 72.0) / 3.0).toInt() + 1).coerceIn(1, 7)
    }
}

/**
 * Geometría local tangente (ENU) para replanteo: a distancias cortas (< 1-2 km)
 * entrega distancia y azimut VERDADERO (no de cuadrícula) con error despreciable.
 * Así la flecha se compara directamente con la brújula (que también es Norte verdadero
 * tras aplicar la declinación magnética).
 */
object LocalGeodesy {
    private val wgs = Ellipsoid.WGS84

    data class Delta(val dEast: Double, val dNorth: Double) {
        val distance: Double get() = hypot(dEast, dNorth)
        /** Azimut verdadero 0..360° desde el norte, sentido horario. */
        val azimuthDeg: Double get() = normalize360(Math.toDegrees(atan2(dEast, dNorth)))
    }

    fun delta(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Delta {
        val phi = Math.toRadians((fromLat + toLat) / 2)
        val s = sin(phi)
        val w = sqrt(1 - wgs.e2 * s * s)
        val nu = wgs.a / w                              // radio de curvatura primer vertical
        val rho = wgs.a * (1 - wgs.e2) / (w * w * w)    // radio de curvatura meridiano
        val dLat = Math.toRadians(toLat - fromLat)
        var dLonDeg = toLon - fromLon
        if (dLonDeg > 180) dLonDeg -= 360
        if (dLonDeg < -180) dLonDeg += 360
        val dLon = Math.toRadians(dLonDeg)
        return Delta(dEast = dLon * nu * cos(phi), dNorth = dLat * rho)
    }
}

fun normalize360(deg: Double): Double {
    var d = deg % 360.0
    if (d < 0) d += 360.0
    return d
}

/** Normaliza a (-180, 180]. */
fun normalize180(deg: Double): Double {
    var d = normalize360(deg)
    if (d > 180) d -= 360
    return d
}

