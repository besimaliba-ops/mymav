package com.mymav.miniapps.spotify.api

import com.mymav.miniapps.spotify.model.LyricLine
import com.mymav.miniapps.spotify.model.SpotifyPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActiveLyricWindow(
    val current: LyricLine?,
    val next: LyricLine?,
    val progressMs: Long,
)

class LyricSyncEngine(
    private val scope: CoroutineScope,
    private val onActiveLineChanged: (ActiveLyricWindow) -> Unit,
) {
    private var lyricLines: List<LyricLine> = emptyList()
    private var playbackState: SpotifyPlaybackState? = null
    private var tickerJob: Job? = null
    private var lastActiveIndex = -1

    fun loadLrc(lrc: String) {
        lyricLines = LrcParser().parse(lrc)
        lastActiveIndex = -1
    }

    fun clear() {
        lyricLines = emptyList()
        lastActiveIndex = -1
    }

    fun updatePlayback(state: SpotifyPlaybackState, force: Boolean = false) {
        playbackState = state
        emitActiveLine(force = force)
    }

    fun start() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                emitActiveLine(force = false)
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    fun scrollDown() {
        skipToNextLyric()
    }

    fun skipToNextLyric() {
        if (lyricLines.isEmpty()) return
        val nextIndex = (currentIndex(estimatedProgressMs()) + 1).coerceAtMost(lyricLines.lastIndex)
        lastActiveIndex = nextIndex
        onActiveLineChanged(
            ActiveLyricWindow(
                current = lyricLines.getOrNull(nextIndex),
                next = lyricLines.getOrNull(nextIndex + 1),
                progressMs = lyricLines[nextIndex].timestampMs,
            )
        )
    }

    private fun emitActiveLine(force: Boolean) {
        val progressMs = estimatedProgressMs()
        val activeIndex = currentIndex(progressMs)
        if (!force && activeIndex == lastActiveIndex) return
        lastActiveIndex = activeIndex
        onActiveLineChanged(
            ActiveLyricWindow(
                current = lyricLines.getOrNull(activeIndex),
                next = lyricLines.getOrNull(activeIndex + 1),
                progressMs = progressMs,
            )
        )
    }

    private fun estimatedProgressMs(): Long {
        val state = playbackState ?: return 0L
        if (!state.isPlaying) return state.progressMs
        val elapsed = System.currentTimeMillis() - state.sampledAtMillis
        return (state.progressMs + elapsed).coerceAtLeast(0L)
    }

    private fun currentIndex(progressMs: Long): Int {
        if (lyricLines.isEmpty()) return -1
        val index = lyricLines.indexOfLast { it.timestampMs <= progressMs }
        return index.coerceAtLeast(0)
    }

    companion object {
        const val TICK_MS = 120L

        val MOCK_LRC = """
            [00:00.00] Waiting for Spotify playback
            [00:04.00] Maverick lens is ready
            [00:08.50] Current lyric appears here
            [00:12.34] Lyric line synced to progress
            [00:16.00] Glance down to skip ahead
            [00:20.00] Keep the chorus in view
        """.trimIndent()
    }
}
