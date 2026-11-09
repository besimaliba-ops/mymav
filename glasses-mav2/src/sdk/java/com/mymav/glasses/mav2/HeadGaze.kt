package com.mymav.glasses.mav2

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.everysight.mav2.sdk.Evs
import com.everysight.mav2.sdk.services.IM2SensorsEvents
import com.everysight.mav2.sdk.services.data.M2Quaternion
import com.everysight.mav2.sdk.uikit.data.M2ImuCalibrationState
import com.everysight.mav2.sdk.uikit.data.M2SensorsRate
import com.mymav.glasses.api.GlassesHomeController
import kotlin.math.PI
import kotlin.math.abs

/**
 * Head-gaze pointer: the glasses' fused orientation (IMU quaternion) turned into
 * a normalised point on the 640×400 HUD, fed to [GlassesHomeController.feedGaze].
 *
 * Small head turns move the "gaze" across the board (±[YAW_RANGE_DEG] spans the
 * width, ±[PITCH_RANGE_DEG] the height). The straight-ahead reference slowly
 * follows your natural resting direction so drift never pins the pointer to an
 * edge. Works on the Everysight simulator (Sensors → yaw / pitch sliders) and on
 * real glasses.
 */
object HeadGaze {
    private const val TAG = "HeadGaze"
    private const val YAW_RANGE_DEG = 11f
    private const val PITCH_RANGE_DEG = 8f
    /** Re-centre time constant while the head is near the reference (ms). */
    private const val RECENTER_TAU_MS = 9_000f
    private const val NEAR_DEG = 3f
    private const val MIN_INTERVAL_MS = 40L

    /** Euler indices / signs from Quaternion.toEuler() — logged on start for checking. */
    private const val YAW_AXIS = 0
    private const val PITCH_AXIS = 1
    private const val YAW_SIGN = 1f
    private const val PITCH_SIGN = -1f

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var refYaw = Float.NaN
    private var refPitch = Float.NaN
    private var lastTs = 0L
    private var lastSent = 0L
    private var logged = 0
    private var lastLog = 0L

    private val listener = object : IM2SensorsEvents {
        override fun onQuaternion(timestampMs: Long, q: M2Quaternion, state: M2ImuCalibrationState) {
            onOrientation(q)
        }
    }

    fun start() {
        if (running) return
        running = true
        refYaw = Float.NaN
        refPitch = Float.NaN
        logged = 0
        runCatching {
            Evs.sensorsService.registerListener(listener)
            Evs.sensorsService.enableInertialSensors(M2SensorsRate.Medium)
            Log.i(TAG, "head gaze on")
        }.onFailure {
            running = false
            Log.w(TAG, "sensors unavailable: ${it.message}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { Evs.sensorsService.unregisterListener(listener) }
        runCatching { Evs.sensorsService.disableInertialSensors() }
    }

    /** Treat the current head direction as straight ahead. */
    fun recenter() {
        refYaw = Float.NaN
        refPitch = Float.NaN
    }

    private fun onOrientation(q: M2Quaternion) {
        val e = runCatching { q.toEuler() }.getOrNull() ?: return
        if (e.size < 3) return
        val yaw = Math.toDegrees(e[YAW_AXIS].toDouble()).toFloat()
        val pitch = Math.toDegrees(e[PITCH_AXIS].toDouble()).toFloat()
        val now = SystemClock.uptimeMillis()
        if (logged < 5 || now - lastLog > 1_000L) {
            logged++
            lastLog = now
            Log.i(TAG, "euler deg = %.1f, %.1f, %.1f".format(
                Math.toDegrees(e[0].toDouble()), Math.toDegrees(e[1].toDouble()), Math.toDegrees(e[2].toDouble()),
            ))
        }
        if (refYaw.isNaN()) {
            refYaw = yaw
            refPitch = pitch
            lastTs = now
            return
        }
        val dt = (now - lastTs).coerceIn(0L, 500L).toFloat()
        lastTs = now
        var dYaw = wrap(yaw - refYaw)
        var dPitch = wrap(pitch - refPitch)
        // Slowly follow the resting direction (only when close, so deliberate looks stay put).
        if (abs(dYaw) < NEAR_DEG && abs(dPitch) < NEAR_DEG) {
            val k = (dt / RECENTER_TAU_MS).coerceIn(0f, 1f)
            refYaw = wrap(refYaw + dYaw * k)
            refPitch += dPitch * k
            dYaw = wrap(yaw - refYaw)
            dPitch = pitch - refPitch
        }
        // Far beyond the HUD (turned to look around the room): that direction
        // becomes the new straight-ahead, so you're back at the centre.
        if (abs(dYaw) > YAW_RANGE_DEG * 2.5f || abs(dPitch) > PITCH_RANGE_DEG * 2.5f) {
            refYaw = yaw
            refPitch = pitch
            dYaw = 0f
            dPitch = 0f
        }
        if (now - lastSent < MIN_INTERVAL_MS) return
        lastSent = now
        val nx = (0.5f + YAW_SIGN * dYaw / (2f * YAW_RANGE_DEG)).coerceIn(0f, 1f)
        val ny = (0.5f + PITCH_SIGN * dPitch / (2f * PITCH_RANGE_DEG)).coerceIn(0f, 1f)
        main.post { runCatching { GlassesHomeController.feedGaze(nx, ny) } }
    }

    private fun wrap(deg: Float): Float {
        var d = deg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    @Suppress("unused")
    private val TWO_PI = (2 * PI).toFloat()
}
