package org.openwild.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.pack.RegionPack
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Pill
import org.openwild.app.ui.Screen
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.ForestDeep
import org.openwild.app.ui.theme.HeroGradient
import org.openwild.app.ui.theme.Leaf
import org.openwild.app.ui.theme.Lime

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    val canIdentify = state.activeRegionName != null && !state.modelMissing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---------- Hero ----------
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(HeroGradient)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = state.activeRegionName?.let { stringResource(R.string.region_pill, it, state.activeSpeciesCount) }
                            ?: stringResource(R.string.no_region_pill),
                        fg = ForestDeep, bg = Lime, icon = Icons.Filled.LocationOn,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Big shutter-style identify button
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(156.dp)
                            .clip(CircleShape)
                            .border(6.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .padding(10.dp)
                            .clip(CircleShape)
                            .background(if (canIdentify) Lime else Color.White.copy(alpha = 0.3f))
                            .clickable(enabled = canIdentify) { vm.startCapture() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = ForestDeep, modifier = Modifier.size(44.dp))
                            Text(stringResource(R.string.identify), color = ForestDeep, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                Text(
                    stringResource(R.string.works_offline),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.modelMissing) WarningBox(stringResource(R.string.model_missing), danger = false)
            if (state.activeRegionName == null) {
                WarningBox(stringResource(R.string.no_pack), danger = false)
            }
            state.suggestedRegion?.let {
                Text(stringResource(R.string.suggested_region, it), style = MaterialTheme.typography.bodySmall)
            }

            // ---------- Quick tiles ----------
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    Modifier.weight(1f), Icons.Filled.Warning, Danger, stringResource(R.string.tile_deadly),
                    stringResource(R.string.tile_deadly_sub), enabled = state.activeRegionName != null,
                ) {
                    vm.setExploreFilter(RegionPack.ExploreFilter.DEADLY); vm.tab(Screen.Explore)
                }
                Tile(
                    Modifier.weight(1f), Icons.Filled.HealthAndSafety, Leaf, stringResource(R.string.survival_guide),
                    stringResource(R.string.tile_survival_sub), enabled = state.activeRegionName != null,
                ) { vm.go(Screen.Survival) }
            }

            // ---------- Survival mode ----------
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.survival_mode), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.survival_mode_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.survivalMode,
                        onCheckedChange = vm::setSurvivalMode,
                        colors = SwitchDefaults.colors(checkedTrackColor = Leaf),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = state.activeRegionName != null) { vm.go(Screen.Credits) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.credits), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Footer()
        }
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier.clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = tint) }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
