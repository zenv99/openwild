package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwild.app.pack.RegionPack
import org.openwild.app.ui.AppViewModel

/** Attribution for every reference photo in the active pack (required by the photos' CC licenses). */
@Composable
fun CreditsScreen(vm: AppViewModel) {
    val pack = vm.pack ?: return
    var credits by remember { mutableStateOf<List<RegionPack.Credit>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    LaunchedEffect(pack.regionId) {
        withContext(Dispatchers.IO) {
            total = pack.creditCount()
            credits = pack.credits(limit = 2000)
        }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            Text(
                "${pack.regionName}: $total reference photos from the iNaturalist community and other open sources. " +
                    "Teacher model: BioCLIP 2 (Imageomics, MIT). Species lists: GBIF.org.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        items(credits) { c ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(c.species, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                Text("© ${c.observer ?: "unknown"} · ${c.license.uppercase()} · ${c.url}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
