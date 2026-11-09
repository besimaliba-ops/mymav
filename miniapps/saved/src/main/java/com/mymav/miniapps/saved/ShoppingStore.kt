package com.mymav.miniapps.saved

import android.content.Context
import com.mymav.glasses.api.HomeWidgetPreviewStore
import org.json.JSONArray
import org.json.JSONObject

class ShopItem(val name: String, val done: Boolean)

/** Shopping list persistence (SharedPreferences JSON). */
class ShoppingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mymav_shopping", Context.MODE_PRIVATE)

    fun load(): List<ShopItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { val o = a.getJSONObject(it); ShopItem(o.getString("n"), o.optBoolean("d")) }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<ShopItem>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("d", it.done)) }
        prefs.edit().putString("items", a.toString()).apply()
        HomeWidgetPreviewStore.invalidate("shopping")
    }

    /** Adds items, skipping ones already on the list. Returns how many were new. */
    fun add(names: List<String>): Int {
        val cur = load().toMutableList()
        var n = 0
        names.map { it.trim() }.filter { it.isNotBlank() }.forEach { nm ->
            val i = cur.indexOfFirst { it.name.equals(nm, ignoreCase = true) }
            if (i < 0) { cur += ShopItem(nm, false); n++ } else if (cur[i].done) cur[i] = ShopItem(cur[i].name, false)
        }
        write(cur)
        return n
    }

    fun toggle(name: String) = write(load().map { if (it.name == name) ShopItem(it.name, !it.done) else it })
    fun delete(name: String) = write(load().filterNot { it.name == name })
    fun clearDone() = write(load().filterNot { it.done })
    fun clearAll() = write(emptyList())

    /** Removes the best match for [query]; returns its name or null. */
    fun remove(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = load()
        val hit = all.firstOrNull { it.name.lowercase() == q }
            ?: all.firstOrNull { it.name.lowercase().contains(q) }
            ?: all.firstOrNull { q.split(' ').filter { w -> w.length > 2 }.any { w -> it.name.lowercase().contains(w) } }
            ?: return null
        write(all.filterNot { it === hit })
        return hit.name
    }
}
