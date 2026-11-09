package com.mymav.miniapps.conversate

enum class MeetingMode(val label: String, val promptHint: String) {
    General("General", "balanced professional copilot"),
    Interview("Interview", "strong questions and active listening"),
    Sales("Sales", "discovery, objections, closing"),
    Social("Social", "rapport and gentle follow-ups"),
    Presentation(
        "Presentation",
        "capture claims and Q&A ideas; minimize interruptions",
    ),
}

enum class ConversateCueType(val label: String, val icon: String) {
    Suggestion("Suggest", "◆"),
    Concept("Concept", "?"),
    Bio("Bio", "◎"),
    Answer("Answer", "→"),
    ;
    companion object {
        fun fromModelTag(tag: String): ConversateCueType = when (tag.lowercase().trim()) {
            "concept", "concepts" -> Concept
            "bio", "bios" -> Bio
            "answer", "answers" -> Answer
            else -> Suggestion
        }
    }
}

data class ConversateCue(
    val type: ConversateCueType,
    val headline: String,
    val detail: String,
)

/** Rolling in-session summary — not a full-screen replace of the live caption. */
data class RollingSummary(
    val keyPoints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val unresolved: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    fun isBlank(): Boolean =
        keyPoints.isEmpty() && decisions.isEmpty() && unresolved.isEmpty() && actions.isEmpty()

    fun oneLine(): String = buildList {
        decisions.firstOrNull()?.let { add("Decision: $it") }
        actions.firstOrNull()?.let { add("Action: $it") }
        unresolved.firstOrNull()?.let { add("Open: $it") }
        keyPoints.firstOrNull()?.let { add(it) }
    }.firstOrNull()?.take(72).orEmpty()
}

data class ConversateTimings(
    val lastTranscriptionDelayMs: Long = 0L,
    val lastCueDelayMs: Long = 0L,
    val avgTranscriptionDelayMs: Long = 0L,
    val avgCueDelayMs: Long = 0L,
    val transcriptionSamples: Int = 0,
    val cueSamples: Int = 0,
)

data class ConversateSessionState(
    val sessionId: String = "",
    val listening: Boolean = false,
    val statusLine: String = "Idle",
    val liveTranscript: String = "",
    val committedTranscript: String = "",
    val livePartial: String = "",
    /** True while [livePartial] is provisional STT (visually distinct from final). */
    val captionProvisional: Boolean = false,
    val activeCue: ConversateCue? = null,
    val cueHistory: List<ConversateCue> = emptyList(),
    val rollingSummary: RollingSummary = RollingSummary(),
    val showRollingSummary: Boolean = false,
    val prepNotes: String = "",
    val meetingMode: String = MeetingMode.General.label,
    val micSource: String = "Phone",
    val engagement: Int = 75,
    val cadence: Int = 50,
    val summary: String = "",
    val lastError: String = "",
    val timings: ConversateTimings = ConversateTimings(),
    /** After Stop — offer Notepad save; never silent task creation. */
    val pendingNotepadOffer: Boolean = false,
    val notepadSaved: Boolean = false,
    /** Readable HUD footer focus (Summary · Save · Stop). */
    val footerFocus: Int = 0,
    /** Caption / summary paging — preserved across caption updates while reading. */
    val captionPage: Int = 0,
    val captionPageCount: Int = 1,
    val summaryPage: Int = 0,
    val summaryPageCount: Int = 1,
    /** Brief confirm after mid-session Save (cleared on next action). */
    val saveConfirm: String = "",
    /** Mic start requested from the HUD, not yet listening. */
    val starting: Boolean = false,
    /** Stop pressed — finalising summary; actions disabled until done. */
    val stopping: Boolean = false,
    /** Notepad note this session saves into (repeated saves update it). */
    val noteId: String = "",
    /** Saved-summaries browser on the glasses. */
    val showSaved: Boolean = false,
    /** The end-of-session summary (not just a mid-session snapshot) is in Notepad. */
    val finalSaved: Boolean = false,
    val savedPage: Int = 0,
)

data class ConversatePrivacySettings(
    val aiCuesEnabled: Boolean = true,
    val liveTranscriptionEnabled: Boolean = true,
    val cloudAiEnabled: Boolean = true,
    val storeSessionsOnDevice: Boolean = true,
)

data class SessionStartConfig(
    val glassesMic: Boolean = false,
    val privacy: ConversatePrivacySettings = ConversatePrivacySettings(),
    val meetingMode: MeetingMode = MeetingMode.General,
    val prepNotes: String = "",
    val briefingMaterials: String = "",
)

data class ConversateSessionRecord(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val prepNotes: String,
    val fullTranscript: String,
    val summary: String,
    val rollingSummary: RollingSummary = RollingSummary(),
    val cues: List<ConversateCue>,
    val micSource: String,
    val timings: ConversateTimings = ConversateTimings(),
)
