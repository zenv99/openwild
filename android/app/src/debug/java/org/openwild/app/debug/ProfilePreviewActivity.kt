package org.openwild.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.openwild.app.R
import org.openwild.app.ui.ProfileSamples
import org.openwild.app.ui.screens.ProfileScreen
import org.openwild.app.ui.theme.OpenWildTheme

/** Shows the profile screen without a region pack, for screenshots of a debug build. */
class ProfilePreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val form = when (intent.getStringExtra("mode")) {
            "public" -> ProfileSamples.publicVerified
            "anonymous" -> ProfileSamples.anonymous
            else -> ProfileSamples.empty
        }
        setContent {
            OpenWildTheme(darkTheme = true) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.profile_title)) },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        ProfileScreen(form, onEvent = {})
                    }
                }
            }
        }
    }
}
