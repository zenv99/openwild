package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
