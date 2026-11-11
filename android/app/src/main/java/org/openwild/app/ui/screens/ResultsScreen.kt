package org.openwild.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.Tier
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Candidate
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionTitle
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Caution
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg
import org.openwild.app.ui.theme.Safe
import kotlin.math.roundToInt

@Composable
fun tierLabel(t: Tier) = stringResource(
    when (t) {
        Tier.HIGH -> R.string.tier_high
        Tier.MEDIUM -> R.string.tier_medium
        Tier.LOW -> R.string.tier_low
        Tier.UNKNOWN -> R.string.tier_unknown
    },
)

@Composable
fun ResultsScreen(state: UiState, vm: AppViewModel) {
    val res = state.result ?: return
    if (vm.pack == null) return
    val tier = res.result.tier

    ScrollColumn {
        // Red banner for any plausible deadly candidate (SAFETY.md §3), above everything else.
        res.deadly.forEach { WarningBox(stringResource(R.string.deadly_banner, displayName(vm, it))) }

        if (tier == Tier.UNKNOWN) {
            WarningBox(stringResource(R.string.notice_not_in_library))
        }

        val top = res.candidates.first()
        Text(tierLabel(tier), color = tierColor(tier), fontWeight = FontWeight.Bold)
        CandidateCard(vm, top, highlight = true) { vm.go(Screen.Species(top.index, tier)) }
        if (tier == Tier.LOW) WarningBox(stringResource(R.string.notice_not_confident))

        if (res.candidates.size > 1) {
            SectionTitle(stringResource(R.string.other_candidates))
            res.candidates.drop(1).forEach { c ->
                // Non-top candidates are always treated as LOW for safety gating.
                CandidateCard(vm, c, highlight = false) { vm.go(Screen.Species(c.index, Tier.LOW)) }
            }
        }

        if (tier != Tier.HIGH && state.photos.size < AppViewModel.MAX_PHOTOS) {
            OutlinedButton(onClick = vm::addMorePhotos, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_photo_more_confidence))
            }
        }
        Button(onClick = { vm.back(); vm.back(); vm.startCapture() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.new_search))
        }
        Footer()
    }
}

fun displayName(vm: AppViewModel, i: Int): String {
    val sp = vm.pack?.species ?: return ""
    return sp.commonNames[i]?.let { "$it (${sp.scientificNames[i]})" } ?: sp.scientificNames[i]
}

@Composable
fun tierColor(t: Tier) = when (t) {
    Tier.HIGH -> Safe
    Tier.MEDIUM -> Caution
    else -> Danger
}

@Composable
private fun CandidateCard(vm: AppViewModel, c: Candidate, highlight: Boolean, onClick: () -> Unit) {
    val sp = vm.pack!!.species
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (c.isDeadly) CardDefaults.cardColors(containerColor = DangerBg) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sp.commonNames[c.index] ?: sp.scientificNames[c.index],
                        style = if (highlight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(sp.scientificNames[c.index], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.confidence, (c.prob * 100).roundToInt()),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LinearProgressIndicator(
                progress = { c.prob.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (c.isDeadly) Danger else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
