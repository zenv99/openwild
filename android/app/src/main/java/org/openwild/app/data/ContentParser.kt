package org.openwild.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.openwild.app.core.SpeciesContent

/** Pack content JSON -> SpeciesContent. Unknown or malformed content becomes "unknown" (= treated as unsafe). */
object ContentParser {

    fun parse(json: String?): SpeciesContent? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val ie = o.optJSONObject("if_eaten")
            val surv = o.optJSONObject("survival")
            SpeciesContent(
                edibility = o.optString("edibility", "unknown"),
                toxicity = o.optString("toxicity", "unknown"),
                psychoactive = o.optBoolean("psychoactive", false),
                harms = ie?.optJSONObject("harms")?.let { h ->
                    SpeciesContent.Harms(
                        toxicParts = h.optJSONArray("toxic_parts").strings(),
                        cookingMakesSafe = if (h.has("cooking_makes_safe") && !h.isNull("cooking_makes_safe"))
                            h.optBoolean("cooking_makes_safe") else null,
                        symptoms = h.str("symptoms"),
                        onset = h.str("onset"),
                        severity = h.str("severity"),
                        whatToDo = h.str("what_to_do"),
                    )
                },
                benefits = ie?.optJSONObject("benefits")?.let { b ->
                    val uses = b.optJSONArray("traditional_uses")
                    SpeciesContent.Benefits(
                        nutrition = b.str("nutrition"),
                        edibleParts = b.optJSONArray("edible_parts").strings(),
                        preparationRequired = b.str("preparation_required"),
                        traditionalUses = (0 until (uses?.length() ?: 0)).mapNotNull { i ->
                            uses?.optJSONObject(i)?.let { u -> (u.str("text") ?: return@let null) to (u.str("source") ?: "") }
                        },
                    )
                },
                feeling = ie?.optJSONObject("feeling")?.let { f ->
                    SpeciesContent.Feeling(f.str("taste_texture"), f.str("effects"), f.str("risks"))
                },
                survivalPriority = surv?.optString("priority", "neutral") ?: "neutral",
                survivalUses = surv?.optJSONArray("uses").strings(),
                sources = o.optJSONArray("sources").strings(),
                expertReviewed = o.optJSONObject("review")?.optString("status") == "expert_reviewed",
                legalWarning = o.str("legal_warning"),
                level = o.str("level") ?: "species",
                groupTaxon = o.str("group_taxon"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
