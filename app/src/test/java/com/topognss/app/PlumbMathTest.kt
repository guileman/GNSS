package com.topognss.app

import com.topognss.app.sensors.plumbFromGravity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PlumbMathTest {

    @Test
    fun perfectlyPlumbGivesZeroTilt() {
        val r = plumbFromGravity(0f, 9.8f, 0f)
        assertEquals(0.0, r.tiltDeg.toDouble(), 1e-4)
        assertEquals(0.0, r.tiltRightDeg.toDouble(), 1e-4)
        assertEquals(0.0, r.tiltForwardDeg.toDouble(), 1e-4)
        assertEquals(0.0, r.mmPerMeter.toDouble(), 1e-4)
    }

    @Test
    fun fiveDegreeTiltToTheRightIsDetected() {
        val rad = Math.toRadians(5.0)
        val ax = (9.8 * sin(rad)).toFloat()
        val ay = (9.8 * cos(rad)).toFloat()
        val r = plumbFromGravity(ax, ay, 0f)
        assertEquals(5.0, r.tiltDeg.toDouble(), 0.01)
        assertEquals(5.0, r.tiltRightDeg.toDouble(), 0.01)
        assertEquals(0.0, r.tiltForwardDeg.toDouble(), 0.01)
    }

    @Test
    fun fiveDegreeTiltForwardIsDetected() {
        val rad = Math.toRadians(5.0)
        val az = (9.8 * sin(rad)).toFloat()
        val ay = (9.8 * cos(rad)).toFloat()
        val r = plumbFromGravity(0f, ay, az)
        assertEquals(5.0, r.tiltDeg.toDouble(), 0.01)
        assertEquals(0.0, r.tiltRightDeg.toDouble(), 0.01)
        assertEquals(5.0, r.tiltForwardDeg.toDouble(), 0.01)
    }

    @Test
    fun mmPerMeterMatchesTangentOfTiltAngle() {
        val rad = Math.toRadians(2.0)
        val ax = (9.8 * sin(rad)).toFloat()
        val ay = (9.8 * cos(rad)).toFloat()
        val r = plumbFromGravity(ax, ay, 0f)
        val expectedMm = Math.tan(rad) * 1000.0
        assertEquals(expectedMm, r.mmPerMeter.toDouble(), 0.1)
    }

    @Test
    fun zeroGravityVectorDoesNotCrash() {
        val r = plumbFromGravity(0f, 0f, 0f)
        assertEquals(0.0, r.tiltDeg.toDouble(), 1e-6)
    }

    @Test
    fun upsideDownOrientationStillReportsSmallTiltNearZero() {
        // Sosteniendo el teléfono "boca abajo" contra el jalón (ay negativo), a plomo.
        val r = plumbFromGravity(0f, -9.8f, 0f)
        assertEquals(0.0, r.tiltDeg.toDouble(), 1e-4)
    }
}
