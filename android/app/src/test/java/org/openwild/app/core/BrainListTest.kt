package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainListTest {
    @Test
    fun standardIsAlwaysListedOnce() {
        val ids = BrainList.offered(boostPublished = false, boostOnPhone = false, downloading = false)
        assertEquals(listOf(BrainList.STANDARD), ids)
    }

    @Test
    fun bioclipIsListedOnlyWhenThisPackCanUseIt() {
        assertEquals(
            listOf(BrainList.STANDARD, BrainList.BIOCLIP),
            BrainList.offered(boostPublished = true, boostOnPhone = false, downloading = false),
        )
        assertEquals(
            listOf(BrainList.STANDARD, BrainList.BIOCLIP),
            BrainList.offered(boostPublished = false, boostOnPhone = true, downloading = false),
        )
        assertFalse(BrainList.offered(false, false, false).contains(BrainList.BIOCLIP))
    }

    @Test
    fun aDownloadInProgressKeepsTheRow() {
        assertEquals(
            listOf(BrainList.STANDARD, BrainList.BIOCLIP),
            BrainList.offered(boostPublished = false, boostOnPhone = false, downloading = true),
        )
    }

    @Test
    fun recommendedIsOneConstantAndBothModelsStaySelectable() {
        assertTrue(BrainList.RECOMMENDED == BrainList.STANDARD || BrainList.RECOMMENDED == BrainList.BIOCLIP)
        assertTrue(BrainList.isRecommended(BrainList.RECOMMENDED))
        assertFalse(BrainList.isRecommended(if (BrainList.RECOMMENDED == BrainList.BIOCLIP) BrainList.STANDARD else BrainList.BIOCLIP))
        val offered = BrainList.offered(boostPublished = true, boostOnPhone = false, downloading = false)
        assertEquals(listOf(BrainList.STANDARD, BrainList.BIOCLIP), offered)
    }

    @Test
    fun reportNamesTheBrainThatActuallyRan() {
        assertEquals(BrainList.BIOCLIP, BrainList.ran(BrainList.BIOCLIP, bioclipActive = true))
        assertEquals(BrainList.STANDARD, BrainList.ran(BrainList.BIOCLIP, bioclipActive = false))
        assertEquals(BrainList.STANDARD, BrainList.ran(BrainList.STANDARD, bioclipActive = false))
        assertEquals(BrainList.SCAN_SEVERAL, BrainList.scanDetail(true))
        assertEquals(BrainList.SCAN_ONE, BrainList.scanDetail(false))
    }
}
