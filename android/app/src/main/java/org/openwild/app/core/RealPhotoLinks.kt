package org.openwild.app.core

import java.net.URLEncoder

/**
 * Browser searches for more photographs of a species. The app does not download them.
 * Open only after the person taps, and only when the phone is online.
 */
object RealPhotoLinks {
    fun inaturalist(scientificName: String) =
        "https://www.inaturalist.org/taxa/search?q=${enc(scientificName)}"

    fun gbif(scientificName: String) =
        "https://www.gbif.org/species/search?q=${enc(scientificName)}"

    fun commons(scientificName: String) =
        "https://commons.wikimedia.org/w/index.php?search=${enc(scientificName)}&title=Special:MediaSearch&type=image"

    private fun enc(name: String) = URLEncoder.encode(name.trim(), Charsets.UTF_8.name()).replace("+", "%20")
}
