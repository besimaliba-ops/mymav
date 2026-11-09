package com.mymav.glasses.api

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device fixtures for Ask/cue/summary/vision/search. Uses bench overrides only —
 * never writes [AiPreset] or saved model fields. Never logs keys.
 */
object AiBench {
    private const val TAG = "AiBench"

    private const val CUE_TRANSCRIPT =
        "Alex: Shall we move the launch to Thursday so marketing can finish the one-pager?\n" +
            "Sam: Thursday is tight. I have the board review at 3. What about Friday morning?\n" +
            "Alex: Friday 10 works if you can send the deck tonight.\n" +
            "Sam: I'll send a draft by 8. Can you check the pricing slide?"

    private const val SUMMARY_TRANSCRIPT =
        "Team standup. Priya: the glasses Ask path still uses the live Current models and we must not change that default. " +
            "Ben: Fast should be the cheap current-catalogue IDs, not GPT-4.1. Maya: vision and search have to go through " +
            "the user's ChatGPT-only or Gemini-only setting, not auto-prefer OpenAI just because a key exists. " +
            "Priya: phone Assistant activity must show the actual provider and model ID. " +
            "Ben: we will benchmark a real Conversate cue, a longer summary, a product-label image, and a current-info " +
            "question with sources before recommending any switch. Maya: do not buy credits. Transcription stays on " +
            "Android SpeechRecognizer. Decision: keep Current selected; opt-in presets only. Open: confirm which GPT-6 " +
            "and Gemini 3.x IDs this account can actually call."

    private const val SEARCH_Q = "Who is the current Prime Minister of the United Kingdom, and from which party? Name a source."
    private const val ASK_Q = "In two short sentences, why should a glasses HUD keep answers brief?"
    private const val VISION_Q = "Read the product name, flavour, net weight, and best-before date from this label."

    /** [spec] = current | fast | balanced | advanced | all | cue | summary | vision | search | ask */
    fun run(spec: String): String {
        val savedPreset = MymavAi.preset().id
        val savedOpen = MymavAi.savedOpenAiModel()
        val savedGem = MymavAi.savedGeminiModel()
        val listed = MymavAi.listAccountModels()
        val out = StringBuilder()
        out.append("list ").append(listed).append(" | ")
        try {
            val jobs = jobsFor(spec.lowercase(Locale.US).trim(), listed)
            if (jobs.isEmpty()) return out.append("no jobs for spec=$spec").toString()
            for (job in jobs) {
                MymavAi.setBenchOverrides(job.openAi, job.gemini)
                val started = System.currentTimeMillis()
                val row = runCatching { one(job.role) }.getOrElse { err ->
                    Row(job.role, false, 0, 0, "", MymavAi.lastProblem.ifBlank { err.message.orEmpty() }.take(180), 0, false)
                }
                val ms = System.currentTimeMillis() - started
                val used = MymavAi.lastUsedLabel.ifBlank { "Used (none)" }
                val cost = estimateUsd(used, row.inChars, row.outChars, row.searched)
                AssistantLog.add(
                    "[bench ${job.tag}/${job.role.name}] ${row.query}",
                    row.answer.ifBlank { row.error }.ifBlank { "(empty)" },
                    if (row.ok) row.kind else AssistantLog.Kind.Error,
                    row.sources,
                    "$used · ${row.role} ${ms}ms useful=${row.usefulMs}ms complete=${row.completeMs}ms $cost" +
                        if (row.error.isNotBlank()) " · ${row.error}" else "",
                )
                out.append(job.tag).append('/').append(job.role.name).append('=')
                    .append(if (row.ok) "OK" else "FAIL")
                    .append(" used=").append(used.replace(' ', '_'))
                    .append(" useful=").append(row.usefulMs).append("ms")
                    .append(" complete=").append(row.completeMs).append("ms")
                    .append(' ').append(cost)
                    .append(" q=").append(row.quality)
                    .append(" | ")
                Log.i(TAG, "${job.tag}/${job.role.name} ok=${row.ok} $used ${row.completeMs}ms $cost ${row.quality}")
            }
        } finally {
            MymavAi.clearBenchOverrides()
        }
        val presetNow = MymavAi.preset().id
        val openNow = MymavAi.savedOpenAiModel()
        val gemNow = MymavAi.savedGeminiModel()
        val preserved = presetNow == savedPreset && openNow == savedOpen && gemNow == savedGem
        out.append("presetPreserved=").append(preserved)
            .append(" preset=").append(presetNow)
            .append(" savedOpenAi=").append(openNow)
            .append(" savedGemini=").append(gemNow)
        return out.toString()
    }

    private data class Job(val tag: String, val role: AiRole, val openAi: String?, val gemini: String?)
    private data class Row(
        val role: AiRole,
        val ok: Boolean,
        val usefulMs: Long,
        val completeMs: Long,
        val answer: String,
        val error: String,
        val inChars: Int,
        val searched: Boolean,
        val outChars: Int = 0,
        val quality: String = "",
        val query: String = "",
        val kind: AssistantLog.Kind = AssistantLog.Kind.Ai,
        val sources: List<AssistantLog.Source> = emptyList(),
    )

    private fun jobsFor(spec: String, listed: String): List<Job> {
        val openai = listed.substringAfter("openaiAccount=", "").substringBefore(" openaiTotal").split(',').filter { it.isNotBlank() && it != "(none of catalogue)" }.toSet()
        val gemini = listed.substringAfter("geminiAccount=", "").substringBefore(" geminiTotal").split(',').filter { it.isNotBlank() && it != "(none of catalogue)" }.toSet()
        val required = listOf(AiRole.Cue, AiRole.Summary, AiRole.Vision, AiRole.Search)
        fun pair(tag: String, o: String?, g: String?, roles: List<AiRole> = required): List<Job> {
            val haveO = o == null || o in openai
            val haveG = g == null || g in gemini
            if (tag != "current" && !haveO && !haveG) return emptyList()
            return roles.map { Job(tag, it, o, g) }
        }
        return when (spec) {
            "cue" -> listOf(Job("current", AiRole.Cue, null, null))
            "summary" -> listOf(Job("current", AiRole.Summary, null, null))
            "vision" -> listOf(Job("current", AiRole.Vision, null, null))
            "search" -> listOf(Job("current", AiRole.Search, null, null))
            "ask" -> listOf(Job("current", AiRole.Ask, null, null))
            "fast" -> pair("fast", "gpt-6-luna", "gemini-3.5-flash-lite")
            "balanced" -> pair("balanced", "gpt-6.1-sol", "gemini-3.8-flash")
            "advanced" -> pair("advanced", "gpt-6.1-sol", "gemini-4-argon")
            "all" -> pair("current", null, null, listOf(AiRole.Ask) + required) +
                pair("fast", "gpt-6-luna", "gemini-3.5-flash-lite", listOf(AiRole.Cue, AiRole.Search)) +
                pair("balanced", "gpt-6.1-sol", "gemini-3.8-flash", listOf(AiRole.Summary, AiRole.Vision)) +
                pair("advanced", "gpt-6-astra", "gemini-4-argon", listOf(AiRole.Summary))
            else -> pair("current", null, null, listOf(AiRole.Ask) + required)
        }
    }

    private fun one(role: AiRole): Row = when (role) {
        AiRole.Ask -> askRow()
        AiRole.Cue -> cueRow()
        AiRole.Summary -> summaryRow()
        AiRole.Vision -> visionRow()
        AiRole.Search -> searchRow()
    }

    private fun askRow(): Row {
        val t0 = System.currentTimeMillis()
        val (text, err) = generateBlocking(
            "You are MyMav on a small HUD. Plain text, no markdown, at most 40 words.",
            ASK_Q, 80, AiRole.Ask,
        )
        val ms = System.currentTimeMillis() - t0
        val ok = !text.isNullOrBlank() && text.length > 20
        return Row(AiRole.Ask, ok, ms, ms, text.orEmpty(), err, ASK_Q.length, false, text?.length ?: 0, if (ok) "brief-text" else "empty", ASK_Q)
    }

    private fun cueRow(): Row {
        val system = "You are a discreet in-conversation copilot. Output ONE cue. " +
            "JSON only: {\"type\":\"suggestion|concept|bio|answer\",\"headline\":\"...\",\"detail\":\"...\",\"confidence\":0.0}"
        val t0 = System.currentTimeMillis()
        val (text, err) = generateBlocking(system, CUE_TRANSCRIPT, 200, AiRole.Cue)
        val ms = System.currentTimeMillis() - t0
        val json = extractJson(text)
        val parsed = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        val headline = parsed?.optString("headline").orEmpty()
        val ok = headline.isNotBlank() && headline.length <= 80
        val quality = if (ok) "json-cue headline=${headline.take(40)}" else "not-json"
        return Row(AiRole.Cue, ok, ms, ms, text.orEmpty(), err, CUE_TRANSCRIPT.length, false, text?.length ?: 0, quality, "Conversate cue fixture")
    }

    private fun summaryRow(): Row {
        val system = "Summarize this meeting for smart glasses. JSON only: " +
            "{\"summary\":\"3 sentences\",\"keyPoints\":[\"...\"],\"decisions\":[\"...\"],\"unresolved\":[\"...\"]}"
        val t0 = System.currentTimeMillis()
        val (text, err) = generateBlocking(system, SUMMARY_TRANSCRIPT, 450, AiRole.Summary)
        val ms = System.currentTimeMillis() - t0
        val parsed = extractJson(text)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val summary = parsed?.optString("summary").orEmpty()
        val points = parsed?.optJSONArray("keyPoints")?.length() ?: 0
        val ok = summary.length > 40 && points > 0
        val quality = if (ok) "json-summary ${summary.split(' ').size}w points=$points" else "not-json"
        return Row(AiRole.Summary, ok, ms, ms, text.orEmpty(), err, SUMMARY_TRANSCRIPT.length, false, text?.length ?: 0, quality, "Conversate session summary fixture")
    }

    private fun visionRow(): Row {
        val frame = MymavVision.Frame(productLabelJpeg(), "Test input · product-label fixture")
        val t0 = System.currentTimeMillis()
        val r = MymavVision.describe(VISION_Q, frame)
        val ms = System.currentTimeMillis() - t0
        val text = r.text.lowercase(Locale.US)
        val hits = listOf("nature valley", "oats", "honey", "210", "2027").count { text.contains(it) }
        val ok = r.kind != AssistantLog.Kind.Error && hits >= 2
        val quality = if (ok) "label-hits=$hits" else "vision-miss hits=$hits"
        return Row(AiRole.Vision, ok, ms, ms, r.text, if (ok) "" else r.text.take(180), 640 * 400, false, r.text.length, quality, VISION_Q, r.kind, r.sources)
    }

    private fun searchRow(): Row {
        val t0 = System.currentTimeMillis()
        val r = MymavSearch.grounded(SEARCH_Q, emptyList())
        val ms = System.currentTimeMillis() - t0
        val searched = r.kind == AssistantLog.Kind.Web && r.sources.isNotEmpty()
        val text = r.text.lowercase(Locale.US)
        val names = listOf("starmer", "sunak", "truss", "johnson", "prime minister", "labour", "conservative")
        val hit = names.any { text.contains(it) }
        val ok = searched && hit
        val quality = if (ok) "grounded sources=${r.sources.size}" else "search-weak kind=${r.kind} sources=${r.sources.size}"
        return Row(AiRole.Search, ok, ms, ms, r.text, if (ok) "" else r.text.take(180), SEARCH_Q.length, searched, r.text.length, quality, SEARCH_Q, r.kind, r.sources)
    }

    private fun generateBlocking(system: String, user: String, maxTokens: Int, role: AiRole): Pair<String?, String> {
        val latch = CountDownLatch(1)
        var result: String? = null
        MymavAi.generate(system, user, maxTokens, maxTokens * 4, role) {
            result = it
            latch.countDown()
        }
        if (!latch.await(75, TimeUnit.SECONDS)) return null to "timeout 75s"
        return result to if (result.isNullOrBlank()) MymavAi.lastProblem else ""
    }

    private fun extractJson(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun productLabelJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1B, 0x5E, 0x20); textSize = 42f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
        c.drawText("NATURE VALLEY", 36f, 80f, title)
        c.drawText("Oats & Honey granola bars", 36f, 140f, body)
        c.drawText("Net wt 210g", 36f, 200f, body)
        c.drawText("Best before 18 OCT 2027", 36f, 260f, body)
        c.drawText("UK 1234 5678", 36f, 320f, body)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return out.toByteArray()
    }

    private fun estimateUsd(used: String, inChars: Int, outChars: Int, searched: Boolean): String {
        val model = used.substringAfterLast(' ').lowercase(Locale.US)
        val inTok = inChars / 4.0
        val outTok = outChars / 4.0
        val (pin, pout) = when {
            model.contains("astra") -> 10.0 to 50.0
            model.contains("6.1-sol") || model == "gpt-6-sol" -> 2.0 to 10.0
            model.contains("luna") -> 0.10 to 0.50
            model.contains("4o-mini") -> 0.15 to 0.60
            model.contains("4.1") && model.contains("mini") -> 0.40 to 1.60
            model.contains("4.1") -> 2.0 to 8.0
            model.contains("argon") -> 2.0 to 10.0
            model.contains("3.8-flash") -> 0.75 to 3.75
            model.contains("3.5-flash-lite") -> 0.30 to 2.50
            model.contains("2.5-flash-lite") -> 0.10 to 0.40
            model.contains("flash") -> 0.30 to 2.50
            else -> 0.15 to 0.60
        }
        var usd = inTok / 1_000_000.0 * pin + outTok / 1_000_000.0 * pout
        if (searched && used.contains("ChatGPT")) usd += 0.01
        if (searched && used.contains("Gemini")) usd += 0.0 // 5k Gemini Search/mo free, then $0.014/call
        return String.format(Locale.US, "~$%.4f", usd)
    }
}
