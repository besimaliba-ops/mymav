package com.mymav.glasses.api

/** One screen of an expanded answer: text plus an optional picture. */
class AskPage(val text: String, val image: ByteArray? = null)

/**
 * Splits long text into card-sized pages. Lines are pre-wrapped conservatively
 * (the renderer wraps again at pixel width) and joined with '\n'.
 */
object AskPager {
    private const val LINES = 8
    private const val CHARS_FULL = 40
    private const val CHARS_WITH_IMAGE = 22

    fun wrap(text: String, chars: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            val words = para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            var cur = ""
            for (w0 in words) {
                var w = w0
                while (w.length > chars) { // very long token
                    if (cur.isNotEmpty()) { out += cur; cur = "" }
                    out += w.take(chars); w = w.drop(chars)
                }
                cur = when {
                    cur.isEmpty() -> w
                    cur.length + 1 + w.length <= chars -> "$cur $w"
                    else -> { out += cur; w }
                }
            }
            if (cur.isNotEmpty()) out += cur
        }
        return out
    }

    /** [firstImage] goes on page 1 (narrow text column); later pages are full width. */
    fun paginate(text: String, firstImage: ByteArray? = null): List<AskPage> {
        val pages = ArrayList<AskPage>()
        val narrow = wrap(text, if (firstImage != null) CHARS_WITH_IMAGE else CHARS_FULL)
        if (firstImage != null) {
            pages += AskPage(narrow.take(LINES).joinToString("\n"), firstImage)
            val restText = narrow.drop(LINES).joinToString(" ")
            if (restText.isNotBlank()) chunk(wrap(restText, CHARS_FULL), pages)
        } else {
            chunk(narrow, pages)
        }
        return pages.ifEmpty { listOf(AskPage(text.take(160))) }
    }

    fun chunk(lines: List<String>, into: MutableList<AskPage>) {
        lines.chunked(LINES).forEach { into += AskPage(it.joinToString("\n")) }
    }

    /** True when the text will not fit the compact bubble, which shows ONE line (about 36 characters); a second line is clipped. */
    fun needsExpansion(text: String): Boolean = text.length > 36 || text.contains('\n')
}
