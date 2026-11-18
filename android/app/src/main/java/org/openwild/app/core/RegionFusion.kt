package org.openwild.app.core

import kotlin.math.ln

/**
 * One region pack reduced to the pieces identification needs. Pure Kotlin so it can be tested
 * without Android. A phone may have a country pack and a finer pack (Indonesia and Bali) enabled
 * together. The same scientific name is scored once: the better fingerprint wins, and the
 * probability is not split across two copies of the species.
 */
class RegionLibrary(
    val regionId: String,
    val species: SpeciesTable,
    val index: PrototypeIndex,
    val params: ConfidenceParams,
    val projection: Projection? = null,
)

class FusedIdentification(
    /** Pack whose species indexes [result] uses, so the species page can open the right record. */
    val displayRegionId: String,
    val result: ConfidenceResult,
)

object RegionFusion {

    /**
     * Identify against every enabled pack.
     * [preferRegionId] is the region Explore is showing. It keeps the result when its fingerprint
     * for the winning species is within [PREFER_SLACK] of the best, so a slightly weaker copy of
     * the same plant does not flip the field guide to another pack.
     * Returns null when no pack has a usable fingerprint for the filter.
     */
    fun identify(
        libraries: List<RegionLibrary>,
        embeddings: List<FloatArray>,
        filter: KingdomFilter,
        month: Int,
        preferRegionId: String? = null,
    ): FusedIdentification? {
        val ready = libraries.mapNotNull { lib -> prepared(lib, embeddings)?.let { lib to it } }
        if (ready.isEmpty()) return null
        if (ready.size == 1) {
            val (lib, inputs) = ready[0]
            val result = scoreOne(lib, inputs, filter, month) ?: return null
            return FusedIdentification(lib.regionId, result)
        }
        return fuse(ready, filter, month, preferRegionId)
    }

    private fun prepared(lib: RegionLibrary, embeddings: List<FloatArray>): List<FloatArray>? {
        if (lib.index.size == 0 || lib.index.dim == 0) return null
        val inputs = if (lib.projection != null) embeddings.map { lib.projection.apply(it) } else embeddings
        if (inputs.any { it.size != lib.index.dim }) return null
        return inputs
    }

    private fun allowed(species: SpeciesTable, filter: KingdomFilter): BooleanArray? = when (filter) {
        KingdomFilter.ANY -> null
        KingdomFilter.PLANTS -> BooleanArray(species.size) { !species.isFungus[it] }
        KingdomFilter.FUNGI -> BooleanArray(species.size) { species.isFungus[it] }
    }

    private fun scoreOne(
        lib: RegionLibrary,
        inputs: List<FloatArray>,
        filter: KingdomFilter,
        month: Int,
    ): ConfidenceResult? {
        val allow = allowed(lib.species, filter)
        if (allow != null && !allow.any { it }) return null
        val sim = lib.index.similarityBatch(inputs, lib.params.textProtoPenalty, allow)
        if (!sim[0].any { it.isFinite() }) return null
        val prior = Confidence.prior(lib.species.obsCounts, lib.species.months[(month - 1).coerceIn(0, 11)])
        val logPrior = DoubleArray(prior.size) { ln(prior[it]) }
        return Confidence.score(sim, logPrior, lib.params)
    }

    private class Slot(
        val sims: DoubleArray,
        var obs: Int,
        var monthFreq: Int,
        var bestRegion: String,
        var bestLocal: Int,
        var bestPeak: Double,
    )

    private fun fuse(
        ready: List<Pair<RegionLibrary, List<FloatArray>>>,
        filter: KingdomFilter,
        month: Int,
        preferRegionId: String?,
    ): FusedIdentification? {
        val params = ready.firstOrNull { it.first.regionId == preferRegionId }?.first?.params ?: ready.first().first.params
        val nPhotos = ready.first().second.size
        val slots = LinkedHashMap<String, Slot>()
        val simByRegion = HashMap<String, Array<DoubleArray>>()
        for ((lib, inputs) in ready) {
            val allow = allowed(lib.species, filter) ?: BooleanArray(lib.species.size) { true }
            val sim = lib.index.similarityBatch(inputs, params.textProtoPenalty, allow)
            simByRegion[lib.regionId] = sim
            val monthRow = lib.species.months[(month - 1).coerceIn(0, 11)]
            for (s in 0 until lib.species.size) {
                if (!allow[s]) continue
                var peak = Double.NEGATIVE_INFINITY
                for (p in 0 until nPhotos) if (sim[p][s] > peak) peak = sim[p][s]
                if (!peak.isFinite()) continue
                val name = lib.species.scientificNames[s].trim().lowercase()
                val incoming = DoubleArray(nPhotos) { sim[it][s] }
                val cur = slots[name]
                if (cur == null) {
                    slots[name] = Slot(incoming, lib.species.obsCounts[s], monthRow[s], lib.regionId, s, peak)
                } else {
                    for (p in 0 until nPhotos) if (incoming[p] > cur.sims[p]) cur.sims[p] = incoming[p]
                    if (peak > cur.bestPeak) {
                        cur.bestPeak = peak
                        cur.obs = lib.species.obsCounts[s]
                        cur.monthFreq = monthRow[s]
                        cur.bestRegion = lib.regionId
                        cur.bestLocal = s
                    }
                }
            }
        }
        if (slots.isEmpty()) return null
        val names = slots.keys.toList()
        val simMatrix = Array(nPhotos) { p -> DoubleArray(names.size) { u -> slots[names[u]]!!.sims[p] } }
        val obs = IntArray(names.size) { slots[names[it]]!!.obs }
        val monthFreq = IntArray(names.size) { slots[names[it]]!!.monthFreq }
        val prior = Confidence.prior(obs, monthFreq)
        val logPrior = DoubleArray(prior.size) { ln(prior[it]) }
        val fused = Confidence.score(simMatrix, logPrior, params)
        val topName = names[fused.top]
        val top = slots[topName]!!
        var displayId = top.bestRegion
        val prefer = ready.firstOrNull { it.first.regionId == preferRegionId }?.first
        if (prefer != null && prefer.regionId != displayId) {
            val local = indexOf(prefer, topName)
            val sim = simByRegion[prefer.regionId]
            if (local >= 0 && sim != null) {
                var preferPeak = Double.NEGATIVE_INFINITY
                for (p in 0 until nPhotos) if (sim[p][local] > preferPeak) preferPeak = sim[p][local]
                if (preferPeak.isFinite() && preferPeak + PREFER_SLACK >= top.bestPeak) displayId = prefer.regionId
            }
        }
        val display = ready.first { it.first.regionId == displayId }.first
        val nameToFused = names.withIndex().associate { it.value to it.index }
        val probs = DoubleArray(display.species.size)
        val eligible = ArrayList<Int>()
        for (i in display.species.scientificNames.indices) {
            val u = nameToFused[display.species.scientificNames[i].trim().lowercase()] ?: continue
            probs[i] = fused.probs[u]
            eligible += i
        }
        if (eligible.isEmpty()) return null
        val order = eligible.sortedByDescending { probs[it] }.toIntArray()
        return FusedIdentification(
            displayId,
            ConfidenceResult(order, probs, fused.tier, fused.score, fused.agreement, fused.meanTopSim),
        )
    }

    private fun indexOf(lib: RegionLibrary, name: String): Int {
        val want = name.trim().lowercase()
        for (i in lib.species.scientificNames.indices) {
            if (lib.species.scientificNames[i].trim().lowercase() == want) return i
        }
        return -1
    }

    private const val PREFER_SLACK = 0.02
}
