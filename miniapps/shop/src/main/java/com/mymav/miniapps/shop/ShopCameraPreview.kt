package com.mymav.miniapps.shop

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymav.glasses.api.ScanCameraSession
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object ShopCameraCaptureHolder {
    @Volatile
    var imageCapture: ImageCapture? = null

    @Volatile
    var cameraReady: Boolean = false
}

@Composable
fun ShopCameraPreview(
    modifier: Modifier = Modifier,
    onPermission: (Boolean) -> Unit = {},
    onCameraReady: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        granted = it
        onPermission(it)
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(Manifest.permission.CAMERA)
            onPermission(false)
        } else onPermission(true)
    }

    DisposableEffect(Unit) {
        ScanCameraSession.tryAcquire("shop")
        onDispose {
            ShopCameraCaptureHolder.imageCapture = null
            ShopCameraCaptureHolder.cameraReady = false
            onCameraReady(false)
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            cameraExecutor.shutdown()
            ScanCameraSession.release("shop")
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!granted) {
            Text("Camera permission needed for live scan")
            return@Box
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            factory = { ctx ->
                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            },
            update = { previewView ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        ShopCameraCaptureHolder.imageCapture = capture
                        ShopCameraCaptureHolder.cameraReady = true
                        onCameraReady(true)
                    }.onFailure {
                        Log.w("ShopCamera", "bind failed", it)
                        ShopCameraCaptureHolder.cameraReady = false
                        onCameraReady(false)
                    }
                }, ContextCompat.getMainExecutor(context))
            },
        )
    }
}

fun captureShopJpeg(
    context: android.content.Context,
    executor: ExecutorService = Executors.newSingleThreadExecutor(),
    onDone: (ByteArray?, String?) -> Unit,
) {
    val capture = ShopCameraCaptureHolder.imageCapture
    if (capture == null) {
        onDone(null, "Phone camera is not open yet.")
        return
    }
    val file = File(context.cacheDir, "shop-scan-${System.currentTimeMillis()}.jpg")
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                file.delete()
                if (bytes == null || bytes.isEmpty()) onDone(null, "Captured file was empty.")
                else onDone(bytes, null)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onDone(null, "Capture failed: ${exception.message ?: exception.imageCaptureError}")
            }
        },
    )
}
