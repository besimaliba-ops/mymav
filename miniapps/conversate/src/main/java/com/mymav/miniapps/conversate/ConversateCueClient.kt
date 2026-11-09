package com.mymav.miniapps.conversate

import android.util.Log
import com.mymav.glasses.api.AiRole
import com.mymav.glasses.api.MymavAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Cloud cues / session summary using MyMav Settings keys. Respects ChatGPT-only / Gemini-only / Auto.
 */
class ConversateCueClient {

    data class SessionSummaryResult(
        val summary: String,
        val keyPoints: List<String>,
        val actionItems: List<String>,
        val decisions: List<String> = emptyList(),
        val unresolved: List<String> = emptyList(),
    )

    suspend fun generateSummary(
        transcript: String,
        briefingContext: String,
        cues: List<ConversateCue>,
    ): SessionSummaryResult? = withContext(Dispatchers.IO) {
        if (transcript.length < 20) return@withContext null
        val cueLines = cues.takeLast(20).joinToString("\n") { "- [${it.type.label}] ${it.headline}" }
        val system = """
            Summarize a completed conversation for the user. Be concise.
            Respond JSON only: {"summary":"2-4 sentences","keyPoints":["..."],"decisions":["..."],"unresolved":["..."],"actionItems":["..."]}
            Max 5 each for keyPoints, decisions, unresolved, actionItems.
        """.trimIndent()
        val user = buildString {
            appendLine("Briefing context:")
            appendLine(if (briefingContext.isBlank()) "(none)" else briefingContext.take(2500))
            appendLine("Cues shown: $cueLines")
            appendLine("Transcript:")
            appendLine(transcript.takeLast(6000))
        }
        val content = chatJson(system, user, maxTokens = 450, role = AiRole.Summary) ?: return@withContext null
        return@withContext runCatching {
            val parsed = JSONObject(content)
            SessionSummaryResult(
                summary = parsed.optString("summary", "Session complete."),
                keyPoints = parsed.optJSONArray("keyPoints").toStringList().take(5),
                actionItems = parsed.optJSONArray("actionItems").toStringList().take(5),
                decisions = parsed.optJSONArray("decisions").toStringList().take(5),
                unresolved = parsed.optJSONArray("unresolved").toStringList().take(5),
            )
        }.getOrNull()
    }

    /** Live rolling summary while listening — key points, decisions, open questions, actions. */
    suspend fun generateRollingSummary(
        transcript: String,
        briefingContext: String,
        previous: RollingSummary,
    ): RollingSummary? = withContext(Dispatchers.IO) {
        if (transcript.length < 60) return@withContext null
        val system = """
            Update a rolling conversation brief for smart glasses. Be terse.
            JSON only: {"keyPoints":["..."],"decisions":["..."],"unresolved":["..."],"actions":["..."]}
            Max 4 items each. Prefer new decisions and actions over restating chatter.
        """.trimIndent()
        val user = buildString {
            appendLine("Prior brief:")
            appendLine("points=${previous.keyPoints}; decisions=${previous.decisions}; open=${previous.unresolved}; actions=${previous.actions}")
            appendLine("Briefing: ${briefingContext.take(800).ifBlank { "(none)" }}")
            appendLine("Transcript tail:")
            appendLine(transcript.takeLast(2800))
        }
        val content = chatJson(system, user, maxTokens = 280, role = AiRole.Summary) ?: return@withContext null
        return@withContext runCatching {
            val parsed = JSONObject(content)
            RollingSummary(
                keyPoints = parsed.optJSONArray("keyPoints").toStringList().take(4),
                decisions = parsed.optJSONArray("decisions").toStringList().take(4),
                unresolved = parsed.optJSONArray("unresolved").toStringList().take(4),
                actions = parsed.optJSONArray("actions").toStringList().take(4),
                updatedAtMs = System.currentTimeMillis(),
            )
        }.getOrNull()
    }

    suspend fun generateCue(
        transcript: String,
        briefingContext: String,
        recentCues: List<ConversateCue>,
        meetingMode: MeetingMode,
    ): CueProposal? = withContext(Dispatchers.IO) {
        if (transcript.length < 12) return@withContext null
        val recentSummary = recentCues.takeLast(4).joinToString("\n") {
            "${it.type.name}: ${it.headline}"
        }
        val system = """
            You are a discreet in-conversation copilot (like Even Realities Conversate).
            Meeting mode: ${meetingMode.label} — ${meetingMode.promptHint}
            Output ONE helpful cue only when something new and useful appears in the transcript.
            Cue types: suggestion, concept, bio, answer.
            Keep headline under 8 words, detail under 25 words. Be factual.
            Use the briefing context for bios, facts, and references when relevant.
            Set confidence 0.0-1.0 (only >= 0.7 if genuinely useful right now).
            Also estimate live conversation metrics as integers 0-100:
            engagement = how attentive/interested the room seems;
            cadence = speech pace (50 = ideal; lower = too slow, higher = rushed).
            Respond JSON only: {"type":"suggestion|concept|bio|answer","headline":"...","detail":"...","confidence":0.0,"engagement":0,"cadence":0}
        """.trimIndent()
        val user = buildString {
            appendLine("Briefing index:")
            appendLine(if (briefingContext.isBlank()) "(none)" else briefingContext.take(2800))
            appendLine()
            appendLine("Recent cues:")
            appendLine(if (recentSummary.isBlank()) "(none)" else recentSummary)
            appendLine()
            appendLine("Transcript tail:")
            appendLine(transcript.takeLast(2500))
        }
        val content = chatJson(system, user, maxTokens = 200, role = AiRole.Cue) ?: return@withContext null
        return@withContext runCatching {
            val parsed = JSONObject(content)
            CueProposal(
                cue = ConversateCue(
                    type = ConversateCueType.fromModelTag(parsed.optString("type", "suggestion")),
                    headline = parsed.optString("headline", "Consider").take(48),
                    detail = parsed.optString("detail", "").take(120),
                ),
                confidence = parsed.optDouble("confidence", 0.75).toFloat().coerceIn(0f, 1f),
                engagement = parsed.optInt("engagement", 75).coerceIn(0, 100),
                cadence = parsed.optInt("cadence", 50).coerceIn(0, 100),
            )
        }.onFailure { Log.w(TAG, "cue parse failed: ${it.message}") }.getOrNull()
    }

    private suspend fun chatJson(system: String, user: String, maxTokens: Int, role: AiRole): String? {
        if (!MymavAi.isConfigured()) return null
        for (p in MymavAi.providerOrder()) {
            val raw = when (p) {
                MymavAi.Provider.ChatGpt -> openAiChatJson(MymavAi.openAiKey(), system, user, maxTokens, role)
                MymavAi.Provider.Gemini -> generateOnce(system, user, maxTokens, role)
                else -> null
            }
            val json = extractJsonObject(raw)
            if (json != null) return json
        }
        return null
    }

    private suspend fun generateOnce(system: String, user: String, maxTokens: Int, role: AiRole): String? =
        suspendCancellableCoroutine { cont ->
            MymavAi.generate(system, user, maxTokens, maxTokens * 4, role) { text ->
                if (cont.isActive) cont.resume(text)
            }
        }

    private fun openAiChatJson(apiKey: String, system: String, user: String, maxTokens: Int, role: AiRole): String? {
        return try {
            val model = MymavAi.openAiModel(role).ifBlank { MymavAi.DEFAULT_OPENAI_MODEL }
            val body = JSONObject().apply {
                put("model", model)
                if (!model.startsWith("gpt-6")) put("temperature", 0.4)
                if (model.startsWith("gpt-6")) {
                    put("reasoning_effort", if (model.contains("astra")) "low" else "none")
                    put("max_completion_tokens", maxTokens)
                } else {
                    put("max_tokens", maxTokens)
                }
                put("response_format", JSONObject().put("type", "json_object"))
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        put(JSONObject().put("role", "user").put("content", user))
                    },
                )
            }
            val conn = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 45_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "OpenAI HTTP $code")
                return null
            }
            MymavAi.markUsed(MymavAi.Provider.ChatGpt, model)
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI cue failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun extractJsonObject(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val s = optString(i).trim()
                if (s.isNotEmpty()) add(s)
            }
        }
    }

    companion object {
        private const val TAG = "ConversateCueClient"
    }
}
