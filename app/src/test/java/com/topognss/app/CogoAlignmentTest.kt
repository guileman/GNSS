package com.topognss.app

import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.geo.CrsType
import com.topognss.app.geo.ProjectCrs
import org.junit.Assert.assertEquals
import org.junit.Test

/** Puntos por estación quedan alineados también después de pasar por geográficas (como en el mapa). */
class CogoAlignmentTest {
    @Test fun stationPointsStayOnLine() {
        val crs = ProjectCrs(CrsType.POSGAR_GK, zone = 5)
        val pj = crs.projection
        val a = pj.forward(-31.4201, -64.1888).let { P3(it.east, it.north) }
        val b = pj.forward(-31.4192, -64.1877).let { P3(it.east, it.north) }
        val created = listOf(20.0, 50.0, 80.0).map { Cogo.stationOffset(a, b, it, 0.0) }
        // ida y vuelta por lat/lon, como se guardan y se dibujan
        val back = created.map { p -> pj.inverse(p.e, p.n).let { ll -> pj.forward(ll.lat, ll.lon) }.let { P3(it.east, it.north) } }
        val last = back.last()
        back.dropLast(1).forEach { p ->
            val (_, off) = Cogo.inverseStationOffset(a, last, p)
            assertEquals(0.0, off, 1e-6)
        }
        // con desplazamiento constante, los intermedios se apartan de la recta A→último
        val shifted = listOf(10.0, 20.0, 30.0).map { Cogo.stationOffset(a, b, it, 0.5) }
        val (_, off10) = Cogo.inverseStationOffset(a, shifted.last(), shifted[0])
        assertEquals(0.5 * (1 - 10.0 / 30.0), kotlin.math.abs(off10), 1e-3)
    }
}
