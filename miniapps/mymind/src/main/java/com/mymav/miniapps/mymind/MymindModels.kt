package com.mymav.miniapps.mymind

import com.mymav.glasses.api.MymindTaskPage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MymindPerson(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val note: String = "",
)

data class MymindMemory(
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    val transcriptSnippet: String = "",
    val peopleIds: List<String> = emptyList(),
)

data class MymindTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val page: MymindTaskPage,
    val done: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("page", page.name)
        .put("done", done)

    companion object {
        fun fromJson(o: JSONObject): MymindTask = MymindTask(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "Task"),
            page = runCatching { MymindTaskPage.valueOf(o.optString("page", "Daily")) }
                .getOrDefault(MymindTaskPage.Daily),
            done = o.optBoolean("done", false),
        )

        fun listToJson(tasks: List<MymindTask>): String {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(raw: String?): List<MymindTask> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}
