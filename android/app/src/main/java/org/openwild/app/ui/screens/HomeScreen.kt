package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    ScrollColumn {
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleLarge)

        if (state.modelMissing) WarningBox(stringResource(R.string.model_missing), danger = false)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.activeRegionName != null) {
                    Text(stringResource(R.string.active_region, state.activeRegionName, state.activeSpeciesCount))
                } else {
                    Text(stringResource(R.string.no_pack))
                }
                state.suggestedRegion?.let {
                    Text(stringResource(R.string.suggested_region, it), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { vm.refreshRemote(); vm.go(Screen.Packs) }) {
                    Text(stringResource(R.string.region_packs))
                }
            }
        }

        Button(
            onClick = vm::startCapture,
            enabled = state.activeRegionName != null && !state.modelMissing,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text(stringResource(R.string.identify), style = MaterialTheme.typography.titleMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.survival_mode), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.survival_mode_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.survivalMode, onCheckedChange = vm::setSurvivalMode)
            }
        }

        OutlinedButton(
            onClick = { vm.go(Screen.Survival) },
            enabled = state.activeRegionName != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.survival_guide)) }

        OutlinedButton(
            onClick = { vm.go(Screen.Credits) },
            enabled = state.activeRegionName != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.credits)) }

        Footer()
    }
}
