package com.topognss.app.cogo

import kotlin.math.abs

data class NetAreaResult(
    val grossArea: Double,
    val holeAreas: List<Double>,
    val netArea: Double,
    val grossPerimeter: Double,
)

/**
 * Superficie neta de un polígono [outer] (p. ej. el terreno) descontando uno o más polígonos
 * interiores [holes] (p. ej. lo edificado), usando la misma fórmula de Gauss (shoelace) que
 * [Cogo.polygon]. No valida que los huecos estén contenidos en el polígono exterior: eso queda
 * a criterio del usuario, igual que al dibujar un plano de mensura con una planilla de superficie.
 */
fun netArea(outer: List<P3>, holes: List<List<P3>>): NetAreaResult {
    val gross = Cogo.polygon(outer) ?: error("El polígono exterior necesita al menos 3 puntos")
    val holeAreas = holes.map { h -> Cogo.polygon(h)?.area ?: 0.0 }
    val totalHoles = holeAreas.sum()
    return NetAreaResult(
        grossArea = gross.area,
        holeAreas = holeAreas,
        netArea = (gross.area - totalHoles).let { if (it < 0) 0.0 else it },
        grossPerimeter = gross.perimeter,
    )
}

/** Línea de "planilla de superficie" lista para exportar/mostrar (nombre descriptivo + valor en m² y ha). */
fun NetAreaResult.summaryRows(outerLabel: String, holeLabels: List<String>): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    rows.add(outerLabel to "${grossArea.fmt()} m²  (${(grossArea / 10_000).fmt(5)} ha)")
    holeAreas.forEachIndexed { i, a ->
        val label = holeLabels.getOrNull(i) ?: "Descuento ${i + 1}"
        rows.add("− $label" to "${a.fmt()} m²")
    }
    rows.add("Superficie libre/neta" to "${netArea.fmt()} m²  (${(netArea / 10_000).fmt(5)} ha)")
    return rows
}

private fun Double.fmt(decimals: Int = 3): String {
    val factor = Math.pow(10.0, decimals.toDouble())
    val r = Math.round(abs(this) * factor) / factor
    return (if (this < 0) "-" else "") + r.toString()
}
