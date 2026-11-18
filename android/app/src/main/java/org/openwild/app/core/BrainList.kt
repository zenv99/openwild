package org.openwild.app.core

/**
 * Which recognition models the brain list may show.
 * A model that cannot identify the pack on the phone is left out. Nothing is described as unavailable.
 */
object BrainList {
    const val STANDARD = "standard"
    const val BIOCLIP = "bioclip"

    fun offered(boostPublished: Boolean, boostOnPhone: Boolean, downloading: Boolean): List<String> {
        val ids = ArrayList<String>(2)
        ids += STANDARD
        if (boostPublished || boostOnPhone || downloading) ids += BIOCLIP
        return ids
    }
}
