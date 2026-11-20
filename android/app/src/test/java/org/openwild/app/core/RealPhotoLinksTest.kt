package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RealPhotoLinksTest {
    @Test
    fun searchesUseTheScientificName() {
        val name = "Amanita subpallidorosea"
        assertEquals(
            "https://www.inaturalist.org/taxa/search?q=Amanita%20subpallidorosea",
            RealPhotoLinks.inaturalist(name),
        )
        assertEquals(
            "https://www.gbif.org/species/search?q=Amanita%20subpallidorosea",
            RealPhotoLinks.gbif(name),
        )
        assertEquals(
            "https://commons.wikimedia.org/w/index.php?search=Amanita%20subpallidorosea&title=Special:MediaSearch&type=image",
            RealPhotoLinks.commons(name),
        )
    }
}
