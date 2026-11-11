@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.openwild.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.openwild.app.R
import org.openwild.app.core.KingdomFilter
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.PhotoPart
import org.openwild.app.ui.UiState
import java.io.File

@Composable
fun CaptureScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA) }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AppViewModel.MAX_PHOTOS),
    ) { uris -> uris.forEach { vm.addPhoto(it) } }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val full = state.photos.size >= AppViewModel.MAX_PHOTOS

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.what_is_it), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                KingdomFilter.ANY to R.string.kind_any,
                KingdomFilter.PLANTS to R.string.kind_plant,
                KingdomFilter.FUNGI to R.string.kind_fungus,
            ).forEach { (f, label) ->
                FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(stringResource(label)) })
            }
        }
        Text(
            stringResource(if (state.filter == KingdomFilter.FUNGI) R.string.hint_fungus else R.string.hint_plant),
            style = MaterialTheme.typography.bodySmall,
        )

        if (hasCamera) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
            }
            DisposableEffect(Unit) {
                onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
            }
        } else {
            Text(stringResource(R.string.camera_permission), style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = hasCamera && !full,
                onClick = {
                    val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                vm.addPhoto(Uri.fromFile(file))
                            }
                            override fun onError(exception: ImageCaptureException) {}
                        },
                    )
                },
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null)
                Text(" " + stringResource(R.string.take_photo))
            }
            OutlinedButton(
                enabled = !full,
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Text(" " + stringResource(R.string.from_gallery))
            }
        }

        state.photos.forEachIndexed { i, photo ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = photo.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.photo_part), style = MaterialTheme.typography.labelSmall)
                    val fungusParts = state.filter == KingdomFilter.FUNGI
                    val parts = when (state.filter) {
                        KingdomFilter.ANY -> PhotoPart.entries.toList()
                        else -> PhotoPart.entries.filter { it.forFungus == fungusParts }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        parts.forEach { part ->
                            FilterChip(
                                selected = photo.part == part,
                                onClick = { vm.setPart(i, part) },
                                label = { Text(stringResource(part.label)) },
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.removePhoto(i) }) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
        }

        Button(
            onClick = { vm.identify() },
            enabled = state.photos.isNotEmpty() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(" " + stringResource(R.string.identifying))
            } else {
                Text(stringResource(R.string.identify) + " (${state.photos.size}/${AppViewModel.MAX_PHOTOS})")
            }
        }
    }
}
