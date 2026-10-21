package com.topognss.app.ui.components

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.pointKind
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.gnss.SolutionType
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

private fun shortLabel(s: GnssState, stale: Boolean): String = when {
    s.connection == ConnectionStatus.CONNECTING -> "CONECTANDO"
    s.connection == ConnectionStatus.ERROR -> "ERROR"
    s.connection != ConnectionStatus.CONNECTED -> "SIN CONEXIÓN"
    stale -> "SIN DATOS"
    else -> s.solution.label
}

private fun statusIcon(s: GnssState, stale: Boolean): ImageVector = when {
    s.connection != ConnectionStatus.CONNECTED -> Icons.Filled.GpsOff
    s.connection == ConnectionStatus.ERROR -> Icons.Filled.SyncProblem
    stale -> Icons.Filled.GpsNotFixed
    s.solution == SolutionType.FIX -> Icons.Filled.GpsFixed
    else -> Icons.Filled.GpsNotFixed
}

/**
 * Barra de estado GNSS persistente (ícono + estado nada más; el detalle vive en GNSS).
 * Verde = FIX, amarillo = FLOAT, naranja = DGPS, rojo = SINGLE / sin datos / desconectado.
 * Emite un sonido corto cada vez que cambia de categoría de estado.
 */
@Composable
fun GnssStatusBar(s: GnssState, onClick: () -> Unit) {
    val now = rememberNow()
    val stale = s.connection == ConnectionStatus.CONNECTED && s.isStale(now)
    val color = solutionColor(s, stale)
    val label = shortLabel(s, stale)

    BeepOnChange(label)

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(statusIcon(s, stale), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        if (s.connection == ConnectionStatus.CONNECTED && !stale) {
            Icon(Icons.Filled.GpsFixed, contentDescription = "Satélites", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(s.satellites?.toString() ?: "—", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * Tono corto de alerta cada vez que [key] cambia (no en la primera composición).
 * Se usa para avisar cambios de estado de conexión/solución sin tener que mirar la pantalla.
 */
@Composable
fun BeepOnChange(key: String, risingIsGood: Boolean = true) {
    val context = LocalContext.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    var prev by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val p = prev
        if (p != null && p != key) {
            val good = key == "FIX" || key.startsWith("✔")
            tone?.startTone(if (good) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 150)
        }
        prev = key
    }
}

/** Reproduce un tono cada vez que [tick] avanza (p. ej. un contador que se incrementa al terminar algo). */
@Composable
fun PlayBeepOnTick(tick: Int) {
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(tick) {
        if (first) { first = false; return@LaunchedEffect }
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
            delay(220)
            t.release()
        }
    }
}

/**
 * Bloque colapsable con marco redondeado: para agrupar ajustes/filtros fuera del flujo principal.
 */
@Composable
fun CollapsibleCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (!expanded && summary != null) {
                    Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content() }
            }
        }
    }
}

/** Marco redondeado simple para delimitar un bloque siempre visible (sin colapsar). */
@Composable
fun FramedBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(10.dp)) { content() } }
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
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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
