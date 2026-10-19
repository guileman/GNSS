package com.topognss.app

import com.topognss.app.ar.AlignSample
import com.topognss.app.ar.ArAligner
import com.topognss.app.ar.ArPose
import com.topognss.app.ar.PoseBuffer
import com.topognss.app.ar.Rigid2D
import com.topognss.app.ar.arHeadingDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Calibración AR ↔ RTK: tests JVM puros. */
class ArAlignmentTest {

    private val truth = Rigid2D(Math.toRadians(37.0), 12.0, -5.0)

    /** Camina [meters] en línea recta (con una leve curva) y devuelve las muestras con ruido. */
    private fun walk(aligner: ArAligner, meters: Double, noise: Double, rnd: Random, t0: Long = 0, start: Pair<Double, Double> = 0.0 to 0.0) : Long {
        var t = t0
        var d = 0.0
        while (d <= meters) {
            val lx = start.first + d
            val ly = start.second + 0.05 * d * d / meters
            val (e, n) = truth.toWorld(lx, ly)
            aligner.add(AlignSample(lx, ly, e + rnd.nextGaussian() * noise, n + rnd.nextGaussian() * noise, 0.02, t))
            d += 0.25
            t += 200
        }
        return t
    }

    @Test fun rigidRoundTrip() {
        val (e, n) = truth.toWorld(3.0, 4.0)
        val (x, y) = truth.toLocal(e, n)
        assertEquals(3.0, x, 1e-9)
        assertEquals(4.0, y, 1e-9)
    }

    @Test fun calibratesAfterWalking() {
        val a = ArAligner()
        walk(a, 10.0, 0.02, Random(1))
        val st = a.status
        assertTrue(st.message, st.ready)
        val tr = st.transform!!
        assertEquals(37.0, Math.toDegrees(tr.thetaRad), 0.3)
        // Un punto a 30 m debe caer a pocos cm de su posición real.
        val (e, n) = tr.toWorld(30.0, 5.0)
        val (et, nt) = truth.toWorld(30.0, 5.0)
        assertEquals(et, e, 0.2)
        assertEquals(nt, n, 0.2)
        assertTrue(st.headingSigmaDeg!! < 1.5)
    }

    @Test fun notReadyWithoutBaseline() {
        val a = ArAligner()
        walk(a, 2.0, 0.02, Random(2))
        assertFalse(a.status.ready)
    }

    @Test fun rejectsOutlierAndDetectsJump() {
        val a = ArAligner()
        var t = walk(a, 10.0, 0.02, Random(3))
        assertTrue(a.status.ready)
        // Una época aislada muy lejos no rompe la calibración.
        a.add(AlignSample(10.5, 0.0, 500.0, 500.0, 0.02, t)); t += 200
        assertTrue(a.status.ready)
        assertEquals(37.0, Math.toDegrees(a.status.transform!!.thetaRad), 0.3)
        // Un salto persistente del tracking (marco AR desplazado 3 m) dispara la recalibración.
        repeat(6) { i ->
            val lx = 11.0 + i * 0.3
            val (e, n) = truth.toWorld(lx, 0.0)
            a.add(AlignSample(lx + 3.0, 0.0, e, n, 0.02, t)); t += 200
        }
        assertFalse(a.status.ready)
        assertEquals(1, a.status.autoResets)
    }

    @Test fun poseInterpolation() {
        val b = PoseBuffer()
        b.add(ArPose(1000, 0.0, 0.0, 0.0))
        b.add(ArPose(1100, 1.0, 0.0, -2.0))
        val p = b.at(1050)
        assertNotNull(p)
        assertEquals(0.5, p!!.x, 1e-9)
        assertEquals(-1.0, p.z, 1e-9)
        assertNull(b.at(2000))
    }

    @Test fun headingFromArAxis() {
        // Con θ = 0, mirar hacia −Z de ARCore es "local +y" = Norte.
        val tr = Rigid2D(0.0, 0.0, 0.0)
        assertEquals(0.0, arHeadingDeg(tr, 0.0, -1.0)!!, 1e-9)
        assertEquals(90.0, arHeadingDeg(tr, 1.0, 0.0)!!, 1e-9)
        // θ = +90° (antihorario): local +y queda apuntando al Oeste.
        assertEquals(270.0, arHeadingDeg(Rigid2D(Math.PI / 2, 0.0, 0.0), 0.0, -1.0)!!, 1e-9)
    }
}
