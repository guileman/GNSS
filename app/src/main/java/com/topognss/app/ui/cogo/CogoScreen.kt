package com.topognss.app.ui.cogo

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.HorizontalCurve
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.curveStakeout
import com.topognss.app.cogo.formatDms
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.crs
import com.topognss.app.ui.activePointsFlow
import com.topognss.app.ui.activeProjectFlow
import com.topognss.app.ui.components.KeyValue
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.OrderedPointSelector
import com.topognss.app.ui.components.SectionTitle
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

    // --- Curva horizontal circular ---
    var curveManualAzimuth by mutableStateOf("")
    var curveRadius by mutableStateOf("")
    var curveDelta by mutableStateOf("")
    var curveTurnRight by mutableStateOf(true)
    var curveInterval by mutableStateOf("10")
    var curveStartStation by mutableStateOf("0")
    var curveMessage by mutableStateOf<String?>(null)

    /** PC = 1er punto seleccionado. Azimut de tangente: automático (2do punto seleccionado) o manual. */
    fun curveResult(sel: List<PointEntity>): Result<HorizontalCurve> = runCatching {
        require(sel.isNotEmpty()) { "Seleccioná el punto PC (inicio de la curva)" }
        val pc = sel[0].toP3()
        val az = if (sel.size >= 2) Cogo.azimuth(pc, sel[1].toP3()) else {
            parseNum(curveManualAzimuth) ?: error("Seleccioná un 2do punto para la tangente, o ingresá el azimut manualmente")
        }
        val r = parseNum(curveRadius) ?: error("Ingresá el radio")
        val d = parseNum(curveDelta) ?: error("Ingresá el ángulo central (delta)")
        HorizontalCurve(pc, az, r, d, curveTurnRight)
    }

    fun curveStakeList(sel: List<PointEntity>) = curveResult(sel).mapCatching { curve ->
        val interval = parseNum(curveInterval) ?: error("Ingresá el intervalo de estacado")
        require(interval > 0) { "El intervalo debe ser positivo" }
        val start = parseNum(curveStartStation) ?: 0.0
        curveStakeout(curve, interval, start, sel.getOrNull(0)?.name ?: "PC")
    }

    fun saveCurveStakes(sel: List<PointEntity>) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val stakes = curveStakeList(sel).getOrElse { curveMessage = it.message; return@launch }
        var ok = 0
        for (s in stakes) {
            if (s.name == (sel.getOrNull(0)?.name ?: "PC")) continue // PC ya existe como punto
            val name = repo.nextName(proj.id, "E")
            val note = "Curva horizontal, estación ${s.station.f(2)}, deflexión ${s.deflectionDeg.f(3)}°"
            runCatching { repo.insertPlane(proj, name, "", s.point, PointKind.STAKE, note) }.onSuccess { ok++ }
        }
        curveMessage = "✔ $ok puntos de estacado creados (para replantear)"
    }

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
        val note = "${method.label} de ${sel[0].name}→${sel[1].name}"
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

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Medición") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Crear puntos") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Curva") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sel.isEmpty()) "Tocá los puntos en orden" else sel.joinToString(" → ") { it.name },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
            TextButton(onClick = { selectOnMap = !selectOnMap }) { Text(if (selectOnMap) "Lista" else "Mapa") }
            TextButton(onClick = { vm.clear() }) { Text("Limpiar") }
        }
        // Lista de selección (alto acotado) + resultados abajo
        if (selectOnMap) {
            TopoMap(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                points = all.map { p -> p.toMapPoint(order = vm.selected.indexOf(p.id).takeIf { it >= 0 }?.plus(1)) },
                line = sel.map { it.lat to it.lon },
                closeLine = tab == 0 && vm.closePolygon && sel.size >= 3,
                fitKey = proj.id,
                onPointTap = { id -> vm.toggle(id) },
            )
        } else {
            OrderedPointSelector(all, vm.selected, vm::toggle, Modifier.fillMaxWidth().heightIn(max = 260.dp))
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (tab == 0) {
                MeasurementResults(sel, vm, proj.crs().projection.let { tm ->
                    sel.firstOrNull()?.let { tm.pointScale(it.lat, it.lon) }
                })
            } else if (tab == 1) CreatePanel(sel, vm) else CurvePanel(sel, vm)
        }
    }
}

@Composable
private fun MeasurementResults(sel: List<PointEntity>, vm: CogoViewModel, scale: Double?) {
    if (sel.size < 2) { Text("Seleccioná 2 o más puntos para medir."); return }
    val pts = sel.map { it.toP3() }
    SectionTitle("Tramos")
    Cogo.traverse(pts).forEach { s ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text("${sel[s.from].name} → ${sel[s.to].name}", style = MaterialTheme.typography.titleSmall)
                KeyValue("Dist. horizontal", s.horizontal.f(3) + " m")
                KeyValue("Desnivel Δh", s.deltaZ.f(3) + " m")
                KeyValue("Dist. inclinada", s.slope.f(3) + " m")
                KeyValue("Pendiente", s.gradePercent.f(2) + " %")
                KeyValue("Azimut", formatDms(s.azimuthDeg) + "  (" + s.azimuthDeg.f(4) + "°)")
            }
        }
    }
    if (sel.size >= 2) {
        val first = pts.first(); val last = pts.last()
        if (sel.size > 2) {
            SectionTitle("Total ${sel.first().name} → ${sel.last().name}")
            KeyValue("Dist. horizontal directa", Cogo.horizontalDistance(first, last).f(3) + " m")
            KeyValue("Desnivel total", (if (first.z != null && last.z != null) last.z - first.z else null).f(3) + " m")
            KeyValue("Acumulado poligonal", Cogo.traverse(pts).sumOf { it.horizontal }.f(3) + " m")
        }
    }
    if (sel.size >= 3) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.closePolygon, onCheckedChange = { vm.closePolygon = it })
            Text("  Polígono cerrado")
        }
        SectionTitle("Ángulos en vértices")
        Cogo.vertexAngles(pts, vm.closePolygon).forEach { a ->
            KeyValue(
                sel[a.at].name,
                "${formatDms(a.clockwiseAngleDeg)}  defl ${if (a.deflectionDeg >= 0) "D" else "I"} ${formatDms(kotlin.math.abs(a.deflectionDeg))}",
            )
        }
        if (vm.closePolygon) {
            Cogo.polygon(pts)?.let { poly ->
                SectionTitle("Superficie (Gauss)")
                KeyValue("Área", poly.area.f(3) + " m²")
                KeyValue("Área", (poly.area / 10_000).f(5) + " ha")
                KeyValue("Perímetro", poly.perimeter.f(3) + " m")
                KeyValue("Sentido", if (poly.clockwise) "horario" else "antihorario", mono = false)
            }
        }
    }
    scale?.let { k ->
        Text(
            "Valores en el plano de proyección. Factor de escala local k = ${k.f(7)} " +
                "(distancia elipsoidal ≈ plana / k; diferencia ${((1 / k - 1) * 1e6).f(0)} ppm).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePanel(sel: List<PointEntity>, vm: CogoViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CreateMethod.entries.forEach { m ->
            FilterChip(selected = vm.method == m, onClick = { vm.method = m }, label = { Text(m.label) })
        }
    }
    val a = sel.getOrNull(0)?.name ?: "A"
    val b = sel.getOrNull(1)?.name ?: "B"
    when (vm.method) {
        CreateMethod.MIDPOINT -> Text("Punto medio exacto entre $a y $b (E, N y cota promediados).")
        CreateMethod.EXTEND -> {
            Text("Nuevo punto sobre la alineación $a→$b, a la distancia indicada medida desde $b (negativa = hacia $a).")
            NumberField(vm.distance, { vm.distance = it }, "Distancia desde $b", suffix = "m")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.extrapolateGrade, onCheckedChange = { vm.extrapolateGrade = it })
                Text("  Extrapolar pendiente para la cota")
            }
        }
        CreateMethod.OFFSET -> {
            Text("Estación medida desde $a sobre la línea $a→$b, y desplazamiento perpendicular mirando hacia $b.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(vm.station, { vm.station = it }, "Estación", Modifier.weight(1f), "m")
                NumberField(vm.offset, { vm.offset = it }, "Desplaz.", Modifier.weight(1f), "m")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !vm.offsetRight, onClick = { vm.offsetRight = false }, label = { Text("◀ Izquierda") })
                FilterChip(selected = vm.offsetRight, onClick = { vm.offsetRight = true }, label = { Text("Derecha ▶") })
            }
        }
    }

    val prev = vm.preview(sel)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            prev.onSuccess { p ->
                Text("Resultado", style = MaterialTheme.typography.titleSmall)
                Text("E ${p.e.f(4)}\nN ${p.n.f(4)}\nH ${p.z.f(4)}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(vm.newName, { vm.newName = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.newCode, { vm.newCode = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Button(onClick = { vm.create(sel) }, enabled = prev.isSuccess, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Crear punto")
    }
    vm.message?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
}

/**
 * Curva horizontal circular: PC = 1er punto seleccionado; la tangente de entrada se toma
 * automáticamente del 2do punto seleccionado (azimut PC→2do punto) o se ingresa manualmente.
 * Genera el estacado (PC, puntos intermedios cada N metros de arco, PT) listo para replantear.
 */
@Composable
private fun CurvePanel(sel: List<PointEntity>, vm: CogoViewModel) {
    val pcName = sel.getOrNull(0)?.name
    SectionTitle("Tangente de entrada")
    Text(
        when {
            pcName == null -> "Seleccioná el punto PC (inicio de la curva)."
            sel.size >= 2 -> "PC: $pcName   ·   tangente hacia ${sel[1].name}"
            else -> "PC: $pcName"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (pcName != null && sel.size < 2) {
        NumberField(vm.curveManualAzimuth, { vm.curveManualAzimuth = it }, "Azimut de tangente en PC", suffix = "°")
    }

    SectionTitle("Datos de la curva")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(vm.curveRadius, { vm.curveRadius = it }, "Radio", Modifier.weight(1f), "m")
        NumberField(vm.curveDelta, { vm.curveDelta = it }, "Ángulo central (Δ)", Modifier.weight(1f), "°")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !vm.curveTurnRight, onClick = { vm.curveTurnRight = false }, label = { Text("◀ Gira izquierda") })
        FilterChip(selected = vm.curveTurnRight, onClick = { vm.curveTurnRight = true }, label = { Text("Gira derecha ▶") })
    }

    val curveRes = vm.curveResult(sel)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            curveRes.onSuccess { c ->
                Text("Elementos de la curva", style = MaterialTheme.typography.titleSmall)
                KeyValue("Tangente (T)", c.tangentLength.f(3) + " m")
                KeyValue("Desarrollo (L)", c.length.f(3) + " m")
                KeyValue("Externa (E)", c.external.f(3) + " m")
                KeyValue("Ordenada media (M)", c.middleOrdinate.f(3) + " m")
                KeyValue("Cuerda larga", c.longChord.f(3) + " m")
                KeyValue("Azimut de salida (PT)", formatDms(c.outTangentAzimuthDeg))
                KeyValue("PT  E/N", "${c.pt.e.f(3)} / ${c.pt.n.f(3)}")
            }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    SectionTitle("Estacado")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(vm.curveInterval, { vm.curveInterval = it }, "Intervalo", Modifier.weight(1f), "m")
        NumberField(vm.curveStartStation, { vm.curveStartStation = it }, "Estación en PC", Modifier.weight(1f), "m")
    }
    val stakes = vm.curveStakeList(sel)
    stakes.onSuccess { list ->
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                list.forEach { s ->
                    KeyValue(
                        "${s.name}  (${s.station.f(2)})",
                        "E ${s.point.e.f(3)}  N ${s.point.n.f(3)}  ·  cuerda ${s.chordFromPc.f(3)} m  defl ${formatDms(s.deflectionDeg)}",
                    )
                }
            }
        }
        Button(onClick = { vm.saveCurveStakes(sel) }, modifier = Modifier.fillMaxWidth()) {
            Text("Crear puntos de estacado (a replantear)")
        }
    }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    vm.curveMessage?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
}
