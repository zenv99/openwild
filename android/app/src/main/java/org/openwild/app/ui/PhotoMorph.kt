package org.openwild.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Landing page (Zen V's vision): a real fly agaric whose white flecks lift off the cap, turn into leaves and
 * fruit, and the mushroom dissolves into a table of tropical fruit. Then it returns, and loops.
 *
 * Photos (Wikimedia Commons): fly agaric by Dominicus Johannes Bergsma, CC BY-SA 4.0;
 * tropical fruit by Peggy Greb, USDA ARS, public domain. Credits: assets/hero_credits.json.
 */
@Composable
fun PhotoMorph(modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "photoMorph")
    val t by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(13_000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val particles = remember { makeParticles() }

    // Timeline (fraction of the loop)
    val lift = when {                       // flecks leave the cap
        t < 0.18f -> 0f
        t < 0.46f -> ease((t - 0.18f) / 0.28f)
        t < 0.88f -> 1f
        else -> 0f
    }
    val toFruit = when {                    // mushroom photo -> fruit photo
        t < 0.34f -> 0f
        t < 0.56f -> ease((t - 0.34f) / 0.22f)
        t < 0.86f -> 1f
        else -> 1f - ease((t - 0.86f) / 0.14f)
    }
    val fall = if (t < 0.50f) 0f else ((t - 0.50f) / 0.38f).coerceIn(0f, 1f)  // leaves drift down over the fruit
    val zoom = 1.04f + 0.04f * sin(2f * PI.toFloat() * t)

    Box(modifier.background(Color(0xFF1B140F))) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)) {
            Image(
                painterResource(R.drawable.hero_mushroom), contentDescription = stringResource(R.string.hero_mushroom_cd),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 1f - toFruit),
            )
            Image(
                painterResource(R.drawable.hero_fruit), contentDescription = stringResource(R.string.hero_fruit_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer(alpha = toFruit, scaleX = 1.08f - 0.08f * toFruit, scaleY = 1.08f - 0.08f * toFruit),
            )
            Canvas(Modifier.fillMaxSize()) { drawFlecks(particles, lift, fall, toFruit, t) }
        }
        // Soft fade at the bottom so the credit stays readable.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.hero_photo_credit), color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun ease(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Fleck centres on the photo (fractions of width/height of hero_mushroom, 1440x1152). */
private val FLECKS = floatArrayOf(
    0.365f, 0.414f, 0.487f, 0.395f, 0.523f, 0.311f, 0.320f, 0.355f, 0.589f, 0.294f, 0.282f, 0.342f,
    0.392f, 0.247f, 0.261f, 0.300f, 0.198f, 0.353f, 0.384f, 0.372f, 0.347f, 0.298f, 0.235f, 0.243f,
    0.355f, 0.358f, 0.653f, 0.306f, 0.242f, 0.363f, 0.194f, 0.290f, 0.533f, 0.395f, 0.430f, 0.313f,
    0.400f, 0.311f, 0.588f, 0.374f, 0.484f, 0.342f, 0.760f, 0.323f, 0.165f, 0.327f, 0.491f, 0.167f,
    0.223f, 0.325f, 0.576f, 0.419f, 0.453f, 0.217f, 0.301f, 0.300f, 0.459f, 0.337f, 0.637f, 0.209f,
    0.608f, 0.236f, 0.476f, 0.432f, 0.638f, 0.406f, 0.377f, 0.307f, 0.425f, 0.191f, 0.247f, 0.264f,
    0.486f, 0.259f, 0.233f, 0.394f, 0.553f, 0.224f, 0.307f, 0.241f, 0.256f, 0.401f, 0.342f, 0.263f,
    0.300f, 0.419f, 0.361f, 0.201f, 0.680f, 0.386f, 0.695f, 0.253f, 0.311f, 0.196f, 0.779f, 0.410f,
    0.189f, 0.383f, 0.399f, 0.406f, 0.689f, 0.281f, 0.377f, 0.168f, 0.703f, 0.326f, 0.423f, 0.415f,
    0.508f, 0.217f, 0.636f, 0.449f,
    // right side of the cap
    0.720f, 0.360f, 0.745f, 0.290f, 0.800f, 0.370f, 0.820f, 0.430f, 0.770f, 0.250f, 0.700f, 0.430f,
)
private const val IMG_ASPECT = 1440f / 1152f
private val CAP_RED = Color(0xFFD2231F)

private class Particle(
    val x: Float, val y: Float, val drift: Float, val rise: Float, val spin: Float, val phase: Float,
    val isFruit: Boolean, val color: Color, val size: Float,
)

private fun makeParticles(): List<Particle> {
    val rnd = Random(9)
    val greens = listOf(Color(0xFF7CB342), Color(0xFF9CCC65), Color(0xFF558B2F), Color(0xFFB7E36A), Color(0xFF43A047))
    val fruits = listOf(Color(0xFFFF8F00), Color(0xFFFFC107), Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFFFF7043))
    return (0 until FLECKS.size / 2).map { i ->
        val fruit = rnd.nextFloat() < 0.28f
        Particle(
            x = FLECKS[2 * i], y = FLECKS[2 * i + 1],
            drift = (rnd.nextFloat() - 0.5f) * 0.8f, rise = 0.04f + rnd.nextFloat() * 0.14f,
            spin = (rnd.nextFloat() - 0.5f) * 720f, phase = rnd.nextFloat() * 6.28f,
            isFruit = fruit, color = if (fruit) fruits[i % fruits.size] else greens[i % greens.size],
            size = 0.011f + rnd.nextFloat() * 0.014f,
        )
    }
}

private fun DrawScope.drawFlecks(ps: List<Particle>, lift: Float, fall: Float, toFruit: Float, t: Float) {
    if (lift <= 0f) return
    // Where the photo is drawn (ContentScale.Crop): map photo fractions to canvas pixels.
    val dw = max(size.width, size.height * IMG_ASPECT)
    val dh = dw / IMG_ASPECT
    val ox = (size.width - dw) / 2f
    val oy = (size.height - dh) / 2f
    val fleckR = dw * 0.007f
    ps.forEachIndexed { i, p ->
        val sx = ox + p.x * dw
        val sy = oy + p.y * dh
        // Cover the fleck on the cap while it is "away", so it really looks lifted off.
        if (toFruit < 1f) drawCircle(CAP_RED.copy(alpha = (lift * 0.9f) * (1f - toFruit)), fleckR * 1.25f, Offset(sx, sy))

        val stagger = ((lift * 1.4f) - (i % 7) * 0.06f).coerceIn(0f, 1f)
        val l = ease(stagger)
        val sway = sin(t * 18f + p.phase) * size.width * 0.02f
        val cx = sx + (p.x - 0.5f) * size.width * 0.75f * l + p.drift * size.width * 0.30f * l + sway * l
        val cy = sy - p.rise * size.height * l + fall * size.height * (0.45f + p.rise)
        val r = fleckR + (p.size * size.width - fleckR) * l
        val alpha = (l * 3f).coerceIn(0f, 1f) * (1f - ((fall - 0.75f) / 0.25f).coerceIn(0f, 1f))
        if (alpha <= 0f) return@forEachIndexed
        val colour = lerp(Color.White, p.color, l)
        if (p.isFruit || l < 0.35f) {
            drawCircle(colour.copy(alpha = alpha), r * (if (p.isFruit) 0.8f else 1f), Offset(cx, cy))
            if (p.isFruit && l > 0.6f) drawCircle(Color.White.copy(alpha = 0.35f * alpha), r * 0.25f, Offset(cx - r * 0.3f, cy - r * 0.3f))
        } else {
            rotate(p.spin * l + t * 90f + p.phase * 20f, Offset(cx, cy)) { drawLeaf(cx, cy, r * 1.6f, colour.copy(alpha = alpha)) }
        }
    }
}

private fun DrawScope.drawLeaf(cx: Float, cy: Float, len: Float, colour: Color) {
    val w = len * 0.42f
    val leaf = Path().apply {
        moveTo(cx, cy - len)
        quadraticBezierTo(cx + w, cy - len * 0.2f, cx, cy + len)
        quadraticBezierTo(cx - w, cy - len * 0.2f, cx, cy - len)
        close()
    }
    drawPath(leaf, colour)
    drawLine(Color.Black.copy(alpha = 0.18f * colour.alpha), Offset(cx, cy - len * 0.85f), Offset(cx, cy + len * 0.9f), strokeWidth = len * 0.06f)
}
