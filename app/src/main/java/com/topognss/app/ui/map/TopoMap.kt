package com.topognss.app.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.topognss.app.TopoApp
import com.topognss.app.ui.theme.LocalDarkTheme
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.hypot
import kotlin.math.max

// =============================================================================================
// Capas base
// =============================================================================================

/**
 * Capas disponibles. Todas son servicios de teselas públicos sin clave de API.
 * osmdroid guarda en caché las teselas vistas: la zona de trabajo queda disponible sin conexión
 * si se recorrió el mapa antes de salir al campo.
 */
enum class MapLayer(val label: String) {
    STREETS("Calles"),
    SATELLITE("Satélite"),
    HYBRID("Híbrido"),
    TOPO("Topográfico"),
    NONE("Sin mapa"),
}

/** Servicios Esri ArcGIS Online (orden de URL z/y/x). */
private class EsriTileSource(name: String, service: String, maxZoom: Int = 19) : OnlineTileSourceBase(
    name, 0, maxZoom, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

private val ESRI_IMAGERY: ITileSource by lazy { EsriTileSource("EsriWorldImagery", "World_Imagery") }
private val ESRI_LABELS: ITileSource by lazy { EsriTileSource("EsriLabels", "Reference/World_Boundaries_and_Places") }
private val ESRI_ROADS: ITileSource by lazy { EsriTileSource("EsriRoads", "Reference/World_Transportation") }

private fun baseSource(layer: MapLayer): ITileSource = when (layer) {
    MapLayer.STREETS, MapLayer.NONE -> TileSourceFactory.MAPNIK
    MapLayer.SATELLITE, MapLayer.HYBRID -> ESRI_IMAGERY
    MapLayer.TOPO -> TileSourceFactory.OpenTopo
}

// =============================================================================================
// Datos que dibuja el mapa
// =============================================================================================

data class MapPoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val color: Color,
    /** Orden de selección (1, 2, 3…) para COGO; null si no está seleccionado. */
    val order: Int? = null,
)

data class MapMe(val lat: Double, val lon: Double, val color: Color, val hrms: Double?)

// =============================================================================================
// Overlay propio: dibuja todo en un solo pase (más rápido que cientos de Marker)
// =============================================================================================

private class TopoOverlay(private val density: Float) : Overlay() {
    var points: List<MapPoint> = emptyList()
    var me: MapMe? = null
    var target: MapPoint? = null
    var line: List<GeoPoint> = emptyList()
    var closeLine: Boolean = false
    var onTap: ((Long) -> Unit)? = null

    private val p = Point()
    private val q = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = android.graphics.Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFFF2B705.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE; strokeWidth = 3f * density
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection

        // Línea / polígono de selección (COGO) o hacia el objetivo (replanteo)
        if (line.size >= 2) {
            val pts = if (closeLine && line.size >= 3) line + line.first() else line
            for (i in 0 until pts.size - 1) {
                proj.toPixels(pts[i], p); proj.toPixels(pts[i + 1], q)
                c.drawLine(p.x.toFloat(), p.y.toFloat(), q.x.toFloat(), q.y.toFloat(), linePaint)
            }
        }

        // Puntos del proyecto
        val r = 6f * density
        for (pt in points) {
            proj.toPixels(GeoPoint(pt.lat, pt.lon), p)
            fill.color = pt.color.toArgb()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), if (pt.order != null) r * 1.5f else r, fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), if (pt.order != null) r * 1.5f else r, stroke)
            val label = if (pt.order != null) "${pt.order}·${pt.name}" else pt.name
            val tx = p.x + r * 1.6f
            val ty = p.y - r * 0.6f
            c.drawText(label, tx, ty, halo)
            c.drawText(label, tx, ty, text)
        }

        // Objetivo de replanteo: diana
        target?.let { t ->
            proj.toPixels(GeoPoint(t.lat, t.lon), p)
            fill.color = t.color.toArgb()
            val x = p.x.toFloat(); val y = p.y.toFloat()
            val rr = 12f * density
            c.drawCircle(x, y, rr, stroke)
            linePaint.color.let { old ->
                linePaint.color = t.color.toArgb()
                c.drawCircle(x, y, rr, linePaint)
                c.drawLine(x - rr * 1.6f, y, x + rr * 1.6f, y, linePaint)
                c.drawLine(x, y - rr * 1.6f, x, y + rr * 1.6f, linePaint)
                linePaint.color = old
            }
            c.drawText(t.name, x + rr * 1.4f, y - rr, halo)
            c.drawText(t.name, x + rr * 1.4f, y - rr, text)
        }

        // Posición del receptor con círculo de precisión (HRMS a escala real)
        me?.let { m ->
            val gp = GeoPoint(m.lat, m.lon)
            proj.toPixels(gp, p)
            m.hrms?.let { h ->
                // píxeles por metro: proyectar un punto 1 m al norte
                proj.toPixels(GeoPoint(m.lat + 1.0 / 111_320.0, m.lon), q)
                val pxPerM = hypot((q.x - p.x).toFloat(), (q.y - p.y).toFloat())
                val rad = (h * pxPerM).toFloat()
                if (rad > 2f) {
                    fill.color = m.color.copy(alpha = 0.25f).toArgb()
                    c.drawCircle(p.x.toFloat(), p.y.toFloat(), rad, fill)
                }
            }
            fill.color = m.color.toArgb()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f * density, fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f * density, stroke)
        }
    }

    /** Toque sobre un punto: devuelve el más cercano dentro de 28 dp. */
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val cb = onTap ?: return false
        val proj = mapView.projection
        // El toque viene en coordenadas de pantalla; se pasa a geográficas y de vuelta a píxeles
        // del mismo sistema que usa toPixels (así funciona también con el mapa rotado).
        val tap = Point()
        proj.toPixels(proj.fromPixels(e.x.toInt(), e.y.toInt()), tap)
        var best: MapPoint? = null
        var bestD = 28f * density
        for (pt in points) {
            proj.toPixels(GeoPoint(pt.lat, pt.lon), p)
            val d = hypot((p.x - tap.x).toFloat(), (p.y - tap.y).toFloat())
            if (d < bestD) { bestD = d; best = pt }
        }
        best?.let { cb(it.id); return true }
        return false
    }
}

// =============================================================================================
// Composable
// =============================================================================================

/**
 * Mapa base con capas seleccionables y el overlay topográfico.
 *
 * @param fitKey cada vez que cambia se hace zoom para encuadrar puntos + posición + objetivo.
 * @param follow si es true el mapa sigue la posición del receptor.
 * @param rotationDeg rumbo del operador (°) para girar el mapa "rumbo arriba"; null = norte arriba.
 */
@Composable
fun TopoMap(
    modifier: Modifier = Modifier,
    points: List<MapPoint> = emptyList(),
    me: MapMe? = null,
    target: MapPoint? = null,
    line: List<Pair<Double, Double>> = emptyList(),
    closeLine: Boolean = false,
    follow: Boolean = false,
    rotationDeg: Float? = null,
    fitKey: Any? = Unit,
    onPointTap: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TopoApp
    val layerName by app.prefs.mapLayer.collectAsState()
    val layer = runCatching { MapLayer.valueOf(layerName) }.getOrDefault(MapLayer.STREETS)
    val dark = LocalDarkTheme.current
    val density = context.resources.displayMetrics.density

    val overlay = remember { TopoOverlay(density) }
    val mapView = remember { createMapView(context, overlay) }
    val hybridOverlays = remember { mutableListOf<TilesOverlay>() }
    var lastLayer by remember { mutableStateOf<MapLayer?>(null) }
    var centerRequest by remember { mutableStateOf(0) }

    // Ciclo de vida del MapView (pausa descargas y sensores internos)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Encuadre automático
    LaunchedEffect(fitKey, centerRequest) {
        val geo = buildList {
            points.forEach { add(GeoPoint(it.lat, it.lon)) }
            me?.let { add(GeoPoint(it.lat, it.lon)) }
            target?.let { add(GeoPoint(it.lat, it.lon)) }
        }
        if (centerRequest > 0 && me != null) {
            mapView.controller.animateTo(GeoPoint(me.lat, me.lon), max(mapView.zoomLevelDouble, 19.0), 400L)
        } else if (geo.isNotEmpty()) {
            mapView.post { fitTo(mapView, geo) }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            update = { mv ->
                // Capa base (sólo si cambió, para no recargar teselas en cada recomposición)
                if (lastLayer != layer) {
                    hybridOverlays.forEach { it.onDetach(mv); mv.overlays.remove(it) }
                    hybridOverlays.clear()
                    mv.setTileSource(baseSource(layer))
                    if (layer == MapLayer.HYBRID) {
                        listOf(ESRI_ROADS, ESRI_LABELS).forEachIndexed { i, src ->
                            val prov = MapTileProviderBasic(mv.context.applicationContext, src)
                            val to = TilesOverlay(prov, mv.context.applicationContext).apply {
                                setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setLoadingLineColor(android.graphics.Color.TRANSPARENT)
                            }
                            mv.overlays.add(i, to)
                            hybridOverlays.add(to)
                        }
                    }
                    lastLayer = layer
                }
                mv.overlayManager.tilesOverlay.isEnabled = layer != MapLayer.NONE
                // En modo oscuro se invierten los colores de la capa de calles (más legible de noche)
                mv.overlayManager.tilesOverlay.setColorFilter(
                    if (dark && layer == MapLayer.STREETS) TilesOverlay.INVERT_COLORS else null
                )
                mv.setBackgroundColor(if (dark) 0xFF0F1416.toInt() else 0xFFE8ECEE.toInt())

                overlay.points = points
                overlay.me = me
                overlay.target = target
                overlay.line = line.map { GeoPoint(it.first, it.second) }
                overlay.closeLine = closeLine
                overlay.onTap = onPointTap
                mv.mapOrientation = -(rotationDeg ?: 0f)
                if (follow && me != null) mv.controller.setCenter(GeoPoint(me.lat, me.lon))
                mv.invalidate()
            },
        )

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

@Composable
private fun LayerSelector(current: MapLayer, onSelect: (MapLayer) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallFloatingActionButton(onClick = { open = true }) {
            Text(current.label.take(3), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapLayer.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text((if (l == current) "✓ " else "   ") + l.label) },
                    onClick = { onSelect(l); open = false },
                )
            }
        }
    }
}

private fun createMapView(context: Context, overlay: TopoOverlay): MapView = MapView(context).apply {
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    isTilesScaledToDpi = true
    minZoomLevel = 3.0
    maxZoomLevel = 23.0          // más allá del zoom nativo se reescalan teselas: útil a escala de obra
    controller.setZoom(5.0)
    controller.setCenter(GeoPoint(-34.6, -58.4))
    overlays.add(overlay)
    overlays.add(CopyrightOverlay(context))
}

/** Encuadra una lista de puntos con margen; si es un solo punto, zoom de obra. */
private fun fitTo(mv: MapView, geo: List<GeoPoint>) {
    if (mv.width == 0 || mv.height == 0) return
    var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
    geo.forEach { g ->
        n = max(n, g.latitude); s = minOf(s, g.latitude)
        e = max(e, g.longitude); w = minOf(w, g.longitude)
    }
    val spanMin = 0.0003 // ~30 m: evita zoom infinito con un único punto
    if (n - s < spanMin) { val c = (n + s) / 2; n = c + spanMin / 2; s = c - spanMin / 2 }
    if (e - w < spanMin) { val c = (e + w) / 2; e = c + spanMin / 2; w = c - spanMin / 2 }
    mv.zoomToBoundingBox(BoundingBox(n, e, s, w), false, (48 * mv.resources.displayMetrics.density).toInt())
}

/** Inicialización global de osmdroid (llamar una vez en Application.onCreate). */
fun initOsmdroid(context: Context) {
    val cfg = org.osmdroid.config.Configuration.getInstance()
    cfg.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    // User-Agent propio: requisito de la política de uso de teselas de OpenStreetMap
    cfg.userAgentValue = context.packageName
    val base = java.io.File(context.filesDir, "osmdroid")
    cfg.osmdroidBasePath = base
    cfg.osmdroidTileCache = java.io.File(base, "tiles")
    cfg.tileFileSystemCacheMaxBytes = 600L * 1024 * 1024   // hasta 600 MB de caché offline
    cfg.tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
}
