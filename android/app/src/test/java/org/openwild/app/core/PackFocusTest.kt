package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackFocusTest {
    private fun pack(id: String, photos: Boolean, species: Int) = PackFocus.Candidate(id, photos, species)

    @Test
    fun photoRegionStaysInFrontWhenWorldIsActive() {
        val packs = listOf(
            pack("WORLD", photos = false, species = 5936),
            pack("ID", photos = true, species = 36621),
        )
        assertEquals("ID", PackFocus.choose("WORLD", packs))
        assertEquals("ID", PackFocus.choose("ID", packs))
    }

    @Test
    fun anExplicitPhotoRegionIsNotReplacedByALargerOne() {
        val packs = listOf(
            pack("ID", photos = true, species = 36621),
            pack("TH", photos = true, species = 8000),
            pack("WORLD", photos = false, species = 5936),
        )
        assertEquals("TH", PackFocus.choose("TH", packs))
    }

    @Test
    fun worldStaysOnScreenWhenItAlreadyHasPhotos() {
        val packs = listOf(
            pack("WORLD", photos = true, species = 5936),
            pack("ID", photos = true, species = 36621),
        )
        assertEquals("WORLD", PackFocus.choose("WORLD", packs))
    }

    @Test
    fun namesOnlyRegionRemainsWhenItIsTheOnlyOne() {
        val world = listOf(pack("WORLD", photos = false, species = 5936))
        assertEquals("WORLD", PackFocus.choose("WORLD", world))
        assertEquals("WORLD", PackFocus.choose(null, world))
    }

    @Test
    fun noPacks() {
        assertNull(PackFocus.choose("ID", emptyList()))
    }

    @Test
    fun severalPhotoRegionsPreferTheLargerGuideWhenTheActiveOneHasNoPictures() {
        val packs = listOf(
            pack("WORLD", photos = false, species = 5936),
            pack("TH", photos = true, species = 8000),
            pack("ID", photos = true, species = 36621),
        )
        assertEquals("ID", PackFocus.choose("WORLD", packs))
    }
}
