package com.mymav.glasses.api

import java.util.Calendar
import java.util.Locale

/**
 * Deterministic date/time phrase parser for calendar requests (no language model, so the same words always give the
 * same event). It reports what it could NOT work out (no time, am/pm unclear) so the assistant asks instead of guessing.
 */
object MymavWhen {
    class Parsed(
        /** Local midnight of the requested day, or null when no day was said. */
        val dayMs: Long?,
        val hour: Int?,
        val minute: Int,
        /** True when the hour is 1-12 with no am/pm or morning/evening word. */
        val ambiguousMeridiem: Boolean,
        val allDay: Boolean,
        val durationMin: Int?,
        val reminderMin: Int?,
        val calendarName: String?,
        /** The request with the date/time/duration/reminder/calendar phrases removed. */
        val rest: String,
    )

    private val DAYS = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY, "sunday" to Calendar.SUNDAY,
    )
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun midnight(now: Calendar): Calendar = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    fun parse(text: String, now: Calendar = Calendar.getInstance()): Parsed {
        var t = " " + text.lowercase().trim().replace(Regex("\\s+"), " ") + " "
        fun take(r: Regex): MatchResult? = r.find(t)?.also { t = t.replaceRange(it.range, " ") }

        // calendar name
        var calName: String? = null
        take(Regex("\\b(?:in|to|into) (?:my |the )?([a-z']+(?: [a-z']+){0,2}?) calendar\\b|\\bon (?:my|the) ([a-z']+(?: [a-z']+){0,2}?) calendar\\b"))?.let {
            val n = (it.groupValues[1].ifBlank { it.groupValues[2] }).trim()
            calName = n.takeIf { x -> x != "my" && x != "the" && x.isNotBlank() }
        }

        // reminder
        var reminder: Int? = null
        take(Regex("\\b(?:with )?(?:a |an )?(?:reminder|alert|notification)(?: of)? (\\d+|an?|one) ?(minutes?|mins?|hours?|hrs?)(?: before)?\\b"))?.let {
            reminder = qty(it.groupValues[1]) * if (it.groupValues[2].startsWith("h")) 60 else 1
        } ?: take(Regex("\\bremind me (\\d+|an?|one) ?(minutes?|mins?|hours?|hrs?) before\\b"))?.let {
            reminder = qty(it.groupValues[1]) * if (it.groupValues[2].startsWith("h")) 60 else 1
        } ?: take(Regex("\\b(\\d+|an?|one) ?(minutes?|mins?|hours?|hrs?) (?:reminder|before)\\b"))?.let {
            reminder = qty(it.groupValues[1]) * if (it.groupValues[2].startsWith("h")) 60 else 1
        }

        // duration
        var duration: Int? = null
        take(Regex("\\bfor (\\d+|an?|one|half an?) ?(minutes?|mins?|hours?|hrs?)\\b"))?.let {
            val q = it.groupValues[1]
            val unit = if (it.groupValues[2].startsWith("h")) 60 else 1
            duration = if (q.startsWith("half")) 30 else qty(q) * unit
        }

        val allDay = take(Regex("\\ball[- ]day\\b")) != null

        // day
        var day: Calendar? = null
        val base = midnight(now)
        take(Regex("\\bday after tomorrow\\b"))?.let { day = base.also { c -> c.add(Calendar.DAY_OF_YEAR, 2) } }
        if (day == null) take(Regex("\\btomorrow\\b"))?.let { day = (base.clone() as Calendar).also { c -> c.add(Calendar.DAY_OF_YEAR, 1) } }
        if (day == null) take(Regex("\\b(today|tonight|this evening)\\b"))?.let { day = base.clone() as Calendar }
        if (day == null) take(Regex("\\bin (\\d+|a|one) (days?|weeks?)\\b"))?.let {
            val n = qty(it.groupValues[1]) * if (it.groupValues[2].startsWith("w")) 7 else 1
            day = (base.clone() as Calendar).also { c -> c.add(Calendar.DAY_OF_YEAR, n) }
        }
        if (day == null) take(Regex("\\b(?:on )?(next |this )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"))?.let {
            val target = DAYS.getValue(it.groupValues[2])
            val c = base.clone() as Calendar
            var add = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
            // "next friday" is never today; plain "friday" / "this friday" can be today.
            if (add == 0 && it.groupValues[1].trim() == "next") add = 7
            c.add(Calendar.DAY_OF_YEAR, add); day = c
        }
        if (day == null) {
            val mon = MONTHS.joinToString("|") { it.take(3) + "[a-z]*" }
            val m1 = Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? ($mon)\\b").find(t)
            val m2 = Regex("\\b(?:on )?($mon) (\\d{1,2})(?:st|nd|rd|th)?\\b").find(t)
            val (mm, dd, range) = when {
                m1 != null -> Triple(m1.groupValues[2], m1.groupValues[1], m1.range)
                m2 != null -> Triple(m2.groupValues[1], m2.groupValues[2], m2.range)
                else -> Triple("", "", null)
            }
            if (range != null) {
                val mi = MONTHS.indexOfFirst { it.startsWith(mm.take(3)) }
                val c = base.clone() as Calendar
                c.set(Calendar.MONTH, mi); c.set(Calendar.DAY_OF_MONTH, dd.toInt())
                if (c.before(base)) c.add(Calendar.YEAR, 1)
                day = c; t = t.replaceRange(range, " ")
            }
        }

        // time
        var hour: Int? = null
        var minute = 0
        var ambiguous = false
        val meridiemWord = Regex("\\bin the (morning|afternoon|evening)\\b").find(t)?.also { t = t.replaceRange(it.range, " ") }?.groupValues?.get(1)
        val tonight = text.lowercase().contains("tonight") || text.lowercase().contains("this evening")
        if (take(Regex("\\bnoon\\b")) != null) hour = 12
        else if (take(Regex("\\bmidnight\\b")) != null) hour = 0
        else {
            val tm = Regex("\\b(?:at |from |@ )?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)\\b").find(t)
                ?: Regex("\\bat (\\d{1,2})(?:[:.](\\d{2}))?()\\b").find(t)
                ?: Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)()\\b").find(t)
            if (tm != null) {
                t = t.replaceRange(tm.range, " ")
                var h = tm.groupValues[1].toInt()
                minute = tm.groupValues[2].ifBlank { "0" }.toInt()
                val mer = tm.groupValues[3].replace(".", "")
                when {
                    mer == "pm" -> { if (h < 12) h += 12 }
                    mer == "am" -> { if (h == 12) h = 0 }
                    meridiemWord == "morning" -> { if (h == 12) h = 0 }
                    meridiemWord == "afternoon" || meridiemWord == "evening" || tonight -> { if (h < 12) h += 12 }
                    h in 1..12 -> ambiguous = true
                }
                hour = h
            }
        }
        if (ambiguous && hour != null && hour in 13..23) ambiguous = false

        val dayMs = day?.timeInMillis
        val rest = t.replace(Regex("\\b(at|on|for|from|to|my|the|a|an)\\b$"), " ").replace(Regex("\\s+"), " ").trim()
        return Parsed(dayMs, hour, minute, ambiguous, allDay, duration, reminder, calName, rest)
    }

    private fun qty(s: String): Int = when (s) { "a", "an", "one" -> 1; else -> s.toIntOrNull() ?: 1 }
}
