package com.topognss.app.ui.settings

import androidx.compose.foundation.clickable
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
