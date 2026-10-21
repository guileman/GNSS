package com.topognss.app

import com.topognss.app.importer.parseDxf
import com.topognss.app.importer.parseKml
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoFileImporterTest {

    @Test
    fun parsesSinglePoint() {
        val dxf = """
            0
            POINT
            8
            0
            10
            100.5
            20
            200.25
            30
            10.0
            0
            ENDSEC
        """.trimIndent()
        val pts = parseDxf(dxf)
        assertEquals(1, pts.size)
        assertEquals(100.5, pts[0].p.e, 1e-9)
        assertEquals(200.25, pts[0].p.n, 1e-9)
        assertEquals(10.0, pts[0].p.z!!, 1e-9)
    }

    @Test
    fun parsesLineAsTwoVertices() {
        val dxf = """
            0
            LINE
            8
            0
            10
            0.0
            20
            0.0
            30
            0.0
            11
            10.0
            21
            20.0
            31
            1.5
            0
            ENDSEC
        """.trimIndent()
        val pts = parseDxf(dxf)
        assertEquals(2, pts.size)
        assertEquals(0.0, pts[0].p.e, 1e-9)
        assertEquals(10.0, pts[1].p.e, 1e-9)
        assertEquals(20.0, pts[1].p.n, 1e-9)
        assertEquals(1.5, pts[1].p.z!!, 1e-9)
    }

    @Test
    fun parsesLwPolylineWithAllVertices() {
        val dxf = """
            0
            LWPOLYLINE
            8
            0
            90
            3
            38
            5.0
            10
            0.0
            20
            0.0
            10
            10.0
            20
            0.0
            10
            10.0
            20
            10.0
            0
            ENDSEC
        """.trimIndent()
        val pts = parseDxf(dxf)
        assertEquals(3, pts.size)
        assertEquals(0.0, pts[0].p.e, 1e-9); assertEquals(0.0, pts[0].p.n, 1e-9)
        assertEquals(10.0, pts[1].p.e, 1e-9); assertEquals(0.0, pts[1].p.n, 1e-9)
        assertEquals(10.0, pts[2].p.e, 1e-9); assertEquals(10.0, pts[2].p.n, 1e-9)
        pts.forEach { assertEquals(5.0, it.p.z!!, 1e-9) } // elevación constante del 38
    }

    @Test
    fun ignoresUnknownEntitiesAndEmptyInput() {
        assertEquals(0, parseDxf("").size)
        val dxf = """
            0
            CIRCLE
            10
            1.0
            20
            1.0
            40
            5.0
            0
            ENDSEC
        """.trimIndent()
        assertEquals(0, parseDxf(dxf).size) // CIRCLE no es una entidad soportada
    }

    @Test
    fun parsesKmlPointAndLineString() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <Point><coordinates>-58.3816,-34.6037,25.0</coordinates></Point>
                </Placemark>
                <Placemark>
                  <LineString>
                    <coordinates>
                      -58.40,-34.60,10
                      -58.41,-34.61,12
                    </coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()
        val pts = parseKml(kml)
        assertEquals(3, pts.size)
        assertEquals(-58.3816, pts[0].lon, 1e-9)
        assertEquals(-34.6037, pts[0].lat, 1e-9)
        assertEquals(25.0, pts[0].alt!!, 1e-9)
        assertEquals(-58.40, pts[1].lon, 1e-9)
        assertEquals(-58.41, pts[2].lon, 1e-9)
    }

    @Test
    fun kmlWithoutAltitudeLeavesAltNull() {
        val kml = "<coordinates>-58.0,-34.0</coordinates>"
        val pts = parseKml(kml)
        assertEquals(1, pts.size)
        assertEquals(null, pts[0].alt)
    }

    @Test
    fun emptyKmlReturnsEmptyList() {
        assertEquals(0, parseKml("<kml></kml>").size)
    }
}
