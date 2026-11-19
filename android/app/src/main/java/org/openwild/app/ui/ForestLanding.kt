package org.openwild.app.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.openwild.app.R
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Ambient motion is off when the phone asks for reduced motion or is saving battery. */
fun motionAllowed(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val save = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    }.getOrDefault(false)
    return scale > 0f && !save
}

private val Night = Color(0xFF04140F)
private val SporeGlow = Color(0xFFD6FFE8)

/** The whole forest artwork, then the push toward the mushroom. The opening, including that move, lasts under two seconds. */
const val SPLASH_MS = 1600

/** Where the foreground mushroom cap sits in the artwork, as fractions of width and height. */
const val CAP_X = 0.52f
const val CAP_Y = 0.48f

data class FitFrame(val left: Float, val top: Float, val width: Float, val height: Float)

object SplashLayout {
    const val IMAGE_W = 720f
    const val IMAGE_H = 1280f

    /** The whole artwork, fitted inside the screen. Nothing is cropped. */
    fun fit(viewW: Float, viewH: Float): FitFrame {
        if (viewW <= 0f || viewH <= 0f) return FitFrame(0f, 0f, 0f, 0f)
        val imageAspect = IMAGE_W / IMAGE_H
        val viewAspect = viewW / viewH
        return if (viewAspect > imageAspect) {
            val h = viewH
            val w = h * imageAspect
            FitFrame((viewW - w) / 2f, 0f, w, h)
        } else {
            val w = viewW
            val h = w / imageAspect
            FitFrame(0f, (viewH - h) / 2f, w, h)
        }
    }

    /** Cap position as fractions of the screen, used as the zoom origin. */
    fun capOrigin(frame: FitFrame, viewW: Float, viewH: Float): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || frame.width <= 0f) return 0.5f to 0.5f
        val x = frame.left + CAP_X * frame.width
        val y = frame.top + CAP_Y * frame.height
        return (x / viewW) to (y / viewH)
    }
}

/**
 * Opening screen. The forest artwork is shown whole first, then the view eases in toward the
 * mushroom. Glitter stays on unless the phone asks for reduced motion or is saving battery.
 * A tap skips it.
 */
@Composable
fun MushroomOpening(onContinue: () -> Unit) {
    val context = LocalContext.current
    val motion = remember { motionAllowed(context) }
    LaunchedEffect(motion) {
        delay(if (motion) SPLASH_MS.toLong() else 700)
        onContinue()
    }
    Box(Modifier.fillMaxSize().background(Night).clickable(onClick = onContinue)) {
        MushroomSplash(motion)
        Text(
            stringResource(R.string.enter_openwild),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun MushroomSplash(motion: Boolean) {
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        if (motion) zoom.animateTo(1f, tween(SPLASH_MS, easing = FastOutSlowInEasing))
    }
    val drift = if (motion) {
        val loop = rememberInfiniteTransition(label = "splash")
        val t by loop.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "spark",
        )
        t
    } else {
        0f
    }
    val sparkles = remember { splashSparkles() }
    val push = if (motion) zoom.value else 0f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = SplashLayout.fit(maxWidth.value, maxHeight.value)
        val (ox, oy) = SplashLayout.capOrigin(frame, maxWidth.value, maxHeight.value)
        Image(
            painterResource(R.drawable.landing_forest),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(28.dp).graphicsLayer { alpha = 0.55f },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Night.copy(alpha = 0.62f), Night.copy(alpha = 0.28f), Night.copy(alpha = 0.66f))),
            ),
        )
        Image(
            painterResource(R.drawable.landing_forest),
            contentDescription = stringResource(R.string.landing_forest_cd),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val scale = 1f + 0.14f * push
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(ox, oy)
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            drawSplashSparkles(sparkles, drift, animate = motion)
        }
    }
}

private class Spark(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val color: Color)

private fun splashSparkles(): List<Spark> {
    val rnd = Random(4)
    val colors = listOf(Color(0xFF7CFFF0), Color(0xFFC9A6FF), Color(0xFFFFD56A))
    return List(42) {
        Spark(
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
            r = 1.2f + rnd.nextFloat() * 2.6f,
            speed = 0.08f + rnd.nextFloat() * 0.18f,
            phase = rnd.nextFloat(),
            color = colors[it % colors.size],
        )
    }
}

private fun DrawScope.drawSplashSparkles(sparkles: List<Spark>, drift: Float, animate: Boolean) {
    sparkles.forEach { s ->
        val rise = if (animate) ((s.y - drift * s.speed) % 1f + 1f) % 1f else s.y
        val twinkle = if (animate) 0.35f + 0.65f * ((sin((drift * 6f + s.phase) * 2f * PI.toFloat()) + 1f) / 2f) else 0.7f
        val x = s.x * size.width + if (animate) sin((drift + s.phase) * 2f * PI.toFloat()) * 8f else 0f
        val y = rise * size.height
        drawCircle(s.color.copy(alpha = 0.85f * twinkle), s.r, Offset(x, y))
        drawCircle(Color.White.copy(alpha = 0.45f * twinkle), s.r * 0.35f, Offset(x, y))
    }
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC10241C))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Home backdrop: original artwork made for Open Wild, full bleed.
 * Scroll and a slow drift shift it. Spores and the drift pause when motion should stay still.
 */
@Composable
fun ForestBackdrop(scrollPx: Float, motion: Boolean, modifier: Modifier = Modifier) {
    val drift = if (motion) {
        val loop = rememberInfiniteTransition(label = "forest")
        val t by loop.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Restart),
            label = "drift",
        )
        t
    } else {
        0f
    }
    val spores = remember { sporeField() }
    val sway = sin(drift * 2f * PI.toFloat())
    Box(modifier.background(Night)) {
        Image(
            painterResource(R.drawable.landing_forest),
            contentDescription = stringResource(R.string.landing_forest_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.08f
                scaleY = 1.08f
                translationY = -scrollPx * 0.18f + sway * 14f
                translationX = sway * 10f
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            drawSpores(spores, scrollPx, if (motion) drift else 0f, animate = motion)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Night.copy(alpha = 0.55f),
                        Night.copy(alpha = 0.08f),
                        Night.copy(alpha = 0.12f),
                        Night.copy(alpha = 0.72f),
                    ),
                ),
            ),
        )
    }
}

private class Spore(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

private fun sporeField(): List<Spore> {
    val rnd = Random(18)
    return List(36) {
        Spore(
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
            r = 1.5f + rnd.nextFloat() * 3.2f,
            speed = 0.04f + rnd.nextFloat() * 0.12f,
            phase = rnd.nextFloat(),
        )
    }
}

private fun DrawScope.drawSpores(spores: List<Spore>, scrollPx: Float, drift: Float, animate: Boolean) {
    spores.forEach { s ->
        val rise = if (animate) ((s.y - drift * s.speed) % 1f + 1f) % 1f else s.y
        val x = s.x * size.width + sin((drift + s.phase) * 2f * PI.toFloat()) * 6f
        val y = rise * size.height - scrollPx * 0.05f
        drawCircle(SporeGlow.copy(alpha = 0.45f), s.r, Offset(x, y))
    }
}
