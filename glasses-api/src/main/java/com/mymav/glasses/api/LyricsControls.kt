package com.mymav.glasses.api

import android.os.Handler
import android.os.Looper

/**
 * Hit regions of the Spotify screen controls, in the 640x400 glasses canvas. The screen draws its pills from
 * these same rectangles, so gaze hit-testing and the drawn controls can never drift apart. None of them touch
 * the HOME pill (x482..612, y28..62) or each other (4 px gaps).
 */
object LyricsControlLayout {
    class Region(val id: String, val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h
    }

    const val ROW_Y = 114f
    const val ROW_H = 30f
    private const val X0 = 156f

    val previous = Region("prev", X0, ROW_Y, 52f, ROW_H)
    val play = Region("play", X0 + 56f, ROW_Y, 84f, ROW_H)
    val next = Region("next", X0 + 144f, ROW_Y, 52f, ROW_H)
    val search = Region("search", X0 + 200f, ROW_Y, 84f, ROW_H)
    val liked = Region("liked", X0 + 288f, ROW_Y, 84f, ROW_H)
    val all = listOf(previous, play, next, search, liked)

    fun hit(nx: Float, ny: Float): Region? {
        val x = nx * 640f
        val y = ny * 400f
        // Tiny vertical slack for gaze jitter; horizontal gaps keep neighbours distinct.
        return all.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y - 4f && y <= it.y + it.h + 4f }
    }
}

/**
 * Live, in-place UI state of the Spotify screen that changes faster than a HUD rebuild is worth:
 * the playback status line, a short control notice (errors), and the gaze-dwell progress of the focused control.
 * The screen registers [listener] while it is on glass; companions only call the setters.
 */
object LyricsControlState {
    @Volatile var status: String = ""
        private set
    @Volatile var notice: String = ""
        private set
    @Volatile var dwellId: String? = null
        private set
    @Volatile var dwellProgress: Float = 0f
        private set

    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun setStatus(value: String) {
        if (value == status) return
        status = value
        notifyChanged()
    }

    fun setNotice(value: String, clearAfterMs: Long = 3_500L) {
        notice = value
        notifyChanged()
        if (value.isNotEmpty()) main.postDelayed({ if (notice == value) setNotice("") }, clearAfterMs)
    }

    fun setDwell(id: String?, progress: Float) {
        val p = if (id == null) 0f else progress.coerceIn(0f, 1f)
        if (id == dwellId && kotlin.math.abs(p - dwellProgress) < 0.04f && p < 1f && p > 0f) return
        dwellId = id
        dwellProgress = p
        notifyChanged()
    }

    private fun notifyChanged() {
        val l = listener ?: return
        main.post { l() }
    }
}

/**
 * One-second gaze dwell over [LyricsControlLayout] regions.
 *  - progress is reported while the same control stays in view;
 *  - the action fires exactly once when 1 s is reached;
 *  - the same control cannot fire again until gaze has left it;
 *  - leaving a control (or losing gaze samples for [LOST_MS]) resets progress.
 * [runOnce] is the shared gate that also guards taps: any action for the same control within [GATE_MS] is dropped.
 */
class GazeDwellController(
    private val onFire: (String) -> Unit,
    private val dwellMs: Long = 1_000L,
) {
    private val main = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var enteredAt = 0L
    private var fired = false
    private val lost = Runnable { reset() }
    private val lastRun = HashMap<String, Long>()

    fun feed(nx: Float, ny: Float, now: Long = System.currentTimeMillis()) {
        main.removeCallbacks(lost)
        main.postDelayed(lost, LOST_MS)
        val id = LyricsControlLayout.hit(nx, ny)?.id
        if (id == null) {
            if (currentId != null) reset()
            return
        }
        if (id != currentId) {
            currentId = id
            android.util.Log.i("LyricsDwell", "enter $id")
            enteredAt = now
            fired = false
            LyricsControlState.setDwell(id, 0f)
            return
        }
        if (fired) return
        val p = (now - enteredAt).toFloat() / dwellMs
        if (p >= 1f) {
            fired = true
            android.util.Log.i("LyricsDwell", "fire $id")
            LyricsControlState.setDwell(id, 1f)
            runOnce(id)
        } else {
            LyricsControlState.setDwell(id, p)
        }
    }

    /** Tap and dwell both go through here so a control never executes twice for one intent. */
    fun runOnce(id: String, now: Long = System.currentTimeMillis()) {
        val prev = lastRun[id] ?: 0L
        if (now - prev < GATE_MS) return
        lastRun[id] = now
        onFire(id)
    }

    fun reset() {
        if (currentId != null) android.util.Log.i("LyricsDwell", "reset $currentId")
        main.removeCallbacks(lost)
        currentId = null
        fired = false
        LyricsControlState.setDwell(null, 0f)
    }

    private companion object {
        const val LOST_MS = 500L
        const val GATE_MS = 900L
    }
}
