package com.mymav.glasses.mav2

import com.everysight.mav2.sdk.Evs
import com.everysight.mav2.sdk.services.IM2AIFrame
import com.everysight.mav2.sdk.services.IM2AIVisionEvents
import com.everysight.mav2.sdk.services.M2AIVisionService
import com.everysight.mav2.sdk.uikit.data.M2CaptureOptions
import com.mymav.glasses.api.MymavVision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One JPEG frame from the glasses (or simulator) camera through the SDK vision service. Capture runs only for the grab. */
object Mav2Camera {
    @Volatile var sourceLabel: String = "the SDK camera feed (simulator - not physical glasses)"

    fun grab(timeoutMs: Long = 8000): MymavVision.Frame? {
        val svc = Evs.visionService
        val latch = CountDownLatch(1)
        var jpeg: ByteArray? = null
        var err: String? = null
        val l = object : IM2AIVisionEvents {
            override fun onCaptureStateChanged(started: Boolean) {}
            override fun onFrameReceived(frame: IM2AIFrame) {
                if (jpeg == null) { jpeg = runCatching { frame.toJpegData() }.getOrNull(); latch.countDown() }
                runCatching { frame.release() }
            }
            override fun onError(type: M2AIVisionService.Errors, message: String) { err = "${type.name}: $message" }
        }
        val opts = Mav2CameraProbe.buildOptions() as? M2CaptureOptions
        if (opts == null) { MymavVision.lastProblem = "the SDK capture options could not be built"; return null }
        runCatching { svc.registerListener(l) }
        try {
            val started = runCatching { svc.startCapture(opts) }.getOrDefault(false)
            if (!started) { MymavVision.lastProblem = err ?: "the glasses refused to start the camera (not connected?)"; return null }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val j = jpeg
            if (j == null || j.isEmpty()) { MymavVision.lastProblem = err ?: "the camera started but no frame arrived in ${timeoutMs / 1000} s"; return null }
            return MymavVision.Frame(j, sourceLabel)
        } finally {
            runCatching { svc.stopCapture() }; runCatching { svc.unregisterListener(l) }
        }
    }
}
