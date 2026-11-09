package com.mymav.glasses.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One camera frame for Food / Shop / Identify, started from a HUD action.
 *
 * Until physical glasses arrive the phone camera is the test input (headless, no phone tap needed
 * while MyMav is open on the phone); the SDK feed (simulator) is the fallback. Every frame carries
 * an honest source label — a phone photo is never presented as the glasses view.
 */
object MymavCapture {
    const val PHONE_TEST_LABEL = "Test input · phone camera"

    /** Set by the app: one JPEG from the phone camera, or null with [phoneProblem] set. */
    @Volatile var phoneCamera: (suspend () -> ByteArray?)? = null
    @Volatile var phoneProblem: String = ""

    /** When physical glasses are connected their camera should win; false while testing on the simulator. */
    @Volatile var preferGlassesCamera: Boolean = false

    /** Debug-only: the next capture returns this labelled sample instead of a camera frame (one use). */
    @Volatile var debugSample: MymavVision.Frame? = null

    class Shot(val frame: MymavVision.Frame?, val problem: String)

    @Volatile private var handoff: MymavVision.Frame? = null
    @Volatile private var handoffAt = 0L

    /** Pass the frame just analysed to the next app (Food → Identify, Identify → Shop) so it never recaptures. */
    fun handOff(frame: MymavVision.Frame) {
        handoff = frame
        handoffAt = System.currentTimeMillis()
    }

    /** The handed-off frame if it is fresh (one use). */
    fun takeHandoff(maxAgeMs: Long = 60_000L): MymavVision.Frame? {
        val f = handoff ?: return null
        handoff = null
        return f.takeIf { System.currentTimeMillis() - handoffAt <= maxAgeMs }
    }

    suspend fun capture(): Shot {
        debugSample?.let { debugSample = null; return Shot(it, "") }
        val phone: suspend () -> MymavVision.Frame? = {
            phoneProblem = ""
            phoneCamera?.invoke()?.takeIf { it.isNotEmpty() }?.let { MymavVision.Frame(it, PHONE_TEST_LABEL) }
        }
        val glasses: suspend () -> MymavVision.Frame? = {
            withContext(Dispatchers.IO) { MymavVision.glassesFrame?.invoke() }
        }
        val order = if (preferGlassesCamera) listOf(glasses, phone) else listOf(phone, glasses)
        for (src in order) {
            val f = runCatching { src() }.getOrNull()
            if (f != null) return Shot(f, "")
        }
        val why = listOf(phoneProblem, MymavVision.lastProblem).filter { it.isNotBlank() }.joinToString(" · ")
        return Shot(null, why.ifBlank { "No camera frame. Keep MyMav open on the phone and try again." })
    }
}
