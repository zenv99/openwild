package org.openwild.app.ui.screens

import org.openwild.app.ui.SpeciesThumb
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.IconButton
import org.openwild.app.ui.SpeciesTile
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwild.app.R
import org.openwild.app.core.Tier
import org.openwild.app.pack.RegionPack.ExploreFilter
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.DangerBadge
import org.openwild.app.ui.Screen
import org.openwild.app.ui.UiState
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.Leaf

/** Offline field guide: search and filter every species in the active region pack. */
@Composable
fun ExploreScreen(state: UiState, vm: AppViewModel) {
    val pack = vm.pack
    if (pack == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_pack))
        }
        return
    }
    var grid by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(pack.regionId, pack.version, state.exploreQuery, state.exploreFilter, grid) {
        results = withContext(Dispatchers.Default) {
            // Warm the safety table off the UI thread before rows and tiles read it.
            runCatching { pack.safety }
            val found = runCatching { pack.search(state.exploreQuery, state.exploreFilter) }.getOrDefault(emptyList())
            if (grid && pack.hasPhotos) {
                runCatching { found.sortedByDescending { it in pack.withPhoto } }.getOrDefault(found)
            } else {
                found
            }
        }
    }
    val filters = listOf(
        ExploreFilter.ALL to R.string.filter_all,
        ExploreFilter.DEADLY to R.string.filter_deadly,
        ExploreFilter.SURVIVAL to R.string.filter_survival,
        ExploreFilter.PLANTS to R.string.kind_plant,
        ExploreFilter.FUNGI to R.string.kind_fungus,
        ExploreFilter.PSYCHOACTIVE to R.string.badge_psychoactive,
    )

    val showGrid = grid && pack.hasPhotos
    val full = GridItemSpan(Int.MAX_VALUE)

    LazyVerticalGrid(
        columns = if (showGrid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { full }) {
            OutlinedTextField(
                value = state.exploreQuery,
                onValueChange = vm::setExploreQuery,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.search_species, pack.species.size)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item(span = { full }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { (f, label) ->
                    FilterChip(selected = state.exploreFilter == f, onClick = { vm.setExploreFilter(f) }, label = { Text(stringResource(label)) })
                }
            }
        }
        item(span = { full }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.results_count, results.size, pack.regionName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (pack.hasPhotos) {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            if (showGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = stringResource(if (showGrid) R.string.view_list else R.string.view_grid),
                        )
                    }
                }
            }
        }
        if (showGrid) {
            gridItems(results, key = { it }) { i ->
                SpeciesTile(pack, i) { vm.go(Screen.Species(i, Tier.LOW)) }
            }
        } else {
            gridItems(results, key = { it }) { i ->
                val flags = pack.safety[i]
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { vm.go(Screen.Species(i, Tier.LOW)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (flags?.isDeadly == true) Danger else Leaf
                    SpeciesThumb(pack, i, tint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pack.species.commonNames[i] ?: pack.species.scientificNames[i], style = MaterialTheme.typography.titleSmall)
                        Text(pack.species.scientificNames[i], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DangerBadge(flags)
                }
            }
        }
        item(span = { full }) { Spacer(Modifier.size(24.dp)) }
    }
}
