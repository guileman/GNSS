package com.topognss.app.ui.cogo

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.formatDms
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.crs
import com.topognss.app.ui.activePointsFlow
import com.topognss.app.ui.activeProjectFlow
import com.topognss.app.ui.components.CollapsibleCard
import com.topognss.app.ui.components.FramedBlock
import com.topognss.app.ui.components.KeyValue
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.OrderedPointSelector
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.hold
import com.topognss.app.ui.map.TopoMap
import com.topognss.app.ui.map.toMapPoint
import com.topognss.app.ui.topo
import kotlinx.coroutines.launch

enum class CreateMethod(val label: String) {
    MIDPOINT("Punto medio"),
    EXTEND("Prolongación"),
    OFFSET("Estación / desplazamiento"),
}

class CogoViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = topo.repository
    val project = hold(activeProjectFlow(), null)
    val points = hold(activePointsFlow(), emptyList())

    /** Ids seleccionados EN ORDEN. */
    val selected = mutableStateListOf<Long>()

    var method by mutableStateOf(CreateMethod.MIDPOINT)
    var distance by mutableStateOf("")
    var station by mutableStateOf("")
    var offset by mutableStateOf("")
    var offsetRight by mutableStateOf(true)
    var extrapolateGrade by mutableStateOf(false)
    var closePolygon by mutableStateOf(true)
    var newName by mutableStateOf("")
    var newCode by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    fun toggle(id: Long) { if (!selected.remove(id)) selected.add(id) }
    fun clear() = selected.clear()

    fun selectedPoints(all: List<PointEntity>): List<PointEntity> = selected.mapNotNull { id -> all.firstOrNull { it.id == id } }

    fun refreshName() = viewModelScope.launch {
        project.value?.let { newName = repo.nextName(it.id, "C") }
    }

    /** Calcula el punto a crear según el método; null + mensaje si faltan datos. */
    fun preview(sel: List<PointEntity>): Result<P3> = runCatching {
        require(sel.size >= 2) { "Seleccioná 2 puntos base (A y B, en ese orden)" }
        val a = sel[0].toP3(); val b = sel[1].toP3()
        when (method) {
            CreateMethod.MIDPOINT -> Cogo.midpoint(a, b)
            CreateMethod.EXTEND -> {
                val d = parseNum(distance) ?: error("Ingresá la distancia desde B")
                Cogo.extend(a, b, d, extrapolateGrade)
            }
            CreateMethod.OFFSET -> {
                val s = parseNum(station) ?: error("Ingresá la estación (distancia desde A)")
                val o = parseNum(offset.ifBlank { "0" }) ?: error("Desplazamiento inválido")
                Cogo.stationOffset(a, b, s, if (offsetRight) o else -o)
            }
        }
    }

    fun create(sel: List<PointEntity>) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val p = preview(sel).getOrElse { message = it.message; return@launch }
        val name = newName.trim().ifEmpty { repo.nextName(proj.id, "C") }
        val note = "${method.label} de ${sel[0].name}→${sel[1].name}" + when (method) {
            CreateMethod.MIDPOINT -> ""
            CreateMethod.EXTEND -> ": ${parseNum(distance).f(3)} m desde ${sel[1].name}"
            CreateMethod.OFFSET -> {
                val o = parseNum(offset.ifBlank { "0" }) ?: 0.0
                ": est. ${parseNum(station).f(3)} m, desp. ${kotlin.math.abs(o).f(3)} m" +
                    if (o == 0.0) "" else if (offsetRight) " D" else " I"
            }
        }
        runCatching { repo.insertPlane(proj, name, newCode.trim(), p, PointKind.COGO, note) }
            .onSuccess { message = "✔ $name creado"; newName = repo.nextName(proj.id, "C") }
            .onFailure { message = "Error: ${it.message} (¿nombre repetido?)" }
    }
}

fun PointEntity.toP3() = P3(easting, northing, elevation)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CogoScreen(onOpenProjects: () -> Unit, vm: CogoViewModel = viewModel()) {
    val project by vm.project.collectAsState()
    val all by vm.points.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectOnMap by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(project?.id) { vm.refreshName() }

    val proj = project
    if (proj == null) { NoProjectCard(onOpenProjects); return }
    val sel = vm.selectedPoints(all)

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TabRow(selectedTabIndex = tab, modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                icon = { Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("Medición", fontSize = 12.sp) })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                icon = { Icon(Icons.Filled.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text = { Text("Crear puntos", fontSize = 12.sp) })
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (sel.isNotEmpty()) {
                Text(sel.joinToString(" → ") { it.name }, Modifier.weight(1f), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Spacer(Modifier.weight(1f))
            IconButton(onClick = { selectOnMap = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Map, contentDescription = "Mapa", tint = if (selectOnMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { selectOnMap = false }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ListIcon, contentDescription = "Lista", tint = if (!selectOnMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.clear() }, modifier = Modifier.size(32.dp), enabled = sel.isNotEmpty()) {
                Icon(Icons.Filled.Close, contentDescription = "Limpiar selección")
            }
        }

        // El mapa (o la lista de selección) domina el espacio disponible de la pantalla.
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp))) {
            if (selectOnMap) {
                TopoMap(
                    modifier = Modifier.fillMaxSize(),
                    points = all.map { p -> p.toMapPoint(order = vm.selected.indexOf(p.id).takeIf { it >= 0 }?.plus(1)) },
                    line = sel.map { it.lat to it.lon },
                    closeLine = tab == 0 && vm.closePolygon && sel.size >= 3,
                    fitKey = proj.id,
                    onPointTap = { id -> vm.toggle(id) },
                )
            } else {
                OrderedPointSelector(all, vm.selected, vm::toggle, Modifier.fillMaxSize())
            }
        }

        CollapsibleCard(
            title = if (tab == 0) "Resultados" else "Nuevo punto",
            icon = if (tab == 0) Icons.Filled.Straighten else Icons.Filled.AddLocationAlt,
            initiallyExpanded = true,
            summary = if (tab == 0) "${sel.size} pts." else vm.method.label,
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (tab == 0) MeasurementResults(sel, vm, proj.crs().projection.let { tm ->
                    sel.firstOrNull()?.let { tm.pointScale(it.lat, it.lon) }
                })
                else CreatePanel(sel, vm)
            }
        }
    }
}

@Composable
private fun MeasurementResults(sel: List<PointEntity>, vm: CogoViewModel, scale: Double?) {
    if (sel.size < 2) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Elegí 2 o más puntos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    val pts = sel.map { it.toP3() }
    Cogo.traverse(pts).forEach { s ->
        FramedBlock(Modifier.padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${sel[s.from].name} → ${sel[s.to].name}", style = MaterialTheme.typography.titleSmall)
            }
            KeyValue("Dist. horizontal", s.horizontal.f(3) + " m")
            KeyValue("Desnivel Δh", s.deltaZ.f(3) + " m")
            KeyValue("Dist. inclinada", s.slope.f(3) + " m")
            KeyValue("Pendiente", s.gradePercent.f(2) + " %")
            KeyValue("Azimut", formatDms(s.azimuthDeg) + "  (" + s.azimuthDeg.f(4) + "°)")
        }
    }
    if (sel.size > 2) {
        val first = pts.first(); val last = pts.last()
        SectionIcon(Icons.Filled.Flag, "${sel.first().name} → ${sel.last().name}")
        KeyValue("Dist. horizontal directa", Cogo.horizontalDistance(first, last).f(3) + " m")
        KeyValue("Desnivel total", (if (first.z != null && last.z != null) last.z - first.z else null).f(3) + " m")
        KeyValue("Acumulado poligonal", Cogo.traverse(pts).sumOf { it.horizontal }.f(3) + " m")
    }
    if (sel.size >= 3) {
        // Control de alineación: cuánto se aparta cada punto intermedio de la recta primero→último.
        val first = pts.first(); val last = pts.last()
        if (Cogo.horizontalDistance(first, last) > 1e-6) {
            SectionIcon(Icons.Filled.CompareArrows, "Alineación")
            for (i in 1 until pts.size - 1) {
                val (st, off) = Cogo.inverseStationOffset(first, last, pts[i])
                val side = when {
                    kotlin.math.abs(off) < 0.0005 -> "en línea"
                    off > 0 -> "D"
                    else -> "I"
                }
                KeyValue(sel[i].name, "est. ${st.f(3)} m · ${(kotlin.math.abs(off) * 100).f(1)} cm $side")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Filled.Loop, contentDescription = null, modifier = Modifier.size(18.dp))
            Switch(checked = vm.closePolygon, onCheckedChange = { vm.closePolygon = it })
        }
        SectionIcon(Icons.Filled.ChangeHistory, "Ángulos")
        Cogo.vertexAngles(pts, vm.closePolygon).forEach { a ->
            KeyValue(
                sel[a.at].name,
                "${formatDms(a.clockwiseAngleDeg)}  ${if (a.deflectionDeg >= 0) "D" else "I"} ${formatDms(kotlin.math.abs(a.deflectionDeg))}",
            )
        }
        if (vm.closePolygon) {
            Cogo.polygon(pts)?.let { poly ->
                SectionIcon(Icons.Filled.CropFree, "Superficie")
                KeyValue("Área", poly.area.f(3) + " m²  ·  " + (poly.area / 10_000).f(5) + " ha")
                KeyValue("Perímetro", poly.perimeter.f(3) + " m")
                KeyValue("Sentido", if (poly.clockwise) "horario" else "antihorario", mono = false)
            }
        }
    }
    scale?.let { k ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("k = ${k.f(7)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionIcon(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePanel(sel: List<PointEntity>, vm: CogoViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CreateMethod.entries.forEach { m ->
            FilterChip(
                selected = vm.method == m, onClick = { vm.method = m }, label = { Text(m.label) },
                leadingIcon = { Icon(methodIcon(m), contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
    val a = sel.getOrNull(0)?.name ?: "A"
    val b = sel.getOrNull(1)?.name ?: "B"
    when (vm.method) {
        CreateMethod.MIDPOINT -> {}
        CreateMethod.EXTEND -> {
            NumberField(vm.distance, { vm.distance = it }, "Dist. desde $b", suffix = "m")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Terrain, contentDescription = null, modifier = Modifier.size(18.dp))
                Switch(checked = vm.extrapolateGrade, onCheckedChange = { vm.extrapolateGrade = it })
            }
        }
        CreateMethod.OFFSET -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(vm.station, { vm.station = it }, "Estación", Modifier.weight(1f), "m")
                NumberField(vm.offset, { vm.offset = it }, "Desplaz.", Modifier.weight(1f), "m")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !vm.offsetRight, onClick = { vm.offsetRight = false },
                    label = { Text("Izq.") }, leadingIcon = { Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp)) })
                FilterChip(selected = vm.offsetRight, onClick = { vm.offsetRight = true },
                    label = { Text("Der.") }, leadingIcon = { Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
            val o = parseNum(vm.offset.ifBlank { "0" })
            if (o != null && o != 0.0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fuera de $a→$b (paralela)", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    val prev = vm.preview(sel)
    FramedBlock(Modifier.padding(vertical = 6.dp)) {
        prev.onSuccess { p ->
            Text("E ${p.e.f(4)}  N ${p.n.f(4)}  H ${p.z.f(4)}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }.onFailure {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(vm.newName, { vm.newName = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.newCode, { vm.newCode = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Button(
        onClick = { vm.create(sel) }, enabled = prev.isSuccess,
        modifier = Modifier.fillMaxWidth().height(42.dp).padding(top = 6.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Crear")
    }
    vm.message?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
}

private fun methodIcon(m: CreateMethod): ImageVector = when (m) {
    CreateMethod.MIDPOINT -> Icons.Filled.LinearScale
    CreateMethod.EXTEND -> Icons.Filled.TrendingFlat
    CreateMethod.OFFSET -> Icons.Filled.CompareArrows
}
