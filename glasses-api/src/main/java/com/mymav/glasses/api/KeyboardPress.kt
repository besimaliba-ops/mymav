package com.mymav.glasses.api

import java.text.BreakIterator

/**
 * What pressing a keyboard key does — shared by Tap (glasses screen) and the
 * 1s gaze dwell (controller), so both always behave identically.
 */
object KeyboardPress {
    fun press(hud: GlassesHud.Keyboard, keyId: String) {
        val a = hud.actions
        when (keyId) {
            "back" -> a.onCancel?.invoke()
            "fn_abc" -> a.onModeChanged?.invoke(KeyboardMode.Letters)
            "fn_cat" -> a.onModeChanged?.invoke(KeyboardMode.Categories)
            "fn_prev" -> a.onPageChanged?.invoke(hud.pageIndex - 1)
            "fn_next" -> a.onPageChanged?.invoke(hud.pageIndex + 1)
            "fn_search" -> {
                val submit = a.onSearchSubmit
                if (submit != null) submit.invoke() else a.onModeChanged?.invoke(KeyboardMode.Search)
            }
            "bs", "del" -> a.onBackspace?.invoke()
            "clr", "clear" -> a.onClear?.invoke()
            "caps" -> a.onCapsToggled?.invoke()
            "sym" -> a.onSymbolsToggled?.invoke()
            "mic", "vtxt" -> (a.onVoiceToText ?: a.onVoiceRequested)?.invoke()
            "vnote" -> (a.onVoiceNote ?: a.onVoiceRequested)?.invoke()
            "done" -> a.onDone?.invoke(hud.buffer)
            "search_field" -> a.onSearchQueryChanged?.invoke(hud.searchQuery.ifBlank { "heart" })
            else -> when {
                keyId.startsWith("sug_") -> {
                    val i = keyId.removePrefix("sug_").toIntOrNull() ?: return
                    hud.suggestions.getOrNull(i)?.let { a.onSuggestionSelected?.invoke(it) }
                }
                keyId.startsWith("cat_") -> a.onCategorySelected?.invoke(keyId.removePrefix("cat_"))
                hud.mode == KeyboardMode.Search && keyId.length == 1 -> {
                    val ch = if (hud.capsLocked) keyId.uppercase() else keyId.lowercase()
                    a.onSearchQueryChanged?.invoke(hud.searchQuery + ch)
                }
                else -> GazeKeyboardCatalog.resolveCommit(
                    keyId, hud.mode, hud.categoryId, hud.pageIndex, hud.searchQuery,
                    capsLocked = hud.capsLocked,
                )?.let { a.onKeyCommitted?.invoke(it) }
            }
        }
    }

    /** Delete the last user-perceived character (a whole emoji, not half of one). */
    fun dropLastGrapheme(text: String): String {
        if (text.isEmpty()) return text
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.last()
        val start = it.previous()
        return if (start == BreakIterator.DONE || start < 0 || start >= end) text.dropLast(1) else text.substring(0, start)
    }

    /** Smart punctuation: "word ." → "word. " like a phone keyboard. */
    fun appendCommit(buffer: String, commit: String): String {
        if (commit.length == 1 && commit[0] in ".,?!:;" && buffer.endsWith(" ") && buffer.length > 1 && buffer[buffer.length - 2] != ' ') {
            return buffer.dropLast(1) + commit + " "
        }
        return buffer + commit
    }
}
