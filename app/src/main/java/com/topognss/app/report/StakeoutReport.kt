package com.topognss.app.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.ProjectEntity
import com.topognss.app.data.ReportBlockEntity
import com.topognss.app.data.crs
import com.topognss.app.data.nomenclaturaCatastral
import com.topognss.app.cogo.formatDms
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una fila de la planilla de replanteo: el residuo registrado al guardar un punto "Replanteado"
 * (ver [com.topognss.app.ui.stakeout.StakeoutViewModel.record]), leído de vuelta desde su nota.
 */
data class StakeoutRow(
    val name: String,
    val targetName: String,
    val dNorth: Double?,
    val dEast: Double?,
    val distance: Double?,
    val cutFill: Double?,
    val solution: String?,
    val hrms: Double?,
)

private val NOTE_REGEX = Regex(
    """Replanteo de (.+?): dN=(-?[\d.]+) dE=(-?[\d.]+) dist=(-?[\d.]+) dH=(-?[\d.]+)"""
)

/** Interpreta la nota de un punto replanteado; si no matchea el formato esperado, deja los residuos en null. */
fun parseStakeoutRow(p: PointEntity): StakeoutRow {
    val m = NOTE_REGEX.find(p.note)
    return if (m != null) {
        StakeoutRow(
            name = p.name,
            targetName = m.groupValues[1],
            dNorth = m.groupValues[2].toDoubleOrNull(),
            dEast = m.groupValues[3].toDoubleOrNull(),
            distance = m.groupValues[4].toDoubleOrNull(),
            cutFill = m.groupValues[5].toDoubleOrNull(),
            solution = p.solution,
            hrms = p.hrms,
        )
    } else {
        StakeoutRow(p.name, p.name, null, null, null, null, p.solution, p.hrms)
    }
}

/**
 * Genera el reporte PDF del proyecto con dos secciones, cada una solo si tiene contenido:
 *
 * 1. **Mediciones**: un cuadro por cada bloque de puntos agregado desde Cálculo → Medición
 *    (nombre, descripción, coordenadas, tramos, ángulos y, si está cerrado, superficie y perímetro).
 * 2. **Replanteo**: tabla de los puntos "Replanteado" con sus residuos (dN, dE, distancia, corte/relleno).
 *
 * Se pagina automáticamente. Usa [PdfDocument] (incluido en el SDK de Android, sin dependencias nuevas).
 */
fun buildReportPdf(
    context: Context,
    project: ProjectEntity,
    points: List<PointEntity>,
    blocks: List<ReportBlockEntity>,
): File {
    val staked = points.filter { it.kind == PointKind.STAKED.name }.map(::parseStakeoutRow)

    val pageWidth = 595 // A4 a 72 dpi
    val pageHeight = 842
    val margin = 36f
    val contentWidth = pageWidth - 2 * margin
    val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK }
    val sectionPaint = Paint().apply { textSize = 13f; isFakeBoldText = true; color = Color.BLACK }
    val blockPaint = Paint().apply { textSize = 11f; isFakeBoldText = true; color = Color.BLACK }
    val subPaint = Paint().apply { textSize = 10f; color = Color.rgb(85, 85, 85) }
    val headerPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; color = Color.BLACK }
    val cellPaint = Paint().apply { textSize = 9f; color = Color.BLACK }
    val linePaint = Paint().apply { strokeWidth = 0.75f; color = Color.rgb(200, 200, 200) }
    val footerPaint = Paint().apply { textSize = 8f; color = Color.rgb(120, 120, 120); textAlign = Paint.Align.RIGHT }

    val doc = PdfDocument()
    var page: PdfDocument.Page? = null
    var canvas: Canvas = Canvas()
    var y = 0f
    var pageNum = 0
    val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR")).format(Date())

    fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNum++
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        page = pg
        canvas = pg.canvas
        y = margin
        canvas.drawText("Reporte — ${project.name}", margin, y + 14f, titlePaint)
        y += 22f
        canvas.drawText("CRS: ${project.crs().describe()} · Generado: $fecha", margin, y + 8f, subPaint)
        y += 14f
        if (pageNum == 1) {
            project.nomenclaturaCatastral().takeIf { it.isNotBlank() }?.let {
                canvas.drawText("Nomenclatura catastral: $it", margin, y + 8f, subPaint)
                y += 14f
            }
        }
        y += 8f
        canvas.drawText("Página $pageNum", pageWidth - margin, pageHeight - 18f, footerPaint)
    }

    /** Si no entran [h] puntos más en la página, abre una nueva. Devuelve true si cambió de página. */
    fun ensure(h: Float): Boolean {
        if (y + h <= pageHeight - margin - 14f) return false
        newPage()
        return true
    }

    /** Recorta el texto para que entre en [maxWidth], con «…» al final si hace falta. */
    fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var n = paint.breakText(text, true, maxWidth - paint.measureText("…"), null)
        if (n < 0) n = 0
        return text.substring(0, n) + "…"
    }

    /** Parte el texto en líneas que entran en [maxWidth] (cortando en espacios cuando se puede). */
    fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        for (para in text.split('\n')) {
            var rest = para.trim()
            if (rest.isEmpty()) { lines += ""; continue }
            while (rest.isNotEmpty()) {
                val n = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                if (n >= rest.length) { lines += rest; break }
                val cut = rest.lastIndexOf(' ', n - 1).takeIf { it > 0 } ?: n
                lines += rest.substring(0, cut).trimEnd()
                rest = rest.substring(cut).trimStart()
            }
        }
        return lines
    }

    fun textBlock(text: String, paint: Paint, lineHeight: Float = 12f) {
        for (line in wrap(text, paint, contentWidth)) {
            ensure(lineHeight)
            canvas.drawText(line, margin, y + 8f, paint)
            y += lineHeight
        }
    }

    /** Tabla con encabezado que se repite al cambiar de página. [colX] son desplazamientos desde el margen. */
    fun table(headers: List<String>, colX: FloatArray, widths: FloatArray, rows: List<List<String>>) {
        fun header() {
            headers.forEachIndexed { i, h -> canvas.drawText(h, margin + colX[i], y + 8f, headerPaint) }
            y += 12f
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 4f
        }
        ensure(40f)
        header()
        for (row in rows) {
            if (ensure(14f)) header()
            row.forEachIndexed { i, c -> canvas.drawText(fit(c, cellPaint, widths[i]), margin + colX[i], y + 8f, cellPaint) }
            y += 13f
        }
        y += 4f
    }

    fun fmt(v: Double?, d: Int = 3) = v?.let { "%.${d}f".format(Locale.US, it) } ?: "—"

    newPage()

    // ---- 1. Mediciones (bloques) ----
    if (blocks.isNotEmpty()) {
        ensure(30f)
        canvas.drawText("Mediciones", margin, y + 12f, sectionPaint)
        y += 20f
        blocks.forEachIndexed { index, block ->
            val pts = resolveBlockPoints(block, points)
            val expected = block.pointIds.split(',').count { it.isNotBlank() }
            ensure(60f)
            canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
            y += 6f
            canvas.drawText(fit("${index + 1}. ${block.name}", blockPaint, contentWidth), margin, y + 10f, blockPaint)
            y += 16f
            if (block.description.isNotBlank()) textBlock(block.description, subPaint)
            if (pts.size < 2) {
                textBlock("Este bloque ya no tiene puntos suficientes (se eliminaron del proyecto).", cellPaint)
                y += 8f
                return@forEachIndexed
            }
            if (pts.size < expected) {
                textBlock("Atención: faltan ${expected - pts.size} punto(s) que se eliminaron del proyecto.", cellPaint)
            }
            val sum = summarizeBlock(pts, block.closed)
            textBlock(
                "Puntos: " + pts.joinToString(" → ") { it.name } + if (sum.closed) " → ${pts.first().name} (cerrado)" else "",
                subPaint,
            )
            y += 4f

            table(
                listOf("Punto", "Este (m)", "Norte (m)", "Cota (m)"),
                floatArrayOf(0f, 150f, 270f, 390f), floatArrayOf(140f, 110f, 110f, 110f),
                pts.map { listOf(it.name, fmt(it.easting), fmt(it.northing), fmt(it.elevation)) },
            )
            table(
                listOf("Tramo", "Dist. horiz.", "Desnivel", "Dist. incl.", "Pend. (%)", "Azimut"),
                floatArrayOf(0f, 150f, 215f, 280f, 345f, 405f), floatArrayOf(145f, 62f, 62f, 62f, 56f, 118f),
                sum.legs.map {
                    listOf(
                        it.from + " → " + it.to + if (it.isClosing) " (cierre)" else "",
                        fmt(it.horizontal), fmt(it.deltaZ), fmt(it.slope), fmt(it.gradePercent, 2), formatDms(it.azimuthDeg),
                    )
                },
            )
            val resumen = buildList {
                add("Acumulado: ${fmt(sum.accumulated)} m")
                sum.directDistance?.let { add("Directa ${pts.first().name} → ${pts.last().name}: ${fmt(it)} m") }
                sum.totalDeltaZ?.let { add("Desnivel total: ${fmt(it)} m") }
            }
            textBlock(resumen.joinToString(" · "), cellPaint)
            y += 4f
            if (sum.vertices.isNotEmpty()) {
                table(
                    listOf("Vértice", "Ángulo horario", "Deflexión"),
                    floatArrayOf(0f, 150f, 300f), floatArrayOf(140f, 140f, 140f),
                    sum.vertices.map {
                        listOf(
                            it.name, formatDms(it.angleDeg),
                            (if (it.deflectionDeg >= 0) "D " else "I ") + formatDms(kotlin.math.abs(it.deflectionDeg)),
                        )
                    },
                )
            }
            if (sum.closed && sum.area != null && sum.perimeter != null) {
                ensure(40f)
                canvas.drawText("Superficie (Gauss)", margin, y + 9f, headerPaint)
                y += 14f
                textBlock(
                    "Área: ${fmt(sum.area)} m² (${fmt(sum.area / 10_000, 5)} ha) · Perímetro: ${fmt(sum.perimeter)} m · " +
                        "Sentido: ${if (sum.clockwise == true) "horario" else "antihorario"}",
                    cellPaint,
                )
            }
            y += 10f
        }
    }

    // ---- 2. Replanteo ----
    if (staked.isNotEmpty()) {
        ensure(60f)
        canvas.drawText("Replanteo", margin, y + 12f, sectionPaint)
        y += 20f
        table(
            listOf("Punto", "Objetivo", "dN (m)", "dE (m)", "Dist (m)", "Corte/Relleno", "Fix"),
            floatArrayOf(0f, 85f, 190f, 265f, 340f, 415f, 495f), floatArrayOf(80f, 100f, 70f, 70f, 70f, 75f, 28f),
            staked.map { listOf(it.name, it.targetName, fmt(it.dNorth), fmt(it.dEast), fmt(it.distance), fmt(it.cutFill), it.solution ?: "—") },
        )
    }

    if (blocks.isEmpty() && staked.isEmpty()) {
        canvas.drawText("Todavía no hay mediciones agregadas ni puntos replanteados en este proyecto.", margin, y + 9f, cellPaint)
    }
    page?.let { doc.finishPage(it) }

    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val safe = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(dir, "${safe}_reporte.pdf")
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    return file
}
