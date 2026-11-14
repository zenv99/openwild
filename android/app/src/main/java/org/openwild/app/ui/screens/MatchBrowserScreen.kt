package org.openwild.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.openwild.app.R
import org.openwild.app.core.Tier
import org.openwild.app.data.ReviewStore
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.CandidatePhotos
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.DangerBadge
import org.openwild.app.ui.Screen
import org.openwild.app.ui.SpeciesThumb
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.Leaf
import kotlin.math.roundToInt

/**
 * "Is it this one?" Swipe through the ranked matches for your photo, most likely first, comparing your
 * photo with each candidate's photos by growth stage. Tap "This is it" when you find it, or search every
 * species in the region. The choice is saved on the phone; sending it is a separate, optional tap.
 *
 * Safety: choosing a plant here never unlocks eating details. Species pages opened from here stay LOW tier.
 */
@Composable
fun MatchBrowserScreen(start: Int, state: UiState, vm: AppViewModel) {
    val res = state.result ?: return
    val pack = vm.pack ?: return
    val sp = pack.species
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cands = res.candidates
    val pages = cands.size + 1 // last page: "Not in the list? Search"
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pages - 1)) { pages }
    var saved by remember { mutableStateOf<ReviewStore.Correction?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Your photo, always visible for comparison.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            state.photos.firstOrNull()?.let { p ->
                Image(
                    p.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.your_photo),
                    contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.matches_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (pager.currentPage < cands.size) stringResource(R.string.matches_position, pager.currentPage + 1, cands.size)
                    else stringResource(R.string.matches_search_page),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        saved?.let { c ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.correction_saved_detail, c.chosen), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { Contribute.sendCorrection(context, c); vm.markCorrectionSent(c.id) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.correction_send))
                }
            }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp) { page ->
            if (page < cands.size) {
                val c = cands[page]
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val name = sp.commonNames[c.index] ?: sp.scientificNames[c.index]
                    CandidatePhotos(pack, c.index, name, Modifier.fillMaxWidth().weight(1f))
                    if (c.isDeadly) WarningBox(stringResource(R.string.matches_deadly_hint))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${page + 1}  $name", style = MaterialTheme.typography.titleLarge)
                            Text(sp.scientificNames[c.index], fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DangerBadge(pack.safety[c.index], fungus = sp.isFungus[c.index])
                        }
                        Text(
                            "${(c.prob * 100).roundToInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (c.isDeadly) Danger else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saved = vm.recordCorrection(c.index, page + 1) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.matches_this_is_it))
                        }
                        OutlinedButton(onClick = { vm.go(Screen.Species(c.index, Tier.LOW)) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.matches_details))
                        }
                    }
                    if (page + 1 < pages) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.matches_next))
                        }
                    }
                }
            } else {
                SearchAllSpecies(vm) { i -> saved = vm.recordCorrection(i, 0) }
            }
        }
    }
}

/** Last page: not among the matches? Find it by name among every species in the region. */
@Composable
private fun SearchAllSpecies(vm: AppViewModel, onPick: (Int) -> Unit) {
    val pack = vm.pack ?: return
    val sp = pack.species
    var q by remember { mutableStateOf("") }
    val hits = remember(q, pack) {
        val needle = q.trim().lowercase()
        if (needle.length < 2) emptyList()
        else sp.scientificNames.indices.filter { i ->
            sp.scientificNames[i].lowercase().contains(needle) || (sp.commonNames[i]?.lowercase()?.contains(needle) == true)
        }.take(60)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.matches_not_listed), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.search_species, sp.size)) },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits, key = { it }) { i ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onPick(i) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpeciesThumb(pack, i, if (pack.safety[i]?.isDeadly == true) Danger else Leaf)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sp.commonNames[i] ?: sp.scientificNames[i], style = MaterialTheme.typography.titleSmall)
                        Text(sp.scientificNames[i], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.matches_pick), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
