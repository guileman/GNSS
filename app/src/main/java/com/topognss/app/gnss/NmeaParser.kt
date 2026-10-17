package com.topognss.app.gnss

import kotlin.math.sqrt

/**
 * Parser NMEA-0183 tolerante a errores, pensado para el flujo de un Emlid Reach RS2/RS2+/RS3
 * (también sirve con cualquier receptor que emita NMEA estándar).
 *
 * Sentencias soportadas (cualquier talker: GP, GN, GL, GA, GB, GQ):
 *  - GGA: posición, calidad de solución (4 = FIX, 5 = FLOAT…), satélites, HDOP, altitud MSL, ondulación.
 *  - GST: desvíos estándar de lat/lon/alt → HRMS / VRMS reales (habilitar GST en el receptor).
 *  - GSA: PDOP / HDOP / VDOP.
 *  - RMC / VTG: velocidad y rumbo sobre el terreno (COG), usados para orientar el replanteo en movimiento.
 *  - ZDA: hora (solo validación de trama).
 *
 * Robustez:
 *  - Valida checksum (XOR entre '$' y '*'); las tramas corruptas se descartan y se cuentan.
 *  - Ignora basura previa al '$' (frecuente al conectar Bluetooth a mitad de trama).
 *  - Campos vacíos → null, nunca lanza excepciones hacia afuera.
 */
object NmeaParser {

    sealed interface Sentence
    data class Gga(
        val utc: String?,
        val lat: Double?,
        val lon: Double?,
        val quality: Int,
        val satellites: Int?,
        val hdop: Double?,
        val altitudeMsl: Double?,
        val geoidSeparation: Double?,
        val diffAge: Double?,
        val baseId: String?,
    ) : Sentence

    data class Gst(val rms: Double?, val sigmaLat: Double?, val sigmaLon: Double?, val sigmaAlt: Double?) : Sentence {
        val hrms: Double? get() = if (sigmaLat != null && sigmaLon != null) sqrt(sigmaLat * sigmaLat + sigmaLon * sigmaLon) else null
    }

    data class Gsa(val fixType: Int?, val pdop: Double?, val hdop: Double?, val vdop: Double?) : Sentence
    data class Rmc(val valid: Boolean, val lat: Double?, val lon: Double?, val speedMs: Double?, val courseDeg: Double?, val date: String?) : Sentence
    data class Vtg(val courseDeg: Double?, val speedMs: Double?) : Sentence

    /** Sentencia propietaria ($P...) o no reconocida pero con checksum válido. */
    data class Other(val type: String, val fields: List<String>) : Sentence

    sealed interface Result {
        data class Ok(val sentence: Sentence) : Result
        data class Invalid(val reason: String) : Result
    }

    private const val KNOTS_TO_MS = 0.514444

    fun parse(rawLine: String): Result {
        val start = rawLine.indexOf('$')
        if (start < 0) return Result.Invalid("sin '$'")
        val line = rawLine.substring(start).trim()
        if (line.length < 7) return Result.Invalid("trama corta")

        val star = line.lastIndexOf('*')
        val body: String
        if (star > 0) {
            body = line.substring(1, star)
            val csText = line.substring(star + 1).take(2)
            val expected = csText.toIntOrNull(16) ?: return Result.Invalid("checksum ilegible")
            if (checksum(body) != expected) return Result.Invalid("checksum incorrecto")
        } else {
            // Algunos equipos/emuladores omiten el checksum: se acepta, pero solo si la trama parece sana.
            body = line.substring(1)
        }

        val f = body.split(',')
        val header = f[0]
        if (header.isEmpty()) return Result.Invalid("cabecera vacía")
        if (header.startsWith("P")) return Result.Ok(Other(header, f.drop(1)))
        if (header.length < 5) return Result.Invalid("cabecera inválida")
        val type = header.substring(header.length - 3) // ignora talker (GP/GN/GL/…)

        return try {
            when (type) {
                "GGA" -> Result.Ok(parseGga(f))
                "GST" -> Result.Ok(Gst(f.d(2), f.d(6), f.d(7), f.d(8)))
                "GSA" -> Result.Ok(Gsa(f.i(2), f.d(15), f.d(16), f.d(17)))
                "RMC" -> Result.Ok(parseRmc(f))
                "VTG" -> Result.Ok(Vtg(courseDeg = f.d(1), speedMs = f.d(5)?.times(KNOTS_TO_MS) ?: f.d(7)?.div(3.6)))
                else -> Result.Ok(Other(type, f.drop(1)))
            }
        } catch (e: Exception) {
            Result.Invalid("error de formato en $type: ${e.message}")
        }
    }

    private fun parseGga(f: List<String>) = Gga(
        utc = f.s(1),
        lat = parseCoord(f.s(2), f.s(3)),
        lon = parseCoord(f.s(4), f.s(5)),
        quality = f.i(6) ?: 0,
        satellites = f.i(7),
        hdop = f.d(8),
        altitudeMsl = f.d(9),
        geoidSeparation = f.d(11),
        diffAge = f.d(13),
        baseId = f.s(14),
    )

    private fun parseRmc(f: List<String>) = Rmc(
        valid = f.s(2) == "A",
        lat = parseCoord(f.s(3), f.s(4)),
        lon = parseCoord(f.s(5), f.s(6)),
        speedMs = f.d(7)?.times(KNOTS_TO_MS),
        courseDeg = f.d(8),
        date = f.s(9),
    )

    /**
     * Convierte "ddmm.mmmmmmm" / "dddmm.mmmmmmm" + hemisferio a grados decimales.
     * Se usa String para no perder decimales (el RS2 entrega 7-8 decimales de minuto ≈ 0.02 mm).
     */
    fun parseCoord(value: String?, hemi: String?): Double? {
        if (value.isNullOrEmpty() || hemi.isNullOrEmpty()) return null
        val dot = value.indexOf('.').let { if (it < 0) value.length else it }
        if (dot < 3) return null
        val degrees = value.substring(0, dot - 2).toIntOrNull() ?: return null
        val minutes = value.substring(dot - 2).toDoubleOrNull() ?: return null
        if (minutes >= 60.0) return null
        val dec = degrees + minutes / 60.0
        return when (hemi) {
            "S", "W" -> -dec
            "N", "E" -> dec
            else -> null
        }
    }

    fun checksum(body: String): Int {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return cs
    }

    private fun List<String>.s(i: Int): String? = getOrNull(i)?.takeIf { it.isNotEmpty() }
    private fun List<String>.d(i: Int): Double? = s(i)?.toDoubleOrNull()
    private fun List<String>.i(i: Int): Int? = s(i)?.toIntOrNull()
}
