package com.mymav.miniapps.tasks

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskCadence { Daily, Weekly, Once }

data class MavTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val cadence: TaskCadence = TaskCadence.Once,
    /** Day-of-week 1=Mon … 7=Sun for Weekly; 0 = any/unused. */
    val dayOfWeek: Int = 0,
    /** Minutes from midnight local, or -1 if unset. */
    val dueMinuteOfDay: Int = -1,
    val dueEpochMs: Long = 0L,
    val notifyWhenDue: Boolean = true,
    val done: Boolean = false,
) {
    fun dueLabel(): String {
        val time = if (dueMinuteOfDay >= 0) {
            val h = dueMinuteOfDay / 60
            val m = dueMinuteOfDay % 60
            String.format("%02d:%02d", h, m)
        } else {
            "—"
        }
        return when (cadence) {
            TaskCadence.Daily -> "Daily · $time"
            TaskCadence.Weekly -> {
                val day = when (dayOfWeek) {
                    1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"
                    5 -> "Fri"; 6 -> "Sat"; 7 -> "Sun"; else -> "Day"
                }
                "Weekly · $day · $time"
            }
            TaskCadence.Once -> if (dueEpochMs > 0L) onceLabel(time) else "Once · $time"
        }
    }

    private fun onceLabel(time: String): String {
        val due = java.util.Calendar.getInstance().apply { timeInMillis = dueEpochMs }
        val now = java.util.Calendar.getInstance()
        val tom = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
        fun same(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        val day = when {
            same(due, now) -> "Today"
            same(due, tom) -> "Tomorrow"
            else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US).format(due.time)
        }
        return if (dueMinuteOfDay >= 0) "$day · $time" else day
    }

    /** True when this task belongs on the Tasks list for the calendar day of [day]. */
    fun appliesOn(day: java.util.Calendar): Boolean = when (cadence) {
        TaskCadence.Daily -> true
        TaskCadence.Weekly -> {
            val iso = (day.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            dayOfWeek == 0 || dayOfWeek == iso
        }
        TaskCadence.Once -> if (dueEpochMs <= 0L) true else {
            val due = java.util.Calendar.getInstance().apply { timeInMillis = dueEpochMs }
            due.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR) &&
                due.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("cadence", cadence.name)
        .put("dayOfWeek", dayOfWeek)
        .put("dueMinuteOfDay", dueMinuteOfDay)
        .put("dueEpochMs", dueEpochMs)
        .put("notifyWhenDue", notifyWhenDue)
        .put("done", done)

    companion object {
        fun fromJson(o: JSONObject): MavTask = MavTask(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "Task"),
            cadence = runCatching { TaskCadence.valueOf(o.optString("cadence", "Once")) }
                .getOrDefault(TaskCadence.Once),
            dayOfWeek = o.optInt("dayOfWeek", 0),
            dueMinuteOfDay = o.optInt("dueMinuteOfDay", -1),
            dueEpochMs = o.optLong("dueEpochMs", 0L),
            notifyWhenDue = o.optBoolean("notifyWhenDue", true),
            done = o.optBoolean("done", false),
        )

        fun listToJson(tasks: List<MavTask>): String {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<MavTask> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}
