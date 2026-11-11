package org.openwild.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.SafetyPolicy
import org.openwild.app.core.SpeciesContent
import org.openwild.app.core.Tier
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Footer
import org.openwild.app.ui.Screen
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionTitle
import org.openwild.app.ui.UiState
import org.openwild.app.ui.WarningBox
import org.openwild.app.ui.isDangerNotice
import org.openwild.app.ui.noticeText
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg

/**
 * Species page. Order is fixed by SAFETY.md: deadly lookalikes, notices, harms, benefits, feeling.
 * [tier] is the tier for THIS species (results top tier, or LOW for other candidates / guide browsing).
 */
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
        Text(sp.commonNames[index] ?: sp.scientificNames[index], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(sp.scientificNames[index], fontStyle = FontStyle.Italic)
        if (fromTopResult) Text(tierLabel(tier) + " · " + stringResource(R.string.confidence, res!!.result.score), color = tierColor(tier))

        // 1. Deadly / toxic lookalikes first.
        val dangerous = lookalikes.filter { it.danger == "deadly" || it.danger == "toxic" }
        if (vis.showLookalikes && dangerous.isNotEmpty()) {
            dangerous.forEach { lk ->
                val target = lk.speciesId?.let { pack.indexOfSpeciesId(it) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = DangerBg),
                    modifier = Modifier.fillMaxWidth().let { m ->
                        if (target != null) m.clickable { vm.go(Screen.Species(target, Tier.LOW)) } else m
                    },
                ) {
                    Text(
                        "${stringResource(R.string.lookalikes)}: ${lk.name} — ${lk.danger.uppercase()}",
                        color = Danger, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // 2. Notices (legal warning, confidence, mushrooms, review status).
        vis.notices.forEach { n -> WarningBox(noticeText(n), danger = isDangerNotice(n)) }

        if (content != null && content.hasSafetyData) {
            Text(stringResource(R.string.toxicity, content.toxicity) + " · " + stringResource(R.string.edibility, content.edibility.replace('_', ' ')))
        }

        // Survival mode puts survival value near the top.
        if (state.survivalMode && content != null) SurvivalBlock(content)

        // 3. If eaten: harms → benefits → feeling.
        if (vis.showHarms) {
            SectionTitle(stringResource(R.string.if_eaten))
            HarmsBlock(content?.harms)
        }
        if (vis.showBenefits) content?.benefits?.let { BenefitsBlock(it) }
        if (vis.showFeeling) content?.feeling?.let { FeelingBlock(it) }
        // Toxic species: symptoms of poisoning ("feeling") are safety info, so show them even when eating info is hidden.
        if (!vis.showFeeling && content != null && content.toxicity in setOf("serious", "deadly") && content.feeling?.risks != null) {
            Field(stringResource(R.string.risks), content.feeling?.risks)
        }

        if (!state.survivalMode && content != null && content.survivalPriority != "neutral") SurvivalBlock(content)

        // Harmless/edible lookalikes after the safety sections.
        val other = lookalikes - dangerous.toSet()
        if (vis.showLookalikes && other.isNotEmpty()) {
            SectionTitle(stringResource(R.string.lookalikes))
            other.forEach { Text("• ${it.name} (${it.danger})") }
        }

        if (description != null) {
            HorizontalDivider()
            SectionTitle(stringResource(R.string.about))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            wiki?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        if (!content?.sources.isNullOrEmpty()) {
            SectionTitle(stringResource(R.string.sources))
            content!!.sources.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Footer()
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}

@Composable
private fun HarmsBlock(h: SpeciesContent.Harms?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.harms), style = MaterialTheme.typography.titleSmall, color = Danger, fontWeight = FontWeight.Bold)
            if (h == null) {
                Text(stringResource(R.string.no_harm_info))
                return@Column
            }
            if (h.toxicParts.isNotEmpty()) Field(stringResource(R.string.toxic_parts), h.toxicParts.joinToString(", "))
            h.cookingMakesSafe?.let { Text(stringResource(if (it) R.string.cooking_makes_safe else R.string.cooking_not_safe), fontWeight = FontWeight.SemiBold) }
            Field(stringResource(R.string.symptoms), h.symptoms)
            Field(stringResource(R.string.onset), h.onset)
            Field(stringResource(R.string.severity), h.severity)
            Field(stringResource(R.string.what_to_do), h.whatToDo)
        }
    }
}

@Composable
private fun BenefitsBlock(b: SpeciesContent.Benefits) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.benefits), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Field(stringResource(R.string.preparation), b.preparationRequired)
            Field(stringResource(R.string.nutrition), b.nutrition)
            if (b.edibleParts.isNotEmpty()) Field(stringResource(R.string.edible_parts), b.edibleParts.joinToString(", "))
            if (b.traditionalUses.isNotEmpty()) {
                Field(stringResource(R.string.traditional_uses), b.traditionalUses.joinToString("\n") { (t, src) -> "• $t ($src)" })
            }
        }
    }
}

@Composable
private fun FeelingBlock(f: SpeciesContent.Feeling) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.feeling), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Field(stringResource(R.string.taste_texture), f.tasteTexture)
            Field(stringResource(R.string.effects), f.effects)
            Field(stringResource(R.string.risks), f.risks)
        }
    }
}

@Composable
private fun SurvivalBlock(c: SpeciesContent) {
    if (c.survivalPriority == "neutral" && c.survivalUses.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.survival) + ": " + c.survivalPriority.replace('_', ' '), fontWeight = FontWeight.Bold)
            if (c.survivalUses.isNotEmpty()) Text(stringResource(R.string.survival_uses, c.survivalUses.joinToString(", ")))
        }
    }
}
