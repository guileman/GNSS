package com.topognss.app.importer

import java.text.Normalizer

/** Columna de un archivo de puntos. */
enum class CsvCol { NAME, E, N, Z, DESC, LAT, LON, SKIP }

/** Órdenes habituales para archivos sin encabezado (P = punto, E = este, N = norte, Z = cota, D = descripción). */
enum class CsvOrder(val label: String, val cols: List<CsvCol>) {
    PENZD("P,E,N,Z,D", listOf(CsvCol.NAME, CsvCol.E, CsvCol.N, CsvCol.Z, CsvCol.DESC)),
    PNEZD("P,N,E,Z,D", listOf(CsvCol.NAME, CsvCol.N, CsvCol.E, CsvCol.Z, CsvCol.DESC)),
    ENZ("E,N,Z", listOf(CsvCol.E, CsvCol.N, CsvCol.Z)),
    NEZ("N,E,Z", listOf(CsvCol.N, CsvCol.E, CsvCol.Z)),
    PLLHD("P,Lat,Lon,H,D", listOf(CsvCol.NAME, CsvCol.LAT, CsvCol.LON, CsvCol.Z, CsvCol.DESC)),
}

/** Una fila leída: planas (e, n) o geográficas (lat, lon). */
data class CsvPoint(
    val name: String?,
    val e: Double?,
    val n: Double?,
    val z: Double?,
    val desc: String,
    val lat: Double?,
    val lon: Double?,
) {
    val isGeographic: Boolean get() = (e == null || n == null) && lat != null && lon != null
}

data class CsvParse(
    val points: List<CsvPoint>,
    /** Líneas con datos que no se pudieron leer (faltan coordenadas o no son números). */
    val skipped: Int,
    val delimiter: Char,
    /** Columnas tomadas del encabezado del archivo; null si no tenía encabezado reconocible. */
    val headerCols: List<CsvCol>?,
)

/**
 * Lector de archivos de puntos CSV/TXT (planillas, estaciones totales, Civil 3D, la propia app…).
 *
 * - Separador detectado: `;`, tab, `,` o espacios.
 * - Decimales con punto, o con coma si el separador no es la coma.
 * - Encabezado reconocido por nombre (Nombre/Punto, Este/E/X, Norte/N/Y, Cota/Z/H, Código/Descripción,
 *   Latitud, Longitud); sin encabezado se usa el orden elegido ([CsvOrder]).
 * - Ignora líneas vacías y comentarios (`#`, `//`).
 */
object PointCsv {

    fun parse(text: String, order: CsvOrder = CsvOrder.PENZD): CsvParse {
        val lines = text.removePrefix("﻿").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) return CsvParse(emptyList(), 0, ',', null)
        val delim = detectDelimiter(lines.take(20))
        val first = split(lines[0], delim)
        val header = headerCols(first)
        val cols = header ?: order.cols
        val data = if (header != null) lines.drop(1) else lines
        val decimalComma = delim != ','

        val out = ArrayList<CsvPoint>()
        var skipped = 0
        for (line in data) {
            val f = split(line, delim)
            fun field(c: CsvCol): String? = cols.indexOf(c).takeIf { it >= 0 }?.let { f.getOrNull(it)?.trim() }
            fun num(c: CsvCol): Double? = field(c)?.let { parseNumber(it, decimalComma) }
            val p = CsvPoint(
                name = field(CsvCol.NAME)?.takeIf { it.isNotBlank() },
                e = num(CsvCol.E), n = num(CsvCol.N), z = num(CsvCol.Z),
                desc = field(CsvCol.DESC).orEmpty(),
                lat = num(CsvCol.LAT), lon = num(CsvCol.LON),
            )
            val planeOk = p.e != null && p.n != null
            val geoOk = p.lat != null && p.lon != null && p.lat in -90.0..90.0 && p.lon in -180.0..180.0
            if (planeOk || geoOk) out += p else skipped++
        }
        return CsvParse(out, skipped, delim, header)
    }

    internal fun detectDelimiter(lines: List<String>): Char {
        val candidates = listOf(';', '\t', ',')
        // El que aparece la misma cantidad de veces (>0) en más líneas
        val best = candidates.maxByOrNull { d ->
            val counts = lines.map { l -> l.count { it == d } }
            val mode = counts.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            (mode?.value ?: 0) * 10 + (mode?.key ?: 0).coerceAtMost(9)
        }
        return if (best != null && lines.any { l -> l.contains(best) }) best else ' '
    }

    internal fun split(line: String, delim: Char): List<String> {
        if (delim == ' ') return line.trim().split(Regex("\\s+"))
        // Respeta comillas dobles ("texto, con coma")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delim && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun parseNumber(s: String, decimalComma: Boolean): Double? {
        val t = s.trim().replace(" ", "")
        if (t.isEmpty()) return null
        return (if (decimalComma) t.replace(',', '.') else t).toDoubleOrNull()
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    private val NAMES = mapOf(
        CsvCol.NAME to setOf("nombre", "name", "punto", "pto", "pt", "point", "id", "p"),
        CsvCol.E to setOf("este", "e", "x", "easting", "estex"),
        CsvCol.N to setOf("norte", "n", "y", "northing", "nortey"),
        CsvCol.Z to setOf("cota", "z", "h", "elev", "elevacion", "elevation", "altura", "alt", "cotaz"),
        CsvCol.DESC to setOf("codigo", "code", "desc", "descripcion", "description", "d", "obs", "observacion", "nota"),
        CsvCol.LAT to setOf("lat", "latitud", "latitude"),
        CsvCol.LON to setOf("lon", "long", "lng", "longitud", "longitude"),
    )

    /** Columnas según el encabezado, o null si la primera línea no parece un encabezado. */
    internal fun headerCols(fields: List<String>): List<CsvCol>? {
        val used = HashSet<CsvCol>()
        val cols = fields.map { f ->
            val k = norm(f)
            NAMES.entries.firstOrNull { (col, names) -> col !in used && k in names }?.key?.also { used += it } ?: CsvCol.SKIP
        }
        val recognized = cols.count { it != CsvCol.SKIP }
        val hasCoords = (CsvCol.E in used && CsvCol.N in used) || (CsvCol.LAT in used && CsvCol.LON in used)
        return if (recognized >= 2 && hasCoords) cols else null
    }
}
