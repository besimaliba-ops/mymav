package com.mymav.miniapps.tasks

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Local persistence for Tasks mini-app + due-time notifications.
 */
class TaskStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<MavTask> {
        val list = MavTask.listFromJson(prefs.getString(KEY, null))
        return if (list.isEmpty()) seed() else list
    }

    fun save(tasks: List<MavTask>) {
        prefs.edit().putString(KEY, MavTask.listToJson(tasks)).apply()
        com.mymav.glasses.api.HomeWidgetPreviewStore.invalidate("tasks")
        scheduleAll(tasks)
    }

    fun upsert(task: MavTask): List<MavTask> {
        val next = load().filterNot { it.id == task.id } + task
        save(next)
        return next
    }

    fun delete(id: String): List<MavTask> {
        val next = load().filterNot { it.id == id }
        save(next)
        return next
    }

    fun toggleDone(id: String): List<MavTask> {
        val next = load().map { if (it.id == id) it.copy(done = !it.done) else it }
        save(next)
        return next
    }

    /** Open tasks for today, in the order they should be done (timed first by time, then untimed). */
    fun today(): List<MavTask> {
        val now = Calendar.getInstance()
        return load().filter { !it.done && it.appliesOn(now) }
            .sortedWith(compareBy<MavTask> { if (it.dueMinuteOfDay < 0) Int.MAX_VALUE else it.dueMinuteOfDay }
                .thenBy { it.title.lowercase() })
    }

    fun openCount(): Int = load().count { !it.done }

    fun hudLines(limit: Int = 4): List<String> =
        load().filter { !it.done }.take(limit).map { "· ${it.title} (${it.dueLabel()})" }

    private fun seed(): List<MavTask> {
        val seeded = listOf(
            MavTask(title = "Stand-up notes", cadence = TaskCadence.Daily, dueMinuteOfDay = 9 * 60),
            MavTask(
                title = "Grocery run",
                cadence = TaskCadence.Weekly,
                dayOfWeek = 6,
                dueMinuteOfDay = 11 * 60,
            ),
            MavTask(title = "Ship MyMav demo", cadence = TaskCadence.Once, dueMinuteOfDay = 17 * 60),
        )
        prefs.edit().putString(KEY, MavTask.listToJson(seeded)).apply()
        scheduleAll(seeded)
        return seeded
    }

    private fun scheduleAll(tasks: List<MavTask>) {
        ensureChannel()
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        tasks.filter { it.notifyWhenDue && !it.done }.forEach { task ->
            val triggerAt = nextTriggerMs(task) ?: return@forEach
            val intent = Intent(app, TaskDueReceiver::class.java).apply {
                action = ACTION_DUE
                putExtra(EXTRA_TASK_ID, task.id)
                putExtra(EXTRA_TITLE, task.title)
            }
            val pi = PendingIntent.getBroadcast(
                app,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun nextTriggerMs(task: MavTask): Long? {
        if (task.dueMinuteOfDay < 0 && task.dueEpochMs <= 0L) return null
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        if (task.cadence == TaskCadence.Once && task.dueEpochMs > now) {
            return task.dueEpochMs
        }
        val minute = task.dueMinuteOfDay.coerceAtLeast(0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, minute / 60)
        cal.set(Calendar.MINUTE, minute % 60)
        when (task.cadence) {
            TaskCadence.Daily -> {
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            TaskCadence.Weekly -> {
                val target = if (task.dayOfWeek in 1..7) {
                    // Map ISO 1=Mon…7=Sun → Calendar SUNDAY=1…SATURDAY=7
                    when (task.dayOfWeek) {
                        7 -> Calendar.SUNDAY
                        else -> task.dayOfWeek + 1
                    }
                } else {
                    cal.get(Calendar.DAY_OF_WEEK)
                }
                var guard = 0
                while ((cal.get(Calendar.DAY_OF_WEEK) != target || cal.timeInMillis <= now) && guard < 14) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    guard++
                }
            }
            TaskCadence.Once -> {
                if (cal.timeInMillis <= now) return null
            }
        }
        return cal.timeInMillis
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "MyMav Tasks", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val PREFS = "mymav_tasks"
        private const val KEY = "tasks_json"
        const val CHANNEL = "mymav_tasks_due"
        const val ACTION_DUE = "com.mymav.miniapps.tasks.DUE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
    }
}

class TaskDueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskStore.ACTION_DUE) return
        val title = intent.getStringExtra(TaskStore.EXTRA_TITLE) ?: "Task due"
        val id = intent.getStringExtra(TaskStore.EXTRA_TASK_ID)?.hashCode() ?: title.hashCode()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    TaskStore.CHANNEL,
                    "MyMav Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notif = Notification.Builder(context, TaskStore.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Task due")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        runCatching { mgr.notify(id, notif) }
    }
}
