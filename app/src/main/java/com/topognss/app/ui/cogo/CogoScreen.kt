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
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.formatDms
import com.topognss.app.cogo.intersectionByAzimuths
import com.topognss.app.cogo.intersectionByDistances
import com.topognss.app.cogo.resectionTienstra
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
    INTERSECTION_AZ("Intersección (azimuts)"),
    INTERSECTION_DIST("Intersección (distancias)"),
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

    // --- Intersección por azimuts / por distancias ---
    var azimuthFromA by mutableStateOf("")
    var azimuthFromB by mutableStateOf("")
    var distFromA by mutableStateOf("")
    var distFromB by mutableStateOf("")
    var intersectionRight by mutableStateOf(true)

    // --- Resección de 3 puntos (Tienstra) ---
    var angleP1P2 by mutableStateOf("")
    var angleP2P3 by mutableStateOf("")
    var resectionName by mutableStateOf("")
    var resectionMessage by mutableStateOf<String?>(null)

    fun resectionResult(sel: List<PointEntity>): Result<P3> = runCatching {
        require(sel.size >= 3) { "Seleccioná los 3 puntos conocidos, en el orden en que los observás girando el instrumento en sentido horario" }
        val a1 = parseNum(angleP1P2) ?: error("Ingresá el ángulo P1–P–P2")
        val a2 = parseNum(angleP2P3) ?: error("Ingresá el ángulo P2–P–P3")
        resectionTienstra(sel[0].toP3(), sel[1].toP3(), sel[2].toP3(), a1, a2)
            ?: error("Configuración inválida (¿ángulos mal medidos o punto sobre el círculo de los 3 conocidos?)")
    }

    fun createResection(sel: List<PointEntity>) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val p = resectionResult(sel).getOrElse { resectionMessage = it.message; return@launch }
        val name = resectionName.trim().ifEmpty { repo.nextName(proj.id, "C") }
        val note = "Resección (Tienstra) desde ${sel[0].name}, ${sel[1].name}, ${sel[2].name}"
        runCatching { repo.insertPlane(proj, name, "", p, PointKind.COGO, note) }
            .onSuccess { resectionMessage = "✔ $name creado"; resectionName = repo.nextName(proj.id, "C") }
            .onFailure { resectionMessage = "Error: ${it.message} (¿nombre repetido?)" }
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
            CreateMethod.INTERSECTION_AZ -> {
                val az1 = parseNum(azimuthFromA) ?: error("Ingresá el azimut observado desde $a")
                val az2 = parseNum(azimuthFromB) ?: error("Ingresá el azimut observado desde $b")
                intersectionByAzimuths(a, az1, b, az2) ?: error("Las visuales son paralelas: no se cruzan")
            }
            CreateMethod.INTERSECTION_DIST -> {
                val d1 = parseNum(distFromA) ?: error("Ingresá la distancia desde $a")
                val d2 = parseNum(distFromB) ?: error("Ingresá la distancia desde $b")
                intersectionByDistances(a, d1, b, d2, intersectionRight)
                    ?: error("Las distancias no son compatibles con la base $a-$b")
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
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Resección") })
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
            } else if (tab == 1) CreatePanel(sel, vm) else ResectionPanel(sel, vm)
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
        CreateMethod.INTERSECTION_AZ -> {
            Text("Punto donde se cruzan las visuales observadas desde $a y desde $b (azimut hacia el punto buscado).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(vm.azimuthFromA, { vm.azimuthFromA = it }, "Azimut desde $a", Modifier.weight(1f), "°")
                NumberField(vm.azimuthFromB, { vm.azimuthFromB = it }, "Azimut desde $b", Modifier.weight(1f), "°")
            }
        }
        CreateMethod.INTERSECTION_DIST -> {
            Text("Punto a la distancia indicada de $a y de $b. Dos soluciones simétricas respecto de la línea $a-$b.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(vm.distFromA, { vm.distFromA = it }, "Distancia desde $a", Modifier.weight(1f), "m")
                NumberField(vm.distFromB, { vm.distFromB = it }, "Distancia desde $b", Modifier.weight(1f), "m")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !vm.intersectionRight, onClick = { vm.intersectionRight = false }, label = { Text("◀ Izquierda") })
                FilterChip(selected = vm.intersectionRight, onClick = { vm.intersectionRight = true }, label = { Text("Derecha ▶") })
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
 * Resección de 3 puntos (problema de Pothenot, método de Tienstra): "estación libre" con
 * un total station, sin necesidad de medir distancias, solo los dos ángulos horizontales
 * entre los 3 puntos conocidos vistos desde la estación.
 */
@Composable
private fun ResectionPanel(sel: List<PointEntity>, vm: CogoViewModel) {
    SectionTitle("Puntos conocidos (en orden horario visto desde la estación)")
    Text(
        if (sel.size < 3) "Seleccioná P1, P2 y P3, en el orden en que los visás girando el instrumento en sentido horario."
        else "P1=${sel[0].name}  P2=${sel[1].name}  P3=${sel[2].name}",
        style = MaterialTheme.typography.bodySmall,
    )
    SectionTitle("Ángulos horizontales medidos desde la estación")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(vm.angleP1P2, { vm.angleP1P2 = it }, "Ángulo P1–P–P2", Modifier.weight(1f), "°")
        NumberField(vm.angleP2P3, { vm.angleP2P3 = it }, "Ángulo P2–P–P3", Modifier.weight(1f), "°")
    }

    val prev = vm.resectionResult(sel)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            prev.onSuccess { p ->
                Text("Posición de la estación", style = MaterialTheme.typography.titleSmall)
                Text("E ${p.e.f(4)}\nN ${p.n.f(4)}\nH ${p.z.f(4)}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    OutlinedTextField(
        vm.resectionName, { vm.resectionName = it }, label = { Text("Nombre") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.createResection(sel) }, enabled = prev.isSuccess,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Crear punto de estación") }
    vm.resectionMessage?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
}
