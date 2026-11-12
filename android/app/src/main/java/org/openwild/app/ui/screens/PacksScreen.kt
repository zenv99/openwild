package org.openwild.app.ui.screens

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
            val upd = remote[id]?.takeIf { it.version > p.version }
            return Status.Installed(p.version, id == activeId, upd)
        }
        state.bundled.firstOrNull { it.regionId == id && it.complete }?.let { return Status.Ready(it.version, it.speciesCount) }
        remote[id]?.let { return Status.Available(it) }
        return Status.NotBuilt
    }

    val q = query.trim().lowercase()
    val regions = state.regions.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
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
                is Status.Available -> stringResource(R.string.pack_size, mb(s.pack.size), s.pack.speciesCount)
                is Status.Downloading -> stringResource(R.string.status_downloading, (s.progress * 100).toInt())
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
            is Status.Available -> IconButton(onClick = { vm.download(s.pack) }) {
                Icon(Icons.Filled.CloudDownload, stringResource(R.string.download), tint = MaterialTheme.colorScheme.primary)
            }
            is Status.Downloading -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            Status.NotBuilt -> Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
