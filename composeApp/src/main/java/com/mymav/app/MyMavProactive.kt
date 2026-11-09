package com.mymav.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.mymav.glasses.api.AssistantLog
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.MymavActions
import com.mymav.glasses.api.MymavAi
import com.mymav.glasses.api.MymavMemory
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Opt-in proactive help: a dinner suggestion and a today's-tasks reminder at times the user chooses. Off by default.
 * Quiet hours and a daily cap are respected, each kind fires at most once a day (snooze re-fires it once later), and
 * a nudge is only sent if there is real content (tasks that exist, or an AI suggestion that was actually generated).
 * Nudges are checked every minute while MyMav is running; they are not delivered while the app is not running.
 */
object MyMavProactive {
    private const val PREFS = "mymav_proactive"
    private const val CHANNEL = "mymav_nudges"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var started = false

    private fun p() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled() = p().getBoolean("enabled", false)
    fun setEnabled(on: Boolean) { p().edit().putBoolean("enabled", on).apply() }
    fun kindOn(kind: String) = p().getBoolean("on_$kind", true)
    fun setKindOn(kind: String, on: Boolean) { p().edit().putBoolean("on_$kind", on).apply() }
    fun time(key: String, def: String) = p().getString(key, def) ?: def
    fun setTime(key: String, v: String): Boolean { if (minutes(v) == null) return false; p().edit().putString(key, v).apply(); return true }
    fun cap() = p().getInt("cap", 2)
    fun setCap(n: Int) { p().edit().putInt("cap", n).apply() }

    private fun minutes(hhmm: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    fun install(context: Context) {
        app = context.applicationContext
        if (started) return
        started = true
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "MyMav suggestions", NotificationManager.IMPORTANCE_DEFAULT))
        main.postDelayed(object : Runnable { override fun run() { runCatching { tick() }; main.postDelayed(this, 60_000L) } }, 20_000L)
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun nowMin(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun inQuietHours(now: Int = nowMin()): Boolean {
        val s = minutes(time("quiet_start", "22:00")) ?: 1320; val e = minutes(time("quiet_end", "08:00")) ?: 480
        return if (s <= e) now in s until e else now >= s || now < e
    }

    private fun firedToday(kind: String) = p().getString("fired_$kind", "") == today()
    private fun sentToday(): Int = if (p().getString("count_day", "") == today()) p().getInt("count", 0) else 0
    private fun markSent(kind: String) {
        p().edit().putString("fired_$kind", today()).putString("count_day", today()).putInt("count", sentToday() + 1).apply()
    }

    private fun tick() {
        if (!enabled() || inQuietHours()) return
        val now = nowMin()
        for (kind in listOf("recipe", "tasks")) {
            if (!kindOn(kind)) continue
            // A snoozed nudge comes back once, when its snooze ends.
            val snoozeAt = p().getLong("snooze_$kind", 0L)
            if (snoozeAt > 0L) {
                if (System.currentTimeMillis() >= snoozeAt) { p().edit().remove("snooze_$kind").apply(); deliver(kind, false) }
                continue
            }
            if (firedToday(kind) || sentToday() >= cap()) continue
            val at = minutes(time("time_$kind", if (kind == "recipe") "18:00" else "09:00")) ?: continue
            if (now in at..(at + 120)) deliver(kind, false)
        }
    }

    /** [preview] = user pressed "Send a test nudge": bypasses time, cap and once-a-day, and says so. */
    fun deliver(kind: String, preview: Boolean) {
        io.execute {
            val text: String? = when (kind) {
                "tasks" -> MymavActions.tasks?.todayLines()?.takeIf { it.isNotEmpty() }?.joinToString("\n") { l ->
                    val (t, title) = l.split("|", limit = 2).let { (it.getOrNull(0).orEmpty()) to (it.getOrNull(1) ?: it[0]) }
                    if (t.isBlank()) "- $title" else "- $t $title"
                }?.let { "Today's tasks:\n$it" }
                else -> suggestRecipe()
            }
            if (!preview) markSent(kind)
            if (text == null) {
                val why = if (kind == "tasks") "no open tasks today" else "no AI suggestion could be generated"
                main.post { AssistantLog.add("Nudge: $kind", "Not sent: $why.", AssistantLog.Kind.Local, emptyList(), "") }
                return@execute
            }
            main.post { show(kind, text, preview) }
        }
    }

    private fun suggestRecipe(): String? {
        if (!MymavAi.isConfigured()) return null
        var out: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        MymavAi.generate(
            "Suggest one dinner idea in at most two short sentences. Practical, no preamble." + MymavMemory.promptBlock(),
            "Dinner idea for tonight.", 120, 300,
        ) { out = it; latch.countDown() }
        latch.await(40, java.util.concurrent.TimeUnit.SECONDS)
        return out?.trim()?.takeIf { it.isNotBlank() }?.let { "Dinner idea (AI suggestion):\n$it" }
    }

    private fun show(kind: String, text: String, preview: Boolean) {
        val label = if (preview) "Test nudge" else "MyMav suggestion"
        AssistantLog.add("Nudge: $kind", text, if (kind == "tasks") AssistantLog.Kind.Data else AssistantLog.Kind.Ai, emptyList(),
            if (preview) "Preview sent by you" else "Proactive, you opted in")
        GlassesHomeController.showNavigationUpdate(text)
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(app, req, Intent(app, NudgeReceiver::class.java).setAction(a).putExtra("kind", kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(label).setContentText(text.lines().first())
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, "Snooze 1 h", action(NudgeReceiver.SNOOZE, 1)).build())
            .addAction(android.app.Notification.Action.Builder(null, "Dismiss", action(NudgeReceiver.DISMISS, 2)).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(("nudge_$kind").hashCode(), n)
    }

    fun snooze(kind: String) { p().edit().putLong("snooze_$kind", System.currentTimeMillis() + 60 * 60_000L).apply(); cancel(kind) }
    fun dismiss(kind: String) { p().edit().remove("snooze_$kind").apply(); cancel(kind) }
    private fun cancel(kind: String) { app.getSystemService(NotificationManager::class.java).cancel(("nudge_$kind").hashCode()) }
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        MyMavProactive.install(context)
        if (intent.action == SNOOZE) MyMavProactive.snooze(kind) else MyMavProactive.dismiss(kind)
    }
    companion object { const val SNOOZE = "com.mymav.app.nudge.SNOOZE"; const val DISMISS = "com.mymav.app.nudge.DISMISS" }
}
