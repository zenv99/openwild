package org.openwild.app.core

/** Species safety content (docs/CONTENT_SCHEMA.md). Parsed from pack JSON by data/ContentParser.kt. */
data class SpeciesContent(
    val edibility: String = "unknown",
    val toxicity: String = "unknown",
    val psychoactive: Boolean = false,
    val harms: Harms? = null,
    val benefits: Benefits? = null,
    val feeling: Feeling? = null,
    val survivalPriority: String = "neutral",
    val survivalUses: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val expertReviewed: Boolean = false,
    val legalWarning: String? = null,
) {
    val isDeadly: Boolean get() = toxicity == "deadly" || edibility == "deadly"
    val hasSafetyData: Boolean get() = edibility != "unknown" || toxicity != "unknown"

    data class Harms(
        val toxicParts: List<String> = emptyList(),
        val cookingMakesSafe: Boolean? = null,
        val symptoms: String? = null,
        val onset: String? = null,
        val severity: String? = null,
        val whatToDo: String? = null,
    )

    data class Benefits(
        val nutrition: String? = null,
        val edibleParts: List<String> = emptyList(),
        val preparationRequired: String? = null,
        val traditionalUses: List<Pair<String, String>> = emptyList(), // (text, source)
    )

    data class Feeling(val tasteTexture: String? = null, val effects: String? = null, val risks: String? = null)
}

data class Lookalike(val name: String, val danger: String, val speciesId: Long?)
