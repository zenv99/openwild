package org.openwild.app.core

/**
 * Colors for the dark navigation bar and the Explore grid.
 * Checked by ProductTest: the selected Explore item must not turn into a near-black pill,
 * and an Explore card with no photo yet must not be a black tile.
 */
object Appearance {
    const val LIME = 0xFFB7E36A.toInt()
    const val FOREST_DEEP = 0xFF07261C.toInt()
    const val LEAF = 0xFF2E8B57.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SAND = 0xFFF6F4EE.toInt()

    /** Page background in the dark theme (#0B1410). */
    const val DARK_PAGE = 0xFF0B1410.toInt()

    /** Navigation bar. Dark enough to sit on the page, light enough that a black icon would show. */
    const val DARK_BAR = 0xFF15201B.toInt()

    /** Selected nav pill. Bright, so the filled Explore compass cannot disappear into the bar. */
    const val DARK_INDICATOR = LIME
    const val DARK_SELECTED_ICON = FOREST_DEEP

    /** Label sits on the bar, not on the pill, so it stays light. */
    const val DARK_LABEL = 0xFFE8F2E4.toInt()
    const val DARK_UNSELECTED = 0xFF9BB5A4.toInt()

    /** Empty Explore photo card. Opaque green, not a black scrim. */
    const val DARK_EXPLORE_CARD = 0xFF306C4A.toInt()

    const val LIGHT_BAR = WHITE
    const val LIGHT_INDICATOR = LEAF
    const val LIGHT_SELECTED_ICON = WHITE
    const val LIGHT_LABEL = FOREST_DEEP
    const val LIGHT_UNSELECTED = 0xFF2C4638.toInt()
    const val LIGHT_EXPLORE_CARD = WHITE

    fun contrast(a: Int, b: Int): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
