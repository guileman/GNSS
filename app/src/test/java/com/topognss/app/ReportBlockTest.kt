package com.topognss.app

import com.topognss.app.data.PointEntity
import com.topognss.app.data.ReportBlockEntity
import com.topognss.app.data.idList
import com.topognss.app.report.layoutPlan
import com.topognss.app.report.niceScaleLength
import com.topognss.app.report.resolveBlockPoints
import com.topognss.app.report.summarizeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloques del reporte PDF: el resumen debe coincidir con lo que muestra la pantalla de Medición. */
class ReportBlockTest {

    private fun pt(id: Long, name: String, e: Double, n: Double, z: Double? = null) =
        PointEntity(id = id, projectId = 1, name = name, lat = 0.0, lon = 0.0, easting = e, northing = n, elevation = z)

    @Test fun twoPointsGiveOneLeg() {
        val s = summarizeBlock(listOf(pt(1, "A", 100.0, 100.0, 10.0), pt(2, "B", 100.0, 200.0, 12.0)), closed = false)
        assertEquals(1, s.legs.size)
        assertEquals(100.0, s.legs[0].horizontal, 1e-9)
        assertEquals(0.0, s.legs[0].azimuthDeg, 1e-9)
        assertEquals(2.0, s.legs[0].deltaZ!!, 1e-9)
        assertEquals(2.0, s.totalDeltaZ!!, 1e-9)
        assertNull(s.area)
        assertNull(s.directDistance)
        assertTrue(s.vertices.isEmpty())
    }

    @Test fun closedSquareHasAreaPerimeterAndClosingLeg() {
        val pts = listOf(pt(1, "A", 0.0, 0.0), pt(2, "B", 0.0, 10.0), pt(3, "C", 10.0, 10.0), pt(4, "D", 10.0, 0.0))
        val s = summarizeBlock(pts, closed = true)
        assertTrue(s.closed)
        assertEquals(4, s.legs.size)
        assertTrue(s.legs.last().isClosing)
        assertEquals("D", s.legs.last().from)
        assertEquals("A", s.legs.last().to)
        assertEquals(100.0, s.area!!, 1e-9)
        assertEquals(40.0, s.perimeter!!, 1e-9)
        assertEquals(40.0, s.accumulated, 1e-9)
        assertEquals(true, s.clockwise)
        assertEquals(4, s.vertices.size)
    }

    @Test fun openThreePointsHasDirectDistanceAndInnerVertexOnly() {
        val pts = listOf(pt(1, "A", 0.0, 0.0), pt(2, "B", 0.0, 30.0), pt(3, "C", 40.0, 30.0))
        val s = summarizeBlock(pts, closed = false)
        assertFalse(s.closed)
        assertEquals(2, s.legs.size)
        assertEquals(70.0, s.accumulated, 1e-9)
        assertEquals(50.0, s.directDistance!!, 1e-9)
        assertEquals(1, s.vertices.size)
        assertEquals("B", s.vertices[0].name)
        assertNull(s.perimeter)
    }

    @Test fun closedWithTwoPointsFallsBackToOpen() {
        val s = summarizeBlock(listOf(pt(1, "A", 0.0, 0.0), pt(2, "B", 3.0, 4.0)), closed = true)
        assertFalse(s.closed)
        assertEquals(1, s.legs.size)
        assertEquals(5.0, s.legs[0].horizontal, 1e-9)
    }

    @Test fun blockPointsResolveInOrderAndSkipDeleted() {
        val all = listOf(pt(1, "A", 0.0, 0.0), pt(3, "C", 1.0, 1.0), pt(2, "B", 2.0, 2.0))
        val block = ReportBlockEntity(projectId = 1, name = "x", pointIds = "2,9,1")
        assertEquals(listOf(2L, 9L, 1L), block.idList())
        assertEquals(listOf("B", "A"), resolveBlockPoints(block, all).map { it.name })
        assertNotNull(block)
    }

    @Test fun planKeepsNorthUpAndEastRight() {
        // Línea al Norte de 100 m en un recuadro de 200×100 con margen 10: escala 0.8, centrada
        val lay = layoutPlan(listOf(pt(1, "A", 0.0, 0.0), pt(2, "B", 0.0, 100.0)), 200f, 100f, 10f)
        assertEquals(0.8f, lay.scale, 1e-6f)
        assertEquals(100f, lay.points[0].x, 1e-4f); assertEquals(90f, lay.points[0].y, 1e-4f)
        assertEquals(100f, lay.points[1].x, 1e-4f); assertEquals(10f, lay.points[1].y, 1e-4f)
        // Este hacia la derecha
        val e = layoutPlan(listOf(pt(1, "A", 0.0, 0.0), pt(2, "B", 50.0, 0.0)), 200f, 100f, 10f)
        assertTrue(e.points[1].x > e.points[0].x)
    }

    @Test fun planWithCoincidentPointsDoesNotBreak() {
        val lay = layoutPlan(listOf(pt(1, "A", 5.0, 5.0), pt(2, "B", 5.0, 5.0)), 200f, 100f, 10f)
        assertEquals(1f, lay.scale, 1e-6f)
        assertEquals(100f, lay.points[0].x, 1e-4f); assertEquals(50f, lay.points[0].y, 1e-4f)
    }

    @Test fun niceScaleLengthRoundsDown() {
        assertEquals(5.0, niceScaleLength(7.3), 1e-9)
        assertEquals(20.0, niceScaleLength(30.0), 1e-9)
        assertEquals(100.0, niceScaleLength(100.0), 1e-9)
        assertEquals(0.2, niceScaleLength(0.3), 1e-9)
    }
}
