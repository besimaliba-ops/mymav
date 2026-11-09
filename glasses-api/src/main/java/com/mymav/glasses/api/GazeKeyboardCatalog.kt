package com.mymav.glasses.api

/**
 * Shared gaze-keyboard content: letter keys, emoji pages, categories, search.
 * Functions match design-ref 13 (ABC / Emojis / Prev / Next / Search).
 */
object GazeKeyboardCatalog {
    /** Phone-style 4-row layout; the last row is drawn with wide space / done keys. */
    val LETTER_KEYS: List<String> = listOf(
        "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
        "a", "s", "d", "f", "g", "h", "j", "k", "l", "'",
        "caps", "z", "x", "c", "v", "b", "n", "m", ",", ".",
        "sym", "?", "!", "spc", "del", "done",
    )

    /** Numbers + symbols page (toggled by the 123 / ABC key). */
    val SYMBOL_KEYS: List<String> = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "@", "#", "^", "$", "%", "&", "*", "(", ")", "-",
        "+", "=", "/", ":", ";", "\"", "_", "'", ",", ".",
        "sym", "?", "!", "spc", "del", "done",
    )

    /** Columns for letter / emoji content grids (2D D-pad focus). */
    const val LETTER_COLS = 10
    const val CATEGORY_COLS = 3
    const val EMOJI_COLS = 6
    const val FUNCTION_COLS = 5

    val FUNCTION_KEYS: List<String> = listOf(
        "fn_abc", "fn_cat", "fn_prev", "fn_next", "fn_search",
    )

    data class EmojiCategory(
        val id: String,
        val title: String,
        /** Short pill label that fits soft glass category keys (no truncation bugs). */
        val shortTitle: String,
        val glyph: String,
        val emojis: List<String>,
    )

    /** Common keyboard emoji sets (iOS / Android style). */
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            id = "smileys",
            title = "Smileys",
            shortTitle = "Smileys",
            glyph = "😀",
            emojis = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅",
                "😂", "🤣", "😊", "😇", "🙂", "😉",
                "😍", "🥰", "😘", "😗", "😋", "😜",
                "🤪", "😝", "🤑", "🤗", "🤭", "🤫",
                "🤔", "😐", "😑", "😶", "🙄", "😏",
                "😣", "😥", "😮", "🤐", "😯", "😪",
                "😫", "🥱", "😴", "😌", "😛", "😓",
                "😕", "🙃", "🫠", "😲", "☹️", "🙁",
                "😖", "😞", "😟", "😤", "😢", "😭",
                "😦", "😧", "😨", "😩", "🤯", "😬",
                "😰", "😱", "🥵", "🥶", "😳", "🤠",
                "🥳", "😎", "🤓", "🧐", "😈", "👿",
                "💀", "☠️", "💩", "🤡", "👻", "👽",
                "🤖", "😺", "😸", "😹", "😻", "😼",
            ),
        ),
        EmojiCategory(
            id = "gestures",
            title = "Gesture",
            shortTitle = "Gesture",
            glyph = "👍",
            emojis = listOf(
                "👍", "👎", "👊", "✊", "🤛", "🤜",
                "👏", "🙌", "👐", "🤲", "🤝", "🙏",
                "✌️", "🤞", "🤟", "🤘", "👌", "🤌",
                "🤏", "👈", "👉", "👆", "🖕", "👇",
                "☝️", "👋", "🤚", "🖐️", "✋", "🖖",
                "💪", "🦾", "🦿", "🦵", "🦶", "👂",
                "✅", "❌", "💯", "🔥", "⭐", "✨",
                "💫", "💥", "💢", "💦", "💨", "🕳️",
            ),
        ),
        EmojiCategory(
            id = "hearts",
            title = "Hearts",
            shortTitle = "Hearts",
            glyph = "❤️",
            emojis = listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜",
                "🖤", "🤍", "🤎", "💔", "❣️", "💕",
                "💞", "💓", "💗", "💖", "💘", "💝",
                "💟", "☮️", "✝️", "☪️", "🕉️", "☸️",
                "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                "⛎", "♈", "♉", "♊", "♋", "♌",
                "🎉", "🎊", "🎈", "🎁", "🥳", "🍾",
                "🎀", "🏆", "🥇", "🥈", "🥉", "🏅",
            ),
        ),
        EmojiCategory(
            id = "objects",
            title = "Objects",
            shortTitle = "Objects",
            glyph = "📱",
            emojis = listOf(
                "📱", "💻", "⌚", "🎧", "🎤", "📷",
                "📹", "🎥", "📞", "☎️", "📺", "📻",
                "🔋", "🔌", "💡", "🔦", "🕯️", "💸",
                "💵", "💰", "💳", "💎", "⚖️", "🧰",
                "🔑", "🔒", "🔓", "✉️", "📧", "📦",
                "📫", "📬", "📭", "📮", "🗳️", "✏️",
                "📝", "📁", "📂", "📅", "📆", "🗓️",
                "📌", "📍", "📎", "🖇️", "📏", "📐",
                "🚗", "✈️", "🏠", "⏰", "⚽", "🏀",
                "🏋️", "🍕", "🍔", "🍟", "☕", "🍺",
                "🎵", "🎶", "🎮", "🕹️", "🎲", "🧩",
            ),
        ),
        EmojiCategory(
            id = "animals",
            title = "Animals",
            shortTitle = "Animals",
            glyph = "🐶",
            emojis = listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊",
                "🐻", "🐼", "🐻‍❄️", "🐨", "🐯", "🦁",
                "🐮", "🐷", "🐸", "🐵", "🙈", "🙉",
                "🙊", "🐒", "🐔", "🐧", "🐦", "🐤",
                "🐣", "🐥", "🦆", "🦅", "🦉", "🦇",
                "🐺", "🐗", "🐴", "🦄", "🐝", "🪱",
                "🐛", "🦋", "🐌", "🐞", "🐜", "🪰",
                "🐢", "🐍", "🦎", "🦖", "🦕", "🐊",
                "🐙", "🦑", "🦐", "🦞", "🦀", "🐡",
                "🐠", "🐟", "🐬", "🐳", "🐋", "🦈",
            ),
        ),
        EmojiCategory(
            id = "food",
            title = "Food",
            shortTitle = "Food",
            glyph = "🍕",
            emojis = listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌",
                "🍉", "🍇", "🍓", "🫐", "🍈", "🍒",
                "🍑", "🥭", "🍍", "🥥", "🥝", "🍅",
                "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️",
                "🫑", "🌽", "🥕", "🫒", "🧄", "🧅",
                "🥔", "🍠", "🥐", "🥯", "🍞", "🥖",
                "🥨", "🧀", "🥚", "🍳", "🧈", "🥞",
                "🧇", "🥓", "🥩", "🍗", "🍖", "🦴",
                "🌭", "🍔", "🍟", "🍕", "🫓", "🥪",
                "🥙", "🧆", "🌮", "🌯", "🫔", "🥗",
                "☕", "🍵", "🧃", "🥤", "🧋", "🍺",
                "🍻", "🥂", "🍷", "🍸", "🍹", "🧉",
            ),
        ),
    )

    const val EMOJI_PAGE_SIZE = 18

    fun category(id: String): EmojiCategory =
        categories.firstOrNull { it.id == id } ?: categories.first()

    fun emojiPages(categoryId: String): List<List<String>> {
        val all = category(categoryId).emojis
        if (all.isEmpty()) return listOf(emptyList())
        return all.chunked(EMOJI_PAGE_SIZE)
    }

    fun searchEmojis(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return categories.flatMap { it.emojis }.distinct().take(EMOJI_PAGE_SIZE)
        val hits = mutableListOf<String>()
        for (cat in categories) {
            if (cat.title.lowercase().contains(q) || cat.id.contains(q)) {
                hits += cat.emojis
            }
        }
        val aliases = mapOf(
            "smile" to "smileys",
            "happy" to "smileys",
            "face" to "smileys",
            "thumb" to "gestures",
            "hand" to "gestures",
            "heart" to "hearts",
            "love" to "hearts",
            "fire" to "gestures",
            "music" to "objects",
            "dog" to "animals",
            "cat" to "animals",
            "animal" to "animals",
            "food" to "food",
            "pizza" to "food",
            "drink" to "food",
        )
        aliases.forEach { (token, catId) ->
            if (q.contains(token)) hits += category(catId).emojis
        }
        return hits.distinct().ifEmpty {
            categories.flatMap { it.emojis }.filter { it.contains(q) }
        }.take(EMOJI_PAGE_SIZE * 3)
    }

    fun visibleKeys(
        mode: KeyboardMode,
        categoryId: String,
        pageIndex: Int,
        searchQuery: String,
        symbols: Boolean = false,
    ): Pair<List<String>, Int> {
        return when (mode) {
            KeyboardMode.Letters -> (if (symbols) SYMBOL_KEYS else LETTER_KEYS) to 1
            KeyboardMode.Categories -> {
                val keys = categories.map { "cat_${it.id}" } + listOf("del", "clr", "done")
                keys to 1
            }
            KeyboardMode.Emoji -> {
                val pages = emojiPages(categoryId)
                val page = pages.getOrElse(pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) {
                    emptyList()
                }
                val keys = page.mapIndexed { i, _ -> "emoji_$i" } + listOf("del", "clr", "done")
                keys to pages.size.coerceAtLeast(1)
            }
            KeyboardMode.Search -> {
                val matches = searchEmojis(searchQuery)
                val pages = matches.chunked(EMOJI_PAGE_SIZE).ifEmpty { listOf(emptyList()) }
                val page = pages.getOrElse(pageIndex.coerceIn(0, pages.lastIndex)) { emptyList() }
                val keys = listOf("search_field") +
                    page.mapIndexed { i, _ -> "semoji_$i" } +
                    listOf("del", "clr", "done")
                keys to pages.size.coerceAtLeast(1)
            }
        }
    }

    fun resolveCommit(
        keyId: String,
        mode: KeyboardMode,
        categoryId: String,
        pageIndex: Int,
        searchQuery: String,
        capsLocked: Boolean = false,
    ): String? {
        when {
            keyId.startsWith("emoji_") -> {
                val i = keyId.removePrefix("emoji_").toIntOrNull() ?: return null
                return emojiPages(categoryId)
                    .getOrNull(pageIndex)
                    ?.getOrNull(i)
            }
            keyId.startsWith("semoji_") -> {
                val i = keyId.removePrefix("semoji_").toIntOrNull() ?: return null
                val page = searchEmojis(searchQuery).chunked(EMOJI_PAGE_SIZE)
                    .getOrNull(pageIndex) ?: return null
                return page.getOrNull(i)
            }
            keyId == "spc" -> return " "
            keyId.length == 1 && mode == KeyboardMode.Letters ->
                return if (capsLocked && keyId[0].isLetter()) keyId.uppercase() else keyId
            else -> return null
        }
    }

    fun labelForKey(
        keyId: String,
        mode: KeyboardMode,
        categoryId: String,
        pageIndex: Int,
        searchQuery: String,
        capsLocked: Boolean = false,
        doneLabel: String = "OK",
        symbols: Boolean = false,
    ): String {
        return when (keyId) {
            "sym" -> if (symbols) "ABC" else "123"
            // ISO space-bar symbol (open box) — universal keyboard glyph.
            "spc" -> "space"
            "caps" -> if (capsLocked) "SHIFT" else "shift"
            "bs", "del" -> "DEL"
            "clr", "clear" -> "CLEAR"
            "mic" -> "MIC"
            "done" -> doneLabel.take(8).ifBlank { "OK" }
            "search_field" -> searchQuery.ifBlank { "Search…" }.take(10)
            "fn_abc" -> "ABC"
            "fn_cat" -> "Emojis"
            "fn_prev" -> "Prev"
            "fn_next" -> "Next"
            "fn_search" -> "Search"
            else -> when {
                keyId.startsWith("cat_") -> {
                    val cat = category(keyId.removePrefix("cat_"))
                    cat.shortTitle
                }
                keyId.startsWith("emoji_") -> {
                    val i = keyId.removePrefix("emoji_").toIntOrNull() ?: return "?"
                    emojiPages(categoryId).getOrNull(pageIndex)?.getOrNull(i) ?: "?"
                }
                keyId.startsWith("semoji_") -> {
                    val i = keyId.removePrefix("semoji_").toIntOrNull() ?: return "?"
                    searchEmojis(searchQuery).chunked(EMOJI_PAGE_SIZE)
                        .getOrNull(pageIndex)?.getOrNull(i) ?: "?"
                }
                keyId.length == 1 && mode == KeyboardMode.Letters ->
                    if (capsLocked && keyId[0].isLetter()) keyId.uppercase() else keyId
                else -> keyId.uppercase()
            }
        }
    }

    /** ASCII fallback when emoji bitmaps cannot be decoded on-lens. */
    fun asciiFallback(emoji: String): String = when (emoji) {
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "🙂", "😉" -> ":)"
        "😍", "🥰", "😘", "😗" -> "<3"
        "😎" -> "B)"
        "🤔" -> "?"
        "😢", "😭" -> ":("
        "😤", "🤯" -> ">:"
        "😴" -> "zZ"
        "👍" -> "+1"
        "👎" -> "-1"
        "👏" -> "**"
        "🙏" -> "/\\"
        "❤️", "🧡", "💛", "💚", "💙", "💜", "💕", "💖" -> "<3"
        "🔥" -> "!!"
        "⭐", "✨" -> "*"
        "⚽", "🏀" -> "O"
        "🎧", "🎵", "🎶" -> "n"
        "🐶", "🐱", "🐭" -> ":3"
        "🍕", "🍔", "🍟" -> "%"
        else -> if (emoji.length <= 2) emoji else ":)"
    }
}
