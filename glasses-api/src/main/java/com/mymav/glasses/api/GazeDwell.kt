package com.mymav.glasses.api

import android.os.Handler
import android.os.SystemClock

/**
 * A 1-second (or [durationMs]) gaze dwell driven by a clock, not by sample rate:
 * it fires exactly on time even if the head is perfectly still and no new gaze
 * samples arrive. Progress ticks every [tickMs] for the on-glass progress bar.
 */
class GazeDwell(
    private val handler: Handler,
    private val tickMs: Long = 80L,
) {
    var target: String? = null
        private set
    var progress: Float = 0f
        private set
    private var startedAt = 0L
    private var duration = 1_000L
    private var onTick: ((Float) -> Unit)? = null
    private var onFire: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val key = target ?: return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            if (elapsed >= duration) {
                val fire = onFire
                cancel()
                if (key.isNotEmpty()) fire?.invoke()
                return
            }
            onTick?.invoke(progress)
            handler.postDelayed(this, tickMs)
        }
    }

    /** Keep dwelling on [key]; restarts only when the target changes. */
    fun feed(key: String, durationMs: Long = 1_000L, onTick: (Float) -> Unit, onFire: () -> Unit) {
        if (key == target) return
        cancel()
        target = key
        duration = durationMs
        startedAt = SystemClock.uptimeMillis()
        progress = 0f
        this.onTick = onTick
        this.onFire = onFire
        onTick(0f)
        handler.postDelayed(ticker, tickMs)
    }

    fun cancel() {
        handler.removeCallbacks(ticker)
        target = null
        progress = 0f
        onTick = null
        onFire = null
    }
}
