package org.openwild.app.core

import kotlin.math.ln

/** Kingdom filter chosen on the capture screen. */
enum class KingdomFilter { ANY, PLANTS, FUNGI }

/** Species-level data the identifier needs from a pack (no Android types). */
class SpeciesTable(
    val ids: LongArray,              // pack species.id
    val scientificNames: Array<String>,
    val commonNames: Array<String?>,
    val isFungus: BooleanArray,
    val obsCounts: IntArray,
    /** 12 x speciesCount month frequency 0..255 (months[m][i]). */
    val months: Array<IntArray>,
) {
    val size: Int get() = ids.size
}

/** Glue: fingerprints -> similarities -> prior -> calibrated result. */
class Identifier(
    private val index: PrototypeIndex,
    private val species: SpeciesTable,
    private val params: ConfidenceParams,
) {
    fun identify(embeddings: List<FloatArray>, filter: KingdomFilter, month: Int /* 1..12 */): ConfidenceResult {
        val allowed = when (filter) {
            KingdomFilter.ANY -> null
            KingdomFilter.PLANTS -> BooleanArray(species.size) { !species.isFungus[it] }
            KingdomFilter.FUNGI -> BooleanArray(species.size) { species.isFungus[it] }
        }
        val sim = embeddings.map { index.similarity(it, params.textProtoPenalty, allowed) }.toTypedArray()
        val prior = Confidence.prior(species.obsCounts, species.months[(month - 1).coerceIn(0, 11)])
        val logPrior = DoubleArray(prior.size) { ln(prior[it]) }
        return Confidence.score(sim, logPrior, params)
    }
}
