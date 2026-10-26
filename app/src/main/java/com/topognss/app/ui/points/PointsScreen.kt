package com.topognss.app.ui.points

import com.topognss.app.export.GcpExporter
import com.topognss.app.export.GcpPoint
import com.topognss.app.geo.ProjectCrs
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import com.topognss.app.ui.components.ScreenHeader
import com.topognss.app.ui.components.EmptyState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.cogo.P3
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.TopoRepository
import com.topognss.app.data.crs
import com.topognss.app.importer.ImportFormat
import com.topognss.app.data.pointKind
import com.topognss.app.export.DxfPoint
import com.topognss.app.export.DxfExporter
import com.topognss.app.importer.PointCsv
import com.topognss.app.importer.CsvOrder
import com.topognss.app.importer.detectFormat
import com.topognss.app.importer.parseDxf
import com.topognss.app.importer.parseKml
import com.topognss.app.importer.queryDisplayName
import com.topognss.app.importer.readImportableText
import com.topognss.app.report.buildStakeoutPdf
import com.topognss.app.ui.activePointsFlow
import com.topognss.app.ui.activeProjectFlow
import com.topognss.app.ui.components.KeyValue
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.PointRow
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.hold
import com.topognss.app.ui.map.TopoMap
import com.topognss.app.ui.map.toMapMe
import com.topognss.app.ui.map.toMapPoint
import com.topognss.app.ui.topo
import kotlinx.coroutines.launch
import java.io.File

class PointsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = topo.repository
    val project = hold(activeProjectFlow(), null)
    val points = hold(activePointsFlow(), emptyList())
    val gnss = topo.gnss.state
    var filter by mutableStateOf<PointKind?>(null)
    var message by mutableStateOf<String?>(null)

    fun delete(p: PointEntity) = viewModelScope.launch { repo.deletePoint(p) }

    fun rename(p: PointEntity, name: String, code: String) = viewModelScope.launch {
        runCatching { repo.updatePoint(p.copy(name = name.trim(), code = code.trim())) }
            .onFailure { message = "No se pudo renombrar (¿nombre repetido?)" }
    }

    fun stake(p: PointEntity) { topo.stakeTarget.value = p.id }

    /** Crea un archivo nuevo en el almacenamiento propio de la app para la foto de [p], y su Uri de FileProvider. */
    fun newPhotoFile(p: PointEntity): File {
        val dir = File(topo.filesDir, "photos").apply { mkdirs() }
        return File(dir, "${p.id}_${System.currentTimeMillis()}.jpg")
    }

    fun setPhoto(p: PointEntity, path: String?) = viewModelScope.launch {
        // Si había una foto anterior distinta, se borra para no acumular archivos huérfanos.
        if (p.photoPath != null && p.photoPath != path) runCatching { File(p.photoPath).delete() }
        runCatching { repo.setPhoto(p, path) }
    }

    /** Alta manual de un punto a replantear (por coordenadas planas del proyecto). */
    fun addManual(name: String, code: String, e: Double, n: Double, h: Double?) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        runCatching { repo.insertPlane(proj, name.trim(), code.trim(), P3(e, n, h), PointKind.STAKE) }
            .onSuccess { message = "Punto $name agregado" }
            .onFailure { message = "Error: ${it.message}" }
    }

    /** Archivo CSV/TXT leído, a la espera de que el usuario confirme columnas y tipo en el diálogo. */
    var csvPending by mutableStateOf<Pair<String, String>?>(null)

    /** Importa puntos desde un DXF (planas), un KML/KMZ (geográficas) o un CSV/TXT de coordenadas. */
    fun importFile(uri: android.net.Uri, fileName: String?) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val format = detectFormat(fileName)
        val text = runCatching { readImportableText(topo, uri, fileName) }.getOrNull()
        if (text.isNullOrBlank()) { message = "No se pudo leer el archivo"; return@launch }
        if (format == null) {
            // CSV, TXT o extensión desconocida: se trata como planilla de coordenadas
            csvPending = (fileName ?: "archivo") to text
            return@launch
        }

        var count = 0
        when (format) {
            ImportFormat.DXF -> for (v in parseDxf(text)) {
                val name = repo.nextName(proj.id, "IMP")
                runCatching {
                    repo.insertPlane(proj, name, "", v.p, PointKind.COGO, note = "Importado de ${fileName ?: "DXF"}")
                }.onSuccess { count++ }
            }
            ImportFormat.KML -> for (v in parseKml(text)) {
                val name = repo.nextName(proj.id, "IMP")
                val base = PointEntity(
                    projectId = proj.id, name = name, code = "", kind = PointKind.COGO.name,
                    lat = v.lat, lon = v.lon, easting = 0.0, northing = 0.0, elevation = v.alt,
                    note = "Importado de ${fileName ?: "KML"}",
                )
                runCatching { repo.insertGeographic(proj, base) }.onSuccess { count++ }
            }
        }
        message = if (count > 0) "Se importaron $count puntos (prefijo IMP)" else "No se encontraron puntos para importar en el archivo"
    }

    /**
     * Importa las filas del CSV. Nombres del archivo cuando los hay (si se repiten se agrega _2, _3…),
     * si no IMP001, IMP002… La descripción va al código del punto.
     */
    fun importCsv(text: String, fileName: String, order: CsvOrder, kind: PointKind) = viewModelScope.launch {
        val proj = project.value ?: return@launch
        val parsed = PointCsv.parse(text, order)
        var ok = 0
        for (r in parsed.points) {
            var name = r.name?.trim()?.takeIf { it.isNotEmpty() } ?: repo.nextName(proj.id, "IMP")
            if (repo.nameExists(proj.id, name)) {
                var i = 2
                while (repo.nameExists(proj.id, "${name}_$i")) i++
                name = "${name}_$i"
            }
            val note = "Importado de $fileName"
            val res = if (r.isGeographic) {
                runCatching {
                    repo.insertGeographic(
                        proj,
                        PointEntity(
                            projectId = proj.id, name = name, code = r.desc, kind = kind.name,
                            lat = r.lat!!, lon = r.lon!!, easting = 0.0, northing = 0.0, elevation = r.z, note = note,
                        ),
                    )
                }
            } else {
                runCatching { repo.insertPlane(proj, name, r.desc, P3(r.e!!, r.n!!, r.z), kind, note) }
            }
            if (res.isSuccess) ok++
        }
        csvPending = null
        message = "Se importaron $ok puntos" + if (parsed.skipped > 0) " (${parsed.skipped} líneas sin coordenadas válidas)" else ""
    }

    /**
     * DXF con un POINT 3D por punto (capa por tipo) y etiquetas de nombre, descripción y tipo en capas
     * NOMBRE / DESCRIPCION / TIPO. Respeta el filtro de tipo activo en la lista.
     */
    fun exportDxf(list: List<PointEntity>): Intent? {
        val proj = project.value ?: return null
        val dir = File(topo.cacheDir, "export").apply { mkdirs() }
        val safe = proj.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${safe}_puntos.dxf")
        val pts = list.map { p ->
            val k = p.pointKind()
            DxfPoint(
                name = p.name, description = p.code, type = k.label,
                typeLayer = when (k) {
                    PointKind.SURVEY -> "RELEVADO"; PointKind.COGO -> "COGO"
                    PointKind.STAKE -> "A_REPLANTEAR"; PointKind.STAKED -> "REPLANTEADO"
                    PointKind.GCP -> "GCP"
                },
                e = p.easting, n = p.northing, z = p.elevation,
            )
        }
        // DXF R12 en ANSI 1252 (acentos legibles en AutoCAD)
        file.writeBytes(DxfExporter.build(pts).toByteArray(charset("windows-1252")))
        val uri = FileProvider.getUriForFile(topo, "${topo.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/dxf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Puntos ${proj.name} (DXF)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Puntos GCP del proyecto (se exportan aparte, sin importar el filtro de la lista). */
    fun gcpPoints(): List<PointEntity> = points.value.filter { it.kind == PointKind.GCP.name }

    /** Lista de GCP en el formato elegido, lista para cargar en WebODM / programas de fotogrametría. */
    fun exportGcp(format: GcpExporter.Format): Intent? {
        val proj = project.value ?: return null
        val gcps = gcpPoints().map { GcpPoint(it.name, it.easting, it.northing, it.elevation) }
        if (gcps.isEmpty()) return null
        val dir = File(topo.cacheDir, "export").apply { mkdirs() }
        val safe = proj.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val suffix = if (format == GcpExporter.Format.WEBODM_GCPI) "GCP_webodm" else "GCP"
        val file = File(dir, "${safe}_$suffix.${format.extension}")
        file.writeText(GcpExporter.build(format, proj.crs(), gcps))
        val uri = FileProvider.getUriForFile(topo, "${topo.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GCP ${proj.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun exportCsv(): Intent? {
        val proj = project.value ?: return null
        val dir = File(topo.cacheDir, "export").apply { mkdirs() }
        val safe = proj.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${safe}_puntos.csv")
        file.writeText(TopoRepository.toCsv(proj, points.value))
        val uri = FileProvider.getUriForFile(topo, "${topo.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Puntos ${proj.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Reporte PDF de replanteo (puntos "Replanteado" con sus residuos). */
    fun exportStakeoutPdf(): Intent? {
        val proj = project.value ?: return null
        val file = buildStakeoutPdf(topo, proj, points.value)
        val uri = FileProvider.getUriForFile(topo, "${topo.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Reporte de replanteo ${proj.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsScreen(onOpenProjects: () -> Unit, onStake: () -> Unit, onBack: () -> Unit, vm: PointsViewModel = viewModel()) {
    val context = LocalContext.current
    val project by vm.project.collectAsState()
    val all by vm.points.collectAsState()
    var detail by remember { mutableStateOf<PointEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showGcp by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFile(it, queryDisplayName(context, it)) }
    }

    val p = project
    if (p == null) { NoProjectCard(onOpenProjects); return }
    val shown = all.filter { vm.filter == null || it.kind == vm.filter!!.name }
    if (showGcp) {
        GcpExportDialog(
            crs = p.crs(),
            gcps = all.filter { it.kind == PointKind.GCP.name },
            onExport = { f ->
                showGcp = false
                vm.exportGcp(f)?.let { context.startActivity(Intent.createChooser(it, "Exportar GCP")) }
            },
            onDismiss = { showGcp = false },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Puntos", onBack = onBack) {
                // Importar / exportar quedan en un menú en vez de tres botones apretados en la cabecera
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Importar (CSV, TXT, DXF, KML)") },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar DXF") },
                            onClick = {
                                menuOpen = false
                                vm.exportDxf(shown)?.let { context.startActivity(Intent.createChooser(it, "Exportar DXF")) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar CSV") },
                            onClick = {
                                menuOpen = false
                                vm.exportCsv()?.let { context.startActivity(Intent.createChooser(it, "Exportar CSV")) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar GCP (fotogrametría)") },
                            onClick = { menuOpen = false; showGcp = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Reporte de replanteo (PDF)") },
                            onClick = {
                                menuOpen = false
                                vm.exportStakeoutPdf()?.let { context.startActivity(Intent.createChooser(it, "Reporte de replanteo")) }
                            },
                        )
                    }
                }
            }
            Text(
                "${p.name} · ${p.crs().describe()}",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (all.isNotEmpty()) {
                // Sólo los tipos que existen, para no llenar la fila de filtros vacíos
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("Todos (${all.size})", maxLines = 1) })
                    PointKind.entries.forEach { k ->
                        val c = all.count { it.kind == k.name }
                        if (c > 0 || vm.filter == k) {
                            FilterChip(selected = vm.filter == k, onClick = { vm.filter = k }, label = { Text("${k.label} ($c)", maxLines = 1) })
                        }
                    }
                }
            }
            vm.message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.secondary) }
            if (shown.isEmpty()) {
                EmptyState(
                    title = if (all.isEmpty()) "Todavía no hay puntos" else "No hay puntos de este tipo",
                    message = if (all.isEmpty()) "Medí un punto desde Levantamiento, importá un archivo o agregá uno por coordenadas con el botón +."
                    else "Probá con otro filtro.",
                    modifier = Modifier.weight(1f),
                )
            } else LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { pt ->
                    PointRow(pt, Modifier.clickable { detail = pt })
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "Agregar punto") }
    }

    detail?.let { pt ->
        // Siempre la versión más actualizada del punto (p. ej. después de guardar la foto).
        val live = all.firstOrNull { it.id == pt.id } ?: pt
        PointDetailDialog(
            live,
            onDismiss = { detail = null },
            onStake = { vm.stake(live); detail = null; onStake() },
            onDelete = { vm.delete(live); detail = null },
            onRename = { n, c -> vm.rename(live, n, c); detail = null },
            onNewPhotoFile = { vm.newPhotoFile(live) },
            onPhotoResult = { path -> vm.setPhoto(live, path) },
        )
    }
    vm.csvPending?.let { (fileName, text) ->
        CsvImportDialog(
            fileName = fileName, text = text,
            onDismiss = { vm.csvPending = null },
            onImport = { order, kind -> vm.importCsv(text, fileName, order, kind) },
        )
    }
    if (showAdd) {
        AddPointDialog(onDismiss = { showAdd = false }) { n, c, e, nn, h ->
            vm.addManual(n, c, e, nn, h); showAdd = false
        }
    }
}

@Composable
private fun PointDetailDialog(
    p: PointEntity,
    onDismiss: () -> Unit,
    onStake: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String, String) -> Unit,
    onNewPhotoFile: () -> File,
    onPhotoResult: (String?) -> Unit,
) {
    var name by remember { mutableStateOf(p.name) }
    var code by remember { mutableStateOf(p.code) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val f = pendingFile
        if (success && f != null) onPhotoResult(f.absolutePath) else f?.delete()
        pendingFile = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Punto ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre", maxLines = 1) }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("Código", maxLines = 1) }, singleLine = true)
                PointPhotoRow(
                    photoPath = p.photoPath,
                    onTakePhoto = {
                        val f = onNewPhotoFile()
                        pendingFile = f
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                        takePicture.launch(uri)
                    },
                    onRemovePhoto = { onPhotoResult(null) },
                )
                KeyValue("Este", p.easting.f(4))
                KeyValue("Norte", p.northing.f(4))
                KeyValue("Cota", p.elevation.f(4))
                KeyValue("h elipsoidal", p.ellipsoidalHeight.f(4))
                KeyValue("Lat", p.lat.f(9))
                KeyValue("Lon", p.lon.f(9))
                KeyValue("Solución", p.solution ?: "—")
                KeyValue("σH / σV", "${p.hrms.f(3)} / ${p.vrms.f(3)}")
                KeyValue("Épocas / sats", "${p.epochs} / ${p.satellites ?: "—"}")
                if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "¿Confirmar borrado?" else "Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { onRename(name, code) }, enabled = name.isNotBlank()) { Text("Guardar cambios") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onStake) { Text("Replantear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Miniatura de la foto georreferenciada del punto (si tiene) + botones para tomar/quitar foto. */
@Composable
private fun PointPhotoRow(photoPath: String?, onTakePhoto: () -> Unit, onRemovePhoto: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (photoPath != null) {
            PointPhotoThumbnail(photoPath, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            IconButton(onClick = onRemovePhoto) { Icon(Icons.Filled.Close, contentDescription = "Quitar foto") }
        } else {
            Text("Sin foto", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onTakePhoto) { Icon(Icons.Filled.CameraAlt, contentDescription = "Tomar foto") }
    }
}

/** Decodifica el archivo de la foto a una miniatura pequeña (evita cargar la imagen entera en memoria). */
@Composable
private fun PointPhotoThumbnail(path: String, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }
    bitmap?.let { bmp ->
        Image(bmp.asImageBitmap(), contentDescription = "Foto del punto", modifier = modifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun AddPointDialog(onDismiss: () -> Unit, onAdd: (String, String, Double, Double, Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var n by remember { mutableStateOf("") }
    var h by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && parseNum(e) != null && parseNum(n) != null && (h.isBlank() || parseNum(h) != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Punto a replantear") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre", maxLines = 1) }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("Código", maxLines = 1) }, singleLine = true)
                NumberField(e, { e = it }, "Este", suffix = "m")
                NumberField(n, { n = it }, "Norte", suffix = "m")
                NumberField(h, { h = it }, "Cota (opcional)", suffix = "m")
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onAdd(name, code, parseNum(e)!!, parseNum(n)!!, parseNum(h)) }) { Text("Agregar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Importar CSV/TXT: muestra cómo se interpretó el archivo (separador, encabezado y las primeras filas),
 * deja elegir el orden de columnas cuando no hay encabezado y el tipo con que se guardan los puntos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CsvImportDialog(
    fileName: String,
    text: String,
    onDismiss: () -> Unit,
    onImport: (CsvOrder, PointKind) -> Unit,
) {
    var order by remember { mutableStateOf(CsvOrder.PENZD) }
    var kind by remember { mutableStateOf(PointKind.STAKE) }
    val parsed = remember(text, order) { PointCsv.parse(text, order) }
    val delimName = when (parsed.delimiter) { ';' -> "punto y coma"; '\t' -> "tabulación"; ',' -> "coma"; else -> "espacios" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar puntos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    "Separador: $delimName · " + if (parsed.headerCols != null) "encabezado reconocido" else "sin encabezado",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (parsed.headerCols == null) {
                    Text("Orden de columnas", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CsvOrder.entries.forEach { o ->
                            FilterChip(selected = order == o, onClick = { order = o }, label = { Text(o.label, maxLines = 1) })
                        }
                    }
                    Text(
                        "P = punto, E = este, N = norte, Z = cota, D = descripción.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Vista previa", style = MaterialTheme.typography.titleSmall)
                if (parsed.points.isEmpty()) {
                    Text("No se encontraron coordenadas válidas con este orden.", color = MaterialTheme.colorScheme.error)
                }
                parsed.points.take(5).forEach { r ->
                    Text(
                        (r.name ?: "(sin nombre)") + "  " +
                            (if (r.isGeographic) "Lat ${r.lat.f(7)}  Lon ${r.lon.f(7)}" else "E ${r.e.f(3)}  N ${r.n.f(3)}") +
                            "  Z ${r.z.f(3)}" + if (r.desc.isNotBlank()) "  ${r.desc}" else "",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                }
                if (parsed.points.size > 5) Text("… y ${parsed.points.size - 5} más", style = MaterialTheme.typography.bodySmall)
                if (parsed.skipped > 0) {
                    Text("${parsed.skipped} líneas se van a omitir (sin coordenadas válidas).", style = MaterialTheme.typography.bodySmall)
                }
                Text("Guardar como", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PointKind.STAKE, PointKind.COGO).forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(if (k == PointKind.COGO) "Cálculo (COGO)" else k.label, maxLines = 1) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.points.isNotEmpty(), onClick = { onImport(order, kind) }) {
                Text("Importar ${parsed.points.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}


/** Elegir el formato de exportación de los GCP; solo se exportan los puntos de tipo GCP. */
@Composable
private fun GcpExportDialog(
    crs: ProjectCrs,
    gcps: List<PointEntity>,
    onExport: (GcpExporter.Format) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(GcpExporter.Format.WEBODM_GCPI) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar GCP") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (gcps.isEmpty()) {
                    Text(
                        "Todavía no hay puntos GCP en este proyecto. Relevalos desde Levantamiento: tocá «+», elegí el tipo " +
                            "«GCP» y medí. Solo esos puntos se exportan acá.",
                    )
                } else {
                    Text("${gcps.size} punto${if (gcps.size == 1) "" else "s"} GCP · ${crs.describe()} (${GcpExporter.crsLabel(crs)})")
                    GcpExporter.Format.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { format = f },
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = format == f, onClick = { format = f })
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(f.label, fontWeight = FontWeight.Medium)
                                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val sinCota = gcps.count { it.elevation == null }
                    if (sinCota > 0) {
                        Text(
                            "$sinCota punto${if (sinCota == 1) "" else "s"} sin cota: se exporta${if (sinCota == 1) "" else "n"} con 0.000.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Sistema de coordenadas (para la casilla EPSG/PROJ de WebODM):",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(GcpExporter.projString(crs), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            if (gcps.isNotEmpty()) TextButton(onClick = { onExport(format) }) { Text("Exportar") }
            else TextButton(onClick = onDismiss) { Text("Entendido") }
        },
        dismissButton = { if (gcps.isNotEmpty()) TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
