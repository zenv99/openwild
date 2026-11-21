package org.openwild.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.openwild.app.R
import org.openwild.app.core.NameSuggest
import org.openwild.app.core.ReportPhotoChoice
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * "This identification was wrong." One optional name, a photo tick, and remember.
 * Diagnostic fields are already on the request. The photo is attached only when the box is ticked.
 * If the inbox cannot be reached, the report waits on the phone.
 */
class ReportSender internal constructor(private val ask: (Request) -> Unit) {
    data class Request(
        val regionId: String, val predicted: String, val chosen: String?, val fields: JSONObject,
        val photos: List<Photo>,
    )

    val enabled: Boolean get() = PhotoInbox.enabled

    fun send(r: Request) = ask(r)
}

/** The same prefs file the rest of contribution uses. */
object ReportPhotoPrefs {
    const val FILE = "contribute"
    private const val KEY_REMEMBER = "remember_send_photo"
    private const val KEY_SEND = "send_my_photo"

    fun read(prefs: SharedPreferences): ReportPhotoChoice.Saved = ReportPhotoChoice.Saved(
        remember = prefs.getBoolean(KEY_REMEMBER, false),
        sendPhoto = prefs.getBoolean(KEY_SEND, false),
    )

    fun write(prefs: SharedPreferences, saved: ReportPhotoChoice.Saved) {
        prefs.edit()
            .putBoolean(KEY_REMEMBER, saved.remember)
            .putBoolean(KEY_SEND, saved.sendPhoto)
            .apply()
    }
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
        val prefs = context.getSharedPreferences(ReportPhotoPrefs.FILE, Context.MODE_PRIVATE)
        val stored = remember(p) { ReportPhotoPrefs.read(prefs) }
        var guess by remember(p) { mutableStateOf(p.chosen ?: "") }
        var sendPhoto by remember(p) {
            mutableStateOf(ReportPhotoChoice.initialSendPhoto(stored) && p.photos.isNotEmpty())
        }
        var rememberChoice by remember(p) { mutableStateOf(ReportPhotoChoice.initialRemember(stored)) }
        val ideas = suggestions(guess).filter { it.scientific != guess.trim() }.take(3)
        WrongIdDialog(
            predicted = p.predicted,
            guess = guess,
            onGuess = { guess = it.take(120) },
            suggestions = ideas,
            showPhotoChoice = p.photos.isNotEmpty(),
            sendPhoto = sendPhoto,
            onSendPhoto = { sendPhoto = it },
            rememberChoice = rememberChoice,
            onRemember = { rememberChoice = it },
            onDismiss = { pending = null },
            onSend = {
                val req = p
                val guessNow = guess
                val photoOn = sendPhoto && req.photos.isNotEmpty()
                val rememberNow = rememberChoice
                pending = null
                if (req.photos.isNotEmpty()) {
                    ReportPhotoPrefs.write(prefs, ReportPhotoChoice.afterSend(rememberNow, photoOn))
                }
                val fields = applyReportFields(req.fields, req.predicted, req.chosen, guessNow, photoOn)
                val photos = ReportPhotoChoice.attachments(photoOn, req.photos.map { it.bitmap to it.part.name.lowercase() })
                CoroutineScope(Dispatchers.Main).launch {
                    val sent = withContext(Dispatchers.IO) {
                        val delivered = PhotoInbox.enabled && runCatching {
                            PhotoInbox.sendReport(req.regionId, req.chosen ?: guessNow.trim(), fields, photos) { _, _ -> }
                        }.isSuccess
                        if (!delivered) ReportOutbox.enqueue(context, req.regionId, req.chosen ?: guessNow.trim(), fields, photos)
                        delivered
                    }
                    val msg = if (sent) R.string.report_sent else R.string.report_queued
                    Toast.makeText(context, context.getString(msg), Toast.LENGTH_LONG).show()
                    onSent()
                }
            },
        )
    }
    return sender
}

@Composable
internal fun WrongIdDialog(
    predicted: String,
    guess: String,
    onGuess: (String) -> Unit,
    suggestions: List<NameSuggest.Hit>,
    showPhotoChoice: Boolean,
    sendPhoto: Boolean,
    onSendPhoto: (Boolean) -> Unit,
    rememberChoice: Boolean,
    onRemember: (Boolean) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_ask_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.report_ask_body, predicted),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = guess,
                    onValueChange = onGuess,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.report_guess)) },
                )
                suggestions.forEach { hit ->
                    TextButton(onClick = { onGuess(hit.scientific) }, modifier = Modifier.fillMaxWidth()) {
                        Text(hit.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showPhotoChoice) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onSendPhoto(!sendPhoto) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sendPhoto, onCheckedChange = onSendPhoto)
                        Text(stringResource(R.string.report_send_photo), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { onRemember(!rememberChoice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rememberChoice, onCheckedChange = onRemember)
                        Text(stringResource(R.string.report_remember), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.report_photo_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onSend) { Text(stringResource(R.string.inbox_ask_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Copies the diagnostic payload and adds what the person typed. Location is never added. */
fun applyReportFields(
    base: JSONObject,
    predicted: String,
    chosen: String?,
    guess: String,
    sendPhoto: Boolean,
): JSONObject {
    val out = JSONObject(base.toString())
    out.remove("lat")
    out.remove("lon")
    for ((k, v) in ReportPhotoChoice.fieldUpdates(predicted, chosen, guess, sendPhoto)) {
        when (v) {
            is Boolean -> out.put(k, v)
            else -> out.put(k, v.toString())
        }
    }
    return out
}

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
