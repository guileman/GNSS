package com.topognss.app

import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.cogo.intersectionByAzimuths
import com.topognss.app.cogo.intersectionByDistances
import com.topognss.app.cogo.resectionTienstra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CogoIntersectionTest {

    @Test
    fun azimuthIntersectionRecoversKnownPoint() {
        val a = P3(0.0, 0.0)
        val b = P3(50.0, 0.0)
        val target = P3(20.0, 35.0)
        val azA = Cogo.azimuth(a, target)
        val azB = Cogo.azimuth(b, target)
        val result = intersectionByAzimuths(a, azA, b, azB)
        assertNotNull(result)
        assertEquals(target.e, result!!.e, 1e-6)
        assertEquals(target.n, result.n, 1e-6)
    }

    @Test
    fun parallelAzimuthsHaveNoIntersection() {
        val a = P3(0.0, 0.0)
        val b = P3(50.0, 0.0)
        // Misma dirección (0° = norte) desde ambos puntos -> paralelas.
        assertNull(intersectionByAzimuths(a, 0.0, b, 0.0))
    }

    @Test
    fun distanceIntersectionRecoversKnownPointOnTheRightSide() {
        val a = P3(0.0, 0.0)
        val b = P3(40.0, 0.0)
        val target = P3(15.0, -25.0) // "derecha" mirando de A(0,0) hacia B(40,0) hacia el norte es +E... ver convención
        val dA = Cogo.horizontalDistance(a, target)
        val dB = Cogo.horizontalDistance(b, target)
        // Probar ambos lados y verificar que alguno coincide con target (fija la convención de signo).
        val right = intersectionByDistances(a, dA, b, dB, preferRight = true)
        val left = intersectionByDistances(a, dA, b, dB, preferRight = false)
        assertNotNull(right); assertNotNull(left)
        val matches = listOf(right!!, left!!).any { abs(it.e - target.e) < 1e-6 && abs(it.n - target.n) < 1e-6 }
        org.junit.Assert.assertTrue("ninguna de las dos soluciones coincide con el punto conocido", matches)
        // Las dos soluciones deben ser simétricas respecto de la línea A-B (misma estación, N opuesta).
        assertEquals(right.e, left.e, 1e-6)
        assertEquals(-right.n, left.n, 1e-6)
    }

    @Test
    fun incompatibleDistancesReturnNull() {
        val a = P3(0.0, 0.0)
        val b = P3(10.0, 0.0)
        // La suma de las dos "radios" es menor que la base: las circunferencias no se tocan.
        assertNull(intersectionByDistances(a, 2.0, b, 2.0))
    }

    @Test
    fun tienstraResectionRecoversKnownPoint() {
        // p1, p2, p3 deben darse en el orden en que aparecen EN SENTIDO HORARIO vistos desde el
        // punto desconocido (así es como se observan con un total station, girando el círculo
        // horizontal siempre en el mismo sentido).
        val p1 = P3(0.0, 0.0)
        val p2 = P3(100.0, 100.0)
        val p3 = P3(100.0, 0.0)
        val unknown = P3(30.0, 40.0)
        val azTo1 = Cogo.azimuth(unknown, p1)
        val azTo2 = Cogo.azimuth(unknown, p2)
        val azTo3 = Cogo.azimuth(unknown, p3)
        val angle12 = ((azTo2 - azTo1) % 360 + 360) % 360
        val angle23 = ((azTo3 - azTo2) % 360 + 360) % 360
        val result = resectionTienstra(p1, p2, p3, angle12, angle23)
        assertNotNull(result)
        assertEquals(unknown.e, result!!.e, 1e-3)
        assertEquals(unknown.n, result.n, 1e-3)
    }

    @Test
    fun tienstraResectionWorksForDifferentStationPosition() {
        // Igual que antes: orden horario visto desde el punto desconocido.
        val p1 = P3(10.0, 5.0)
        val p2 = P3(60.0, 90.0)
        val p3 = P3(80.0, -20.0)
        val unknown = P3(-15.0, 30.0)
        val azTo1 = Cogo.azimuth(unknown, p1)
        val azTo2 = Cogo.azimuth(unknown, p2)
        val azTo3 = Cogo.azimuth(unknown, p3)
        val angle12 = ((azTo2 - azTo1) % 360 + 360) % 360
        val angle23 = ((azTo3 - azTo2) % 360 + 360) % 360
        val result = resectionTienstra(p1, p2, p3, angle12, angle23)
        assertNotNull(result)
        assertEquals(unknown.e, result!!.e, 1e-3)
        assertEquals(unknown.n, result.n, 1e-3)
    }
}

private fun abs(v: Double) = kotlin.math.abs(v)
