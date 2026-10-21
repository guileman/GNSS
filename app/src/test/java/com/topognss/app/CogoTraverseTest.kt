package com.topognss.app

import com.topognss.app.cogo.P3
import com.topognss.app.cogo.TraverseLeg
import com.topognss.app.cogo.adjustTraverse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CogoTraverseTest {

    @Test
    fun noClosureErrorWhenLegsAreExact() {
        val start = P3(0.0, 0.0, 100.0)
        val legs = listOf(
            TraverseLeg("V1", 0.0, 10.0), // 10 m al norte
            TraverseLeg("V2", 90.0, 10.0), // 10 m al este
        )
        val end = P3(10.0, 10.0, 100.0) // coincide exactamente con el cierre calculado
        val adj = adjustTraverse(start, legs, end)
        assertEquals(0.0, adj.closureLinear, 1e-9)
        assertEquals(10.0, adj.vertices[0].adjusted.n, 1e-9)
        assertEquals(10.0, adj.vertices[1].adjusted.e, 1e-9)
    }

    @Test
    fun distributesClosureProportionallyToDistance() {
        val start = P3(0.0, 0.0)
        // Dos tramos de igual longitud (10 m cada uno) hacia el norte; el cierre conocido
        // está desplazado 0.2 m en E respecto de lo calculado.
        val legs = listOf(
            TraverseLeg("V1", 0.0, 10.0),
            TraverseLeg("V2", 0.0, 10.0),
        )
        val rawEnd = P3(0.0, 20.0)
        val knownEnd = P3(0.2, 20.0)
        val adj = adjustTraverse(start, legs, knownEnd)
        assertEquals(0.2, adj.closureE, 1e-9)
        // Mitad del perímetro recorrido en V1 -> mitad de la corrección.
        assertEquals(0.1, adj.vertices[0].adjusted.e, 1e-9)
        assertEquals(0.2, adj.vertices[1].adjusted.e, 1e-9)
        assertTrue(adj.precisionDenominator > 50.0)
    }

    @Test
    fun closedLoopClosesOnStartPoint() {
        val start = P3(100.0, 200.0, 50.0)
        // Cuadrado de 20 m de lado, con un pequeño error de cierre introducido en la última medición.
        val legs = listOf(
            TraverseLeg("V1", 90.0, 20.0),
            TraverseLeg("V2", 180.0, 20.0),
            TraverseLeg("V3", 270.0, 20.0),
            TraverseLeg("V4", 0.0, 20.05), // debería volver a 'start' pero sobra 0.05 m
        )
        val adj = adjustTraverse(start, legs, start)
        assertEquals(0.05, adj.closureLinear, 1e-6)
        // El último vértice ajustado debe coincidir exactamente con el punto de cierre conocido.
        assertEquals(start.e, adj.vertices.last().adjusted.e, 1e-9)
        assertEquals(start.n, adj.vertices.last().adjusted.n, 1e-9)
    }
}
