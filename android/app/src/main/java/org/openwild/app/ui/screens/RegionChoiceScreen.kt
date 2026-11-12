package org.openwild.app.ui.screens

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.RegionInfo
import org.openwild.app.pack.PackManager
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.UiState

/** First launch: the user picks a region. Nothing is installed until they do. */
@Composable
fun RegionChoiceScreen(state: UiState, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val ready = state.bundled.filter { it.complete }.associateBy { it.regionId }
    val regions = state.regions.filter { r ->
        q.isEmpty() || r.name.lowercase().contains(q) || r.id.lowercase().contains(q)
    }
    val grouped = regions.groupBy { it.continent.ifBlank { "Other" } }.toSortedMap()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.choose_region_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        item {
            Text(stringResource(R.string.choose_region_body), style = MaterialTheme.typography.bodyMedium)
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
        state.suggestedRegionId?.let { id ->
            state.regions.firstOrNull { it.id == id }?.let { suggested ->
                if (q.isEmpty()) {
                    item {
                        Text(stringResource(R.string.near_you), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text(stringResource(R.string.near_you_install), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { ChoiceRow(suggested, ready[suggested.id], state.installingRegion == suggested.id, vm) }
                }
            }
        }
        grouped.forEach { (continent, list) ->
            item { Text(continent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(list, key = { it.id }) { region ->
                ChoiceRow(region, ready[region.id], state.installingRegion == region.id, vm)
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun ChoiceRow(region: RegionInfo, pack: PackManager.BundledPack?, installing: Boolean, vm: AppViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(region.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (pack != null) stringResource(R.string.status_ready, pack.version, pack.speciesCount) else stringResource(R.string.status_not_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            installing -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            pack != null -> Button(onClick = { vm.installRegion(region.id) }) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.install_pack))
            }
            else -> Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
