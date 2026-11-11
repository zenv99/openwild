package org.openwild.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
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
        SpeciesPhotoHeader(pack, index, sp.commonNames[index] ?: sp.scientificNames[index])

        // Header, coloured by danger level
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(headerBrush(content)).padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sp.commonNames[index] ?: sp.scientificNames[index], style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(sp.scientificNames[index], fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.85f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val glass = Color.White.copy(alpha = 0.18f)
                    Pill(stringResource(if (sp.isFungus[index]) R.string.kind_fungus else R.string.kind_plant), Color.White, glass)
                    if (content != null && content.hasSafetyData) {
                        Pill(stringResource(R.string.toxicity, content.toxicity), Color.White, glass)
                        Pill(content.edibility.replace('_', ' '), Color.White, glass)
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
                ) {
                    Text(
                        stringResource(R.string.lookalike_line, lk.name, lk.danger.uppercase()),
                        color = Danger, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 2. Notices
        vis.notices.forEach { n -> WarningBox(noticeText(n), danger = isDangerNotice(n)) }

        if (state.survivalMode && content != null) SurvivalCard(content)

        // 3. If eaten: harms → benefits → feeling
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
        Footer()
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
