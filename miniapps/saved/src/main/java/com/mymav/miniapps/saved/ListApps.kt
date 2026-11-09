package com.mymav.miniapps.saved

import android.os.Handler
import android.os.Looper
import com.mymav.glasses.api.AskPager
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.ListAppActions
import com.mymav.glasses.api.ListDetail
import com.mymav.glasses.api.ListPill
import com.mymav.glasses.api.ListRow
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.MymavActions
import com.mymav.glasses.api.MymavKitchen
import com.mymav.glasses.api.MymavWeb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val main = Handler(Looper.getMainLooper())
private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
private val fileCache = LinkedHashMap<String, ByteArray?>()

/** File bytes for thumbnails/pictures, cached so the HUD can reuse decoded textures. */
private fun fileBytes(path: String?): ByteArray? {
    if (path == null) return null
    synchronized(fileCache) {
        if (fileCache.containsKey(path)) return fileCache[path]
        if (fileCache.size > 14) fileCache.clear()
        val b = runCatching { File(path).takeIf { it.length() in 1..8_000_000 }?.readBytes() }.getOrNull()
        fileCache[path] = b
        return b
    }
}

private fun pagesOf(text: String, chars: Int): List<String> =
    AskPager.wrap(text, chars).chunked(8).map { it.joinToString("\n") }.ifEmpty { listOf("") }

/** Shared glue: focus handling, keyboard round-trips, "note" toast in the header. */
abstract class ListAppBase(private val appId: String) : MiniApp {
    protected var session: GlassesSession? = null
    protected var focus = 0
    protected var note = ""

    protected abstract fun hud(): GlassesHud.ListApp

    fun refresh() { session?.replaceHud(hud()) }

    override fun onLeave(session: GlassesSession) = Unit

    protected fun actions(total: Int, onSelect: () -> Unit) = ListAppActions(
        onFocusChanged = { i -> focus = i.coerceIn(0, (total - 1).coerceAtLeast(0)); note = ""; refresh() },
        onSelect = onSelect,
        onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
        onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
    )

    protected fun ask(title: String, initial: String = "", done: (String) -> Unit) {
        GlassesHomeController.openKeyboard(
            title = title,
            initial = initial,
            searchSubmitsBuffer = false,
            doneLabel = "Save",
            onDone = { t ->
                GlassesHomeController.markMiniAppOpen(appId)
                if (t.isNotBlank()) done(t.trim())
                refresh()
            },
            onCancel = {
                GlassesHomeController.markMiniAppOpen(appId)
                refresh()
            },
        )
    }
}

// ----------------------------------------------------------------------------------------------
// Shopping
// ----------------------------------------------------------------------------------------------

object ShoppingMiniApp : ListAppBase("shopping") {
    override val descriptor = MiniAppDescriptor(
        id = "shopping", title = "Shopping", subtitle = "Your shopping list",
        route = "mini/shopping", requiredCapabilities = listOf("local-store", "hud"),
    )

    private var lastRow = 0

    private class Cat(val label: String, val tone: HudAccentTone, val words: List<String>)

    private val cats = listOf(
        Cat("produce", HudAccentTone.Green, listOf("apple", "banana", "carrot", "onion", "garlic", "potato", "tomato", "lettuce", "pepper", "lemon", "lime", "veg", "fruit", "spinach", "mushroom", "broccoli", "ginger", "coriander", "parsley", "basil", "chilli", "cucumber", "avocado", "berries", "grape", "orange", "celery", "leek", "herb", "salad")),
        Cat("dairy", HudAccentTone.Blue, listOf("milk", "cheese", "butter", "yoghurt", "yogurt", "cream", "egg", "custard")),
        Cat("meat", HudAccentTone.Red, listOf("chicken", "beef", "pork", "lamb", "bacon", "sausage", "fish", "salmon", "tuna", "prawn", "mince", "steak", "ham", "turkey")),
        Cat("pantry", HudAccentTone.Yellow, listOf("bread", "flour", "rice", "pasta", "noodle", "bagel", "cereal", "oat", "tortilla", "sugar", "oil", "sauce", "stock", "spice", "salt", "beans", "lentil", "tin", "curry", "coconut", "honey", "jam")),
        Cat("home", HudAccentTone.Purple, listOf("soap", "paper", "tissue", "detergent", "bin", "foil", "sponge", "toothpaste", "shampoo", "bleach", "wash", "towel", "bag")),
    )

    private fun cat(name: String): Cat? {
        val n = name.lowercase()
        return cats.firstOrNull { c -> c.words.any { n.contains(it) } }
    }

    override fun onEnter(session: GlassesSession) {
        this.session = session
        focus = 0; note = ""
        GlassesHomeController.markMiniAppOpen("shopping")
        refresh()
    }

    fun pushHud(session: GlassesSession) { this.session = session; refresh() }

    override fun hud(): GlassesHud.ListApp {
        val st = SavedApps.shoppingStore
        val items = st?.load().orEmpty()
        val pills = listOf(
            ListPill("ADD", HudAccentTone.Green),
            ListPill("DELETE", HudAccentTone.Red),
            ListPill("CLEAR DONE", HudAccentTone.Blue),
        )
        val total = items.size + pills.size
        focus = focus.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (focus < items.size) lastRow = focus
        val open = items.count { !it.done }
        return GlassesHud.ListApp(
            appId = "shopping", title = "Shopping", tone = HudAccentTone.Green,
            rows = items.map { i ->
                val c = cat(i.name)
                ListRow(
                    id = i.name, title = i.name, trailing = c?.label.orEmpty(),
                    tone = c?.tone ?: HudAccentTone.Teal, badge = i.name.take(1), checked = i.done,
                )
            },
            focusedIndex = focus, pills = pills,
            statusLine = note.ifBlank { if (items.isEmpty()) "" else "$open to buy" },
            emptyTitle = "List is empty",
            emptyHint = "Press ADD, or say: add milk to my shopping list",
            actions = actions(total) {
                when {
                    focus < items.size -> { st?.toggle(items[focus].name); refresh() }
                    focus == items.size -> ask("Add to shopping list") { t ->
                        val n = st?.add(MymavKitchen.splitItems(t)) ?: 0
                        note = "Added $n"
                    }
                    focus == items.size + 1 -> {
                        val row = items.getOrNull(lastRow)
                        if (row != null) { st?.delete(row.name); note = "Removed ${row.name.take(14)}"; focus = items.size }
                        refresh()
                    }
                    else -> { st?.clearDone(); note = "Cleared ticked"; refresh() }
                }
            },
        )
    }
}

// ----------------------------------------------------------------------------------------------
// Recipes
// ----------------------------------------------------------------------------------------------

object RecipeBookMiniApp : ListAppBase("recipes") {
    override val descriptor = MiniAppDescriptor(
        id = "recipes", title = "Recipes", subtitle = "Recipes MyMav found for you",
        route = "mini/recipes", requiredCapabilities = listOf("local-store", "hud"),
    )

    private var detailId: String? = null
    private var page = 0
    private var lastRow = 0

    override fun onEnter(session: GlassesSession) {
        this.session = session
        focus = 0; note = ""; detailId = null; page = 0
        GlassesHomeController.markMiniAppOpen("recipes")
        refresh()
    }

    fun pushHud(session: GlassesSession) { this.session = session; refresh() }

    private fun pagesFor(r: SavedRecipe): List<Pair<String, ByteArray?>> {
        val out = ArrayList<Pair<String, ByteArray?>>()
        val img = fileBytes(r.imagePath)
        val head = listOfNotNull(r.meta.takeIf { it.isNotBlank() }, "${r.ingredients.size} ingredients")
        val ing = r.ingredients.flatMap { AskPager.wrap("- $it", if (img != null) 44 else 80) }
        val all = head + ing
        val per = 8
        all.chunked(per).forEachIndexed { i, c -> out += c.joinToString("\n") to (if (i == 0) img else null) }
        val steps = MymavKitchen.splitSteps(r.steps)
        val stepLines = steps.flatMapIndexed { i, s -> AskPager.wrap("${i + 1}. $s", 78) }
        stepLines.chunked(8).forEach { out += it.joinToString("\n") to null }
        return out.ifEmpty { listOf(r.name to img) }
    }

    override fun hud(): GlassesHud.ListApp {
        val st = SavedApps.recipesStore
        val all = st?.load().orEmpty()
        val open = all.firstOrNull { it.id == detailId }
        if (open != null) return detailHud(st, open)
        detailId = null
        val pills = listOf(ListPill("FIND", HudAccentTone.Orange), ListPill("DELETE", HudAccentTone.Red))
        val total = all.size + pills.size
        focus = focus.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (focus < all.size) lastRow = focus
        return GlassesHud.ListApp(
            appId = "recipes", title = "Recipes", tone = HudAccentTone.Orange,
            rows = all.map { r ->
                ListRow(
                    id = r.id, title = r.name,
                    subtitle = "${r.ingredients.size} ingredients" + if (r.meta.isNotBlank()) " - ${r.meta}" else "",
                    tone = HudAccentTone.Orange, badge = r.name.take(1),
                    imageBytes = fileBytes(r.imagePath),
                )
            },
            focusedIndex = focus, pills = pills,
            statusLine = note.ifBlank { if (all.isEmpty()) "" else "${all.size} saved" },
            emptyTitle = "No recipes yet",
            emptyHint = "Press FIND, or ask MyMav: recipe for lasagne, then SAVE",
            actions = actions(total) {
                when {
                    focus < all.size -> { detailId = all[focus].id; page = 0; focus = 0; note = ""; refresh() }
                    focus == all.size -> ask("Find a recipe") { q ->
                        note = "Searching..."
                        refresh()
                        io.execute {
                            val r = runCatching { MymavWeb.findRecipe(q) }.getOrNull()
                            main.post {
                                if (r != null) {
                                    val saved = st?.add(r.name, r.meta, r.image, r.ingredients, r.steps)
                                    detailId = saved?.id; page = 0; focus = 0; note = "Saved"
                                } else note = "Not found"
                                refresh()
                            }
                        }
                    }
                    else -> {
                        val r = all.getOrNull(lastRow)
                        if (r != null) { st?.delete(r.id); note = "Deleted"; }
                        refresh()
                    }
                }
            },
        )
    }

    private fun detailHud(st: RecipeStore?, r: SavedRecipe): GlassesHud.ListApp {
        val pages = pagesFor(r)
        page = page.coerceIn(0, pages.lastIndex)
        val pills = listOf(
            ListPill(if (pages.size > 1) "NEXT" else "-", HudAccentTone.Blue),
            ListPill("COOK", HudAccentTone.Red),
            ListPill("+LIST", HudAccentTone.Green),
            ListPill("DELETE", HudAccentTone.Orange),
            ListPill("BACK", HudAccentTone.Teal),
        )
        focus = focus.coerceIn(0, pills.lastIndex)
        val (text, img) = pages[page]
        return GlassesHud.ListApp(
            appId = "recipes", title = "Recipes", tone = HudAccentTone.Orange,
            focusedIndex = focus, pills = pills,
            detail = ListDetail(r.name, text, img, note.ifBlank { "Page ${page + 1}/${pages.size}" }),
            actions = actions(pills.size) {
                when (focus) {
                    0 -> { page = (page + 1) % pages.size; note = ""; refresh() }
                    1 -> GlassesHomeController.startCookingFromApp(
                        MymavWeb.Recipe(r.name, r.meta, fileBytes(r.imagePath), r.ingredients, r.steps),
                    )
                    2 -> {
                        note = runCatching { MymavActions.shopping?.add(r.ingredients) }.getOrNull()
                            ?.let { "On shopping list" } ?: "No list"
                        refresh()
                    }
                    3 -> { st?.delete(r.id); detailId = null; focus = 0; note = "Deleted"; refresh() }
                    else -> { detailId = null; focus = 0; note = ""; refresh() }
                }
            },
        )
    }
}

// ----------------------------------------------------------------------------------------------
// Notepad
// ----------------------------------------------------------------------------------------------

object NotepadMiniApp : ListAppBase("notepad") {
    override val descriptor = MiniAppDescriptor(
        id = "notepad", title = "Notepad", subtitle = "Notes, pictures and videos MyMav saved",
        route = "mini/notepad", requiredCapabilities = listOf("local-store", "hud"),
    )

    private var detailId: String? = null
    private var page = 0
    private var lastRow = 0

    override fun onEnter(session: GlassesSession) {
        this.session = session
        focus = 0; note = ""; detailId = null; page = 0
        GlassesHomeController.markMiniAppOpen("notepad")
        refresh()
    }

    fun pushHud(session: GlassesSession) { this.session = session; refresh() }

    private fun pagesFor(n: SavedNote): List<Pair<String, ByteArray?>> {
        val out = ArrayList<Pair<String, ByteArray?>>()
        val images = n.attachments.filter { it.kind == "image" }.mapNotNull { fileBytes(it.ref) }
        val extras = n.attachments.filter { it.kind != "image" }.map {
            (if (it.kind == "video") "Video: " else "Link: ") + it.ref.substringAfterLast('/')
        }
        val first = images.firstOrNull()
        val body = (n.body.ifBlank { n.title } + if (extras.isNotEmpty()) "\n\n" + extras.joinToString("\n") else "")
        val chunks = pagesOf(body, if (first != null) 44 else 80)
        // Picture-led first page keeps the narrow text next to the image.
        chunks.forEachIndexed { i, c -> out += c to (if (i == 0) first else null) }
        images.drop(1).forEachIndexed { i, b -> out += "Picture ${i + 2}/${images.size}" to b }
        return out
    }

    override fun hud(): GlassesHud.ListApp {
        val st = SavedApps.notesStore
        val all = st?.load().orEmpty()
        val open = all.firstOrNull { it.id == detailId }
        if (open != null) return detailHud(st, open)
        detailId = null
        val pills = listOf(ListPill("NEW", HudAccentTone.Purple), ListPill("DELETE", HudAccentTone.Red))
        val total = all.size + pills.size
        focus = focus.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (focus < all.size) lastRow = focus
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        return GlassesHud.ListApp(
            appId = "notepad", title = "Notepad", tone = HudAccentTone.Purple,
            rows = all.map { n ->
                val pics = n.attachments.count { it.kind == "image" }
                val vids = n.attachments.count { it.kind == "video" }
                ListRow(
                    id = n.id, title = n.title,
                    subtitle = n.body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith(n.title.trimEnd('.', ' ')) }.orEmpty(),
                    trailing = when {
                        pics > 0 -> "$pics pic" + if (pics > 1) "s" else ""
                        vids > 0 -> "video"
                        else -> fmt.format(Date(n.updatedAt))
                    },
                    tone = HudAccentTone.Purple, badge = n.title.take(1),
                    imageBytes = n.attachments.firstOrNull { it.kind == "image" }?.let { fileBytes(it.ref) },
                )
            },
            focusedIndex = focus, pills = pills,
            statusLine = note.ifBlank { if (all.isEmpty()) "" else "${all.size} notes" },
            emptyTitle = "No notes yet",
            emptyHint = "Press NEW, or say: take a note buy oat milk",
            actions = actions(total) {
                when {
                    focus < all.size -> { detailId = all[focus].id; page = 0; focus = 0; note = ""; refresh() }
                    focus == all.size -> ask("New note") { t ->
                        st?.create(t.take(40).replaceFirstChar { it.uppercase() }, t, emptyList()); note = "Saved"
                    }
                    else -> {
                        all.getOrNull(lastRow)?.let { st?.delete(it.id); note = "Deleted" }
                        refresh()
                    }
                }
            },
        )
    }

    private fun detailHud(st: NoteStore?, n: SavedNote): GlassesHud.ListApp {
        val pages = pagesFor(n)
        page = page.coerceIn(0, pages.lastIndex)
        val pills = listOf(
            ListPill(if (pages.size > 1) "NEXT" else "-", HudAccentTone.Blue),
            ListPill("ADD TEXT", HudAccentTone.Green),
            ListPill("DELETE", HudAccentTone.Red),
            ListPill("BACK", HudAccentTone.Teal),
        )
        focus = focus.coerceIn(0, pills.lastIndex)
        val (text, img) = pages[page]
        return GlassesHud.ListApp(
            appId = "notepad", title = "Notepad", tone = HudAccentTone.Purple,
            focusedIndex = focus, pills = pills,
            detail = ListDetail(n.title, text, img, note.ifBlank { "Page ${page + 1}/${pages.size}" }),
            actions = actions(pills.size) {
                when (focus) {
                    0 -> { page = (page + 1) % pages.size; note = ""; refresh() }
                    1 -> ask("Add to note") { t ->
                        st?.upsert(n.copy(body = n.body.trimEnd() + "\n" + t)); note = "Added"
                    }
                    2 -> { st?.delete(n.id); detailId = null; focus = 0; note = "Deleted"; refresh() }
                    else -> { detailId = null; focus = 0; note = ""; refresh() }
                }
            },
        )
    }
}
