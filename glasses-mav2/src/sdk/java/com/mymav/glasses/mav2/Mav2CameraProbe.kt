package com.mymav.glasses.mav2

import android.util.Log
import com.everysight.mav2.sdk.Evs
import com.everysight.mav2.sdk.services.IM2AIFrame
import com.everysight.mav2.sdk.services.IM2AIVisionEvents
import com.everysight.mav2.sdk.services.M2AIVisionService

/**
 * Test-only: asks the connected glasses (or simulator) for camera frames through the SDK's vision service and logs
 * exactly what comes back (DBGCAM tag). It never shows or stores an image; it only records the outcome so we can
 * tell "simulator has no camera" from "this build cannot ask for frames".
 */
object Mav2CameraProbe {
    @Volatile var last: String = "not run"

    fun run() {
        val svc = Evs.visionService
        last = "started"
        val l = object : IM2AIVisionEvents {
            override fun onCaptureStateChanged(started: Boolean) { note("captureState=$started") }
            override fun onFrameReceived(frame: IM2AIFrame) {
                note("FRAME ${frame.getWidth()}x${frame.getHeight()} jpegBytes=${runCatching { frame.toJpegData().size }.getOrDefault(-1)}")
                runCatching { frame.release() }
            }
            override fun onError(type: M2AIVisionService.Errors, message: String) { note("ERROR ${type.name}: $message") }
        }
        runCatching { svc.registerListener(l) }.onFailure { note("registerListener failed: $it") }
        note("isCameraOpen(before)=${runCatching { svc.isCameraOpen() }.getOrNull()}")
        val opts = buildOptions()
        if (opts == null) { note("could not build M2CaptureOptions"); return }
        val ok = runCatching { svc.startCapture(opts as com.everysight.mav2.sdk.uikit.data.M2CaptureOptions) }
        note("startCapture -> ${ok.getOrNull() ?: ok.exceptionOrNull()}")
        Thread {
            Thread.sleep(6000)
            note("after 6s: isCameraOpen=${runCatching { svc.isCameraOpen() }.getOrNull()} fps=${runCatching { svc.getReceivedFps() }.getOrNull()} bytes/s=${runCatching { svc.getReceivedBytesPerSec() }.getOrNull()}")
            val tp = runCatching { svc.takePicture() }
            note("takePicture -> ${tp.getOrNull() ?: tp.exceptionOrNull()}")
            Thread.sleep(4000)
            runCatching { svc.stopCapture() }; runCatching { svc.unregisterListener(l) }
            note("stopped. summary: $last")
        }.start()
    }

    private fun note(s: String) { last = s; Log.i("DBGCAM", s) }

    private const val D = "com.everysight.mav2.sdk.uikit.data."
    private fun obj(name: String): Any? = runCatching { Class.forName(D + name).getField("INSTANCE").get(null) }.getOrNull()

    internal fun buildOptions(): Any? = runCatching {
        val resCls = Class.forName(D + "M2AIFrameResolution")
        val comp = resCls.getField("Companion").get(null)
        val res = comp.javaClass.getMethod("getRES_640x480_x2").invoke(comp)
        val qCls = Class.forName(D + "M2CompressionQuality")
        val qc = qCls.getField("Companion").get(null)
        val quality = (qc.javaClass.getMethod("getPresets").invoke(qc) as List<*>).first()
        Log.i("DBGCAM", "res=$res pos=${obj("M2Position\$Center")} exp=${obj("M2Exposure\$Auto")} quality=$quality qIsEnum=${qCls.isEnum} qCtors=${qCls.declaredConstructors.size}")
        val c = Class.forName(D + "M2ContinuousOptions").declaredConstructors.first { it.parameterTypes.size == 5 }
        c.newInstance(res, obj("M2Position\$Center"), obj("M2Exposure\$Auto"), quality, true)
    }.onFailure { Log.i("DBGCAM", "buildOptions failed: $it") }.getOrNull()
}
