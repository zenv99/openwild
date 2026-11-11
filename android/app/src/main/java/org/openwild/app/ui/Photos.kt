package org.openwild.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwild.app.R
import org.openwild.app.pack.RegionPack

/** Decoded thumbnails, so scrolling a long list doesn't re-decode. Photos never leave the phone. */
private object ThumbCache {
    val cache = LruCache<String, ImageBitmap>(400)
}

/** Cover photos for grid tiles, bounded by memory (≈40 MB). */
private object CoverCache {
    val cache = object : LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

private fun decode(bytes: ByteArray?, sample: Int = 1): ImageBitmap? =
    bytes?.takeIf { it.isNotEmpty() }?.let {
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(it, 0, it.size, opts)?.asImageBitmap()
        }.getOrNull()
    }

/** Square species thumbnail for list rows; falls back to a leaf icon when the pack has no photo. */
@Composable
fun SpeciesThumb(pack: RegionPack, i: Int, tint: Color, size: Dp = 52.dp) {
    val key = "${pack.regionId}:${pack.version}:$i"
    val bmp by produceState(ThumbCache.cache.get(key), key) {
        if (value == null && pack.hasPhotos) {
            value = withContext(Dispatchers.IO) {
                // Newer packs store no separate thumbnail: scale the cover photo down (448 px -> 224 px) instead.
                decode(pack.thumbnail(i)) ?: decode(pack.coverImage(i), sample = 2)
            }?.also { ThumbCache.cache.put(key, it) }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Eco, null, tint = tint)
        }
    }
}

/**
 * Photo card for grids and carousels: the cover photo fills the card, the name sits on a dark fade,
 * and a danger badge sits on top. Falls back to a leaf icon on a tinted card.
 */
@Composable
fun SpeciesTile(pack: RegionPack, i: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val key = "${pack.regionId}:${pack.version}:$i"
    val bmp by produceState(CoverCache.cache.get(key), key) {
        if (value == null && pack.hasPhotos) {
            value = withContext(Dispatchers.IO) { decode(pack.coverImage(i)) }?.also { CoverCache.cache.put(key, it) }
        }
    }
    val flags = pack.safety[i]
    val tint = if (flags?.isDeadly == true) org.openwild.app.ui.theme.Danger else org.openwild.app.ui.theme.Leaf
    Box(
        modifier.aspectRatio(0.8f).clip(MaterialTheme.shapes.large).background(tint.copy(alpha = 0.18f)).clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Eco, null, tint = tint, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 10.dp),
        ) {
            Column {
                Text(
                    pack.species.commonNames[i] ?: pack.species.scientificNames[i],
                    color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pack.species.scientificNames[i], color = Color.White.copy(alpha = 0.75f), fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { DangerBadge(flags) }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { k ->
            Box(Modifier.size(if (k == current) 8.dp else 6.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (k == current) 1f else 0.5f)))
        }
    }
}

/** Large photo on the species page. Tap opens a full-screen, zoomable viewer with photo credits. */
@Composable
fun SpeciesPhotoHeader(pack: RegionPack, i: Int, name: String) {
    if (!pack.hasPhotos) return
    val photos by produceState<List<Pair<RegionPack.Photo, ImageBitmap>>>(emptyList(), pack, i) {
        value = withContext(Dispatchers.IO) { pack.photos(i).mapNotNull { p -> decode(p.bytes)?.let { p to it } } }
    }
    if (photos.isEmpty()) return
    var viewerAt by remember(i) { mutableStateOf<Int?>(null) }
    val pager = rememberPagerState { photos.size }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.large)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Image(
                    photos[page].second,
                    contentDescription = stringResource(R.string.photo_of, name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { viewerAt = page },
                )
            }
            PagerDots(photos.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
            Text(
                stringResource(R.string.photo_tap_hint),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        PhotoCredit(photos[pager.currentPage].first, Color.Unspecified)
    }
    viewerAt?.let { start -> PhotoViewer(photos, start, name) { viewerAt = null } }
}

@Composable
private fun PhotoCredit(p: RegionPack.Photo, color: Color) {
    Text(
        stringResource(R.string.photo_credit, p.observer?.takeIf { it.isNotBlank() } ?: stringResource(R.string.photo_credit_unknown),
            p.license.uppercase().replace('-', ' ')),
        style = MaterialTheme.typography.labelSmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
    )
}

@Composable
private fun PhotoViewer(photos: List<Pair<RegionPack.Photo, ImageBitmap>>, start: Int, name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page -> ZoomableImage(photos[page].second, name) }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.photo_close), tint = Color.White)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(16.dp)) {
                Column {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    PhotoCredit(photos[pager.currentPage].first, Color.White.copy(alpha = 0.8f))
                    if (photos.size > 1) {
                        Text(stringResource(R.string.photo_counter, pager.currentPage + 1, photos.size),
                            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bmp: ImageBitmap, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bmp,
        contentDescription = stringResource(R.string.photo_of, name),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                // Pinch always zooms; one-finger drags only pan when zoomed in, so swiping to the next photo still works.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}
