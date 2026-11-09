package com.mymav.glasses.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saved learning progress: for each note that was studied, how many cards were seen and answered correctly. */
object MymavLearning {
    class Progress(val noteId: String, val title: String, var seen: Int, var correct: Int, var lastAt: Long)

    private const val PREFS = "mymav_learning"
    @Volatile private var ctx: Context? = null
    private val items = LinkedHashMap<String, Progress>()

    fun install(context: Context) {
        ctx = context.applicationContext
        synchronized(items) {
            items.clear()
            val raw = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("items", null) ?: return
            runCatching {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) a.getJSONObject(i).let { items[it.getString("id")] = Progress(it.getString("id"), it.optString("title"), it.optInt("seen"), it.optInt("correct"), it.optLong("at")) }
            }
        }
    }

    fun all(): List<Progress> = synchronized(items) { items.values.sortedByDescending { it.lastAt } }

    fun record(noteId: String, title: String, correct: Boolean) = synchronized(items) {
        val p = items.getOrPut(noteId) { Progress(noteId, title, 0, 0, 0L) }
        p.seen++; if (correct) p.correct++; p.lastAt = System.currentTimeMillis()
        val a = JSONArray(); items.values.forEach { a.put(JSONObject().put("id", it.noteId).put("title", it.title).put("seen", it.seen).put("correct", it.correct).put("at", it.lastAt)) }
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("items", a.toString())?.apply()
    }

    fun clear() = synchronized(items) {
        items.clear(); ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("items")?.apply()
    }
}
