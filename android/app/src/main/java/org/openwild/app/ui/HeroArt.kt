package org.openwild.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Landing-page art (Zen V's vision): an abstract fly agaric — the mushroom of
 * "The Sacred Mushroom and the Cross" — whose white spots drift off the cap and
 * become a heap of abundant food, then return. Original drawing, pure Canvas, no assets.
 */
@Composable
fun MushroomMorph(modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "morph")
    val t by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val spin by loop.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "spin",
    )
    Canvas(modifier) {
        // 0 = mushroom, 1 = abundance. Hold, morph, hold, morph back.
        val p = when {
            t < 0.28f -> 0f
            t < 0.45f -> smooth((t - 0.28f) / 0.17f)
            t < 0.78f -> 1f
            t < 0.95f -> 1f - smooth((t - 0.78f) / 0.17f)
            else -> 0f
        }
        val breathe = 1f + 0.025f * sin(t * 2f * PI.toFloat() * 3f)
        drawAura(spin, p)
        drawMushroom(alpha = 1f - p, scale = breathe * (1f - 0.25f * p))
        drawBowl(alpha = p)
        drawLeaves(alpha = p, sway = sin(t * 2f * PI.toFloat() * 2f))
        drawSporesToFood(p, t)
    }
}

private fun smooth(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private val CapRed = Color(0xFFE53935)
private val CapDeep = Color(0xFF8E0000)
private val Cream = Color(0xFFFFF3E0)

/** Psychedelic rotating rings behind everything. */
private fun DrawScope.drawAura(spin: Float, p: Float) {
    val c = center
    val r = size.minDimension / 2f
    drawCircle(
        Brush.radialGradient(listOf(lerp(Color(0x66FF6E40), Color(0x66B7E36A), p), Color.Transparent), c, r),
        radius = r, center = c,
    )
    rotate(spin, c) {
        for (i in 0 until 4) {
            val rr = r * (0.55f + i * 0.12f)
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, lerp(Color(0xAAFFD54F), Color(0xAAB7E36A), p), Color.Transparent),
                    center = c,
                ),
                startAngle = i * 70f, sweepAngle = 200f, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(width = r * 0.018f),
            )
        }
    }
}

private fun DrawScope.drawMushroom(alpha: Float, scale: Float) {
    if (alpha <= 0.01f) return
    val w = size.minDimension * scale
    val cx = center.x
    val capY = center.y - w * 0.06f
    // Stem
    val stemW = w * 0.16f
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Cream, Color(0xFFD7CCC8)), startY = capY, endY = capY + w * 0.36f),
        topLeft = Offset(cx - stemW / 2, capY),
        size = Size(stemW, w * 0.36f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(stemW / 2, stemW / 2),
        alpha = alpha,
    )
    // Ring (annulus) on the stem
    drawOval(Cream, topLeft = Offset(cx - stemW * 0.85f, capY + w * 0.08f), size = Size(stemW * 1.7f, w * 0.05f), alpha = alpha)
    // Cap: a dome
    val capW = w * 0.62f
    val capH = w * 0.34f
    val cap = Path().apply {
        moveTo(cx - capW / 2, capY + capH * 0.05f)
        cubicTo(cx - capW / 2, capY - capH, cx + capW / 2, capY - capH, cx + capW / 2, capY + capH * 0.05f)
        quadraticBezierTo(cx, capY + capH * 0.22f, cx - capW / 2, capY + capH * 0.05f)
        close()
    }
    drawPath(cap, Brush.verticalGradient(listOf(Color(0xFFFF7043), CapRed, CapDeep), startY = capY - capH, endY = capY + capH * 0.2f), alpha = alpha)
}

/** Woven bowl that replaces the cap when the food appears. */
private fun DrawScope.drawBowl(alpha: Float) {
    if (alpha <= 0.01f) return
    val w = size.minDimension
    val cx = center.x
    val top = center.y + w * 0.08f
    val bowl = Path().apply {
        moveTo(cx - w * 0.36f, top)
        cubicTo(cx - w * 0.34f, top + w * 0.26f, cx + w * 0.34f, top + w * 0.26f, cx + w * 0.36f, top)
        close()
    }
    drawPath(bowl, Brush.verticalGradient(listOf(Color(0xFFA1887F), Color(0xFF4E342E)), startY = top, endY = top + w * 0.2f), alpha = alpha)
    for (i in 1..3) {
        val y = top + w * 0.05f * i
        drawLine(Color(0x55FFFFFF), Offset(cx - w * (0.34f - 0.05f * i), y), Offset(cx + w * (0.34f - 0.05f * i), y), strokeWidth = w * 0.006f, alpha = alpha)
    }
}

private fun DrawScope.drawLeaves(alpha: Float, sway: Float) {
    if (alpha <= 0.01f) return
    val w = size.minDimension
    val base = Offset(center.x, center.y + w * 0.08f)
    val angles = listOf(-62f, -35f, 35f, 62f)
    angles.forEachIndexed { i, a ->
        rotate(a + sway * 4f, base) {
            val leaf = Path().apply {
                moveTo(base.x, base.y)
                quadraticBezierTo(base.x + w * 0.07f, base.y - w * 0.2f, base.x, base.y - w * 0.36f)
                quadraticBezierTo(base.x - w * 0.07f, base.y - w * 0.2f, base.x, base.y)
                close()
            }
            drawPath(leaf, Brush.verticalGradient(listOf(Color(0xFFB7E36A), Color(0xFF2E8B57)), startY = base.y - w * 0.36f, endY = base.y), alpha = alpha * (0.75f + 0.05f * i))
        }
    }
}

/**
 * The heart of the morph: each white spot on the cap drifts to a spot in the bowl and becomes food.
 * Spot i -> fruit i (colour and size interpolate).
 */
private data class Morph(val sx: Float, val sy: Float, val sr: Float, val fx: Float, val fy: Float, val fr: Float, val food: Color)

private val MORPHS = listOf(
    // cap spot (relative to size)          bowl heap position              fruit colour
    Morph(-0.17f, -0.17f, 0.030f, -0.20f, -0.025f, 0.085f, Color(0xFFFFD54F)), // banana / mango
    Morph(-0.05f, -0.24f, 0.035f, -0.06f, -0.085f, 0.095f, Color(0xFFFF8F00)), // papaya
    Morph(0.08f, -0.22f, 0.030f, 0.09f, -0.075f, 0.090f, Color(0xFFD81B60)), // dragon fruit
    Morph(0.19f, -0.14f, 0.026f, 0.22f, -0.015f, 0.080f, Color(0xFF7CB342)), // lime
    Morph(-0.24f, -0.06f, 0.022f, -0.27f, 0.035f, 0.060f, Color(0xFF6D4C41)), // coconut
    Morph(0.00f, -0.12f, 0.028f, 0.01f, 0.015f, 0.085f, Color(0xFFE53935)), // chili / tomato
    Morph(-0.11f, -0.08f, 0.020f, -0.13f, 0.045f, 0.070f, Color(0xFF8E24AA)), // mangosteen
    Morph(0.13f, -0.07f, 0.022f, 0.15f, 0.045f, 0.070f, Color(0xFFC0CA33)), // guava
    Morph(0.25f, -0.04f, 0.018f, 0.29f, 0.045f, 0.055f, Color(0xFFFF7043)), // rambutan
)

private fun DrawScope.drawSporesToFood(p: Float, t: Float) {
    val w = size.minDimension
    val c = center
    MORPHS.forEachIndexed { i, m ->
        // Slight arc so spots "float" rather than slide
        val lift = sin(p * PI.toFloat()) * w * 0.12f * (if (i % 2 == 0) 1f else 0.7f)
        val x = c.x + w * (m.sx + (m.fx - m.sx) * p)
        val y = c.y + w * (m.sy + (m.fy - m.sy) * p) - lift
        val r = w * (m.sr + (m.fr - m.sr) * p)
        val col = lerp(Color.White, m.food, p)
        drawCircle(Brush.radialGradient(listOf(lerp(col, Color.White, 0.35f), col), Offset(x - r * 0.3f, y - r * 0.3f), r * 1.2f), r, Offset(x, y))
    }
    // Drifting spores
    for (i in 0 until 14) {
        val a = (i * 0.45f + t * 2f * PI.toFloat()) % (2f * PI.toFloat())
        val rr = w * (0.30f + 0.08f * sin(i + t * 6f))
        val pos = Offset(c.x + cos(a) * rr, c.y - w * 0.05f + sin(a) * rr * 0.6f)
        drawCircle(lerp(Color(0x99FFF3E0), Color(0x99B7E36A), p), radius = w * 0.006f, center = pos)
    }
}
