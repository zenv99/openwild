package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionChoiceTest {
    @Test
    fun newInstallDoesNotActivateTheFirstPack() {
        assertNull(RegionChoice.activeId(null, listOf("ID-BA", "PH")))
    }

    @Test
    fun keepsARegionTheUserAlreadyChose() {
        assertEquals("PH", RegionChoice.activeId("PH", listOf("ID-BA", "PH")))
    }

    @Test
    fun dropsAChoiceWhosePackIsGone() {
        assertNull(RegionChoice.activeId("PH", listOf("ID-BA")))
    }
}

class PackUpdatesTest {
    private val installed = mapOf("ID-BA" to 2, "PH" to 5)

    @Test
    fun onlyNewerInstalledRegions() {
        val remote = listOf(
            PackVersion("ID-BA", 3, 77_000_000),
            PackVersion("PH", 5, 10_000_000),
            PackVersion("AU-NSW", 1, 12_000_000),
        )
        assertEquals(listOf("ID-BA"), PackUpdates.pending(installed, remote).map { it.regionId })
    }

    @Test
    fun largeMeteredDownloadMustBeNamedAsMobileData() {
        assertTrue(PackUpdates.confirmMobileData(77_000_000, metered = true))
        assertTrue(PackUpdates.confirmMobileData(0, metered = true))
        assertFalse(PackUpdates.confirmMobileData(77_000_000, metered = false))
        assertFalse(PackUpdates.confirmMobileData(2_000_000, metered = true))
    }
}

class AppearanceTest {
    @Test
    fun darkExploreNavAndCardsStayReadable() {
        // The old default pill (#4A4458 on #15201B) was about 1.8:1, which read as a black Explore button.
        assertTrue(Appearance.contrast(0x4A4458, Appearance.DARK_BAR) < 3.0)
        assertTrue(Appearance.contrast(Appearance.DARK_INDICATOR, Appearance.DARK_BAR) >= 3.0)
        assertTrue(Appearance.contrast(Appearance.DARK_SELECTED_ICON, Appearance.DARK_INDICATOR) >= 4.5)
        assertTrue(Appearance.contrast(Appearance.DARK_LABEL, Appearance.DARK_BAR) >= 4.5)
        assertTrue(Appearance.contrast(Appearance.DARK_UNSELECTED, Appearance.DARK_BAR) >= 4.5)
        assertTrue(Appearance.contrast(Appearance.DARK_EXPLORE_CARD, Appearance.DARK_PAGE) >= 3.0)
        assertTrue(Appearance.contrast(Appearance.DARK_LABEL, Appearance.DARK_EXPLORE_CARD) >= 4.5)
    }

    @Test
    fun lightNavStaysReadable() {
        assertTrue(Appearance.contrast(Appearance.LIGHT_INDICATOR, Appearance.LIGHT_BAR) >= 3.0)
        assertTrue(Appearance.contrast(Appearance.LIGHT_SELECTED_ICON, Appearance.LIGHT_INDICATOR) >= 3.0)
        assertTrue(Appearance.contrast(Appearance.LIGHT_LABEL, Appearance.LIGHT_BAR) >= 4.5)
        assertTrue(Appearance.contrast(Appearance.LIGHT_UNSELECTED, Appearance.LIGHT_BAR) >= 4.5)
        assertTrue(Appearance.contrast(Appearance.LIGHT_LABEL, Appearance.LIGHT_EXPLORE_CARD) >= 4.5)
    }
}

class LightningAddressTest {
    @Test
    fun matchesLnurlSpecVector() {
        val url = "https://service.com/api?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"
        val encoded = Bech32.encode("lnurl", url.encodeToByteArray())
        assertEquals(
            "lnurl1dp68gurn8ghj7um9wfmxjcm99e3k7mf0v9cxj0m385ekvcenxc6r2c35xvukxefcv5mkvv34x5ekzd3ev56nyd3hxqurzepexejxxepnxscrvwfnv9nxzcn9xq6xyefhvgcxxcmyxymnserxfq5fns",
            encoded,
        )
        val (hrp, decoded) = Bech32.decode(encoded.uppercase())
        assertEquals("lnurl", hrp)
        assertEquals(url, decoded.decodeToString())
    }

    @Test
    fun cakeAddressPointsAtItsLnurlPayEndpoint() {
        val uri = LightningAddress.payUri("zenv9@cake.cash")
        assertTrue(uri.startsWith("lightning:lnurl1"))
        val (hrp, decoded) = Bech32.decode(uri.removePrefix("lightning:"))
        assertEquals("lnurl", hrp)
        assertEquals("https://cake.cash/.well-known/lnurlp/zenv9", decoded.decodeToString())
    }
}

class FieldNoteTest {
    @Test
    fun shareTextKeepsTheDoNotEatLine() {
        val note = FieldNote("Amanita muscaria", rating = 4, text = "Red cap, white spots, under pines.", photos = listOf("a.jpg"))
        val text = note.shareText("Bali, Indonesia")
        assertTrue(text.contains("Amanita muscaria"))
        assertTrue(text.contains("Rating: 4/5"))
        assertTrue(text.contains("Bali, Indonesia"))
        assertTrue(text.contains(FieldNote.SAFETY_LINE))
        assertTrue(text.contains("Never eat"))
    }

    @Test
    fun emptyNoteIsNotSubmittable() {
        assertFalse(FieldNote("Ficus").hasContent())
        assertTrue(FieldNote("Ficus", text = "Roadside tree").hasContent())
        assertEquals(0, FieldNote.clampRating(-3))
        assertEquals(5, FieldNote.clampRating(9))
    }
}
