package com.mymav.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.mymav.glasses.api.MymavActions

/** Reads upcoming calendar events for the daily briefing (needs READ_CALENDAR). */
class MyMavCalendar(private val context: Context) : MymavActions.CalendarBridge {
    companion object {
        fun granted(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        fun canWrite(context: Context) =
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    override fun upcoming(limit: Int): List<MymavActions.CalEvent>? {
        if (!granted(context)) return null
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now)
        android.content.ContentUris.appendId(builder, now + 48L * 3_600_000L)
        val out = ArrayList<MymavActions.CalEvent>()
        runCatching {
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit * 3) {
                    val allDay = c.getInt(3) == 1
                    val end = c.getLong(2)
                    if (!allDay && end < now) continue
                    out += MymavActions.CalEvent(
                        c.getString(0)?.ifBlank { null } ?: "(No title)", c.getLong(1), end, allDay, c.getString(4).orEmpty(),
                    )
                }
            }
        }
        // Timed events first by start; all-day last.
        return out.sortedWith(compareBy({ it.allDay }, { it.startMs })).take(limit)
    }

    override fun between(fromMs: Long, toMs: Long, limit: Int): List<MymavActions.CalEvent>? {
        if (!granted(context)) return null
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, fromMs)
        android.content.ContentUris.appendId(builder, toMs)
        val out = ArrayList<MymavActions.CalEvent>()
        runCatching {
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += MymavActions.CalEvent(
                        c.getString(0)?.ifBlank { null } ?: "(No title)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4).orEmpty(),
                    )
                }
            }
        }
        return out
    }

    override fun writable(): List<MymavActions.CalendarInfo>? {
        if (!granted(context) || !canWrite(context)) return null
        val out = ArrayList<MymavActions.CalendarInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.VISIBLE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || c.getInt(4) != 1) continue
                    out += MymavActions.CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
                }
            }
        }
        return out
    }

    override fun insert(
        calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, reminderMinutes: Int?,
    ): MymavActions.CalWrite {
        if (!granted(context) || !canWrite(context)) return MymavActions.CalWrite(false, "Calendar write permission is off. Allow it in MyMav Settings.")
        return try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes != null) 1 else 0)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return MymavActions.CalWrite(false, "The calendar refused the event. Nothing was added.")
            val id = android.content.ContentUris.parseId(uri)
            if (reminderMinutes != null) {
                val r = android.content.ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
            }
            // Read it back: only a verified row counts as success.
            val ok = context.contentResolver.query(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getString(0) == title && c.getLong(1) == startMs } == true
            if (ok) MymavActions.CalWrite(true, "event $id") else MymavActions.CalWrite(false, "I wrote the event but could not confirm it was saved. Check your calendar.")
        } catch (e: SecurityException) {
            MymavActions.CalWrite(false, "Calendar write permission is off. Allow it in MyMav Settings.")
        } catch (e: Exception) {
            MymavActions.CalWrite(false, "The calendar write failed (${e.javaClass.simpleName}). Nothing was confirmed.")
        }
    }
}
