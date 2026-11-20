package org.openwild.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BitcoinDonationTest {
    @Test
    fun displayedAddressEqualsTheConstantExactly() {
        val expected = "bc1qgj3ce8qeq22s37py9s6m2ksjpz9v3zhls5re3a"
        val shown = BitcoinDonation.displayedAddress()
        assertEquals(expected, BitcoinDonation.ADDRESS)
        assertEquals(BitcoinDonation.ADDRESS, shown)
        assertEquals(expected.length, shown.length)
        for (i in expected.indices) {
            assertEquals("character at $i", expected[i], shown[i])
        }
        assertEquals("bitcoin:$expected", BitcoinDonation.qrPayload())
        assertTrue(BitcoinDonation.qrPayload().endsWith(shown))
    }

    @Test
    fun aboutScreenAndReadmeShowThatSameAddress() {
        val root = repoRoot()
        val about = File(root, "android/app/src/main/java/org/openwild/app/ui/screens/AboutScreen.kt").readText()
        val section = File(root, "android/app/src/main/java/org/openwild/app/ui/screens/BitcoinDonateSection.kt").readText()
        val readme = File(root, "README.md").readText()
        val funding = File(root, ".github/FUNDING.yml").readText()
        val address = BitcoinDonation.displayedAddress()

        assertTrue(about.contains("BitcoinDonateSection()"))
        assertTrue(section.contains("BitcoinDonation.displayedAddress()"))
        assertFalse(about.contains(address))
        assertFalse(section.contains(address))
        assertTrue(readme.contains("## Support the project"))
        assertTrue(readme.contains("```\n$address\n```"))
        assertTrue(funding.contains("https://github.com/zenv99/openwild#support-the-project"))
        assertTrue(readme.contains("#support-the-project") || readme.contains("## Support the project"))
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
