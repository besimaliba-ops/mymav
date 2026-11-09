package com.mymav.miniapps.conversate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymavActions
import com.mymav.glasses.api.MymavAi
import com.mymav.glasses.api.MymavCaptions
import com.mymav.glasses.api.MymavMicOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Live Conversate: phone STT, CueGate, cloud/local cues, rolling summary, timings.
 * Cancels obsolete AI requests so older cues never replace newer ones.
 * On Stop: offers Notepad save — never silently creates tasks.
 */
class ConversationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (ConversateSessionState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cueClient = ConversateCueClient()
    private val cueGate = CueGate()
    private val analyzeMutex = Mutex()
    private val transcriptBuffer = StringBuilder()
    private val cueRequestGen = AtomicInteger(0)
    private val summaryRequestGen = AtomicInteger(0)

    private var state = ConversateSessionState()
    private var speechRecognizer: SpeechRecognizer? = null
    private var analyzeJob: Job? = null
    private var summaryJob: Job? = null
    private var speechWatchdogJob: Job? = null
    private var autoDismissJob: Job? = null
    private var speechRestartPending = false
    private var lastSttEventAt = 0L
    private var lastAnalyzeAt = 0L
    private var lastSummaryAt = 0L
    private var sessionStartedAt = 0L
    private var briefingContext = ""
    private var privacy = ConversatePrivacySettings()
    private var meetingMode = MeetingMode.General
    private var livePartialText = ""
    private var hudSession: GlassesSession? = null
    private var lastPartialStartedAt = AtomicLong(0L)
    private var lastFinalUtteranceStartedAt = 0L
    private var transcriptionDelaySum = 0L
    private var transcriptionDelayCount = 0
    private var cueDelaySum = 0L
    private var cueDelayCount = 0
    private var lastRecord: ConversateSessionRecord? = null

    fun currentState(): ConversateSessionState = state
    fun lastRecord(): ConversateSessionRecord? = lastRecord

    fun onPremiumEnter(
        session: GlassesSession,
        mode: MeetingMode,
        prepNotes: String,
        briefingMaterials: String,
    ) {
        hudSession = session
        if (!state.listening && !state.stopping && !state.pendingNotepadOffer) {
            meetingMode = mode
            briefingContext = BriefingPack.compile(prepNotes, briefingMaterials, mode)
            patch {
                copy(
                    meetingMode = mode.label,
                    prepNotes = prepNotes,
                    statusLine = "Idle",
                    summary = "",
                    lastError = "",
                    notepadSaved = false,
                    showRollingSummary = false,
                    showSaved = false,
                    footerFocus = 0,
                )
            }
        }
        pushHud(session)
    }

    /** Start from the glasses (tap or gaze on Start); shows "Starting" until the mic is live. */
    fun startFromHud(session: GlassesSession? = hudSession) {
        if (state.listening || state.starting || state.stopping) return
        patch { copy(starting = true, lastError = "", pendingNotepadOffer = false, showSaved = false, statusLine = "Starting…") }
        pushHud(session)
        scope.launch {
            val err = start(
                SessionStartConfig(meetingMode = meetingMode, prepNotes = state.prepNotes),
                session ?: hudSession,
            )
            if (err != null) {
                patch { copy(starting = false, lastError = err, statusLine = "Idle") }
                pushHud(session)
            }
        }
    }

    /** Null = started; otherwise honest blocker. */
    fun start(config: SessionStartConfig, session: GlassesSession? = hudSession): String? {
        if (state.listening) return "Conversate is already listening."
        hudSession = session ?: hudSession
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Microphone permission is off. Allow it in MyMav Settings, Meeting recorder."
        }
        if (MymavActions.meeting?.recording() == true) {
            return "A meeting is being recorded, and the microphone can't do both. Stop the recording first."
        }
        MymavCaptions.stop(false)
        MymavMicOwner.tryAcquire(MymavMicOwner.Owner.Conversate)?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            MymavMicOwner.release(MymavMicOwner.Owner.Conversate)
            return "This phone has no speech recognition service available."
        }

        privacy = config.privacy
        meetingMode = config.meetingMode
        briefingContext = BriefingPack.compile(config.prepNotes, config.briefingMaterials, meetingMode)
        cueGate.reset()
        cueRequestGen.incrementAndGet()
        summaryRequestGen.incrementAndGet()
        sessionStartedAt = System.currentTimeMillis()
        lastSttEventAt = sessionStartedAt
        lastAnalyzeAt = 0L
        lastSummaryAt = 0L
        transcriptBuffer.clear()
        livePartialText = ""
        transcriptionDelaySum = 0L
        transcriptionDelayCount = 0
        cueDelaySum = 0L
        cueDelayCount = 0
        lastRecord = null
        val cloud = privacy.cloudAiEnabled && MymavAi.isConfigured()
        val transcribeOnly = !privacy.aiCuesEnabled && privacy.liveTranscriptionEnabled
        val newId = UUID.randomUUID().toString()
        patch {
            copy(
                sessionId = newId,
                noteId = "conversate-$newId",
                notepadSaved = false,
                finalSaved = false,
                listening = true,
                starting = false,
                stopping = false,
                showSaved = false,
                saveConfirm = "",
                footerFocus = 0,
                summaryPage = 0,
                statusLine = when {
                    transcribeOnly -> "Transcribe only"
                    cloud && privacy.aiCuesEnabled -> "Listening"
                    privacy.aiCuesEnabled -> "Listening (local cues — add AI key for cloud)"
                    else -> "Listening"
                },
                meetingMode = config.meetingMode.label,
                prepNotes = config.prepNotes,
                micSource = "Phone",
                liveTranscript = "",
                committedTranscript = "",
                livePartial = "",
                captionProvisional = false,
                activeCue = null,
                cueHistory = emptyList(),
                rollingSummary = RollingSummary(),
                showRollingSummary = false,
                engagement = 68,
                cadence = 50,
                summary = "",
                lastError = "",
                timings = ConversateTimings(),
                pendingNotepadOffer = false,
            )
        }
        startPhoneSpeech()
        pushHud()
        if (privacy.aiCuesEnabled) {
            analyzeJob = scope.launch(Dispatchers.Default) {
                while (state.listening) {
                    delay(5_500)
                    runCueAnalysis()
                }
            }
        }
        if (privacy.cloudAiEnabled && MymavAi.isConfigured()) {
            summaryJob = scope.launch(Dispatchers.Default) {
                while (state.listening) {
                    delay(18_000)
                    runRollingSummary()
                }
            }
        }
        speechWatchdogJob = scope.launch {
            while (state.listening) {
                delay(4_500)
                ensurePhoneSpeechActive()
            }
        }
        return null
    }

    fun stopListening(session: GlassesSession? = hudSession) {
        if (!state.listening && speechRecognizer == null) return
        cueRequestGen.incrementAndGet()
        summaryRequestGen.incrementAndGet()
        analyzeJob?.cancel()
        summaryJob?.cancel()
        speechWatchdogJob?.cancel()
        autoDismissJob?.cancel()
        analyzeJob = null
        summaryJob = null
        speechWatchdogJob = null
        autoDismissJob = null
        stopPhoneSpeech()
        MymavMicOwner.release(MymavMicOwner.Owner.Conversate)
        patch {
            copy(
                listening = false,
                stopping = true,
                showRollingSummary = false,
                statusLine = "Wrapping up…",
                livePartial = "",
                captionProvisional = false,
            )
        }
        val s = session ?: hudSession
        pushHud(s)
        scope.launch {
            val record = runCatching { finalizeSession() }.getOrNull()
            lastRecord = record
            patch {
                copy(
                    stopping = false,
                    finalSaved = false,
                    pendingNotepadOffer = true,
                    footerFocus = 0,
                    summaryPage = 0,
                    statusLine = "Stopped · mic off",
                )
            }
            pushHud(s)
        }
    }

    fun toggleRollingSummary(session: GlassesSession? = hudSession) {
        // Opening summary starts at current page; closing returns without wiping session.
        patch {
            copy(
                showRollingSummary = !showRollingSummary,
                summaryPage = 0,
                footerFocus = if (!showRollingSummary) 2 else 0,
                saveConfirm = "",
            )
        }
        pushHud(session)
    }

    fun setFooterFocus(index: Int, session: GlassesSession? = hudSession) {
        patch { copy(footerFocus = index.coerceIn(0, ConversateHudBuilder.footerFor(this).lastIndex)) }
        pushHud(session)
    }

    fun setContentPage(page: Int, session: GlassesSession? = hudSession) {
        patch {
            if (showRollingSummary || pendingNotepadOffer) {
                copy(summaryPage = page.coerceAtLeast(0))
            } else {
                copy(captionPage = page.coerceAtLeast(0))
            }
        }
        pushHud(session)
    }

    /**
     * Save → Notepad snapshot + confirm **without stopping** the session.
     * Also used after Stop when a final record exists.
     */
    fun saveSummaryToNotepad(): String {
        val notes = MymavActions.notes ?: return "Notepad isn't available."
        val record = lastRecord
        val rolling = record?.rollingSummary ?: state.rollingSummary
        val cues = record?.cues ?: state.cueHistory
        val summaryText = record?.summary?.ifBlank { null }
            ?: state.summary.ifBlank { null }
            ?: rolling.oneLine().ifBlank { transcriptBuffer.toString().takeLast(400) }
        if (summaryText.isBlank() && rolling.isBlank() && cues.isEmpty() && transcriptBuffer.isEmpty()) {
            return "Nothing to save yet."
        }
        val body = buildString {
            appendLine(summaryText)
            appendLine()
            if (rolling.keyPoints.isNotEmpty()) {
                appendLine("Key points:")
                rolling.keyPoints.forEach { appendLine("• $it") }
            }
            if (rolling.decisions.isNotEmpty()) {
                appendLine("Decisions:")
                rolling.decisions.forEach { appendLine("• $it") }
            }
            if (rolling.unresolved.isNotEmpty()) {
                appendLine("Unresolved:")
                rolling.unresolved.forEach { appendLine("• $it") }
            }
            if (rolling.actions.isNotEmpty()) {
                appendLine("Actions:")
                rolling.actions.forEach { appendLine("• $it") }
            }
            if (cues.isNotEmpty()) {
                appendLine()
                appendLine("Cues:")
                cues.takeLast(8).forEach { appendLine("• [${it.type.label}] ${it.headline}: ${it.detail}") }
            }
            if (state.listening && transcriptBuffer.isNotEmpty()) {
                appendLine()
                appendLine("Live transcript (snapshot):")
                appendLine(transcriptBuffer.toString().takeLast(1_200))
            }
        }
        val noteId = state.noteId.ifBlank { "conversate-" + state.sessionId.ifBlank { UUID.randomUUID().toString() } }
        val startedAt = sessionStartedAt.takeIf { it > 0 } ?: record?.startedAtMs ?: System.currentTimeMillis()
        val msg = runCatching {
            notes.saveOrUpdate(id = noteId, title = "Conversate ${noteTitleTime(startedAt)}", text = body.trim())
        }.getOrElse {
            val err = "Couldn't save: ${it.message ?: it.javaClass.simpleName}"
            patch { copy(saveConfirm = "", lastError = err) }
            pushHud()
            return err
        }
        val confirm = if (state.listening) "Saved · still listening" else "Saved to Notepad"
        patch {
            copy(
                noteId = noteId,
                notepadSaved = true,
                finalSaved = finalSaved || !listening,
                statusLine = confirm,
                saveConfirm = confirm,
                lastError = "",
            )
        }
        pushHud()
        val shown = confirm
        scope.launch {
            delay(4_000)
            if (state.saveConfirm == shown) {
                patch { copy(saveConfirm = "", statusLine = if (listening) listeningStatus() else statusLine) }
                pushHud()
            }
        }
        return msg
    }

    fun dismissNotepadOffer() {
        patch {
            copy(
                pendingNotepadOffer = false,
                statusLine = "Idle",
                saveConfirm = "",
                showRollingSummary = false,
                showSaved = false,
                footerFocus = 0,
            )
        }
        pushHud()
    }

    private fun savedSummaries(): List<ConversateHudBuilder.SavedSummary> =
        runCatching {
            MymavActions.notes?.search(listOf("conversate"), 0L, Long.MAX_VALUE).orEmpty()
                .filter { it.title.startsWith("Conversate") }
                .take(10)
                .map { ConversateHudBuilder.SavedSummary(it.title, it.body) }
        }.getOrDefault(emptyList())

    private fun onFooter(index: Int, session: GlassesSession) {
        val label = ConversateHudBuilder.footerFor(state).getOrNull(index) ?: return
        when (label) {
            "Start" -> startFromHud(session)
            "Saved" -> {
                patch { copy(showSaved = true, savedPage = 0, footerFocus = 2) }
                pushHud(session)
            }
            "Summary", "Review" -> toggleRollingSummary(session)
            "Save", "Save summary" -> saveSummaryToNotepad()
            "Stop" -> if (state.listening) stopListening(session)
            "Close" -> dismissNotepadOffer()
            "Previous", "Next" -> {
                val step = if (label == "Next") 1 else -1
                patch {
                    if (showSaved) {
                        val last = ConversateHudBuilder.savedPages(savedSummaries()).lastIndex.coerceAtLeast(0)
                        copy(savedPage = (savedPage + step).coerceIn(0, last))
                    } else {
                        val last = ConversateHudBuilder.summaryPages(this).lastIndex
                        copy(summaryPage = (summaryPage + step).coerceIn(0, last))
                    }
                }
                pushHud(session)
            }
            "Back" -> {
                patch {
                    if (showSaved) copy(showSaved = false, footerFocus = 1)
                    else copy(showRollingSummary = false, footerFocus = 0)
                }
                pushHud(session)
            }
        }
    }

    fun pushHud(session: GlassesSession? = hudSession) {
        val s = session ?: hudSession ?: return
        if (!GlassesHomeController.acceptsMiniAppHud("conversate")) return
        main.removeCallbacks(flushHud)
        hudPending = false
        val actions = ConversateHudBuilder.conversateActions(
            onFooter = { onFooter(it, s) },
            onFooterFocus = { setFooterFocus(it, s) },
        )
        val hud = ConversateHudBuilder.sessionHud(state, savedSummaries(), actions)
        s.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    /** Partial captions land many times a second; keep the latest and flush at most every 200 ms. */
    private var hudPending = false
    private val flushHud = Runnable {
        hudPending = false
        pushHud()
    }

    private fun requestHud(immediate: Boolean = false) {
        if (immediate) {
            pushHud()
            return
        }
        if (hudPending) return
        hudPending = true
        main.postDelayed(flushHud, 200L)
    }

    suspend fun finalizeSession(): ConversateSessionRecord {
        val endedAt = System.currentTimeMillis()
        val transcript = transcriptBuffer.toString()
        patch { copy(statusLine = "Saving summary…") }
        val cloudSummary = if (privacy.cloudAiEnabled && MymavAi.isConfigured() && transcript.length >= 20) {
            cueClient.generateSummary(transcript, briefingContext, state.cueHistory)
        } else {
            null
        }
        val rolling = when {
            cloudSummary != null -> RollingSummary(
                keyPoints = cloudSummary.keyPoints,
                decisions = cloudSummary.decisions,
                unresolved = cloudSummary.unresolved,
                actions = cloudSummary.actionItems,
                updatedAtMs = endedAt,
            )
            else -> state.rollingSummary
        }
        val summary = when {
            cloudSummary != null -> buildString {
                append(cloudSummary.summary)
                if (cloudSummary.keyPoints.isNotEmpty()) {
                    append(" Key: ")
                    append(cloudSummary.keyPoints.take(3).joinToString("; "))
                }
            }
            state.cueHistory.isNotEmpty() ->
                "Session (${state.meetingMode}): ${state.cueHistory.size} cues. ${state.cueHistory.last().headline}."
            briefingContext.isNotBlank() ->
                "Briefing-led session (${state.meetingMode}). Index loaded (${briefingContext.length} chars)."
            transcript.isNotBlank() -> {
                val words = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
                "Captured $words words. Add an AI key in Settings for a cloud summary."
            }
            else -> "No speech captured."
        }
        val timings = currentTimings()
        patch {
            copy(
                statusLine = "Idle",
                summary = summary,
                rollingSummary = rolling,
                timings = timings,
            )
        }
        return ConversateSessionRecord(
            id = state.sessionId.ifBlank { UUID.randomUUID().toString() },
            startedAtMs = sessionStartedAt.takeIf { it > 0 } ?: endedAt,
            endedAtMs = endedAt,
            prepNotes = state.prepNotes,
            fullTranscript = transcript,
            summary = summary,
            rollingSummary = rolling,
            cues = state.cueHistory,
            micSource = state.micSource,
            timings = timings,
        )
    }

    fun release() {
        cueRequestGen.incrementAndGet()
        summaryRequestGen.incrementAndGet()
        analyzeJob?.cancel()
        summaryJob?.cancel()
        speechWatchdogJob?.cancel()
        autoDismissJob?.cancel()
        stopPhoneSpeech()
        MymavMicOwner.release(MymavMicOwner.Owner.Conversate)
        if (state.listening) {
            patch { copy(listening = false, starting = false, statusLine = "Idle", livePartial = "", captionProvisional = false) }
        }
    }

    private fun noteTitleTime(ms: Long): String =
        java.text.SimpleDateFormat("d MMM HH:mm", Locale.UK).format(java.util.Date(ms))

    private fun currentTimings(): ConversateTimings = ConversateTimings(
        lastTranscriptionDelayMs = state.timings.lastTranscriptionDelayMs,
        lastCueDelayMs = state.timings.lastCueDelayMs,
        avgTranscriptionDelayMs = if (transcriptionDelayCount > 0) transcriptionDelaySum / transcriptionDelayCount else 0L,
        avgCueDelayMs = if (cueDelayCount > 0) cueDelaySum / cueDelayCount else 0L,
        transcriptionSamples = transcriptionDelayCount,
        cueSamples = cueDelayCount,
    )

    private fun startPhoneSpeech() {
        main.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(recognitionListener)
            }
            listenOnceSafe()
        }
    }

    private fun stopPhoneSpeech() {
        main.post {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        }
        lastFinalUtteranceStartedAt = System.currentTimeMillis()
        speechRecognizer?.startListening(intent)
    }

    private fun listenOnceSafe() {
        if (!state.listening) return
        runCatching { listenOnce() }.onFailure {
            Log.w(TAG, "startListening failed", it)
            schedulePhoneSpeechRestart(500)
        }
    }

    private fun schedulePhoneSpeechRestart(delayMs: Long = 150L) {
        if (!state.listening) return
        if (speechRestartPending) return
        speechRestartPending = true
        scope.launch {
            delay(delayMs)
            speechRestartPending = false
            if (!state.listening) return@launch
            main.post { listenOnceSafe() }
        }
    }

    private fun ensurePhoneSpeechActive() {
        if (!state.listening) return
        val idleMs = System.currentTimeMillis() - lastSttEventAt
        if (idleMs > 4_000) {
            Log.i(TAG, "STT idle ${idleMs}ms — restarting phone recognizer")
            startPhoneSpeech()
        }
    }

    private fun markSttActivity() {
        lastSttEventAt = System.currentTimeMillis()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (state.activeCue == null) patch { copy(statusLine = listeningStatus()) }
        }
        override fun onBeginningOfSpeech() {
            lastPartialStartedAt.set(System.currentTimeMillis())
            lastFinalUtteranceStartedAt = System.currentTimeMillis()
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            schedulePhoneSpeechRestart(80)
        }
        override fun onError(error: Int) {
            markSttActivity()
            if (!state.listening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    patch {
                        copy(
                            listening = false,
                            statusLine = "Error",
                            lastError = "Microphone permission was removed.",
                            captionProvisional = false,
                        )
                    }
                    stopPhoneSpeech()
                    MymavMicOwner.release(MymavMicOwner.Owner.Conversate)
                    pushHud()
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    patch { copy(statusLine = "STT network…", lastError = "Speech service unreachable") }
                    schedulePhoneSpeechRestart(1_500)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> schedulePhoneSpeechRestart(600)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> schedulePhoneSpeechRestart(300)
                else -> schedulePhoneSpeechRestart(300)
            }
        }
        override fun onResults(results: Bundle?) {
            markSttActivity()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) appendTranscript(text)
            schedulePhoneSpeechRestart(120)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!privacy.liveTranscriptionEnabled) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                markSttActivity()
                if (lastPartialStartedAt.get() == 0L) lastPartialStartedAt.set(System.currentTimeMillis())
                livePartialText = text.trim()
                val committed = transcriptBuffer.toString().trim()
                val combined = listOf(committed, livePartialText).filter { it.isNotBlank() }.joinToString(" ")
                // In-place update — provisional caption, no full-screen flash. Coalesced so a burst of
                // STT partials does not rebuild (or even patch) the glasslet on every packet.
                patch {
                    copy(
                        committedTranscript = committed,
                        livePartial = livePartialText,
                        liveTranscript = combined.takeLast(420),
                        captionProvisional = true,
                        statusLine = if (activeCue != null) "Cue ready" else "Caption…",
                    )
                }
                requestHud()
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun debugTranscript(text: String) {
        if (state.listening && text.isNotBlank()) main.post { appendTranscript(text) }
    }

    private fun appendTranscript(text: String) {
        if (!privacy.liveTranscriptionEnabled) return
        val now = System.currentTimeMillis()
        val started = lastPartialStartedAt.get().takeIf { it > 0 } ?: lastFinalUtteranceStartedAt
        if (started > 0) {
            val delayMs = (now - started).coerceAtLeast(0L)
            transcriptionDelaySum += delayMs
            transcriptionDelayCount++
            patch {
                copy(
                    timings = timings.copy(
                        lastTranscriptionDelayMs = delayMs,
                        avgTranscriptionDelayMs = transcriptionDelaySum / transcriptionDelayCount,
                        transcriptionSamples = transcriptionDelayCount,
                    ),
                )
            }
        }
        lastPartialStartedAt.set(0L)
        livePartialText = ""
        if (transcriptBuffer.isNotEmpty()) transcriptBuffer.append(' ')
        transcriptBuffer.append(text.trim())
        if (transcriptBuffer.length > 12_000) {
            transcriptBuffer.delete(0, transcriptBuffer.length - 10_000)
        }
        val committed = transcriptBuffer.toString()
        patch {
            copy(
                committedTranscript = committed,
                livePartial = "",
                liveTranscript = committed.takeLast(420),
                captionProvisional = false,
                statusLine = listeningStatus(),
            )
        }
        pushHud()
        scope.launch(Dispatchers.Default) { runCueAnalysis() }
    }

    private suspend fun runCueAnalysis() {
        if (!analyzeMutex.tryLock()) return
        try {
            maybeAnalyze()
        } finally {
            analyzeMutex.unlock()
        }
    }

    private suspend fun maybeAnalyze() {
        if (!state.listening || !privacy.aiCuesEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastAnalyzeAt < 5_000) return
        val transcript = transcriptBuffer.toString()
        if (transcript.length < 36) return
        if (!cueGate.passesTranscriptInterest(transcript, meetingMode)) return
        lastAnalyzeAt = now
        val req = cueRequestGen.incrementAndGet()
        val cueStarted = System.currentTimeMillis()
        val previousStatus = state.statusLine
        patch { copy(statusLine = "Processing…") }
        pushHud()
        try {
            val proposal = if (privacy.cloudAiEnabled && MymavAi.isConfigured()) {
                cueClient.generateCue(transcript, briefingContext, state.cueHistory, meetingMode)
            } else {
                CueProposal(localCue(transcript), confidence = 0.72f, engagement = 72, cadence = 52)
            }
            // Obsolete request — drop silently (never replace newer cue).
            if (req != cueRequestGen.get() || !state.listening) return
            val cue = proposal?.cue ?: return
            if (!cueGate.passesCooldown(cue.type, System.currentTimeMillis())) return
            if (!cueGate.passesConfidence(proposal.confidence)) return
            cueGate.recordShown(cue.type)
            Log.i(TAG, "cue ${cue.type.label}: ${cue.headline} | ${cue.detail}")
            val cueDelay = System.currentTimeMillis() - cueStarted
            cueDelaySum += cueDelay
            cueDelayCount++
            val history = (state.cueHistory + cue).takeLast(12)
            patch {
                copy(
                    activeCue = cue,
                    cueHistory = history,
                    statusLine = "Cue ready",
                    engagement = proposal.engagement,
                    cadence = proposal.cadence,
                    timings = timings.copy(
                        lastCueDelayMs = cueDelay,
                        avgCueDelayMs = cueDelaySum / cueDelayCount,
                        cueSamples = cueDelayCount,
                    ),
                )
            }
            pushHud()
            autoDismissJob?.cancel()
            autoDismissJob = scope.launch {
                delay(14_000)
                if (state.activeCue == cue && req == cueRequestGen.get()) {
                    patch {
                        copy(
                            activeCue = null,
                            statusLine = if (listening) listeningStatus() else "Idle",
                        )
                    }
                    pushHud()
                }
            }
        } catch (e: Exception) {
            if (req != cueRequestGen.get()) return
            Log.w(TAG, "cue analysis failed", e)
            patch {
                copy(
                    statusLine = "Error",
                    lastError = e.message?.take(80) ?: e.javaClass.simpleName,
                )
            }
            pushHud()
            delay(1_200)
            if (state.listening && req == cueRequestGen.get()) {
                patch { copy(statusLine = listeningStatus(), lastError = "") }
                pushHud()
            }
        } finally {
            if (state.listening && state.statusLine == "Processing…" && req == cueRequestGen.get()) {
                patch { copy(statusLine = previousStatus.ifBlank { listeningStatus() }) }
                pushHud()
            }
            ensurePhoneSpeechActive()
        }
    }

    private suspend fun runRollingSummary() {
        if (!state.listening || !privacy.cloudAiEnabled || !MymavAi.isConfigured()) return
        val now = System.currentTimeMillis()
        if (now - lastSummaryAt < 16_000) return
        val transcript = transcriptBuffer.toString()
        if (transcript.length < 80) return
        lastSummaryAt = now
        val req = summaryRequestGen.incrementAndGet()
        val rolling = cueClient.generateRollingSummary(transcript, briefingContext, state.rollingSummary)
            ?: return
        if (req != summaryRequestGen.get() || !state.listening) return
        patch { copy(rollingSummary = rolling) }
        // Refresh home widget preview; HUD only if summary panel open or no active cue flash.
        if (state.showRollingSummary) pushHud()
        else publishHomePreview()
    }

    private fun listeningStatus(): String = when {
        !privacy.aiCuesEnabled && privacy.liveTranscriptionEnabled -> "Transcribe only"
        privacy.cloudAiEnabled && MymavAi.isConfigured() -> "Listening"
        privacy.aiCuesEnabled -> "Listening (local cues)"
        else -> "Listening"
    }

    private fun localCue(transcript: String): ConversateCue {
        val lower = transcript.takeLast(200).lowercase(Locale.UK)
        return when {
            listOf("decide", "decision", "agree", "go with", "we'll").any { it in lower } ->
                ConversateCue(ConversateCueType.Suggestion, "Confirm decision", "Restate the choice and owner.")
            listOf("action", "next step", "follow up", "i'll", "schedule").any { it in lower } ->
                ConversateCue(ConversateCueType.Answer, "Capture the action", "Who owns it, and by when?")
            "?" in lower ->
                ConversateCue(ConversateCueType.Suggestion, "Answer the question", transcript.takeLast(48))
            else ->
                ConversateCue(ConversateCueType.Suggestion, "Follow up", "Ask about: ${transcript.takeLast(40)}…")
        }
    }

    private fun patch(block: ConversateSessionState.() -> ConversateSessionState) {
        state = state.block()
        onState(state)
        publishHomePreview()
    }

    private fun publishHomePreview() {
        com.mymav.glasses.api.HomeWidgetPreviewStore.put(
            "conversate",
            com.mymav.glasses.api.HomeWidgetPreviewStore.Preview(
                title = "Conversate",
                subtitle = state.statusLine,
                metricDetail = state.meetingMode,
                state = if (state.listening) "live" else "idle",
            ),
        )
        GlassesHomeController.refreshHomeIfShowing()
    }

    companion object {
        private const val TAG = "ConversationEngine"
    }
}
