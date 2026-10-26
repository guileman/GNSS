package com.topognss.app.ui.survey

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.topognss.app.ui.components.StatusChip
import com.topognss.app.data.pointKind
import com.topognss.app.ui.components.KindBadge
import com.topognss.app.ui.theme.solutionColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.PointRow
import com.topognss.app.ui.components.SectionTitle
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.components.rememberNow
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
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

data class MeasureProgress(val accepted: Int, val target: Int, val rejected: Int, val lastReason: String?, val byTime: Boolean = false)

class SurveyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = topo.prefs
    private val repo = topo.repository
    val gnss = topo.gnss.state
    val project = hold(activeProjectFlow(), null)
    val recent = hold(activePointsFlow().map { l -> l.filter { it.kind == PointKind.SURVEY.name || it.kind == PointKind.GCP.name }.reversed() }, emptyList())
    /** Todos los puntos del proyecto (relevados, COGO, a replantear…) para dibujarlos en el mapa. */
    val all = hold(activePointsFlow(), emptyList<PointEntity>())

    var epochs by mutableStateOf(prefs.surveyEpochs)
    var byTime by mutableStateOf(prefs.surveyByTime)
    var seconds by mutableStateOf(prefs.surveySeconds)
    var maxHrms by mutableStateOf(prefs.surveyMaxHrms.toString())
    var maxVrms by mutableStateOf(prefs.surveyMaxVrms.toString())
    var requireFix by mutableStateOf(prefs.surveyRequireFix)
    var antennaHeight by mutableStateOf(prefs.antennaHeight.toString())
    var name by mutableStateOf("")
    /** Tipo del punto que se va a medir: relevado común o GCP para fotogrametría. */
    var kind by mutableStateOf(PointKind.SURVEY)
    var code by mutableStateOf("")
    var progress by mutableStateOf<MeasureProgress?>(null)
    var message by mutableStateOf<String?>(null)
    var lastSaved by mutableStateOf<PointEntity?>(null)
    private var job: Job? = null

    /** Deja el punto como objetivo de Replanteo (la pantalla de replanteo lo toma de acá). */
    fun stake(p: PointEntity) { topo.stakeTarget.value = p.id }

    /** Prefijo del nombre según el tipo: GCP001… para control terrestre, el prefijo configurado para el resto. */
    private fun namePrefix(k: PointKind) = if (k == PointKind.GCP) "GCP" else prefs.surveyPrefix.ifEmpty { "P" }

    fun refreshName(p: ProjectEntity?) {
        if (p == null) return
        viewModelScope.launch { name = repo.nextName(p.id, namePrefix(kind)) }
    }

    fun selectKind(k: PointKind, p: ProjectEntity?) {
        kind = k
        refreshName(p)
    }

    fun saveSettings() {
        prefs.surveyEpochs = epochs
        prefs.surveyByTime = byTime
        prefs.surveySeconds = seconds
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
        val pointKind = kind
        if (pointName.isEmpty()) { message = "Ingresá un nombre"; return }
        saveSettings()
        val n = epochs.coerceAtLeast(1)
        val byTime = byTime
        val secs = seconds.coerceAtLeast(1)
        val target = if (byTime) secs else n
        val hMax = parseNum(maxHrms)
        val vMax = parseNum(maxVrms)
        val antH = parseNum(antennaHeight) ?: 0.0
        message = null

        job = viewModelScope.launch {
            if (repo.nameExists(p.id, pointName)) { message = "Ya existe un punto '$pointName'"; return@launch }
            var rejected = 0
            var accepted = 0
            var startAt = 0L
            progress = MeasureProgress(0, target, 0, null, byTime)
            // Por segundos, el reloj arranca con la primera época válida; el tope incluye la espera del FIX.
            val timeoutMs = if (byTime) 20_000L + secs * 2_000L else 20_000L + n * 4_000L
            val list: List<Epoch>? = withTimeoutOrNull(timeoutMs) {
                topo.gnss.epochs
                    .onEach { e ->
                        val r = rejectReason(e, hMax, vMax)
                        if (r != null) rejected++ else {
                            accepted++
                            if (startAt == 0L) startAt = System.currentTimeMillis()
                        }
                        val shown = if (byTime) ((System.currentTimeMillis() - startAt) / 1000).toInt().takeIf { startAt > 0L } ?: 0 else accepted
                        progress = MeasureProgress(shown.coerceAtMost(target), target, rejected, r ?: progress?.lastReason, byTime)
                    }
                    .filter { rejectReason(it, hMax, vMax) == null }
                    .let { f ->
                        if (byTime) f.transformWhile { e -> emit(e); System.currentTimeMillis() - startAt < secs * 1000L }
                        else f.take(n)
                    }
                    .toList()
            }
            progress = null
            if (list == null || list.isEmpty() || (!byTime && list.size < n)) {
                message = if (byTime) "Tiempo agotado: $accepted épocas válidas ($rejected rechazadas)."
                else "Tiempo agotado: $accepted de $n épocas válidas ($rejected rechazadas)."
                return@launch
            }
            val pt = average(list, antH, p, pointName, pointKind)
            runCatching { repo.insertGeographic(p, pt) }
                .onSuccess {
                    lastSaved = pt.copy(id = it)
                    message = "✔ $pointName guardado (${list.size} ép., σH ${pt.hrms.f(3)} m)"
                    name = repo.nextName(p.id, namePrefix(pointKind))
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
    private fun average(list: List<Epoch>, antH: Double, project: ProjectEntity, pointName: String, pointKind: PointKind): PointEntity {
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
            kind = pointKind.name,
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

/**
 * Levantamiento (estilo Emlid Flow): el mapa ocupa toda la pantalla; "+" abre el panel para medir.
 * Botones flotantes en el mapa: Cálculo y Puntos a la izquierda, Replantear a la derecha. Arriba, el estado
 * de la conexión con el receptor es un icono que abre la pantalla Receptores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    onOpenProjects: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenCogo: () -> Unit,
    onOpenStakeout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit,
    vm: SurveyViewModel = viewModel(),
) {
    val project by vm.project.collectAsState()
    val s by vm.gnss.collectAsState()
    val all by vm.all.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    // Punto tocado en el mapa: muestra su ficha; la flecha flotante lo replantea
    var tappedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tapped = all.firstOrNull { it.id == tappedId }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(project?.id) { if (vm.name.isEmpty()) vm.refreshName(project) }

    val p = project
    if (p == null) { NoProjectCard(onOpenProjects); return }

    val connected = s.connection == ConnectionStatus.CONNECTED
    val ready = connected && s.hasPosition
    val now = rememberNow()
    val connColor = solutionColor(s, connected && s.isStale(now))
    val prog = vm.progress

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            TopoMap(
                modifier = Modifier.fillMaxSize(),
                points = all.map { it.toMapPoint() },
                me = s.toMapMe(),
                fitKey = p.id to all.size,
                onPointTap = { id -> tappedId = id; panelOpen = false },
            )

            // Proyecto activo (toca para cambiar) + Ajustes, arriba a la izquierda
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = onOpenProjects,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp),
                        )
                    }
                }
                Surface(
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ajustes", Modifier.padding(10.dp).size(22.dp))
                }
                // Estado de la conexión: el punto de color es la solución (verde FIX, amarillo FLOAT, rojo sin datos)
                Surface(
                    onClick = onOpenConnection,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Box(Modifier.padding(10.dp).size(22.dp)) {
                        Icon(Icons.Filled.SatelliteAlt, contentDescription = "Receptor: ${if (connected) s.solution.label else "sin conexión"}", Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.TopEnd).size(10.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.5.dp)
                                .background(connColor, CircleShape)
                        )
                    }
                }
            }

            // Atajos + "+" + chips de estado, abajo del mapa
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MapRoundButton(Icons.Filled.Straighten, "Cálculo", onOpenCogo)
                        MapRoundButton(Icons.AutoMirrored.Filled.List, "Puntos", onOpenPoints)
                    }
                    MapRoundButton(
                        Icons.Filled.NearMe, "Replantear",
                        { tapped?.let { vm.stake(it); tappedId = null }; onOpenStakeout() },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                    if (!panelOpen) {
                        FloatingActionButton(
                            onClick = { panelOpen = true; tappedId = null },
                            modifier = Modifier.align(Alignment.BottomCenter),
                            shape = CircleShape,
                            containerColor = Color(0xFF263238),
                            contentColor = Color.White,
                        ) { Icon(Icons.Filled.Add, contentDescription = "Medir punto") }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).clickable { showSettings = true },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.background(Color(0xFF37474F), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes de medición", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    if (ready) StatusChip(s.solution.label, container = solutionColor(s.solution))
                    else StatusChip("Sin posición", container = Color(0xFF546E7A))
                    StatusChip("H ${s.hrms.f(3)}  V ${s.vrms.f(3)}")
                    StatusChip("Pole ${parseNum(vm.antennaHeight).f(3)}")
                    StatusChip(if (vm.byTime) "${vm.seconds} s" else "${vm.epochs} ép.")
                }
            }
        }

        if (tapped != null && !panelOpen) {
            Surface(
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tapped.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                KindBadge(tapped.pointKind())
                            }
                            if (tapped.code.isNotBlank()) {
                                Text(tapped.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                "E ${tapped.easting.f(3)}  N ${tapped.northing.f(3)}  H ${tapped.elevation.f(3)}",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { tappedId = null }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    }
                }
            }
        }

        if (panelOpen) {
            Surface(
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nuevo punto", style = MaterialTheme.typography.titleMedium)
                            val xy = if (s.lat != null && s.lon != null) p.crs().projection.forward(s.lat!!, s.lon!!) else null
                            val h = s.altitudeMsl?.minus(parseNum(vm.antennaHeight) ?: 0.0)
                            Text(
                                "E ${(xy?.east).f(3)}  N ${(xy?.north).f(3)}  H ${h.f(3)}",
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { panelOpen = false }) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
                    }

                    if (prog == null) {
                        Button(
                            onClick = { vm.measure() },
                            enabled = ready,
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (s.solution == SolutionType.FIX) FixGreen else MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Medir", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                if (!ready) {
                                    Text(
                                        if (!connected) "No hay ningún receptor conectado" else "Esperando posición…",
                                        fontSize = 12.sp, maxLines = 1,
                                    )
                                }
                            }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (prog.byTime) "Midiendo ${prog.accepted}/${prog.target} s · rechazadas ${prog.rejected}" else "Midiendo ${prog.accepted}/${prog.target} épocas · rechazadas ${prog.rejected}", fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(progress = { prog.accepted / prog.target.toFloat() }, modifier = Modifier.fillMaxWidth())
                                prog.lastReason?.let { Text("Último rechazo: $it", style = MaterialTheme.typography.bodySmall) }
                                OutlinedButton(onClick = { vm.cancel() }) { Text("Cancelar") }
                            }
                        }
                    }
                    vm.message?.let { Text(it, color = if (it.startsWith("✔")) FixGreen else MaterialTheme.colorScheme.error) }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tipo", Modifier.padding(end = 4.dp))
                        FilterChip(selected = vm.kind == PointKind.SURVEY, onClick = { vm.selectKind(PointKind.SURVEY, p) }, label = { Text("Punto", maxLines = 1) })
                        FilterChip(selected = vm.kind == PointKind.GCP, onClick = { vm.selectKind(PointKind.GCP, p) }, label = { Text("GCP", maxLines = 1) })
                    }
                    if (vm.kind == PointKind.GCP) {
                        Text(
                            "Control terrestre para fotogrametría." + if (!vm.requireFix) " Conviene exigir solución FIX en los ajustes de medición." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(vm.name, { vm.name = it }, label = { Text("Nombre", maxLines = 1) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(vm.code, { vm.code = it }, label = { Text("Descripción", maxLines = 1) }, singleLine = true, modifier = Modifier.weight(1.4f))
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Ajustes de medición") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(vm.antennaHeight, { vm.antennaHeight = it }, "Altura del pole", Modifier.fillMaxWidth(), "m")
                    Text(
                        "Se resta a la cota recibida. Ponela en 0 si ya la configuraste en el receptor.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SectionTitle("Tiempo de medición")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !vm.byTime, onClick = { vm.byTime = false }, label = { Text("Épocas", maxLines = 1) })
                        FilterChip(selected = vm.byTime, onClick = { vm.byTime = true }, label = { Text("Segundos", maxLines = 1) })
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (vm.byTime) listOf(5, 10, 15, 30, 60).forEach { n ->
                            FilterChip(selected = vm.seconds == n, onClick = { vm.seconds = n }, label = { Text("$n s", maxLines = 1) })
                        } else listOf(1, 5, 10, 30, 60).forEach { n ->
                            FilterChip(selected = vm.epochs == n, onClick = { vm.epochs = n }, label = { Text("$n", maxLines = 1) })
                        }
                    }
                    SectionTitle("Filtros de calidad")
                    NumberField(vm.maxHrms, { vm.maxHrms = it }, "HRMS máx", Modifier.fillMaxWidth(), "m")
                    NumberField(vm.maxVrms, { vm.maxVrms = it }, "VRMS máx", Modifier.fillMaxWidth(), "m")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = vm.requireFix, onCheckedChange = { vm.requireFix = it })
                        Text("  Exigir solución FIX", Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveSettings(); showSettings = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MapRoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Solo ícono (sin etiqueta); el nombre queda como descripción de accesibilidad
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { Icon(icon, contentDescription = label) }
}
