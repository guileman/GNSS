package com.topognss.app.ar

import com.topognss.app.geo.normalize360
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Alineación entre el sistema local de ARCore y el plano local ENU del receptor RTK.
 *
 * ARCore rastrea el teléfono en un marco propio: Y vertical (gravedad), X/Z horizontales con
 * orientación arbitraria. Como Y ya es vertical, sólo hace falta una transformación RÍGIDA 2D
 * (rotación alrededor de la vertical + traslación, sin escala: ARCore trabaja en metros) entre
 *
 *     local = (x, −z)   →   mundo = (Este, Norte)
 *
 * (x, −z) es un sistema dextrógiro igual que (E, N), así que la transformación es una rotación
 * propia, sin reflexión. Se estima por mínimos cuadrados ponderados (Umeyama/Kabsch 2D) con pares
 * (posición AR del teléfono, posición RTK) acumulados mientras el operador camina.
 *
 * Archivo puro (sin Android) para poder testearlo en JVM.
 */

/** mundo = R(θ)·local + t, con θ antihorario (convención matemática en el plano E-N). */
data class Rigid2D(val thetaRad: Double, val tx: Double, val ty: Double) {
    private val c = cos(thetaRad)
    private val s = sin(thetaRad)

    fun toWorld(x: Double, y: Double): Pair<Double, Double> = Pair(c * x - s * y + tx, s * x + c * y + ty)

    fun toLocal(e: Double, n: Double): Pair<Double, Double> {
        val dx = e - tx
        val dy = n - ty
        return Pair(c * dx + s * dy, -s * dx + c * dy)
    }

    /** Rota un vector (sin trasladar) del marco local al mundo. */
    fun rotateToWorld(x: Double, y: Double): Pair<Double, Double> = Pair(c * x - s * y, s * x + c * y)
}

/** Un par de calibración: posición horizontal AR (x, −z) y posición RTK en el ENU local. */
data class AlignSample(
    val lx: Double,
    val ly: Double,
    val east: Double,
    val north: Double,
    /** Desvío 1σ horizontal de la posición RTK (m). */
    val sigma: Double,
    val timeMs: Long,
)

data class AlignmentStatus(
    val transform: Rigid2D? = null,
    /** true cuando la calibración es suficiente para dibujar el mojón. */
    val ready: Boolean = false,
    val samples: Int = 0,
    /** Extensión del recorrido usado (m): sin recorrido no hay rumbo. */
    val baseline: Double = 0.0,
    /** Residuo RMS del ajuste (m). */
    val rms: Double? = null,
    /** Incertidumbre estimada del rumbo (°). */
    val headingSigmaDeg: Double? = null,
    /** Cantidad de recalibraciones automáticas por saltos del tracking. */
    val autoResets: Int = 0,
    val message: String = "Caminá unos metros en línea recta con solución FIX para calibrar",
)

class ArAligner(
    val minBaseline: Double = 6.0,
    val minSamples: Int = 10,
    val maxSamples: Int = 400,
    /** Las muestras viejas pierden peso (deriva del tracking visual ~1 % de lo recorrido). */
    val decayMs: Double = 120_000.0,
    val maxAgeMs: Long = 300_000,
    val maxRms: Double = 0.35,
    val maxHeadingSigmaDeg: Double = 1.5,
    /** Separación mínima entre muestras guardadas: parado no se acumulan duplicados. */
    val minStep: Double = 0.15,
) {
    private val samples = ArrayList<AlignSample>()
    private var badStreak = 0
    private var autoResets = 0

    @Volatile
    var status = AlignmentStatus()
        private set

    @Synchronized
    fun reset(message: String = AlignmentStatus().message) {
        samples.clear()
        badStreak = 0
        status = AlignmentStatus(autoResets = autoResets, message = message)
    }

    @Synchronized
    fun add(s: AlignSample): AlignmentStatus {
        // 1) Detección de saltos: si con la calibración vigente varias muestras seguidas caen lejos,
        //    ARCore relocalizó o derivó mucho → se recalibra desde cero.
        val tr = status.transform
        if (status.ready && tr != null) {
            val (e, n) = tr.toWorld(s.lx, s.ly)
            val r = hypot(e - s.east, n - s.north)
            val limit = max(0.8, 5 * (status.rms ?: 0.1) + 3 * s.sigma)
            if (r > limit) {
                badStreak++
                if (badStreak < 5) return status // se descarta como atípica
                autoResets++
                samples.clear()
                badStreak = 0
                status = AlignmentStatus(
                    autoResets = autoResets,
                    message = "El tracking AR saltó: recalibrando, caminá unos metros",
                )
            } else {
                badStreak = 0
            }
        }

        // 2) Decimación espacial (o una cada 3 s si está quieto).
        val last = samples.lastOrNull()
        if (last != null && hypot(s.east - last.east, s.north - last.north) < minStep && s.timeMs - last.timeMs < 3000) {
            return status
        }
        samples.add(s)
        prune(s.timeMs)
        recompute(s.timeMs)
        return status
    }

    private fun prune(now: Long) {
        while (samples.size > maxSamples) samples.removeAt(0)
        while (samples.size > minSamples && now - samples[0].timeMs > maxAgeMs &&
            diameter(samples.subList(1, samples.size)) >= minBaseline
        ) samples.removeAt(0)
    }

    private fun recompute(now: Long) {
        val n = samples.size
        if (n < 3) {
            status = status.copy(transform = null, ready = false, samples = n, baseline = diameter(samples),
                message = AlignmentStatus().message)
            return
        }
        var fit = fit(now) ?: return
        // Rechazo de atípicos (p. ej. época con FIX falso o desfasada): una pasada.
        if (n >= 8) {
            val limit = max(3 * fit.rms, 0.30)
            val removed = samples.removeAll { residual(fit.tr, it) > limit }
            if (removed) fit = fit(now) ?: return
        }
        val baseline = diameter(samples)
        // σθ ≈ σ / sqrt(Σ d²), corrigiendo por correlación temporal (≈ 1 muestra independiente por metro).
        var sumD2 = 0.0
        var cx = 0.0
        var cy = 0.0
        samples.forEach { cx += it.lx; cy += it.ly }
        cx /= samples.size; cy /= samples.size
        samples.forEach { sumD2 += (it.lx - cx) * (it.lx - cx) + (it.ly - cy) * (it.ly - cy) }
        val sigmaPos = max(fit.rms, samples.map { it.sigma }.sorted()[samples.size / 2])
        val independent = max(1.0, baseline)
        val headingSigma = if (sumD2 > 1e-6) {
            Math.toDegrees(sigmaPos / sqrt(sumD2) * sqrt(samples.size / independent))
        } else null

        val ready = samples.size >= minSamples && baseline >= minBaseline && fit.rms <= maxRms &&
            headingSigma != null && headingSigma <= maxHeadingSigmaDeg
        val message = when {
            ready -> "AR calibrada"
            baseline < minBaseline -> "Calibrando: caminá %.1f m más en línea recta".format(minBaseline - baseline)
            fit.rms > maxRms -> "Calibración inconsistente (residuo %.2f m): seguí caminando o recalibrá".format(fit.rms)
            else -> "Calibrando: seguí caminando para afinar el rumbo"
        }
        status = AlignmentStatus(
            transform = fit.tr, ready = ready, samples = samples.size, baseline = baseline, rms = fit.rms,
            headingSigmaDeg = headingSigma, autoResets = autoResets, message = message,
        )
    }

    private class Fit(val tr: Rigid2D, val rms: Double)

    /** Ajuste rígido 2D ponderado (solución cerrada de Kabsch/Umeyama sin escala). */
    private fun fit(now: Long): Fit? {
        val w = DoubleArray(samples.size) { i ->
            val s = samples[i]
            val sig = max(s.sigma, 0.01)
            exp(-(now - s.timeMs) / decayMs) / (sig * sig)
        }
        val wSum = w.sum()
        if (wSum <= 0) return null
        var clx = 0.0; var cly = 0.0; var cwx = 0.0; var cwy = 0.0
        samples.forEachIndexed { i, s ->
            clx += w[i] * s.lx; cly += w[i] * s.ly
            cwx += w[i] * s.east; cwy += w[i] * s.north
        }
        clx /= wSum; cly /= wSum; cwx /= wSum; cwy /= wSum
        var sxx = 0.0
        var sxy = 0.0
        samples.forEachIndexed { i, s ->
            val ax = s.lx - clx; val ay = s.ly - cly
            val bx = s.east - cwx; val by = s.north - cwy
            sxx += w[i] * (ax * bx + ay * by)
            sxy += w[i] * (ax * by - ay * bx)
        }
        if (sxx == 0.0 && sxy == 0.0) return null
        val theta = atan2(sxy, sxx)
        val c = cos(theta); val sn = sin(theta)
        val tr = Rigid2D(theta, cwx - (c * clx - sn * cly), cwy - (sn * clx + c * cly))
        var sq = 0.0
        samples.forEach { val r = residual(tr, it); sq += r * r }
        return Fit(tr, sqrt(sq / samples.size))
    }

    private fun residual(tr: Rigid2D, s: AlignSample): Double {
        val (e, n) = tr.toWorld(s.lx, s.ly)
        return hypot(e - s.east, n - s.north)
    }

    companion object {
        /** Diámetro aproximado del recorrido (dos pasadas del punto más lejano), en el marco RTK. */
        fun diameter(list: List<AlignSample>): Double {
            if (list.size < 2) return 0.0
            val a = list.maxByOrNull { hypot(it.east - list[0].east, it.north - list[0].north) }!!
            return list.maxOf { hypot(it.east - a.east, it.north - a.north) }
        }
    }
}

/** Pose del teléfono en el marco AR con su hora (reloj de pared, para cruzar con las épocas GNSS). */
data class ArPose(val timeMs: Long, val x: Double, val y: Double, val z: Double)

/** Historial corto de poses AR para interpolar la posición del teléfono en el instante de cada época. */
class PoseBuffer(private val capacity: Int = 400) {
    private val buf = ArrayDeque<ArPose>()

    @Synchronized
    fun add(p: ArPose) {
        if (buf.isNotEmpty() && p.timeMs <= buf.last().timeMs) return
        buf.addLast(p)
        while (buf.size > capacity) buf.removeFirst()
    }

    @Synchronized
    fun clear() = buf.clear()

    /** Pose interpolada linealmente; null si no hay poses alrededor de [timeMs] (tracking perdido). */
    @Synchronized
    fun at(timeMs: Long, maxGapMs: Long = 300): ArPose? {
        if (buf.isEmpty()) return null
        val first = buf.first()
        val last = buf.last()
        if (timeMs <= first.timeMs) return first.takeIf { first.timeMs - timeMs <= maxGapMs / 2 }
        if (timeMs >= last.timeMs) return last.takeIf { timeMs - last.timeMs <= maxGapMs / 2 }
        var i = buf.size - 1
        while (i > 0 && buf[i - 1].timeMs > timeMs) i--
        val a = buf[i - 1]
        val b = buf[i]
        if (b.timeMs - a.timeMs > maxGapMs) return null
        val f = (timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
        return ArPose(timeMs, a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.z + f * (b.z - a.z))
    }
}

/**
 * Rumbo verdadero (°) de un vector horizontal del marco AR (componentes x, z de ARCore).
 * Null si el vector es casi vertical.
 */
fun arHeadingDeg(tr: Rigid2D, ax: Double, az: Double): Double? {
    if (hypot(ax, az) < 1e-3) return null
    val (de, dn) = tr.rotateToWorld(ax, -az)
    return normalize360(Math.toDegrees(atan2(de, dn)))
}
