package org.openwild.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.core.RealPhotoLinks

/** True when a network with internet is connected. False if that cannot be read. Does not send anything. */
@Composable
fun rememberDeviceOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun connected(): Boolean {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = connected() }
            override fun onLost(network: Network) { online = connected() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        try {
            online = connected()
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            onDispose { cm.unregisterNetworkCallback(callback) }
        } catch (_: SecurityException) {
            online = false
            onDispose { }
        }
    }
    return online
}

/** Browser searches for this species. Hidden when the phone is offline, so the page stays the same. */
@Composable
fun SeeRealPhotos(scientificName: String) {
    if (!rememberDeviceOnline()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            stringResource(R.string.see_real_photos),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.see_real_photos_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { Contribute.open(context, RealPhotoLinks.inaturalist(scientificName)) }) {
                Text(stringResource(R.string.see_real_inat))
            }
            TextButton(onClick = { Contribute.open(context, RealPhotoLinks.gbif(scientificName)) }) {
                Text(stringResource(R.string.see_real_gbif))
            }
            TextButton(onClick = { Contribute.open(context, RealPhotoLinks.commons(scientificName)) }) {
                Text(stringResource(R.string.see_real_commons))
            }
        }
    }
}
