package com.mymav.miniapps.memorysight

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MemoryDetection(
    val id: String = UUID.randomUUID().toString(),
    val item: String,
    val place: String,
    val timeLabel: String,
    val context: String = "",
) {
    fun hudLine(): String = "$timeLabel · $item @ $place" + context.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("item", item)
        .put("place", place)
        .put("timeLabel", timeLabel)
        .put("context", context)

    companion object {
        fun fromJson(o: JSONObject): MemoryDetection = MemoryDetection(
            id = o.optString("id", UUID.randomUUID().toString()),
            item = o.optString("item", "Item"),
            place = o.optString("place", "Unknown"),
            timeLabel = o.optString("timeLabel", ""),
            context = o.optString("context", ""),
        )

        fun listToJson(items: List<MemoryDetection>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<MemoryDetection> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}
