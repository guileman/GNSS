package com.topognss.app

import com.topognss.app.cogo.P3
import com.topognss.app.cogo.netArea
import org.junit.Assert.assertEquals
import org.junit.Test

class CogoAreaTest {

    @Test
    fun netAreaSubtractsHoleFromOuterSquare() {
        // Terreno de 20x20 = 400 m², edificado de 5x5 = 25 m² en una esquina.
        val outer = listOf(P3(0.0, 0.0), P3(20.0, 0.0), P3(20.0, 20.0), P3(0.0, 20.0))
        val hole = listOf(P3(2.0, 2.0), P3(7.0, 2.0), P3(7.0, 7.0), P3(2.0, 7.0))
        val res = netArea(outer, listOf(hole))
        assertEquals(400.0, res.grossArea, 1e-6)
        assertEquals(25.0, res.holeAreas[0], 1e-6)
        assertEquals(375.0, res.netArea, 1e-6)
    }

    @Test
    fun noHolesMeansNetEqualsGross() {
        val outer = listOf(P3(0.0, 0.0), P3(10.0, 0.0), P3(10.0, 10.0), P3(0.0, 10.0))
        val res = netArea(outer, emptyList())
        assertEquals(100.0, res.netArea, 1e-6)
        assertEquals(0, res.holeAreas.size)
    }

    @Test
    fun multipleHolesAreSubtractedTogether() {
        val outer = listOf(P3(0.0, 0.0), P3(30.0, 0.0), P3(30.0, 30.0), P3(0.0, 30.0)) // 900 m²
        val hole1 = listOf(P3(1.0, 1.0), P3(4.0, 1.0), P3(4.0, 4.0), P3(1.0, 4.0)) // 9 m²
        val hole2 = listOf(P3(10.0, 10.0), P3(15.0, 10.0), P3(15.0, 15.0), P3(10.0, 15.0)) // 25 m²
        val res = netArea(outer, listOf(hole1, hole2))
        assertEquals(900.0 - 9.0 - 25.0, res.netArea, 1e-6)
    }

    @Test
    fun netAreaNeverGoesNegative() {
        // Hueco mayor que el polígono exterior (caso inconsistente): no debe dar superficie negativa.
        val outer = listOf(P3(0.0, 0.0), P3(5.0, 0.0), P3(5.0, 5.0), P3(0.0, 5.0)) // 25 m²
        val hole = listOf(P3(0.0, 0.0), P3(20.0, 0.0), P3(20.0, 20.0), P3(0.0, 20.0)) // 400 m²
        val res = netArea(outer, listOf(hole))
        assertEquals(0.0, res.netArea, 1e-9)
    }
}
