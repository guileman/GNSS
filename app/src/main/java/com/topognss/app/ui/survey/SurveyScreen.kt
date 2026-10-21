package com.topognss.app.ui.survey

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.ProjectEntity
import com.topognss.app.data.crs
import com.topognss.app.geo.LocalGeodesy
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.Epoch
import com.topognss.app.gnss.SolutionType
import com.topognss.app.ui.activePointsFlow
import com.topognss.app.ui.activeProjectFlow
import com.topognss.app.ui.components.CollapsibleCard
import com.topognss.app.ui.components.FramedBlock
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.PlayBeepOnTick
import com.topognss.app.ui.components.PointRow
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.hold
import com.topognss.app.ui.map.TopoMap
import com.topognss.app.ui.map.toMapMe
import com.topognss.app.ui.map.toMapPoint
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.topo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

data class MeasureProgress(val accepted: Int, val target: Int, val rejected: Int, val lastReason: String?)

class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = topo.prefs
    private val repo = topo.repository
    val gnss = topo.gnss.state
    val project = hold(activeProjectFlow(), null)
    val recent = hold(activePointsFlow().map { l -> l.filter { it.kind == PointKind.SURVEY.name }.reversed() }, emptyList())

    var epochs by mutableStateOf(prefs.surveyEpochs)
    var maxHrms by mutableStateOf(prefs.surveyMaxHrms.toString())
    var maxVrms by mutableStateOf(prefs.surveyMaxVrms.toString())
    var requireFix by mutableStateOf(prefs.surveyRequireFix)
    var antennaHeight by mutableStateOf(prefs.antennaHeight.toString())
    var name by mutableStateOf("")
    var code by mutableStateOf("")
    var progress by mutableStateOf<MeasureProgress?>(null)
    var message by mutableStateOf<String?>(null)
    var lastSaved by mutableStateOf<PointEntity?>(null)
    /** Se incrementa cada vez que se guarda un punto; la UI lo usa para emitir el sonido de aviso. */
    var savedTick by mutableStateOf(0)
    private var job: Job? = null

    fun refreshName(p: ProjectEntity?) {
        if (p == null) return
        viewModelScope.launch { name = repo.nextName(p.id, prefs.surveyPrefix) }
    }

    fun saveSettings() {
        prefs.surveyEpochs = epochs
        parseNum(maxHrms)?.let { prefs.surveyMaxHrms = it.toFloat() }
        parseNum(maxVrms)?.let { prefs.surveyMaxVrms = it.toFloat() }
        prefs.surveyRequireFix = requireFix
        parseNum(antennaHeight)?.let { prefs.antennaHeight = it.toFloat() }
    }

    /** Motivo de rechazo de una época, o null si cumple los filtros. */
    private fun rejectReason(e: Epoch, hMax: Double?, vMax: Double?): String? = when {
        requireFix && e.solution != SolutionType.FIX -> "solución ${e.solution.label}"
        !requireFix && e.solution.rank < SolutionType.SINGLE.rank -> "sin solución"
        hMax != null && e.hrms != null && e.hrms > hMax -> "HRMS ${e.hrms.f(3)} > ${hMax.f(3)}"
        vMax != null && e.vrms != null && e.vrms > vMax -> "VRMS ${e.vrms.f(3)} > ${vMax.f(3)}"
        else -> null
    }

    /**
     * Toma N épocas que cumplan los filtros (FIX / HRMS / VRMS), las promedia y guarda el punto.
     * Las épocas rechazadas no cortan la medición: se sigue esperando hasta el timeout.
     */
    fun measure() {
        val p = project.value ?: return
        if (job?.isActive == true) return
        if (topo.gnss.state.value.connection != ConnectionStatus.CONNECTED) { message = "Receptor desconectado"; return }
        val pointName = name.trim()
        if (pointName.isEmpty()) { message = "Ingresá un nombre"; return }
        saveSettings()
        val n = epochs.coerceAtLeast(1)
        val hMax = parseNum(maxHrms)
        val vMax = parseNum(maxVrms)
        val antH = parseNum(antennaHeight) ?: 0.0
        message = null

        job = viewModelScope.launch {
            if (repo.nameExists(p.id, pointName)) { message = "Ya existe un punto '$pointName'"; return@launch }
            var rejected = 0
            var accepted = 0
            progress = MeasureProgress(0, n, 0, null)
            val timeoutMs = 20_000L + n * 4_000L
            val list: List<Epoch>? = withTimeoutOrNull(timeoutMs) {
                topo.gnss.epochs
                    .onEach { e ->
                        val r = rejectReason(e, hMax, vMax)
                        if (r != null) rejected++ else accepted++
                        progress = MeasureProgress(accepted.coerceAtMost(n), n, rejected, r ?: progress?.lastReason)
                    }
                    .filter { rejectReason(it, hMax, vMax) == null }
                    .take(n)
                    .toList()
            }
            progress = null
            if (list == null || list.size < n) {
                message = "Tiempo agotado: $accepted de $n épocas válidas ($rejected rechazadas)."
                return@launch
            }
            val pt = average(list, antH, p, pointName)
            runCatching { repo.insertGeographic(p, pt) }
                .onSuccess {
                    lastSaved = pt.copy(id = it)
                    message = "✔ $pointName guardado (${list.size} ép., σH ${pt.hrms.f(3)} m)"
                    name = repo.nextName(p.id, prefs.surveyPrefix.ifEmpty { "P" })
                    savedTick++
                }
                .onFailure { message = "Error al guardar: ${it.message}" }
        }
    }

    fun cancel() {
        job?.cancel()
        progress = null
        message = "Medición cancelada"
    }

    /**
     * Promedio de épocas. A escala de unos centímetros promediar lat/lon en grados es exacto a nivel
     * sub-milimétrico. La dispersión real se calcula en metros (ENU local) y se combina con el σ
     * reportado por el receptor (GST) tomando el mayor de ambos como precisión del punto.
     */
    private fun average(list: List<Epoch>, antH: Double, project: ProjectEntity, pointName: String): PointEntity {
        val lat = list.sumOf { it.lat } / list.size
        val lon = list.sumOf { it.lon } / list.size
        val msl = list.mapNotNull { it.altitudeMsl }.takeIf { it.isNotEmpty() }?.average()
        val hEll = list.mapNotNull { it.ellipsoidalHeight }.takeIf { it.isNotEmpty() }?.average()

        val scatterH = if (list.size > 1) sqrt(list.sumOf {
            val d = LocalGeodesy.delta(lat, lon, it.lat, it.lon); d.dEast * d.dEast + d.dNorth * d.dNorth
        } / (list.size - 1)) else null
        val scatterV = if (list.size > 1 && msl != null) sqrt(list.sumOf {
            val dz = (it.altitudeMsl ?: msl) - msl; dz * dz
        } / (list.size - 1)) else null
        val gstH = list.mapNotNull { it.hrms }.takeIf { it.isNotEmpty() }?.average()
        val gstV = list.mapNotNull { it.vrms }.takeIf { it.isNotEmpty() }?.average()

        val worst = list.minByOrNull { it.solution.rank }!!.solution
        val xy = project.crs().projection.forward(lat, lon)
        return PointEntity(
            projectId = project.id,
            name = pointName,
            code = code.trim(),
            kind = PointKind.SURVEY.name,
            lat = lat, lon = lon,
            easting = xy.east, northing = xy.north,
            elevation = msl?.minus(antH),
            ellipsoidalHeight = hEll?.minus(antH),
            hrms = listOfNotNull(gstH, scatterH).maxOrNull(),
            vrms = listOfNotNull(gstV, scatterV).maxOrNull(),
            pdop = list.mapNotNull { it.pdop }.takeIf { it.isNotEmpty() }?.average(),
            satellites = list.mapNotNull { it.satellites }.minOrNull(),
            solution = worst.label,
            epochs = list.size,
            note = buildString {
                append("ant=${antH.f(3)}")
                scatterH?.let { append(" dispH=${it.f(4)}") }
                scatterV?.let { append(" dispV=${it.f(4)}") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(onOpenProjects: () -> Unit, vm: SurveyViewModel = viewModel()) {
    val project by vm.project.collectAsState()
    val s by vm.gnss.collectAsState()
    val recent by vm.recent.collectAsState()
    var showMap by rememberSaveable { mutableStateOf(true) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(project?.id) { if (vm.name.isEmpty()) vm.refreshName(project) }

    val p = project
    if (p == null) { NoProjectCard(onOpenProjects); return }

    PlayBeepOnTick(vm.savedTick)

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Posición actual en el sistema del proyecto (compacta)
        FramedBlock {
            val xy = if (s.lat != null && s.lon != null) p.crs().projection.forward(s.lat!!, s.lon!!) else null
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Coord("E", (xy?.east).f(3))
                Coord("N", (xy?.north).f(3))
                Coord("H", (s.altitudeMsl?.minus(parseNum(vm.antennaHeight) ?: 0.0)).f(3))
            }
        }

        // Punto a medir: nombre/código, épocas y filtros, todo colapsado
        CollapsibleCard(
            title = "Punto a medir",
            icon = Icons.Filled.Tune,
            summary = "${vm.name.ifBlank { "—" }} · ${vm.epochs} ép.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(vm.name, { vm.name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(vm.code, { vm.code = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 5, 10, 30).forEach { n ->
                    FilterChip(selected = vm.epochs == n, onClick = { vm.epochs = n }, label = { Text("$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                NumberField(vm.maxHrms, { vm.maxHrms = it }, "HRMS máx", Modifier.weight(1f), "m")
                NumberField(vm.maxVrms, { vm.maxVrms = it }, "VRMS máx", Modifier.weight(1f), "m")
                NumberField(vm.antennaHeight, { vm.antennaHeight = it }, "Ant.", Modifier.weight(1f), "m")
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                Switch(checked = vm.requireFix, onCheckedChange = { vm.requireFix = it; vm.saveSettings() })
            }
        }

        val prog = vm.progress
        if (prog == null) {
            val ready = s.connection == ConnectionStatus.CONNECTED && s.hasPosition
            Button(
                onClick = { vm.measure() },
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = if (s.solution == SolutionType.FIX) FixGreen else MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (ready) "MEDIR" else "Sin posición", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${prog.accepted}/${prog.target} · rechazadas ${prog.rejected}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { prog.accepted / prog.target.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { vm.cancel() }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar") }
                }
            }
        }
        vm.message?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("✔")) FixGreen else MaterialTheme.colorScheme.error) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" ${recent.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showMap = true }) { Icon(Icons.Filled.Map, contentDescription = "Mapa", tint = if (showMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { showMap = false }) { Icon(ListIcon, contentDescription = "Lista", tint = if (!showMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // El mapa domina el espacio restante de la pantalla.
        if (showMap) {
            TopoMap(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)),
                points = recent.map { it.toMapPoint() },
                me = s.toMapMe(),
                fitKey = recent.size,
            )
        } else LazyColumn(Modifier.weight(1f)) {
            items(recent, key = { it.id }) { pt ->
                Column {
                    PointRow(pt)
                    Text(
                        "   σH ${pt.hrms.f(3)}  σV ${pt.vrms.f(3)}  ${pt.epochs} ép.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Coord(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}
