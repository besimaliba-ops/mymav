package com.mymav.miniapps.saved

import org.json.JSONArray
import org.json.JSONObject

data class SavedRecipe(
    val id: String,
    val name: String,
    val meta: String = "",
    /** Absolute path of the saved photo, or null. */
    val imagePath: String? = null,
    val ingredients: List<String> = emptyList(),
    val steps: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("meta", meta)
        .put("imagePath", imagePath ?: "")
        .put("ingredients", JSONArray(ingredients))
        .put("steps", steps).put("savedAt", savedAt)

    companion object {
        fun fromJson(o: JSONObject): SavedRecipe {
            val ing = o.optJSONArray("ingredients")
            return SavedRecipe(
                id = o.optString("id"),
                name = o.optString("name", "Recipe"),
                meta = o.optString("meta"),
                imagePath = o.optString("imagePath").takeIf { it.isNotBlank() },
                ingredients = if (ing == null) emptyList() else (0 until ing.length()).map { ing.getString(it) },
                steps = o.optString("steps"),
                savedAt = o.optLong("savedAt", 0L),
            )
        }
    }
}

/** kind = "image" | "video" | "link"; ref = absolute file path (image/video) or URL (link). */
data class NoteAttachment(val kind: String, val ref: String, val caption: String = "") {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("ref", ref).put("caption", caption)

    companion object {
        fun fromJson(o: JSONObject) = NoteAttachment(o.optString("kind"), o.optString("ref"), o.optString("caption"))
    }
}

data class SavedNote(
    val id: String,
    val title: String,
    val body: String = "",
    val attachments: List<NoteAttachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("body", body)
        .put("attachments", JSONArray().also { a -> attachments.forEach { a.put(it.toJson()) } })
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): SavedNote {
            val arr = o.optJSONArray("attachments")
            return SavedNote(
                id = o.optString("id"),
                title = o.optString("title", "Note"),
                body = o.optString("body"),
                attachments = if (arr == null) emptyList() else (0 until arr.length()).map { NoteAttachment.fromJson(arr.getJSONObject(it)) },
                createdAt = o.optLong("createdAt", 0L),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }
}
