package com.mymav.miniapps.conversate

import com.mymav.glasses.api.AppEnterActions
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.HudReadableCard
import com.mymav.glasses.api.HudReadableMode
import com.mymav.glasses.api.miniAppHomeActions

/**
 * Conversate glasses screens. Every action is a footer pill (tap or 1s gaze):
 *  idle      Start · Saved
 *  live      Summary · Save · Stop
 *  summary   Previous · Next · Back
 *  stopped   Save summary · Review · Close
 *  saved     Previous · Next · Back
 */
object ConversateHudBuilder {

    val IDLE = listOf("Start", "Saved")
    val LIVE = listOf("Summary", "Save", "Stop")
    val PAGER = listOf("Previous", "Next", "Back")
    val STOPPED = listOf("Save summary", "Review", "Close")

    /** Lines per page on the single tall card (matches AppEnterScreen's 8-line budget). */
    private const val PAGE_LINES = 8
    /** Matches AppEnterScreen body box so saved/summary pages agree with measured wrap. */
    private const val BODY_WIDTH = 540f
    private const val BODY_SCALE = 0.9f
    private const val LIVE_LINES = 3

    data class SavedSummary(val title: String, val body: String)

    fun footerFor(state: ConversateSessionState): List<String> = when {
        state.showSaved -> PAGER
        state.showRollingSummary -> PAGER
        state.pendingNotepadOffer -> STOPPED
        state.listening || state.starting || state.stopping -> LIVE
        else -> IDLE
    }

    fun premiumEnter(): GlassesHud.AppEnter = sessionHud(ConversateSessionState(), emptyList(), miniAppHomeActions())

    fun sessionHud(
        state: ConversateSessionState,
        saved: List<SavedSummary>,
        actions: AppEnterActions,
    ): GlassesHud.AppEnter = when {
        state.showSaved -> savedHud(state, saved, actions)
        state.showRollingSummary -> summaryHud(state, actions)
        state.pendingNotepadOffer -> stoppedHud(state, actions)
        state.listening || state.starting || state.stopping -> liveHud(state, actions)
        else -> idleHud(state, saved, actions)
    }

    private fun base(
        state: ConversateSessionState,
        chip: String,
        cards: List<HudReadableCard>,
        footer: List<String>,
        disabled: Set<Int>,
        actions: AppEnterActions,
    ) = GlassesHud.AppEnter(
        appId = "conversate",
        title = "Conversate",
        subtitle = state.statusLine,
        accent = HudAccentTone.Blue,
        readableMode = HudReadableMode.Conversate,
        statusChip = chip,
        cards = cards,
        footerLabels = footer,
        footerFocus = firstEnabled(state.footerFocus.coerceIn(0, footer.lastIndex), footer.size, disabled),
        footerDisabled = disabled,
        actions = actions,
    )

    private fun idleHud(state: ConversateSessionState, saved: List<SavedSummary>, actions: AppEnterActions): GlassesHud.AppEnter {
        val ready = HudReadableCard(
            eyebrow = if (state.lastError.isNotBlank()) "COULD NOT START" else "READY",
            body = state.lastError.ifBlank { "Start live captions and reply cues for this conversation." },
        )
        val savedCard = HudReadableCard(
            eyebrow = "SAVED SUMMARIES",
            body = when (saved.size) {
                0 -> "None yet. Save during or after a session."
                1 -> "1 saved · ${saved.first().title}"
                else -> "${saved.size} saved · latest ${saved.first().title}"
            },
        )
        val disabled = if (saved.isEmpty()) setOf(1) else emptySet()
        return base(state, "", listOf(ready, savedCard), IDLE, disabled, actions)
    }

    private fun liveHud(state: ConversateSessionState, actions: AppEnterActions): GlassesHud.AppEnter {
        // Unwrapped source; AppEnterScreen measured-wraps and keeps the newest caption lines.
        val caption = liveCaption(state)
        val cue = suggestedCue(state)
        val captionCard = HudReadableCard(
            eyebrow = "LIVE CAPTION",
            body = caption.ifBlank {
                when {
                    state.starting -> "Starting the microphone…"
                    state.stopping -> "Stopping · writing the summary…"
                    else -> "Listening — speak normally."
                }
            },
        )
        val cueEyebrow = if (state.saveConfirm.isNotBlank()) "SUGGESTED REPLY · SAVED" else "SUGGESTED REPLY"
        val cueCard = HudReadableCard(
            eyebrow = cueEyebrow,
            body = cue.ifBlank {
                when {
                    state.lastError.isNotBlank() -> state.lastError
                    state.statusLine.startsWith("Processing") -> "Working on a cue…"
                    else -> "Cues appear for questions, decisions and actions."
                }
            },
        )
        val chip = when {
            state.starting -> "Starting"
            state.stopping -> "Stopping"
            state.saveConfirm.isNotBlank() -> "Saved"
            else -> "Recording"
        }
        val nothingToSave = state.committedTranscript.isBlank() && state.rollingSummary.isBlank() && state.cueHistory.isEmpty()
        val disabled = when {
            state.starting || state.stopping -> setOf(0, 1, 2)
            nothingToSave -> setOf(1)
            else -> emptySet()
        }
        return base(state, chip, listOf(captionCard, cueCard), LIVE, disabled, actions)
            .copy(
                sourceLabel = if (state.listening) "Test input · ${state.micSource.lowercase()} microphone" else "",
            )
    }

    fun summaryPages(state: ConversateSessionState): List<String> {
        val lines = summaryLines(state.rollingSummary).ifEmpty {
            listOfNotNull(
                state.summary.takeIf { it.isNotBlank() },
                if (state.listening) "Key points, decisions and tasks build up every ~20 s while you talk." else null,
                state.committedTranscript.takeIf { it.isNotBlank() }?.let { "So far: " + lastSentences(it, 2) },
            ).ifEmpty { listOf("Nothing captured yet.") }
        }
        return paginate(lines)
    }

    private fun summaryHud(state: ConversateSessionState, actions: AppEnterActions): GlassesHud.AppEnter {
        val pages = summaryPages(state)
        val page = state.summaryPage.coerceIn(0, pages.lastIndex)
        val eyebrow = (if (state.listening) "SUMMARY SO FAR" else "SESSION SUMMARY") +
            if (pages.size > 1) " · ${page + 1}/${pages.size}" else ""
        val disabled = buildSet {
            if (page == 0) add(0)
            if (page >= pages.lastIndex) add(1)
        }
        val chip = if (state.listening) "Recording" else "Summary"
        return base(state, chip, listOf(HudReadableCard(eyebrow = eyebrow, body = pages[page])), PAGER, disabled, actions)
            .copy(footerFocus = if (2 in disabled) 0 else firstEnabled(state.footerFocus, 3, disabled))
    }

    private fun stoppedHud(state: ConversateSessionState, actions: AppEnterActions): GlassesHud.AppEnter {
        val points = summaryLines(state.rollingSummary).ifEmpty { listOf(state.summary.ifBlank { "Session ended." }) }
        val wrapped = points.map { wrap(it) }
        val brief = if (wrapped.sumOf { it.size } <= LIVE_LINES) {
            wrapped.flatten().joinToString("\n")
        } else {
            // Whole points only, never a half sentence; the rest is one Review away.
            val shown = mutableListOf<String>()
            for (p in wrapped) {
                if (shown.size + p.size > LIVE_LINES - 1) break
                shown += p
            }
            (shown.ifEmpty { listOf(wrapped.first().first()) } + "More in Review.").joinToString("\n")
        }
        val next = when {
            state.stopping -> "Writing the summary..."
            state.finalSaved -> "Summary saved to Notepad."
            state.notepadSaved -> "Save summary updates the note you saved during the session."
            else -> "Save it to Notepad, review it here, or close."
        }
        val disabled = if (state.stopping) setOf(0, 1) else emptySet()
        val chip = when {
            state.stopping -> "Stopping"
            state.finalSaved -> "Saved"
            else -> "Stopped"
        }
        return base(
            state,
            chip,
            listOf(
                HudReadableCard(eyebrow = "SESSION SUMMARY", body = brief),
                HudReadableCard(eyebrow = "NEXT", body = next),
            ),
            STOPPED,
            disabled,
            actions,
        )
    }

    fun savedPages(saved: List<SavedSummary>): List<Pair<SavedSummary, String>> =
        saved.flatMap { s ->
            val pages = paginate(s.body.lines().map { it.trim() }.filter { it.isNotBlank() })
            pages.map { s to it }
        }

    private fun savedHud(state: ConversateSessionState, saved: List<SavedSummary>, actions: AppEnterActions): GlassesHud.AppEnter {
        val pages = savedPages(saved)
        if (pages.isEmpty()) {
            return base(state, "Saved", listOf(HudReadableCard("SAVED SUMMARIES", "No saved summaries yet.")), PAGER, setOf(0, 1), actions)
        }
        val page = state.savedPage.coerceIn(0, pages.lastIndex)
        val (note, body) = pages[page]
        val disabled = buildSet {
            if (page == 0) add(0)
            if (page >= pages.lastIndex) add(1)
        }
        return base(
            state,
            "Saved",
            listOf(HudReadableCard(eyebrow = savedTitleEyebrow(note.title), body = body)),
            PAGER,
            disabled,
            actions,
        ).copy(footerFocus = firstEnabled(state.footerFocus, 3, disabled))
    }

    fun pushBriefingCues(
        session: com.mymav.glasses.api.GlassesSession,
        mode: MeetingMode,
        briefingIndex: String,
    ) {
        val cues = briefingCuesForMode(mode, briefingIndex)
        val state = ConversateSessionState(
            listening = false,
            statusLine = "Briefing loaded",
            meetingMode = mode.label,
            activeCue = cues.firstOrNull(),
            cueHistory = cues,
            prepNotes = briefingIndex.take(200),
        )
        val hud = sessionHud(state, emptyList(), miniAppHomeActions())
        session.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    fun conversateActions(
        onFooter: (Int) -> Unit,
        onFooterFocus: ((Int) -> Unit)? = null,
    ): AppEnterActions {
        val base = miniAppHomeActions()
        return AppEnterActions(
            onHomeRequested = base.onHomeRequested,
            onGazeNormalized = base.onGazeNormalized,
            onFooterFocusChanged = onFooterFocus,
            onFooterActivated = onFooter,
        )
    }

    private fun firstEnabled(preferred: Int, count: Int, disabled: Set<Int>): Int {
        if (preferred !in disabled) return preferred
        return (0 until count).firstOrNull { it !in disabled } ?: preferred
    }

    /** Full title, word-safe. AppEnterScreen wraps it; page lives in the card, not a mid-word take(). */
    private fun savedTitleEyebrow(title: String): String =
        title.replace(Regex("\\s+"), " ").trim().uppercase().ifBlank { "SAVED SUMMARY" }

    private fun paginate(lines: List<String>): List<String> {
        val wrapped = lines.flatMap { wrap(it) }
        return wrapped.chunked(PAGE_LINES).map { it.joinToString("\n") }.ifEmpty { listOf("") }
    }

    /** Same measured wrap the glasses renderer uses, so page counts agree. */
    private fun wrap(text: String): List<String> =
        com.mymav.glasses.api.HudLineLayout.wrap(text, BODY_WIDTH, BODY_SCALE, false)

    private fun lastSentences(text: String, n: Int): String =
        text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.takeLast(n).joinToString(" ")

    /** Latest speech natural; history only as prior context — no permanent ellipsis. */
    private fun liveCaption(state: ConversateSessionState): String = when {
        state.captionProvisional && state.livePartial.isNotBlank() -> state.livePartial.trim()
        state.committedTranscript.isNotBlank() -> lastSentences(state.committedTranscript, 1)
        state.liveTranscript.isNotBlank() -> state.liveTranscript.trim()
        else -> ""
    }

    private fun suggestedCue(state: ConversateSessionState): String {
        val cue = state.activeCue ?: return ""
        return cue.detail.ifBlank { cue.headline }
    }

    fun summaryLines(s: RollingSummary): List<String> = buildList {
        s.keyPoints.forEach { add("• $it") }
        s.decisions.forEach { add("Decision: $it") }
        s.actions.forEach { add("Task: $it") }
        s.unresolved.forEach { add("Open: $it") }
    }

    private fun briefingCuesForMode(mode: MeetingMode, briefing: String): List<ConversateCue> {
        val tail = briefing.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(2)
            .joinToString(" · ").ifBlank { "No briefing text yet" }
        return when (mode) {
            MeetingMode.Interview -> listOf(
                ConversateCue(ConversateCueType.Suggestion, "Open with context", tail.take(60)),
                ConversateCue(ConversateCueType.Answer, "STAR story ready", "Have one example for each role requirement."),
            )
            MeetingMode.Sales -> listOf(
                ConversateCue(ConversateCueType.Concept, "Discovery first", "Confirm pain before pricing."),
                ConversateCue(ConversateCueType.Suggestion, "Next step", "Propose a concrete follow-up date."),
            )
            MeetingMode.Presentation -> listOf(
                ConversateCue(ConversateCueType.Concept, "Capture claims", "Note definitions and numbers."),
                ConversateCue(ConversateCueType.Suggestion, "Q&A ideas", "Draft 2 questions from the deck."),
            )
            MeetingMode.Social -> listOf(
                ConversateCue(ConversateCueType.Bio, "Remember names", tail.take(50)),
                ConversateCue(ConversateCueType.Suggestion, "Follow-up", "Mirror their last point before sharing."),
            )
            MeetingMode.General -> listOf(
                ConversateCue(ConversateCueType.Suggestion, "Align on goal", tail.take(60)),
                ConversateCue(ConversateCueType.Answer, "Clarify before agreeing", "Ask what success looks like."),
            )
        }
    }
}
