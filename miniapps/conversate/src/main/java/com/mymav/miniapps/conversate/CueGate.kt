package com.mymav.miniapps.conversate

/**
 * Reduces noisy cues: per-type cooldown, transcript interest heuristics, model confidence.
 * Ported from maverick-ai-hello conversate CueGate; tuned so decision/action phrases pass
 * without firing a generic cue on every short sentence.
 */
class CueGate(
    private val typeCooldownMs: Long = 28_000L,
    private val minConfidence: Float = 0.64f,
) {
    private val lastShownByType = mutableMapOf<ConversateCueType, Long>()
    private var lastAnyShownAt = 0L
    private var lastTranscriptTail = ""

    fun passesTranscriptInterest(transcript: String, mode: MeetingMode = MeetingMode.General): Boolean {
        val tail = transcript.takeLast(400).lowercase()
        // Skip if essentially the same tail we already gated (partial STT churn).
        if (tail == lastTranscriptTail) return false
        if (mode == MeetingMode.Presentation) {
            if (tail.length < 40) return false
            val presentationSignals = listOf(
                tail.split(Regex("\\s+")).size >= 18,
                "percent" in tail || "%" in tail,
                tail.contains(" means "),
                tail.contains(" because "),
                tail.contains(" research "),
                tail.contains(" data "),
                tail.contains(" study "),
                tail.contains(" example "),
                "?" in tail,
                decisionSignal(tail),
                actionSignal(tail),
            )
            val ok = presentationSignals.count { it } >= 1
            if (ok) lastTranscriptTail = tail
            return ok
        }
        if (tail.length < 28) return false
        val signals = listOf(
            "?" in tail,
            tail.contains(" who "),
            tail.contains(" what "),
            tail.contains(" how "),
            tail.contains(" why "),
            tail.contains(" when "),
            tail.contains(" called "),
            tail.contains(" means "),
            tail.contains(" think "),
            tail.contains(" agree "),
            tail.contains(" decide "),
            tail.contains(" decision"),
            tail.contains(" question"),
            decisionSignal(tail),
            actionSignal(tail),
            Regex("""\b[A-Z][a-z]+ [A-Z][a-z]+\b""").containsMatchIn(transcript.takeLast(200)),
            tail.split(Regex("\\s+")).size >= 10,
        )
        // Need 2 signals OR a strong decision/action alone with enough words.
        val count = signals.count { it }
        val strong = (decisionSignal(tail) || actionSignal(tail)) &&
            tail.split(Regex("\\s+")).size >= 8
        val ok = count >= 2 || strong
        if (ok) lastTranscriptTail = tail
        return ok
    }

    fun passesCooldown(type: ConversateCueType, now: Long = System.currentTimeMillis()): Boolean {
        if (now - lastAnyShownAt < 12_000L) return false
        val last = lastShownByType[type] ?: return true
        return now - last >= typeCooldownMs
    }

    fun passesConfidence(confidence: Float): Boolean = confidence >= minConfidence

    fun recordShown(type: ConversateCueType, now: Long = System.currentTimeMillis()) {
        lastShownByType[type] = now
        lastAnyShownAt = now
    }

    fun reset() {
        lastShownByType.clear()
        lastAnyShownAt = 0L
        lastTranscriptTail = ""
    }

    private fun decisionSignal(tail: String): Boolean =
        listOf(
            "let's ", "lets ", "we should ", "we'll ", "we will ",
            "decided", "agree to ", "go with ", "choose ", "picked ",
        ).any { it in tail }

    private fun actionSignal(tail: String): Boolean =
        listOf(
            "action ", "next step", "follow up", "follow-up", "send ",
            "email ", "schedule ", "book ", "remind ", "todo", "to-do",
            "i'll ", "i will ", "can you ", "please ",
        ).any { it in tail }
}

data class CueProposal(
    val cue: ConversateCue,
    val confidence: Float,
    val engagement: Int = 75,
    val cadence: Int = 50,
)
