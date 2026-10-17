package com.topognss.app.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class CompassReading(
    /** Rumbo del teléfono respecto del Norte VERDADERO (0..360), ya corregido por declinación. */
    val headingTrueDeg: Float,
    /** Rumbo magnético crudo (0..360). */
    val headingMagneticDeg: Float,
    val declinationDeg: Float,
    /** SensorManager.SENSOR_STATUS_* — < MEDIUM indica que conviene calibrar (movimiento en "8"). */
    val accuracy: Int,
    /** true si el teléfono está más bien vertical (pantalla mirando al operador). */
    val upright: Boolean,
)

/**
 * Brújula basada en el sensor de fusión TYPE_ROTATION_VECTOR (magnetómetro + acelerómetro + giróscopo),
 * mucho más estable que magnetómetro+acelerómetro. Si el equipo no lo tiene se usa la combinación clásica.
 *
 * Puntos críticos:
 *  1. Remapeo de ejes según la postura: plano (mirando al cielo) o vertical (como al sacar una foto),
 *     si no el azimut "salta" al levantar el teléfono.
 *  2. Compensación de la rotación de pantalla.
 *  3. Filtro paso-bajo sobre seno/coseno (NO sobre el ángulo, para no romper el cruce 359°→0°).
 *  4. Declinación magnética del modelo WMM ([GeomagneticField]) en la posición del receptor:
 *     en Buenos Aires ronda -9°/-10°, imprescindible para replantear.
 */
class CompassSensor(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

    /**
     * @param position proveedor de (lat, lon, altura) para calcular la declinación; null = sin corrección.
     * @param smoothing 0..1, fracción de la muestra nueva en el filtro (0.15 ≈ suave, 0.5 ≈ rápido).
     */
    fun readings(position: () -> Triple<Double, Double, Double>?, smoothing: Float = 0.15f): Flow<CompassReading> = callbackFlow {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomag = FloatArray(3)
        var haveGravity = false
        var haveGeomag = false
        var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

        var sinF = 0.0
        var cosF = 1.0
        var initialized = false

        var declination = 0f
        var lastDeclinationCalc = 0L

        fun displayRotation(): Int = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)

        fun emitFromRotationMatrix() {
            // Inclinación: R[8] = componente Z del eje Z del teléfono en el mundo (≈1 plano, ≈0 vertical)
            val upright = abs(rotation[8]) < 0.55f
            val (axisX, axisY) = if (upright) {
                // Teléfono vertical: el "frente" es la dirección hacia donde apunta la cámara trasera.
                SensorManager.AXIS_X to SensorManager.AXIS_Z
            } else {
                when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
            }
            SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val az = orientation[0].toDouble() // radianes, -π..π, 0 = Norte magnético

            if (!initialized) {
                sinF = sin(az); cosF = cos(az); initialized = true
            } else {
                sinF += smoothing * (sin(az) - sinF)
                cosF += smoothing * (cos(az) - cosF)
            }
            val magDeg = ((Math.toDegrees(atan2(sinF, cosF)) + 360.0) % 360.0).toFloat()

            val now = System.currentTimeMillis()
            if (now - lastDeclinationCalc > 10_000) { // la declinación cambia muy lento: recalcular cada 10 s
                position()?.let { (lat, lon, h) ->
                    declination = GeomagneticField(lat.toFloat(), lon.toFloat(), h.toFloat(), now).declination
                    lastDeclinationCalc = now
                }
            }
            val trueDeg = ((magDeg + declination) % 360f + 360f) % 360f
            trySend(CompassReading(trueDeg, magDeg, declination, accuracy, upright))
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        emitFromRotationMatrix()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        for (i in 0..2) gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
                        haveGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomag, 0, 3)
                        haveGeomag = true
                        if (rotationSensor == null && haveGravity &&
                            SensorManager.getRotationMatrix(rotation, null, gravity, geomag)
                        ) emitFromRotationMatrix()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, acc: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor.type == Sensor.TYPE_ROTATION_VECTOR) accuracy = acc
            }
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, rate)
            // El magnetómetro se registra igual sólo para recibir el estado de calibración.
            mag?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            accel?.let { sensorManager.registerListener(listener, it, rate) }
            mag?.let { sensorManager.registerListener(listener, it, rate) }
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()
}

/**
 * Fusión de rumbo para el replanteo:
 *  - En movimiento (velocidad GNSS ≥ [minSpeed]) el rumbo sobre el terreno (COG) del receptor es
 *    más fiable que el magnetómetro (inmune a interferencias de jalón metálico, vehículos, etc.).
 *  - Detenido, el COG es ruido puro → se usa la brújula del teléfono.
 */
enum class HeadingSource { COMPASS, GNSS_COG, NONE }

data class FusedHeading(val degrees: Float?, val source: HeadingSource)

enum class HeadingMode(val label: String) { AUTO("Auto"), COMPASS("Brújula"), GNSS("Rumbo GNSS"), NORTH_UP("Norte arriba") }

fun fuseHeading(
    mode: HeadingMode,
    compass: CompassReading?,
    gnssSpeedMs: Double?,
    gnssCourseDeg: Double?,
    minSpeed: Double = 0.6,
): FusedHeading {
    val cogOk = gnssCourseDeg != null && gnssSpeedMs != null && gnssSpeedMs >= minSpeed
    return when (mode) {
        HeadingMode.NORTH_UP -> FusedHeading(0f, HeadingSource.NONE)
        HeadingMode.COMPASS -> FusedHeading(compass?.headingTrueDeg, if (compass != null) HeadingSource.COMPASS else HeadingSource.NONE)
        HeadingMode.GNSS -> if (cogOk) FusedHeading(gnssCourseDeg!!.toFloat(), HeadingSource.GNSS_COG) else FusedHeading(null, HeadingSource.NONE)
        HeadingMode.AUTO -> when {
            cogOk -> FusedHeading(gnssCourseDeg!!.toFloat(), HeadingSource.GNSS_COG)
            compass != null -> FusedHeading(compass.headingTrueDeg, HeadingSource.COMPASS)
            else -> FusedHeading(null, HeadingSource.NONE)
        }
    }
}
