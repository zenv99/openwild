package org.openwild.app.ui.screens

import androidx.compose.material.icons.filled.Eco
import org.openwild.app.ui.stageLabel
import org.openwild.app.ui.PhotoWanted
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import org.openwild.app.ui.SpeciesThumb
import org.openwild.app.ui.SpeciesPhotoHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.openwild.app.R
import org.openwild.app.ui.rememberFeedbackSender
import androidx.compose.material3.OutlinedButton
import org.openwild.app.core.SafetyPolicy
import org.openwild.app.core.SpeciesContent
import org.openwild.app.core.Tier
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Field
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Pill
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionCard
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.isDangerNotice
import org.openwild.app.ui.noticeText
import org.openwild.app.ui.theme.Caution
import org.openwild.app.ui.theme.CautionGradient
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg
import org.openwild.app.ui.theme.DangerGradient
import org.openwild.app.ui.theme.Leaf
import org.openwild.app.ui.theme.NeutralGradient
import org.openwild.app.ui.theme.Psychedelic
import org.openwild.app.ui.theme.SafeGradient
import org.openwild.app.ui.tierLabel

private fun headerBrush(c: SpeciesContent?): Brush = when {
    c == null || !c.hasSafetyData -> NeutralGradient
    c.isDeadly || c.toxicity == "serious" -> DangerGradient
    c.toxicity == "mild" || c.psychoactive || c.edibility == "edible_with_preparation" -> CautionGradient
    c.edibility == "edible" -> SafeGradient
    else -> NeutralGradient
}

/**
 * Species page. Order is fixed by SAFETY.md: deadly lookalikes, notices, harms, benefits, feeling.
 * [tier] is the tier for THIS species (results top tier, or LOW for other candidates / browsing).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeciesScreen(index: Int, tier: Tier, state: UiState, vm: AppViewModel) {
    val pack = vm.pack ?: return
    val sp = pack.species
    val content = remember(index) { pack.content(index) }
    val lookalikes = remember(index) { pack.lookalikes(index) }
    val (description, wiki) = remember(index) { pack.description(index) }
    val res = state.result
    val fromTopResult = res != null && res.result.top == index && res.result.tier == tier
    val vis = SafetyPolicy.visibility(
        tier = tier,
        content = content,
        isFungus = sp.isFungus[index],
        photoCount = if (fromTopResult) res!!.photoCount else 0,
        hasUndersidePhoto = fromTopResult && res!!.hasUnderside,
    )

    ScrollColumn {
        SpeciesPhotoHeader(pack, index, sp.commonNames[index] ?: sp.scientificNames[index]) { st -> vm.go(Screen.AddPhotos(index, st)) }

        // Header, coloured by danger level
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(headerBrush(content)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sp.commonNames[index] ?: sp.scientificNames[index], style = MaterialTheme.typography.headlineMedium, color = Color.White)
                if (sp.commonNames[index] != null) Text(sp.scientificNames[index], fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.85f))
                // Other names people use, the region's own languages first.
                val aka = remember(index) { pack.alsoCalled(index, localLanguages(pack.regionId)) }
                if (aka.isNotEmpty()) {
                    Text(
                        stringResource(R.string.also_called, aka.take(10).joinToString(" · ") { (lang, n) -> "$n (${languageName(lang)})" }),
                        color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val glass = Color.White.copy(alpha = 0.18f)
                    Pill(stringResource(if (sp.isFungus[index]) R.string.kind_fungus else R.string.kind_plant), Color.White, glass)
                    if (content != null && content.hasSafetyData) {
                        Pill(stringResource(R.string.toxicity, content.toxicity), Color.White, glass)
                        // Reported food use is not a recommendation to eat, and never a mushroom verdict.
                        if (!sp.isFungus[index] && content.edibility.startsWith("edible")) {
                            Pill(
                                stringResource(R.string.badge_food_reported),
                                Color.White,
                                glass,
                                contentDescription = stringResource(R.string.badge_food_reported_cd),
                                singleLine = true,
                            )
                        }
                    }
                    if (content?.psychoactive == true) Pill(stringResource(R.string.badge_psychoactive), Color.White, Psychedelic)
                    if (fromTopResult) Pill(tierLabel(tier) + " · ${res!!.result.score}%", Color.White, glass)
                }
            }
        }

        // 1. Deadly / toxic lookalikes first.
        val dangerous = lookalikes.filter { it.danger == "deadly" || it.danger == "toxic" }
        if (vis.showLookalikes) {
            dangerous.forEach { lk ->
                val target = lk.speciesId?.let { pack.indexOfSpeciesId(it) }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(DangerBg)
                        .let { m -> if (target != null) m.clickable { vm.go(Screen.Species(target, Tier.LOW)) } else m }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (target != null) {
                        SpeciesThumb(pack, target, Danger, size = 56.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        stringResource(R.string.lookalike_line, lk.name, lk.danger.uppercase()),
                        color = org.openwild.app.ui.theme.OnDangerBg, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 2. Notices
        vis.notices.forEach { n -> WarningBox(noticeText(n), danger = isDangerNotice(n)) }

        if (state.survivalMode && content != null) SurvivalCard(content)

        // 4. If eaten: harms → benefits → feeling
        if (vis.showHarms) {
            SectionCard(stringResource(R.string.harms), Icons.Filled.Warning, Danger) {
                val h = content?.harms
                if (h == null) {
                    Text(stringResource(R.string.no_harm_info))
                } else {
                    if (h.toxicParts.isNotEmpty()) Field(stringResource(R.string.toxic_parts), h.toxicParts.joinToString(", "))
                    h.cookingMakesSafe?.let {
                        Text(stringResource(if (it) R.string.cooking_makes_safe else R.string.cooking_not_safe), fontWeight = FontWeight.SemiBold)
                    }
                    Field(stringResource(R.string.symptoms), h.symptoms)
                    Field(stringResource(R.string.onset), h.onset)
                    Field(stringResource(R.string.severity), h.severity)
                    Field(stringResource(R.string.what_to_do), h.whatToDo)
                }
            }
        }
        if (vis.showBenefits) content?.benefits?.let { b ->
            SectionCard(stringResource(R.string.benefits), Icons.Filled.Restaurant, Leaf) {
                Field(stringResource(R.string.preparation), b.preparationRequired)
                Field(stringResource(R.string.nutrition), b.nutrition)
                if (b.edibleParts.isNotEmpty()) Field(stringResource(R.string.edible_parts), b.edibleParts.joinToString(", "))
                if (b.traditionalUses.isNotEmpty()) {
                    Field(stringResource(R.string.traditional_uses), b.traditionalUses.joinToString("\n") { (t, src) -> "• $t ($src)" })
                }
            }
        }
        if (vis.showFeeling) content?.feeling?.let { f ->
            SectionCard(stringResource(R.string.feeling), Icons.Filled.Psychology, if (content?.psychoactive == true) Psychedelic else Caution) {
                Field(stringResource(R.string.taste_texture), f.tasteTexture)
                Field(stringResource(R.string.effects), f.effects)
                Field(stringResource(R.string.risks), f.risks)
            }
        }
        // Poisoning risks are safety information, so show them even when eating info is hidden.
        if (!vis.showFeeling && content != null && content.toxicity in setOf("serious", "deadly") && content.feeling?.risks != null) {
            SectionCard(stringResource(R.string.risks), Icons.Filled.Psychology, Danger) { Text(content.feeling?.risks ?: "") }
        }

        if (!state.survivalMode && content != null) SurvivalCard(content)

        val other = lookalikes - dangerous.toSet()
        if (vis.showLookalikes && other.isNotEmpty()) {
            SectionCard(stringResource(R.string.lookalikes), Icons.Filled.Forest, MaterialTheme.colorScheme.primary) {
                other.forEach { Text("• ${it.name} (${it.danger})") }
            }
        }

        if (description != null || !content?.sources.isNullOrEmpty()) {
            SectionCard(stringResource(R.string.about), Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant) {
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                wiki?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                content?.sources?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        // Parts identified: which parts are food and which are poison (below About). Poison always shows;
        // food only when the identification is confident enough to show eating information.
        if (content != null && !sp.isFungus[index] && (content.parts.isNotEmpty() || content.hasSafetyData)) {
            PartsCard(content, showFood = vis.showBenefits)
        }
        ReviewCard(pack.regionId, sp.scientificNames[index], vm)
        Footer()
    }
}

@Composable
private fun ReviewCard(regionId: String, scientificName: String, vm: AppViewModel) {
    var rating by remember(regionId, scientificName) { mutableIntStateOf(0) }
    var body by remember(regionId, scientificName) { mutableStateOf("") }
    var ready by remember(regionId, scientificName) { mutableStateOf(false) }
    LaunchedEffect(regionId, scientificName) {
        val saved = withContext(Dispatchers.IO) { vm.loadReview(regionId, scientificName) }
        if (saved != null) {
            rating = saved.rating
            body = saved.body
        }
        ready = true
    }
    SectionCard(stringResource(R.string.review_title), Icons.Filled.Star, MaterialTheme.colorScheme.primary) {
        Text(stringResource(R.string.review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (star in 1..5) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.review_star, star),
                    tint = if (star <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp).clickable { rating = star },
                )
            }
        }
        OutlinedTextField(
            value = body,
            onValueChange = { if (it.length <= 4000) body = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text(stringResource(R.string.review_text_label)) },
        )
        val feedback = rememberFeedbackSender()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready && rating in 1..5, onClick = { vm.saveReview(regionId, scientificName, rating, body) }) {
                Text(stringResource(R.string.review_save))
            }
            // Optional: share it with OpenWild (asks first). Tips, local names and corrections help everyone.
            if (feedback.enabled) {
                OutlinedButton(enabled = ready && (rating in 1..5 || body.isNotBlank()), onClick = {
                    vm.saveReview(regionId, scientificName, rating.coerceAtLeast(0), body)
                    feedback.send(
                        regionId, scientificName, "review",
                        org.json.JSONObject().put("rating", rating).put("text", body.take(4000)),
                        "${"★".repeat(rating)} " + body.take(120) + if (body.length > 120) "…" else "",
                    )
                }) { Text(stringResource(R.string.review_share)) }
            }
        }
    }
}

@Composable
private fun SurvivalCard(c: SpeciesContent) {
    if (c.survivalPriority == "neutral" && c.survivalUses.isEmpty()) return
    val tint = when (c.survivalPriority) {
        "high_value" -> Leaf
        "avoid" -> Danger
        else -> Caution
    }
    SectionCard(stringResource(R.string.survival), Icons.Filled.HealthAndSafety, tint) {
        Text(c.survivalPriority.replace('_', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
        if (c.survivalUses.isNotEmpty()) Text(stringResource(R.string.survival_uses, c.survivalUses.joinToString(", ")))
    }
}

/** Every plant asks for more photos: close-ups and missing growth stages. Louder when it has almost none. */
private val PartOrder = listOf("all", "seeds", "fruit", "flowers", "leaves", "stem", "bark", "roots", "sap")

@Composable
private fun partLabel(part: String): String = stringResource(
    when (part) {
        "all" -> R.string.pp_all
        "seeds" -> R.string.pp_seeds
        "fruit" -> R.string.pp_fruit
        "flowers" -> R.string.pp_flowers
        "leaves" -> R.string.pp_leaves
        "stem" -> R.string.pp_stem
        "bark" -> R.string.pp_bark
        "roots" -> R.string.pp_roots
        else -> R.string.pp_sap
    },
)

@Composable
private fun PartsCard(content: org.openwild.app.core.SpeciesContent, showFood: Boolean) {
    val rows = content.parts.sortedBy { PartOrder.indexOf(it.part) }
    SectionCard(stringResource(R.string.parts_title), Icons.Filled.Eco, if (content.isDeadly) Danger else Leaf) {
        rows.forEach { r ->
            val hidden = r.isFood && !showFood
            val (label, color) = when {
                hidden -> stringResource(R.string.part_status_hidden) to MaterialTheme.colorScheme.onSurfaceVariant
                r.status == "deadly" -> stringResource(R.string.part_status_deadly) to Danger
                r.status == "toxic" -> stringResource(R.string.part_status_toxic) to Danger
                r.status == "harmful" -> stringResource(R.string.part_status_harmful) to Caution
                r.status == "experts_only" -> stringResource(R.string.part_status_experts) to Caution
                r.status == "edible_cooked" -> stringResource(R.string.part_status_cooked) to Caution
                r.status == "caution" -> stringResource(R.string.part_status_caution) to Caution
                else -> stringResource(R.string.part_status_edible) to org.openwild.app.ui.theme.Safe
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(partLabel(r.part), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(110.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    if (!hidden && r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val listed = rows.map { it.part }.toSet()
        if ("all" !in listed && listed.size < 8) {
            val msg = when {
                rows.isEmpty() && content.isDeadly -> R.string.parts_none_deadly
                rows.isEmpty() -> R.string.parts_none
                content.isDeadly -> R.string.parts_other_deadly
                else -> R.string.parts_other_unknown
            }
            Text(
                stringResource(msg),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (content.isDeadly) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.parts_disclaimer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** The region's own languages, shown first among a plant's other names. */
internal fun localLanguages(regionId: String): List<String> {
    if (regionId == "ID-BA") return listOf("ban", "ind", "jav")
    return when (regionId.substringBefore('-')) {
        "ID" -> listOf("ind", "ban", "jav", "sun", "mad", "min")
        "MY", "BN" -> listOf("msa")
        "SG" -> listOf("msa", "zho", "tam")
        "PH" -> listOf("tgl", "ceb")
        "TL" -> listOf("tet", "ind")
        "TH" -> listOf("tha")
        "VN" -> listOf("vie")
        "KH" -> listOf("khm")
        "LA" -> listOf("lao")
        "MM" -> listOf("mya")
        "CN", "TW" -> listOf("zho")
        "JP" -> listOf("jpn")
        "KR" -> listOf("kor")
        "IN" -> listOf("hin", "tam", "ben")
        "NP" -> listOf("nep")
        "LK" -> listOf("sin", "tam")
        "BD" -> listOf("ben")
        "NZ" -> listOf("mri")
        else -> emptyList()
    } + listOf("eng")
}

/** "ban" -> "Balinese", in the phone's language; the code itself if Android doesn't know it. */
internal fun languageName(code: String): String =
    java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault()).takeIf { it.isNotBlank() && it != code } ?: code
