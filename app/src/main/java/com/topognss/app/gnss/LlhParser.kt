package com.topognss.app.gnss

import kotlin.math.sqrt

/**
 * Formato LLH de Emlid (el mismo que los .pos de RTKLIB), que el Reach usa por defecto en la
 * salida de posición. Una línea por época, campos separados por espacios:
 *
 *   2026/10/24 20:44:04.000  -32.920782726  -64.123456789  520.1234  1  25  0.0080  0.0070  0.0150  ...  1.00  999.9
 *   fecha      hora          lat (°)        lon (°)        h elips.  Q  ns  sdn     sde     sdu     ...  edad  ratio
 *
 * Q (RTKLIB): 1 = FIX, 2 = FLOAT, 3 = SBAS, 4 = DGPS, 5 = SINGLE, 6 = PPP.
 * La altura es ELIPSOIDAL: este formato no trae la ondulación del geoide, así que no hay cota (MSL).
 */
object LlhParser {
    data class Llh(
        val lat: Double,
        val lon: Double,
        val ellipsoidalHeight: Double?,
        val solution: SolutionType,
        val satellites: Int?,
        val hrms: Double?,
        val vrms: Double?,
        val age: Double?,
        val utc: String?,
    )

    private val start = Regex("""^\d{4}/\d{2}/\d{2}\s""")

    /** true si la línea tiene pinta de LLH (empieza con fecha AAAA/MM/DD). */
    fun looksLike(line: CharSequence): Boolean = start.containsMatchIn(line)

    fun parse(line: String): Llh? {
        if (!looksLike(line)) return null
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 6) return null
        val lat = f[2].toDoubleOrNull() ?: return null
        val lon = f[3].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val sol = when (f[5].toIntOrNull()) {
            1 -> SolutionType.FIX
            2 -> SolutionType.FLOAT
            3, 4 -> SolutionType.DGPS
            5, 6 -> SolutionType.SINGLE
            else -> SolutionType.NONE
        }
        val sdn = f.getOrNull(7)?.toDoubleOrNull()
        val sde = f.getOrNull(8)?.toDoubleOrNull()
        val sdu = f.getOrNull(9)?.toDoubleOrNull()
        return Llh(
            lat = lat, lon = lon,
            ellipsoidalHeight = f[4].toDoubleOrNull(),
            solution = sol,
            satellites = f.getOrNull(6)?.toIntOrNull(),
            hrms = if (sdn != null && sde != null) sqrt(sdn * sdn + sde * sde) else null,
            vrms = sdu?.let { kotlin.math.abs(it) },
            age = f.getOrNull(13)?.toDoubleOrNull(),
            utc = f[1],
        )
    }
}
