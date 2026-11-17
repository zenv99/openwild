package org.openwild.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openwild.app.R
import org.openwild.app.core.Tier
import kotlin.math.roundToInt

/**
 * Opt-in online check: a slower, more careful identification in the cloud. The photos go to OpenWild's
 * private inbox only when the user taps "Check online" (and agreed once), are compared there against thousands
 * of real, community-confirmed photos, and are deleted after the check unless the user chose to share them.
 */
class OnlineCheck(private val app: Context, private val scope: CoroutineScope) {
    sealed interface S {
        data object Idle : S
        data class Sending(val done: Int, val total: Int) : S
        data class Waiting(val since: Long) : S
        data class Done(val json: JSONObject, val offlineTop: String) : S
        data class Failed(val message: String) : S
    }

    private val prefs get() = app.getSharedPreferences("contribute", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<S>(S.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    val available: Boolean get() = PhotoInbox.enabled
    val optedIn: Boolean get() = prefs.getBoolean("online_ok", false)
    val share: Boolean get() = prefs.getBoolean("online_share", false)

    fun optIn(share: Boolean) = prefs.edit().putBoolean("online_ok", true).putBoolean("online_share", share).apply()
    fun optOut() = prefs.edit().putBoolean("online_ok", false).putBoolean("online_share", false).apply()

    fun reset() {
        job?.cancel()
        _state.value = S.Idle
    }

    fun start(regionId: String, photos: List<Photo>, offlineTop: String) {
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = S.Sending(0, photos.size.coerceAtMost(6))
                val pairs = photos.map { it.bitmap to it.part.name.lowercase() }
                val id = withContext(Dispatchers.IO) {
                    PhotoInbox.sendScan(regionId, pairs, offlineTop, share) { d, t -> _state.value = S.Sending(d, t) }
                }
                val since = System.currentTimeMillis()
                _state.value = S.Waiting(since)
                delay(60_000)                       // the cloud check needs at least a minute
                while (System.currentTimeMillis() - since < 25 * 60_000L) {
                    val r = withContext(Dispatchers.IO) { runCatching { PhotoInbox.scanResult(id) }.getOrNull() }
                    if (r != null) {
                        _state.value = if (r.optString("status") == "done") S.Done(r, offlineTop)
                        else S.Failed(r.optString("error").ifBlank { app.getString(R.string.online_failed) })
                        return@launch
                    }
                    delay(20_000)
                }
                _state.value = S.Failed(app.getString(R.string.online_timeout))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = S.Failed(app.getString(R.string.online_failed))
            }
        }
    }
}

private fun decodeThumb(b64: String?): Bitmap? = b64?.takeIf { it.isNotBlank() }?.let {
    runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull()
}

@Composable
fun OnlineCheckCard(state: UiState, vm: AppViewModel) {
    val oc = vm.online
    if (!oc.available) return
    val res = state.result ?: return
    val pack = vm.pack ?: return
    val s by oc.state.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val offlineTop = pack.species.scientificNames[res.result.top]
    val reports = rememberReportSender()

    fun go() = oc.start(pack.regionId, state.photos, offlineTop)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.online_title), style = MaterialTheme.typography.titleMedium)
            }
            when (val st = s) {
                is OnlineCheck.S.Idle -> {
                    Text(stringResource(R.string.online_pitch), style = MaterialTheme.typography.bodySmall)
                    var on by remember { mutableStateOf(oc.optedIn) }
                    Button(onClick = { if (oc.optedIn) go() else asking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.online_button))
                    }
                    if (on) TextButton(onClick = { oc.optOut(); on = false }) {
                        Text(stringResource(R.string.online_turn_off), style = MaterialTheme.typography.labelSmall)
                    }
                }
                is OnlineCheck.S.Sending -> {
                    Text(stringResource(R.string.online_sending, st.done, st.total))
                    LinearProgressIndicator(progress = { if (st.total == 0) 0f else st.done / st.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                is OnlineCheck.S.Waiting -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(st) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                    val sec = ((now - st.since) / 1000).toInt()
                    Text(stringResource(R.string.online_waiting))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.online_elapsed, sec / 60, sec % 60), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is OnlineCheck.S.Failed -> {
                    Text(st.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = ::go) { Text(stringResource(R.string.retry)) }
                }
                is OnlineCheck.S.Done -> {
                    val j = st.json
                    val cands = j.optJSONArray("candidates")
                    val verdict = j.optString("verdict")
                    val topName = cands?.optJSONObject(0)?.optString("scientific_name").orEmpty()
                    val (label, danger) = when (verdict) {
                        "confident" -> stringResource(R.string.online_confident) to false
                        "likely" -> stringResource(R.string.online_likely) to false
                        else -> stringResource(if (j.optBoolean("genus_only")) R.string.online_genus else R.string.online_unsure) to true
                    }
                    Text(label, fontWeight = FontWeight.Bold,
                        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (verdict != "unsure" && !topName.equals(st.offlineTop, ignoreCase = true)) {
                        WarningBox(stringResource(R.string.online_disagrees), danger = false)
                    }
                    if (cands != null) for (k in 0 until minOf(5, cands.length())) {
                        val c = cands.getJSONObject(k)
                        OnlineRow(vm, c, highlight = k == 0, tier = if (k == 0 && verdict == "confident") Tier.MEDIUM else Tier.LOW)
                    }
                    Text(stringResource(R.string.online_never_eat), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The library needs this plant's photos: ask for them (that's how the offline app learns).
                    val idx = pack.indexOfScientificName(topName)
                    if (verdict == "confident" && reports.enabled && (idx == null || pack.photosWanted(idx))) {
                        OutlinedButton(onClick = {
                            reports.send(ReportSender.Request(
                                regionId = pack.regionId, predicted = st.offlineTop, chosen = topName,
                                fields = JSONObject().put("kind", "online_check").put("verdict", verdict)
                                    .put("online_score", cands?.optJSONObject(0)?.optDouble("score")),
                                photos = state.photos,
                            ))
                        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.online_need_photos)) }
                    }
                    TextButton(onClick = oc::reset) { Text(stringResource(R.string.online_again)) }
                }
            }
        }
    }

    if (asking) {
        var share by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.online_ask_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.online_ask_body))
                    Text(stringResource(R.string.online_ask_not_sent), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = share, onCheckedChange = { share = it })
                        Text(stringResource(R.string.online_ask_share), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { oc.optIn(share); asking = false; go() }) { Text(stringResource(R.string.online_button_short)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.not_now)) } },
        )
    }
}

@Composable
private fun OnlineRow(vm: AppViewModel, c: JSONObject, highlight: Boolean, tier: Tier) {
    val pack = vm.pack ?: return
    val sci = c.optString("scientific_name")
    val idx = pack.indexOfScientificName(sci)
    val thumb = remember(c) { decodeThumb(c.optString("example_photo_jpeg_b64")) }
    val common = idx?.let { pack.species.commonNames[it] } ?: c.optString("common_name").takeIf { it.isNotBlank() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = idx != null) { idx?.let { vm.go(Screen.Species(it, tier)) } }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(common ?: sci, style = MaterialTheme.typography.titleSmall)
            if (common != null) Text(sci, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
            if (idx != null) DangerBadge(pack.safety[idx], fungus = pack.species.isFungus[idx])
            else Text(stringResource(R.string.online_not_in_pack), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            c.optString("example_credit").takeIf { it.isNotBlank() }?.let {
                Text(stringResource(if (c.optBoolean("example_is_garden")) R.string.online_example_garden else R.string.online_example, it),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("${(c.optDouble("probability", 0.0) * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
    }
}
