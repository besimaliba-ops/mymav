package com.mymav.glasses.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Countdown timers for MyMav ("set a timer for 10 minutes", recipe step timers).
 * They run in-process (the glasses/notify service keeps the app alive) and, when
 * done, show on the glasses, notify the phone and vibrate.
 */
object MymavTimers {
    class T(val id: Int, val label: String, val endAt: Long, val runnable: Runnable)

    private const val CHANNEL = "mymav_timers"
    private val main = Handler(Looper.getMainLooper())
    private val timers = ArrayList<T>()
    private var nextId = 1
    @Volatile private var appContext: Context? = null

    /** Called on the main thread when a timer finishes (label). */
    @Volatile var onFire: ((String) -> Unit)? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun start(label: String, durationMs: Long): T {
        val id = nextId++
        val end = System.currentTimeMillis() + durationMs
        lateinit var t: T
        val r = Runnable { fire(t) }
        t = T(id, label.ifBlank { "Timer" }, end, r)
        timers += t
        main.postDelayed(r, durationMs)
        return t
    }

    @Synchronized
    fun active(): List<T> = timers.sortedBy { it.endAt }

    /** Cancel timers whose label contains [query] (all when blank). Returns how many. */
    @Synchronized
    fun cancel(query: String?): Int {
        val q = query?.trim()?.lowercase().orEmpty()
        val hit = timers.filter { q.isEmpty() || it.label.lowercase().contains(q) }
        hit.forEach { main.removeCallbacks(it.runnable); timers.remove(it) }
        return hit.size
    }

    private fun fire(t: T) {
        synchronized(this) { timers.remove(t) }
        runCatching { notifyPhone(t.label) }
        runCatching { vibrate() }
        onFire?.invoke(t.label)
    }

    private fun notifyPhone(label: String) {
        val ctx = appContext ?: return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "MyMav timers", NotificationManager.IMPORTANCE_HIGH),
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer done")
            .setContentText(label)
            .setAutoCancel(true)
            .build()
        mgr.notify(("timer" + label + System.nanoTime()).hashCode(), n)
    }

    private fun vibrate() {
        val ctx = appContext ?: return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }

    fun format(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Spoken-style duration, e.g. "1 hour 30 min". */
    fun spoken(ms: Long): String {
        val totalMin = (ms / 60_000L)
        val h = totalMin / 60
        val m = totalMin % 60
        val sec = (ms / 1000L) % 60
        return buildList {
            if (h > 0) add("$h hour" + if (h > 1) "s" else "")
            if (m > 0) add("$m min")
            if (h == 0L && m == 0L && sec > 0) add("$sec sec")
        }.joinToString(" ").ifBlank { "0 sec" }
    }

    /** "10 minutes", "1 hour 30 minutes", "half an hour", "90 seconds" -> ms, or null. */
    fun parseDuration(text: String): Long? {
        val t = text.lowercase()
        var total = 0L
        var found = false
        if (Regex("\\bhalf an? hour\\b").containsMatchIn(t)) { total += 30 * 60_000L; found = true }
        if (Regex("\\ban? hour( and a half)?\\b").containsMatchIn(t) && !Regex("\\d\\s*(hours?|hrs?|h)\\b").containsMatchIn(t)) {
            total += if (t.contains("hour and a half")) 90 * 60_000L else 60 * 60_000L; found = true
        }
        for (m in Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b").findAll(t)) {
            val n = m.groupValues[1].toDoubleOrNull() ?: continue
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") -> (n * 3_600_000).toLong()
                u.startsWith("m") -> (n * 60_000).toLong()
                else -> (n * 1000).toLong()
            }
            found = true
        }
        return if (found && total > 0) total else null
    }
}
