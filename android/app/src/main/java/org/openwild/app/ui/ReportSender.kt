package org.openwild.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.json.JSONObject
import org.openwild.app.R

/**
 * "This ID was wrong" / "it was actually X", sent WITH the photos so the app can learn: asks permission
 * first and lists exactly what goes. Anonymous by default; the user may add the name they want credited.
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
fun rememberReportSender(onSent: () -> Unit = {}): ReportSender {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ReportSender.Request?>(null) }
    val sender = remember { ReportSender { pending = it } }
    pending?.let { p ->
        val prefs = context.getSharedPreferences("contribute", Context.MODE_PRIVATE)
        var guess by remember(p) { mutableStateOf("") }
        var credit by remember(p) { mutableStateOf(prefs.getString("credit", "") ?: "") }
        var withPhotos by remember(p) { mutableStateOf(p.photos.isNotEmpty()) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.report_ask_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.report_ask_body, p.predicted, p.chosen ?: stringResource(R.string.report_unknown)))
                    if (p.chosen == null) {
                        OutlinedTextField(
                            value = guess, onValueChange = { guess = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.report_guess)) },
                        )
                    }
                    if (p.photos.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withPhotos, onCheckedChange = { withPhotos = it })
                            Text(stringResource(R.string.report_with_photos, p.photos.size), style = MaterialTheme.typography.bodySmall)
                        }
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
                    pending = null
                    if (credit.isNotBlank()) prefs.edit().putString("credit", credit.trim()).apply()
                    val fields = JSONObject(req.fields.toString())
                        .put("predicted", req.predicted)
                        .put("chosen", req.chosen ?: "")
                        .put("guess", guess.trim())
                        .put("credit", credit.trim().ifBlank { "Anonymous" })
                    val photos = if (withPhotos) req.photos.map { it.bitmap to it.part.name.lowercase() } else emptyList()
                    CoroutineScope(Dispatchers.Main).launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { PhotoInbox.sendReport(req.regionId, req.chosen ?: guess.trim(), fields, photos) { _, _ -> } }.isSuccess
                        }
                        Toast.makeText(context, context.getString(if (ok) R.string.report_sent else R.string.feedback_failed), Toast.LENGTH_LONG).show()
                        if (ok) onSent()
                    }
                }) { Text(stringResource(R.string.inbox_ask_yes)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.inbox_ask_no)) } },
        )
    }
    return sender
}
