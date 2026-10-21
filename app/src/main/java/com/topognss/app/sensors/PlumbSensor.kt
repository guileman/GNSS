package com.topognss.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Plomada virtual: usa el acelerómetro del propio teléfono (no el del receptor RS2, que no
 * expone IMU por ningún canal documentado) para indicar cuán a plomo está el jalón contra el
 * que se lo apoya. Prefiere TYPE_GRAVITY (ya filtrado por el fusionador de Android) y cae a
 * TYPE_ACCELEROMETER con un filtro paso-bajo simple si el equipo no lo tiene.
 */
class PlumbSensor(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun readings(): Flow<PlumbReading> = callbackFlow {
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val g = floatArrayOf(0f, 0f, 0f)
        var initialized = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                    g[0] = event.values[0]; g[1] = event.values[1]; g[2] = event.values[2]
                } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && gravitySensor == null) {
                    // Filtro paso-bajo clásico: separa la gravedad de la aceleración por movimiento.
                    if (!initialized) {
                        g[0] = event.values[0]; g[1] = event.values[1]; g[2] = event.values[2]
                        initialized = true
                    } else {
                        for (i in 0..2) g[i] = 0.85f * g[i] + 0.15f * event.values[i]
                    }
                }
                trySend(plumbFromGravity(g[0], g[1], g[2]))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        gravitySensor?.let { sensorManager.registerListener(listener, it, rate) }
        if (gravitySensor == null) accelSensor?.let { sensorManager.registerListener(listener, it, rate) }

        awaitClose { sensorManager.unregisterListener(listener) }
    }.conflate()
}
