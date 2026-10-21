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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.TraverseLeg
import com.topognss.app.cogo.adjustTraverse
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

    // --- Poligonal con ajuste (Bowditch) ---
    val legAzimuth = mutableStateListOf("")
    val legDistance = mutableStateListOf("")
    val legName = mutableStateListOf("")
    var traverseClosedLoop by mutableStateOf(true)
    var traverseMessage by mutableStateOf<String?>(null)

    init {
        addLeg(); addLeg()
    }

    fun addLeg() {
        legAzimuth.add(""); legDistance.add(""); legName.add("V${legAzimuth.size}")
    }

    fun removeLeg(i: Int) {
        if (legAzimuth.size <= 1) return
        legAzimuth.removeAt(i); legDistance.removeAt(i); legName.removeAt(i)
    }

    /** Punto de partida = primer punto seleccionado; punto de cierre = segundo, o el mismo si [traverseClosedLoop]. */
    fun traverseResult(sel: List<PointEntity>): Result<com.topognss.app.cogo.TraverseAdjustment> = runCatching {
        require(sel.isNotEmpty()) { "Seleccioná el punto de partida conocido" }
        val start = sel[0].toP3()
        val known = when {
            sel.size >= 2 -> sel[1].toP3()
            traverseClosedLoop -> start
            else -> error("Seleccioná también el punto de cierre, o activá \"cierra en el mismo punto\"")
        }
        val legs = legAzimuth.indices.map { i ->
            val az = parseNum(legAzimuth[i]) ?: error("Tramo ${i + 1}: falta el azimut")
            val d = parseNum(legDistance[i]) ?: error("Tramo ${i + 1}: falta la distancia")
            require(d > 0) { "Tramo ${i + 1}: la distancia debe ser positiva" }
            TraverseLeg(legName[i].ifBlank { "V${i + 1}" }, az, d)
        }
        adjustTraverse(start, legs, known)
    }

    fun saveTraverse(sel: List<PointEntity>) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val res = traverseResult(sel).getOrElse { traverseMessage = it.message; return@launch }
        var ok = 0
        for (v in res.vertices) {
            val name = v.name.trim().ifEmpty { repo.nextName(proj.id, "V") }
            val note = "Poligonal ajustada (Bowditch) desde ${sel[0].name}, cierre ${res.closureLinear.f(3)} m"
            runCatching { repo.insertPlane(proj, name, "", v.adjusted, PointKind.COGO, note) }.onSuccess { ok++ }
        }
        traverseMessage = if (ok == res.vertices.size) {
            val prec = if (res.precisionDenominator.isInfinite()) "∞" else "1 : ${res.precisionDenominator.toLong()}"
            "✔ $ok vértices creados · precisión $prec"
        } else {
            "Se crearon $ok de ${res.vertices.size} vértices (¿nombres repetidos?)"
        }
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
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Poligonal") })
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
            } else if (tab == 1) CreatePanel(sel, vm) else TraversePanel(sel, vm)
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
 * Poligonal con ajuste por mínimos cuadrados (regla de Bowditch/compás).
 *
 * El punto de partida es el 1er punto seleccionado (conocido). Los tramos se ingresan
 * manualmente como azimut + distancia (p. ej. tomados con estación total u otro instrumento).
 * El cierre es el 2do punto seleccionado, o el mismo punto de partida si la poligonal es cerrada.
 */
@Composable
private fun TraversePanel(sel: List<PointEntity>, vm: CogoViewModel) {
    val start = sel.getOrNull(0)?.name
    val known = sel.getOrNull(1)?.name
    SectionTitle("Puntos de control")
    Text(
        when {
            start == null -> "Seleccioná el punto de partida conocido (y, opcionalmente, el de cierre)."
            known != null -> "Partida: $start   ·   Cierre: $known"
            vm.traverseClosedLoop -> "Partida y cierre: $start (poligonal cerrada)"
            else -> "Partida: $start   ·   falta el punto de cierre"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (known == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.traverseClosedLoop, onCheckedChange = { vm.traverseClosedLoop = it })
            Text("  Cierra en el mismo punto de partida")
        }
    }

    SectionTitle("Tramos observados (azimut + distancia)")
    vm.legAzimuth.indices.forEach { i ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.legName[i], { vm.legName[i] = it }, label = { Text("Nombre") },
                singleLine = true, modifier = Modifier.weight(0.8f),
            )
            Spacer(Modifier.width(4.dp))
            NumberField(vm.legAzimuth[i], { vm.legAzimuth[i] = it }, "Azimut", Modifier.weight(1f), "°")
            Spacer(Modifier.width(4.dp))
            NumberField(vm.legDistance[i], { vm.legDistance[i] = it }, "Distancia", Modifier.weight(1f), "m")
            IconButton(onClick = { vm.removeLeg(i) }, enabled = vm.legAzimuth.size > 1) {
                Icon(Icons.Filled.Close, contentDescription = "Quitar tramo")
            }
        }
    }
    TextButton(onClick = { vm.addLeg() }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(" Agregar tramo")
    }

    val res = vm.traverseResult(sel)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            res.onSuccess { adj ->
                Text("Error de cierre", style = MaterialTheme.typography.titleSmall)
                KeyValue("ΔE / ΔN", "${adj.closureE.f(3)} m / ${adj.closureN.f(3)} m")
                KeyValue("Cierre lineal", adj.closureLinear.f(4) + " m")
                KeyValue("Perímetro", adj.perimeter.f(3) + " m")
                KeyValue(
                    "Precisión",
                    if (adj.precisionDenominator.isInfinite()) "exacto" else "1 : ${adj.precisionDenominator.toLong()}",
                )
                SectionTitle("Vértices ajustados")
                adj.vertices.forEach { v ->
                    KeyValue(v.name, "E ${v.adjusted.e.f(3)}  N ${v.adjusted.n.f(3)}")
                }
            }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Button(onClick = { vm.saveTraverse(sel) }, enabled = res.isSuccess, modifier = Modifier.fillMaxWidth()) {
        Text("Crear vértices ajustados")
    }
    vm.traverseMessage?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
}
