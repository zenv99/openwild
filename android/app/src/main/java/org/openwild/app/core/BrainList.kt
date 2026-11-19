package org.openwild.app.core

/**
 * Which recognition models the brain list may show.
 * A model that cannot identify the pack on the phone is left out. Nothing is described as unavailable.
 *
 * [RECOMMENDED] is the only place the Recommended badge is chosen. Change that one constant
 * to move the badge. A phone that has not chosen a model still runs Standard until a download
 * is on the phone, so photo identification keeps working with no signal.
 */
object BrainList {
    const val STANDARD = "standard"
    const val BIOCLIP = "bioclip"

    const val RECOMMENDED = BIOCLIP

    const val SCAN_SEVERAL = "several_crops"
    const val SCAN_ONE = "one_crop"

    fun offered(boostPublished: Boolean, boostOnPhone: Boolean, downloading: Boolean): List<String> {
        val ids = ArrayList<String>(2)
        ids += STANDARD
        if (boostPublished || boostOnPhone || downloading) ids += BIOCLIP
        return ids
    }

    fun isRecommended(id: String): Boolean = id == RECOMMENDED

    /**
     * The model that actually produced a result. A BioCLIP request with no boost on the phone
     * falls back to Standard, and the report says so.
     */
    fun ran(requested: String, bioclipActive: Boolean): String =
        if (requested == BIOCLIP && bioclipActive) BIOCLIP else STANDARD

    fun scanDetail(careful: Boolean): String = if (careful) SCAN_SEVERAL else SCAN_ONE
}
