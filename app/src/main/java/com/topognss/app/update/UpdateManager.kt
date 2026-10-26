package com.topognss.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.topognss.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Repositorio de GitHub donde se publican los Releases con el APK. */
const val UPDATE_REPO = "guileman/GNSS"

data class UpdateInfo(val tag: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** APK descargado y verificado. [sameSigner] = false: Android no lo va a dejar instalar encima de la versión actual. */
    data class Ready(val info: UpdateInfo, val file: File, val sameSigner: Boolean?) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Comparación de versiones tipo «1.0.44» / «v1.0.45». */
object Versions {
    fun parse(v: String): List<Int> {
        val out = ArrayList<Int>()
        for (part in v.trim().removePrefix("v").removePrefix("V").split('.')) {
            val n = part.takeWhile { it.isDigit() }.toIntOrNull() ?: break
            out += n
            if (part.any { !it.isDigit() }) break
        }
        return out
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * Actualización de la app sin pasar por el navegador: consulta el último Release de GitHub, descarga
 * el APK dentro de la app y abre el instalador de Android (que siempre pide confirmar con «Instalar»).
 * Para que Android acepte instalarlo ENCIMA de la versión actual, ambas deben estar firmadas con la misma clave.
 */
class UpdateManager(private val context: Context, private val prefs: AppPrefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Versión nueva hallada por la búsqueda automática y no pospuesta: la pantalla principal la ofrece con un aviso. */
    private val _prompt = MutableStateFlow<UpdateInfo?>(null)
    val prompt: StateFlow<UpdateInfo?> = _prompt.asStateFlow()

    private var lastInfo: UpdateInfo? = null

    val currentVersion: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** [auto] = búsqueda silenciosa al abrir la app (como mucho cada 12 h; si falla no molesta). */
    fun check(auto: Boolean = false) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        if (auto && System.currentTimeMillis() - prefs.lastUpdateCheck < 12 * 3_600_000L) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val info = fetchLatest()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (info != null && Versions.isNewer(info.tag, currentVersion)) {
                    lastInfo = info
                    _state.value = UpdateState.Available(info)
                    if (auto && prefs.dismissedUpdate != info.tag) _prompt.value = info
                } else {
                    _state.value = UpdateState.UpToDate
                    cleanup()
                }
            } catch (e: Exception) {
                _state.value = if (auto) UpdateState.Idle else UpdateState.Error(
                    "No se pudo consultar las versiones. Revisá que haya Internet (la Wi-Fi del receptor no tiene).",
                )
            }
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val c = (URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GNSS-app")
        }
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return UpdateInfo(tag, a.getString("browser_download_url"), a.optLong("size"), j.optString("body").trim())
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    fun download() {
        if (_state.value is UpdateState.Downloading) return
        val info = (_state.value as? UpdateState.Available)?.info ?: lastInfo ?: return
        lastInfo = info
        _state.value = UpdateState.Downloading(info, 0f)
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "GNSS-${info.tag}.apk")
                val c = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "GNSS-app")
                }
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                    var done = 0L
                    var lastPct = -1
                    c.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IOException("descarga incompleta")
                } finally {
                    c.disconnect()
                }
                _state.value = verify(info, file)
            } catch (e: Exception) {
                _state.value = UpdateState.Error("Falló la descarga: ${e.message ?: "sin conexión"}")
            }
        }
    }

    /** El archivo tiene que ser una app válida de este mismo paquete; además se compara la firma con la instalada. */
    private fun verify(info: UpdateInfo, file: File): UpdateState {
        val pm = context.packageManager
        val flags = signatureFlags()
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return UpdateState.Error("El archivo descargado no es una app válida. Probá de nuevo.")
        if (archive.packageName != context.packageName) return UpdateState.Error("El archivo descargado no corresponde a esta app.")
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        val a = signers(archive)
        val b = signers(installed)
        val same = if (a == null || b == null) null else a.intersect(b).isNotEmpty()
        return UpdateState.Ready(info, file, same)
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo?): Set<String>? {
        p ?: return null
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else p.signatures
        return sigs?.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet()
    }

    /** Android exige que el usuario habilite «instalar apps desconocidas» para esta app (una sola vez). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install() {
        val s = _state.value as? UpdateState.Ready ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", s.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** [postpone] = «Más tarde»: no vuelve a avisar de esa misma versión (sí de una más nueva). */
    fun dismissPrompt(postpone: Boolean) {
        _prompt.value?.let { if (postpone) prefs.dismissedUpdate = it.tag }
        _prompt.value = null
    }

    private fun cleanup() {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
}
