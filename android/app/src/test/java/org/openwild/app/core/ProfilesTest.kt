package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwild.app.ui.ProfileSamples
import org.openwild.app.ui.ProfileSync
import java.io.File

class ProfilesTest {

    @Test
    fun creditNeverUsesTheMailbox() {
        assertEquals("Anonymous", Attribution.creditName("anonymous", "Ada Lovelace"))
        assertEquals("Anonymous", Attribution.creditName("anonymous", "ada@mit.edu"))
        assertEquals("Ada Lovelace", Attribution.creditName("public", "Ada Lovelace"))
        assertEquals("Anonymous", Attribution.creditName("public", "  "))
        assertEquals("Photo: Anonymous · CC BY 4.0", Attribution.photoCredit("Anonymous", PhotoLicense.CC_BY))
        assertEquals("Review by Ada Lovelace", Attribution.reviewCredit("Ada Lovelace"))
    }

    @Test
    fun publicViewOmitsTheMailbox() {
        val profile = LocalProfile(
            displayName = "Ada Lovelace",
            title = "Student",
            affiliation = "Example University",
            visibility = "anonymous",
            email = "ada@mit.edu",
            verifiedAcademic = true,
            verifiedDomain = "mit.edu",
        )
        val hidden = ProfileDocuments.publicJson(profile)
        assertFalse(hidden.contains("ada@mit.edu"))
        assertFalse(hidden.contains("mit.edu"))
        assertFalse(hidden.contains("email"))
        assertTrue(hidden.contains("Anonymous"))
        val owner = ProfileDocuments.ownerExport(profile, emptyList())
        assertTrue(owner.contains("ada@mit.edu"))
        val shown = ProfileDocuments.publicJson(profile.copy(visibility = "public"))
        assertTrue(shown.contains("mit.edu"))
        assertFalse(shown.contains("ada@mit.edu"))
        assertFalse(shown.contains("\"email\""))
    }

    @Test
    fun syncJsonHasNoMailbox() {
        val row = LocalContribution(
            id = 3, kind = "review", species = "Quercus robur", regionId = "GB",
            body = "A large oak.", rating = 5, visibility = "anonymous", license = "cc-by",
            credit = "Anonymous",
        )
        val json = row.toSyncJson()
        assertFalse(json.contains("@"))
        assertFalse(json.contains("email"))
        assertTrue(json.contains("\"credit\":\"Anonymous\""))
    }

    @Test
    fun coarseLocationIsOffUnlessChosen() {
        assertNull(CoarseLocation.cell(false, 106.8, -6.2))
        assertNull(CoarseLocation.cell(true, null, 1.0))
        val cell = CoarseLocation.cell(true, -0.24, 51.26)
        assertEquals(-0.2, cell!!.first, 0.0001)
        assertEquals(51.3, cell.second, 0.0001)
    }

    @Test
    fun trustWeightsMatchTheSharedFixture() {
        val text = repoFile("server/test/fixtures/trust-weights.json").readText()
        val cases = text.split("{").drop(1)
        assertTrue(cases.size >= 8)
        for (block in cases) {
            val verified = bool(block, "verifiedAcademic")
            val visibility = str(block, "visibility")
            val age = num(block, "accountAgeDays").toInt()
            val kind = str(block, "kind")
            val approved = bool(block, "approved")
            val weight = num(block, "weight").toDouble()
            assertEquals(str(block, "name"), weight, TrustWeight.weight(verified, visibility, age, kind, approved), 0.0001)
        }
    }

    @Test
    fun academicDomainsMatchTheVendoredLists() {
        val domains = AcademicDomains.parseList(repoFile("server/data/academic-domains.txt").readText())
        val abused = AcademicDomains.parseList(repoFile("server/data/academic-domains-abused.txt").readText())
        assertEquals("listed", AcademicDomains.check("ada@mit.edu", domains, abused).reason)
        assertEquals("mit.edu", AcademicDomains.check("a@cs.mit.edu", domains, abused).matchedDomain)
        assertEquals("listed", AcademicDomains.check("student@ox.ac.uk", domains, abused).reason)
        assertTrue(AcademicDomains.check("student@itb.ac.id", domains, abused).academic)
        assertEquals("abused", AcademicDomains.check("student@ui.ac.id", domains, abused).reason)
        assertEquals("abused", AcademicDomains.check("a@si.edu", domains, abused).reason)
        assertEquals("suffix", AcademicDomains.check("a@notaschool.edu", domains, abused).reason)
        assertEquals("free-mail", AcademicDomains.check("ada@gmail.com", domains, abused).reason)
        assertFalse(AcademicDomains.check("a@example.ac", domains, abused).academic)
        assertEquals("not-an-email", AcademicDomains.check("not-an-email", domains, abused).reason)
    }

    @Test
    fun anonymousSampleHidesTheBadgeAndTheMailbox() {
        assertFalse(ProfileSamples.anonymous.badgeVisible)
        assertFalse(ProfileSamples.anonymous.photoCreditLine().contains("@"))
        assertTrue(ProfileSamples.anonymous.photoCreditLine().contains("Anonymous"))
        assertTrue(ProfileSamples.publicVerified.badgeVisible)
        assertTrue(ProfileSamples.publicVerified.photoCreditLine().contains("Ada Lovelace"))
        assertTrue(ProfileSamples.publicVerified.photoCreditLine().contains("CC BY 4.0"))
        assertFalse(ProfileSync.enabled())
    }

    @Test
    fun onlineSyncStaysOffWithoutAFlagAndAUrl() {
        assertFalse(ProfileSyncGate.allowed(false, ""))
        assertFalse(ProfileSyncGate.allowed(false, "https://profiles.example"))
        assertFalse(ProfileSyncGate.allowed(true, "  "))
        assertTrue(ProfileSyncGate.allowed(true, "https://profiles.example"))
    }

    private fun bool(block: String, key: String) = Regex(""""$key"\s*:\s*(true|false)""").find(block)!!.groupValues[1].toBoolean()
    private fun str(block: String, key: String): String =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(block)!!.groupValues[1] ?: ""
    private fun num(block: String, key: String) = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(block)!!.groupValues[1]

    private fun repoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(relative),
            File(cwd, relative),
            File(cwd, "../$relative"),
            File(cwd, "../../$relative"),
        )
        return candidates.firstOrNull { it.isFile } ?: error("missing $relative from $cwd")
    }
}
