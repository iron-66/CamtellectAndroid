package com.example.camtellect

import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    onSnapshotReady: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder().build()
                try {
                    cameraProvider.unbindAll()
                    val selector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
    LaunchedEffect(imageCapture) {
        CameraPreview.takePicture = {
            imageCapture?.let { capture ->
                val photoFile = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "photo_${System.currentTimeMillis()}.jpg"
                )
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                capture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onSnapshotReady(photoFile.absolutePath)
                        }
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("CameraPreview", "Photo capture failed: ${exc.message}", exc)
                        }
                    }
                )
            }
        }
    }
}

object CameraPreview {
    var takePicture: (() -> Unit)? = null
}
