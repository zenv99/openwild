@file:OptIn(ExperimentalMaterial3Api::class)

package org.openwild.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import org.openwild.app.R
import org.openwild.app.core.KingdomFilter
import org.openwild.app.ui.AppViewModel
import org.openwild.app.ui.Photo
import org.openwild.app.ui.PhotoPart
import org.openwild.app.ui.UiState
import org.openwild.app.ui.theme.ForestDeep
import org.openwild.app.ui.theme.Lime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(90)
            .build()
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var bindAttempt by remember { mutableIntStateOf(0) }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val previewHolder = remember { arrayOfNulls<PreviewView>(1) }
    val binding = remember { AtomicBoolean(false) }
    val full = state.photos.size >= AppViewModel.MAX_PHOTOS

    DisposableEffect(bindAttempt) {
        onDispose {
            cameraReady = false
            binding.set(false)
            runCatching { providerHolder[0]?.unbindAll() }
            providerHolder[0] = null
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val kinds = listOf(
            KingdomFilter.ANY to R.string.kind_any,
            KingdomFilter.PLANTS to R.string.kind_plant,
            KingdomFilter.FUNGI to R.string.kind_fungus,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, (f, label) ->
                SegmentedButton(
                    selected = state.filter == f,
                    onClick = { vm.setFilter(f) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = kinds.size),
                ) { Text(stringResource(label)) }
            }
        }

        // The viewfinder is NOT inside a vertical scroll. Scrolling was destroying the camera
        // surface, and binding before the view was attached left ImageCapture unbound, so the
        // shutter did nothing (the error callback was also empty).
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCamera) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    update = { view ->
                        fun start() {
                            previewHolder[0] = view
                            if (!view.isAttachedToWindow || !binding.compareAndSet(false, true)) return
                            val future = ProcessCameraProvider.getInstance(view.context)
                            future.addListener({
                                if (!view.isAttachedToWindow) {
                                    binding.set(false)
                                    return@addListener
                                }
                                try {
                                    val provider = future.get()
                                    val selector = when {
                                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                        else -> {
                                            cameraError = context.getString(R.string.camera_none)
                                            cameraReady = false
                                            return@addListener
                                        }
                                    }
                                    view.display?.rotation?.let { imageCapture.targetRotation = it }
                                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                                    provider.unbindAll()
                                    provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                                    providerHolder[0] = provider
                                    cameraReady = true
                                    cameraError = null
                                } catch (e: Exception) {
                                    cameraReady = false
                                    cameraError = context.getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName)
                                }
                            }, ContextCompat.getMainExecutor(view.context))
                        }
                        if (view.isAttachedToWindow) start() else view.doOnAttach { start() }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(stringResource(R.string.camera_permission), color = Color.White, modifier = Modifier.padding(24.dp))
            }
            Text(
                stringResource(if (state.filter == KingdomFilter.FUNGI) R.string.hint_fungus else R.string.hint_plant),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "${state.photos.size}/${AppViewModel.MAX_PHOTOS}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        cameraError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { cameraError = null; bindAttempt++ }) { Text(stringResource(R.string.camera_retry)) }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalIconButton(
                enabled = !full,
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(R.string.from_gallery)) }

            val shutterEnabled = hasCamera && !full
            Box(
                Modifier.size(78.dp).clip(CircleShape)
                    .border(5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(8.dp).clip(CircleShape)
                    .background(if (shutterEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = shutterEnabled) {
                        if (!cameraReady || providerHolder[0] == null) {
                            cameraError = context.getString(R.string.camera_not_ready)
                            return@clickable
                        }
                        val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                        try {
                            previewHolder[0]?.display?.rotation?.let { imageCapture.targetRotation = it }
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        vm.addCapturedFile(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        cameraError = context.getString(
                                            R.string.camera_failed,
                                            exception.message ?: exception.imageCaptureError.toString(),
                                        )
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            cameraError = context.getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName)
                        }
                    },
            )

            Button(
                onClick = { vm.identify() },
                enabled = state.photos.isNotEmpty() && !state.busy,
                modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = ForestDeep),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ForestDeep)
                } else {
                    Text(stringResource(R.string.identify), fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        if (state.photos.isNotEmpty()) {
            Text(stringResource(R.string.photo_part), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(state.photos) { i, photo -> Thumb(i, photo, state.filter, vm) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Thumb(i: Int, photo: Photo, filter: KingdomFilter, vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(104.dp)) {
        Box {
            Image(
                bitmap = photo.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(104.dp).clip(MaterialTheme.shapes.medium),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).clickable { vm.removePhoto(i) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Box {
            Text(
                stringResource(photo.part.label) + " ▾",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 6.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { open = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val parts = when (filter) {
                    KingdomFilter.ANY -> PhotoPart.entries.toList()
                    KingdomFilter.FUNGI -> PhotoPart.entries.filter { it.forFungus }
                    KingdomFilter.PLANTS -> PhotoPart.entries.filter { !it.forFungus }
                }
                parts.forEach { part ->
                    DropdownMenuItem(text = { Text(stringResource(part.label)) }, onClick = { vm.setPart(i, part); open = false })
                }
            }
        }
    }
}
