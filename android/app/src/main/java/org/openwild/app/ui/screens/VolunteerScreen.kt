package org.openwild.app.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.ui.Footer
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.VolunteerHelp
import org.openwild.app.ui.theme.Leaf

/**
 * Ways to help OpenWild. The whole screen is on the phone. A button opens the browser
 * with Intent.ACTION_VIEW through [LocalUriHandler] only after a tap.
 */
@Composable
fun VolunteerScreen(repoUrl: String = org.openwild.app.BuildConfig.REPO_URL) {
    val uriHandler = LocalUriHandler.current
    ScrollColumn {
        Text(
            stringResource(R.string.volunteer_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.volunteer_easiest_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Leaf,
        )
        Text(stringResource(R.string.volunteer_easiest_body))
        VolunteerWay(
            label = stringResource(R.string.volunteer_photos),
            body = stringResource(R.string.volunteer_photos_body),
            icon = Icons.Filled.PhotoCamera,
            onOpen = { openVolunteerLink(uriHandler, VolunteerHelp.photos(repoUrl)) },
        )
        VolunteerWay(
            label = stringResource(R.string.volunteer_safety),
            body = stringResource(R.string.volunteer_safety_body),
            icon = Icons.Filled.HealthAndSafety,
            onOpen = { openVolunteerLink(uriHandler, VolunteerHelp.safety(repoUrl)) },
        )
        VolunteerWay(
            label = stringResource(R.string.volunteer_translate),
            body = stringResource(R.string.volunteer_translate_body),
            icon = Icons.Filled.Translate,
            onOpen = { openVolunteerLink(uriHandler, VolunteerHelp.translate(repoUrl)) },
        )
        VolunteerWay(
            label = stringResource(R.string.volunteer_bug),
            body = stringResource(R.string.volunteer_bug_body),
            icon = Icons.Filled.BugReport,
            onOpen = { openVolunteerLink(uriHandler, VolunteerHelp.bug(repoUrl)) },
        )
        VolunteerWay(
            label = stringResource(R.string.volunteer_code),
            body = stringResource(R.string.volunteer_code_body),
            icon = Icons.Filled.Code,
            onOpen = { openVolunteerLink(uriHandler, VolunteerHelp.code(repoUrl)) },
        )
        Footer()
    }
}

@Composable
private fun VolunteerWay(label: String, body: String, icon: ImageVector, onOpen: () -> Unit) {
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** LocalUriHandler sends ACTION_VIEW. A missing browser must not crash the screen. */
internal fun openVolunteerLink(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    runCatching { uriHandler.openUri(url) }
}
