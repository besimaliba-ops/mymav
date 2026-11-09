package com.mymav.glasses.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Shell-owned facade over Everysight SDK (or stub).
 * Mini-apps must not open a second SDK instance.
 */
interface GlassesSession {
    val connectionState: StateFlow<ConnectionState>
    val statusMessage: StateFlow<String>
    val simulatorHost: StateFlow<String>
    val hasSdkKey: StateFlow<Boolean>
    val backendName: String
    /** True only once the glasses/simulator session is genuinely ready (not just a connect() in flight). */
    val isLinkReady: Boolean get() = connectionState.value == ConnectionState.Connected

    fun init()
    fun stop()
    fun setSimulatorHost(host: String)
    fun connectSimulator()
    fun connectDevice()
    fun disconnect()
    fun showConfigureUi()
    fun replaceHud(screenId: String, title: String, lines: List<String>)
    /**
     * Patch Spotify lyrics text on the existing glasslet when possible.
     * Returns true if the live screen was updated without a full redraw/cover re-upload.
     */
    fun patchLyricsHud(hud: GlassesHud.Lyrics): Boolean = false
    /**
     * Move / re-texture the home mascot without rebuilding the whole board (kills flicker).
     */
    fun patchHomeAvatar(
        visible: Boolean,
        x: Float,
        y: Float,
        imageBytes: ByteArray?,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean = false
    /** Move the docked mascot and show sprite-sheet [cell] (no texture upload). */
    fun patchHomeAvatarCell(visible: Boolean, x: Float, y: Float, cell: Int): Boolean = false
    /** Rich companion HUD (home / keyboard / notification / Spotify). Defaults to text summary on stub. */
    fun replaceHud(hud: GlassesHud) {
        when (hud) {
            is GlassesHud.TextLines -> replaceHud(hud.screenId, hud.title, hud.lines)
            is GlassesHud.HomeBoard -> replaceHud(
                screenId = "home-board",
                title = "MyMav Home",
                lines = listOf(
                    "Page ${hud.pageIndex + 1}/${hud.pageCount}",
                    "Focus: ${hud.focusedWidgetId}",
                    hud.widgets.joinToString(" · ") { it.title },
                ),
            )
            is GlassesHud.Keyboard -> replaceHud(
                screenId = "gaze-keyboard",
                title = hud.title,
                lines = listOf(
                    "Buffer: ${hud.buffer.ifBlank { "(empty)" }}",
                    "Focus: ${hud.focusedKeyId}",
                    hud.suggestions.take(3).joinToString(" · ").ifBlank { "Suggestions…" },
                ),
            )
            is GlassesHud.AppEnter -> replaceHud(
                screenId = "enter-${hud.appId}",
                title = hud.title,
                lines = listOf(hud.subtitle) + hud.bullets.take(4),
            )
            is GlassesHud.Notification -> replaceHud(
                screenId = "notify-message",
                title = "${hud.appLabel} · ${hud.sender}",
                lines = listOf(
                    hud.body,
                    "Reply: ${hud.replyState}",
                    hud.draftReply?.let { "Draft: $it" } ?: "Gaze mic to reply",
                ),
            )
            is GlassesHud.Lyrics -> replaceHud(
                screenId = "spotify-lyrics",
                title = "Spotify · Now Playing",
                lines = listOf(
                    "${hud.trackName} — ${hud.artistName}",
                    hud.currentLyric,
                    hud.nextLyric.ifBlank { "Synced ${hud.progressMs / 1000}s" },
                ),
            )
            is GlassesHud.IdentifyResult -> replaceHud(
                screenId = "identify-result",
                title = "Identify · ${hud.label}",
                lines = listOf(
                    hud.detail.ifBlank { "Matched ${(hud.confidence * 100).toInt()}%" },
                    "${hud.images.size} reference images",
                ),
            )
            is GlassesHud.SongList -> replaceHud(
                screenId = "spotify-songs",
                title = hud.title,
                lines = listOf(hud.statusLine.ifBlank { "${hud.items.size} tracks" }) +
                    hud.items.take(5).map { "${it.title} — ${it.artist}" },
            )
            is GlassesHud.NavMap -> replaceHud(
                screenId = "nav-map",
                title = "Navigation",
                lines = listOf(hud.nav.instruction, "in ${MymavNav.fmtDistance(hud.nav.toNextM)}", "${MymavNav.fmtDistance(hud.nav.remainingM)} left"),
            )
            is GlassesHud.FitnessSummary -> replaceHud(
                screenId = "fitness-${hud.page.name.lowercase()}",
                title = "${hud.title} · ${hud.page.name}",
                lines = listOf(hud.subtitle, "${hud.heroValue} ${hud.heroLabel}") +
                    hud.rows.map { "${it.icon} ${it.primary} · ${it.secondary}" },
            )
            is GlassesHud.TasksBoard -> replaceHud(
                screenId = "tasks-board",
                title = "Tasks",
                lines = listOf(
                    hud.statusLine.ifBlank { "${hud.openCount} open" },
                    "Mode: ${hud.mode}",
                ) + hud.items.take(5).map { row ->
                    val mark = if (row.done) "✓" else "□"
                    "$mark ${row.title} · ${row.dueLabel}"
                } + listOf("ADD · DUE · DONE · BACK"),
            )
            is GlassesHud.ListApp -> replaceHud(
                screenId = "list-${hud.appId}",
                title = hud.title,
                lines = listOf(hud.statusLine) + hud.rows.take(5).map { it.title },
            )
            is GlassesHud.WorkoutSelect -> replaceHud(
                screenId = "workout-select",
                title = "Workouts",
                lines = listOf(
                    hud.statusLine,
                    if (hud.focusedIndex == 0) "▶ Running" else "  Running",
                    if (hud.focusedIndex == 1) "▶ Weight training" else "  Weight training",
                ),
            )
            is GlassesHud.RunningPace -> replaceHud(
                screenId = "running-pace",
                title = "Running · ${hud.statusLabel}",
                lines = listOf(
                    "${hud.statusLabel} ${hud.deltaLabel}",
                    "${hud.paceLabel} ${hud.paceUnit} · ${hud.targetLabel}",
                    "ELAPSED ${hud.elapsedLabel} · SPLIT ${hud.splitMeters} · KM ${hud.distanceKmLabel}",
                ),
            )
            is GlassesHud.WeightSession -> {
                val focused = hud.exercises.getOrNull(hud.focusedIndex.coerceAtLeast(0))
                val setMarks = focused?.setsDone?.joinToString("") { if (it) "✓" else "□" }.orEmpty()
                replaceHud(
                    screenId = "weight-session",
                    title = "Weight · ${focused?.name ?: "Session"}",
                    lines = listOf(
                        hud.statusLine,
                        focused?.let {
                            "${it.name} · ${it.bodyPart.label} · ${it.reps} reps"
                        } ?: "No exercises",
                        if (setMarks.isNotBlank()) "Sets $setMarks" else "Sets —",
                    ) + hud.exercises.take(4).mapIndexed { i, ex ->
                        val mark = if (i == hud.focusedIndex) "▶" else " "
                        "$mark ${ex.name} ${ex.setsCompleted}/${ex.setsTotal}"
                    },
                )
            }
            is GlassesHud.MemoryTimeline -> replaceHud(
                screenId = "memorysight",
                title = hud.title,
                lines = listOf(hud.statusLine.ifBlank { "${hud.entries.size} memories" }) +
                    hud.entries.take(6),
            )
            is GlassesHud.MoneyQuote -> replaceHud(
                screenId = "money-quote",
                title = "Money Exchange",
                lines = listOf(
                    hud.foreignLabel.ifBlank { "Gaze a price tag…" },
                    hud.homeLabel,
                    hud.rateLabel,
                ).filter { it.isNotBlank() },
            )
            is GlassesHud.MymindBoard -> replaceHud(
                screenId = "mymind-${hud.page.name.lowercase()}",
                title = "Mymind · ${hud.page.name}",
                lines = listOf(hud.memorySummary.ifBlank { "Conversation memory" }) +
                    hud.peopleLines.take(3) +
                    hud.taskLines.take(5),
            )
        }
    }
    fun clearHud()
}
