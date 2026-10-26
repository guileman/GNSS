package com.topognss.app

import com.topognss.app.cogo.Cogo
import com.topognss.app.cogo.P3
import com.topognss.app.geo.CrsType
import com.topognss.app.geo.LocalGeodesy
import com.topognss.app.geo.ProjectCrs
import com.topognss.app.export.DxfExporter
import com.topognss.app.export.DxfPoint
import com.topognss.app.export.GcpExporter
import com.topognss.app.export.GcpPoint
import com.topognss.app.gnss.NmeaAggregator
import com.topognss.app.importer.CsvCol
import com.topognss.app.importer.CsvOrder
import com.topognss.app.importer.PointCsv
import com.topognss.app.gnss.NmeaParser
import com.topognss.app.gnss.SolutionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests JVM puros (sin Android) — se ejecutan en CI con `./gradlew testDebugUnitTest`. */
class CoreLogicTest {

    // Valores de referencia calculados con PROJ/pyproj 3.8 (EPSG:32721 y EPSG:5348, sin cambio de datum).
    @Test fun utm21sBuenosAires() {
        val crs = ProjectCrs(CrsType.UTM, zone = 21, south = true)
        val p = crs.projection.forward(-34.6037, -58.3816)
        assertEquals(373317.5022, p.east, 0.001)
        assertEquals(6170036.1713, p.north, 0.001)
    }

    @Test fun posgarFaja6BuenosAires() {
        val crs = ProjectCrs(CrsType.POSGAR_GK, zone = 6)
        val p = crs.projection.forward(-34.6037, -58.3816)
        assertEquals(6373266.8090, p.east, 0.001)
        assertEquals(6170469.3020, p.north, 0.001)
    }

    @Test fun inverseRoundTrip() {
        val crs = ProjectCrs(CrsType.POSGAR_GK, zone = 2)
        val p = crs.projection.forward(-54.8019, -68.3030)
        val ll = crs.projection.inverse(p.east, p.north)
        assertEquals(-54.8019, ll.lat, 1e-10)
        assertEquals(-68.3030, ll.lon, 1e-10)
    }

    @Test fun zoneHelpers() {
        assertEquals(21, ProjectCrs.utmZoneFor(-58.38))
        assertEquals(6, ProjectCrs.posgarFajaFor(-58.38))
        assertEquals(4, ProjectCrs.posgarFajaFor(-64.19))
    }

    @Test fun localDeltaMatchesProjectionAtShortRange() {
        val d = LocalGeodesy.delta(-34.6037, -58.3816, -34.6037 + 0.0001, -58.3816)
        assertEquals(0.0, d.dEast, 1e-9)
        assertEquals(11.09, d.dNorth, 0.01) // 0.0001° de latitud ≈ 11.09 m
        assertEquals(0.0, d.azimuthDeg, 1e-9)
    }

    private fun cs(body: String) = "$" + body + "*" + "%02X".format(NmeaParser.checksum(body))

    @Test fun parseGgaFix() {
        val r = NmeaParser.parse(cs("GNGGA,121212.00,3436.22200000,S,05822.89600000,W,4,24,0.6,25.123,M,14.456,M,1.0,0000"))
        val g = (r as NmeaParser.Result.Ok).sentence as NmeaParser.Gga
        assertEquals(-34.6037, g.lat!!, 1e-12)
        assertEquals(-58.3816, g.lon!!, 1e-12)
        assertEquals(4, g.quality)
        assertEquals(24, g.satellites)
    }

    @Test fun rejectsBadChecksum() {
        assertTrue(NmeaParser.parse("\$GPGGA,1,2*00") is NmeaParser.Result.Invalid)
    }

    @Test fun aggregatorBuildsEpochWithGst() {
        val agg = NmeaAggregator { 1000L }
        agg.accept(cs("GNGST,1,0.01,0.008,0.006,45.0,0.003,0.004,0.015"))
        agg.accept(cs("GNGSA,A,3,01,02,03,,,,,,,,,,1.2,0.6,1.0,1"))
        val e = agg.accept(cs("GNGGA,121212.00,3436.222,S,05822.896,W,5,18,0.7,25.0,M,14.0,M,,"))
        assertNotNull(e)
        assertEquals(SolutionType.FLOAT, e!!.solution)
        assertEquals(0.005, e.hrms!!, 1e-12)
        assertEquals(0.015, e.vrms!!, 1e-12)
        assertEquals(39.0, agg.state.ellipsoidalHeight!!, 1e-9)
        assertEquals(1.2, agg.state.pdop!!, 1e-12)
    }

    @Test fun parsesEmlidLlh() {
        val agg = NmeaAggregator { 1000L }
        val line = "2026/10/24 20:44:04.000  -32.920782726  -64.123456789   520.1234   1  25   0.0030   0.0040   0.0150   0.0010  -0.0020   0.0030   1.00  999.9"
        val e = agg.accept(line)
        assertNotNull(e)
        assertEquals(SolutionType.FIX, e!!.solution)
        assertEquals(-32.920782726, e.lat, 1e-12)
        assertEquals(0.005, e.hrms!!, 1e-12)
        assertEquals(0.015, e.vrms!!, 1e-12)
        assertNull(e.altitudeMsl)
        assertEquals(520.1234, agg.state.ellipsoidalHeight!!, 1e-9)
        assertEquals("LLH", agg.state.format)
        assertEquals(25, agg.state.satellites)
    }

    @Test fun llhFloatAndGarbage() {
        val agg = NmeaAggregator { 0L }
        val e = agg.accept("2026/10/24 20:44:05.000 -32.9 -64.1 520.0 2 18 0.05 0.05 0.10")
        assertEquals(SolutionType.FLOAT, e!!.solution)
        assertNull(agg.accept("hola mundo"))
    }

    @Test fun csvWithSpanishHeaderSemicolonAndDecimalComma() {
        val txt = "Nombre;Este;Norte;Cota;Código\nP1;5432100,25;6352145,50;520,10;BM\nP2;5432110,00;6352150,00;;esq\n;x;y;;\n"
        val r = PointCsv.parse(txt)
        assertEquals(';', r.delimiter)
        assertNotNull(r.headerCols)
        assertEquals(2, r.points.size)
        assertEquals(1, r.skipped)
        assertEquals("P1", r.points[0].name)
        assertEquals(5432100.25, r.points[0].e!!, 1e-9)
        assertEquals(6352145.50, r.points[0].n!!, 1e-9)
        assertEquals(520.10, r.points[0].z!!, 1e-9)
        assertEquals("BM", r.points[0].desc)
        assertNull(r.points[1].z)
    }

    @Test fun csvWithoutHeaderUsesChosenOrder() {
        val txt = "1,6352145.5,5432100.25,520.1,PIQ\n2 , 6352150 , 5432110 , 521 , PIQ"
        val r = PointCsv.parse(txt, CsvOrder.PNEZD)
        assertNull(r.headerCols)
        assertEquals(2, r.points.size)
        assertEquals(5432100.25, r.points[0].e!!, 1e-9)
        assertEquals(6352145.5, r.points[0].n!!, 1e-9)
        val spaces = PointCsv.parse("A 100.0 200.0 10.0\nB 101.0 201.0 11.0", CsvOrder.PENZD)
        assertEquals(' ', spaces.delimiter)
        assertEquals(2, spaces.points.size)
    }

    @Test fun csvReadsAppOwnExport() {
        val txt = "# Proyecto: X\nNombre,Este,Norte,Cota,Codigo,Tipo,Latitud,Longitud\nP001,5432100.1,6352145.2,520.3,,SURVEY,-32.9,-64.1\n"
        val r = PointCsv.parse(txt)
        assertEquals(CsvCol.NAME, r.headerCols!![0])
        assertEquals(1, r.points.size)
        assertTrue(!r.points[0].isGeographic)
    }

    @Test fun csvGeographic() {
        val r = PointCsv.parse("P,Lat,Lon,H\nA,-32.92,-64.12,500")
        assertTrue(r.points[0].isGeographic)
        assertEquals(-64.12, r.points[0].lon!!, 1e-12)
    }

    @Test fun dxfHasLayersPointsAndLabels() {
        val dxf = DxfExporter.build(
            listOf(DxfPoint("P1", "Esquina", "Relevado", "RELEVADO", 5432100.0, 6352145.0, 520.0)),
        )
        assertTrue(dxf.startsWith("0\nSECTION\n2\nHEADER"))
        assertTrue(dxf.contains("AC1009"))
        assertTrue(dxf.contains("0\nPOINT\n8\nPUNTOS_RELEVADO\n10\n5432100.0000\n20\n6352145.0000\n30\n520.0000"))
        assertTrue(dxf.contains("8\nNOMBRE\n"))
        assertTrue(dxf.contains("1\nP1\n"))
        assertTrue(dxf.contains("8\nDESCRIPCION\n"))
        assertTrue(dxf.contains("1\nEsquina\n"))
        assertTrue(dxf.contains("8\nTIPO\n"))
        assertTrue(dxf.trimEnd().endsWith("0\nEOF"))
    }

    @Test fun noEpochWithoutFix() {
        val agg = NmeaAggregator { 0L }
        assertNull(agg.accept(cs("GPGGA,121212.00,,,,,0,00,,,M,,M,,")))
    }

    private val square = listOf(P3(0.0, 0.0, 10.0), P3(0.0, 10.0, 11.0), P3(10.0, 10.0, 12.0), P3(10.0, 0.0, 10.0))

    @Test fun polygonArea() {
        val r = Cogo.polygon(square)!!
        assertEquals(100.0, r.area, 1e-9)
        assertEquals(40.0, r.perimeter, 1e-9)
        assertTrue(r.clockwise)
    }

    @Test fun azimuthAndSegments() {
        val s = Cogo.segment(square, 1, 2)
        assertEquals(90.0, s.azimuthDeg, 1e-9)
        assertEquals(10.0, s.horizontal, 1e-9)
        assertEquals(1.0, s.deltaZ!!, 1e-9)
    }

    @Test fun pointGeneration() {
        val a = square[0]; val b = square[1]
        val mid = Cogo.midpoint(a, b)
        assertEquals(5.0, mid.n, 1e-9)
        val ext = Cogo.extend(a, b, 5.0, extrapolateGrade = true)
        assertEquals(15.0, ext.n, 1e-9); assertEquals(11.5, ext.z!!, 1e-9)
        val off = Cogo.stationOffset(a, b, station = 5.0, offset = 2.0) // derecha de una línea al Norte = Este
        assertEquals(2.0, off.e, 1e-9); assertEquals(5.0, off.n, 1e-9)
        val (st, of) = Cogo.inverseStationOffset(a, b, off)
        assertEquals(5.0, st, 1e-9); assertEquals(2.0, of, 1e-9)
    }

    @Test fun gcpExportWebOdmGcpi() {
        val pts = listOf(GcpPoint("GCP001", 373317.5022, 6170036.1713, 12.345), GcpPoint("GCP 002", 373400.0, 6170100.5, null))
        val txt = GcpExporter.build(GcpExporter.Format.WEBODM_GCPI, ProjectCrs(CrsType.UTM, zone = 21, south = true), pts)
        val lines = txt.trim().lines()
        assertEquals("+proj=utm +zone=21 +south +ellps=WGS84 +datum=WGS84 +units=m +no_defs", lines[0])
        assertEquals("GCP001 373317.502 6170036.171 12.345", lines[1])
        assertEquals("GCP_002 373400.000 6170100.500 0.000", lines[2]) // sin espacios en el nombre; cota 0.0 si falta
        assertEquals(3, lines.size)
    }

    @Test fun gcpExportCsvAndCrs() {
        val crs = ProjectCrs(CrsType.POSGAR_GK, zone = 5)
        val csv = GcpExporter.build(GcpExporter.Format.CSV, crs, listOf(GcpPoint("A,1", 5428933.853, 6380679.159, 23.679))).trim().lines()
        assertEquals("GCP Label,Easting,Northing,Elevation", csv[0])
        assertEquals("A_1,5428933.853,6380679.159,23.679", csv[1])
        assertEquals("EPSG:5347", GcpExporter.crsLabel(crs))
        assertTrue(GcpExporter.projString(crs).contains("+lon_0=-60 +k=1 +x_0=5500000"))
        assertEquals("EPSG:32721", GcpExporter.crsLabel(ProjectCrs(CrsType.UTM, zone = 21, south = true)))
        assertEquals("EPSG:32619", GcpExporter.crsLabel(ProjectCrs(CrsType.UTM, zone = 19, south = false)))
    }
}
