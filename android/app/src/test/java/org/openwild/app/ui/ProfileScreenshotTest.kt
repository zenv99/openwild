package org.openwild.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.openwild.app.ui.theme.OpenWildTheme

/** Renders the photo send form. Goldens live under src/test/snapshots. */
class ProfileScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    @Test
    fun blank() = paparazzi.snapshot {
        OpenWildTheme(darkTheme = true) { PhotoSendForm(CreditDraft()) }
    }

    @Test
    fun filled() = paparazzi.snapshot {
        val picture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        picture.eraseColor(Color.parseColor("#2E8B57"))
        OpenWildTheme(darkTheme = true) {
            PhotoSendForm(
                CreditDraft(
                    name = "Ada Lovelace",
                    title = "Student",
                    affiliation = "Example University",
                    remember = true,
                    avatarPreview = picture,
                    detailsOpen = true,
                ),
            )
        }
    }
}
