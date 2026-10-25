package com.topognss.app.export

import java.util.Locale

/** Punto a exportar, independiente de Room (permite testear el DXF en JVM). */
data class DxfPoint(
    val name: String,
    val description: String,
    /** Tipo legible ("Relevado", "COGO", "A replantear", "Replanteado"). */
    val type: String,
    /** Sufijo de capa para el tipo, sin espacios ni acentos ("RELEVADO", "COGO"…). */
    val typeLayer: String,
    val e: Double,
    val n: Double,
    val z: Double?,
)

/**
 * DXF ASCII R12 (AC1009): lo abren AutoCAD, BricsCAD, QGIS, LibreCAD, Civil 3D, etc.
 *
 * - Cada punto es un POINT 3D (E, N, cota) en la capa PUNTOS_<TIPO> (para prender/apagar por tipo).
 * - Etiquetas TEXT en capas separadas: NOMBRE (arriba a la derecha del punto), DESCRIPCION y TIPO (debajo).
 */
object DxfExporter {
    private val LAYER_COLORS = mapOf(
        "PUNTOS_RELEVADO" to 4,       // cian
        "PUNTOS_COGO" to 30,          // naranja
        "PUNTOS_A_REPLANTEAR" to 6,   // magenta
        "PUNTOS_REPLANTEADO" to 3,    // verde
        "NOMBRE" to 7,                // blanco/negro
        "DESCRIPCION" to 8,           // gris
        "TIPO" to 2,                  // amarillo
    )

    fun build(points: List<DxfPoint>, textHeight: Double = 0.20): String {
        val sb = StringBuilder()
        fun g(code: Int, value: Any) { sb.append(code).append('\n').append(value).append('\n') }
        fun num(v: Double) = String.format(Locale.US, "%.4f", v)

        // HEADER
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$DWGCODEPAGE"); g(3, "ANSI_1252")
        if (points.isNotEmpty()) {
            g(9, "\$EXTMIN"); g(10, num(points.minOf { it.e })); g(20, num(points.minOf { it.n })); g(30, num(0.0))
            g(9, "\$EXTMAX"); g(10, num(points.maxOf { it.e })); g(20, num(points.maxOf { it.n })); g(30, num(0.0))
        }
        g(0, "ENDSEC")

        // TABLES: tipo de línea continuo y capas
        val layers = LAYER_COLORS.keys + points.map { "PUNTOS_${it.typeLayer}" }.distinct().filter { it !in LAYER_COLORS }
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE"); g(70, 1)
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, 0); g(3, "Solid line"); g(72, 65); g(73, 0); g(40, num(0.0))
        g(0, "ENDTAB")
        g(0, "TABLE"); g(2, "LAYER"); g(70, layers.size)
        for (l in layers) {
            g(0, "LAYER"); g(2, l); g(70, 0); g(62, LAYER_COLORS[l] ?: 7); g(6, "CONTINUOUS")
        }
        g(0, "ENDTAB")
        g(0, "ENDSEC")

        // ENTITIES
        g(0, "SECTION"); g(2, "ENTITIES")
        val h = textHeight
        for (p in points) {
            val z = p.z ?: 0.0
            g(0, "POINT"); g(8, "PUNTOS_${p.typeLayer}")
            g(10, num(p.e)); g(20, num(p.n)); g(30, num(z))
            text(::g, ::num, "NOMBRE", p.e + h * 0.6, p.n + h * 0.6, z, h, p.name)
            if (p.description.isNotBlank()) text(::g, ::num, "DESCRIPCION", p.e + h * 0.6, p.n - h * 0.9, z, h * 0.8, p.description)
            text(::g, ::num, "TIPO", p.e + h * 0.6, p.n - h * 2.1, z, h * 0.7, p.type)
        }
        g(0, "ENDSEC")
        g(0, "EOF")
        return sb.toString()
    }

    private fun text(
        g: (Int, Any) -> Unit, num: (Double) -> String,
        layer: String, x: Double, y: Double, z: Double, height: Double, value: String,
    ) {
        g(0, "TEXT"); g(8, layer)
        g(10, num(x)); g(20, num(y)); g(30, num(z))
        g(40, num(height))
        g(1, value.replace('\n', ' ').replace('\r', ' '))
    }
}
