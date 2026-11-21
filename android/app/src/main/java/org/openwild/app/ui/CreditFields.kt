package org.openwild.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwild.app.OpenWildApp
import org.openwild.app.R
import org.openwild.app.core.Attribution
import org.openwild.app.core.PhotoLicense

/** What the send form is showing. Remember starts off. */
data class CreditDraft(
    val name: String = "",
    val title: String = "",
    val affiliation: String = "",
    val remember: Boolean = false,
    val avatarJpeg: ByteArray? = null,
    val avatarPreview: Bitmap? = null,
    val detailsOpen: Boolean = false,
    val license: String = PhotoLicense.CC_BY.code,
) {
    fun creditName(): String = Attribution.creditName(name)

    fun photoLine(): String = Attribution.photoCredit(creditName(), PhotoLicense.fromCode(license) ?: PhotoLicense.DEFAULT)

    fun reviewLine(): String = Attribution.reviewCredit(creditName())

    fun noteLine(): String = Attribution.feedbackCredit(creditName())
}

@Composable
fun rememberCreditDraft(): CreditDraft {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(CreditDraft()) }
    LaunchedEffect(Unit) {
        draft = withContext(Dispatchers.IO) {
            val saved = (context.applicationContext as OpenWildApp).credits.remembered()
            if (saved == null) CreditDraft() else CreditDraft(
                name = saved.name,
                title = saved.title,
                affiliation = saved.affiliation,
                remember = true,
                avatarJpeg = saved.avatarJpeg,
                detailsOpen = saved.title.isNotBlank() || saved.affiliation.isNotBlank(),
            )
        }
    }
    return draft
}

/**
 * Optional name on a send form. Title and affiliation stay collapsed until opened.
 * "Remember my name" is unchecked until the person checks it. Unchecking wipes the saved copy.
 */
@Composable
fun CreditFields(
    draft: CreditDraft,
    onChange: (CreditDraft) -> Unit,
    showLicense: Boolean = false,
    line: String = draft.photoLine(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun apply(next: CreditDraft) {
        val wiped = draft.remember && !next.remember
        val shown = if (wiped) CreditDraft(license = next.license) else next
        onChange(shown)
        scope.launch(Dispatchers.IO) {
            val store = (context.applicationContext as OpenWildApp).credits
            if (wiped) store.clearSaved()
            else if (shown.remember) store.remember(shown.name, shown.title, shown.affiliation, shown.avatarJpeg)
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { AvatarImage.fromUri(context, uri) } ?: return@launch
            apply(draft.copy(avatarJpeg = jpeg, avatarPreview = null))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { AvatarImage.fromUri(context, uri) } ?: return@launch
            apply(draft.copy(avatarJpeg = jpeg, avatarPreview = null))
        }
    }
    CreditFieldsBody(
        draft = draft,
        onChange = ::apply,
        showLicense = showLicense,
        line = line,
        onCamera = {
            val uri = Contribute.newCameraFile(context)
            cameraUri = uri
            camera.launch(uri)
        },
        onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreditFieldsBody(
    draft: CreditDraft,
    onChange: (CreditDraft) -> Unit,
    showLicense: Boolean = false,
    line: String = draft.photoLine(),
    onCamera: () -> Unit = {},
    onGallery: () -> Unit = {},
) {
    val preview = draft.avatarPreview ?: draft.avatarJpeg?.let { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    val named = !Attribution.isAnonymous(draft.name)
    fun apply(next: CreditDraft) = onChange(next)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { apply(draft.copy(name = it.take(60))) },
            label = { Text(stringResource(R.string.credit_name)) },
            supportingText = { Text(stringResource(R.string.credit_name_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { apply(draft.copy(detailsOpen = !draft.detailsOpen)) }) {
            Text(stringResource(if (draft.detailsOpen) R.string.credit_details_hide else R.string.credit_details_show))
        }
        if (draft.detailsOpen) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { apply(draft.copy(title = it.take(80))) },
                label = { Text(stringResource(R.string.credit_title)) },
                supportingText = { Text(stringResource(R.string.credit_title_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.affiliation,
                onValueChange = { apply(draft.copy(affiliation = it.take(120))) },
                label = { Text(stringResource(R.string.credit_affiliation)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.remember, onCheckedChange = { apply(draft.copy(remember = it)) })
            Text(stringResource(R.string.credit_remember))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarCircle(preview = if (named) preview else null, name = if (named) draft.name else "")
            OutlinedButton(onClick = onCamera) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                Text(stringResource(R.string.credit_camera))
            }
            OutlinedButton(onClick = onGallery) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                Text(stringResource(R.string.credit_gallery))
            }
        }
        Text(stringResource(R.string.credit_avatar_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showLicense) {
            Text(stringResource(R.string.credit_license_help), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoLicense.entries.forEach { license ->
                    FilterChip(
                        selected = draft.license == license.code,
                        onClick = { apply(draft.copy(license = license.code)) },
                        label = { Text(license.label) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (named && preview != null) AvatarCircle(preview = preview, name = draft.name)
            Text(line, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AvatarCircle(preview: Bitmap?, name: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(preview.asImageBitmap(), null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
        } else {
            val initials = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
            Text(initials, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

fun queueCredit(
    context: android.content.Context,
    kind: String,
    regionId: String,
    species: String,
    body: String,
    rating: Int,
    draft: CreditDraft,
    shareLocation: Boolean = false,
    longitude: Double? = null,
    latitude: Double? = null,
) {
    val store = (context.applicationContext as OpenWildApp).credits
    store.enqueue(
        kind = kind,
        species = species,
        regionId = regionId,
        body = body,
        rating = rating,
        typedName = draft.name,
        title = draft.title,
        affiliation = draft.affiliation,
        licenseCode = draft.license,
        avatarJpeg = draft.avatarJpeg,
        shareLocation = shareLocation,
        longitude = longitude,
        latitude = latitude,
    )
    runCatching { ProfileSync.sync(context) }
}

/** A send form the screenshots can render on their own. */
@Composable
fun PhotoSendForm(draft: CreditDraft, onChange: (CreditDraft) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contrib_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.credit_send_intro), style = MaterialTheme.typography.bodyMedium)
        CreditFieldsBody(draft, onChange, showLicense = true, line = draft.photoLine())
        Text(stringResource(R.string.footer), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    }
}
