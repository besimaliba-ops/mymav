package com.mymav.miniapps.saved

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HomeWidgetPreviewStore
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.MymavActions
import com.mymav.glasses.api.MymavWeb
import com.mymav.glasses.api.miniAppHomeActions

/** Shared setup for Recipe Book + Notepad: stores, MyMav AI bridges, home widget data. */
object SavedApps {
    @Volatile var recipesStore: RecipeStore? = null
    @Volatile var notesStore: NoteStore? = null
    @Volatile var shoppingStore: ShoppingStore? = null

    fun install(context: Context) {
        val app = context.applicationContext
        if (recipesStore == null) recipesStore = RecipeStore(app)
        if (notesStore == null) notesStore = NoteStore(app)
        if (shoppingStore == null) shoppingStore = ShoppingStore(app)
        val ss = shoppingStore!!
        val rs = recipesStore!!
        val ns = notesStore!!

        HomeWidgetPreviewStore.provide("recipes") {
            val all = rs.load()
            HomeWidgetPreviewStore.Preview(
                title = "Recipes", subtitle = "Recipe book",
                metric = all.size.toString(), metricDetail = "saved",
                lines = all.take(6).map { "|" + it.name },
            )
        }
        HomeWidgetPreviewStore.provide("notepad") {
            val all = ns.load()
            HomeWidgetPreviewStore.Preview(
                title = "Notepad", subtitle = "Notes",
                metric = all.size.toString(), metricDetail = "notes",
                lines = all.take(6).map { "|" + it.title },
            )
        }

        HomeWidgetPreviewStore.provide("shopping") {
            val open = ss.load().filterNot { it.done }
            HomeWidgetPreviewStore.Preview(
                title = "Shopping", subtitle = "Shopping list",
                metric = open.size.toString(), metricDetail = "items",
                lines = open.take(6).map { "|" + it.name },
            )
        }

        MymavActions.shopping = object : MymavActions.ShoppingBridge {
            override fun add(items: List<String>): String {
                val n = ss.add(items)
                val total = items.size
                return when {
                    total == 1 && n == 1 -> "Added ${items[0]} to your shopping list."
                    n == 0 -> "Already on your shopping list."
                    else -> "Added $n item${if (n > 1) "s" else ""} to your shopping list."
                }
            }

            override fun items(): List<String> = ss.load().filterNot { it.done }.map { it.name }

            override fun remove(query: String): String =
                ss.remove(query)?.let { "Removed $it from your shopping list." } ?: "I couldn't find \"$query\" on your list."

            override fun clearAll(): String { ss.clearAll(); return "Shopping list cleared." }
        }

        MymavActions.recipes = object : MymavActions.RecipeBridge {
            override fun save(recipe: MymavWeb.Recipe): String {
                rs.add(recipe.name, recipe.meta, recipe.image, recipe.ingredients, recipe.steps)
                return "Saved \"${recipe.name}\" to your Recipe Book."
            }

            override fun names(): List<String> = rs.load().map { it.name }

            override fun find(query: String): MymavWeb.Recipe? = rs.find(query)?.let { r ->
                MymavWeb.Recipe(
                    r.name, r.meta, r.imagePath?.let { p -> runCatching { java.io.File(p).readBytes() }.getOrNull() },
                    r.ingredients, r.steps,
                )
            }
        }

        MymavActions.notes = object : MymavActions.NoteBridge {
            override fun save(title: String, text: String, images: List<ByteArray>, links: List<String>): String {
                val atts = ArrayList<NoteAttachment>()
                images.forEach { b -> ns.saveImageBytes(b)?.let { atts += NoteAttachment("image", it) } }
                links.forEach { atts += NoteAttachment("link", it) }
                ns.create(title.ifBlank { "Note" }, text, atts)
                val extra = if (atts.isNotEmpty()) " with ${atts.size} attachment" + (if (atts.size > 1) "s" else "") else ""
                return "Saved \"${title.take(40)}\" to your Notepad$extra."
            }

            override fun saveOrUpdate(id: String, title: String, text: String): String {
                val existing = ns.load().firstOrNull { it.id == id }
                ns.upsert(
                    existing?.copy(title = title.ifBlank { existing.title }, body = text)
                        ?: SavedNote(id, title.ifBlank { "Note" }, text),
                )
                return if (existing != null) "Updated \"${title.take(40)}\" in your Notepad." else "Saved \"${title.take(40)}\" to your Notepad."
            }

            override fun titles(): List<String> = ns.load().map { it.title }

            override fun find(query: String): Pair<String, String>? =
                ns.find(query)?.let { it.title to it.body }

            override fun search(words: List<String>, fromMs: Long, toMs: Long): List<MymavActions.NoteHit> {
                val ws = words.map { it.lowercase() }.filter { it.length > 2 }
                return ns.load().filter { n ->
                    val t = maxOf(n.updatedAt, n.createdAt)
                    (t in fromMs..toMs || n.createdAt in fromMs..toMs) &&
                        (ws.isEmpty() || ws.any { n.title.lowercase().contains(it) || n.body.lowercase().contains(it) })
                }.sortedByDescending { it.updatedAt }
                    .map { MymavActions.NoteHit(it.id, it.title, it.body, it.createdAt, it.updatedAt) }
            }
        }
    }
}

