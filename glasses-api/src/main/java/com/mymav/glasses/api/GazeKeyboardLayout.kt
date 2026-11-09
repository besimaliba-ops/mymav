package com.mymav.glasses.api

/**
 * Pixel layout of the shared glasses keyboard (640×400).
 *
 * One source of truth for the glasses renderer (where keys are drawn), the
 * D-pad focus mover and the gaze hit-test (which key you are looking at).
 */
object GazeKeyboardLayout {
    data class KeyRect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val cx: Float get() = x + w / 2f
        val cy: Float get() = y + h / 2f
        fun contains(px: Float, py: Float, slop: Float = 0f): Boolean =
            px >= x - slop && px <= x + w + slop && py >= y - slop && py <= y + h + slop
    }

    const val SUGGEST_Y = 62f
    const val SUGGEST_H = 34f
    const val FUNCTION_Y = 314f
    const val FUNCTION_H = 42f
    const val HINT_Y = 366f

    private const val LEFT = 16f
    private const val KEY_W = 56f
    private const val GAP_X = 6f
    private const val ROW_Y = 102f
    private const val KEY_H = 46f
    private const val GAP_Y = 6f

    /** Units (key widths) per id on the bottom letter row. */
    private val bottomSpans = mapOf("sym" to 1, "?" to 1, "!" to 1, "spc" to 4, "del" to 1, "done" to 2)

    fun suggestionKeys(count: Int): List<String> = (0 until count.coerceIn(0, 3)).map { "sug_$it" }

    fun rects(
        mode: KeyboardMode,
        contentKeys: List<String>,
        suggestionCount: Int,
    ): LinkedHashMap<String, KeyRect> {
        val out = LinkedHashMap<String, KeyRect>()
        out["back"] = KeyRect(12f, 8f, 96f, 44f)
        out["vtxt"] = KeyRect(404f, 8f, 108f, 44f)
        out["vnote"] = KeyRect(520f, 8f, 108f, 44f)
        if (mode == KeyboardMode.Letters) {
            suggestionKeys(suggestionCount).forEachIndexed { i, id ->
                out[id] = KeyRect(12f + i * 208f, SUGGEST_Y, 200f, SUGGEST_H)
            }
        }
        when (mode) {
            KeyboardMode.Letters -> {
                val rows = contentKeys.chunked(GazeKeyboardCatalog.LETTER_COLS)
                rows.forEachIndexed { r, row ->
                    val y = ROW_Y + r * (KEY_H + GAP_Y)
                    var x = LEFT
                    row.forEach { id ->
                        val units = if (r == rows.lastIndex) bottomSpans[id] ?: 1 else 1
                        val w = units * KEY_W + (units - 1) * GAP_X
                        out[id] = KeyRect(x, y, w, KEY_H)
                        x += w + GAP_X
                    }
                }
            }
            KeyboardMode.Categories -> {
                val keyW = 196f
                val keyH = 52f
                contentKeys.forEachIndexed { index, id ->
                    val col = index % 3
                    val row = index / 3
                    out[id] = KeyRect(LEFT + col * (keyW + 10f), 106f + row * (keyH + 10f), keyW, keyH)
                }
            }
            KeyboardMode.Emoji, KeyboardMode.Search -> {
                val grid = contentKeys.filter { it.startsWith("emoji_") || it.startsWith("semoji_") }
                val util = contentKeys.filter { !it.startsWith("emoji_") && !it.startsWith("semoji_") }
                val keyW = 92f
                val keyH = 44f
                val gap = 6f
                val top = 106f
                grid.forEachIndexed { index, id ->
                    val col = index % GazeKeyboardCatalog.EMOJI_COLS
                    val row = index / GazeKeyboardCatalog.EMOJI_COLS
                    out[id] = KeyRect(LEFT + col * (keyW + 8f), top + row * (keyH + gap), keyW, keyH)
                }
                var x = LEFT
                util.forEach { id ->
                    val w = when (id) {
                        "search_field" -> 220f
                        "clr", "clear", "del", "bs" -> 120f
                        else -> 100f
                    }
                    out[id] = KeyRect(x, top + 3 * (keyH + gap), w, keyH)
                    x += w + 8f
                }
            }
        }
        val fnW = 118f
        GazeKeyboardCatalog.FUNCTION_KEYS.forEachIndexed { index, id ->
            val w = if (id == "fn_cat") fnW + 8f else fnW
            val xShift = if (index > 1) 8f else 0f
            out[id] = KeyRect(14f + index * (fnW + 6f) + xShift, FUNCTION_Y, w, FUNCTION_H)
        }
        return out
    }

    /** Key under a gaze point (with a little slop so gaps don't drop the dwell). */
    fun hit(rects: Map<String, KeyRect>, px: Float, py: Float): String? =
        rects.entries.firstOrNull { it.value.contains(px, py) }?.key
            ?: rects.entries.firstOrNull { it.value.contains(px, py, 4f) }?.key

    /** D-pad: nearest key in the pressed direction, by geometry. */
    fun move(rects: Map<String, KeyRect>, focused: String, dx: Int, dy: Int): String {
        val from = rects[focused] ?: return rects.keys.firstOrNull() ?: focused
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        for ((id, r) in rects) {
            if (id == focused) continue
            val vx = r.cx - from.cx
            val vy = r.cy - from.cy
            val along = vx * dx + vy * dy
            if (along <= 4f) continue
            val across = kotlin.math.abs(if (dx != 0) vy else vx)
            val score = along + across * 2.2f
            if (score < bestScore) {
                bestScore = score
                best = id
            }
        }
        return best ?: focused
    }
}
