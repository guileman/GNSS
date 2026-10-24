package com.topognss.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.pointKind
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.solutionColor
import kotlinx.coroutines.delay
import java.util.Locale

/** Formato numérico con punto decimal siempre (convención topográfica, independiente del idioma). */
fun Double?.f(decimals: Int = 3, empty: String = "—"): String =
    this?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: empty

fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Reloj de 1 Hz para detectar datos viejos aunque no lleguen tramas nuevas. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * Franja de estado GNSS (estilo Emlid Flow): una sola línea delgada.
 * Desconectado: "Sin receptor conectado". Conectado: punto de color de la solución, y SAT / H / V.
 * Verde = FIX, amarillo = FLOAT, naranja = DGPS, rojo = SINGLE / sin datos. El fondo lo pone quien la usa.
 */
@Composable
fun GnssStatusBar(s: GnssState, onClick: () -> Unit) {
    val now = rememberNow()
    val stale = s.connection == ConnectionStatus.CONNECTED && s.isStale(now)
    val color = solutionColor(s, stale)
    val live = s.connection == ConnectionStatus.CONNECTED && !stale
    val label = when {
        s.connection == ConnectionStatus.CONNECTING -> "Conectando…"
        s.connection == ConnectionStatus.ERROR -> "Error · reintentando"
        s.connection != ConnectionStatus.CONNECTED -> "Sin receptor conectado"
        stale -> "Sin datos del receptor"
        else -> s.solution.label
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (live) {
            Metric("SAT", s.satellites?.toString() ?: "—")
            Metric("H", s.hrms.f(3))
            Metric("V", s.vrms.f(3))
        } else if (s.connection != ConnectionStatus.CONNECTING) {
            Text(
                s.errorMessage ?: "Tocar para conectar",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Etiqueta compacta de una sola línea (nunca parte el texto en vertical). */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = Color(0xFF37474F),
    content: Color = Color.White,
) {
    Box(modifier.background(container, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

/** Cabecera simple de pantalla secundaria: flecha de volver, título y acciones. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** Engranaje que abre los Ajustes generales de la app (arriba a la derecha en las pestañas principales). */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
    }
}

/** Estado vacío: qué pasa y qué hacer. */
@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun NoProjectCard(onOpenProjects: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No hay un proyecto activo", style = MaterialTheme.typography.titleMedium)
            Text("Creá o elegí un proyecto para definir el sistema de coordenadas (UTM, POSGAR 2007 o plano local).")
            Button(onClick = onOpenProjects) { Text("Ir a proyectos") }
        }
    }
}

@Composable
fun KindBadge(kind: PointKind) {
    val c = when (kind) {
        PointKind.SURVEY -> MaterialTheme.colorScheme.primary
        PointKind.COGO -> MaterialTheme.colorScheme.tertiary
        PointKind.STAKE -> Color(0xFF7C3AED)
        PointKind.STAKED -> FixGreen
    }
    Box(Modifier.background(c.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(kind.label, color = c, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Fila compacta de punto: nombre, código, E/N/H. */
@Composable
fun PointRow(p: PointEntity, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, fontWeight = FontWeight.Bold)
                if (p.code.isNotBlank()) Text(p.code, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                KindBadge(p.pointKind())
                p.solution?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(
                "E ${p.easting.f(3)}  N ${p.northing.f(3)}  H ${p.elevation.f(3)}",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }
    }
}

/**
 * Selector múltiple ORDENADO de puntos: el orden de selección define la poligonal / línea base.
 */
@Composable
fun OrderedPointSelector(
    points: List<PointEntity>,
    selected: List<Long>,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        items(points, key = { it.id }) { p ->
            val order = selected.indexOf(p.id)
            PointRow(
                p,
                Modifier.clickable { onToggle(p.id) },
                leading = {
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                        if (order >= 0) {
                            Box(
                                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                        } else {
                            Checkbox(checked = false, onCheckedChange = { onToggle(p.id) })
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        isError = value.isNotBlank() && parseNum(value) == null,
        modifier = modifier,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun VSpace(h: Int = 8) = Spacer(Modifier.height(h.dp))
