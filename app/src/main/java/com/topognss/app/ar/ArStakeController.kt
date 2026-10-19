package com.topognss.app.ar

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.topognss.app.geo.LocalGeodesy
import com.topognss.app.gnss.Epoch
import com.topognss.app.gnss.SolutionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Punto a replantear tal como lo necesita la vista AR. */
data class ArTarget(val name: String, val lat: Double, val lon: Double, val elevation: Double?)

/** Posición en pantalla (px) del mojón, para las etiquetas del overlay de Compose. */
data class ScreenPoint(
    val x: Float,
    val y: Float,
    /** true si el mojón está dentro del encuadre. */
    val onScreen: Boolean,
    /** Dirección hacia el mojón cuando está fuera del encuadre (0° arriba, 90° derecha). */
    val edgeAngleDeg: Float,
    /** true si quedó detrás de la cámara. */
    val behind: Boolean,
)

data class ArUiState(
    val running: Boolean = false,
    val trackingOk: Boolean = false,
    /** Motivo legible si el tracking visual no funciona. */
    val trackingMessage: String? = "Iniciando cámara…",
    val alignment: AlignmentStatus = AlignmentStatus(),
    /** Rumbo verdadero de la cámara (°), sólo con la calibración lista. */
    val headingDeg: Double? = null,
    val target: ScreenPoint? = null,
    /** true si ARCore encontró el piso (si no, se estima a 1,4 m bajo el teléfono). */
    val groundDetected: Boolean = false,
    /** Aviso si las épocas GNSS no sirven para calibrar (sin FIX, etc.). */
    val gnssMessage: String? = null,
    val error: String? = null,
)

/**
 * Dueño de la sesión ARCore y de la calibración AR ↔ RTK para el replanteo.
 *
 * Flujo: el renderer (hilo GL) guarda cada pose de la cámara en [poses]; cada época GNSS
 * ([onEpoch]) se empareja con la pose interpolada en su instante y alimenta el [aligner].
 * Con la transformación lista, el renderer ubica el mojón en el marco AR.
 */
class ArStakeController(private val context: Context) {
    @Volatile
    var session: Session? = null
        private set

    val aligner = ArAligner()
    val poses = PoseBuffer()

    /** Origen del ENU local (primera época válida). */
    @Volatile
    var origin: Pair<Double, Double>? = null
        private set

    @Volatile
    var target: ArTarget? = null

    /** Cota del terreno bajo el jalón en este momento (cota GNSS − altura de antena). */
    @Volatile
    var groundElevationNow: Double? = null
        private set

    /**
     * Retardo típico entre el instante de la posición y la llegada de la GGA por Bluetooth/Wi-Fi.
     * Al caminar a 1,2 m/s, 100 ms equivalen a 12 cm a lo largo del recorrido.
     */
    var gnssLatencyMs: Long = 100

    private val _ui = MutableStateFlow(ArUiState())
    val ui: StateFlow<ArUiState> = _ui.asStateFlow()

    internal fun updateUi(f: (ArUiState) -> ArUiState) {
        synchronized(_ui) { _ui.value = f(_ui.value) }
    }

    /** Crea la sesión ARCore. Devuelve un mensaje de error o null si quedó lista. */
    fun createSession(): String? {
        if (session != null) return null
        return try {
            val s = Session(context)
            val cfg = Config(s).apply {
                focusMode = Config.FocusMode.AUTO
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            s.configure(cfg)
            session = s
            null
        } catch (e: Exception) {
            val msg = "No se pudo iniciar ARCore: ${e.javaClass.simpleName}${e.message?.let { " — $it" } ?: ""}"
            updateUi { it.copy(error = msg) }
            msg
        }
    }

    fun resume() {
        val s = session ?: return
        try {
            s.resume()
            // Tras una pausa ARCore puede rehacer su marco de referencia: se recalibra por seguridad.
            recalibrate("Cámara reanudada: caminá unos metros para calibrar")
            updateUi { it.copy(running = true, error = null) }
        } catch (e: Exception) {
            updateUi { it.copy(running = false, error = "La cámara no está disponible (${e.javaClass.simpleName})") }
        }
    }

    fun pause() {
        session?.pause()
        updateUi { it.copy(running = false) }
    }

    fun close() {
        session?.close()
        session = null
    }

    fun recalibrate(message: String = AlignmentStatus().message) {
        aligner.reset(message)
        origin = null
        updateUi { it.copy(alignment = aligner.status, headingDeg = null, target = null) }
    }

    /** Cada época del receptor: se empareja con la pose AR del mismo instante. */
    fun onEpoch(e: Epoch, antennaHeight: Double) {
        groundElevationNow = e.altitudeMsl?.minus(antennaHeight)
        if (session == null || !_ui.value.running) return
        val sigma = e.hrms ?: when (e.solution) {
            SolutionType.FIX -> 0.02
            SolutionType.FLOAT -> 0.3
            else -> 3.0
        }
        if (sigma > MAX_SIGMA) {
            updateUi { it.copy(gnssMessage = "Calibración en pausa: se necesita FIX (HRMS ${"%.2f".format(sigma)} m)") }
            return
        }
        var o = origin
        if (o == null || LocalGeodesy.delta(o.first, o.second, e.lat, e.lon).distance > 2000) {
            if (o != null) aligner.reset()
            o = e.lat to e.lon
            origin = o
        }
        val pose = poses.at(e.timeMillis - gnssLatencyMs)
        if (pose == null) {
            updateUi { it.copy(gnssMessage = "Sin tracking AR en este instante") }
            return
        }
        val d = LocalGeodesy.delta(o.first, o.second, e.lat, e.lon)
        val st = aligner.add(AlignSample(pose.x, -pose.z, d.dEast, d.dNorth, max(sigma, 0.01), e.timeMillis))
        updateUi { it.copy(alignment = st, gnssMessage = null) }
    }

    companion object {
        /** HRMS máximo aceptado para calibrar (con FLOAT el rumbo AR sería poco confiable). */
        const val MAX_SIGMA = 0.15
    }
}
