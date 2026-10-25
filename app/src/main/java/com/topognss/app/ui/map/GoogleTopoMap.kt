package com.topognss.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.topognss.app.TopoApp
import com.topognss.app.ui.theme.LocalDarkTheme
import kotlin.math.max

/**
 * Mapa base de Google Maps (Maps SDK for Android) con el mismo contenido que el mapa OpenStreetMap:
 * puntos con etiqueta, posición con círculo de precisión, objetivo de replanteo, línea de selección,
 * líneas punteadas auxiliares y vista previa del punto calculado.
 */
@Composable
internal fun GoogleTopoMap(
    modifier: Modifier,
    points: List<MapPoint>,
    me: MapMe?,
    target: MapPoint?,
    line: List<Pair<Double, Double>>,
    closeLine: Boolean,
    follow: Boolean,
    rotationDeg: Float?,
    fitKey: Any?,
    onPointTap: ((Long) -> Unit)?,
    dashed: List<List<Pair<Double, Double>>>,
    previews: List<MapPoint>,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TopoApp
    val layerName by app.prefs.mapLayer.collectAsState()
    val layer = runCatching { MapLayer.valueOf(layerName) }.getOrDefault(MapLayer.STREETS)
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current.density

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-34.6, -58.4), 5f)
    }
    var loaded by remember { mutableStateOf(false) }
    var centerRequest by remember { mutableIntStateOf(0) }
    val icons = remember { HashMap<String, Pair<BitmapDescriptor, Offset>>() }

    val properties = remember(layer, dark) {
        MapProperties(
            mapType = when (layer) {
                MapLayer.STREETS -> MapType.NORMAL
                MapLayer.SATELLITE -> MapType.SATELLITE
                MapLayer.HYBRID -> MapType.HYBRID
                MapLayer.TOPO -> MapType.TERRAIN
                MapLayer.NONE -> MapType.NONE
            },
            mapStyleOptions = if (dark && layer == MapLayer.STREETS) MapStyleOptions(NIGHT_STYLE) else null,
            maxZoomPreference = 22f,
            isBuildingEnabled = false,
            isIndoorEnabled = false,
        )
    }
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = true,
            tiltGesturesEnabled = false,
            indoorLevelPickerEnabled = false,
        )
    }

    // Encuadre automático (o centrar en mi posición)
    LaunchedEffect(fitKey, centerRequest, loaded) {
        if (!loaded) return@LaunchedEffect
        val m = me
        if (centerRequest > 0 && m != null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(m.lat, m.lon), max(camera.position.zoom, 19f)), 400)
            return@LaunchedEffect
        }
        val geo = buildList {
            points.forEach { add(LatLng(it.lat, it.lon)) }
            m?.let { add(LatLng(it.lat, it.lon)) }
            target?.let { add(LatLng(it.lat, it.lon)) }
        }
        if (geo.isEmpty()) return@LaunchedEffect
        runCatching {
            if (geo.size == 1) {
                camera.move(CameraUpdateFactory.newLatLngZoom(geo[0], 19f))
            } else {
                val b = LatLngBounds.builder().apply { geo.forEach { include(it) } }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(b, (48 * density).toInt()))
                // Evitar un zoom exagerado si los puntos están casi superpuestos
                if (camera.position.zoom > 20f) camera.move(CameraUpdateFactory.zoomTo(20f))
            }
        }
    }

    // Seguir la posición y girar "rumbo arriba" (replanteo)
    LaunchedEffect(follow, me?.lat, me?.lon, rotationDeg, loaded) {
        if (!loaded) return@LaunchedEffect
        val m = me
        if (!follow && rotationDeg == null) return@LaunchedEffect
        val b = CameraPosition.Builder(camera.position).bearing(rotationDeg ?: 0f)
        if (follow && m != null) b.target(LatLng(m.lat, m.lon))
        camera.move(CameraUpdateFactory.newCameraPosition(b.build()))
    }

    Box(modifier.clipToBounds()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = properties,
            uiSettings = uiSettings,
            onMapLoaded = { loaded = true },
        ) {
            // Línea / polígono de selección (Cálculo) o hacia el objetivo (replanteo)
            if (line.size >= 2) {
                val pts = (if (closeLine && line.size >= 3) line + line.first() else line).map { LatLng(it.first, it.second) }
                Polyline(points = pts, color = Color(0xFFF2B705), width = 3f * density)
            }
            // Líneas punteadas auxiliares
            dashed.forEach { seg ->
                if (seg.size >= 2) {
                    Polyline(
                        points = seg.map { LatLng(it.first, it.second) },
                        color = PREVIEW_COLOR,
                        width = 2.5f * density,
                        pattern = listOf(Dash(10f * density), Gap(6f * density)),
                        zIndex = 1f,
                    )
                }
            }
            // Puntos del proyecto, con nombre (y número de orden si están seleccionados)
            points.forEach { pt ->
                key(pt.id) {
                    val label = if (pt.order != null) "${pt.order}·${pt.name}" else pt.name
                    val big = pt.order != null
                    val (icon, anchor) = icons.getOrPut("p|$label|${pt.color.toArgb()}|$big") {
                        pointIcon(label, pt.color, if (big) 9f else 6f, density, dashedRing = false)
                    }
                    val state = remember { MarkerState(LatLng(pt.lat, pt.lon)) }
                    SideEffect { state.position = LatLng(pt.lat, pt.lon) }
                    Marker(
                        state = state, icon = icon, anchor = anchor, zIndex = 2f,
                        onClick = { onPointTap?.invoke(pt.id); true },
                    )
                }
            }
            // Vista previa de los puntos calculados (anillo punteado)
            previews.forEachIndexed { i, pv ->
                key("pv$i") {
                    val (icon, anchor) = icons.getOrPut("v|${pv.name}") { pointIcon(pv.name, PREVIEW_COLOR, 9f, density, dashedRing = true) }
                    val state = remember { MarkerState(LatLng(pv.lat, pv.lon)) }
                    SideEffect { state.position = LatLng(pv.lat, pv.lon) }
                    Marker(state = state, icon = icon, anchor = anchor, zIndex = 3f, onClick = { true })
                }
            }
            // Objetivo de replanteo: diana
            target?.let { t ->
                val (icon, anchor) = icons.getOrPut("t|${t.name}|${t.color.toArgb()}") { targetIcon(t.name, t.color, density) }
                val state = remember { MarkerState(LatLng(t.lat, t.lon)) }
                SideEffect { state.position = LatLng(t.lat, t.lon) }
                Marker(state = state, icon = icon, anchor = anchor, zIndex = 3f, onClick = { true })
            }
            // Posición del receptor con círculo de precisión (HRMS a escala real)
            me?.let { m ->
                val pos = LatLng(m.lat, m.lon)
                m.hrms?.let { h ->
                    if (h > 0) Circle(center = pos, radius = h, fillColor = m.color.copy(alpha = 0.25f), strokeWidth = 0f, zIndex = 4f)
                }
                val (icon, anchor) = icons.getOrPut("me|${m.color.toArgb()}") { dotIcon(m.color, density) }
                val state = remember { MarkerState(pos) }
                SideEffect { state.position = pos }
                Marker(state = state, icon = icon, anchor = anchor, zIndex = 5f, onClick = { true })
            }
        }

        // Controles flotantes: selector de capa y centrar en mi posición
        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            LayerSelector(layer) { app.prefs.setMapLayer(it.name) }
            if (me != null) {
                SmallFloatingActionButton(onClick = { centerRequest++ }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "Centrar en mi posición", Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Íconos dibujados a mano (círculo + etiqueta con halo), igual que en el mapa OpenStreetMap
// ---------------------------------------------------------------------------------------------

private fun textPaints(density: Float): Pair<Paint, Paint> {
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE
    }
    val halo = Paint(text).apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f * density }
    return text to halo
}

/** Círculo de color con su nombre a la derecha. Devuelve el ícono y el ancla (centro del círculo). */
private fun pointIcon(label: String, tint: Color, radiusDp: Float, density: Float, dashedRing: Boolean): Pair<BitmapDescriptor, Offset> {
    val (text, halo) = textPaints(density)
    val r = radiusDp * density
    val pad = 4f * density
    val textW = text.measureText(label)
    val w = (pad + 2 * r + 4f * density + textW + pad).toInt().coerceAtLeast(1)
    val h = (2 * r + 2 * pad + 6f * density).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = pad + r
    val cy = h / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = android.graphics.Color.WHITE
    }
    if (dashedRing) {
        fill.color = tint.copy(alpha = 0.35f).toArgb()
        c.drawCircle(cx, cy, r, fill)
        stroke.color = tint.toArgb()
        stroke.pathEffect = DashPathEffect(floatArrayOf(5f * density, 3f * density), 0f)
        c.drawCircle(cx, cy, r, stroke)
    } else {
        fill.color = tint.toArgb()
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, stroke)
    }
    val tx = cx + r + 4f * density
    val ty = cy - 2f * density
    c.drawText(label, tx, ty, halo)
    c.drawText(label, tx, ty, text)
    return BitmapDescriptorFactory.fromBitmap(bmp) to Offset(cx / w, cy / h)
}

/** Diana del objetivo de replanteo con su nombre. */
private fun targetIcon(label: String, tint: Color, density: Float): Pair<BitmapDescriptor, Offset> {
    val (text, halo) = textPaints(density)
    val rr = 12f * density
    val arm = rr * 1.6f
    val textW = text.measureText(label)
    val w = (2 * arm + 4f * density + textW + 4f * density).toInt()
    val h = (2 * arm + 4f * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = arm + 2f * density
    val cy = h / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = tint.toArgb() }
    val white = Paint(p).apply { color = android.graphics.Color.WHITE; strokeWidth = 5f * density }
    c.drawCircle(cx, cy, rr, white)
    c.drawCircle(cx, cy, rr, p)
    c.drawLine(cx - arm, cy, cx + arm, cy, p)
    c.drawLine(cx, cy - arm, cx, cy + arm, p)
    c.drawText(label, cx + arm + 2f * density, cy - rr, halo)
    c.drawText(label, cx + arm + 2f * density, cy - rr, text)
    return BitmapDescriptorFactory.fromBitmap(bmp) to Offset(cx / w, cy / h)
}

/** Punto de la posición actual. */
private fun dotIcon(tint: Color, density: Float): Pair<BitmapDescriptor, Offset> {
    val r = 9f * density
    val s = (2 * r + 6f * density).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint.toArgb() }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = android.graphics.Color.WHITE
    }
    c.drawCircle(s / 2f, s / 2f, r, fill)
    c.drawCircle(s / 2f, s / 2f, r, stroke)
    return BitmapDescriptorFactory.fromBitmap(bmp) to Offset(0.5f, 0.5f)
}

/** Estilo nocturno simple para la capa de calles en tema oscuro. */
private const val NIGHT_STYLE = """[
 {"elementType":"geometry","stylers":[{"color":"#242f3e"}]},
 {"elementType":"labels.text.stroke","stylers":[{"color":"#242f3e"}]},
 {"elementType":"labels.text.fill","stylers":[{"color":"#746855"}]},
 {"featureType":"road","elementType":"geometry","stylers":[{"color":"#38414e"}]},
 {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#212a37"}]},
 {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#9ca5b3"}]},
 {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#746855"}]},
 {"featureType":"water","elementType":"geometry","stylers":[{"color":"#17263c"}]},
 {"featureType":"poi","stylers":[{"visibility":"off"}]}
]"""
