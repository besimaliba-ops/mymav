package com.mymav.miniapps.spotify.api

import com.mymav.miniapps.spotify.model.LyricLine

class LrcParser {
    fun parse(lrc: String): List<LyricLine> {
        return lrc.lineSequence()
            .mapNotNull(::parseLine)
            .sortedBy { it.timestampMs }
            .toList()
    }

    private fun parseLine(line: String): LyricLine? {
        val match = LRC_LINE_REGEX.find(line.trim()) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val text = match.groupValues[4].trim()
        return LyricLine(
            timestampMs = (minutes * 60_000L) + (seconds * 1_000L) + fraction,
            text = text,
        )
    }

    private companion object {
        val LRC_LINE_REGEX = Regex("""\[(\d{2}):(\d{2})[.:](\d{1,3})]\s*(.*)""")
    }
}
