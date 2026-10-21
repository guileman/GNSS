package com.topognss.app.ui.stakeout

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.cogo.AlignmentFix
import com.topognss.app.cogo.formatDms
import com.topognss.app.ui.components.OrderedPointSelector
import com.topognss.app.sensors.FusedHeading
import com.topognss.app.sensors.HeadingMode
import com.topognss.app.sensors.HeadingSource
import com.topognss.app.sensors.fuseHeading
import com.topognss.app.ui.components.NoProjectCard
import com.topognss.app.ui.components.PointRow
import com.topognss.app.ui.components.f
import com.topognss.app.ui.map.TopoMap
import com.topognss.app.ui.map.toMapMe
import com.topognss.app.ui.map.toMapPoint
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.FloatYellow
import com.topognss.app.ui.theme.SingleRed
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StakeoutScreen(onOpenProjects: () -> Unit, vm: StakeoutViewModel = viewModel()) {
    val project by vm.project.collectAsState()
    val target by vm.target.collectAsState()
    val s by vm.gnss.collectAsState()
    val compass by vm.compass.collectAsState()
    val points by vm.points.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var mapMode by rememberSaveable { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    if (project == null) { NoProjectCard(onOpenProjects); return }

    val heading = fuseHeading(vm.headingMode, compass, s.speedMs, s.courseDeg)
    val t = target
    val nav = t?.let { computeNav(s, it, heading.degrees, vm.prefs.antennaHeight.toDouble()) }
    val inside = nav != null && nav.distance <= vm.tolerance

    ProximityAlert(inside = inside, sound = vm.sound, near = nav != null && nav.distance <= vm.tolerance * 5)

    val bg by animateColorAsState(
        if (inside) FixGreen.copy(alpha = 0.25f) else MaterialTheme.colorScheme.background,
        label = "bg",
    )

    Column(Modifier.fillMaxSize().background(bg).padding(horizontal = 12.dp)) {
        // --- Modo: punto único o eje/alineación ---
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !vm.alignmentMode, onClick = { vm.alignmentMode = false }, label = { Text("Punto") })
            FilterChip(selected = vm.alignmentMode, onClick = { vm.alignmentMode = true }, label = { Text("Eje") })
        }
        if (vm.alignmentMode) {
            AlignmentPanel(vm, points, s)
            return@Column
        }
        // --- Objetivo ---
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.step(-1) }) { Text("◀") }
            Column(Modifier.weight(1f).clickable { picking = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Objetivo", style = MaterialTheme.typography.labelSmall)
                Text(t?.name ?: "Elegir punto…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                t?.let { Text("E ${it.easting.f(3)}  N ${it.northing.f(3)}  H ${it.elevation.f(3)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            TextButton(onClick = { vm.step(1) }) { Text("▶") }
        }

        // --- Indicaciones grandes ---
        if (t == null) {
            Text("Tocá \"Elegir punto\" o usá ‹Replantear› desde la lista de puntos.", Modifier.padding(16.dp))
        } else if (nav == null) {
            Text("Esperando posición del receptor…", Modifier.padding(16.dp), color = SingleRed)
        } else {
            Text(
                distanceText(nav.distance),
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                color = if (inside) FixGreen else MaterialTheme.colorScheme.onBackground,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (nav.forward != null && nav.right != null) {
                    BigDirection(if (nav.forward >= 0) "Avanzar" else "Retroceder", abs(nav.forward))
                    BigDirection(if (nav.right >= 0) "Derecha" else "Izquierda", abs(nav.right))
                } else {
                    BigDirection(if (nav.dNorth >= 0) "Norte" else "Sur", abs(nav.dNorth))
                    BigDirection(if (nav.dEast >= 0) "Este" else "Oeste", abs(nav.dEast))
                }
                nav.cutFill?.let { BigDirection(if (it >= 0) "Cortar" else "Rellenar", abs(it)) }
            }
        }

        // --- Gráfico: flecha lejos, radar/diana cerca ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !mapMode, onClick = { mapMode = false }, label = { Text("Guía") })
            FilterChip(selected = mapMode, onClick = { mapMode = true }, label = { Text("Mapa") })
        }
        if (mapMode) Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
            // Mapa "rumbo arriba" si hay rumbo; si no, norte arriba. Sigue la posición del receptor.
            TopoMap(
                modifier = Modifier.fillMaxSize(),
                points = points.filter { it.id != t?.id }.map { it.toMapPoint() },
                me = s.toMapMe(),
                target = t?.toMapPoint()?.copy(color = if (inside) FixGreen else SingleRed),
                line = if (t != null && s.lat != null && s.lon != null) listOf(s.lat!! to s.lon!!, t.lat to t.lon) else emptyList(),
                follow = true,
                rotationDeg = heading.degrees?.takeIf { heading.source != HeadingSource.NONE },
                fitKey = t?.id,
            )
            if (nav != null) {
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(96.dp)) {
                    DirectionArrow(angleDeg = (nav.relativeDeg ?: nav.azimuthDeg).toFloat(), color = solutionTint(s.solution.code))
                }
            }
        } else Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            if (nav != null) {
                if (nav.distance > 3.0) {
                    // Sin rumbo: flecha orientada al norte de pantalla (azimut absoluto)
                    DirectionArrow(angleDeg = (nav.relativeDeg ?: nav.azimuthDeg).toFloat(), color = solutionTint(s.solution.code))
                } else {
                    Radar(nav, heading, vm.tolerance)
                }
            }
        }

        // --- Rumbo / sensores ---
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeadingMode.entries.forEach { m ->
                FilterChip(selected = vm.headingMode == m, onClick = { vm.headingMode = m }, label = { Text(m.label) })
            }
        }
        HeadingInfo(heading, compass?.declinationDeg, compass?.accuracy, vm.compassAvailable, nav)

        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(0.02, 0.05, 0.10).forEach { tol ->
                FilterChip(selected = abs(vm.tolerance - tol) < 1e-6, onClick = { vm.updateTolerance(tol) },
                    label = { Text("${(tol * 100).toInt()} cm") })
            }
            TextButton(onClick = { vm.toggleSound() }) { Text(if (vm.sound) "🔊" else "🔇") }
            Button(onClick = { vm.record(nav, heading) }, enabled = nav != null, modifier = Modifier.weight(1f)) { Text("Registrar") }
        }
        vm.message?.let { Text(it, fontSize = 12.sp) }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Punto a replantear") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(points, key = { it.id }) { p ->
                        PointRow(p, Modifier.clickable { vm.selectTarget(p); picking = false })
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cerrar") } },
        )
    }
}

/**
 * Replanteo de ejes/alineaciones: en vez de un único punto objetivo, el operador elige una
 * polilínea de puntos (el eje, p. ej. una cañería o un cordón) y camina sobre ella; la app
 * muestra en todo momento la estación (distancia acumulada desde el inicio del eje) y el
 * desvío lateral (+ derecha / − izquierda) respecto del eje, para corregir la trayectoria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AlignmentPanel(
    vm: StakeoutViewModel,
    points: List<com.topognss.app.data.PointEntity>,
    s: com.topognss.app.gnss.GnssState,
) {
    var picking by remember { mutableStateOf(false) }
    val path = vm.alignmentSelected.mapNotNull { id -> points.firstOrNull { it.id == id } }
    val fix: AlignmentFix? = vm.alignmentFix(points, s)
    val totalLength = vm.alignmentLength(points)

    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Eje", style = MaterialTheme.typography.labelSmall)
            Text(
                if (path.isEmpty()) "Elegir puntos del eje…" else path.joinToString(" → ") { it.name },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
        }
        TextButton(onClick = { picking = true }) { Text("Elegir") }
        if (path.isNotEmpty()) TextButton(onClick = { vm.clearAlignment() }) { Text("Limpiar") }
    }

    when {
        path.size < 2 -> Text("Seleccioná 2 o más puntos, en orden, para definir el eje.", Modifier.padding(16.dp))
        s.lat == null || s.lon == null -> Text("Esperando posición del receptor…", Modifier.padding(16.dp), color = SingleRed)
        fix == null -> Text("Sin posición en el plano del proyecto.", Modifier.padding(16.dp), color = SingleRed)
        else -> {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Estación", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${fix.station.f(2)} / ${totalLength.f(2)} m", fontSize = 26.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (fix.offset >= 0) "Derecha" else "Izquierda", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${abs(fix.offset).f(3)} m", fontSize = 34.sp, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = if (abs(fix.offset) <= vm.tolerance) FixGreen else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
                TopoMap(
                    modifier = Modifier.fillMaxSize(),
                    points = path.mapIndexed { i, p -> p.toMapPoint(order = i + 1) },
                    me = s.toMapMe(),
                    line = path.map { it.lat to it.lon },
                    follow = true,
                    fitKey = vm.alignmentSelected.joinToString(","),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.02, 0.05, 0.10).forEach { tol ->
                    FilterChip(
                        selected = abs(vm.tolerance - tol) < 1e-6, onClick = { vm.updateTolerance(tol) },
                        label = { Text("${(tol * 100).toInt()} cm") },
                    )
                }
                Button(onClick = { vm.recordAlignment(points, s) }, modifier = Modifier.weight(1f)) { Text("Registrar") }
            }
        }
    }
    vm.message?.let { Text(it, fontSize = 12.sp) }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Puntos del eje (en orden)") },
            text = {
                Column {
                    OrderedPointSelector(points, vm.alignmentSelected, vm::toggleAlignmentPoint, Modifier.heightIn(max = 420.dp))
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Listo") } },
        )
    }
}

private fun distanceText(d: Double): String = when {
    d < 10 -> "${d.f(3)} m"
    d < 1000 -> "${d.f(2)} m"
    else -> "${(d / 1000).f(3)} km"
}

private fun solutionTint(code: Int): Color = when (code) {
    4 -> FixGreen
    5 -> FloatYellow
    else -> SingleRed
}

@Composable
private fun BigDirection(label: String, value: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("${value.f(if (value < 10) 3 else 2)} m", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HeadingInfo(h: FusedHeading, declination: Float?, accuracy: Int?, compassAvailable: Boolean, nav: StakeNav?) {
    val src = when (h.source) {
        HeadingSource.COMPASS -> "brújula"
        HeadingSource.GNSS_COG -> "rumbo GNSS (en movimiento)"
        HeadingSource.NONE -> "norte arriba"
    }
    val parts = mutableListOf("Rumbo ${(h.degrees?.toDouble()).f(0)}° ($src)")
    declination?.let { parts += "decl. ${it.toDouble().f(1)}°" }
    nav?.let { parts += "Az ${formatDms(it.azimuthDeg, 0)}" }
    Text(parts.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!compassAvailable) {
        Text("El teléfono no tiene magnetómetro: usá rumbo GNSS caminando.", fontSize = 11.sp, color = SingleRed)
    } else if (accuracy != null && accuracy < android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
        h.source == HeadingSource.COMPASS) {
        Text("Brújula poco precisa: calibrala moviendo el teléfono en forma de 8 y alejate de metales.",
            fontSize = 11.sp, color = FloatYellow)
    }
}

/** Flecha grande que rota suavemente (tomando el camino angular más corto). */
@Composable
private fun DirectionArrow(angleDeg: Float, color: Color) {
    // Desenrollar el ángulo para que la animación no gire 350° al cruzar 0°/360°
    // Acumulador sin estado observable (no dispara recomposiciones al escribirse)
    val acc = remember { floatArrayOf(angleDeg) }
    val diff = ((angleDeg - acc[0]) % 360f + 540f) % 360f - 180f
    acc[0] += diff
    val anim by animateFloatAsState(acc[0], animationSpec = tween(150), label = "arrow")
    Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = center
        drawCircle(color.copy(alpha = 0.12f), radius = r * 0.95f)
        rotate(anim, c) {
            val path = Path().apply {
                moveTo(c.x, c.y - r * 0.85f)              // punta
                lineTo(c.x + r * 0.45f, c.y - r * 0.15f)
                lineTo(c.x + r * 0.17f, c.y - r * 0.15f)
                lineTo(c.x + r * 0.17f, c.y + r * 0.7f)
                lineTo(c.x - r * 0.17f, c.y + r * 0.7f)
                lineTo(c.x - r * 0.17f, c.y - r * 0.15f)
                lineTo(c.x - r * 0.45f, c.y - r * 0.15f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * Radar / diana para la aproximación final (< 3 m). La parte superior de la pantalla es hacia donde
 * mira el operador (o el norte si no hay rumbo). Escala automática por anillos.
 */
@Composable
private fun Radar(nav: StakeNav, heading: FusedHeading, tolerance: Double) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val range = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 4.0).firstOrNull { it >= nav.distance * 1.15 } ?: 4.0
    val inside = nav.distance <= tolerance
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.weight(1f).aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f * 0.92f
        val c = center
        val pxPerM = r / range.toFloat()

        for (i in 1..4) drawCircle(onBg.copy(alpha = 0.25f), radius = r * i / 4f, style = Stroke(width = 2f))
        drawLine(onBg.copy(alpha = 0.2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 2f)
        drawLine(onBg.copy(alpha = 0.2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 2f)
        // Círculo de tolerancia
        drawCircle(FixGreen.copy(alpha = 0.35f), radius = (tolerance * pxPerM).toFloat().coerceAtMost(r))

        // Indicador de Norte en el borde (rota con el rumbo)
        val hdg = heading.degrees
        if (hdg != null && heading.source != HeadingSource.NONE) {
            val a = Math.toRadians(-hdg.toDouble())
            val nx = c.x + (r * sin(a)).toFloat()
            val ny = c.y - (r * cos(a)).toFloat()
            drawCircle(SingleRed, radius = 10f, center = Offset(nx, ny))
        }

        // Objetivo: (derecha, adelante) si hay rumbo; si no (este, norte)
        val x = (nav.right ?: nav.dEast).toFloat()
        val y = (nav.forward ?: nav.dNorth).toFloat()
        val tp = Offset(c.x + x * pxPerM, c.y - y * pxPerM)
        drawCrosshair(tp, if (inside) FixGreen else SingleRed)

        // Operador en el centro
        val me = Path().apply {
            moveTo(c.x, c.y - 18f); lineTo(c.x + 12f, c.y + 12f); lineTo(c.x - 12f, c.y + 12f); close()
        }
        drawPath(me, primary)
    }
    Text("Escala: radio ${range.f(2)} m", fontSize = 10.sp, color = onBg.copy(alpha = 0.6f))
    }
}

private fun DrawScope.drawCrosshair(p: Offset, color: Color) {
    drawCircle(color, radius = 16f, center = p, style = Stroke(width = 5f))
    drawLine(color, Offset(p.x - 26f, p.y), Offset(p.x + 26f, p.y), strokeWidth = 4f)
    drawLine(color, Offset(p.x, p.y - 26f), Offset(p.x, p.y + 26f), strokeWidth = 4f)
}

/** Alerta sonora y vibratoria de proximidad (pitidos más rápidos dentro de la tolerancia). */
@Composable
private fun ProximityAlert(inside: Boolean, near: Boolean, sound: Boolean) {
    val context = LocalContext.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    LaunchedEffect(inside) {
        if (inside) vibrate(context, 300)
    }
    LaunchedEffect(inside, near, sound) {
        if (!sound) return@LaunchedEffect
        while (inside || near) {
            tone?.startTone(if (inside) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120)
            delay(if (inside) 400 else 1200)
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrate(context: Context, ms: Long) {
    runCatching {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

