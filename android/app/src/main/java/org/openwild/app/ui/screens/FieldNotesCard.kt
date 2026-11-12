package org.openwild.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwild.app.R
import org.openwild.app.core.FieldNote
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.SectionCard
import org.openwild.app.ui.UiState
import org.openwild.app.ui.theme.Caution

/** Ratings, a written note, and photos. Stored on the phone. Submit is a share sheet the user has to tap. */
@Composable
fun FieldNotesCard(scientificName: String, regionName: String?, state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    LaunchedEffect(scientificName) { vm.loadFieldNote(scientificName) }
    val note = state.fieldNote?.takeIf { it.scientificName == scientificName } ?: FieldNote(scientificName)

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addFieldPhoto(scientificName, uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) vm.addFieldPhotoBitmap(scientificName, bmp)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null)
    }

    SectionCard(stringResource(R.string.notes_title), Icons.Filled.RateReview, MaterialTheme.colorScheme.primary) {
        Text(stringResource(R.string.notes_body))
        Text(stringResource(R.string.notes_safety), color = Caution, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (star in 1..5) {
                IconButton(onClick = {
                    val next = if (note.rating == star) 0 else star
                    vm.setFieldRating(scientificName, next)
                }) {
                    Icon(
                        if (star <= note.rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.notes_star, star),
                        tint = if (star <= note.rating) Caution else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedTextField(
            value = note.text,
            onValueChange = { vm.setFieldText(scientificName, it) },
            placeholder = { Text(stringResource(R.string.notes_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        if (note.photos.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                note.photos.forEach { fileName ->
                    NotePhoto(vm, fileName) { vm.removeFieldPhoto(scientificName, fileName) }
                }
            }
        }
        if (note.photos.size < FieldNote.MAX_PHOTOS) {
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                Text(stringResource(R.string.notes_add_photo))
            }
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) camera.launch(null) else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp))
                Text(stringResource(R.string.notes_take_photo))
            }
        }
        TextButton(
            onClick = {
                val files = note.photos.map { vm.fieldPhotoFile(it) }.filter { it.exists() }
                Contribute.submitFieldNote(context, note, regionName, files)
            },
            enabled = note.hasContent(),
        ) { Text(stringResource(R.string.notes_submit)) }
        Text(stringResource(R.string.notes_submit_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { Contribute.submitFieldNoteIssue(context, note, regionName) },
            enabled = note.hasContent(),
        ) { Text(stringResource(R.string.notes_github)) }
    }
}

@Composable
private fun NotePhoto(vm: AppViewModel, fileName: String, onRemove: () -> Unit) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, fileName) {
        value = withContext(Dispatchers.IO) {
            val file = vm.fieldPhotoFile(fileName)
            if (!file.exists()) null
            else try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(12.dp))) {
            bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp)) }
        }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.notes_remove_photo)) }
    }
}
