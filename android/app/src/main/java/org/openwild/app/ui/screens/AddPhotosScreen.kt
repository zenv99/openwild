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
import org.openwild.app.R
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
fun AddPhotosScreen(index: Int, vm: AppViewModel) {
    val pack = vm.pack ?: return
    val sp = pack.species
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("contribute", Context.MODE_PRIVATE) }
    val gaps = remember(index) { pack.photoGaps(index) }
    var stage by rememberSaveable { mutableStateOf(gaps.firstOrNull() ?: "closeup") }
    val staged = remember { mutableStateListOf<StagedPhoto>() }
    var credit by rememberSaveable { mutableStateOf(prefs.getString("credit", "") ?: "") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedCount by rememberSaveable { mutableStateOf(-1) }
    var pendingCamera by remember { mutableStateOf<Uri?>(null) }

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

        OutlinedTextField(
            value = credit, onValueChange = { credit = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contrib_credit)) },
            supportingText = { Text(stringResource(R.string.contrib_credit_help)) },
        )
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text(stringResource(R.string.contrib_consent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        Text(stringResource(R.string.contrib_privacy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(
            enabled = !busy && staged.isNotEmpty() && credit.isNotBlank() && consent,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                prefs.edit().putString("credit", credit.trim()).apply()
                val list = staged.toList()
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Contribute.savePhotosForContribution(context, sp.scientificNames[index], list) }
                    savedCount = n
                    busy = false
                    if (n > 0) Contribute.openPhotoContribution(context, pack.regionId, sp.scientificNames[index], credit.trim(), list)
                }
            },
        ) { Text(stringResource(R.string.contrib_submit, staged.size)) }

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
                        Contribute.openPhotoContribution(context, pack.regionId, sp.scientificNames[index], credit.trim(), staged.toList())
                    }) { Text(stringResource(R.string.contrib_open_form)) }
                }
            }
        }
    }
}

@Composable
private fun StagedThumb(context: Context, uri: Uri) {
    val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { Contribute.decodeUpright(context, uri, 240) } }
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp)) }
    }
}
