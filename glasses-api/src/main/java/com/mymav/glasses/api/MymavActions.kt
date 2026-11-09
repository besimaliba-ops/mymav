package com.mymav.glasses.api

/** Bridges MyMav AI to mini-apps that live in other modules. */
object MymavActions {
    interface TaskBridge {
        /** Add a task; returns a short confirmation. [dueEpochMs] 0 = no date. */
        fun add(title: String, dueEpochMs: Long, hasTime: Boolean): String

        /** Today's open tasks in due order: "HH:MM|Title" ("" time = anytime). */
        fun todayLines(): List<String>
    }

    interface RecipeBridge {
        fun save(recipe: MymavWeb.Recipe): String
        fun names(): List<String>
        fun find(query: String): MymavWeb.Recipe?
    }

    interface NoteBridge {
        /** Save a note (text + pictures + video links); returns a short confirmation. */
        fun save(title: String, text: String, images: List<ByteArray>, links: List<String>): String
        /** Create or replace the note with this stable [id] (one note per session, not one per save). */
        fun saveOrUpdate(id: String, title: String, text: String): String = save(title, text, emptyList(), emptyList())
        fun titles(): List<String>
        /** (title, body) of the best matching note, or null. */
        fun find(query: String): Pair<String, String>?
        /** Saved notes touched between [fromMs] and [toMs] that mention any of [words] (all notes when empty), newest first. */
        fun search(words: List<String>, fromMs: Long, toMs: Long): List<NoteHit> = emptyList()
    }

    class NoteHit(val id: String, val title: String, val body: String, val createdAt: Long, val updatedAt: Long)

    interface ShoppingBridge {
        /** Add items; returns a short confirmation. */
        fun add(items: List<String>): String
        /** Open (not ticked) items. */
        fun items(): List<String>
        fun remove(query: String): String
        fun clearAll(): String
    }

    class CalEvent(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean, val location: String)

    class CalendarInfo(val id: Long, val name: String, val account: String)
    class CalWrite(val ok: Boolean, val text: String)

    interface CalendarBridge {
        /** Upcoming events (from now), or null when calendar access isn't granted. */
        fun upcoming(limit: Int): List<CalEvent>?

        /** Events overlapping [fromMs, toMs), or null when read access isn't granted. */
        fun between(fromMs: Long, toMs: Long, limit: Int): List<CalEvent>? = null

        /** Calendars the user can add events to, or null when write access isn't granted. */
        fun writable(): List<CalendarInfo>? = null

        /** Adds the event and reads it back; [ok] is true only when the saved event was verified. */
        fun insert(
            calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, reminderMinutes: Int?,
        ): CalWrite = CalWrite(false, "This build cannot write to the calendar.")
    }

    class Contact(val name: String, val number: String)
    /** A recent WhatsApp/Instagram message whose notification can be replied to. */
    class ReplyTarget(val app: String, val sender: String, val key: String, val packageName: String)
    class SendResult(val ok: Boolean, val text: String)

    interface MessageBridge {
        /** Contacts matching [name], or null when contacts permission is off. */
        fun findContacts(name: String): List<Contact>?
        fun canSendSms(): Boolean
        /** Recent messages from people matching [name] that can be replied to from their notification. */
        fun replyTargets(name: String): List<ReplyTarget>
        /** Runs off the main thread; [done] reports what actually happened. */
        fun sendSms(number: String, text: String, done: (SendResult) -> Unit)
        fun reply(target: ReplyTarget, text: String, done: (SendResult) -> Unit)
        /** Opens the phone dialler with [number] filled in. The user still presses call; [done] says only whether it opened. */
        fun dial(number: String, done: (SendResult) -> Unit)
    }

    @Volatile
    var messages: MessageBridge? = null

    /** Meeting recorder. [done] says what actually happened (recording started / stopped and what is being done with it). */
    interface MeetingBridge {
        fun recording(): Boolean
        fun start(done: (SendResult) -> Unit)
        fun stop(done: (SendResult) -> Unit)
    }

    @Volatile
    var meeting: MeetingBridge? = null

    class SpotifyOutcome(val ok: Boolean, val text: String)

    /** Voice control of the connected Spotify account. [cmd] = play | pause | resume | next | previous | current | search | mood. */
    interface SpotifyBridge {
        fun connected(): Boolean
        /** Runs off the main thread; [done] is called on any thread exactly once, with the real result. */
        fun run(cmd: String, query: String, done: (SpotifyOutcome) -> Unit)
    }

    @Volatile
    var spotify: SpotifyBridge? = null

    @Volatile
    var shopping: ShoppingBridge? = null

    @Volatile
    var calendar: CalendarBridge? = null

    @Volatile
    var tasks: TaskBridge? = null

    @Volatile
    var recipes: RecipeBridge? = null

    @Volatile
    var notes: NoteBridge? = null
}
