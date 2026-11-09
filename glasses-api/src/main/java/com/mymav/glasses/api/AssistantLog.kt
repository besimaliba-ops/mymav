package com.mymav.glasses.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the assistant answered, where the answer came from, and the sources behind it.
 * Persisted on the phone (private prefs, last [MAX] entries) so the phone can show source names and links and
 * so follow-up questions can see the recent conversation. Never stores API keys.
 */
object AssistantLog {
    private const val PREFS = "mymav_assistant_log"
    private const val MAX = 40

    /** How an answer was produced. Only [Web] and [Data] are retrieved; [Ai] is a model answer that was not searched. */
    enum class Kind(val label: String, val short: String) {
        Web("Retrieved from the web", "Web"),
        Data("Retrieved from a data service", "Data"),
        Ai("AI answer - not searched, may be wrong", "AI"),
        Local("Calculated on this phone", "Calc"),
        Error("Not completed", "Error"),
    }

    class Source(val name: String, val url: String)

    class Entry(
        val id: Long,
        val at: Long,
        val query: String,
        val answer: String,
        val kind: Kind,
        val sources: List<Source>,
        val note: String = "",
    )

    @Volatile private var appContext: Context? = null
    private val entries = ArrayList<Entry>()
    @Volatile var onChanged: (() -> Unit)? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        synchronized(entries) {
            if (entries.isNotEmpty()) return
            val raw = prefs()?.getString("entries", null) ?: return
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val src = o.optJSONArray("sources") ?: JSONArray()
                    entries += Entry(
                        o.getLong("id"), o.getLong("at"), o.getString("q"), o.getString("a"),
                        Kind.entries.firstOrNull { it.name == o.optString("k") } ?: Kind.Ai,
                        (0 until src.length()).map { Source(src.getJSONObject(it).optString("n"), src.getJSONObject(it).optString("u")) },
                        o.optString("note"),
                    )
                }
            }
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(query: String, answer: String, kind: Kind, sources: List<Source> = emptyList(), note: String = ""): Entry {
        val e = Entry(System.currentTimeMillis(), System.currentTimeMillis(), query.take(300), answer.take(1500), kind, sources.take(8), note)
        synchronized(entries) {
            entries += e
            while (entries.size > MAX) entries.removeAt(0)
            persist()
        }
        onChanged?.invoke()
        return e
    }

    fun recent(): List<Entry> = synchronized(entries) { entries.toList().asReversed() }

    /** Last [n] successful (question, answer) turns, oldest first, for follow-up context. */
    fun recentTurns(n: Int): List<Pair<String, String>> = synchronized(entries) {
        entries.filter { it.kind != Kind.Error }.takeLast(n).map { it.query to it.answer }
    }

    fun clear() {
        synchronized(entries) { entries.clear(); persist() }
        onChanged?.invoke()
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("at", e.at).put("q", e.query).put("a", e.answer).put("k", e.kind.name)
                    .put("note", e.note)
                    .put("sources", JSONArray().also { s -> e.sources.forEach { s.put(JSONObject().put("n", it.name).put("u", it.url)) } }),
            )
        }
        prefs()?.edit()?.putString("entries", arr.toString())?.apply()
    }
}
