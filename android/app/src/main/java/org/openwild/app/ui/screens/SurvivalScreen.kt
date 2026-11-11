package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import org.openwild.app.ui.theme.Leaf
import org.openwild.app.ui.SpeciesThumb
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwild.app.R
import org.openwild.app.core.Tier
import org.openwild.app.pack.RegionPack
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.DangerBadge
import org.openwild.app.ui.Screen
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Danger

/** "Know before you go": the region's deadliest species and its high-value survival plants. */
@Composable
fun SurvivalScreen(vm: AppViewModel) {
    val pack = vm.pack ?: return
    var lists by remember { mutableStateOf<Pair<List<Int>, List<Int>>?>(null) }
    LaunchedEffect(pack.regionId) { lists = withContext(Dispatchers.IO) { pack.survivalGuide() } }
    val (valuable, deadly) = lists ?: return

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { WarningBox(stringResource(R.string.survival_rules), danger = false) }
        if (valuable.isEmpty() && deadly.isEmpty()) item { Text(stringResource(R.string.survival_empty)) }
        if (deadly.isNotEmpty()) {
            item { Header(stringResource(R.string.survival_deadly), danger = true) }
            items(deadly) { SpeciesRow(vm, pack, it) }
        }
        if (valuable.isNotEmpty()) {
            item { Header(stringResource(R.string.survival_valuable), danger = false) }
            items(valuable) { SpeciesRow(vm, pack, it) }
        }
    }
}

@Composable
private fun Header(text: String, danger: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (danger) Danger else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SpeciesRow(vm: AppViewModel, pack: RegionPack, i: Int) {
    val sp = pack.species
    // Browsing (not a photo ID): opened as LOW tier so eating details stay hidden.
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { vm.go(Screen.Species(i, Tier.LOW)) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesThumb(pack, i, if (pack.safety[i]?.isDeadly == true) Danger else Leaf)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(sp.commonNames[i] ?: sp.scientificNames[i], style = MaterialTheme.typography.titleSmall)
            Text(sp.scientificNames[i], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
        }
        DangerBadge(pack.safety[i])
    }
}
