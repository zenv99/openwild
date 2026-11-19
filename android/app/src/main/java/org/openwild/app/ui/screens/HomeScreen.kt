package org.openwild.app.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import org.openwild.app.ui.PhotoWanted
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import org.openwild.app.ui.ForestBackdrop
import org.openwild.app.ui.GlassPanel
import org.openwild.app.ui.motionAllowed
import org.openwild.app.core.Tier
import org.openwild.app.ui.SpeciesTile
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.openwild.app.ui.PlantNetToggle
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
    val regionOn = state.activeRegionName != null
    val canPhoto = regionOn && state.brainOn && !state.modelMissing && state.photoIdReady
    val scroll = rememberScrollState()
    val motion = motionAllowed(LocalContext.current)
    Box(Modifier.fillMaxSize().background(Color(0xFF04140F))) {
        ForestBackdrop(scroll.value.toFloat(), motion, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
            Pill(
                text = state.activeRegionName?.let { stringResource(R.string.region_pill, it, state.activeSpeciesCount) }
                    ?: stringResource(R.string.no_region_pill),
                fg = ForestDeep, bg = Lime, icon = Icons.Filled.LocationOn,
            )
            Text(
                stringResource(R.string.landing_art_credit),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(168.dp))
            GlassPanel {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.clip(CircleShape)
                            .background(
                                when {
                                    canPhoto -> Lime
                                    regionOn -> Lime.copy(alpha = 0.75f)
                                    else -> Color.White.copy(alpha = 0.3f)
                                },
                            )
                            .clickable(enabled = regionOn) { vm.startCapture() }
                            .padding(horizontal = 28.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = ForestDeep, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.identify),
                            color = ForestDeep,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Text(stringResource(R.string.works_offline), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
            GlassPanel {
                Text(stringResource(R.string.explore), color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (state.activeRegionName != null && state.brainOn && (state.modelMissing || !state.photoIdReady)) {
                WarningBox(stringResource(R.string.model_missing), danger = false)
            }
            if (state.activeRegionName == null) {
                WarningBox(stringResource(R.string.no_pack), danger = false)
            }
            val bundledUpdate = state.bundled.firstOrNull { it.regionId == state.activeRegionId && it.version > state.activePackVersion }
            if (bundledUpdate != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.pack_update_banner), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.installRegion(bundledUpdate.regionId) }) { Text(stringResource(R.string.pack_update_action)) }
                }
            }
            // The copy inside the app is cut down to fit; the full photo set (every stage of every plant) is one
            // download away from GitHub. Offered here, not only in the Packs tab.
            LaunchedEffect(Unit) { if (org.openwild.app.BuildConfig.ALLOW_NETWORK && state.remote.isEmpty()) vm.refreshRemote() }
            // Offer only what this phone doesn't have yet: the full photo set when the smaller "essentials"
            // download is installed, or a newer version with what's new in it. "Not now" is remembered.
            val active = vm.pack
            val remoteForActive = state.remote.firstOrNull { it.regionId == state.activeRegionId }
            val offer = remoteForActive?.takeIf { r ->
                r.version > state.activePackVersion &&
                    (state.dismissedUpdates[r.regionId] ?: 0) < r.version &&
                    (active?.isLight == true || state.updateReminders)
            }
            if (offer != null && bundledUpdate == null && active != null) {
                val pct = state.downloading[offer.regionId]
                val light = active.isLight
                val newPhotos = (offer.photoSpecies - active.photoSpeciesCount).coerceAtLeast(0)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (light) stringResource(R.string.full_photos_title, offer.name)
                            else stringResource(R.string.update_ready_title, offer.name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (light) stringResource(R.string.full_photos_body)
                            else if (newPhotos > 0) stringResource(R.string.update_ready_body, newPhotos)
                            else stringResource(R.string.update_ready_body_small),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (pct != null) {
                            Text(stringResource(R.string.status_downloading_named, offer.name, (pct * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { vm.download(offer) }) {
                                    Text(
                                        if (light) stringResource(R.string.full_photos_action, offer.name, (offer.size / 1_000_000).toInt())
                                        else stringResource(R.string.update_ready_action, (offer.size / 1_000_000).toInt())
                                    )
                                }
                                TextButton(onClick = { vm.dismissUpdate(offer.regionId, offer.version) }) {
                                    Text(stringResource(R.string.not_now))
                                }
                            }
                        }
                    }
                }
            }
            state.suggestedRegion?.let {
                Text(stringResource(R.string.suggested_region, it), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
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
            }

            // ---------- Photo rows: food and deadly species of this region ----------
            val pack = vm.pack
            if (pack != null && state.activeRegionName != null) {
                val guide = remember(pack.regionId, pack.version) {
                    val (food, deadly) = pack.survivalGuide()
                    val photoFirst = compareByDescending<Int> { it in pack.withPhoto }
                    food.sortedWith(photoFirst).take(15) to deadly.sortedWith(photoFirst).take(15)
                }
                PhotoRow(stringResource(R.string.home_food_row), guide.first, pack, vm) {
                    vm.setExploreFilter(RegionPack.ExploreFilter.SURVIVAL); vm.tab(Screen.Explore)
                }
                PhotoRow(stringResource(R.string.home_deadly_row), guide.second, pack, vm) {
                    vm.setExploreFilter(RegionPack.ExploreFilter.DEADLY); vm.tab(Screen.Explore)
                }
            }

            // ---------- Be a plant photographer: the community builds the library ----------
            if (pack != null && state.activeRegionName != null) {
                val wanted = remember(pack.regionId, pack.version) { pack.mostWanted(limit = pack.species.size) }
                if (wanted.isNotEmpty()) {
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            vm.setExploreFilter(RegionPack.ExploreFilter.PHOTOS_WANTED); vm.tab(Screen.Explore)
                        },
                        colors = CardDefaults.cardColors(containerColor = PhotoWanted.copy(alpha = 0.14f)),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PhotoCamera, null, tint = PhotoWanted, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.home_photographer_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.home_photographer_body, wanted.size, state.activeRegionName),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
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

            GlassPanel {
                LegacyPackOffer(state, vm)
                OnThisPhone(state, vm)
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
private fun LegacyPackOffer(state: UiState, vm: AppViewModel) {
    state.installed.forEach { installed ->
        val info = state.regions.firstOrNull { it.id == installed.regionId && it.hidden } ?: return@forEach
        val parent = state.regions.firstOrNull { it.id == info.parentId && !it.hidden } ?: return@forEach
        val haveParent = state.installed.any { it.regionId == parent.id }
        if (haveParent) return@forEach
        val remote = state.remote.firstOrNull { it.regionId == parent.id }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.legacy_pack_offer, installed.name, parent.name),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (remote != null) {
                Button(onClick = { vm.download(remote) }) { Text(stringResource(R.string.legacy_pack_action, parent.name)) }
            } else {
                TextButton(onClick = { vm.tab(Screen.Packs) }) { Text(stringResource(R.string.legacy_pack_action, parent.name)) }
            }
        }
    }
}

@Composable
private fun OnThisPhone(state: UiState, vm: AppViewModel) {
    var deleteRegion by remember { mutableStateOf<String?>(null) }
    var removeDownload by remember { mutableStateOf(false) }
    LaunchedEffect(state.activeRegionId) { vm.refreshUpgrade() }
    val mb = { bytes: Long -> if (bytes <= 0L) "—" else "%.0f MB".format(bytes / 1_000_000.0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.on_this_phone), color = Color.White, style = MaterialTheme.typography.titleMedium)
        BrainCard(state, vm, onRemoveDownload = { removeDownload = true })
        if (state.installed.isEmpty()) {
            Text(stringResource(R.string.no_region_on_phone), style = MaterialTheme.typography.bodySmall)
        }
        state.installed.forEach { installed ->
            val on = installed.regionId in state.enabledRegionIds
            val explore = on && installed.regionId == state.activeRegionId
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(installed.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.region_row_meta, installed.version, mb(installed.file.length()), installed.speciesCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(if (installed.photoId) R.string.region_has_photos else R.string.region_names_only),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (on) {
                            Text(
                                stringResource(if (explore) R.string.region_explore_focus else R.string.region_also_on),
                                style = MaterialTheme.typography.labelMedium,
                                color = Leaf,
                            )
                        }
                        val info = state.regions.firstOrNull { it.id == installed.regionId && it.hidden }
                        val parent = info?.parentId?.let { id -> state.regions.firstOrNull { it.id == id } }
                        if (parent != null && state.installed.any { it.regionId == parent.id }) {
                            Text(
                                stringResource(R.string.legacy_pack_remove, parent.name, installed.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = Lime,
                            )
                        }
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { vm.setRegionEnabled(installed.regionId, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Leaf),
                    )
                    IconButton(onClick = { deleteRegion = installed.regionId }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.region_delete))
                    }
                }
            }
        }
    }
    deleteRegion?.let { id ->
        val pack = state.installed.firstOrNull { it.regionId == id }
        AlertDialog(
            onDismissRequest = { deleteRegion = null },
            title = { Text(stringResource(R.string.region_delete_title, pack?.name ?: id)) },
            text = { Text(stringResource(R.string.region_delete_body, mb(pack?.file?.length() ?: 0L))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteRegion = null
                    vm.deletePack(id)
                }) { Text(stringResource(R.string.region_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteRegion = null }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }
    if (removeDownload) {
        AlertDialog(
            onDismissRequest = { removeDownload = false },
            title = { Text(stringResource(R.string.brain_remove_download_title)) },
            text = { Text(stringResource(R.string.brain_remove_download_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removeDownload = false
                    vm.removeDownloadedModel()
                }) { Text(stringResource(R.string.brain_remove_download)) }
            },
            dismissButton = {
                TextButton(onClick = { removeDownload = false }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }
}

@Composable
private fun BrainCard(state: UiState, vm: AppViewModel, onRemoveDownload: () -> Unit) {
    val u = state.upgrade
    val downloading = u.progress != null
    val models = org.openwild.app.core.BrainList.offered(u.regionAvailable, u.boostInstalled, downloading)
    val bioclipOn = downloading || (state.brainModel == org.openwild.app.pack.PackManager.BRAIN_BIOCLIP && (u.active || (u.modelInstalled && u.boostInstalled)))
    val standardMb = (state.brainBytes / 1_000_000L).toInt().coerceAtLeast(1)
    val bioclipMb = ((u.modelMb.takeIf { it > 0 } ?: 309) + u.regionMb).coerceAtLeast(1)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.photo_id_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.brainOn,
                    onCheckedChange = vm::setBrainOn,
                    colors = SwitchDefaults.colors(checkedTrackColor = Leaf),
                )
            }
            Text(stringResource(R.string.brain_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            models.forEach { id ->
                val bioclip = id == org.openwild.app.core.BrainList.BIOCLIP
                val selected = if (bioclip) bioclipOn else !bioclipOn
                val status = when {
                    bioclip && downloading -> stringResource(R.string.brain_status_downloading, ((u.progress ?: 0f) * 100).toInt())
                    bioclip && u.modelInstalled && u.boostInstalled -> stringResource(R.string.brain_status_installed)
                    bioclip -> stringResource(R.string.brain_status_download)
                    else -> stringResource(R.string.brain_status_installed)
                }
                ModelRow(
                    selected = selected,
                    recommended = org.openwild.app.core.BrainList.isRecommended(id),
                    title = stringResource(if (bioclip) R.string.brain_bioclip else R.string.brain_standard),
                    detail = stringResource(if (bioclip) R.string.brain_bioclip_detail else R.string.brain_standard_detail),
                    size = stringResource(R.string.brain_size_mb, if (bioclip) bioclipMb else standardMb),
                    status = status,
                    onClick = { if (bioclip) vm.selectBioclip() else vm.selectStandardBrain() },
                )
            }
            if (downloading) {
                LinearProgressIndicator(progress = { u.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
            if (u.error != null) {
                Text(stringResource(R.string.brain_download_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.scan_detail), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                ScanChip(stringResource(R.string.scan_fast), selected = !state.scanCareful, Modifier.weight(1f)) { vm.setScanCareful(false) }
                ScanChip(stringResource(R.string.scan_careful), selected = state.scanCareful, Modifier.weight(1f)) { vm.setScanCareful(true) }
            }
            Text(
                stringResource(if (state.scanCareful) R.string.scan_careful_detail else R.string.scan_fast_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (u.modelInstalled) {
                TextButton(onClick = onRemoveDownload) { Text(stringResource(R.string.brain_remove_download)) }
            }
            PlantNetToggle(vm.plantNet)
        }
    }
}

@Composable
private fun ModelRow(
    selected: Boolean,
    recommended: Boolean,
    title: String,
    detail: String,
    size: String,
    status: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Leaf.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Leaf))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (recommended) {
                Text(
                    stringResource(R.string.brain_recommended),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Leaf,
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(size, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) Leaf else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Leaf else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = if (selected) ForestDeep else MaterialTheme.colorScheme.onSurface)
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

@Composable
private fun PhotoRow(title: String, items: List<Int>, pack: RegionPack, vm: AppViewModel, onSeeAll: () -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.see_all)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it }) { i ->
                // Browsing, not a photo ID: open at LOW tier so eating details stay hidden.
                SpeciesTile(pack, i, Modifier.width(140.dp)) { vm.go(Screen.Species(i, Tier.LOW)) }
            }
        }
    }
}
