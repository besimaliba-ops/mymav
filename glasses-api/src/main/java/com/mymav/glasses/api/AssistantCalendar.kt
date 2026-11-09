package com.mymav.glasses.api

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Calendar questions and event creation through the real phone calendar (CalendarContract).
 *
 * Creating an event is a short conversation: the assistant collects what is missing (title, day, time, am/pm,
 * which calendar), reads the full event back, and only writes after the user says yes. Success is reported only
 * after the saved event has been read back from the calendar. Calendar events are separate from tasks.
 */
internal object AssistantCalendar {
    private enum class Await { Title, Day, Time, Meridiem, Calendar, Confirm }

    private class Draft(
        var title: String, var dayMs: Long?, var hour: Int?, var minute: Int, var ambiguous: Boolean,
        var allDay: Boolean, var durationMin: Int, var reminder: Int?, var calName: String?,
    ) {
        var await: Await = Await.Title
        var calId: Long? = null
        var calLabel: String = ""
        var choices: List<MymavActions.CalendarInfo> = emptyList()
        var expiresAt: Long = System.currentTimeMillis() + 3 * 60_000L
        fun startMs(): Long {
            val c = Calendar.getInstance().apply { timeInMillis = dayMs ?: 0L }
            if (!allDay) { c.set(Calendar.HOUR_OF_DAY, hour ?: 0); c.set(Calendar.MINUTE, minute) }
            return c.timeInMillis
        }
    }

    @Volatile private var draft: Draft? = null
    @Volatile private var submitting = false
    private var lastDone = ""
    private var lastDoneAt = 0L
    private val io = Executors.newSingleThreadExecutor()

    private val CREATE = Regex(
        "^(?:please |can you |could you |i need to |i want to |i'd like to )*(?:add|create|put|schedule|book|set up|make|new)\\b.*" +
            "\\b(?:event|meeting|appointment|calendar|call with|dinner|lunch|dentist|doctor)\\b|^schedule (?:a |an |my )?.+ (?:on|at|tomorrow|today|next|this)\\b",
    )
    private val READ = Regex(
        "\\b(?:what|whats|what's|show|read|list|tell|any|got|have|check)\\b.*\\b(?:calendar|events?|meetings?|appointments?)\\b|" +
            "^what do i have (?:on |tomorrow|this|next|today)|^what'?s on (?:tomorrow|this|next|friday|monday|tuesday|wednesday|thursday|saturday|sunday)",
    )

    fun isCreate(c: String) = CREATE.containsMatchIn(c) && !Regex("\\b(task|to-?do)s?\\b").containsMatchIn(c)
    fun isRead(c: String) = READ.containsMatchIn(c) && !isCreate(c) && !Regex("\\b(task|to-?do)s?\\b").containsMatchIn(c)
    fun clear() { draft = null }
    fun hasPending() = draft?.let { it.expiresAt > System.currentTimeMillis() } == true

    private fun fmtDay(ms: Long) = SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(ms))
    private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))
    private fun out(text: String, kind: AssistantLog.Kind = AssistantLog.Kind.Local, src: Boolean = false) =
        AssistantRouter.Outcome(
            text, kind, if (src) listOf(AssistantLog.Source("Phone calendar", "content://com.android.calendar")) else emptyList(),
            // Conversational prompts ("Which day?", "Okay, nothing added") carry no source footer.
            if (kind == AssistantLog.Kind.Local && !src) "ask" else "",
        )
    private fun err(text: String) = out(text, AssistantLog.Kind.Error)

    // ---- reading -----------------------------------------------------------------------------------------------

    fun read(cleaned: String, deliver: (AssistantRouter.Outcome) -> Unit) {
        val bridge = MymavActions.calendar
        val p = MymavWhen.parse(cleaned)
        val now = Calendar.getInstance()
        val from: Long; val to: Long; val label: String
        when {
            p.dayMs != null -> { from = p.dayMs; to = p.dayMs + 24 * 3_600_000L; label = fmtDay(p.dayMs) }
            cleaned.contains("week") -> { from = now.timeInMillis; to = from + 7 * 24 * 3_600_000L; label = "the next 7 days" }
            else -> { from = now.timeInMillis; to = from + 48 * 3_600_000L; label = "the next 2 days" }
        }
        val events = bridge?.between(from, to, 8)
        if (events == null) { deliver(err("Calendar read permission is off. Allow it in MyMav Settings.")); return }
        if (events.isEmpty()) { deliver(out("Nothing on your calendar for $label.", AssistantLog.Kind.Data, true)); return }
        val lines = events.joinToString("\n") { e ->
            (if (e.allDay) "${fmtDay(e.startMs)} all day" else "${fmtDay(e.startMs)} ${fmtTime(e.startMs)}") + " " + e.title + if (e.location.isNotBlank()) " (${e.location})" else ""
        }
        deliver(out("Calendar, $label:\n$lines", AssistantLog.Kind.Data, true))
    }

    // ---- creating ----------------------------------------------------------------------------------------------

    fun start(cleaned: String, deliver: (AssistantRouter.Outcome) -> Unit) {
        val p = MymavWhen.parse(cleaned)
        var title = p.rest
            .replace(Regex("^(?:please |can you |could you |i need to |i want to |i'd like to )+"), "")
            .replace(Regex("^(?:add|create|put|schedule|book|set up|make|new)\\b"), "")
            .replace(Regex("^\\s*(?:a |an |the |my )+"), "")
            .replace(Regex("^(?:new )?(?:calendar )?"), "")
            .trim()
        title = title.replace(Regex("^(event|appointment|meeting|call)\\s+(called|named|titled|for|about|to)\\s+"), "")
            .replace(Regex("^(called|named|titled)\\s+"), "")
            .replace(Regex("\\s+(to|in|on|into) (?:my )?calendar$"), "").replace(Regex("^(to|in|on) calendar\\s*"), "").trim()
        if (title.startsWith("with ")) title = "Meeting $title"
        title = title.replaceFirstChar { it.uppercase() }
        val d = Draft(title, p.dayMs, p.hour, p.minute, p.ambiguousMeridiem, p.allDay, p.durationMin ?: 60, p.reminderMin, p.calendarName)
        draft = d
        advance(d, deliver)
    }

    private fun advance(d: Draft, deliver: (AssistantRouter.Outcome) -> Unit) {
        val bridge = MymavActions.calendar
        val cals = bridge?.writable()
        if (bridge == null || cals == null) {
            draft = null
            deliver(err("I can't add events yet: calendar write permission is off. Open MyMav Settings and tap Allow calendar."))
            return
        }
        if (cals.isEmpty()) { draft = null; deliver(err("I couldn't find a calendar on this phone that I can add events to.")); return }
        fun ask(a: Await, q: String) {
            d.await = a; d.expiresAt = System.currentTimeMillis() + 3 * 60_000L
            deliver(AssistantRouter.Outcome(q, AssistantLog.Kind.Local, emptyList(), "ask"))
        }
        if (d.title.isBlank()) return ask(Await.Title, "What should the event be called?")
        if (d.dayMs == null) return ask(Await.Day, "Which day is \"${d.title}\"? Say a day like tomorrow, Friday or 17 October.")
        if (!d.allDay && d.hour == null) return ask(Await.Time, "What time is \"${d.title}\" on ${fmtDay(d.dayMs!!)}?")
        if (d.ambiguous) {
            val h = d.hour!!
            return ask(Await.Meridiem, "Do you mean $h:${"%02d".format(d.minute)} in the morning or in the evening? Say am or pm.")
        }
        if (d.startMs() < System.currentTimeMillis() - 60_000L && !d.allDay) {
            d.dayMs = null
            return ask(Await.Day, "That time has already passed. Which day did you mean?")
        }
        if (d.calId == null) {
            val match = d.calName?.let { n -> cals.filter { it.name.contains(n, true) || it.account.contains(n, true) } }
            val pick = when {
                match != null && match.size == 1 -> match[0]
                match == null && cals.size == 1 -> cals[0]
                else -> null
            }
            if (pick != null) { d.calId = pick.id; d.calLabel = pick.name }
            else {
                val list = (match?.takeIf { it.isNotEmpty() } ?: cals).take(5)
                d.choices = list
                return ask(Await.Calendar, "Which calendar? Say the number or name.\n" + list.mapIndexed { i, c -> "${i + 1}. ${c.name}" }.joinToString("\n"))
            }
        }
        val s = d.startMs()
        val e = if (d.allDay) s + 24 * 3_600_000L else s + d.durationMin * 60_000L
        val when1 = if (d.allDay) "${fmtDay(s)} (all day)" else "${fmtDay(s)}, ${fmtTime(s)}-${fmtTime(e)}"
        val rem = d.reminder?.let { ", reminder $it min before" }.orEmpty()
        ask(Await.Confirm, "Add \"${d.title}\" on $when1 to ${d.calLabel}$rem? Say yes to add it, or no to cancel.")
    }

    /** True when [cleaned] was consumed as part of the pending event conversation. */
    fun continuePending(cleaned: String, deliver: (AssistantRouter.Outcome) -> Unit): Boolean {
        val d = draft ?: return false
        if (d.expiresAt < System.currentTimeMillis()) { draft = null; return false }
        val c = cleaned.trim().removeSuffix(".").removeSuffix("!")
        if (c in setOf("cancel", "never mind", "nevermind", "forget it", "stop", "no", "no thanks", "don't")) {
            draft = null
            deliver(out("Okay, I didn't add anything.")); return true
        }
        when (d.await) {
            Await.Title -> { d.title = cleaned.trim().replaceFirstChar { it.uppercase() } }
            Await.Day -> {
                val p = MymavWhen.parse(cleaned)
                if (p.dayMs == null) { deliver(out("I didn't catch the day. Try tomorrow, Friday or 17 October.")); return true }
                d.dayMs = p.dayMs
                if (p.hour != null && d.hour == null) { d.hour = p.hour; d.minute = p.minute; d.ambiguous = p.ambiguousMeridiem }
            }
            Await.Time -> {
                val p = MymavWhen.parse(cleaned)
                var h = p.hour; var ambiguous = p.ambiguousMeridiem; var m = p.minute
                if (h == null) {
                    Regex("^(\\d{1,2})(?::(\\d{2}))?$").find(c)?.let { r ->
                        h = r.groupValues[1].toInt(); m = r.groupValues[2].ifBlank { "0" }.toInt(); ambiguous = h in 1..12
                    }
                }
                if (h == null) { deliver(out("I didn't catch the time. Try 3 pm or 15:00.")); return true }
                d.hour = h; d.minute = m; d.ambiguous = ambiguous
            }
            Await.Meridiem -> {
                val pm = Regex("\\b(pm|p m|evening|afternoon|night)\\b").containsMatchIn(c)
                val am = Regex("\\b(am|a m|morning)\\b").containsMatchIn(c)
                if (!pm && !am) { deliver(out("Please say am or pm.")); return true }
                var h = d.hour ?: 0
                if (pm && h < 12) h += 12
                if (am && h == 12) h = 0
                d.hour = h; d.ambiguous = false
            }
            Await.Calendar -> {
                val byNum = c.removePrefix("number ").toIntOrNull()?.let { d.choices.getOrNull(it - 1) }
                val byName = d.choices.firstOrNull { c.contains(it.name.lowercase()) || it.name.lowercase().contains(c) }
                val pick = byNum ?: byName
                if (pick == null) { deliver(out("Please say the number or the name of the calendar.")); return true }
                d.calId = pick.id; d.calLabel = pick.name
            }
            Await.Confirm -> {
                if (c in setOf("yes", "yeah", "yep", "confirm", "add it", "do it", "yes please", "go ahead", "sure", "ok", "okay")) {
                    submit(d, deliver); return true
                }
                draft = null  // anything else is a new request, never a confirmation
                return false
            }
        }
        advance(d, deliver)
        return true
    }

    private fun submit(d: Draft, deliver: (AssistantRouter.Outcome) -> Unit) {
        if (submitting) { deliver(out("Still adding that event.")); return }
        val key = "${d.title}|${d.startMs()}|${d.calId}"
        if (key == lastDone && System.currentTimeMillis() - lastDoneAt < 120_000L) {
            draft = null
            deliver(out("That event is already in your calendar. I didn't add it twice.")); return
        }
        submitting = true
        val s = d.startMs()
        val e = if (d.allDay) s + 24 * 3_600_000L else s + d.durationMin * 60_000L
        io.execute {
            val r = runCatching { MymavActions.calendar!!.insert(d.calId!!, d.title, s, e, d.allDay, d.reminder) }
                .getOrElse { MymavActions.CalWrite(false, "The calendar write failed (${it.javaClass.simpleName}). Nothing was confirmed.") }
            submitting = false
            draft = null
            if (r.ok) {
                lastDone = key; lastDoneAt = System.currentTimeMillis()
                val when1 = if (d.allDay) "${fmtDay(s)} (all day)" else "${fmtDay(s)}, ${fmtTime(s)}-${fmtTime(e)}"
                deliver(out("Added \"${d.title}\" to ${d.calLabel} on $when1. It is saved in your calendar.", AssistantLog.Kind.Data, true))
            } else deliver(err(r.text))
        }
    }
}
