package org.openwild.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openwild.app.R

/**
 * Asks permission, then sends a small piece of feedback (a yes/no on a photo, a review) to the OpenWild
 * inbox. Shows exactly what goes. "Don't ask again" is offered only for yes/no answers, and only for those.
 */
class FeedbackSender internal constructor(
    private val context: Context,
    private val ask: (Pending) -> Unit,
) {
    data class Pending(val regionId: String, val species: String, val kind: String, val fields: JSONObject, val summary: String)

    val enabled: Boolean get() = PhotoInbox.enabled

    fun send(regionId: String, species: String, kind: String, fields: JSONObject, summary: String) {
        val p = Pending(regionId, species, kind, fields, summary)
        val prefs = context.getSharedPreferences("contribute", Context.MODE_PRIVATE)
        if (kind == "vote" && prefs.getBoolean("votes_ok", false)) go(p) else ask(p)
    }

    internal fun go(p: Pending) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) { runCatching { PhotoInbox.sendFeedback(p.regionId, p.species, p.kind, p.fields) }.isSuccess }
            Toast.makeText(context, context.getString(if (ok) R.string.feedback_sent else R.string.feedback_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberFeedbackSender(): FeedbackSender {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<FeedbackSender.Pending?>(null) }
    val sender = remember { FeedbackSender(context) { pending = it } }
    pending?.let { p ->
        var remember by remember(p) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.feedback_ask_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.feedback_ask_sent, p.summary))
                    Text(stringResource(R.string.feedback_ask_not_sent), style = MaterialTheme.typography.bodySmall)
                    if (p.kind == "vote") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = remember, onCheckedChange = { remember = it })
                            Text(stringResource(R.string.feedback_votes_remember), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (remember) context.getSharedPreferences("contribute", Context.MODE_PRIVATE).edit().putBoolean("votes_ok", true).apply()
                    pending = null
                    sender.go(p)
                }) { Text(stringResource(R.string.inbox_ask_yes)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.inbox_ask_no)) } },
        )
    }
    return sender
}
