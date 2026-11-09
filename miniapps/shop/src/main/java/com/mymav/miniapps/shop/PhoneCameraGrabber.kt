package com.mymav.miniapps.shop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.mymav.glasses.api.MymavCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Phone camera as the hands-free test input: takes one still without any phone tap.
 * Reuses a phone preview's ImageCapture when one is open (the camera can only be bound once),
 * otherwise binds a short-lived headless ImageCapture and releases it straight after.
 */
object PhoneCameraGrabber {
    private const val TAG = "PhoneCameraGrabber"
    private const val MAX_EDGE = 1280
    private val executor = Executors.newSingleThreadExecutor()

    private class OneShotOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Image captures of any phone preview currently on screen (Food / Shop / Identify). */
    @Volatile private var openPreviews: () -> List<ImageCapture?> = { emptyList() }

    fun install(context: Context, previews: () -> List<ImageCapture?>) {
        val app = context.applicationContext
        openPreviews = previews
        MymavCapture.phoneCamera = { grab(app) }
    }

    suspend fun grab(context: Context, timeoutMs: Long = 8_000L): ByteArray? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            MymavCapture.phoneProblem = "Phone camera permission is off — allow it once in MyMav."
            return null
        }
        val shared = (openPreviews() + ShopCameraCaptureHolder.imageCapture).firstOrNull { it != null }
        val raw = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                if (shared != null) take(shared) else headless(context)
            }
        }
        if (raw == null && MymavCapture.phoneProblem.isBlank()) {
            MymavCapture.phoneProblem = "Phone camera did not answer — keep MyMav open on the phone."
        }
        return raw
    }

    private suspend fun headless(context: Context): ByteArray? {
        val provider: ProcessCameraProvider? = runCatching {
            val future = ProcessCameraProvider.getInstance(context)
            suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                future.addListener({
                    runCatching { future.get() }
                        .onSuccess { if (cont.isActive) cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
                }, ContextCompat.getMainExecutor(context))
            }
        }.getOrNull()
        if (provider == null) {
            MymavCapture.phoneProblem = "Phone camera service unavailable."
            return null
        }
        val owner = OneShotOwner()
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            owner.registry.currentState = Lifecycle.State.RESUMED
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            return take(capture)
        } catch (e: Exception) {
            Log.w(TAG, "headless capture failed", e)
            MymavCapture.phoneProblem = "Phone camera is busy (${e.javaClass.simpleName})."
            return null
        } finally {
            runCatching { provider.unbind(capture) }
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private suspend fun take(capture: ImageCapture): ByteArray? = suspendCancellableCoroutine { cont ->
        val done: (ByteArray?) -> Unit = { b -> if (cont.isActive) cont.resume(b) }
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val out = runCatching { toUprightJpeg(image) }.getOrNull()
                    image.close()
                    if (out == null) MymavCapture.phoneProblem = "Phone camera returned an unreadable frame."
                    done(out)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "capture failed: ${exception.imageCaptureError}")
                    MymavCapture.phoneProblem = "Phone camera capture failed — keep MyMav open on the phone."
                    done(null)
                }
            },
        )
    }

    private fun toUprightJpeg(image: ImageProxy): ByteArray {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes
        val rot = image.imageInfo.rotationDegrees
        val scale = (MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        val m = Matrix().apply { postRotate(rot.toFloat()); postScale(scale, scale) }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val st = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, 85, st)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return st.toByteArray()
    }
}
