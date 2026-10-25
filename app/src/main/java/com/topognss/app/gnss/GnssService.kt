package com.topognss.app.gnss

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.topognss.app.MainActivity
import com.topognss.app.R
import com.topognss.app.TopoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Servicio en primer plano: evita que Android mate el proceso (y el socket) con la pantalla apagada
 * durante un relevamiento largo. No contiene lógica: la conexión vive en [GnssManager].
 */
class GnssService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updater: Job? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build("Conectando…"), type)
        } catch (e: Exception) {
            // Android 14+ puede rechazarlo si faltan permisos de dispositivo conectado: seguimos sin servicio.
            stopSelf()
            return START_NOT_STICKY
        }
        // Con la pantalla apagada el Wi-Fi entra en ahorro de energía y corta el flujo de datos
        if (wifiLock == null) {
            wifiLock = runCatching {
                (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "topognss:gnss")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        updater?.cancel()
        val gnss = (application as TopoApp).gnss
        updater = scope.launch {
            gnss.state
                .map { s ->
                    if (s.connection != ConnectionStatus.CONNECTED) "${s.connection.name.lowercase()} · ${s.connectionLabel}"
                    else String.format(
                        Locale.US, "%s · %d sats · H %s",
                        s.solution.label, s.satellites ?: 0,
                        s.hrms?.let { String.format(Locale.US, "%.3f m", it) } ?: "—",
                    )
                }
                .distinctUntilChanged()
                .collect { text ->
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    runCatching { nm.notify(NOTIF_ID, build(text)) }
                }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TopoApp.CHANNEL_GNSS)
            .setSmallIcon(R.drawable.ic_stat_gnss)
            .setContentTitle("Receptor GNSS")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GnssService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GnssService::class.java))
        }
    }
}
