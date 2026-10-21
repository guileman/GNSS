package com.topognss.app.ui.points

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointsScreen(onOpenProjects: () -> Unit, onStake: () -> Unit, vm: PointsViewModel = viewModel()) {
    val context = LocalContext.current
    val project by vm.project.collectAsState()
    val all by vm.points.collectAsState()
    var detail by remember { mutableStateOf<PointEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    val gnss by vm.gnss.collectAsState()

    val p = project
    if (p == null) { NoProjectCard(onOpenProjects); return }
    val shown = all.filter { vm.filter == null || it.kind == vm.filter!!.name }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(p.crs().describe(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onOpenProjects) { Text("Proyectos") }
                OutlinedButton(onClick = {
                    vm.exportCsv()?.let { context.startActivity(Intent.createChooser(it, "Exportar CSV")) }
                }) { Text("CSV") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = showMap, onClick = { showMap = !showMap },
                    label = { Text(if (showMap) "Ver lista" else "Ver mapa") })
                FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("Todos (${all.size})") })
                PointKind.entries.forEach { k ->
                    val c = all.count { it.kind == k.name }
                    FilterChip(selected = vm.filter == k, onClick = { vm.filter = k }, label = { Text("${k.label} ($c)") })
                }
            }
            vm.message?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.secondary) }
            if (showMap) {
                TopoMap(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    points = shown.map { it.toMapPoint() },
                    me = gnss.toMapMe(),
                    fitKey = vm.filter to shown.size,
                    onPointTap = { id -> detail = shown.firstOrNull { it.id == id } },
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
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("Código") }, singleLine = true)
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
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("Código") }, singleLine = true)
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
