package org.openwild.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openwild.app.BuildConfig
import org.openwild.app.R
import org.openwild.app.core.KingdomFilter
import org.openwild.app.core.PlantNetRequest
import org.openwild.app.ui.theme.Leaf
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Opt-in Pl@ntNet check. The photo leaves the phone only when the person taps the button on a result,
 * the switch is on, a key is set, and the phone has a connection. Plants only. Offline results stay.
 */
class PlantNet(private val app: Context, private val scope: CoroutineScope) {
    data class Hit(val scientific: String, val common: String?, val score: Double)

    sealed interface S {
        data object Idle : S
        data object Sending : S
        data class Done(val hits: List<Hit>, val remaining: Int?) : S
        data class Failed(val reason: String) : S
    }

    private val prefs = app.getSharedPreferences("plantnet", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("on", false))
    val enabled = _enabled.asStateFlow()
    private val _state = MutableStateFlow<S>(S.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("on", on).apply()
        _enabled.value = on
    }

    fun userKey(): String = prefs.getString("key", "") ?: ""

    fun setUserKey(value: String) {
        prefs.edit().putString("key", value.trim().take(80)).apply()
    }

    /** True when this build baked in a key and the person has not typed one. The key itself is not shown. */
    fun usingBuildKey(): Boolean = userKey().isBlank() && BuildConfig.PLANTNET_API_KEY.isNotBlank()

    private fun key(): String = PlantNetRequest.resolveKey(userKey(), BuildConfig.PLANTNET_API_KEY)

    fun reset() {
        job?.cancel()
        _state.value = S.Idle
    }

    fun start(photos: List<Photo>) {
        val apiKey = key()
        if (apiKey.isBlank() || photos.isEmpty()) {
            _state.value = S.Failed("key")
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = S.Sending
            val outcome = withContext(Dispatchers.IO) {
                runCatching { post(apiKey, photos.take(5)) }.getOrElse { S.Failed("offline") }
            }
            _state.value = outcome
        }
    }

    private fun post(apiKey: String, photos: List<Photo>): S {
        val boundary = "ow" + System.nanoTime().toString(16)
        val encoded = URLEncoder.encode(apiKey, "UTF-8")
        val conn = (URL("$ORIGIN/v2/identify/all?api-key=$encoded&no-reject=true&lang=en").openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { raw ->
                photos.forEachIndexed { i, photo ->
                    writePart(raw, boundary, "images", jpeg(photo.bitmap), "p$i.jpg")
                    writeField(raw, boundary, "organs", PlantNetRequest.organ(photo.part.name))
                }
                raw.write("--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                200 -> parse(body)
                401, 403 -> S.Failed("key")
                404 -> S.Failed("none")
                429 -> S.Failed("quota")
                else -> S.Failed("failed")
            }
        } catch (e: java.io.IOException) {
            S.Failed("offline")
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): S {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return S.Failed("failed")
        val arr = json.optJSONArray("results") ?: return S.Failed("none")
        val hits = buildList {
            for (i in 0 until minOf(5, arr.length())) {
                val row = arr.optJSONObject(i) ?: continue
                val species = row.optJSONObject("species") ?: continue
                val name = species.optString("scientificNameWithoutAuthor").ifBlank {
                    species.optString("scientificName")
                }.substringBefore(" (").trim()
                if (name.isBlank()) continue
                val commons = species.optJSONArray("commonNames")
                val common = if (commons != null && commons.length() > 0) commons.optString(0).takeIf { it.isNotBlank() } else null
                add(Hit(name, common, row.optDouble("score", 0.0)))
            }
        }
        if (hits.isEmpty()) return S.Failed("none")
        val remaining = if (json.has("remainingIdentificationRequests")) json.optInt("remainingIdentificationRequests") else null
        return S.Done(hits, remaining)
    }

    private fun writeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
    }

    private fun writePart(out: java.io.OutputStream, boundary: String, name: String, bytes: ByteArray, filename: String) {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val max = 1280
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val src = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        val bytes = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
        if (src !== bitmap) src.recycle()
        return bytes.toByteArray()
    }

    companion object {
        private const val ORIGIN = "https://my-api.plantnet.org"
    }
}

@Composable
fun PlantNetToggle(plantNet: PlantNet) {
    val on by plantNet.enabled.collectAsState()
    var key by remember { mutableStateOf(plantNet.userKey()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.plantnet_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.plantnet_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = plantNet::setEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Leaf),
            )
        }
        if (on) {
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.take(80)
                    plantNet.setUserKey(it)
                },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.plantnet_key)) },
            )
            if (plantNet.usingBuildKey()) {
                Text(
                    stringResource(R.string.plantnet_build_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.plantnet_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PlantNetCard(state: UiState, vm: AppViewModel) {
    val plantNet = vm.plantNet
    val on by plantNet.enabled.collectAsState()
    if (!on) return
    val res = state.result ?: return
    val s by plantNet.state.collectAsState()
    val context = LocalContext.current
    val fungi = state.filter == KingdomFilter.FUNGI
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.plantnet_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(if (fungi) R.string.plantnet_fungi_note else R.string.plantnet_plants_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val st = s) {
                is PlantNet.S.Idle -> {
                    Button(
                        onClick = { plantNet.start(state.photos) },
                        enabled = state.photos.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.plantnet_button)) }
                }
                is PlantNet.S.Sending -> {
                    Text(stringResource(R.string.plantnet_sending))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is PlantNet.S.Failed -> {
                    Text(stringResource(plantNetReason(st.reason)), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { plantNet.start(state.photos) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is PlantNet.S.Done -> {
                    st.hits.forEachIndexed { i, hit ->
                        val pack = vm.pack
                        val idx = pack?.indexOfScientificName(hit.scientific)
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                hit.common ?: hit.scientific,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (hit.common != null) {
                                Text(hit.scientific, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "${(hit.score * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (idx != null) {
                                TextButton(onClick = { vm.go(Screen.Species(idx, org.openwild.app.core.Tier.LOW)) }) {
                                    Text(stringResource(R.string.plantnet_in_pack))
                                }
                            }
                        }
                    }
                    st.remaining?.let {
                        Text(
                            stringResource(R.string.plantnet_remaining, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = plantNet::reset) { Text(stringResource(R.string.plantnet_clear)) }
                }
            }
            Text(
                stringResource(R.string.plantnet_credit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.plantnet_page))))
            }) { Text(stringResource(R.string.plantnet_open)) }
            Text(
                stringResource(R.string.online_never_eat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun plantNetReason(reason: String): Int = when (reason) {
    "offline" -> R.string.plantnet_offline
    "key" -> R.string.plantnet_need_key
    "quota" -> R.string.plantnet_quota
    "none" -> R.string.plantnet_none
    else -> R.string.plantnet_failed
}
