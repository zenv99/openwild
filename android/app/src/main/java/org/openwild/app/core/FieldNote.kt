package org.openwild.app.core

/**
 * A review the user wrote on this phone. It is not sent anywhere unless they tap submit,
 * and the share text always carries the "do not eat" line from SAFETY.md.
 */
data class FieldNote(
    val scientificName: String,
    val rating: Int = 0,
    val text: String = "",
    val photos: List<String> = emptyList(),
) {
    fun hasContent(): Boolean = rating in 1..5 || text.isNotBlank() || photos.isNotEmpty()

    fun shareText(regionName: String?): String = buildString {
        appendLine("Species: $scientificName")
        if (rating in 1..5) appendLine("Rating: $rating/5")
        if (!regionName.isNullOrBlank()) appendLine("Region: $regionName")
        if (text.isNotBlank()) {
            appendLine()
            appendLine(text.trim())
        }
        appendLine()
        append(SAFETY_LINE)
    }

    companion object {
        const val MAX_PHOTOS = 6
        const val SAFETY_LINE =
            "Not medical advice. Never eat a plant you have not identified for yourself. Notes stay on the phone unless the sender chose to share them."

        fun clampRating(rating: Int): Int = rating.coerceIn(0, 5)
    }
}
