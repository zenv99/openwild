package org.openwild.app.ui.screens

import org.openwild.app.ui.theme.Leaf
import org.openwild.app.ui.SpeciesThumb
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.Tier
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Candidate
import org.openwild.app.ui.ConfidenceRing
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.DangerBadge
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Pill
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionTitle
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg
import org.openwild.app.ui.tierColor
import org.openwild.app.ui.tierLabel
import kotlin.math.roundToInt

@Composable
fun ResultsScreen(state: UiState, vm: AppViewModel) {
    val res = state.result ?: return
    val pack = vm.pack ?: return
    val context = LocalContext.current
    val tier = res.result.tier
    val top = res.candidates.first()
    val sp = pack.species

    ScrollColumn {
        // Red banner for any plausible deadly candidate (SAFETY.md §3), above everything else.
        res.deadly.forEach { WarningBox(stringResource(R.string.deadly_banner, displayName(vm, it))) }
        if (tier == Tier.UNKNOWN) WarningBox(stringResource(R.string.notice_not_in_library))

        Card(
            Modifier.fillMaxWidth().clickable { vm.go(Screen.Species(top.index, tier)) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column {
                state.photos.firstOrNull()?.let { p ->
                    Box {
                        Image(
                            bitmap = p.bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                        )
                        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                            Pill(tierLabel(tier), Color.White, tierColor(tier))
                        }
                    }
                }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sp.commonNames[top.index] ?: sp.scientificNames[top.index], style = MaterialTheme.typography.headlineSmall)
                        Text(sp.scientificNames[top.index], fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DangerBadge(pack.safety[top.index])
                        Text(
                            stringResource(R.string.based_on_photos, res.photoCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ConfidenceRing(res.result.score, tierColor(tier))
                }
            }
        }
        if (tier == Tier.LOW) WarningBox(stringResource(R.string.notice_not_confident))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tier != Tier.HIGH && state.photos.size < AppViewModel.MAX_PHOTOS) {
                OutlinedButton(onClick = vm::addMorePhotos, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_photo))
                }
            }
            OutlinedButton(onClick = { vm.back(); vm.back(); vm.startCapture() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.new_search))
            }
        }

        if (res.candidates.size > 1) {
            SectionTitle(stringResource(R.string.other_candidates))
            res.candidates.drop(1).forEach { c ->
                // Non-top candidates are always treated as LOW for safety gating.
                CandidateRow(vm, c) { vm.go(Screen.Species(c.index, Tier.LOW)) }
            }
        }

        // Privacy-first contribution: nothing leaves the phone unless the user shares it.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { Contribute.shareSighting(context, state, vm) }) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.share_sighting))
            }
            TextButton(onClick = { Contribute.reportWrongId(context, vm, res) }) {
                Icon(Icons.Filled.Flag, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.report_wrong))
            }
        }
        Footer()
    }
}

fun displayName(vm: AppViewModel, i: Int): String {
    val sp = vm.pack?.species ?: return ""
    return sp.commonNames[i]?.let { "$it (${sp.scientificNames[i]})" } ?: sp.scientificNames[i]
}

@Composable
private fun CandidateRow(vm: AppViewModel, c: Candidate, onClick: () -> Unit) {
    val pack = vm.pack ?: return
    val sp = pack.species
    val ink = if (c.isDeadly) Color(0xFF7F0000) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (c.isDeadly) DangerBg else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesThumb(pack, c.index, if (c.isDeadly) Danger else Leaf, size = 60.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sp.commonNames[c.index] ?: sp.scientificNames[c.index], style = MaterialTheme.typography.titleSmall, color = ink)
            Text(sp.scientificNames[c.index], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall, color = ink)
            LinearProgressIndicator(
                progress = { c.prob.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = if (c.isDeadly) Danger else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${(c.prob * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = if (c.isDeadly) Danger else ink)
            DangerBadge(pack.safety[c.index])
        }
    }
}
