package com.topognss.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.topognss.app.data.AppDatabase
import com.topognss.app.data.AppPrefs
import com.topognss.app.data.TopoRepository
import com.topognss.app.gnss.GnssManager
import com.topognss.app.sensors.CompassSensor
import com.topognss.app.ui.map.initOsmdroid
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Contenedor de dependencias manual (sin Hilt para que el build de CI sea simple y rápido).
 * Todo lo que vive aquí sobrevive a la navegación entre pantallas.
 */
class TopoApp : Application() {
    lateinit var repository: TopoRepository
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var compass: CompassSensor
        private set
    val gnss: GnssManager by lazy { GnssManager() }
    val updates: com.topognss.app.update.UpdateManager by lazy { com.topognss.app.update.UpdateManager(this, prefs) }

    /** Punto objetivo elegido para replantear (compartido entre la lista de puntos y el replanteo). */
    val stakeTarget = MutableStateFlow<Long?>(null)

    override fun onCreate() {
        super.onCreate()
        repository = TopoRepository(AppDatabase.create(this))
        prefs = AppPrefs(this)
        compass = CompassSensor(this)
        initOsmdroid(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_GNSS, "Conexión GNSS", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Mantiene la conexión con el receptor mientras la pantalla está apagada"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_GNSS = "gnss"
    }
}
