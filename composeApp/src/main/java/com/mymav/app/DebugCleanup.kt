package com.mymav.app

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.mymav.miniapps.saved.NoteStore

/**
 * Test-only (debug builds): lists, then deletes by exact id, the artefacts a verification run created.
 * `dump` only reads. `delete` removes exactly the ids it is given and nothing else.
 */
object DebugCleanup {
    fun dump(ctx: Context) {
        NoteStore(ctx).load().filter { it.title.startsWith("Meeting ") }.forEach {
            Log.i("DBGCLEAN", "NOTE id=${it.id} title='${it.title}' created=${java.util.Date(it.createdAt)} bodyLen=${it.body.length} body='${it.body.take(80).replace('\n', ' ')}'")
        }
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.ACCOUNT_NAME),
                "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED}=0", arrayOf("%test event%"), null,
            )?.use { c -> while (c.moveToNext()) Log.i("DBGCLEAN", "EVENT id=${c.getLong(0)} title='${c.getString(1)}' start=${java.util.Date(c.getLong(2))} cal=${c.getLong(3)} account=${c.getString(4)}") }
        }.onFailure { Log.i("DBGCLEAN", "event query failed: $it") }
        Log.i("DBGCLEAN", "dump done")
    }

    /** arg: "note:<id>,note:<id>,event:<id>" */
    fun delete(ctx: Context, arg: String) {
        arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tok ->
            val (kind, id) = tok.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (kind) {
                "note" -> { val s = NoteStore(ctx); if (s.load().any { it.id == id }) { s.delete(id); Log.i("DBGCLEAN", "deleted note $id") } else Log.i("DBGCLEAN", "note $id not found") }
                "event" -> {
                    val n = runCatching { ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong()), null, null) }.getOrDefault(-1)
                    Log.i("DBGCLEAN", "event $id delete rows=$n")
                }
                else -> Log.i("DBGCLEAN", "ignored '$tok'")
            }
        }
    }
}
