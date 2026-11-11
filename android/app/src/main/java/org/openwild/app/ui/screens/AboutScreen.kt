@file:OptIn(ExperimentalMaterial3Api::class)

package org.openwild.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.BuildConfig
import org.openwild.app.R
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionCard
import org.openwild.app.ui.ThemeMode
import org.openwild.app.ui.UiState
import org.openwild.app.ui.theme.ForestDeep
import org.openwild.app.ui.theme.HeroGradient
import org.openwild.app.ui.theme.Leaf
import org.openwild.app.ui.theme.Lime

/** Settings, privacy, contributing, donations and credits. */
@Composable
fun AboutScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    ScrollColumn {
        // Creator card
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(HeroGradient).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(stringResource(R.string.created_by), color = Lime, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.creator_bio), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.gift_line), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                Text("v${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }

        if (BuildConfig.SHOW_DONATIONS && BuildConfig.LIGHTNING_ADDRESS.isNotBlank()) {
            SectionCard(stringResource(R.string.donate), Icons.Filled.Favorite, Lime) {
                Text(stringResource(R.string.donate_body))
                Text(BuildConfig.LIGHTNING_ADDRESS, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { Contribute.donateLightning(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = ForestDeep),
                ) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.donate_lightning), fontWeight = FontWeight.Bold)
                }
            }
        }

        SectionCard(stringResource(R.string.feedback_title), Icons.Filled.Feedback, MaterialTheme.colorScheme.primary) {
            Text(stringResource(R.string.feedback_body))
            Button(onClick = { Contribute.sendFeedback(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feedback_send))
            }
        }

        SectionCard(stringResource(R.string.appearance), Icons.Filled.Palette, MaterialTheme.colorScheme.primary) {
            val modes = listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SYSTEM to R.string.theme_system)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = state.themeMode == m,
                        onClick = { vm.setThemeMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }

        SectionCard(stringResource(R.string.privacy_title), Icons.Filled.Lock, Leaf) {
            Text(stringResource(R.string.privacy_body))
        }

        SectionCard(stringResource(R.string.contribute_title), Icons.Filled.VerifiedUser, MaterialTheme.colorScheme.primary) {
            Text(stringResource(R.string.contribute_body))
            TextButton(onClick = { Contribute.open(context, "${BuildConfig.REPO_URL}/blob/main/CONTRIBUTING.md") }) {
                Icon(Icons.Filled.VerifiedUser, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.contribute_review))
            }
            TextButton(onClick = { Contribute.open(context, "${BuildConfig.REPO_URL}/blob/main/CONTRIBUTING.md#ways-to-help") }) {
                Icon(Icons.Filled.Translate, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.contribute_translate))
            }
            TextButton(onClick = { Contribute.open(context, "https://www.inaturalist.org") }) {
                Icon(Icons.Filled.Info, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.contribute_inat))
            }
            TextButton(onClick = { Contribute.open(context, BuildConfig.REPO_URL) }) {
                Icon(Icons.Filled.Code, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.source_code))
            }
        }

        TextButton(onClick = { vm.go(Screen.Credits) }, enabled = vm.pack != null) { Text(stringResource(R.string.credits)) }
        Footer()
    }
}
