package com.topognss.app.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.topognss.app.data.PointEntity
import com.topognss.app.data.PointKind
import com.topognss.app.data.ProjectEntity
import com.topognss.app.data.crs
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
 * Genera el PDF de replanteo: una tabla con todos los puntos de tipo "Replanteado" y sus
 * residuos respecto del objetivo (dN, dE, distancia, corte/relleno), paginada automáticamente.
 * Usa [PdfDocument] (incluido en el SDK de Android, sin dependencias nuevas).
 */
fun buildStakeoutPdf(context: Context, project: ProjectEntity, points: List<PointEntity>): File {
    val staked = points.filter { it.kind == PointKind.STAKED.name }.map(::parseStakeoutRow)

    val pageWidth = 595 // A4 a 72 dpi
    val pageHeight = 842
    val margin = 36f
    val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK }
    val subPaint = Paint().apply { textSize = 10f; color = Color.rgb(85, 85, 85) }
    val headerPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; color = Color.BLACK }
    val cellPaint = Paint().apply { textSize = 9f; color = Color.BLACK }
    val linePaint = Paint().apply { strokeWidth = 0.75f; color = Color.rgb(200, 200, 200) }

    val colX = floatArrayOf(margin, margin + 85, margin + 190, margin + 265, margin + 340, margin + 415, margin + 495)
    val headers = listOf("Punto", "Objetivo", "dN (m)", "dE (m)", "Dist (m)", "Corte/Relleno", "Fix")

    val doc = PdfDocument()
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = 0f
    var pageNum = 0

    fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNum++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        canvas = page!!.canvas
        y = margin
        canvas!!.drawText("Reporte de replanteo — ${project.name}", margin, y + 14f, titlePaint)
        y += 22f
        val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR")).format(Date())
        canvas!!.drawText("CRS: ${project.crs().describe()} · Generado: $fecha", margin, y + 8f, subPaint)
        y += 22f
        headers.forEachIndexed { i, h -> canvas!!.drawText(h, colX[i], y, headerPaint) }
        y += 4f
        canvas!!.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 14f
    }

    fun fmt(v: Double?) = v?.let { "%.3f".format(it) } ?: "—"

    newPage()
    if (staked.isEmpty()) {
        canvas!!.drawText("No hay puntos replanteados todavía en este proyecto.", margin, y, cellPaint)
    } else {
        for (row in staked) {
            if (y > pageHeight - margin - 20f) newPage()
            val cells = listOf(
                row.name, row.targetName, fmt(row.dNorth), fmt(row.dEast),
                fmt(row.distance), fmt(row.cutFill), row.solution ?: "—",
            )
            cells.forEachIndexed { i, c -> canvas!!.drawText(c, colX[i], y, cellPaint) }
            y += 16f
        }
    }
    page?.let { doc.finishPage(it) }

    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val safe = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(dir, "${safe}_replanteo.pdf")
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    return file
}
