package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HomeBoardCatalog
import com.mymav.glasses.api.LyricsControlLayout
import com.mymav.glasses.api.LyricsControlState
import java.util.Locale

/**
 * Spotify lyrics glasslet — soft rounded panels (no hard green wireframes).
 *
 * Text column ends before the HOME pill ([HomeBoardCatalog.HOME_PILL_X]) so titles never overlap it.
 * Cover art must be JPEG/PNG file bytes (raw RGBA fails on SDK 0.2.0).
 */
class NotifyLyricsScreen(
    private val hud: GlassesHud.Lyrics,
) : HudScreen(tag = "mymav-notify-lyrics") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("spotify")
    private val background = HudPrimitives.fullBleedBackground()
    /** Glass surface in this app's colour scope (property initialisers run before build(), so the scope is set here). */
    private fun glassPanel(x: Float, y: Float, w: Float, h: Float, radius: Float) =
        GlassSurface.withScope(glassScope) { HudPrimitives.frostedPanel(x, y, w, h, radius = radius, gradient = false, glass = true) }

    private val header = glassPanel(16f, 16f, 608f, 138f, 20f)
    private val lyricPanel = glassPanel(16f, 162f, 608f, 214f, 20f)

    private val coverImage = createHudImage(
        hud.albumArtBytes, hud.albumArtWidth, hud.albumArtHeight,
        COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE,
        circular = false,
        cornerRadius = 14f,
    )
    private val coverShade = M2RectFilled(HudTheme.InkRaised).apply {
        setDimensions(COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE)
        softCorners(14f)
    }
    private val coverLabel = M2Text(if (hud.artIsVideoPreview) "Video" else "Album").apply {
        setXY(COVER_X + 22f, COVER_Y + 40f)
        setColor(HudTheme.InkMuted)
        setScale(0.75f)
    }
    private val videoBadge = if (hud.artIsVideoPreview && coverImage != null) {
        M2Text("Video").apply {
            setXY(COVER_X + 14f, COVER_Y + COVER_SIZE - 24f)
            setColor(HudTheme.BrandSpotify)
            setScale(0.7f)
        }
    } else {
        null
    }

    /** Playback status (Playing / Paused / Connecting / Not connected / Unavailable) — never implied when unknown. */
    private val topLine = M2Text(statusLabel()).apply {
        setXY(TEXT_X, 26f)
        setColor(statusColor())
        setScale(0.75f)
    }
    private val trackLine = M2Text(HudText.fit(trackTitle(), TITLE_MAX_W, 1.0f, true)).apply {
        setXY(TEXT_X, 48f)
        setColor(M2Color.White)
        setScale(1.0f)
    }
    private val artistLine = M2Text(HudText.fit(artistTitle(), TITLE_MAX_W, 0.8f)).apply {
        setXY(TEXT_X, 76f)
        setColor(HudTheme.InkMuted)
        setScale(0.8f)
    }
    private val albumLine = M2Text(HudText.fit(albumLabel(), TITLE_MAX_W, 0.7f)).apply {
        setXY(TEXT_X, 98f)
        setColor(HudTheme.InkMuted)
        setScale(0.7f)
    }

    // Controls drawn from the shared hit regions (LyricsControlLayout) so gaze and drawing always agree.
    private val R = LyricsControlLayout
    private val previousButton = glassPanel(R.previous.x, R.previous.y, R.previous.w, R.previous.h, 15f)
    private val playButton = PillStyle.apply(HudPrimitives.softPill(R.play.x, R.play.y, R.play.w, R.play.h, HudTheme.BrandSpotify), HudTheme.BrandSpotify)
    private val nextButton = glassPanel(R.next.x, R.next.y, R.next.w, R.next.h, 15f)
    private val searchButton = glassPanel(R.search.x, R.search.y, R.search.w, R.search.h, 15f)
    private val likedButton = PillStyle.apply(HudPrimitives.softPill(R.liked.x, R.liked.y, R.liked.w, R.liked.h, HudTheme.FocusGlow), HudTheme.FocusGlow)
    private val previousText = HudPrimitives.centeredPillLabel("Prev", R.previous.x, R.previous.y, R.previous.w, R.previous.h, 0.7f, M2Color.White)
    private val playText = HudPrimitives.centeredPillLabel(
        if (hud.isPlaying) "Pause" else "Play", R.play.x, R.play.y, R.play.w, R.play.h, 0.75f, PillStyle.LABEL,
    )
    private val nextText = HudPrimitives.centeredPillLabel("Next", R.next.x, R.next.y, R.next.w, R.next.h, 0.7f, M2Color.White)
    private val searchText = HudPrimitives.centeredPillLabel("Search", R.search.x, R.search.y, R.search.w, R.search.h, 0.7f, M2Color.White)
    private val likedText = HudPrimitives.centeredPillLabel("Liked", R.liked.x, R.liked.y, R.liked.w, R.liked.h, 0.7f, PillStyle.LABEL)

    /** One progress bar per control, grown in place while the viewer's gaze dwells on it. */
    private val dwellBars: Map<String, M2RectFilled> = R.all.associate { r ->
        r.id to M2RectFilled(HudTheme.FocusGlow).apply {
            park()
        }
    }
    private val noticeText = M2Text(" ").apply {
        setXY(40f, 318f)
        setColor(M2Color.Orange)
        setScale(0.8f)
    }

    private fun statusLabel(): String = when (LyricsControlState.status) {
        "playing" -> "Playing"
        "paused" -> "Paused"
        "loading" -> "Connecting to Spotify"
        "disconnected" -> "Spotify not connected"
        "unavailable" -> "Spotify unavailable"
        else -> if (hud.isPlaying) "Playing" else "Paused"
    }

    private fun statusColor(): M2Color = when (LyricsControlState.status) {
        "disconnected", "unavailable" -> M2Color.Orange
        "loading" -> M2Color.White
        else -> HudTheme.BrandSpotify
    }

    /** Apply the live status / notice / dwell progress without rebuilding the screen (textures stay warm). */
    private fun applyLiveState() {
        runCatching {
            val n = LyricsControlState.notice
            // The status line is the one text slot that repaints reliably; an error notice takes it over briefly.
            topLine.setHudText(if (n.isBlank()) statusLabel() else (if (n.length > 38) n.take(37) + "…" else n))
            topLine.setColor(if (n.isBlank()) statusColor() else M2Color.Orange)
            noticeText.setHudText(if (n.isBlank()) " " else n)
            val id = LyricsControlState.dwellId
            val p = LyricsControlState.dwellProgress
            R.all.forEach { r ->
                val bar = dwellBars[r.id] ?: return@forEach
                if (r.id == id && p > 0.02f) {
                    bar.setDimensions(r.x + 6f, r.y + r.h + 3f, ((r.w - 12f) * p).coerceAtLeast(3f), 4f)
                    bar.softCorners(1.5f)
                } else {
                    bar.park()
                }
            }
        }.onFailure { android.util.Log.w("LyricsLive", "apply failed: $it") }
    }

    private val currentLyricLines: List<M2Text> = List(CURRENT_LYRIC_MAX_LINES) { index ->
        val lines = wrapCurrent(hud.currentLyric)
        M2Text(lines.getOrElse(index) { " " }).apply {
            setXY(40f, 178f + index * CURRENT_LYRIC_LINE_H)
            setColor(M2Color.White)
            setScale(0.95f)
        }
    }
    private val nextLyricLines: List<M2Text> = List(NEXT_LYRIC_MAX_LINES) { index ->
        val lines = wrapNext(hud.nextLyric)
        M2Text(lines.getOrElse(index) { " " }).apply {
            setXY(40f, nextLyricY() + index * NEXT_LYRIC_LINE_H)
            setColor(HudTheme.InkMuted)
            setScale(0.75f)
        }
    }
    private val progress = M2Text(progressLabel(hud.progressMs)).apply {
        setXY(40f, 344f)
        setColor(HudTheme.InkMuted)
        setScale(0.7f)
    }
    private val hint = M2Text("Down search - Fwd x2 liked").apply {
        setXY(624f - 24f - HudText.width("Down search - Fwd x2 liked", 0.7f), 344f)
        setColor(HudTheme.InkMuted)
        setScale(0.7f)
    }
    private var lastForwardAtMs: Long = 0L
    /** Stable key so lyric ticks can patch text without re-uploading cover textures. */
    val trackKey: String =
        "${hud.trackName}|${hud.artistName}|${hud.albumArtWidth}x${hud.albumArtHeight}|${hud.artIsVideoPreview}"

    /**
     * Update lyric / progress / play label in place — keeps album art texture warm.
     */
    fun patchLyrics(
        currentLyric: String,
        nextLyric: String,
        progressMs: Long,
        isPlaying: Boolean,
    ) {
        val cur = wrapCurrent(currentLyric)
        currentLyricLines.forEachIndexed { i, text ->
            text.setHudText(cur.getOrElse(i) { " " })
        }
        val nxt = wrapNext(nextLyric)
        nextLyricLines.forEachIndexed { i, text ->
            text.setHudText(nxt.getOrElse(i) { " " })
        }
        progress.setHudText(progressLabel(progressMs))
        val playLabel = if (isPlaying) "Pause" else "Play"
        playText.setHudText(playLabel)
        // Keep the label centred in its pill when its width changes.
        playText.setXY(R.play.x + (R.play.w - HudText.width(playLabel, playText.scale)) / 2f, playText.getY())
        applyLiveState()
    }

    override fun build() {
        add(background)
        LyricsControlState.listener = {
            // Drawable mutations must run on the main thread, like every other HUD update.
            val main = android.os.Looper.getMainLooper()
            if (android.os.Looper.myLooper() == main) applyLiveState()
            else android.os.Handler(main).post { applyLiveState() }
        }
        HudPrimitives.addPanel(this, header)
        HudPrimitives.addPanel(this, lyricPanel)
        add(topLine)
        add(trackLine)
        add(artistLine)
        // Always show a cover plate; image draws on top when encode succeeds.
        add(coverShade)
        if (coverImage != null) {
            add(coverImage)
            videoBadge?.let { add(it) }
        } else {
            add(coverLabel)
        }
        if (albumLabel().isNotBlank()) add(albumLine)
        HudPrimitives.addPanel(this, previousButton)
        HudPrimitives.addPanel(this, playButton)
        HudPrimitives.addPanel(this, nextButton)
        HudPrimitives.addPanel(this, searchButton)
        HudPrimitives.addPanel(this, likedButton)
        add(previousText)
        add(playText)
        add(nextText)
        add(searchText)
        add(likedText)
        dwellBars.values.forEach { add(it) }
        add(noticeText)
        currentLyricLines.forEach { add(it) }
        nextLyricLines.forEach { add(it) }
        add(progress)
        add(hint)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
    }

    private fun nextLyricY(): Float =
        178f + CURRENT_LYRIC_MAX_LINES * CURRENT_LYRIC_LINE_H + 8f

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Backward -> hud.actions.onPreviousTrack?.invoke()
            M2Touch.Tap -> {
                // A control that is currently being looked at is the selected control; otherwise play/pause.
                val focused = LyricsControlState.dwellId
                val onControl = hud.actions.onControl
                if (focused != null && onControl != null) onControl(focused) else hud.actions.onTogglePlayback?.invoke()
            }
            M2Touch.Down -> hud.actions.onSearchRequested?.invoke()
            M2Touch.Forward -> {
                val now = System.currentTimeMillis()
                if (now - lastForwardAtMs < 450L && hud.actions.onLikedSongsRequested != null) {
                    lastForwardAtMs = 0L
                    hud.actions.onLikedSongsRequested?.invoke()
                } else {
                    lastForwardAtMs = now
                    hud.actions.onNextTrack?.invoke()
                }
            }
            M2Touch.Up -> {
                GlassesHomeController.rememberAppHud(hud)
                GlassesHomeController.simulateHomePillGazeDwell()
            }
            M2Touch.LongTap -> {
                val home = hud.actions.onHomeRequested
                if (home != null) home.invoke() else hud.actions.onSearchRequested?.invoke()
            }
            else -> Unit
        }
    }

    /** Title must stay left of HOME pill (x=450). */
    private fun trackTitle(): String {
        return hud.trackName
    }

    private fun artistTitle(): String {
        return hud.artistName
    }

    private fun albumLabel(): String {
        return hud.albumName.orEmpty()
    }

    // The glasses draw a single label of more than ~44 characters as blank, however narrow it is, so lyric
    // lines are wrapped by character count (and by width) rather than by width alone.
    private fun wrapCurrent(raw: String): List<String> =
        capLines(raw, HudText.wrap(raw, LYRIC_MAX_W, 0.95f, CURRENT_LYRIC_MAX_LINES), CURRENT_LYRIC_CHARS, CURRENT_LYRIC_MAX_LINES)

    private fun wrapNext(raw: String): List<String> =
        capLines(raw, HudText.wrap(raw, LYRIC_MAX_W, 0.75f, NEXT_LYRIC_MAX_LINES), NEXT_LYRIC_CHARS, NEXT_LYRIC_MAX_LINES)

    private fun capLines(raw: String, byWidth: List<String>, maxChars: Int, maxLines: Int): List<String> {
        if (byWidth.all { it.length <= maxChars }) return byWidth.ifEmpty { listOf(" ") }
        val lines = wrapLyric(raw, maxChars)
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines).toMutableList().also { it[it.lastIndex] = (it.last() + " " + lines.drop(maxLines).joinToString(" ")).takeForHud(maxChars) }
    }

    private fun progressLabel(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "Synced  %d:%02d".format(total / 60, total % 60)
    }

    companion object {
        /** Lyric column: 40px inset inside the 608px panel. */
        private const val LYRIC_MAX_W = 560f
        /** Header text column ends 16px before the HOME pill. */
        private val TITLE_MAX_W: Float
            get() = HomeBoardCatalog.HOME_PILL_X - TEXT_X - 16f
        private const val COVER_X = 28f
        private const val COVER_Y = 28f
        /** Larger on-glass cover; source JPEG is HQ (≈300px) from SpotifyArtworkLoader. */
        private const val COVER_SIZE = 108f
        /** Text starts after cover; ends before HOME pill with a gap. */
        private const val TEXT_X = 156f
        /** ~608px panel − margins; chars at ~0.95 scale. */
        private const val CURRENT_LYRIC_CHARS = 42
        private const val CURRENT_LYRIC_MAX_LINES = 2
        private const val CURRENT_LYRIC_LINE_H = 34f
        private const val NEXT_LYRIC_CHARS = 42
        private const val NEXT_LYRIC_MAX_LINES = 2
        private const val NEXT_LYRIC_LINE_H = 26f
        private val TITLE_MAX_CHARS: Int
            get() {
                val available = HomeBoardCatalog.HOME_PILL_X - TEXT_X - 24f
                return (available / 11f).toInt().coerceIn(18, 28)
            }
    }
}

/** Word-wrap lyric text within the lyrics panel; ellipsis only if still too long. */
private fun wrapLyric(raw: String, maxChars: Int): List<String> {
    val normalized = raw.replace(Regex("[\\s\\u2013\\u2014\\u2026]+"), " ").trim()
    if (normalized.isEmpty()) return listOf(" ")
    val wrapped = normalized.wrapHudLines(maxChars)
    return if (wrapped.isEmpty()) listOf(" ") else wrapped
}
