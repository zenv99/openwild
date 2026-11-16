package org.openwild.app.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
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
                // Latin name as a second line only when there is a common name above it (never the same name twice).
                if (pack.species.commonNames[i] != null) Text(
                    pack.species.scientificNames[i], color = Color.White.copy(alpha = 0.75f), fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { DangerBadge(flags, fungus = pack.species.isFungus[i]) }
        if (pack.photosWanted(i)) PhotosWantedBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        // Cover borrowed from a related species: say so on the card itself.
        val borrowed = remember(key) { pack.photoCount(i) == 0 }
        if (b != null && borrowed) {
            Text(
                stringResource(R.string.related_tile), color = Color.White, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 40.dp)
                    .clip(RoundedCornerShape(50)).background(PhotoWanted).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
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

private val LifeStages = listOf("seedling", "leaves", "flower", "fruit", "closeup")

@Composable
fun stageLabel(stage: String): String = stringResource(
    when (stage) {
        "seedling" -> R.string.stage_seedling
        "leaves" -> R.string.stage_leaves
        "flower" -> R.string.stage_flower
        "fruit" -> R.string.stage_fruit
        "closeup" -> R.string.stage_closeup
        "bark" -> R.string.stage_bark
        "related" -> R.string.stage_related
        "field" -> R.string.stage_field
        "specimen" -> R.string.stage_specimen
        else -> R.string.stage_unspecified
    },
)

/**
 * Large photo on the species page. Tap opens a full-screen, zoomable viewer with photo credits.
 * A photo of a related species carries a banner saying so. Missing growth stages show a "Need photo" tile
 * with a plus; tapping it opens "Add your photos" for that stage ([onAdd]).
 */
@Composable
fun SpeciesPhotoHeader(pack: RegionPack, i: Int, name: String, onAdd: ((String?) -> Unit)? = null) {
    if (!pack.hasPhotos) return
    val photos by produceState<List<Pair<RegionPack.Photo, ImageBitmap>>>(emptyList(), pack, i) {
        value = withContext(Dispatchers.IO) { pack.photos(i).mapNotNull { p -> decode(p.bytes)?.let { p to it } } }
    }
    var viewerAt by remember(i) { mutableStateOf<Int?>(null) }
    if (photos.isEmpty()) {
        NeedPhotoBox(Modifier.fillMaxWidth().height(220.dp), big = true, onClick = onAdd?.let { { it(null) } })
        return
    }
    val pager = rememberPagerState { photos.size }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.large)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize()) {
                    Image(
                        photos[page].second,
                        contentDescription = stringResource(R.string.photo_of, name),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clickable { viewerAt = page },
                    )
                    if (photos[page].first.stage == "related") RelatedBanner(photos[page].first, Modifier.align(Alignment.TopStart))
                }
            }
            PagerDots(photos.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
            if (onAdd != null) {
                AddPhotoPill(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { onAdd(null) }
            }
        }
        PhotoCredit(photos[pager.currentPage].first, Color.Unspecified)
        LifeStageStrip(photos.filter { it.first.stage != "related" }, onAdd = onAdd?.let { f -> { st: String -> f(st) } }) { st ->
            viewerAt = photos.indexOfFirst { it.first.stage == st }.coerceAtLeast(0)
        }
    }
    viewerAt?.let { start -> PhotoViewer(photos, start, name) { viewerAt = null } }
}

/** "Related plant, not this one: <name>" across the top of a borrowed photo. Never ambiguous. */
@Composable
fun RelatedBanner(p: RegionPack.Photo, modifier: Modifier = Modifier) {
    val other = p.observer?.substringAfter("related species: ", "")?.trim()?.takeIf { it.isNotBlank() }
    Row(
        modifier.fillMaxWidth().background(PhotoWanted.copy(alpha = 0.95f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (other != null) stringResource(R.string.related_banner, other) else stringResource(R.string.stage_related),
            color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Placeholder where a photo is missing: a plus in a circle and "Need photo". Tappable when [onClick] is set. */
@Composable
fun NeedPhotoBox(modifier: Modifier = Modifier, big: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        modifier.clip(RoundedCornerShape(if (big) 20.dp else 12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.5.dp, PhotoWanted.copy(alpha = 0.55f), RoundedCornerShape(if (big) 20.dp else 12.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 2.dp)) {
            Box(
                Modifier.size(if (big) 52.dp else 30.dp).clip(RoundedCornerShape(50)).background(PhotoWanted),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, stringResource(R.string.need_photo_add), tint = Color.White, modifier = Modifier.size(if (big) 30.dp else 18.dp)) }
            Text(
                stringResource(R.string.need_photo), style = if (big) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = PhotoWanted,
            )
            if (big && onClick != null) {
                Text(stringResource(R.string.need_photo_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddPhotoPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.need_photo_add), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A candidate's reference photos for comparing with your own: swipe through them, each labelled with its
 * growth stage (leaves, flower, fruit...) and credited. Tap to open full screen.
 */
/**
 * Photos of one candidate inside the match browser. No inner swiping (that fought with swiping between
 * candidates): one large photo, and a row of thumbnails underneath to switch photo by tapping. Tap the
 * large photo to open it full screen and zoom.
 */
@Composable
fun CandidatePhotos(pack: RegionPack, i: Int, name: String, modifier: Modifier = Modifier) {
    val photos by produceState<List<Pair<RegionPack.Photo, ImageBitmap>>>(emptyList(), pack, i) {
        value = withContext(Dispatchers.IO) { pack.photos(i).mapNotNull { p -> decode(p.bytes)?.let { p to it } } }
    }
    var current by remember(i) { mutableStateOf(0) }
    var viewerAt by remember(i) { mutableStateOf<Int?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            if (photos.isEmpty()) {
                NeedPhotoBox(Modifier.fillMaxSize())
            } else {
                val k = current.coerceIn(0, photos.size - 1)
                val cur = photos[k].first
                Image(
                    photos[k].second, contentDescription = stringResource(R.string.photo_of, name),
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clickable { viewerAt = k },
                )
                if (cur.stage == "related") {
                    RelatedBanner(cur, Modifier.align(Alignment.TopStart))
                } else {
                    Text(
                        stageLabel(cur.stage), color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    PhotoCredit(cur, Color.White.copy(alpha = 0.9f))
                }
            }
        }
        if (photos.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                photos.take(5).forEachIndexed { n, (ph, bmp) ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (n == current) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .padding(if (n == current) 3.dp else 0.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { current = n },
                    ) {
                        Image(bmp, contentDescription = stageLabel(ph.stage), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                if (photos.size > 5) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { viewerAt = 5 },
                        contentAlignment = Alignment.Center,
                    ) { Text("+${photos.size - 5}", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
    viewerAt?.let { start -> PhotoViewer(photos, start, name) { viewerAt = null } }
}

@Composable
private fun LifeStageStrip(
    photos: List<Pair<RegionPack.Photo, ImageBitmap>>,
    onAdd: ((String) -> Unit)? = null,
    onOpen: (String) -> Unit = {},
) {
    val byStage = photos.groupBy { it.first.stage }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(LifeStages) { stage ->
            val hit = byStage[stage]?.firstOrNull()
            Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hit != null) {
                    Image(
                        hit.second, contentDescription = stageLabel(stage), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(stage) },
                    )
                } else {
                    NeedPhotoBox(Modifier.fillMaxWidth().height(84.dp), onClick = onAdd?.let { { it(stage) } })
                }
                Text(stageLabel(stage), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Small camera pill: this plant still needs photos. Tapping it (where offered) opens "Add your photos". */
@Composable
fun PhotosWantedBadge(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(PhotoWanted.copy(alpha = 0.92f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.photos_wanted_badge), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

val PhotoWanted = Color(0xFFE07A1F)
private const val COMMUNITY_SUFFIX = " (OpenWild community)"

@Composable
private fun PhotoCredit(p: RegionPack.Photo, color: Color) {
    val obs = p.observer
    if (obs != null && obs.endsWith(COMMUNITY_SUFFIX)) {
        // Community photographers get their name in front, highlighted.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhotoCamera, null, tint = PhotoWanted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.photo_credit_community, obs.removeSuffix(COMMUNITY_SUFFIX)),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            )
        }
        return
    }
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
            if (photos[pager.currentPage].first.stage == "related") {
                RelatedBanner(photos[pager.currentPage].first, Modifier.align(Alignment.TopStart).padding(top = 56.dp))
            }
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
