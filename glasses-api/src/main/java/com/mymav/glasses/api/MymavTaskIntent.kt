package com.mymav.glasses.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Understands "add a task ..." / "what are my tasks" for the MyMav avatar. */
object MymavTaskIntent {
    class Parsed(val title: String, val dueEpochMs: Long, val hasTime: Boolean)

    private val ADD = Regex(
        "(?i)\\b(add|create|make|set|put|new|log)\\b.*\\b(tasks?|reminders?|to-?dos?)\\b|\\bremind me\\b|\\bnote to self\\b",
    )
    private val LIST = Regex(
        "(?i)\\b(what|show|list|read|tell|any|got)\\b.*\\b(tasks?|to-?dos?|reminders?|agenda)\\b",
    )

    fun isAdd(text: String) = ADD.containsMatchIn(text)
    fun isList(text: String) = !isAdd(text) && LIST.containsMatchIn(text)

    private val DAYS = listOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY,
    )

    fun aiPrompt(now: Calendar): String {
        val day = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(now.time)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(now.time)
        return "Extract a to-do task from the user's request. Today is $day and the time is $time. " +
            "Reply with ONLY a JSON object: {\"title\":\"short task title without date/time words in sentence case (only the first letter capital), " +
            "\",\"date\":\"YYYY-MM-DD or null\",\"time\":\"HH:MM 24-hour or null\"}. " +
            "Resolve words like tomorrow, Friday or next Monday to a real date. If a time is given " +
            "without a date use today if that time is still ahead, otherwise tomorrow."
    }

    fun parseAiJson(raw: String?, now: Calendar = Calendar.getInstance()): Parsed? {
        val t = raw ?: return null
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching {
            val o = JSONObject(t.substring(a, b + 1))
            var title = o.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return null
            if (title == title.uppercase()) title = title.lowercase().replaceFirstChar { it.uppercase() }
            val date = o.optString("date").takeIf { it.isNotBlank() && it != "null" }
            val time = o.optString("time").takeIf { it.isNotBlank() && it != "null" }
            val cal = (now.clone() as Calendar)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            var hasDate = false
            if (date != null) {
                val p = date.split('-')
                if (p.size == 3) {
                    cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt()); hasDate = true
                }
            }
            var hasTime = false
            if (time != null) {
                val p = time.split(':')
                if (p.size >= 2) {
                    cal.set(Calendar.HOUR_OF_DAY, p[0].trim().toInt()); cal.set(Calendar.MINUTE, p[1].trim().toInt())
                    hasTime = true
                }
            }
            if (!hasDate && hasTime && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            Parsed(title, if (hasDate || hasTime) cal.timeInMillis else 0L, hasTime)
        }.getOrNull()
    }

    /** Best-effort parse without AI. */
    fun parseLocal(raw: String, now: Calendar = Calendar.getInstance()): Parsed? {
        var t = raw.trim()
            .replace(Regex("(?i)^(hey|hi),? ?my ?mav[,.! ]*"), "")
        val cal = (now.clone() as Calendar)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        var hasDate = false
        var hasTime = false

        // Time: "at 5pm", "at 17:30", "5:30 pm", noon
        val tm = Regex("(?i)\\b(?:at|@|by)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(t)
            ?: Regex("(?i)\\b(?:at|@)\\s*(\\d{1,2})(?::(\\d{2}))?()").find(t)
            ?: Regex("(?i)\\b(\\d{1,2}):(\\d{2})()").find(t)
        if (tm != null) {
            var h = tm.groupValues[1].toInt()
            val m = tm.groupValues[2].ifBlank { "0" }.toInt()
            val ap = tm.groupValues[3].lowercase()
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && m in 0..59) {
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); hasTime = true
                t = t.removeRange(tm.range)
            }
        } else if (Regex("(?i)\\bnoon\\b").containsMatchIn(t)) {
            cal.set(Calendar.HOUR_OF_DAY, 12); hasTime = true
            t = t.replace(Regex("(?i)\\b(at )?noon\\b"), "")
        }

        // Day words
        val low = t.lowercase()
        when {
            Regex("\\btomorrow\\b").containsMatchIn(low) -> {
                cal.add(Calendar.DAY_OF_YEAR, 1); hasDate = true
                t = t.replace(Regex("(?i)\\b(for |on )?tomorrow\\b"), "")
            }
            Regex("\\b(today|tonight)\\b").containsMatchIn(low) -> {
                hasDate = true
                t = t.replace(Regex("(?i)\\b(for |on )?(today|tonight)\\b"), "")
            }
            else -> for ((name, dow) in DAYS) {
                val m = Regex("(?i)\\b(on |for |next )?$name\\b").find(t) ?: continue
                var add = (dow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (add == 0) add = 7
                cal.add(Calendar.DAY_OF_YEAR, add); hasDate = true
                t = t.removeRange(m.range)
                break
            }
        }
        if (!hasDate && hasTime && cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)

        // Title
        t = t.replace(
            Regex("(?i)^\\s*(please |can you |could you )?(add|create|make|set|put|log)( me)?( a| an| new)*( task| reminder| to-?do| todo)?( list)?( to| for| called| named| saying| that| about)?[:,]?\\s*"),
            "",
        ).replace(Regex("(?i)^\\s*remind me( to| about)?\\s*"), "")
            .replace(Regex("(?i)\\s*(to|on|in) (my |the )?(task|tasks|to-?do|todo)( list)?\\s*$"), "")
            .replace(Regex("(?i)\\b(due|by|at|on|for)\\s*$"), "")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ' ')
        if (t.isBlank()) return null
        val title = t.replaceFirstChar { it.uppercase() }
        return Parsed(title, if (hasDate || hasTime) cal.timeInMillis else 0L, hasTime)
    }

    fun whenLabel(p: Parsed, now: Calendar = Calendar.getInstance()): String {
        if (p.dueEpochMs <= 0L) return ""
        val due = Calendar.getInstance().apply { timeInMillis = p.dueEpochMs }
        val sameDay = due.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            due.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = due.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) &&
            due.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)
        val day = when {
            sameDay -> "today"
            isTomorrow -> "tomorrow"
            else -> SimpleDateFormat("EEE d MMM", Locale.US).format(due.time)
        }
        val time = if (p.hasTime) " at " + SimpleDateFormat("HH:mm", Locale.US).format(due.time) else ""
        return " for $day$time"
    }
}
