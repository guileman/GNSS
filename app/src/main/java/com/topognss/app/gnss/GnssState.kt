package com.topognss.app.gnss

/** Calidad de solución según el campo 6 de GGA. */
enum class SolutionType(val code: Int, val label: String) {
    NONE(0, "SIN SOLUCIÓN"),
    SINGLE(1, "SINGLE"),
    DGPS(2, "DGPS"),
    PPS(3, "PPS"),
    FIX(4, "FIX"),
    FLOAT(5, "FLOAT"),
    DEAD_RECKONING(6, "DR"),
    MANUAL(7, "MANUAL"),
    SIMULATION(8, "SIM");

    /** Ranking para filtros: mayor es mejor. */
    val rank: Int get() = when (this) {
        FIX -> 4; FLOAT -> 3; DGPS -> 2; SINGLE, PPS -> 1; else -> 0
    }

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: NONE
    }
}

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Fotografía inmutable del estado del receptor, publicada por StateFlow. */
data class GnssState(
    val connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionLabel: String = "",
    val errorMessage: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Altura sobre el geoide (cota) reportada en GGA. */
    val altitudeMsl: Double? = null,
    val geoidSeparation: Double? = null,
    val solution: SolutionType = SolutionType.NONE,
    val satellites: Int? = null,
    val hdop: Double? = null,
    val pdop: Double? = null,
    val vdop: Double? = null,
    /** Precisión horizontal 1σ combinada (m), desde GST. */
    val hrms: Double? = null,
    /** Precisión vertical 1σ (m), desde GST. */
    val vrms: Double? = null,
    val speedMs: Double? = null,
    val courseDeg: Double? = null,
    val diffAge: Double? = null,
    val utc: String? = null,
    /** % batería si llega en alguna trama propietaria; el RS2 no la emite por NMEA estándar. */
    val batteryPercent: Int? = null,
    val lastEpochMillis: Long = 0L,
    val sentences: Long = 0,
    val badSentences: Long = 0,
) {
    /** Altura elipsoidal h = H + N. */
    val ellipsoidalHeight: Double?
        get() = if (altitudeMsl != null) altitudeMsl + (geoidSeparation ?: 0.0) else null

    val hasPosition: Boolean get() = lat != null && lon != null && solution != SolutionType.NONE

    fun isStale(now: Long, timeoutMs: Long = 3000): Boolean =
        lastEpochMillis == 0L || now - lastEpochMillis > timeoutMs
}

/** Una época de posición (una GGA) con la precisión vigente, usada para promediar. */
data class Epoch(
    val lat: Double,
    val lon: Double,
    val altitudeMsl: Double?,
    val ellipsoidalHeight: Double?,
    val solution: SolutionType,
    val satellites: Int?,
    val hrms: Double?,
    val vrms: Double?,
    val pdop: Double?,
    val timeMillis: Long,
)

/**
 * Integra las sentencias sueltas en un [GnssState]. Una "época" se cierra con cada GGA.
 * GST/GSA suelen llegar después de la GGA del mismo segundo, por lo que la precisión
 * asociada puede corresponder a la época anterior (desfase ≤ 1 época, irrelevante en estático).
 * Clase pura (sin Android) para poder testearla en JVM.
 */
class NmeaAggregator(private val clock: () -> Long = System::currentTimeMillis) {
    var state = GnssState()
        private set

    /** @return la [Epoch] si la línea cerró una época con posición, si no null. */
    fun accept(line: String): Epoch? {
        when (val r = NmeaParser.parse(line)) {
            is NmeaParser.Result.Invalid -> {
                state = state.copy(badSentences = state.badSentences + 1)
                return null
            }
            is NmeaParser.Result.Ok -> {
                state = state.copy(sentences = state.sentences + 1)
                return apply(r.sentence)
            }
        }
    }

    private fun apply(s: NmeaParser.Sentence): Epoch? {
        when (s) {
            is NmeaParser.Gga -> {
                val now = clock()
                state = state.copy(
                    lat = s.lat, lon = s.lon,
                    altitudeMsl = s.altitudeMsl,
                    geoidSeparation = s.geoidSeparation,
                    solution = SolutionType.fromCode(s.quality),
                    satellites = s.satellites,
                    hdop = s.hdop,
                    diffAge = s.diffAge,
                    utc = s.utc,
                    lastEpochMillis = now,
                )
                if (s.lat == null || s.lon == null || s.quality == 0) return null
                return Epoch(
                    lat = s.lat, lon = s.lon,
                    altitudeMsl = s.altitudeMsl,
                    ellipsoidalHeight = state.ellipsoidalHeight,
                    solution = state.solution,
                    satellites = s.satellites,
                    hrms = state.hrms, vrms = state.vrms, pdop = state.pdop,
                    timeMillis = now,
                )
            }
            is NmeaParser.Gst -> state = state.copy(hrms = s.hrms ?: state.hrms, vrms = s.sigmaAlt ?: state.vrms)
            is NmeaParser.Gsa -> state = state.copy(
                pdop = s.pdop ?: state.pdop,
                vdop = s.vdop ?: state.vdop,
            )
            is NmeaParser.Rmc -> if (s.valid) state = state.copy(
                speedMs = s.speedMs ?: state.speedMs,
                courseDeg = s.courseDeg ?: state.courseDeg,
            )
            is NmeaParser.Vtg -> state = state.copy(
                speedMs = s.speedMs ?: state.speedMs,
                courseDeg = s.courseDeg ?: state.courseDeg,
            )
            is NmeaParser.Other -> parseProprietary(s)
        }
        return null
    }

    /**
     * Punto de extensión para tramas propietarias. El Emlid RS2 NO publica la batería por NMEA;
     * si otro equipo o un firmware futuro la envía (p. ej. "$PBAT,85*hh"), se interpreta aquí.
     */
    private fun parseProprietary(s: NmeaParser.Other) {
        if (s.type.contains("BAT")) {
            s.fields.firstNotNullOfOrNull { it.toDoubleOrNull() }?.let { pct ->
                if (pct in 0.0..100.0) state = state.copy(batteryPercent = pct.toInt())
            }
        }
    }

    fun setConnection(status: ConnectionStatus, label: String = state.connectionLabel, error: String? = null) {
        state = if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.CONNECTING) {
            GnssState(connection = status, connectionLabel = label, errorMessage = error)
        } else {
            state.copy(connection = status, connectionLabel = label, errorMessage = error)
        }
    }
}
