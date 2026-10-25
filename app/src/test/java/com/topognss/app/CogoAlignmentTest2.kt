package com.topognss.app

import com.topognss.app.cogo.P3
import com.topognss.app.cogo.nearestOnPolyline
import com.topognss.app.cogo.pointAtStation
import com.topognss.app.cogo.polylineLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CogoAlignmentTest2 {

    @Test
    fun pointOnSegmentGivesExactStationAndOffset() {
        val path = listOf(P3(0.0, 0.0), P3(0.0, 100.0)) // eje hacia el norte
        val p = P3(5.0, 40.0) // 5 m a la derecha (este), a 40 m de avance
        val fix = nearestOnPolyline(path, p)!!
        assertEquals(40.0, fix.station, 1e-6)
        assertEquals(5.0, fix.offset, 1e-6) // derecha = positivo, mirando hacia el norte
        assertEquals(0, fix.segmentIndex)
    }

    @Test
    fun pointOnOtherSideIsNegativeOffset() {
        val path = listOf(P3(0.0, 0.0), P3(0.0, 100.0))
        val p = P3(-3.0, 60.0)
        val fix = nearestOnPolyline(path, p)!!
        assertEquals(60.0, fix.station, 1e-6)
        assertEquals(-3.0, fix.offset, 1e-6)
    }

    @Test
    fun pickCorrectSegmentOnMultiLegAlignment() {
        // Eje en L: (0,0)->(0,100)->(100,100)
        val path = listOf(P3(0.0, 0.0), P3(0.0, 100.0), P3(100.0, 100.0))
        val onSecondLeg = P3(40.0, 95.0) // cerca del 2do tramo, 5 m "a la izquierda" del eje (hacia el sur)
        val fix = nearestOnPolyline(path, onSecondLeg)!!
        assertEquals(1, fix.segmentIndex)
        // estación = 100 (primer tramo completo) + 40 (avance en el segundo tramo)
        assertEquals(140.0, fix.station, 1e-6)
    }

    @Test
    fun pointBeyondEndClampsToLastVertex() {
        val path = listOf(P3(0.0, 0.0), P3(0.0, 100.0))
        val p = P3(10.0, 150.0) // 50 m más allá del final
        val fix = nearestOnPolyline(path, p)!!
        assertEquals(100.0, fix.station, 1e-6) // longitud total del eje
        assertEquals(path[1].e, fix.point.e, 1e-6)
        assertEquals(path[1].n, fix.point.n, 1e-6)
    }

    @Test
    fun pointBeforeStartClampsToFirstVertex() {
        val path = listOf(P3(0.0, 0.0), P3(0.0, 100.0))
        val p = P3(0.0, -20.0)
        val fix = nearestOnPolyline(path, p)!!
        assertEquals(0.0, fix.station, 1e-6)
    }

    @Test
    fun tooShortPathReturnsNull() {
        assertNull(nearestOnPolyline(listOf(P3(0.0, 0.0)), P3(1.0, 1.0)))
    }

    @Test
    fun pointAtStationRoundTripsWithPolylineLength() {
        val path = listOf(P3(0.0, 0.0), P3(0.0, 60.0), P3(80.0, 60.0))
        val total = polylineLength(path)
        assertEquals(140.0, total, 1e-6)
        val mid = pointAtStation(path, 90.0)!! // 60 en el primer tramo + 30 en el segundo
        assertEquals(30.0, mid.e, 1e-6)
        assertEquals(60.0, mid.n, 1e-6)
        assertTrue(pointAtStation(path, 200.0) == null)
    }
}
