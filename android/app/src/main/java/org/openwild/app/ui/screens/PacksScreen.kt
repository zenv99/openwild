package org.openwild.app.ui.screens

import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.BuildConfig
import org.openwild.app.R
import org.openwild.app.core.RegionInfo
import org.openwild.app.pack.PackManager
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Leaf

private fun mb(bytes: Long) = if (bytes <= 0) "—" else "%.1f MB".format(bytes / 1_000_000.0)

    private sealed interface Status {
    data class Installed(val version: Int, val active: Boolean, val update: PackManager.RemotePack?) : Status
    data class Available(val pack: PackManager.RemotePack) : Status
    data class Ready(val version: Int, val speciesCount: Int) : Status
    data object Installing : Status
    data class Downloading(val progress: Float) : Status
    data object NotBuilt : Status
}

/**
 * Region downloads, Organic Maps style: near-you first, then continents you can expand,
 * each region showing its size and status. Everything downloaded stays on the device.
 */
@Composable
fun PacksScreen(state: UiState, vm: AppViewModel) {
    LaunchedEffect(Unit) { if (BuildConfig.ALLOW_NETWORK && state.remote.isEmpty()) vm.refreshRemote() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPack(uri)
    }
    var query by rememberSaveable { mutableStateOf("") }
    val activeId = vm.pack?.regionId
    val installed = state.installed.associateBy { it.regionId }
    val remote = state.remote.associateBy { it.regionId }

    fun status(id: String): Status {
        if (state.installingRegion == id) return Status.Installing
        state.downloading[id]?.let { return Status.Downloading(it) }
        installed[id]?.let { p ->
            val upd = remote[id]?.let { PackManager.updateFor(p, it) }
            return Status.Installed(p.version, id == activeId, upd)
        }
        state.bundled.firstOrNull { it.regionId == id && it.complete }?.let { return Status.Ready(it.version, it.speciesCount) }
        remote[id]?.let { return Status.Available(it) }
        return Status.NotBuilt
    }

    val q = query.trim().lowercase()
    val regions = state.regions.filter { !it.hidden && (q.isEmpty() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q)) }
    val grouped = regions.groupBy { it.continent.ifBlank { "Other" } }.toSortedMap()
    val totalBytes = state.installed.sumOf { it.file.length() }
    val updates = state.installed.count { p -> remote[p.regionId]?.let { it.version > p.version } == true }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.on_device), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.on_device_summary, state.installed.size, mb(totalBytes)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.private_note), style = MaterialTheme.typography.bodySmall)
                    }
                    if (updates > 0) {
                        Button(onClick = vm::updateAll) { Text(stringResource(R.string.update_all, updates)) }
                    }
                    if (!BuildConfig.ALLOW_NETWORK) {
                        Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_region)) }
                    }
                }
            }
        }
        item { InstalledCard(state, vm) }
        if (BuildConfig.ALLOW_NETWORK) item { BetterRecognitionCard(state, vm) }
        if (BuildConfig.ALLOW_NETWORK && state.remoteError && state.remote.isEmpty()) {
            item { WarningBox(stringResource(R.string.offline_list), danger = false) }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_regions)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.suggestedRegionId == null && q.isEmpty()) {
            item { org.openwild.app.ui.UseLocationButton(vm) }
        }
        // Near you
        state.suggestedRegionId?.let { id ->
            state.regions.firstOrNull { it.id == id }?.let { r ->
                if (q.isEmpty()) {
                    item { Header(stringResource(R.string.near_you)) }
                    item { RegionRow(r, status(r.id), vm, near = true) }
                }
            }
        }

        grouped.forEach { (continent, list) ->
            item(key = "h-$continent") {
                ContinentHeader(continent, list.size, expandedByDefault = q.isNotEmpty())
            }
            items(list, key = { it.id }) { r ->
                val open = expandedContinents[continent] ?: q.isNotEmpty()
                AnimatedVisibility(visible = open) { RegionRow(r, status(r.id), vm, near = false) }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

/** "Packs" version numbers are dates (2026111711 = 17 Nov 2026, build 11). */
private fun versionDate(v: Int): String {
    val t = v.toString()
    if (t.length < 8) return "v$v"
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val m = t.substring(4, 6).toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return "v$v"
    return "${t.substring(6, 8).trimStart('0')} $m ${t.substring(0, 4)}"
}

/** Everything downloaded onto this phone, what it is, how big, and a delete button for each. */
@Composable
private fun InstalledCard(state: UiState, vm: AppViewModel) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val remote = state.remote.associateBy { it.regionId }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.installed_title), style = MaterialTheme.typography.titleMedium)
            if (state.installed.isEmpty() && vm.upgradeFiles().isEmpty()) {
                Text(stringResource(R.string.installed_none), style = MaterialTheme.typography.bodySmall)
            }
            state.installed.forEach { p ->
                val upd = remote[p.regionId]?.let { PackManager.updateFor(p, it) }
                val kind = stringResource(if (p.light) R.string.installed_essentials else R.string.installed_full)
                InstalledRow(
                    title = p.name + if (p.regionId == state.activeRegionId) " · " + stringResource(R.string.installed_in_use) else "",
                    sub = "$kind · ${versionDate(p.version)} · ${mb(p.file.length())}" +
                        (upd?.let { " · " + stringResource(R.string.installed_update, mb(it.size)) } ?: ""),
                    onUpdate = upd?.let { u -> { vm.download(u) } },
                    onDelete = { confirm = p.name to { vm.deletePack(p.regionId) } },
                )
            }
            val files = vm.upgradeFiles()
            if (files.isNotEmpty()) {
                files.forEach { f ->
                    val label = if (f.name.endsWith(".tflite")) stringResource(R.string.installed_model)
                    else stringResource(R.string.installed_boost, state.regions.firstOrNull { f.name.startsWith(it.id + ".") }?.name ?: f.name.substringBefore("."))
                    InstalledRow(title = label, sub = mb(f.length()), onUpdate = null,
                        onDelete = { confirm = label to { vm.deleteUpgradeFile(f) } })
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_reminders), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.update_reminders_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.updateReminders, onCheckedChange = vm::setUpdateReminders)
            }
        }
    }
    confirm?.let { (what, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.installed_delete_q, what)) },
            text = { Text(stringResource(R.string.installed_delete_body)) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InstalledRow(title: String, sub: String, onUpdate: (() -> Unit)?, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onUpdate != null) IconButton(onClick = onUpdate) { Icon(Icons.Filled.SystemUpdate, stringResource(R.string.update)) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
    }
}

/** Expanded/collapsed state per continent, shared between header and rows. */
private val expandedContinents = mutableStateMapOf<String, Boolean>()

@Composable
private fun ContinentHeader(name: String, count: Int, expandedByDefault: Boolean) {
    val open = expandedContinents[name] ?: expandedByDefault
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .clickable { expandedContinents[name] = !open }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RegionRow(r: RegionInfo, s: Status, vm: AppViewModel, near: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = s is Status.Installed && !s.active) { vm.useRegion(r.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (near) {
            Icon(Icons.Filled.LocationOn, null, tint = Leaf)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val sub = when (s) {
                is Status.Installed -> stringResource(if (s.active) R.string.status_in_use else R.string.status_downloaded, s.version)
                is Status.Available -> stringResource(R.string.pack_size, mb(s.pack.first.size), s.pack.speciesCount)
                is Status.Downloading -> stringResource(R.string.status_downloading_named, r.name, (s.progress * 100).toInt())
                is Status.Ready -> stringResource(R.string.status_ready, s.version, s.speciesCount)
                Status.Installing -> stringResource(R.string.installing_pack)
                Status.NotBuilt -> stringResource(R.string.status_not_ready)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (s) {
            is Status.Installed -> {
                if (s.update != null) {
                    IconButton(onClick = { vm.download(s.update) }) { Icon(Icons.Filled.SystemUpdate, stringResource(R.string.update)) }
                } else if (s.active) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Leaf)
                }
                IconButton(onClick = { vm.deletePack(r.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            }
            is Status.Ready -> Button(onClick = { vm.installRegion(r.id) }) { Text(stringResource(R.string.install_pack)) }
            Status.Installing -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            is Status.Available -> IconButton(onClick = { vm.download(s.pack.first) }) {
                Icon(Icons.Filled.CloudDownload, stringResource(R.string.download), tint = MaterialTheme.colorScheme.primary)
            }
            is Status.Downloading -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            Status.NotBuilt -> Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}


/**
 * Optional "Better recognition" (BioCLIP 2): much more accurate, but a large download. Recommended, with the
 * space it needs stated up front. Everything comes from GitHub; nothing is sent.
 */
@Composable
private fun BetterRecognitionCard(state: UiState, vm: AppViewModel) {
    val u = state.upgrade
    androidx.compose.runtime.LaunchedEffect(state.activeRegionId, state.activePackVersion) { vm.refreshUpgrade() }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.upgrade_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (u.active) R.string.upgrade_badge_on else R.string.upgrade_badge_recommended),
                    color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Text(stringResource(R.string.upgrade_body), style = MaterialTheme.typography.bodyMedium)
            if (!u.modelInstalled) {
                Text(
                    stringResource(R.string.upgrade_space, (u.modelMb.takeIf { it > 0 } ?: 350) + u.regionMb),
                    style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = org.openwild.app.ui.theme.Caution,
                )
            }
            u.error?.let { Text(stringResource(R.string.upgrade_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val p = u.progress
            when {
                p != null -> {
                    Text(stringResource(R.string.upgrade_downloading, state.activeRegionName ?: "", (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                !u.modelInstalled -> Button(onClick = vm::installUpgrade) {
                    Text(state.activeRegionName?.let { stringResource(R.string.upgrade_install, it) } ?: stringResource(R.string.upgrade_install_plain))
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.upgrade_switch), modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = u.on, onCheckedChange = { vm.setBetterRecognition(it) })
                    }
                    if (u.on && !u.active) {
                        if (u.regionAvailable) {
                            Text(stringResource(R.string.upgrade_region_needed), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = vm::installUpgrade) { Text(stringResource(R.string.upgrade_region_get, state.activeRegionName ?: "", u.regionMb.coerceAtLeast(1))) }
                        } else {
                            Text(stringResource(R.string.upgrade_region_soon), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = vm::removeUpgrade) { Text(stringResource(R.string.upgrade_remove)) }
                }
            }
        }
    }
}
