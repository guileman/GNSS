package com.topognss.app

import com.topognss.app.cogo.HorizontalCurve
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.center
import com.topognss.app.cogo.curveStakeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class CogoCurveTest {

    @Test
    fun ninetyDegreeCurveGeometryIsCorrect() {
        val curve = HorizontalCurve(P3(0.0, 0.0), tangentAzimuthDeg = 0.0, radius = 100.0, deltaDeg = 90.0, turnRight = true)
        assertEquals(100.0, curve.tangentLength, 1e-6) // tan(45°) = 1
        assertEquals(100.0 * Math.PI / 2, curve.length, 1e-6)
        assertEquals(100.0 * sqrt(2.0), curve.longChord, 1e-6)
        assertEquals(90.0, curve.outTangentAzimuthDeg, 1e-6)
    }

    @Test
    fun pointAtZeroIsPcAndAtLengthIsPt() {
        val curve = HorizontalCurve(P3(500.0, 1000.0, 20.0), tangentAzimuthDeg = 30.0, radius = 80.0, deltaDeg = 60.0, turnRight = true)
        val p0 = curve.pointAt(0.0)
        assertEquals(curve.pc.e, p0.e, 1e-6)
        assertEquals(curve.pc.n, p0.n, 1e-6)
        val pEnd = curve.pointAt(curve.length)
        assertEquals(curve.pt.e, pEnd.e, 1e-9)
        assertEquals(curve.pt.n, pEnd.n, 1e-9)
    }

    @Test
    fun allStakedPointsLieOnTheCircle() {
        val curve = HorizontalCurve(P3(0.0, 0.0), tangentAzimuthDeg = 10.0, radius = 150.0, deltaDeg = 70.0, turnRight = true)
        val center = curve.center()
        for (frac in listOf(0.0, 0.1, 0.33, 0.5, 0.75, 1.0)) {
            val p = curve.pointAt(curve.length * frac)
            val d = hypot(p.e - center.e, p.n - center.n)
            assertEquals(150.0, d, 1e-6)
        }
    }

    @Test
    fun leftTurningCurveMirrorsRightTurning() {
        val right = HorizontalCurve(P3(0.0, 0.0), 0.0, 100.0, 45.0, turnRight = true)
        val left = HorizontalCurve(P3(0.0, 0.0), 0.0, 100.0, 45.0, turnRight = false)
        val pr = right.pointAt(right.length)
        val pl = left.pointAt(left.length)
        // Simétricos respecto del eje de la tangente (misma coordenada "adelante", E opuesto si tangente apunta al norte).
        assertEquals(pr.n, pl.n, 1e-6)
        assertEquals(-pr.e, pl.e, 1e-6)
    }

    @Test
    fun stakeoutCoversFromPcToPtInOrder() {
        val curve = HorizontalCurve(P3(0.0, 0.0), 0.0, 200.0, 50.0, turnRight = true)
        val stakes = curveStakeout(curve, interval = 20.0, startStation = 1000.0)
        assertEquals("PC", stakes.first().name)
        assertEquals(1000.0, stakes.first().station, 1e-6)
        assertEquals("PT", stakes.last().name)
        assertEquals(1000.0 + curve.length, stakes.last().station, 1e-6)
        // Las estaciones deben ser estrictamente crecientes.
        for (i in 1 until stakes.size) {
            assertTrue(stakes[i].station > stakes[i - 1].station)
        }
        // La deflexión del PT debe ser exactamente la mitad del ángulo central.
        assertEquals(curve.deltaDeg / 2, stakes.last().deflectionDeg, 1e-6)
    }
}
