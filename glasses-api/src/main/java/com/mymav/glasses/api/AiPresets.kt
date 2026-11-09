package com.mymav.glasses.api

/** Which job a call is doing. Presets may use different IDs per role. */
enum class AiRole { Ask, Cue, Summary, Vision, Search }

/**
 * Named model presets. [Current] is the saved-ID fallback (`gpt-4o-mini` ·
 * `gemini-flash-latest` unless the user edited those fields). Fast is the
 * everyday trial for Ask and Conversate cues. Balanced / Advanced stay
 * available for harder questions. GPT-4.1 and Gemini 2.5 are not the top tier.
 *
 * Official catalogues (2026-10-18):
 * OpenAI flagship: gpt-6-luna (efficient), gpt-6.1-sol (balanced), gpt-6-astra (demanding).
 * Gemini current: gemini-3.5-flash-lite, gemini-3.8-flash, gemini-flash-latest, gemini-4-argon.
 */
enum class AiPreset(
    val id: String,
    val label: String,
    val summary: String,
    val productClass: ProductClass,
) {
    Current(
        id = "current",
        label = "Current (fallback)",
        summary = "Saved Ask models. Switch back here any time — these IDs stay put when you try Fast.",
        productClass = ProductClass.Flash,
    ),
    Fast(
        id = "fast",
        label = "Fast (trial)",
        summary = "Everyday trial for quick Ask and Conversate cues.",
        productClass = ProductClass.Flash,
    ),
    Balanced(
        id = "balanced",
        label = "Balanced",
        summary = "Demanding questions, summaries, vision, and search.",
        productClass = ProductClass.Flash,
    ),
    Advanced(
        id = "advanced",
        label = "Advanced",
        summary = "Hard questions and long summaries. Does not fall back to a cheaper class.",
        productClass = ProductClass.Pro,
    ),
    ;

    enum class ProductClass { Flash, Pro }

    fun openAiId(role: AiRole): String? = when (this) {
        Current -> null
        Fast -> "gpt-6-luna"
        Balanced -> "gpt-6.1-sol"
        Advanced -> if (role == AiRole.Summary) "gpt-6-astra" else "gpt-6.1-sol"
    }

    fun geminiId(role: AiRole): String? = when (this) {
        Current -> null
        Fast -> "gemini-3.5-flash-lite"
        Balanced -> "gemini-3.8-flash"
        Advanced -> "gemini-4-argon"
    }

    fun idsLine(savedOpenAi: String, savedGemini: String): String {
        if (this == Current) return "ChatGPT $savedOpenAi · Gemini $savedGemini"
        val oAsk = openAiId(AiRole.Ask)!!
        val gAsk = geminiId(AiRole.Ask)!!
        val oSum = openAiId(AiRole.Summary)!!
        val gSum = geminiId(AiRole.Summary)!!
        return if (oAsk == oSum && gAsk == gSum) {
            "ChatGPT $oAsk · Gemini $gAsk"
        } else {
            "Ask/cues/vision/search ChatGPT $oAsk · Gemini $gAsk. Summaries ChatGPT $oSum · Gemini $gSum"
        }
    }

    companion object {
        val CATALOGUE_OPENAI = listOf(
            "gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol",
            "gpt-4o-mini", "gpt-4o", "gpt-4.1", "gpt-4.1-mini",
        )
        val CATALOGUE_GEMINI = listOf(
            "gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-pro-preview",
            "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest",
            "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
        )

        /** Unset prefs start on Fast (everyday trial). Saved `current` stays the fallback. */
        fun fromId(raw: String?): AiPreset =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: Fast

        private val ADVANCED_REQUEST = Regex(
            """^(?i)(?:please )?(?:use |with )?(?:the )?(?:advanced|stronger)(?: model)?(?:[:.\-]| for)?\s+""",
        )

        fun stripAdvancedRequest(question: String): Pair<String, Boolean> {
            val trimmed = question.trim()
            val m = ADVANCED_REQUEST.find(trimmed) ?: return question to false
            val rest = trimmed.substring(m.range.last + 1).trim()
            return (rest.ifBlank { question }) to true
        }
    }
}
