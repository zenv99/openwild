package org.openwild.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwild.app.OpenWildApp
import org.openwild.app.R
import org.openwild.app.core.Attribution
import org.openwild.app.ui.CreditDraft
import org.openwild.app.ui.CreditFields
import org.openwild.app.ui.PhotoInbox
import org.openwild.app.ui.ProfileSync
import org.openwild.app.ui.rememberCreditDraft
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.Contribute.StagedPhoto
import org.openwild.app.ui.PhotoWanted
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.stageLabel

private val ContributeStages = listOf("closeup", "leaves", "flower", "fruit", "bark", "seedling", "field")
private const val MAX_PHOTOS = 20

/**
 * "Add your photos": the user photographs a plant stage by stage, puts their name on it and shares it under
 * CC BY 4.0. Photos are saved to the phone without location data; the upload is a GitHub form the user
 * submits themselves. Nothing leaves the phone without those taps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddPhotosScreen(index: Int, vm: AppViewModel, initialStage: String? = null) {
    val pack = vm.pack ?: return
    val sp = pack.species
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gaps = remember(index) { pack.photoGaps(index) }
    var stage by rememberSaveable { mutableStateOf(initialStage ?: gaps.firstOrNull() ?: "closeup") }
    val staged = remember { mutableStateListOf<StagedPhoto>() }
    var credit by remember { mutableStateOf(CreditDraft()) }
    val remembered = rememberCreditDraft()
    LaunchedEffect(remembered) { if (remembered.remember || remembered.name.isNotBlank()) credit = remembered }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedCount by rememberSaveable { mutableStateOf(-1) }
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }
    var askSend by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sentCount by rememberSaveable { mutableStateOf(-1) }
    var sendError by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.take(MAX_PHOTOS - staged.size).forEach { staged += StagedPhoto(it, stage) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null && staged.size < MAX_PHOTOS) staged += StagedPhoto(u, stage)
        pendingCamera = null
    }
    fun shoot() {
        val u = Contribute.newCameraFile(context)
        pendingCamera = u
        camera.launch(u)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot() }

    ScrollColumn {
        Text(stringResource(R.string.contrib_title), style = MaterialTheme.typography.headlineSmall)
        Text(sp.commonNames[index] ?: sp.scientificNames[index], style = MaterialTheme.typography.titleMedium)
        if (sp.commonNames[index] != null) Text(sp.scientificNames[index], fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Why and how: get close, every stage, many angles.
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(PhotoWanted.copy(alpha = 0.12f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.contrib_why), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.contrib_tips), style = MaterialTheme.typography.bodySmall)
            if (gaps.isNotEmpty()) {
                Text(
                    stringResource(R.string.contrib_needed, gaps.map { stageLabel(it) }.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = PhotoWanted,
                )
            }
        }

        Text(stringResource(R.string.contrib_stage_prompt), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ContributeStages.forEach { s ->
                val n = staged.count { it.stage == s }
                FilterChip(
                    selected = stage == s, onClick = { stage = s },
                    label = { Text(stageLabel(s) + if (n > 0) " · $n" else if (s in gaps) " ★" else "") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot()
                    else askCamera.launch(Manifest.permission.CAMERA)
                },
                enabled = staged.size < MAX_PHOTOS, modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.contrib_take))
            }
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = staged.size < MAX_PHOTOS, modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.contrib_gallery))
            }
        }

        if (staged.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                staged.forEachIndexed { k, p ->
                    Box {
                        StagedThumb(context, p.uri)
                        Text(
                            stageLabel(p.stage), color = Color.White, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
                        )
                        IconButton(onClick = { staged.removeAt(k) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                            Icon(Icons.Filled.Close, stringResource(R.string.contrib_remove), tint = Color.White,
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)))
                        }
                    }
                }
            }
        }

        CreditFields(credit, onChange = { credit = it }, showLicense = true, line = credit.photoLine())
        val creditName = Attribution.creditName(credit.name)
        val licenseLabel = (org.openwild.app.core.PhotoLicense.fromCode(credit.license) ?: org.openwild.app.core.PhotoLicense.DEFAULT).label
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text(stringResource(R.string.contrib_consent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        Text(stringResource(R.string.contrib_privacy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val ready = !busy && staged.isNotEmpty() && consent
        if (PhotoInbox.enabled) {
            // Direct send, after asking permission. The GitHub form stays available as an alternative.
            Button(enabled = ready && sentCount < 0, modifier = Modifier.fillMaxWidth(), onClick = { askSend = true }) {
                Text(stringResource(R.string.inbox_send, staged.size))
            }
            progress?.let { (done, total) ->
                Text(stringResource(R.string.inbox_sending, done, total), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { done / total.toFloat().coerceAtLeast(1f) }, modifier = Modifier.fillMaxWidth())
            }
            sendError?.let { Text(stringResource(R.string.inbox_failed, it), color = MaterialTheme.colorScheme.error) }
            if (sentCount > 0) {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.inbox_done_title, sentCount), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.inbox_done_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val formButton: @Composable () -> Unit = {
            Text(stringResource(if (PhotoInbox.enabled) R.string.contrib_submit_form else R.string.contrib_submit, staged.size))
        }
        val formClick = {
            busy = true
            val list = staged.toList()
            val draft = credit
            scope.launch {
                val n = withContext(Dispatchers.IO) {
                    queuePlantPhoto(context, pack.regionId, sp.scientificNames[index], draft, list)
                    Contribute.savePhotosForContribution(context, sp.scientificNames[index], list)
                }
                savedCount = n
                busy = false
                if (n > 0) Contribute.openPhotoContribution(context, pack.regionId, sp.scientificNames[index], "$creditName, $licenseLabel", list)
            }
            Unit
        }
        if (PhotoInbox.enabled) {
            // Direct send is the way: the GitHub form can't receive photos from the app. Kept only as a fallback
            // after a failed send.
            if (sendError != null) TextButton(enabled = ready, onClick = formClick, modifier = Modifier.fillMaxWidth()) { formButton() }
        } else {
            Button(enabled = ready, onClick = formClick, modifier = Modifier.fillMaxWidth()) { formButton() }
        }

        if (askSend) {
            AlertDialog(
                onDismissRequest = { askSend = false },
                title = { Text(stringResource(R.string.inbox_ask_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.inbox_ask_sent, staged.size, sp.scientificNames[index], creditName))
                        Text(stringResource(R.string.inbox_ask_not_sent), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.inbox_ask_what_happens), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        askSend = false
                        busy = true
                        sendError = null
                        val list = staged.toList()
                        val draft = credit
                        progress = 0 to list.size
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    queuePlantPhoto(context, pack.regionId, sp.scientificNames[index], draft, list)
                                    PhotoInbox.send(context, pack.regionId, sp.scientificNames[index], creditName, list, licenseLabel) { d, t ->
                                        progress = d to t
                                    }
                                }
                            }
                            busy = false
                            progress = null
                            r.onSuccess { sentCount = it; if (it > 0) staged.clear() }
                            r.onFailure { sendError = it.message ?: "no connection" }
                        }
                    }) { Text(stringResource(R.string.inbox_ask_yes)) }
                },
                dismissButton = { TextButton(onClick = { askSend = false }) { Text(stringResource(R.string.inbox_ask_no)) } },
            )
        }

        if (savedCount >= 0) {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (savedCount == 0) {
                    Text(stringResource(R.string.contrib_save_failed))
                } else {
                    Text(stringResource(R.string.contrib_next_title, savedCount), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.contrib_next_steps), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        Contribute.openPhotoContribution(context, pack.regionId, sp.scientificNames[index], "$creditName, $licenseLabel", staged.toList())
                    }) { Text(stringResource(R.string.contrib_open_form)) }
                }
            }
        }
    }
}

private fun queuePlantPhoto(
    context: Context,
    regionId: String,
    species: String,
    draft: CreditDraft,
    photos: List<Contribute.StagedPhoto>,
) {
    val store = (context.applicationContext as OpenWildApp).credits
    store.enqueue(
        kind = "photo",
        species = species,
        regionId = regionId,
        body = photos.joinToString(",") { it.stage },
        rating = 0,
        typedName = draft.name,
        title = draft.title,
        affiliation = draft.affiliation,
        licenseCode = draft.license,
        avatarJpeg = draft.avatarJpeg,
    )
    runCatching { ProfileSync.sync(context) }
}

@Composable
private fun StagedThumb(context: Context, uri: Uri) {
    val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { Contribute.decodeUpright(context, uri, 240) } }
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp)) }
    }
}
