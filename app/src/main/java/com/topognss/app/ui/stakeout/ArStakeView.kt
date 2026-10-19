package com.topognss.app.ui.stakeout

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.topognss.app.ar.ArStakeController
import com.topognss.app.ar.ArStakeRenderer
import com.topognss.app.ar.ArUiState
import com.topognss.app.ar.ScreenPoint
import com.topognss.app.ui.components.f
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.FloatYellow
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private sealed interface ArGate {
    data object Checking : ArGate
    data object NeedPermission : ArGate
    data object Installing : ArGate
    data class Unsupported(val message: String) : ArGate
    data object Ready : ArGate
}

/**
 * Vista de replanteo en realidad aumentada: cámara con el mojón dibujado en la posición real
 * del punto. La calibración se hace sola mientras el operador camina con FIX.
 */
@Composable
fun ArStakeView(
    controller: ArStakeController,
    targetName: String?,
    nav: StakeNav?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var gate by remember { mutableStateOf<ArGate>(ArGate.Checking) }
    var resumes by remember { mutableIntStateOf(0) }
    var userRequestedInstall by remember { mutableStateOf(true) }
    var permissionAsked by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        gate = if (granted) ArGate.Checking else ArGate.NeedPermission
        resumes++
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Permiso de cámara → disponibilidad de ARCore → instalación/actualización → sesión.
    LaunchedEffect(resumes) {
        if (gate == ArGate.Ready) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            gate = ArGate.NeedPermission
            if (!permissionAsked) {
                permissionAsked = true
                permLauncher.launch(Manifest.permission.CAMERA)
            }
            return@LaunchedEffect
        }
        val apk = ArCoreApk.getInstance()
        var avail = apk.checkAvailability(context)
        var tries = 0
        while (avail.isTransient && tries++ < 25) {
            delay(200)
            avail = apk.checkAvailability(context)
        }
        if (avail == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            gate = ArGate.Unsupported("Este teléfono no es compatible con ARCore (Servicios de Google Play para RA).")
            return@LaunchedEffect
        }
        val activity = context.findActivity()
        if (activity == null) {
            gate = ArGate.Unsupported("No se pudo abrir la cámara AR.")
            return@LaunchedEffect
        }
        try {
            when (apk.requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    userRequestedInstall = false
                    gate = ArGate.Installing
                    return@LaunchedEffect
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                else -> Unit
            }
        } catch (e: Exception) {
            gate = ArGate.Unsupported("Hace falta instalar \"Servicios de Google Play para RA\" (${e.javaClass.simpleName}).")
            return@LaunchedEffect
        }
        val err = controller.createSession()
        gate = if (err == null) ArGate.Ready else ArGate.Unsupported(err)
    }

    Box(modifier.background(Color.Black)) {
        when (val g = gate) {
            ArGate.Ready -> ArCameraWithOverlay(controller, targetName, nav)
            ArGate.Checking -> CenterMessage("Preparando la cámara AR…")
            ArGate.Installing -> CenterMessage("Instalá o actualizá \"Servicios de Google Play para RA\" y volvé a la app.")
            ArGate.NeedPermission -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("La vista AR necesita la cámara.", color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 8.dp)) {
                    Text("Permitir cámara")
                }
            }
            is ArGate.Unsupported -> CenterMessage(g.message)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ArCameraWithOverlay(controller: ArStakeController, targetName: String?, nav: StakeNav?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember(controller) { ArStakeRenderer(context.applicationContext, controller) }
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val ui by controller.ui.collectAsState()

    DisposableEffect(lifecycleOwner, controller) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { controller.resume(); holder[0]?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { holder[0]?.onPause(); controller.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            holder[0]?.onPause()
            controller.pause()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    // onResume/onPause los maneja el observador de ciclo de vida (se registra después).
                    holder[0] = this
                }
            },
        )
        ArOverlay(ui, targetName, nav, onRecalibrate = { controller.recalibrate() })
    }
}

@Composable
private fun ArOverlay(ui: ArUiState, targetName: String?, nav: StakeNav?, onRecalibrate: () -> Unit) {
    val al = ui.alignment
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        // --- Estado de la calibración (arriba) ---
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp)).padding(8.dp),
        ) {
            when {
                ui.error != null -> Text(ui.error, color = Color.White, fontSize = 13.sp)
                !ui.trackingOk -> Text(ui.trackingMessage ?: "Buscando el entorno…", color = FloatYellow, fontSize = 13.sp)
                !al.ready -> {
                    Text(al.message, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { (al.baseline / 6.0).coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        "Recorrido ${al.baseline.f(1)} m · ${al.samples} muestras · residuo ${al.rms.f(2)} m",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                    )
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AR ✓  rumbo ±${al.headingSigmaDeg.f(1)}° · residuo ${al.rms.f(2)} m" +
                            if (!ui.groundDetected) " · piso estimado" else "",
                        color = FixGreen, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRecalibrate) { Text("Recalibrar", color = Color.White, fontSize = 12.sp) }
                }
            }
            ui.gnssMessage?.let { Text(it, color = FloatYellow, fontSize = 11.sp) }
        }

        // --- Etiqueta sobre el mojón o flecha al borde si está fuera de cuadro ---
        val sp = ui.target
        if (al.ready && sp != null && nav != null) {
            if (sp.onScreen) {
                StakeLabel(sp, targetName, nav)
            } else {
                EdgeArrow(sp, wPx, hPx)
            }
        }
    }
}

@Composable
private fun StakeLabel(sp: ScreenPoint, name: String?, nav: StakeNav) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Column(
        Modifier
            .offset { IntOffset((sp.x - size.width / 2f).roundToInt(), (sp.y - size.height - gap).roundToInt()) }
            .onSizeChanged { size = it }
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        name?.let { Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        Text("${nav.distance.f(if (nav.distance < 10) 2 else 1)} m", color = Color.White,
            fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Black)
        nav.cutFill?.let {
            Text((if (it >= 0) "Cortar " else "Rellenar ") + "${abs(it).f(2)} m", color = Color.White, fontSize = 12.sp)
        }
    }
}

/** Flecha en el borde de la pantalla que indica hacia dónde girar para ver el mojón. */
@Composable
private fun EdgeArrow(sp: ScreenPoint, w: Float, h: Float) {
    val density = LocalDensity.current
    val sizePx = with(density) { 72.dp.toPx() }
    val rad = Math.toRadians(sp.edgeAngleDeg.toDouble())
    val r = min(w, h) / 2f - sizePx * 0.75f
    val cx = w / 2f + (r * sin(rad)).toFloat()
    val cy = h / 2f - (r * cos(rad)).toFloat()
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.offset { IntOffset((cx - sizePx / 2).roundToInt(), (cy - sizePx / 2).roundToInt()) }.size(72.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val c = center
            drawCircle(Color.Black.copy(alpha = 0.45f), radius = s / 2)
            rotate(sp.edgeAngleDeg, c) {
                val p = Path().apply {
                    moveTo(c.x, c.y - s * 0.38f)
                    lineTo(c.x + s * 0.26f, c.y + s * 0.18f)
                    lineTo(c.x, c.y + s * 0.06f)
                    lineTo(c.x - s * 0.26f, c.y + s * 0.18f)
                    close()
                }
                drawPath(p, color)
            }
        }
    }
    val hint = when {
        sp.behind && abs(sp.edgeAngleDeg) > 135f -> "Date vuelta"
        sp.edgeAngleDeg > 20f -> "Girá a la derecha"
        sp.edgeAngleDeg < -20f -> "Girá a la izquierda"
        else -> "Levantá la vista"
    }
    Box(Modifier.fillMaxSize().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Text(hint, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
