package com.topognss.app.ui.projects

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.data.ProjectEntity
import com.topognss.app.data.crs
import com.topognss.app.data.nomenclaturaCatastral
import com.topognss.app.geo.CrsType
import com.topognss.app.geo.ProjectCrs
import com.topognss.app.ui.components.NumberField
import com.topognss.app.ui.components.f
import com.topognss.app.ui.components.parseNum
import com.topognss.app.ui.hold
import com.topognss.app.ui.topo
import kotlinx.coroutines.launch

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = topo.repository
    val projects = hold(repo.observeProjects(), emptyList())
    val activeId = topo.prefs.activeProjectId
    val gnss = topo.gnss.state

    fun select(p: ProjectEntity) = topo.prefs.setActiveProject(p.id)

    fun save(existing: ProjectEntity?, draft: ProjectEntity) = viewModelScope.launch {
        if (existing == null) {
            val id = repo.createProject(draft)
            topo.prefs.setActiveProject(id)
        } else {
            val updated = draft.copy(id = existing.id, createdAt = existing.createdAt)
            repo.updateProject(updated)
            if (existing.crs() != updated.crs()) repo.reprojectAll(updated)
        }
    }

    fun delete(p: ProjectEntity) = viewModelScope.launch {
        repo.deleteProject(p)
        if (activeId.value == p.id) topo.prefs.setActiveProject(-1)
    }
}

@Composable
fun ProjectsScreen(onDone: () -> Unit, vm: ProjectsViewModel = viewModel()) {
    val list by vm.projects.collectAsState()
    val active by vm.activeId.collectAsState()
    var editing by remember { mutableStateOf<ProjectEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Proyectos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text("Nuevo") }
        }
        if (list.isEmpty()) Text("Todavía no hay proyectos. Creá uno para empezar a relevar.", Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(list, key = { it.id }) { p ->
                val isActive = p.id == active
                Card(
                    Modifier.fillMaxWidth().clickable { vm.select(p); onDone() },
                    colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (isActive) "  (activo)" else "", style = MaterialTheme.typography.titleMedium)
                            Text(p.crs().describe(), style = MaterialTheme.typography.bodySmall)
                            if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                            p.nomenclaturaCatastral().takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { editing = p }) { Text("Editar") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ProjectDialog(
            initial = editing,
            gnssLatLon = vm.gnss.value.let { s -> if (s.lat != null && s.lon != null) s.lat to s.lon else null },
            onDismiss = { creating = false; editing = null },
            onSave = { draft -> vm.save(editing, draft); creating = false; editing = null },
            onDelete = editing?.let { e -> { vm.delete(e); editing = null } },
        )
    }
}

@Composable
private fun ProjectDialog(
    initial: ProjectEntity?,
    gnssLatLon: Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onSave: (ProjectEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var type by remember { mutableStateOf(initial?.crs()?.type ?: CrsType.POSGAR_GK) }
    var zone by remember { mutableStateOf((initial?.zone ?: 5).toString()) }
    var south by remember { mutableStateOf(initial?.south ?: true) }
    var oLat by remember { mutableStateOf(initial?.originLat?.toString() ?: "") }
    var oLon by remember { mutableStateOf(initial?.originLon?.toString() ?: "") }
    var circ by remember { mutableStateOf(initial?.catastroCircunscripcion ?: "") }
    var secc by remember { mutableStateOf(initial?.catastroSeccion ?: "") }
    var mzCh by remember { mutableStateOf(initial?.catastroManzanaOChacra ?: "") }
    var parc by remember { mutableStateOf(initial?.catastroParcela ?: "") }
    var partida by remember { mutableStateOf(initial?.catastroPartida ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun autoFromGnss() {
        val (lat, lon) = gnssLatLon ?: return
        when (type) {
            CrsType.UTM -> { zone = ProjectCrs.utmZoneFor(lon).toString(); south = lat < 0 }
            CrsType.POSGAR_GK -> zone = ProjectCrs.posgarFajaFor(lon).toString()
            CrsType.LOCAL_TM -> { oLat = lat.f(8); oLon = lon.f(8) }
        }
    }

    val zoneInt = zone.toIntOrNull()
    val valid = name.isNotBlank() && when (type) {
        CrsType.UTM -> zoneInt != null && zoneInt in 1..60
        CrsType.POSGAR_GK -> zoneInt != null && zoneInt in 1..7
        CrsType.LOCAL_TM -> parseNum(oLat) != null && parseNum(oLon) != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo proyecto" else "Editar proyecto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Descripción") })
                Text("Sistema de coordenadas", style = MaterialTheme.typography.titleSmall)
                CrsType.entries.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { type = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label)
                    }
                }
                when (type) {
                    CrsType.UTM -> Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField(zone, { zone = it }, "Zona (1-60)", Modifier.weight(1f))
                        Text("  Sur ")
                        Switch(checked = south, onCheckedChange = { south = it })
                    }
                    CrsType.POSGAR_GK -> NumberField(zone, { zone = it }, "Faja (1-7) · MC = -72° + 3°·(faja−1)")
                    CrsType.LOCAL_TM -> {
                        NumberField(oLat, { oLat = it }, "Latitud origen (°)")
                        NumberField(oLon, { oLon = it }, "Longitud origen / MC (°)")
                        Text("k0 = 1, E/N falsos = 10 000 m. Las distancias planas ≈ distancias reales en la obra.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { autoFromGnss() }, enabled = gnssLatLon != null) {
                    Text(if (gnssLatLon != null) "Completar con posición del receptor" else "Sin posición del receptor")
                }
                Text("Nomenclatura catastral (opcional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "El formato varía por provincia/país; completá solo los campos que uses.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(circ, { circ = it }, label = { Text("Circunscripción") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(secc, { secc = it }, label = { Text("Sección") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(mzCh, { mzCh = it }, label = { Text("Manzana / Chacra") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(parc, { parc = it }, label = { Text("Parcela") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(partida, { partida = it }, label = { Text("Partida inmobiliaria") }, singleLine = true)
                if (initial != null) {
                    Text("Si cambiás el sistema se recalculan las planas de todos los puntos.",
                        style = MaterialTheme.typography.bodySmall)
                    onDelete?.let { del ->
                        TextButton(onClick = { if (confirmDelete) del() else confirmDelete = true }) {
                            Text(if (confirmDelete) "¿Borrar proyecto y TODOS sus puntos?" else "Eliminar proyecto",
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    ProjectEntity(
                        name = name.trim(), description = desc.trim(), crsType = type.name,
                        zone = zoneInt ?: 0, south = south,
                        originLat = parseNum(oLat) ?: 0.0, originLon = parseNum(oLon) ?: 0.0,
                        catastroCircunscripcion = circ.trim(), catastroSeccion = secc.trim(),
                        catastroManzanaOChacra = mzCh.trim(), catastroParcela = parc.trim(),
                        catastroPartida = partida.trim(),
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
