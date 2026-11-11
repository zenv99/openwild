package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionTitle
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox

private fun mb(bytes: Long) = if (bytes <= 0) "?" else "%.1f MB".format(bytes / 1_000_000.0)

@Composable
fun PacksScreen(state: UiState, vm: AppViewModel) {
    LaunchedEffect(Unit) { if (state.remote.isEmpty()) vm.refreshRemote() }
    val activeId = vm.pack?.regionId

    ScrollColumn {
        SectionTitle(stringResource(R.string.installed))
        if (state.installed.isEmpty()) Text(stringResource(R.string.no_pack))
        state.installed.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text("v${p.version} · ${mb(p.file.length())}", style = MaterialTheme.typography.bodySmall)
                        if (!p.compatible) Text(stringResource(R.string.incompatible), color = MaterialTheme.colorScheme.error)
                    }
                    if (p.regionId == activeId) {
                        Text(stringResource(R.string.in_use), color = MaterialTheme.colorScheme.primary)
                    } else if (p.compatible) {
                        TextButton(onClick = { vm.useRegion(p.regionId) }) { Text(stringResource(R.string.use_region)) }
                    }
                    TextButton(onClick = { vm.deletePack(p.regionId) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.available))
            OutlinedButton(onClick = { vm.refreshRemote() }) { Text(stringResource(R.string.refresh)) }
        }
        if (state.remoteError && state.remote.isEmpty()) WarningBox(stringResource(R.string.offline_list), danger = false)

        val installedVersions = state.installed.associate { it.regionId to it.version }
        // Suggested region first, then alphabetical.
        val remote = state.remote.sortedWith(compareBy({ it.name != state.suggestedRegion }, { it.name }))
        remote.forEach { p ->
            val progress = state.downloading[p.regionId]
            val have = installedVersions[p.regionId]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.pack_size, mb(p.size), p.speciesCount), style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            progress != null -> Unit
                            have == null -> Button(onClick = { vm.download(p) }) { Text(stringResource(R.string.download)) }
                            have < p.version -> Button(onClick = { vm.download(p) }) { Text(stringResource(R.string.update)) }
                            else -> Unit
                        }
                    }
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
