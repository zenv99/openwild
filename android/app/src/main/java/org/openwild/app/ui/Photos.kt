package org.openwild.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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

private fun decode(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }

/** Square species thumbnail for list rows; falls back to a leaf icon when the pack has no photo. */
@Composable
fun SpeciesThumb(pack: RegionPack, i: Int, tint: Color, size: Dp = 52.dp) {
    val key = "${pack.regionId}:${pack.version}:$i"
    val bmp by produceState(ThumbCache.cache.get(key), key) {
        if (value == null && pack.hasPhotos) {
            value = withContext(Dispatchers.IO) { decode(pack.thumbnail(i)) }?.also { ThumbCache.cache.put(key, it) }
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
        Box(Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.large)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Image(
                    photos[page].second,
                    contentDescription = stringResource(R.string.photo_of, name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { viewerAt = page },
                )
            }
            Text(
                if (photos.size > 1) stringResource(R.string.photo_counter, pager.currentPage + 1, photos.size)
                else stringResource(R.string.photo_tap_hint),
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
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}
