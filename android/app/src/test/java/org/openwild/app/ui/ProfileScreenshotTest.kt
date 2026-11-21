package org.openwild.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.openwild.app.ui.screens.ProfileScreen
import org.openwild.app.ui.theme.OpenWildTheme

/** Renders the profile screen without a device. Goldens live under src/test/snapshots. */
class ProfileScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    @Test
    fun empty() = paparazzi.snapshot { OpenWildTheme(darkTheme = true) { ProfileScreen(ProfileSamples.empty, onEvent = {}) } }

    @Test
    fun publicVerified() = paparazzi.snapshot { OpenWildTheme(darkTheme = true) { ProfileScreen(ProfileSamples.publicVerified, onEvent = {}) } }

    @Test
    fun anonymous() = paparazzi.snapshot { OpenWildTheme(darkTheme = true) { ProfileScreen(ProfileSamples.anonymous, onEvent = {}) } }
}
