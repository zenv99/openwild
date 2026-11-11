package org.openwild.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.Notice
import org.openwild.app.core.Tier
import org.openwild.app.pack.RegionPack
import org.openwild.app.ui.theme.Caution
import org.openwild.app.ui.theme.CautionBg
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg
import org.openwild.app.ui.theme.Psychedelic
import org.openwild.app.ui.theme.PsychedelicBg
import org.openwild.app.ui.theme.Safe
import org.openwild.app.ui.theme.SafeBg

@Composable
fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun WarningBox(text: String, danger: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (danger) DangerBg else CautionBg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = if (danger) Danger else Caution)
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (danger) Color(0xFF7F0000) else Color(0xFF4A3000), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** Danger badge for lists, from a pack's cached safety flags. */
@Composable
fun DangerBadge(flags: RegionPack.SafetyFlags?) {
    when {
        flags == null -> Unit
        flags.isDeadly -> Pill(stringResource(R.string.badge_deadly), Color.White, Danger)
        flags.psychoactive -> Pill(stringResource(R.string.badge_psychoactive), Psychedelic, PsychedelicBg)
        flags.toxicity == "serious" -> Pill(stringResource(R.string.badge_toxic), Danger, DangerBg)
        flags.toxicity == "mild" -> Pill(stringResource(R.string.badge_caution), Caution, CautionBg)
        flags.edibility.startsWith("edible") -> Pill(stringResource(R.string.badge_edible), Safe, SafeBg)
        else -> Unit
    }
}

@Composable
fun tierColor(t: Tier) = when (t) {
    Tier.HIGH -> Safe
    Tier.MEDIUM -> Caution
    else -> Danger
}

@Composable
fun tierLabel(t: Tier) = stringResource(
    when (t) {
        Tier.HIGH -> R.string.tier_high
        Tier.MEDIUM -> R.string.tier_medium
        Tier.LOW -> R.string.tier_low
        Tier.UNKNOWN -> R.string.tier_unknown
    },
)

/** Animated circular confidence gauge. */
@Composable
fun ConfidenceRing(percent: Int, color: Color, size: Dp = 88.dp, stroke: Dp = 9.dp) {
    val target = (percent.coerceIn(0, 100)) / 100f
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(900), label = "confidence")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            drawArc(track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = s)
            drawArc(color, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, style = s)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = color)
        }
    }
}

/** Card with a coloured title row, used for Harms / Benefits / Feeling / Survival sections. */
@Composable
fun SectionCard(title: String, icon: ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            }
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun noticeText(n: Notice): String = stringResource(
    when (n) {
        Notice.NOT_CONFIDENT_EATING -> R.string.notice_not_confident_eating
        Notice.NOT_CONFIDENT_DO_NOT_EAT -> R.string.notice_not_confident
        Notice.NOT_IN_LIBRARY -> R.string.notice_not_in_library
        Notice.NO_SAFETY_DATA -> R.string.notice_no_safety_data
        Notice.NEED_UNDERSIDE_PHOTO -> R.string.notice_need_underside
        Notice.MUSHROOM_POOR_SURVIVAL_FOOD -> R.string.notice_mushroom_survival
        Notice.LEGAL_WARNING -> R.string.legal_warning
        Notice.NOT_EXPERT_REVIEWED -> R.string.notice_not_reviewed
    },
)

fun isDangerNotice(n: Notice) = n != Notice.MUSHROOM_POOR_SURVIVAL_FOOD && n != Notice.NOT_EXPERT_REVIEWED

@Composable
fun Footer() {
    Text(
        stringResource(R.string.footer),
        style = MaterialTheme.typography.labelLarge,
        color = Danger,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
