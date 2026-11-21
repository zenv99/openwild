package org.openwild.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VolunteerHelpTest {
    private val repo = "https://github.com/zenv99/openwild"

    @Test
    fun linksMatchTheContributingSectionsAndGitHub() {
        assertEquals(
            "$repo/blob/main/CONTRIBUTING.md#add-or-fix-plant-photos",
            VolunteerHelp.photos(repo),
        )
        assertEquals(
            "$repo/blob/main/CONTRIBUTING.md#check-plant-safety-info",
            VolunteerHelp.safety(repo),
        )
        assertEquals(
            "$repo/blob/main/CONTRIBUTING.md#translate-the-app",
            VolunteerHelp.translate(repo),
        )
        assertEquals("$repo/issues/new", VolunteerHelp.bug(repo))
        assertEquals(repo, VolunteerHelp.code(repo))
        assertEquals(
            listOf(
                VolunteerHelp.photos(repo),
                VolunteerHelp.safety(repo),
                VolunteerHelp.translate(repo),
                VolunteerHelp.bug(repo),
                VolunteerHelp.code(repo),
            ),
            VolunteerHelp.links("$repo/"),
        )
        VolunteerHelp.links(repo).forEach { link ->
            assertTrue(link.startsWith(repo))
        }
    }

    @Test
    fun aboutScreenOpensAnOfflineVolunteerScreenBesideDonate() {
        val root = repoRoot()
        val about = File(root, "android/app/src/main/java/org/openwild/app/ui/screens/AboutScreen.kt").readText()
        val screen = File(root, "android/app/src/main/java/org/openwild/app/ui/screens/VolunteerScreen.kt").readText()
        val strings = File(root, "android/app/src/main/res/values/strings.xml").readText()
        val manifest = File(root, "android/app/src/main/AndroidManifest.xml").readText()

        assertTrue(about.indexOf("BitcoinDonateSection()") < about.indexOf("Screen.Volunteer"))
        assertTrue(about.contains("R.string.volunteer_open"))
        assertTrue(screen.contains("LocalUriHandler"))
        assertTrue(screen.contains("openUri"))
        assertTrue(screen.contains("VolunteerHelp.photos"))
        assertTrue(screen.contains("VolunteerHelp.safety"))
        assertTrue(screen.contains("VolunteerHelp.translate"))
        assertTrue(screen.contains("VolunteerHelp.bug"))
        assertTrue(screen.contains("VolunteerHelp.code"))
        assertTrue(screen.contains("Footer()"))
        assertFalse(screen.contains("HttpURLConnection"))
        assertFalse(screen.contains("java.net.URL"))
        assertTrue(strings.contains("Volunteer &amp; improve OpenWild"))
        assertTrue(strings.contains("Add or fix plant photos"))
        assertTrue(strings.contains("Check plant safety info (botanists / foragers)"))
        assertTrue(strings.contains("Translate the app"))
        assertTrue(strings.contains("Report a bug or idea"))
        assertTrue(strings.contains(">Help with code<"))
        assertTrue(strings.contains("Wrong ID?"))
        assertTrue(strings.contains("This is it"))
        assertTrue(strings.contains("Never eat anything based only on this app."))
        assertFalse(strings.contains("Bali"))
        assertFalse(screen.contains("Bali"))
        assertEquals(1, Regex("""uses-permission android:name="android.permission.INTERNET"""").findAll(manifest).count())
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("READ_CONTACTS"))
    }

    @Test
    fun contributingAndReadmeDescribeTheSameWaysToHelp() {
        val root = repoRoot()
        val contributing = File(root, "CONTRIBUTING.md").readText()
        val readme = File(root, "README.md").readText()

        assertTrue(contributing.contains("### Add or fix plant photos"))
        assertTrue(contributing.contains("### Check plant safety info"))
        assertTrue(contributing.contains("### Translate the app"))
        assertTrue(contributing.contains("### Report a bug or idea"))
        assertTrue(contributing.contains("### Help with code"))
        assertTrue(contributing.contains("Wikimedia Commons"))
        assertTrue(contributing.contains("iNaturalist"))
        assertTrue(contributing.contains("GBIF"))
        assertTrue(contributing.contains("Always credit"))
        assertTrue(contributing.contains("Plants For A Future"))
        assertTrue(contributing.contains("tropical.theferns.info"))
        assertTrue(contributing.contains("Safety data needs a source."))
        assertTrue(contributing.contains("Wrong ID?"))
        assertTrue(contributing.contains("This is it"))
        assertTrue(contributing.contains("Never eat anything based only on this app."))
        assertFalse(contributing.contains("Bali"))
        assertTrue(readme.contains("[CONTRIBUTING.md](CONTRIBUTING.md)"))
        assertTrue(readme.contains("Volunteer & improve OpenWild"))
        assertFalse(readme.contains("Bali"))
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        repeat(8) {
            if (File(dir, "README.md").isFile && File(dir, "android").isDirectory) return dir
            dir = dir.parentFile ?: error("repo root not found from ${File("").absolutePath}")
        }
        error("repo root not found from ${File("").absolutePath}")
    }
}
