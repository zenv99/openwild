package org.openwild.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openwild.app.R
import org.openwild.app.core.NameSuggest
import org.openwild.app.pack.Location
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * "This identification was wrong." The sheet always offers a place to type what the plant is.
 * Sending is explicit. If the inbox cannot be reached, the report waits on the phone.
 */
class ReportSender internal constructor(private val ask: (Request) -> Unit) {
    data class Request(
        val regionId: String, val predicted: String, val chosen: String?, val fields: JSONObject,
        val photos: List<Photo>,
    )

    val enabled: Boolean get() = PhotoInbox.enabled

    fun send(r: Request) = ask(r)
}

@Composable
fun rememberReportSender(
    onSent: () -> Unit = {},
    suggestions: (String) -> List<NameSuggest.Hit> = { emptyList() },
): ReportSender {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ReportSender.Request?>(null) }
    val sender = remember { ReportSender { pending = it } }
    pending?.let { p ->
        val prefs = context.getSharedPreferences("contribute", Context.MODE_PRIVATE)
        var guess by remember(p) { mutableStateOf(p.chosen ?: "") }
        var note by remember(p) { mutableStateOf("") }
        var credit by remember(p) { mutableStateOf(prefs.getString("credit", "") ?: "") }
        var withPhotos by remember(p) { mutableStateOf(p.photos.isNotEmpty()) }
        var shareLocation by remember(p) { mutableStateOf(false) }
        val ideas = suggestions(guess)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.report_ask_title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.report_optimize))
                    Text(
                        stringResource(R.string.report_ask_body, p.predicted),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = guess,
                        onValueChange = { guess = it.take(120) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.report_guess)) },
                    )
                    ideas.forEach { hit ->
                        TextButton(onClick = { guess = hit.scientific }, modifier = Modifier.fillMaxWidth()) {
                            Text(hit.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.report_note)) },
                        minLines = 2,
                    )
                    if (p.photos.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withPhotos, onCheckedChange = { withPhotos = it })
                            Text(stringResource(R.string.report_with_photos, p.photos.size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = shareLocation, onCheckedChange = { shareLocation = it })
                        Text(stringResource(R.string.report_location_opt_in), style = MaterialTheme.typography.bodySmall)
                    }
                    if (withPhotos) {
                        OutlinedTextField(
                            value = credit, onValueChange = { credit = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.report_credit)) },
                        )
                    }
                    Text(stringResource(R.string.report_ask_not_sent), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val req = p
                    val guessNow = guess.trim()
                    val noteNow = note.trim()
                    val creditNow = credit.trim()
                    val photosOn = withPhotos
                    val locOn = shareLocation
                    pending = null
                    if (creditNow.isNotBlank()) prefs.edit().putString("credit", creditNow).apply()
                    val fields = JSONObject(req.fields.toString())
                        .put("predicted", req.predicted)
                        .put("chosen", req.chosen ?: "")
                        .put("guess", guessNow)
                        .put("suggested", guessNow)
                        .put("note", noteNow)
                        .put("credit", creditNow.ifBlank { "Anonymous" })
                        .put("share_location", locOn)
                    if (locOn && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        Location.lastKnown(context)?.let { (lon, lat) ->
                            fields.put("lon", round1(lon)).put("lat", round1(lat))
                        }
                    }
                    val photos = if (photosOn) req.photos.map { it.bitmap to it.part.name.lowercase() } else emptyList()
                    CoroutineScope(Dispatchers.Main).launch {
                        val sent = withContext(Dispatchers.IO) {
                            val delivered = PhotoInbox.enabled && runCatching {
                                PhotoInbox.sendReport(req.regionId, req.chosen ?: guessNow, fields, photos) { _, _ -> }
                            }.isSuccess
                            if (!delivered) ReportOutbox.enqueue(context, req.regionId, req.chosen ?: guessNow, fields, photos)
                            delivered
                        }
                        val msg = if (sent) R.string.report_sent else R.string.report_queued
                        Toast.makeText(context, context.getString(msg), Toast.LENGTH_LONG).show()
                        onSent()
                    }
                }) { Text(stringResource(R.string.inbox_ask_yes)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.inbox_ask_no)) } },
        )
    }
    return sender
}

private fun round1(v: Double): Double = round(v * 10.0) / 10.0

/** What a wrong-ID report carries besides the person's own words. No coordinates. */
fun wrongIdFields(res: IdResult, names: Array<String>, packVersion: Int, modelId: String, kind: String): JSONObject {
    val top = res.candidates.take(10).joinToString("; ") { c ->
        "${names[c.index]} ${(c.prob * 100).roundToInt()}%"
    }
    val emb = JSONArray()
    for (e in res.embeddings) {
        val row = JSONArray()
        for (x in e) row.put(round(x * 10000f) / 10000.0)
        emb.put(row)
    }
    return JSONObject()
        .put("kind", kind)
        .put("score", res.result.score)
        .put("tier", res.result.tier.name)
        .put("top_matches", top)
        .put("pack_version", packVersion)
        .put("model", modelId)
        .put("brain", res.brain)
        .put("brain_requested", res.brainRequested)
        .put("scan_detail", res.scanDetail)
        .put("embeddings", emb)
}
