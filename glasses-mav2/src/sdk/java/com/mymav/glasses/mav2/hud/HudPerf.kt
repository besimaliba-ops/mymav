package com.mymav.glasses.mav2.hud

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Lightweight HUD performance counters (debug receiver `perf reset|dump|stall on|off`). Counting is a few
 * field updates per event, so it stays on; the main-thread stall monitor only runs when asked.
 */
object HudPerf {
    private const val TAG = "HudPerf"

    @Volatile private var since = SystemClock.elapsedRealtime()
    private val counts = HashMap<String, Long>()
    private val totalsNs = HashMap<String, Long>()
    private val maxNs = HashMap<String, Long>()

    @Synchronized
    fun count(key: String, n: Long = 1) {
        counts[key] = (counts[key] ?: 0L) + n
    }

    @Synchronized
    fun time(key: String, ns: Long) {
        counts[key] = (counts[key] ?: 0L) + 1
        totalsNs[key] = (totalsNs[key] ?: 0L) + ns
        if (ns > (maxNs[key] ?: 0L)) maxNs[key] = ns
    }

    inline fun <T> timed(key: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            time(key, System.nanoTime() - t0)
        }
    }

    @Synchronized
    fun reset() {
        counts.clear(); totalsNs.clear(); maxNs.clear()
        since = SystemClock.elapsedRealtime()
        stalls = 0; stallWorstMs = 0L; stallTotalMs = 0L
    }

    @Synchronized
    fun dump(label: String): String {
        val secs = (SystemClock.elapsedRealtime() - since) / 1000.0
        val sb = StringBuilder("PERF[$label] window=${"%.1f".format(secs)}s")
        for (k in counts.keys.sorted()) {
            val n = counts[k] ?: 0L
            val tot = totalsNs[k]
            if (tot == null) sb.append(" | $k=$n")
            else sb.append(" | $k n=$n avg=${"%.1f".format(tot / 1e6 / n)}ms max=${"%.1f".format((maxNs[k] ?: 0L) / 1e6)}ms tot=${"%.0f".format(tot / 1e6)}ms")
        }
        sb.append(" | stalls>${STALL_MS}ms=$stalls worst=${stallWorstMs}ms total=${stallTotalMs}ms")
        val rt = Runtime.getRuntime()
        sb.append(" | heap=${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB")
        val s = sb.toString()
        Log.i(TAG, s)
        return s
    }

    // ---- main-thread stall monitor ----------------------------------------------------------------------------
    private const val STALL_MS = 50L
    private const val TICK_MS = 16L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var monitoring = false
    private var expectedAt = 0L
    private var stalls = 0
    private var stallWorstMs = 0L
    private var stallTotalMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!monitoring) return
            val now = SystemClock.uptimeMillis()
            val late = now - expectedAt
            if (late > STALL_MS) synchronized(this@HudPerf) {
                stalls++
                stallTotalMs += late
                if (late > stallWorstMs) stallWorstMs = late
            }
            expectedAt = now + TICK_MS
            main.postDelayed(this, TICK_MS)
        }
    }

    fun texcache(arg: String): String {
        when (arg) {
            "on" -> HudImageCache.enabled = true
            "off" -> HudImageCache.enabled = false
            "reset" -> HudImageCache.reset()
        }
        val s = HudImageCache.dump()
        Log.i(TAG, s)
        return s
    }

    fun monitorStalls(on: Boolean) {
        if (on == monitoring) return
        monitoring = on
        if (on) {
            expectedAt = SystemClock.uptimeMillis() + TICK_MS
            main.postDelayed(tick, TICK_MS)
            Thread(::sampleStalls, "HudPerf-stalls").apply { isDaemon = true }.start()
        } else {
            main.removeCallbacks(tick)
        }
    }

    /** While monitoring: when the main thread is ~100 ms overdue, log where it is (app frames only), once per stall. */
    private fun sampleStalls() {
        val mainThread = Looper.getMainLooper().thread
        var reportedFor = 0L
        while (monitoring) {
            Thread.sleep(20)
            val due = expectedAt
            if (SystemClock.uptimeMillis() - due > 100L && reportedFor != due) {
                reportedFor = due
                val frames = mainThread.stackTrace
                    .filter { it.className.startsWith("com.mymav") }
                    .take(6)
                    .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                Log.i(TAG, "STALL main busy: ${frames.ifEmpty { mainThread.stackTrace.take(4).joinToString(" < ") }}")
            }
        }
    }
}
