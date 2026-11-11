package org.openwild.app.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Confidence scoring — a direct port of pipeline/openwild_pipeline/confidence.py (docs/CONFIDENCE.md).
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 */
enum class Tier { HIGH, MEDIUM, LOW, UNKNOWN }

data class ConfidenceParams(
    val temperature: Double = 0.03,
    val photoAlpha: Double = 0.6,
    val priorBeta: Double = 0.5,
    val openSetThreshold: Double = 0.30,
    val highThreshold: Double = 0.85,
    val mediumThreshold: Double = 0.50,
    val textProtoPenalty: Double = 0.05,
    val margin: Double = 0.30,
) {
    companion object {
        /** Build from a pack's meta table; missing keys keep their defaults. */
        fun fromMeta(meta: Map<String, String>): ConfidenceParams {
            val d = ConfidenceParams()
            fun g(k: String, def: Double) = meta[k]?.toDoubleOrNull() ?: def
            return ConfidenceParams(
                temperature = g("temperature", d.temperature),
                photoAlpha = g("photo_alpha", d.photoAlpha),
                priorBeta = g("prior_beta", d.priorBeta),
                openSetThreshold = g("open_set_threshold", d.openSetThreshold),
                highThreshold = g("high_threshold", d.highThreshold),
                mediumThreshold = g("medium_threshold", d.mediumThreshold),
                textProtoPenalty = g("text_proto_penalty", d.textProtoPenalty),
            )
        }
    }
}

data class ConfidenceResult(
    /** Species indices, best first (only species that were eligible). */
    val order: IntArray,
    /** Fused probability per species index. */
    val probs: DoubleArray,
    val tier: Tier,
    /** 0..100 for the top species. */
    val score: Int,
    val agreement: Double,
    val meanTopSim: Double,
) {
    val top: Int get() = order[0]
}

object Confidence {

    /** Prior over species from regional observation counts and the current month's relative frequency (0..255). */
    fun prior(obsCounts: IntArray, monthFreq: IntArray?): DoubleArray {
        val p = DoubleArray(obsCounts.size) { i ->
            val base = sqrt(obsCounts[i].toDouble() + 1.0)
            if (monthFreq != null) base * (monthFreq[i] / 255.0 + 0.05) else base
        }
        val sum = p.sum()
        for (i in p.indices) p[i] /= sum
        return p
    }

    private fun logSoftmaxInPlace(z: DoubleArray) {
        var m = Double.NEGATIVE_INFINITY
        for (v in z) if (v > m) m = v
        var s = 0.0
        for (v in z) s += exp(v - m)
        val lse = m + ln(s)
        for (i in z.indices) z[i] -= lse
    }

    /**
     * @param sim [photo][species] best-prototype similarity; Double.NEGATIVE_INFINITY = species not eligible.
     * @param logPrior log prior per species.
     */
    fun score(sim: Array<DoubleArray>, logPrior: DoubleArray, prm: ConfidenceParams): ConfidenceResult {
        val n = sim.size
        val s = logPrior.size
        require(n > 0) { "need at least one photo" }
        val has = BooleanArray(s) { sim[0][it].isFinite() }
        require(has.any()) { "no eligible species" }

        val fused = DoubleArray(s)
        val perPhotoTop = IntArray(n)
        for (p in 0 until n) {
            val z = DoubleArray(s) { if (has[it]) sim[p][it] / prm.temperature else -1e9 }
            var best = 0
            for (i in 1 until s) if (z[i] > z[best]) best = i
            perPhotoTop[p] = best
            logSoftmaxInPlace(z)
            for (i in 0 until s) fused[i] += z[i]
        }
        for (i in 0 until s) {
            fused[i] = if (has[i]) prm.photoAlpha * fused[i] + prm.priorBeta * logPrior[i] else -1e9
        }
        logSoftmaxInPlace(fused)
        val probs = DoubleArray(s) { exp(fused[it]) }

        val order = (0 until s).filter { has[it] }.sortedByDescending { probs[it] }.toIntArray()
        val top = order[0]
        val p1 = probs[top]
        val p2 = if (order.size > 1) probs[order[1]] else 0.0
        val agreement = perPhotoTop.count { it == top }.toDouble() / n
        val meanTopSim = (0 until n).sumOf { sim[it][top] } / n

        val tier = when {
            meanTopSim < prm.openSetThreshold -> Tier.UNKNOWN
            p1 >= prm.highThreshold && (p1 - p2) >= prm.margin && (n == 1 || agreement >= 0.5) -> Tier.HIGH
            p1 >= prm.mediumThreshold -> Tier.MEDIUM
            else -> Tier.LOW
        }
        return ConfidenceResult(order, probs, tier, (100 * p1).roundToInt(), agreement, meanTopSim)
    }
}
