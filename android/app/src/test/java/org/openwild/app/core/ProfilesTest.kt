package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwild.app.ui.ProfileSync

class ProfilesTest {

    @Test
    fun blankNameIsAnonymousAndDropsThePicture() {
        assertEquals("Anonymous", Attribution.creditName("  "))
        assertEquals("Ada Lovelace", Attribution.creditName("Ada Lovelace"))
        assertNull(Attribution.avatarBytes("  ", byteArrayOf(1, 2, 3)))
        assertNull(Attribution.avatarBytes("Ada", null))
        val jpeg = byteArrayOf(9, 9, 9)
        assertTrue(Attribution.avatarBytes("Ada", jpeg) === jpeg)
        assertEquals("Photo: Anonymous · CC BY 4.0", Attribution.photoCredit("Anonymous", PhotoLicense.CC_BY))
        assertEquals("Review by Ada Lovelace", Attribution.reviewCredit("Ada Lovelace"))
    }

    @Test
    fun syncJsonOmitsThePictureWhenAnonymous() {
        val row = QueuedSubmission(
            id = 4, kind = "review", species = "Quercus robur", regionId = "GB",
            credit = "Anonymous", title = "Student", affiliation = "Example", hasAvatar = false,
        )
        val json = row.toSyncJson("11111111-1111-4111-8111-111111111111", "abc")
        assertTrue(json.contains("\"credit\":\"Anonymous\""))
        assertTrue(json.contains("\"avatarBase64\":null"))
        assertTrue(json.contains("\"title\":\"\""))
        assertFalse(json.contains("@"))
    }

    @Test
    fun namedSyncKeepsTitleAndPicture() {
        val row = QueuedSubmission(
            id = 5, kind = "photo", species = "Hedera helix", regionId = "GB",
            credit = "Ada Lovelace", title = "Student", affiliation = "Example University",
            license = "cc-by", hasAvatar = true,
        )
        val json = row.toSyncJson("11111111-1111-4111-8111-111111111111", "aW1hZ2U=")
        assertTrue(json.contains("\"title\":\"Student\""))
        assertTrue(json.contains("\"avatarBase64\":\"aW1hZ2U=\""))
        assertEquals(1.0, TrustWeight.weight(), 0.0)
    }

    @Test
    fun coarseLocationIsOffUnlessChosen() {
        assertNull(CoarseLocation.cell(false, 106.8, -6.2))
        val cell = CoarseLocation.cell(true, -0.24, 51.26)
        assertEquals(-0.2, cell!!.first, 0.0001)
        assertEquals(51.3, cell.second, 0.0001)
    }

    @Test
    fun onlineSyncStaysOffWithoutAFlagAndAUrl() {
        assertFalse(ProfileSyncGate.allowed(false, ""))
        assertFalse(ProfileSyncGate.allowed(false, "https://profiles.example"))
        assertFalse(ProfileSyncGate.allowed(true, "  "))
        assertTrue(ProfileSyncGate.allowed(true, "https://profiles.example"))
        assertFalse(ProfileSync.enabled())
    }
}
