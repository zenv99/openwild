package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.openwild.app.R
import org.openwild.app.core.CountryHint
import org.openwild.app.core.DownloadCaution
import org.openwild.app.core.RecommendedBundle
import org.openwild.app.core.RegionInfo
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.DownloadGate
import org.openwild.app.ui.UiState
import org.openwild.app.ui.UseLocationButton
import org.openwild.app.ui.theme.Caution
import org.openwild.app.ui.theme.Danger

/**
 * One primary download: the person's country plus World.
 * The country is what Explore shows. Every other pack stays in the list under this card.
 */
@Composable
fun RecommendedCard(state: UiState, vm: AppViewModel, gate: DownloadGate) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val country = state.regions.firstOrNull { it.id == state.suggestedRegionId && !it.hidden && it.id != CountryHint.WORLD_ID }
    val remote = state.remote.associateBy { it.regionId }
    val bundled = state.bundled.filter { it.complete }.map { it.regionId }.toSet()
    val installed = state.installed.associateBy { it.regionId }
    fun side(id: String) = RecommendedBundle.Side(
        bytes = remote[id]?.size ?: 0L,
        onPhone = installed[id] != null,
        light = installed[id]?.light == true,
        offered = remote[id] != null || id in bundled,
    )
    val plan = country?.let {
        RecommendedBundle.Plan(it.id, it.name, side(it.id), side(CountryHint.WORLD_ID))
    }
    val progress = country?.let { RecommendedBundle.activeProgress(it.id, state.downloading) }
    val installing = state.installingRegion == country?.id || state.installingRegion == CountryHint.WORLD_ID
    val busy = progress != null || installing
    val essentials = country != null && remote[country.id]?.essentials != null && plan?.fetchCountry == true

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.recommended_kicker),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                if (country == null) stringResource(R.string.recommended_title_pick)
                else stringResource(R.string.recommended_title, country.name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (country == null) stringResource(R.string.recommended_body_pick)
                else stringResource(R.string.recommended_body, country.name),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (plan != null) {
                val (kind, bytes) = RecommendedBundle.sizeOf(plan.country.bytes, plan.world.bytes)
                val size = when (kind) {
                    RecommendedBundle.SizeKind.TOTAL -> stringResource(R.string.recommended_size, DownloadCaution.format(bytes))
                    RecommendedBundle.SizeKind.AT_LEAST -> stringResource(R.string.recommended_size_least, DownloadCaution.format(bytes))
                    RecommendedBundle.SizeKind.UNKNOWN -> null
                }
                if (size != null) {
                    Text(size, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (plan.canStart && DownloadCaution.needsWarning(plan.transferBytes)) {
                    Text(stringResource(R.string.recommended_wifi), color = Caution, fontWeight = FontWeight.SemiBold)
                }
                if (essentials) {
                    Text(stringResource(R.string.recommended_full_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                stringResource(R.string.footer),
                style = MaterialTheme.typography.labelLarge,
                color = Danger,
                fontWeight = FontWeight.Bold,
            )
            when {
                busy && plan != null -> {
                    val label = when {
                        plan.countryId in state.downloading -> plan.countryName
                        CountryHint.WORLD_ID in state.downloading -> stringResource(R.string.world_pack)
                        else -> plan.countryName
                    }
                    val pct = ((progress ?: 0f) * 100).toInt()
                    Text(stringResource(R.string.status_downloading_named, label, pct), style = MaterialTheme.typography.bodySmall)
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                plan == null -> {
                    Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recommended_pick_country))
                    }
                }
                plan.complete && state.activeRegionId == plan.countryId -> {
                    Text(stringResource(R.string.recommended_done, plan.countryName), fontWeight = FontWeight.SemiBold)
                }
                plan.complete -> {
                    Button(onClick = { vm.showRecommended(plan.countryId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recommended_show, plan.countryName))
                    }
                }
                plan.canStart -> {
                    val both = plan.fetchCountry && plan.fetchWorld
                    Button(
                        onClick = { gate.ask(plan.transferBytes) { vm.downloadRecommended(plan.countryId) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (both) R.string.recommended_action else R.string.recommended_action_rest))
                    }
                }
                state.remoteLoading -> Text(stringResource(R.string.recommended_checking), style = MaterialTheme.typography.bodySmall)
                else -> Text(stringResource(R.string.recommended_missing, plan.countryName), style = MaterialTheme.typography.bodySmall)
            }
            if (country != null) {
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.recommended_not_country)) }
            }
            UseLocationButton(vm)
        }
    }
    if (picking) {
        CountryPickerDialog(
            regions = CountryHint.countries(state.regions),
            onPick = { vm.chooseCountry(it.id); picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CountryPickerDialog(regions: List<RegionInfo>, onPick: (RegionInfo) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = regions.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q) }
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).height(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recommended_pick_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_regions)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { region ->
                        TextButton(onClick = { onPick(region) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(region.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.size(8.dp))
                                Text(region.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
