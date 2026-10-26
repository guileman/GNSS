package com.topognss.app.ui.settings

import androidx.compose.foundation.clickable
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight
import com.topognss.app.update.UpdateState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.topognss.app.TopoApp
import com.topognss.app.ui.components.ScreenHeader
import com.topognss.app.ui.map.googleMapsAvailable
import com.topognss.app.ui.theme.ThemeMode

/**
 * Ajustes generales de la app (se abre con el engranaje de arriba en las pestañas principales).
 * Apariencia (tema), herramientas secundarias y datos de la versión.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPlumb: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TopoApp
    val theme by app.prefs.themeMode.collectAsState()
    val provider by app.prefs.mapProvider.collectAsState()
    val googleOk = remember { googleMapsAvailable(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Ajustes", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Apariencia") {
                ThemeMode.entries.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { app.prefs.setThemeMode(m.name) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == m.name, onClick = { app.prefs.setThemeMode(m.name) })
                        Text(m.label)
                    }
                }
            }
            Section("Mapa") {
                val options = listOf(
                    Triple("GOOGLE", "Google Maps", if (googleOk) "Imágenes satelitales de Google. Necesita Internet." else "No disponible: falta la clave de API de Google Maps en esta versión."),
                    Triple("OSM", "OpenStreetMap / Esri", "Funciona sin Internet en las zonas ya vistas."),
                )
                options.forEachIndexed { i, (id, title, sub) ->
                    if (i > 0) HorizontalDivider()
                    val enabled = id != "GOOGLE" || googleOk
                    val selected = if (googleOk) provider == id else id == "OSM"
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) { app.prefs.setMapProvider(id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { app.prefs.setMapProvider(id) }, enabled = enabled)
                        Column {
                            Text(title)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Section("Herramientas") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenPlumb).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Plomada virtual")
                        Text(
                            "Nivel del jalón con los sensores del teléfono",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            Section("Actualizaciones") { UpdateContent(app) }
            Section("Acerca de") {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Versión", Modifier.weight(1f))
                    Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

/** Buscar, descargar e instalar la última versión publicada en GitHub sin salir de la app. */
@Composable
private fun UpdateContent(app: TopoApp) {
    val um = app.updates
    val context = LocalContext.current
    val st by um.state.collectAsState()
    val auto by app.prefs.autoUpdates.collectAsState()
    fun mb(bytes: Long) = if (bytes > 0) " · %.1f MB".format(bytes / 1048576.0) else ""

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = st) {
            UpdateState.Idle, UpdateState.UpToDate -> {
                Text(
                    if (s == UpdateState.UpToDate) "Tenés la última versión (${um.currentVersion})." else "Versión instalada: ${um.currentVersion}",
                )
                Button(onClick = { um.check() }) { Text("Buscar actualización") }
            }
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Buscando…")
            }
            is UpdateState.Available -> {
                Text("Hay una versión nueva: ${s.info.tag}${mb(s.info.sizeBytes)}", fontWeight = FontWeight.SemiBold)
                if (s.info.notes.isNotBlank()) {
                    Text(s.info.notes.take(500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { um.download() }) { Text("Descargar") }
            }
            is UpdateState.Downloading -> {
                Text("Descargando ${s.info.tag}… ${(s.progress * 100).toInt()} %")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                Text("Lista para instalar: ${s.info.tag}", fontWeight = FontWeight.SemiBold)
                if (s.sameSigner == false) {
                    Text(
                        "Esta versión está firmada con otra clave, y Android no permite instalarla encima de la actual. " +
                            "Para instalarla hay que desinstalar la app antes, y se pierden los proyectos guardados en el teléfono: " +
                            "exportá tus puntos (Puntos → ⋮ → Exportar CSV) antes de seguir.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = {
                    if (um.canInstall()) um.install() else {
                        Toast.makeText(context, "Permití instalar apps desde GNSS y volvé a tocar Instalar", Toast.LENGTH_LONG).show()
                        um.openInstallPermission()
                    }
                }) { Text("Instalar") }
            }
            is UpdateState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { um.check() }) { Text("Reintentar") }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Buscar al abrir la app")
                Text(
                    "Avisa si hay una versión nueva (necesita Internet)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = auto, onCheckedChange = { app.prefs.setAutoUpdates(it) })
        }
    }
}
