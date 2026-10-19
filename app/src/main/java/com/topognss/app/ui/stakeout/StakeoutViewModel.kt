package com.topognss.app.ui.stakeout

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topognss.app.ar.ArStakeController
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.geo.LocalGeodesy
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.sensors.CompassReading
import com.topognss.app.sensors.FusedHeading
import com.topognss.app.sensors.HeadingMode
import com.topognss.app.ui.activePointsFlow
import com.topognss.app.ui.activeProjectFlow
import com.topognss.app.ui.components.f
import com.topognss.app.ui.hold
import com.topognss.app.ui.topo
import com.topognss.app.geo.normalize180
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Navegación hacia el objetivo, en metros. */
data class StakeNav(
    val distance: Double,
    /** Azimut verdadero al objetivo (°). */
    val azimuthDeg: Double,
    val dNorth: Double,
    val dEast: Double,
    /** Ángulo del objetivo respecto de hacia dónde apunta el operador (+ derecha). Null sin rumbo. */
    val relativeDeg: Double?,
    /** Componentes en el marco del operador: + adelante / + derecha. Null sin rumbo. */
    val forward: Double?,
    val right: Double?,
    /** cota_actual − cota_objetivo: + = cortar, − = rellenar. */
    val cutFill: Double?,
)

fun computeNav(s: GnssState, target: PointEntity, heading: Float?, antennaHeight: Double): StakeNav? {
    val lat = s.lat ?: return null
    val lon = s.lon ?: return null
    if (s.connection != ConnectionStatus.CONNECTED) return null
    val d = LocalGeodesy.delta(lat, lon, target.lat, target.lon)
    val rel = heading?.let { normalize180(d.azimuthDeg - it) }
    val rad = rel?.let { Math.toRadians(it) }
    val currentH = s.altitudeMsl?.minus(antennaHeight)
    return StakeNav(
        distance = d.distance,
        azimuthDeg = d.azimuthDeg,
        dNorth = d.dNorth,
        dEast = d.dEast,
        relativeDeg = rel,
        forward = rad?.let { d.distance * cos(it) },
        right = rad?.let { d.distance * sin(it) },
        cutFill = if (currentH != null && target.elevation != null) currentH - target.elevation else null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class StakeoutViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = topo.repository
    val prefs = topo.prefs
    val gnss = topo.gnss.state
    val project = hold(activeProjectFlow(), null)
    val points = hold(activePointsFlow(), emptyList())
    val target = hold(topo.stakeTarget.flatMapLatest { id -> if (id != null) repo.observePoint(id) else flowOf(null) }, null)

    /** La brújula sólo consume batería mientras la pantalla de replanteo está visible (WhileSubscribed). */
    val compass = hold<CompassReading?>(
        topo.compass.readings(position = {
            val s = topo.gnss.state.value
            if (s.lat != null && s.lon != null) Triple(s.lat, s.lon, s.ellipsoidalHeight ?: 0.0) else null
        }),
        null,
    )
    val compassAvailable = topo.compass.isAvailable

    /** Replanteo en realidad aumentada: sesión ARCore + calibración con el RTK. */
    val ar = ArStakeController(app)

    init {
        // Cada época del receptor alimenta la calibración AR (sólo actúa con la cámara AR abierta).
        viewModelScope.launch {
            topo.gnss.epochs.collect { e -> ar.onEpoch(e, prefs.antennaHeight.toDouble()) }
        }
    }

    override fun onCleared() {
        ar.close()
        super.onCleared()
    }

    var headingMode by mutableStateOf(HeadingMode.AUTO)
    var tolerance by mutableStateOf(prefs.stakeTolerance.toDouble())
    var sound by mutableStateOf(prefs.stakeSound)
    var message by mutableStateOf<String?>(null)

    fun selectTarget(p: PointEntity) {
        topo.stakeTarget.value = p.id
        message = null
    }

    /** Siguiente/anterior punto "a replantear" (o de toda la lista si no hay de ese tipo). */
    fun step(delta: Int) {
        val list = points.value.filter { it.kind == PointKind.STAKE.name }.ifEmpty { points.value }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == target.value?.id }
        val next = list[((if (idx < 0) 0 else idx + delta) % list.size + list.size) % list.size]
        selectTarget(next)
    }

    fun updateTolerance(t: Double) { tolerance = t; prefs.stakeTolerance = t.toFloat() }
    fun toggleSound() { sound = !sound; prefs.stakeSound = sound }

    /** Guarda la posición actual como punto replanteado, con los residuos respecto del objetivo. */
    fun record(nav: StakeNav?, heading: FusedHeading) {
        val proj = project.value ?: return
        val t = target.value ?: return
        val s = gnss.value
        if (nav == null || s.lat == null || s.lon == null) { message = "Sin posición"; return }
        val antH = prefs.antennaHeight.toDouble()
        viewModelScope.launch {
            var name = "${t.name}_R"
            var i = 2
            while (repo.nameExists(proj.id, name)) name = "${t.name}_R${i++}"
            val pt = PointEntity(
                projectId = proj.id, name = name, code = t.code, kind = PointKind.STAKED.name,
                lat = s.lat, lon = s.lon, easting = 0.0, northing = 0.0,
                elevation = s.altitudeMsl?.minus(antH), ellipsoidalHeight = s.ellipsoidalHeight?.minus(antH),
                hrms = s.hrms, vrms = s.vrms, pdop = s.pdop, satellites = s.satellites,
                solution = s.solution.label, epochs = 1,
                note = "Replanteo de ${t.name}: dN=${(-nav.dNorth).f(3)} dE=${(-nav.dEast).f(3)} " +
                    "dist=${nav.distance.f(3)} dH=${nav.cutFill.f(3)} rumbo=${heading.source}",
            )
            runCatching { repo.insertGeographic(proj, pt) }
                .onSuccess { message = "✔ Registrado $name (Δ ${nav.distance.f(3)} m)" }
                .onFailure { message = "Error: ${it.message}" }
        }
    }
}
