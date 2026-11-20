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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.openwild.app.R
import org.openwild.app.ui.bringIntoViewOnFocus
import org.openwild.app.ui.ReportSender
import org.openwild.app.ui.rememberReportSender
import org.openwild.app.core.Tier
import org.openwild.app.data.ReviewStore
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.CandidatePhotos
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
    val scope = rememberCoroutineScope()
    val cands = res.candidates
    val pages = cands.size + 1 // last page: "Not in the list? Search"
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pages - 1)) { pages }
    var saved by remember { mutableStateOf<ReviewStore.Correction?>(null) }
    val chips = androidx.compose.foundation.lazy.rememberLazyListState()
    // The selected match always sits first on the left, fully visible.
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { chips.animateScrollToItem(pager.currentPage) }
    fun go(page: Int) = scope.launch { pager.animateScrollToPage(page.coerceIn(0, pages - 1)) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Your photo + where you are.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            state.photos.firstOrNull()?.let { p ->
                Image(
                    p.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.your_photo),
                    contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
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

        // Ranked names: tap to jump straight to any match. Scrolls on its own, outside the swipe area.
        androidx.compose.foundation.lazy.LazyRow(
            state = chips, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cands.size + 1) { k ->
                val selected = k == pager.currentPage
                val label = if (k < cands.size) {
                    val c = cands[k]
                    "${k + 1}. ${sp.commonNames[c.index] ?: sp.scientificNames[c.index]} · ${(c.prob * 100).roundToInt()}%"
                } else stringResource(R.string.matches_pick_search)
                val deadly = k < cands.size && cands[k].isDeadly
                Text(
                    label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else if (deadly) Danger else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { go(k) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        saved?.let { c ->
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.correction_saved_detail, c.chosen), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                val reports = rememberReportSender(onSent = { vm.markCorrectionSent(c.id) }, suggestions = vm::suggestNames)
                TextButton(onClick = {
                    val fields = org.openwild.app.ui.wrongIdFields(
                        res, pack.species.scientificNames, c.packVersion, c.modelId, "correction",
                    ).put("chosen_rank", c.chosenRank)
                    reports.send(ReportSender.Request(
                        regionId = c.regionId, predicted = c.predicted, chosen = c.chosen,
                        fields = fields,
                        photos = state.photos,
                    ))
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.correction_send))
                }
            }
        }

        HorizontalPager(
            pager, Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            pageSpacing = 16.dp, beyondViewportPageCount = 1,
        ) { page ->
            if (page < cands.size) {
                val c = cands[page]
                val name = sp.commonNames[c.index] ?: sp.scientificNames[c.index]
                Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CandidatePhotos(pack, c.index, name, Modifier.fillMaxWidth().weight(1f))
                    if (c.isDeadly) WarningBox(stringResource(R.string.matches_deadly_hint))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleLarge)
                            if (sp.commonNames[c.index] != null) {
                                Text(sp.scientificNames[c.index], fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DangerBadge(pack.safety[c.index], fungus = sp.isFungus[c.index])
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${(c.prob * 100).roundToInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                color = if (c.isDeadly) Danger else MaterialTheme.colorScheme.primary,
                            )
                            TextButton(onClick = { vm.go(Screen.Species(c.index, Tier.LOW)) }) { Text(stringResource(R.string.matches_details)) }
                        }
                    }
                }
            } else {
                SearchAllSpecies(vm) { i -> saved = vm.recordCorrection(i, 0) }
            }
        }

        // Fixed controls, always in the same place.
        Row(
            Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { go(pager.currentPage - 1) }, enabled = pager.currentPage > 0) {
                Text("‹ " + stringResource(R.string.matches_prev))
            }
            Button(
                onClick = {
                    val k = pager.currentPage
                    if (k < cands.size) saved = vm.recordCorrection(cands[k].index, k + 1)
                },
                enabled = pager.currentPage < cands.size, modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.matches_this_is_it))
            }
            OutlinedButton(onClick = { go(pager.currentPage + 1) }, enabled = pager.currentPage < pages - 1) {
                Text(stringResource(R.string.matches_next_short) + " ›")
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
            value = q, onValueChange = { q = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
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
                        if (sp.commonNames[i] != null) Text(sp.scientificNames[i], fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.matches_pick), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
