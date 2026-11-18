package org.openwild.app.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
private val Glow = Color(0xFF7CFFC4)
private val SporeGlow = Color(0xFFD6FFE8)

/**
 * Opening screen. The fly agaric photograph and its morph stay as they are.
 * A tap, or a few seconds, continues into the home screen.
 */
@Composable
fun MushroomOpening(onContinue: () -> Unit) {
    val context = LocalContext.current
    val motion = remember { motionAllowed(context) }
    LaunchedEffect(motion) {
        delay(if (motion) 2800 else 900)
        onContinue()
    }
    Box(Modifier.fillMaxSize().background(Night).clickable(onClick = onContinue)) {
        if (motion) {
            PhotoMorph(Modifier.fillMaxSize())
        } else {
            Image(
                painterResource(R.drawable.hero_mushroom),
                contentDescription = stringResource(R.string.hero_mushroom_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            stringResource(R.string.enter_openwild),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
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
 * Home backdrop. The forest artwork file was not in this build, so this is the fly agaric
 * photograph set in a drawn night forest. Scroll shifts it slightly. Drift and spores pause
 * when motion should stay still.
 */
@Composable
fun ForestBackdrop(scrollPx: Float, motion: Boolean, modifier: Modifier = Modifier) {
    val drift = if (motion) {
        val loop = rememberInfiniteTransition(label = "forest")
        val t by loop.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
            label = "drift",
        )
        t
    } else {
        0f
    }
    val spores = remember { sporeField() }
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF02110E), Color(0xFF0A2A28), Color(0xFF071018))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawForest(scrollPx, drift)
            drawSpores(spores, scrollPx, if (motion) drift else 0f, animate = motion)
        }
        Image(
            painterResource(R.drawable.hero_mushroom),
            contentDescription = stringResource(R.string.hero_mushroom_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 96.dp)
                .fillMaxWidth(0.72f)
                .aspectRatio(1.15f)
                .graphicsLayer {
                    translationY = -scrollPx * 0.16f + sin(drift * 2f * PI.toFloat()) * 10f
                    translationX = sin(drift * 2f * PI.toFloat()) * 8f
                }
                .clip(RoundedCornerShape(32.dp)),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Night.copy(alpha = 0.72f),
                        Night.copy(alpha = 0.05f),
                        Night.copy(alpha = 0.28f),
                        Night.copy(alpha = 0.82f),
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

private fun DrawScope.drawForest(scrollPx: Float, drift: Float) {
    val w = size.width
    val h = size.height
    val sway = sin(drift * 2f * PI.toFloat()) * 8f
    drawCircle(Glow.copy(alpha = 0.16f), w * 0.42f, Offset(w * 0.5f + sway, h * 0.34f - scrollPx * 0.08f))
    drawCircle(Color(0xFF3D6BFF).copy(alpha = 0.10f), w * 0.28f, Offset(w * 0.18f, h * 0.62f))
    drawCircle(Color(0xFFFF4FA3).copy(alpha = 0.08f), w * 0.22f, Offset(w * 0.82f, h * 0.58f))
    mushroom(Offset(w * 0.16f + sway, h * 0.72f), w * 0.16f, Color(0xFF1FA87A))
    mushroom(Offset(w * 0.84f - sway, h * 0.66f), w * 0.20f, Color(0xFF7A4DFF))
    leaf(Offset(w * 0.08f, h * 0.40f), w * 0.22f, 40f)
    leaf(Offset(w * 0.92f, h * 0.46f), w * 0.26f, -30f)
    // Distant elephant, small and calm, on the right horizon.
    val ex = w * 0.78f
    val ey = h * 0.78f
    drawCircle(Color(0xFF0C1A16), w * 0.035f, Offset(ex, ey))
    drawCircle(Color(0xFF0C1A16), w * 0.012f, Offset(ex + w * 0.03f, ey - w * 0.01f))
    drawLine(Color(0xFF0C1A16), Offset(ex + w * 0.02f, ey), Offset(ex + w * 0.045f, ey + w * 0.04f), strokeWidth = 3f)
}

private fun DrawScope.mushroom(at: Offset, cap: Float, color: Color) {
    drawLine(color.copy(alpha = 0.7f), Offset(at.x, at.y - cap * 0.15f), Offset(at.x, at.y + cap * 0.55f), strokeWidth = cap * 0.14f)
    drawCircle(color.copy(alpha = 0.9f), cap * 0.48f, Offset(at.x, at.y - cap * 0.28f))
    drawCircle(Glow.copy(alpha = 0.4f), cap * 0.1f, Offset(at.x - cap * 0.16f, at.y - cap * 0.38f))
}

private fun DrawScope.leaf(at: Offset, len: Float, tilt: Float) {
    val path = Path().apply {
        moveTo(at.x, at.y)
        quadraticTo(at.x + len * 0.2f, at.y - len, at.x - len * 0.15f, at.y - len * 1.3f)
        quadraticTo(at.x - len * 0.45f, at.y - len * 0.4f, at.x, at.y)
        close()
    }
    drawPath(path, Color(0xFF143C30).copy(alpha = 0.9f))
    // tilt is reserved so the two leaves are not identical; the path already leans.
    if (tilt < 0) drawCircle(Glow.copy(alpha = 0.12f), len * 0.15f, at)
}

private fun DrawScope.drawSpores(spores: List<Spore>, scrollPx: Float, drift: Float, animate: Boolean) {
    spores.forEach { s ->
        val rise = if (animate) ((s.y - drift * s.speed) % 1f + 1f) % 1f else s.y
        val x = s.x * size.width + sin((drift + s.phase) * 2f * PI.toFloat()) * 6f
        val y = rise * size.height - scrollPx * 0.05f
        drawCircle(SporeGlow.copy(alpha = 0.45f), s.r, Offset(x, y))
    }
}
