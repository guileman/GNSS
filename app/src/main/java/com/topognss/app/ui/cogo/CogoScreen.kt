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
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.topognss.app.cogo.HorizontalCurve
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.TraverseLeg
import com.topognss.app.cogo.adjustTraverse
import com.topognss.app.cogo.curveStakeout
import com.topognss.app.cogo.formatDms
import com.topognss.app.cogo.intersectionByAzimuths
import com.topognss.app.cogo.intersectionByDistances
import com.topognss.app.cogo.netArea
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
import com.topognss.app.ui.components.ScreenHeader
import com.topognss.app.ui.components.SectionTitle
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.hold
import com.topognss.app.ui.map.MapPoint
import com.topognss.app.ui.map.PREVIEW_COLOR
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

    // --- Superficie con descuento de edificado ---
    val holeSelected = mutableStateListOf<Long>()
    var showHolePicker by mutableStateOf(false)

    fun toggleHole(id: Long) { if (!holeSelected.remove(id)) holeSelected.add(id) }
    fun clearHole() = holeSelected.clear()

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

/** Lo que se dibuja en el mapa para la herramienta activa: líneas punteadas y puntos en vista previa. */
private data class CogoSketch(
    val dashed: List<List<P3>> = emptyList(),
    val points: List<Pair<String, P3>> = emptyList(),
)

/**
 * Vista previa del cálculo en curso. Las líneas punteadas representan los parámetros:
 * estación y desplazamiento (dos tramos en L), prolongación, visuales de intersección,
 * visuales de la resección, arco y tangentes de la curva, recorrido de la poligonal.
 */
private fun sketchFor(tab: Int, sel: List<PointEntity>, vm: CogoViewModel): CogoSketch = runCatching {
    when (tab) {
        1 -> {
            val p = vm.preview(sel).getOrNull() ?: return@runCatching CogoSketch()
            val a = sel[0].toP3(); val b = sel[1].toP3()
            val dashed = when (vm.method) {
                CreateMethod.MIDPOINT -> listOf(listOf(a, b))
                CreateMethod.EXTEND -> listOf(listOf(b, p))
                CreateMethod.OFFSET -> {
                    // Pie de la perpendicular sobre A→B a la distancia "estación": A → pie → punto
                    val foot = Cogo.stationOffset(a, b, parseNum(vm.station) ?: 0.0, 0.0)
                    listOf(listOf(a, foot), listOf(foot, p))
                }
                CreateMethod.INTERSECTION_AZ, CreateMethod.INTERSECTION_DIST -> listOf(listOf(a, p), listOf(b, p))
            }
            CogoSketch(dashed, listOf(vm.newName.ifBlank { "Nuevo" } to p))
        }
        2 -> {
            val p = vm.resectionResult(sel).getOrNull() ?: return@runCatching CogoSketch()
            CogoSketch(sel.take(3).map { listOf(p, it.toP3()) }, listOf(vm.resectionName.ifBlank { "Estación" } to p))
        }
        3 -> {
            val c = vm.curveResult(sel).getOrNull() ?: return@runCatching CogoSketch()
            val arc = (0..32).map { c.pointAt(c.length * it / 32.0) }
            val stakes = vm.curveStakeList(sel).getOrNull().orEmpty()
                .filter { it.name != (sel.getOrNull(0)?.name ?: "PC") }
                .map { it.name to it.point }
            CogoSketch(listOf(arc, listOf(c.pc, c.pi, c.pt)), stakes.ifEmpty { listOf("PT" to c.pt) })
        }
        4 -> {
            val r = vm.traverseResult(sel).getOrNull() ?: return@runCatching CogoSketch()
            val start = sel[0].toP3()
            val close = if (sel.size >= 2) sel[1].toP3() else start
            val path = listOf(start) + r.vertices.map { it.adjusted } + close
            CogoSketch(listOf(path), r.vertices.map { it.name to it.adjusted })
        }
        else -> CogoSketch()
    }
}.getOrDefault(CogoSketch())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CogoScreen(onOpenProjects: () -> Unit, onBack: () -> Unit, vm: CogoViewModel = viewModel()) {
    val project by vm.project.collectAsState()
    val all by vm.points.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectOnMap by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(project?.id) { vm.refreshName() }

    val proj = project
    if (proj == null) { NoProjectCard(onOpenProjects); return }
    val sel = vm.selectedPoints(all)

    // Cinco herramientas: pestañas desplazables y de una sola línea (antes se partían en medio de la palabra)
    val tabs: @Composable () -> Unit = {
        ScrollableTabRow(
            selectedTabIndex = tab, edgePadding = 4.dp,
            containerColor = Color.Transparent, divider = {},
        ) {
            listOf("Medición", "Crear puntos", "Resección", "Curva", "Poligonal").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1, softWrap = false) })
            }
        }
    }
    val selection: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sel.isEmpty()) "Tocá los puntos en orden" else sel.joinToString(" → ") { it.name },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
            TextButton(onClick = { selectOnMap = !selectOnMap }) { Text(if (selectOnMap) "Lista" else "Mapa") }
            TextButton(onClick = { vm.clear() }) { Text("Limpiar") }
        }
    }
    val glass = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

    // El mapa ocupa la mitad de la pantalla y el encabezado flota encima (como los botones de Levantamiento)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selectOnMap) {
                val tm = proj.crs().projection
                fun ll(p: P3): Pair<Double, Double> = tm.inverse(p.e, p.n).let { it.lat to it.lon }
                val sketch = sketchFor(tab, sel, vm)
                Box(Modifier.fillMaxWidth().height(this@BoxWithConstraints.maxHeight * 0.52f)) {
                    TopoMap(
                        modifier = Modifier.fillMaxSize(),
                        points = all.map { p -> p.toMapPoint(order = vm.selected.indexOf(p.id).takeIf { it >= 0 }?.plus(1)) },
                        line = sel.map { it.lat to it.lon },
                        closeLine = tab == 0 && vm.closePolygon && sel.size >= 3,
                        fitKey = proj.id,
                        onPointTap = { id -> vm.toggle(id) },
                        dashed = sketch.dashed.map { seg -> seg.map(::ll) },
                        previews = sketch.points.map { (n, p) -> ll(p).let { (la, lo) -> MapPoint(-1, n, la, lo, PREVIEW_COLOR) } },
                        controlsTopPadding = 56.dp,
                    )
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(shape = CircleShape, color = glass, shadowElevation = 4.dp) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                        }
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(24.dp), color = glass, shadowElevation = 4.dp) { tabs() }
                    }
                    Surface(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                        shape = RoundedCornerShape(16.dp), color = glass, shadowElevation = 4.dp,
                    ) { selection() }
                }
            } else {
                ScreenHeader(title = "Cálculo", onBack = onBack)
                tabs()
                selection()
                OrderedPointSelector(all, vm.selected, vm::toggle, Modifier.fillMaxWidth().heightIn(max = 260.dp))
            }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (tab == 0) {
                MeasurementResults(sel, all, vm, proj.crs().projection.let { tm ->
                    sel.firstOrNull()?.let { tm.pointScale(it.lat, it.lon) }
                })
            } else if (tab == 1) CreatePanel(sel, vm)
            else if (tab == 2) ResectionPanel(sel, vm)
            else if (tab == 3) CurvePanel(sel, vm)
            else TraversePanel(sel, vm)
        }
        }
    }
}

@Composable
private fun MeasurementResults(sel: List<PointEntity>, all: List<PointEntity>, vm: CogoViewModel, scale: Double?) {
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
            DiscountSection(pts, all, vm)
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
                FilterChip(selected = !vm.offsetRight, onClick = { vm.offsetRight = false }, label = { Text("◀ Izquierda", maxLines = 1) })
                FilterChip(selected = vm.offsetRight, onClick = { vm.offsetRight = true }, label = { Text("Derecha ▶", maxLines = 1) })
            }
        }
        CreateMethod.INTERSECTION_AZ -> {
            Text("Punto donde se cruzan las visuales observadas desde $a y desde $b (azimut hacia el punto buscado).")
            NumberField(vm.azimuthFromA, { vm.azimuthFromA = it }, "Azimut desde $a", Modifier.fillMaxWidth(), "°")
            NumberField(vm.azimuthFromB, { vm.azimuthFromB = it }, "Azimut desde $b", Modifier.fillMaxWidth(), "°")
        }
        CreateMethod.INTERSECTION_DIST -> {
            Text("Punto a la distancia indicada de $a y de $b. Dos soluciones simétricas respecto de la línea $a-$b.")
            NumberField(vm.distFromA, { vm.distFromA = it }, "Distancia desde $a", Modifier.fillMaxWidth(), "m")
            NumberField(vm.distFromB, { vm.distFromB = it }, "Distancia desde $b", Modifier.fillMaxWidth(), "m")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !vm.intersectionRight, onClick = { vm.intersectionRight = false }, label = { Text("◀ Izquierda", maxLines = 1) })
                FilterChip(selected = vm.intersectionRight, onClick = { vm.intersectionRight = true }, label = { Text("Derecha ▶", maxLines = 1) })
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
        OutlinedTextField(vm.newName, { vm.newName = it }, label = { Text("Nombre", maxLines = 1) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(vm.newCode, { vm.newCode = it }, label = { Text("Código", maxLines = 1) }, singleLine = true, modifier = Modifier.weight(1f))
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
    NumberField(vm.angleP1P2, { vm.angleP1P2 = it }, "Ángulo P1–P–P2", Modifier.fillMaxWidth(), "°")
    NumberField(vm.angleP2P3, { vm.angleP2P3 = it }, "Ángulo P2–P–P3", Modifier.fillMaxWidth(), "°")

    val prevResection = vm.resectionResult(sel)
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            prevResection.onSuccess { p ->
                Text("Posición de la estación", style = MaterialTheme.typography.titleSmall)
                Text("E ${p.e.f(4)}\nN ${p.n.f(4)}\nH ${p.z.f(4)}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }.onFailure { Text(it.message ?: "Datos incompletos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    OutlinedTextField(
        vm.resectionName, { vm.resectionName = it }, label = { Text("Nombre", maxLines = 1) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.createResection(sel) }, enabled = prevResection.isSuccess,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("Crear punto de estación") }
    vm.resectionMessage?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
}

/**
 * Superficie con descuento de edificado: permite elegir, aparte de la selección principal
 * (el polígono exterior, p. ej. el terreno), un segundo grupo de puntos que forman el polígono
 * a descontar (p. ej. lo construido), y muestra la planilla de superficie (bruta, edificada, neta).
 */
@Composable
private fun DiscountSection(outer: List<P3>, all: List<PointEntity>, vm: CogoViewModel) {
    val holePts = vm.holeSelected.mapNotNull { id -> all.firstOrNull { it.id == id } }
    SectionTitle("Superficie con descuento")
    TextButton(onClick = { vm.showHolePicker = !vm.showHolePicker }) {
        Text(if (vm.showHolePicker) "Ocultar selección de edificado" else "Elegir polígono a descontar (edificado)")
    }
    if (vm.showHolePicker) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (holePts.isEmpty()) "Tocá en la lista los puntos del polígono a descontar, en orden"
                else holePts.joinToString(" → ") { it.name },
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
            )
            TextButton(onClick = { vm.clearHole() }) { Text("Limpiar") }
        }
        OrderedPointSelector(all, vm.holeSelected, vm::toggleHole, Modifier.fillMaxWidth().heightIn(max = 220.dp))
    }
    if (holePts.size >= 3) {
        val result = netArea(outer, listOf(holePts.map { it.toP3() }))
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Planilla de superficie", style = MaterialTheme.typography.titleSmall)
                KeyValue("Superficie bruta (terreno)", result.grossArea.f(3) + " m²")
                KeyValue("Superficie edificada (a descontar)", result.holeAreas.sum().f(3) + " m²")
                KeyValue("Superficie libre/neta", result.netArea.f(3) + " m²  (${(result.netArea / 10_000).f(5)} ha)")
            }
        }
    }
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
        NumberField(vm.curveDelta, { vm.curveDelta = it }, "Delta (Δ)", Modifier.weight(1f), "°")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !vm.curveTurnRight, onClick = { vm.curveTurnRight = false }, label = { Text("◀ Gira izquierda", maxLines = 1) })
        FilterChip(selected = vm.curveTurnRight, onClick = { vm.curveTurnRight = true }, label = { Text("Gira derecha ▶", maxLines = 1) })
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
        NumberField(vm.curveStartStation, { vm.curveStartStation = it }, "Estación PC", Modifier.weight(1f), "m")
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
        // Un tramo por tarjeta: nombre arriba, azimut y distancia abajo (en una sola fila no entraban las etiquetas)
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tramo ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    OutlinedTextField(
                        vm.legName[i], { vm.legName[i] = it }, label = { Text("Nombre", maxLines = 1) },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.removeLeg(i) }, enabled = vm.legAzimuth.size > 1) {
                        Icon(Icons.Filled.Close, contentDescription = "Quitar tramo")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(vm.legAzimuth[i], { vm.legAzimuth[i] = it }, "Azimut", Modifier.weight(1f), "°")
                    NumberField(vm.legDistance[i], { vm.legDistance[i] = it }, "Distancia", Modifier.weight(1f), "m")
                }
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
