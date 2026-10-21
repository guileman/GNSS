package com.topognss.app.sensors

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Lectura de la plomada virtual: qué tan vertical está el jalón/baliza contra el que se apoya
 * el teléfono, calculada a partir del vector de gravedad medido por el acelerómetro.
 *
 * Convención: el teléfono se sostiene pegado al jalón, en vertical (retrato), con el eje largo
 * (Y del sensor) alineado con el eje del jalón. Si el jalón está perfectamente a plomo, toda la
 * gravedad cae sobre el eje Y y los ejes X/Z —perpendiculares al jalón— quedan en cero.
 */
data class PlumbReading(
    /** Ángulo total de inclinación respecto de la vertical, en grados (0 = a plomo). */
    val tiltDeg: Float,
    /** Componente de inclinación hacia la derecha (+) / izquierda (−) del jalón, en grados. */
    val tiltRightDeg: Float,
    /** Componente de inclinación hacia adelante (+, hacia la pantalla) / atrás (−), en grados. */
    val tiltForwardDeg: Float,
    /** Desvío lineal equivalente por cada metro de longitud del jalón, en milímetros. */
    val mmPerMeter: Float,
)

/**
 * Calcula la inclinación del jalón a partir de una muestra (ya filtrada) del acelerómetro/
 * gravedad, en el sistema de ejes del propio teléfono (m/s², como entrega Android).
 */
fun plumbFromGravity(ax: Float, ay: Float, az: Float): PlumbReading {
    val g = hypot(hypot(ax.toDouble(), ay.toDouble()), az.toDouble())
    if (g < 1e-3) return PlumbReading(0f, 0f, 0f, 0f)

    val cosTotal = (ay / g).coerceIn(-1.0, 1.0)
    val totalTiltRad = acos(abs(cosTotal))
    val tiltDeg = Math.toDegrees(totalTiltRad).toFloat()

    val ayAbs = abs(ay.toDouble()).coerceAtLeast(1e-6)
    val tiltRightDeg = Math.toDegrees(atan2(ax.toDouble(), ayAbs)).toFloat()
    val tiltForwardDeg = Math.toDegrees(atan2(az.toDouble(), ayAbs)).toFloat()

    val mmPerMeter = (tan(totalTiltRad) * 1000.0).toFloat()
    return PlumbReading(tiltDeg, tiltRightDeg, tiltForwardDeg, mmPerMeter)
}
