package com.topognss.app.report

import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.data.PointEntity
import com.topognss.app.data.ReportBlockEntity
import com.topognss.app.data.idList

/** Un tramo entre dos puntos consecutivos del bloque (o el lado de cierre). */
data class BlockLeg(
    val from: String,
    val to: String,
    val horizontal: Double,
    val deltaZ: Double?,
    val slope: Double?,
    val gradePercent: Double?,
    val azimuthDeg: Double,
    /** true si es el lado que cierra el polígono (último → primero). */
    val isClosing: Boolean = false,
)

data class BlockVertex(val name: String, val angleDeg: Double, val deflectionDeg: Double)

/**
 * La información que la pantalla de Medición muestra para una selección de puntos, calculada
 * a partir de los datos actuales. Es lo que se imprime en un bloque del reporte PDF.
 */
data class BlockSummary(
    val points: List<PointEntity>,
    val closed: Boolean,
    val legs: List<BlockLeg>,
    /** Suma de las distancias horizontales de [legs] (con el lado de cierre si [closed]). */
    val accumulated: Double,
    /** Distancia directa primero → último (solo con 3+ puntos abiertos). */
    val directDistance: Double?,
    /** Desnivel último − primero, si ambos tienen cota. */
    val totalDeltaZ: Double?,
    val vertices: List<BlockVertex>,
    val area: Double?,
    val perimeter: Double?,
    val clockwise: Boolean?,
)

private fun PointEntity.p3() = P3(easting, northing, elevation)

/**
 * Calcula el resumen de un bloque. Requiere 2 o más puntos. Un bloque [closed] necesita 3 o
 * más; con menos se trata como abierto. Con el polígono cerrado, la tabla de tramos incluye el
 * lado de cierre, de modo que la suma de los tramos coincide con el perímetro.
 */
fun summarizeBlock(points: List<PointEntity>, closed: Boolean): BlockSummary {
    require(points.size >= 2) { "Un bloque necesita al menos 2 puntos" }
    val isClosed = closed && points.size >= 3
    val p3 = points.map { it.p3() }
    val segments = Cogo.traverse(p3).toMutableList()
    if (isClosed) segments += Cogo.segment(p3, p3.size - 1, 0)
    val legs = segments.mapIndexed { i, s ->
        BlockLeg(
            from = points[s.from].name, to = points[s.to].name,
            horizontal = s.horizontal, deltaZ = s.deltaZ, slope = s.slope,
            gradePercent = s.gradePercent, azimuthDeg = s.azimuthDeg,
            isClosing = isClosed && i == segments.lastIndex,
        )
    }
    val first = p3.first(); val last = p3.last()
    val poly = if (isClosed) Cogo.polygon(p3) else null
    return BlockSummary(
        points = points,
        closed = isClosed,
        legs = legs,
        accumulated = legs.sumOf { it.horizontal },
        directDistance = if (points.size > 2 && !isClosed) Cogo.horizontalDistance(first, last) else null,
        totalDeltaZ = if (first.z != null && last.z != null) last.z!! - first.z!! else null,
        vertices = Cogo.vertexAngles(p3, isClosed).map {
            BlockVertex(points[it.at].name, it.clockwiseAngleDeg, it.deflectionDeg)
        },
        area = poly?.area,
        perimeter = poly?.perimeter,
        clockwise = poly?.clockwise,
    )
}

/** Puntos de un bloque, en su orden, tal como existen hoy; los que se borraron quedan afuera. */
fun resolveBlockPoints(block: ReportBlockEntity, all: List<PointEntity>): List<PointEntity> {
    val byId = all.associateBy { it.id }
    return block.idList().mapNotNull { byId[it] }
}
