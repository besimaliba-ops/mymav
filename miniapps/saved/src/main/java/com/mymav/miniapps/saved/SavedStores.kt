package com.mymav.miniapps.saved

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Recipe Book persistence: JSON in SharedPreferences, photos in files/recipes. */
class RecipeStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mymav_recipes", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "recipes").also { it.mkdirs() }

    /** Newest first. */
    fun load(): List<SavedRecipe> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { SavedRecipe.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.savedAt }
    }

    private fun write(list: List<SavedRecipe>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("items", a.toString()).apply()
        com.mymav.glasses.api.HomeWidgetPreviewStore.invalidate("recipes")
    }

    fun add(name: String, meta: String, image: ByteArray?, ingredients: List<String>, steps: String): SavedRecipe {
        val id = UUID.randomUUID().toString()
        var path: String? = null
        if (image != null && image.isNotEmpty()) {
            val f = File(dir, "$id.jpg")
            runCatching { f.writeBytes(image); path = f.absolutePath }
        }
        val r = SavedRecipe(id, name, meta, path, ingredients, steps)
        // Same name saved again replaces the old copy.
        write(load().filterNot { it.name.equals(name, ignoreCase = true) } + r)
        return r
    }

    fun delete(id: String) {
        load().firstOrNull { it.id == id }?.imagePath?.let { runCatching { File(it).delete() } }
        write(load().filterNot { it.id == id })
    }

    fun find(query: String): SavedRecipe? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = load()
        all.firstOrNull { it.name.lowercase() == q }?.let { return it }
        all.firstOrNull { it.name.lowercase().contains(q) }?.let { return it }
        val words = q.split(' ').filter { it.length > 2 }
        return all.firstOrNull { r -> words.isNotEmpty() && words.all { r.name.lowercase().contains(it) } }
            ?: all.firstOrNull { r -> words.any { r.name.lowercase().contains(it) } }
    }
}

/** Notepad persistence: JSON in SharedPreferences, media copied into files/notes. */
class NoteStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mymav_notes", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "notes").also { it.mkdirs() }

    /** Most recently updated first. */
    fun load(): List<SavedNote> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { SavedNote.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
    }

    private fun write(list: List<SavedNote>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("items", a.toString()).apply()
        com.mymav.glasses.api.HomeWidgetPreviewStore.invalidate("notepad")
    }

    fun upsert(note: SavedNote) {
        write(load().filterNot { it.id == note.id } + note.copy(updatedAt = System.currentTimeMillis()))
    }

    fun create(title: String, body: String, attachments: List<NoteAttachment>): SavedNote {
        val n = SavedNote(UUID.randomUUID().toString(), title, body, attachments)
        upsert(n)
        return n
    }

    fun delete(id: String) {
        load().firstOrNull { it.id == id }?.attachments
            ?.filter { it.kind != "link" }?.forEach { runCatching { File(it.ref).delete() } }
        write(load().filterNot { it.id == id })
    }

    fun saveImageBytes(bytes: ByteArray): String? = runCatching {
        val f = File(dir, "${UUID.randomUUID()}.jpg")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    /** Copy a picked photo/video into app storage so it survives the picker's permission. */
    fun copyUri(uri: Uri, kind: String): String? = runCatching {
        val ext = if (kind == "video") "mp4" else "jpg"
        val f = File(dir, "${UUID.randomUUID()}.$ext")
        app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f.absolutePath
    }.getOrNull()

    fun find(query: String): SavedNote? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = load()
        all.firstOrNull { it.title.lowercase().contains(q) }?.let { return it }
        val words = q.split(' ').filter { it.length > 2 }
        return all.firstOrNull { n -> words.any { n.title.lowercase().contains(it) || n.body.lowercase().contains(it) } }
    }
}
