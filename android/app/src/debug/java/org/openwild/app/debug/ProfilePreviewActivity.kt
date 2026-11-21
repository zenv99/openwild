package org.openwild.app.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import org.openwild.app.ui.CreditDraft
import org.openwild.app.ui.PhotoSendForm
import org.openwild.app.ui.theme.OpenWildTheme

/** Debug-only preview of the send form. Release builds do not include this activity. */
class ProfilePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filled = intent.getStringExtra("mode") == "filled"
        val picture = if (!filled) null else Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.parseColor("#2E8B57"))
        }
        val draft = if (!filled) CreditDraft() else CreditDraft(
            name = "Ada Lovelace",
            title = "Student",
            affiliation = "Example University",
            remember = true,
            avatarPreview = picture,
            detailsOpen = true,
        )
        setContent {
            OpenWildTheme(darkTheme = true) {
                Scaffold { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        PhotoSendForm(draft)
                    }
                }
            }
        }
    }
}
