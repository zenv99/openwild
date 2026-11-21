package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryHintTest {
    private val indonesia = RegionInfo("ID", "Indonesia", emptyList())
    private val thailand = RegionInfo("TH", "Thailand", emptyList())
    private val unitedStates = RegionInfo("US", "United States", emptyList())
    private val world = RegionInfo("WORLD", "World (common species)", emptyList())
    private val finer = RegionInfo("QQ-1", "Finer area", emptyList(), parentId = "QQ", hidden = true)
    private val parent = RegionInfo("QQ", "Parent country", emptyList())
    private val regions = listOf(world, indonesia, thailand, unitedStates, finer, parent)

    @Test
    fun coarseLocationWinsWhenItNamesACountry() {
        val match = CountryHint.match(
            regions,
            CountryHint.Signals(localeCountry = "US", simCountry = "TH", locationCountryId = "ID"),
        )
        assertEquals("ID", match?.id)
    }

    @Test
    fun cellNetworkBeatsSimAndLocale() {
        val match = CountryHint.match(
            regions,
            CountryHint.Signals(localeCountry = "US", simCountry = "US", networkCountry = "th"),
        )
        assertEquals("TH", match?.id)
    }

    @Test
    fun simBeatsTimeZoneAndLocale() {
        val match = CountryHint.match(
            regions,
            CountryHint.Signals(localeCountry = "TH", simCountry = "US", timezoneId = "Asia/Jakarta"),
        )
        assertEquals("US", match?.id)
    }

    @Test
    fun indonesianTimeZoneBeatsLocale() {
        val match = CountryHint.match(
            regions,
            CountryHint.Signals(localeCountry = "US", timezoneId = "Asia/Makassar"),
        )
        assertEquals("ID", match?.id)
        assertEquals("ID", CountryHint.match(regions, CountryHint.Signals(timezoneId = "Asia/Jakarta"))?.id)
        assertEquals("ID", CountryHint.match(regions, CountryHint.Signals(timezoneId = "Asia/Jayapura"))?.id)
    }

    @Test
    fun localeIsUsedWhenNothingElseMatches() {
        assertEquals("US", CountryHint.match(regions, CountryHint.Signals(localeCountry = "us"))?.id)
    }

    @Test
    fun hiddenFinerPackAndWorldAreNeverTheCountry() {
        assertNull(CountryHint.match(regions, CountryHint.Signals(locationCountryId = "QQ-1")))
        assertEquals("TH", CountryHint.match(regions, CountryHint.Signals(locationCountryId = "WORLD", localeCountry = "TH"))?.id)
        assertNull(CountryHint.match(regions, CountryHint.Signals()))
        assertNull(CountryHint.match(regions, CountryHint.Signals(localeCountry = "USA", simCountry = "U1")))
        val choices = CountryHint.countries(regions)
        assertFalse(choices.any { it.hidden || it.id == "WORLD" || it.id == "QQ-1" })
        assertEquals(listOf("ID", "QQ", "TH", "US"), choices.map { it.id })
    }

    @Test
    fun codesAreTwoLetters() {
        assertEquals("ID", CountryHint.normalizeCode(" id "))
        assertNull(CountryHint.normalizeCode("ID-BA"))
        assertNull(CountryHint.normalizeCode("WORLD"))
        assertNull(CountryHint.normalizeRegionId("WORLD"))
        assertEquals("QQ-1", CountryHint.normalizeRegionId("qq-1"))
    }
}

class DownloadCautionTest {
    @Test
    fun warnsAtFiftyMegabytesAndAbove() {
        assertFalse(DownloadCaution.needsWarning(49_999_999))
        assertTrue(DownloadCaution.needsWarning(DownloadCaution.WIFI_WARN_BYTES))
        assertTrue(DownloadCaution.needsWarning(120_000_000))
        assertFalse(DownloadCaution.needsWarning(0))
    }

    @Test
    fun formatsMegabytes() {
        assertEquals("—", DownloadCaution.format(0))
        assertEquals("0.5 MB", DownloadCaution.format(500_000))
        assertEquals("5.5 MB", DownloadCaution.format(5_500_000))
        assertEquals("50 MB", DownloadCaution.format(50_000_000))
        assertEquals("412 MB", DownloadCaution.format(412_400_000))
    }
}

class RecommendedBundleTest {
    private fun side(bytes: Long, onPhone: Boolean = false, light: Boolean = false, offered: Boolean = true) =
        RecommendedBundle.Side(bytes, onPhone, light, offered)

    @Test
    fun oneTapTransfersBothFullPacks() {
        val plan = RecommendedBundle.Plan("ID", "Indonesia", side(80_000_000), side(20_000_000))
        assertTrue(plan.fetchCountry && plan.fetchWorld && plan.canStart)
        assertFalse(plan.complete)
        assertEquals(100_000_000L, plan.totalBytes)
        assertEquals(100_000_000L, plan.transferBytes)
        assertEquals(RecommendedBundle.SizeKind.TOTAL to 100_000_000L, RecommendedBundle.sizeOf(80_000_000, 20_000_000))
    }

    @Test
    fun aLighterCopyStillNeedsTheFullSet() {
        val plan = RecommendedBundle.Plan(
            "ID",
            "Indonesia",
            side(80_000_000, onPhone = true, light = true),
            side(20_000_000, onPhone = true, light = false),
        )
        assertTrue(plan.fetchCountry)
        assertFalse(plan.fetchWorld)
        assertEquals(80_000_000L, plan.transferBytes)
        assertFalse(plan.complete)
    }

    @Test
    fun nothingToFetchWhenBothFullPacksAreOnThePhone() {
        val plan = RecommendedBundle.Plan(
            "TH",
            "Thailand",
            side(10_000_000, onPhone = true),
            side(20_000_000, onPhone = true),
        )
        assertTrue(plan.complete)
        assertFalse(plan.canStart)
        assertEquals(0L, plan.transferBytes)
    }

    @Test
    fun missingPacksCannotStart() {
        val plan = RecommendedBundle.Plan(
            "TH",
            "Thailand",
            side(0, offered = false),
            side(0, offered = false),
        )
        assertFalse(plan.canStart)
        assertFalse(plan.complete)
        assertEquals(RecommendedBundle.SizeKind.UNKNOWN to 0L, RecommendedBundle.sizeOf(0, 0))
        assertEquals(RecommendedBundle.SizeKind.AT_LEAST to 12_000_000L, RecommendedBundle.sizeOf(12_000_000, 0))
    }

    @Test
    fun progressFollowsTheFileInFlight() {
        assertNull(RecommendedBundle.activeProgress("ID", emptyMap()))
        assertEquals(0.4f, RecommendedBundle.activeProgress("ID", mapOf("ID" to 0.4f))!!, 0.001f)
        assertEquals(0.25f, RecommendedBundle.activeProgress("ID", mapOf("WORLD" to 0.25f))!!, 0.001f)
        assertEquals(0.5f, RecommendedBundle.activeProgress("ID", mapOf("ID" to 0.4f, "WORLD" to 0.6f))!!, 0.001f)
    }
}
