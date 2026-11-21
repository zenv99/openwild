package org.openwild.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.openwild.app.R
import org.openwild.app.core.DownloadCaution

/** Asks before a large region download. Small packs start immediately. */
class DownloadGate {
    var request by mutableStateOf<Pair<Long, () -> Unit>?>(null)
        private set

    fun ask(bytes: Long, go: () -> Unit) {
        if (DownloadCaution.needsWarning(bytes)) request = bytes to go else go()
    }

    fun dismiss() {
        request = null
    }

    fun confirm() {
        val go = request?.second
        request = null
        go?.invoke()
    }
}

@Composable
fun rememberDownloadGate(): DownloadGate = remember { DownloadGate() }

@Composable
fun DownloadGateDialog(gate: DownloadGate) {
    val pending = gate.request ?: return
    AlertDialog(
        onDismissRequest = gate::dismiss,
        title = { Text(stringResource(R.string.wifi_warn_title)) },
        text = { Text(stringResource(R.string.wifi_warn_body, DownloadCaution.format(pending.first))) },
        confirmButton = { TextButton(onClick = gate::confirm) { Text(stringResource(R.string.wifi_warn_go)) } },
        dismissButton = { TextButton(onClick = gate::dismiss) { Text(stringResource(R.string.not_now)) } },
    )
}
