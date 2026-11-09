package com.mymav.glasses.api

/**
 * Shared Conversate wrap. Layout uses the selected font's advances when
 * [widthPx] is installed; pagination in the mini-app then matches the glass.
 * Does not draw — a per-object character limit is applied later as same-baseline chunks.
 */
object HudLineLayout {
    @Volatile var widthPx: ((text: String, scale: Float, bold: Boolean) -> Float)? = null

    fun wrap(raw: String, maxWidth: Float, scale: Float, bold: Boolean = false): List<String> {
        val words = raw.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val meas = widthPx
        fun fits(s: String): Boolean =
            if (meas != null) meas(s, scale, bold) <= maxWidth else s.length <= 42
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fits(candidate)) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            if (fits(word)) {
                current = word
            } else {
                var rest = word
                while (rest.isNotEmpty()) {
                    var lo = 1
                    var hi = rest.length
                    while (lo < hi) {
                        val mid = (lo + hi + 1) / 2
                        if (fits(rest.take(mid))) lo = mid else hi = mid - 1
                    }
                    lines += rest.take(lo.coerceAtLeast(1))
                    rest = rest.drop(lo.coerceAtLeast(1))
                }
                current = ""
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
