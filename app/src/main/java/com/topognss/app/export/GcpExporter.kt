package com.topognss.app.export

import com.topognss.app.geo.CrsType
import com.topognss.app.geo.ProjectCrs
import java.util.Locale

/** Punto de control terrestre (GCP) listo para exportar: coordenadas planas del proyecto. */
data class GcpPoint(val name: String, val e: Double, val n: Double, val z: Double?)

/**
 * Exporta los GCP en los formatos que leen las herramientas de marcado de WebODM / OpenDroneMap y
 * los programas de fotogrametría más comunes.
 *
 * Importante: el `gcp_list.txt` final de ODM incluye además el píxel de cada punto en cada foto
 * (im_x, im_y, nombre de imagen). Eso se obtiene marcando los puntos sobre las fotos, y lo hace la
 * herramienta GCP Interface / GCP Editor de WebODM a partir de estas listas de coordenadas.
 */
object GcpExporter {

    enum class Format(val label: String, val detail: String, val extension: String, val mime: String) {
        WEBODM_GCPI(
            "WebODM · GCP Interface (.txt)",
            "Primera línea con el sistema de coordenadas y luego «nombre X Y Z». Es lo que carga la herramienta GCP Interface de WebODM.",
            "txt", "text/plain",
        ),
        CSV(
            "CSV · Este, Norte, Cota (.csv)",
            "«GCP Label, Easting, Northing, Elevation». Sirve para GCP Editor Pro de WebODM, Pix4D, Metashape y planillas.",
            "csv", "text/csv",
        ),
    }

    /**
     * Definición PROJ del sistema de coordenadas del proyecto: es la cabecera que esperan WebODM/ODM
     * y la que hay que escribir en la casilla de «EPSG / PROJ» de las herramientas de marcado.
     */
    fun projString(crs: ProjectCrs): String = when (crs.type) {
        CrsType.UTM ->
            "+proj=utm +zone=${crs.zone}${if (crs.south) " +south" else ""} +ellps=WGS84 +datum=WGS84 +units=m +no_defs"
        // POSGAR 2007 / Argentina faja n = EPSG:5342+n; MC = -72° + 3°·(n-1); falso este n·10^6 + 500 000
        CrsType.POSGAR_GK ->
            "+proj=tmerc +lat_0=-90 +lon_0=${-72 + 3 * (crs.zone - 1)} +k=1 +x_0=${crs.zone * 1_000_000 + 500_000} +y_0=0 " +
                "+ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"
        CrsType.LOCAL_TM ->
            "+proj=tmerc +lat_0=${deg(crs.originLat)} +lon_0=${deg(crs.originLon)} +k=1 +x_0=10000 +y_0=10000 +ellps=WGS84 +units=m +no_defs"
    }

    /** Nombre del sistema para mostrar (por ejemplo «EPSG:5347» en POSGAR). */
    fun crsLabel(crs: ProjectCrs): String = when (crs.type) {
        CrsType.UTM -> "EPSG:${(if (crs.south) 32700 else 32600) + crs.zone}"
        CrsType.POSGAR_GK -> "EPSG:${5342 + crs.zone}"
        CrsType.LOCAL_TM -> "sistema local (sin código EPSG)"
    }

    fun build(format: Format, crs: ProjectCrs, points: List<GcpPoint>): String {
        val lines = ArrayList<String>()
        when (format) {
            Format.WEBODM_GCPI -> {
                lines += projString(crs)
                // Sin espacios en el nombre: el separador de columnas es el espacio. Cota 0.0 si falta (no NaN).
                points.forEach { lines += "${label(it.name)} ${num(it.e)} ${num(it.n)} ${num(it.z ?: 0.0)}" }
            }
            Format.CSV -> {
                lines += "GCP Label,Easting,Northing,Elevation"
                points.forEach { lines += "${label(it.name).replace(',', '_')},${num(it.e)},${num(it.n)},${num(it.z ?: 0.0)}" }
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun label(name: String) = name.trim().replace(Regex("\\s+"), "_").ifEmpty { "GCP" }
    private fun num(v: Double) = String.format(Locale.US, "%.3f", v)
    private fun deg(v: Double) = String.format(Locale.US, "%.8f", v)
}
