package com.topognss.app.importer

import com.topognss.app.cogo.P3

/** Vértice en coordenadas planas (del propio proyecto o las que traiga el DXF), listo para insertar. */
data class ImportedPlaneVertex(val p: P3)

/** Vértice en coordenadas geográficas WGS84 (lon, lat, alt), como las trae un KML/KMZ. */
data class ImportedGeoVertex(val lon: Double, val lat: Double, val alt: Double?)

/**
 * Parser ASCII (texto plano) de DXF minimalista: lee los group codes 10/20/30 (X/Y/Z) de las
 * entidades más comunes para importar puntos — POINT, LINE (ambos extremos) y LWPOLYLINE (todos
 * sus vértices, elevación constante vía el group code 38). No soporta DXF binario, el formato
 * antiguo POLYLINE/VERTEX (R12), splines ni bulges — alcanza para traer puntos/ejes simples de
 * un relevamiento o proyecto en CAD.
 */
fun parseDxf(text: String): List<ImportedPlaneVertex> {
    val lines = text.lines()
    val result = mutableListOf<ImportedPlaneVertex>()

    var entity: String? = null
    var x = 0.0; var y = 0.0; var z = 0.0
    var haveX = false; var haveY = false
    var x2 = 0.0; var y2 = 0.0; var z2 = 0.0
    var haveX2 = false; var haveY2 = false

    fun flushVertex() {
        if (haveX && haveY) result.add(ImportedPlaneVertex(P3(x, y, z)))
        haveX = false; haveY = false
    }
    fun flushLine() {
        if (haveX && haveY) result.add(ImportedPlaneVertex(P3(x, y, z)))
        if (haveX2 && haveY2) result.add(ImportedPlaneVertex(P3(x2, y2, z2)))
        haveX = false; haveY = false; haveX2 = false; haveY2 = false
    }
    fun closeEntity() {
        when (entity) {
            "POINT", "LWPOLYLINE" -> flushVertex()
            "LINE" -> flushLine()
        }
    }

    var i = 0
    while (i + 1 < lines.size) {
        val code = lines[i].trim().toIntOrNull()
        val value = lines[i + 1].trim()
        i += 2
        if (code == null) continue
        when (code) {
            0 -> {
                closeEntity()
                entity = value
                x = 0.0; y = 0.0; z = 0.0; haveX = false; haveY = false
                x2 = 0.0; y2 = 0.0; z2 = 0.0; haveX2 = false; haveY2 = false
            }
            10 -> when (entity) {
                "POINT", "LINE" -> { x = value.toDoubleOrNull() ?: x; haveX = true }
                "LWPOLYLINE" -> {
                    if (haveX && haveY) flushVertex() // nuevo vértice: cierra el anterior
                    x = value.toDoubleOrNull() ?: x; haveX = true
                }
            }
            20 -> when (entity) {
                "POINT", "LINE", "LWPOLYLINE" -> { y = value.toDoubleOrNull() ?: y; haveY = true }
            }
            30 -> if (entity == "POINT" || entity == "LINE") z = value.toDoubleOrNull() ?: z
            38 -> if (entity == "LWPOLYLINE") z = value.toDoubleOrNull() ?: z // elevación constante
            11 -> if (entity == "LINE") { x2 = value.toDoubleOrNull() ?: x2; haveX2 = true }
            21 -> if (entity == "LINE") { y2 = value.toDoubleOrNull() ?: y2; haveY2 = true }
            31 -> if (entity == "LINE") z2 = value.toDoubleOrNull() ?: z2
        }
    }
    closeEntity()
    return result
}

private val COORDINATES_TAG = Regex("<coordinates>([\\s\\S]*?)</coordinates>", RegexOption.IGNORE_CASE)

/**
 * Extrae las coordenadas de un KML buscando directamente las etiquetas `<coordinates>`, sin un
 * parser XML completo: alcanza para `Point`, `LineString` y `Polygon` (cualquier geometría KML
 * guarda sus vértices ahí, como "lon,lat[,alt]" separados por espacios o salsos de línea).
 */
fun parseKml(text: String): List<ImportedGeoVertex> {
    val result = mutableListOf<ImportedGeoVertex>()
    for (match in COORDINATES_TAG.findAll(text)) {
        val tuples = match.groupValues[1].trim().split(Regex("\\s+"))
        for (t in tuples) {
            if (t.isBlank()) continue
            val parts = t.split(",")
            if (parts.size < 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val alt = parts.getOrNull(2)?.toDoubleOrNull()
            result.add(ImportedGeoVertex(lon, lat, alt))
        }
    }
    return result
}
