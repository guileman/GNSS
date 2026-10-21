package com.topognss.app.cogo

import com.topognss.app.geo.normalize360
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Curva horizontal circular simple, definida por el punto PC (donde empieza la curva), el
 * azimut de la tangente de entrada (dirección de avance en PC), el radio y el ángulo central
 * (delta). [turnRight] indica si la curva gira a la derecha (horario) o a la izquierda.
 *
 * Convención: igual que el resto de Cogo, azimut desde el Norte en sentido horario, E=x, N=y.
 */
data class HorizontalCurve(
    val pc: P3,
    val tangentAzimuthDeg: Double,
    val radius: Double,
    val deltaDeg: Double,
    val turnRight: Boolean,
) {
    init {
        require(radius > 0) { "El radio debe ser positivo" }
        require(deltaDeg > 0 && deltaDeg < 180) { "El ángulo central debe estar entre 0° y 180°" }
    }

    /** Longitud de arco de la curva. */
    val length: Double get() = radius * Math.toRadians(deltaDeg)

    /** Longitud de la tangente (PI a PC, y PI a PT). */
    val tangentLength: Double get() = radius * tan(Math.toRadians(deltaDeg) / 2)

    /** Distancia externa (PI al punto medio del arco). */
    val external: Double get() = radius * (1.0 / cos(Math.toRadians(deltaDeg) / 2) - 1)

    /** Ordenada media (punto medio de la cuerda al punto medio del arco). */
    val middleOrdinate: Double get() = radius * (1 - cos(Math.toRadians(deltaDeg) / 2))

    /** Cuerda larga (PC a PT en línea recta). */
    val longChord: Double get() = 2 * radius * sin(Math.toRadians(deltaDeg) / 2)

    /** Azimut de la tangente de salida (dirección de avance en PT). */
    val outTangentAzimuthDeg: Double get() = normalize360(tangentAzimuthDeg + (if (turnRight) deltaDeg else -deltaDeg))

    /** Punto PI (intersección de tangentes), útil para referencia. */
    val pi: P3 get() = Cogo.polar(pc, tangentAzimuthDeg, tangentLength)

    /** Punto PT (fin de la curva). */
    val pt: P3 get() = pointAt(length)

    /**
     * Punto sobre el arco a la distancia [arcLength] desde PC (0 = PC, [length] = PT).
     * La cota (si la hay) se interpola linealmente con el desarrollo del arco.
     */
    fun pointAt(arcLength: Double): P3 {
        require(arcLength >= 0) { "La distancia de arco no puede ser negativa" }
        val phi = arcLength / radius // ángulo central recorrido, en radianes
        val localForward = radius * sin(phi)
        val localRight = radius * (1 - cos(phi))
        val signedRight = if (turnRight) localRight else -localRight
        val az = Math.toRadians(tangentAzimuthDeg)
        val e = pc.e + localForward * sin(az) + signedRight * cos(az)
        val n = pc.n + localForward * cos(az) - signedRight * sin(az)
        val z = pc.z?.let { z0 -> if (length > 1e-9) z0 + (ptZOffset() ?: 0.0) * (arcLength / length) else z0 }
        return P3(e, n, z)
    }

    private fun ptZOffset(): Double? = null // la cota de PT se maneja aparte si se conoce; ver curveStakeout()

    /** Azimut de la tangente a la curva en el punto situado a [arcLength] de PC (dirección de avance). */
    fun tangentAzimuthAt(arcLength: Double): Double {
        val phi = Math.toDegrees(arcLength / radius)
        return normalize360(tangentAzimuthDeg + (if (turnRight) phi else -phi))
    }
}

data class CurveStakePoint(val name: String, val station: Double, val point: P3, val chordFromPc: Double, val deflectionDeg: Double)

/**
 * Genera puntos de estacado sobre la curva cada [interval] metros de arco (más el punto final
 * PT si no coincide exactamente), con la estación acumulada desde [startStation] (la del PC).
 * [deflectionDeg] de cada punto es el ángulo de deflexión desde la tangente de entrada hasta la
 * cuerda PC-punto (la mitad del ángulo central recorrido), tal como se replantea clásicamente con
 * un total station parado en el PC.
 */
fun curveStakeout(curve: HorizontalCurve, interval: Double, startStation: Double = 0.0, pcName: String = "PC"): List<CurveStakePoint> {
    require(interval > 0) { "El intervalo de estacado debe ser positivo" }
    val points = ArrayList<CurveStakePoint>()
    var s = 0.0
    var i = 0
    while (s < curve.length - 1e-6) {
        val arcLenFromPc = s
        val phiHalf = Math.toDegrees(arcLenFromPc / curve.radius) / 2
        val name = if (i == 0) pcName else "E${(startStation + s).toInt()}"
        points.add(
            CurveStakePoint(
                name = name,
                station = startStation + s,
                point = curve.pointAt(arcLenFromPc),
                chordFromPc = 2 * curve.radius * sin(Math.toRadians(phiHalf)),
                deflectionDeg = phiHalf,
            ),
        )
        s += interval
        i++
    }
    // Punto final (PT), siempre incluido.
    val halfDeltaDeg = curve.deltaDeg / 2
    points.add(
        CurveStakePoint(
            name = "PT",
            station = startStation + curve.length,
            point = curve.pt,
            chordFromPc = curve.longChord,
            deflectionDeg = halfDeltaDeg,
        ),
    )
    return points
}

/** Ángulo central correspondiente a una longitud de arco dada, en grados. */
fun deltaForArcLength(radius: Double, arcLength: Double): Double = Math.toDegrees(arcLength / radius)

/** Centro de la circunferencia de la curva. */
fun HorizontalCurve.center(): P3 {
    val az = Math.toRadians(tangentAzimuthDeg)
    val sign = if (turnRight) 1.0 else -1.0
    return P3(pc.e + sign * radius * cos(az), pc.n - sign * radius * sin(az))
}
