package com.topognss.app.cogo

import kotlin.math.abs

/**
 * Posición de un punto respecto de un eje/alineación (polilínea abierta): [station] = distancia
 * acumulada desde el inicio del eje hasta el pie de la perpendicular, [offset] = desvío lateral
 * (+ derecha, − izquierda mirando en el sentido de avance del tramo), [point] = el pie de la
 * perpendicular sobre el eje, [segmentIndex] = tramo del eje donde cae.
 */
data class AlignmentFix(val station: Double, val offset: Double, val point: P3, val segmentIndex: Int)

/**
 * Punto más cercano de [p] sobre el eje [path] (polilínea de 2+ puntos), para el replanteo
 * continuo de ejes/alineaciones (caminar sobre una polilínea, p. ej. un eje de cañería o un
 * cordón). Devuelve la estación (acumulada desde el inicio del eje) y el desvío lateral del
 * tramo más cercano cuyo pie de perpendicular cae efectivamente dentro de ese tramo; si [p] queda
 * más allá de ambos extremos del eje, usa el extremo más cercano (estación 0 o longitud total),
 * con el desvío (lateral + lo que sobra en el eje) como distancia directa al extremo.
 * Devuelve null si el eje tiene menos de 2 puntos.
 */
fun nearestOnPolyline(path: List<P3>, p: P3): AlignmentFix? {
    if (path.size < 2) return null

    var cumulative = 0.0
    var best: AlignmentFix? = null
    var bestDist = Double.MAX_VALUE

    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]
        val segLen = Cogo.horizontalDistance(a, b)
        if (segLen > 1e-9) {
            val (sRaw, offset) = Cogo.inverseStationOffset(a, b, p)
            if (sRaw in 0.0..segLen) {
                val dist = abs(offset)
                if (dist < bestDist) {
                    bestDist = dist
                    val point = Cogo.stationOffset(a, b, sRaw, 0.0)
                    best = AlignmentFix(cumulative + sRaw, offset, point, i)
                }
            }
        }
        cumulative += segLen
    }

    if (best != null) return best

    // p queda más allá de ambos extremos del eje: usar el extremo más cercano.
    val distToStart = Cogo.horizontalDistance(path.first(), p)
    val distToEnd = Cogo.horizontalDistance(path.last(), p)
    val totalLength = (0 until path.size - 1).sumOf { Cogo.horizontalDistance(path[it], path[it + 1]) }
    return if (distToStart <= distToEnd) {
        AlignmentFix(0.0, distToStart, path.first(), 0)
    } else {
        AlignmentFix(totalLength, distToEnd, path.last(), path.size - 2)
    }
}

/** Longitud total de un eje/polilínea. */
fun polylineLength(path: List<P3>): Double = (0 until path.size - 1).sumOf { Cogo.horizontalDistance(path[it], path[it + 1]) }

/** Punto del eje a la estación [station] (distancia acumulada desde el inicio); null si está fuera de rango. */
fun pointAtStation(path: List<P3>, station: Double): P3? {
    if (path.size < 2 || station < -1e-6) return null
    var cumulative = 0.0
    for (i in 0 until path.size - 1) {
        val a = path[i]; val b = path[i + 1]
        val segLen = Cogo.horizontalDistance(a, b)
        if (station <= cumulative + segLen + 1e-9) {
            return Cogo.stationOffset(a, b, (station - cumulative).coerceIn(0.0, segLen), 0.0)
        }
        cumulative += segLen
    }
    return if (abs(station - cumulative) < 1e-6) path.last() else null
}
