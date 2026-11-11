package org.openwild.app.core

/**
 * Decides what a species page may show. Implements SAFETY.md — change only with expert review.
 * Pure Kotlin so the rules are unit-tested (SafetyPolicyTest).
 */
enum class Notice {
    /** Medium tier: eating information hidden. */
    NOT_CONFIDENT_EATING,
    /** Low tier / non-top candidate. */
    NOT_CONFIDENT_DO_NOT_EAT,
    /** Open-set: no match in the region pack. */
    NOT_IN_LIBRARY,
    /** No reviewed content: treat as unsafe. */
    NO_SAFETY_DATA,
    /** Fungus at high confidence but without an underside photo or with only one photo. */
    NEED_UNDERSIDE_PHOTO,
    /** Every fungus. */
    MUSHROOM_POOR_SURVIVAL_FOOD,
    /** Psychoactive species. */
    LEGAL_WARNING,
    /** Content not yet expert reviewed. */
    NOT_EXPERT_REVIEWED,
    /** Only a genus/family-level warning exists for this species. */
    GROUP_LEVEL_ONLY,
}

data class Visibility(
    val showHarms: Boolean,
    val showLookalikes: Boolean,
    val showBenefits: Boolean,
    val showFeeling: Boolean,
    val notices: List<Notice>,
)

object SafetyPolicy {

    const val DEADLY_CANDIDATE_MIN_PROB = 0.02
    const val DEADLY_CANDIDATE_TOP_K = 5

    /**
     * @param tier tier of THIS species (the top result's tier, or LOW for any other candidate).
     * @param isFungus kingdom == Fungi.
     * @param photoCount photos used for the identification.
     * @param hasUndersidePhoto user tagged a gills/pores photo.
     */
    fun visibility(
        tier: Tier,
        content: SpeciesContent?,
        isFungus: Boolean,
        photoCount: Int,
        hasUndersidePhoto: Boolean,
    ): Visibility {
        val notices = mutableListOf<Notice>()
        var eatingAllowed = tier == Tier.HIGH

        when (tier) {
            Tier.HIGH -> Unit
            Tier.MEDIUM -> notices += Notice.NOT_CONFIDENT_EATING
            Tier.LOW -> notices += Notice.NOT_CONFIDENT_DO_NOT_EAT
            Tier.UNKNOWN -> notices += Notice.NOT_IN_LIBRARY
        }
        if (isFungus && tier == Tier.HIGH && (photoCount < 2 || !hasUndersidePhoto)) {
            eatingAllowed = false
            notices += Notice.NEED_UNDERSIDE_PHOTO
        }
        if (content == null || !content.hasSafetyData) {
            eatingAllowed = false
            notices += Notice.NO_SAFETY_DATA
        }
        if (content != null && content.isGroupLevel) {
            // Group rules only ever warn; they never unlock eating information.
            eatingAllowed = false
            notices += Notice.GROUP_LEVEL_ONLY
        }
        if (content?.psychoactive == true) notices += Notice.LEGAL_WARNING
        if (isFungus) notices += Notice.MUSHROOM_POOR_SURVIVAL_FOOD
        if (content != null && content.hasSafetyData && !content.expertReviewed) notices += Notice.NOT_EXPERT_REVIEWED

        return Visibility(
            showHarms = tier != Tier.UNKNOWN,
            showLookalikes = tier != Tier.UNKNOWN,
            showBenefits = eatingAllowed,
            showFeeling = eatingAllowed,
            notices = notices,
        )
    }

    /** Species indices to name in the red "could be DEADLY" banner on the results screen. */
    fun deadlyCandidates(result: ConfidenceResult, isDeadly: (Int) -> Boolean): List<Int> =
        result.order.take(DEADLY_CANDIDATE_TOP_K)
            .filter { result.probs[it] >= DEADLY_CANDIDATE_MIN_PROB && isDeadly(it) }
}
