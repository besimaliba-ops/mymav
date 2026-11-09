package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.mymav.glasses.api.PlacedWidget

/**
 * 2D focus helpers for D-pad / arrow keys.
 * [M2Touch.Forward]/[Backward] = right/left; [M2Touch.Up]/[Down] = up/down.
 */
object FocusGridNav {
    fun delta(touch: M2Touch): Pair<Int, Int>? = when (touch) {
        M2Touch.Forward -> 1 to 0
        M2Touch.Backward -> -1 to 0
        M2Touch.Down -> 0 to 1
        M2Touch.Up -> 0 to -1
        else -> null
    }

    /**
     * Move focus inside a flat list laid out in [cols] columns (row-major).
     * Wraps on both axes.
     */
    fun moveInGrid(keys: List<String>, focused: String, cols: Int, dx: Int, dy: Int): String {
        if (keys.isEmpty() || cols <= 0) return focused
        val idx = keys.indexOf(focused).let { if (it < 0) 0 else it }
        val rows = (keys.size + cols - 1) / cols
        val row = idx / cols
        val col = idx % cols
        var nextRow = (row + dy).mod(rows)
        var nextCol = (col + dx).mod(cols)
        var nextIdx = nextRow * cols + nextCol
        // Clamp to last key when landing past the ragged last row.
        while (nextIdx >= keys.size) {
            nextCol = (nextCol - 1).mod(cols)
            nextIdx = nextRow * cols + nextCol
            if (nextCol == col && dy == 0) {
                nextIdx = keys.lastIndex
                break
            }
        }
        return keys[nextIdx.coerceIn(0, keys.lastIndex)]
    }

    /**
     * Voice pills (top) + content grid + function row.
     * Up from first content row → voice; Down from voice → content; Down from content → functions.
     */
    fun moveContentAndFunctions(
        contentKeys: List<String>,
        functionKeys: List<String>,
        focused: String,
        contentCols: Int,
        dx: Int,
        dy: Int,
        voiceKeys: List<String> = listOf("vtxt", "vnote"),
    ): String {
        val inVoice = focused in voiceKeys
        if (inVoice) {
            if (dy > 0) {
                return contentKeys.firstOrNull() ?: functionKeys.firstOrNull() ?: focused
            }
            return moveInGrid(voiceKeys, focused, voiceKeys.size.coerceAtLeast(1), dx, 0)
        }
        val inFn = focused in functionKeys
        if (inFn) {
            if (dy < 0) {
                // Up into last content row, same-ish column.
                val fnIdx = functionKeys.indexOf(focused).coerceAtLeast(0)
                val lastRowStart = ((contentKeys.size - 1) / contentCols) * contentCols
                val target = (lastRowStart + fnIdx.coerceAtMost(contentCols - 1))
                    .coerceAtMost(contentKeys.lastIndex)
                return contentKeys.getOrElse(target) { contentKeys.last() }
            }
            return moveInGrid(functionKeys, focused, functionKeys.size.coerceAtLeast(1), dx, 0)
        }
        if (dy < 0 && contentKeys.isNotEmpty()) {
            val idx = contentKeys.indexOf(focused).coerceAtLeast(0)
            val row = idx / contentCols
            if (row == 0 && voiceKeys.isNotEmpty()) {
                val col = idx % contentCols
                return voiceKeys.getOrElse(col.coerceAtMost(voiceKeys.lastIndex)) { voiceKeys.first() }
            }
        }
        if (dy > 0) {
            val idx = contentKeys.indexOf(focused).coerceAtLeast(0)
            val row = idx / contentCols
            val rows = (contentKeys.size + contentCols - 1) / contentCols
            if (row >= rows - 1) {
                val col = idx % contentCols
                val fnIdx = col.coerceAtMost(functionKeys.lastIndex)
                return functionKeys.getOrElse(fnIdx) { functionKeys.first() }
            }
        }
        return moveInGrid(contentKeys, focused, contentCols, dx, dy)
    }

    /** 2D widget focus using packed cell ownership on a 2×2 page. */
    fun moveWidgets(placed: List<PlacedWidget>, focusedId: String, dx: Int, dy: Int): String {
        if (placed.isEmpty()) return focusedId
        val owner = Array(2) { arrayOfNulls<String>(2) }
        for (p in placed) {
            for (r in p.row until (p.row + p.rowSpan).coerceAtMost(2)) {
                for (c in p.col until (p.col + p.colSpan).coerceAtMost(2)) {
                    owner[r][c] = p.widget.id
                }
            }
        }
        val cur = placed.find { it.widget.id == focusedId } ?: placed.first()
        var r = cur.row
        var c = cur.col
        repeat(4) {
            r = (r + dy).mod(2)
            c = (c + dx).mod(2)
            val id = owner[r][c]
            if (id != null && id != focusedId) return id
        }
        // Fallback linear wrap.
        val ids = placed.map { it.widget.id }
        val idx = ids.indexOf(focusedId).coerceAtLeast(0)
        return ids[(idx + if (dx + dy >= 0) 1 else -1 + ids.size) % ids.size]
    }
}
