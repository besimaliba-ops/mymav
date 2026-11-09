package com.mymav.glasses.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The user's own ChatGPT (OpenAI) and Gemini (Google) API keys, entered in the
 * phone app's Settings → MyMav AI, power the glasses mascot's answers.
 *
 * Keys live only in this app's private storage and are only ever sent to the
 * provider's own API host. They are never logged.
 */
object MymavAi {
    private const val TAG = "MymavAi"
    private const val PREFS = "mymav_ai"

    enum class Provider(val label: String) {
        Auto("Auto (ChatGPT, then Gemini)"),
        ChatGpt("ChatGPT only"),
        Gemini("Gemini only"),
        Off("Off (built-in answers)"),
    }

    const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    /** "-latest" alias: Google keeps it pointing at a current Flash model, so it doesn't get retired under us. */
    const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    private val RETIRED_GEMINI = setOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-pro")

    private const val SYSTEM_PROMPT =
        "You are MyMav, a friendly assistant living in smart glasses. The reply is shown on a small " +
            "heads-up display as plain text: no markdown symbols, no emoji. Be brief (at most 25 words) for " +
            "simple questions. When the user asks for detail, steps, a list, an explanation, a recipe or a " +
            "comparison, give a complete but compact answer (up to about 120 words), putting each list item " +
            "or step on its own line."

    @Volatile private var appContext: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Pair<String, String>>() // role to text, role = "user" | "assistant"

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(): Provider {
        val raw = prefs()?.getString("provider", null)
        return Provider.entries.firstOrNull { it.name == raw } ?: Provider.Auto
    }
    fun openAiKey(): String = prefs()?.getString("openai_key", "").orEmpty().trim()
    fun geminiKey(): String = prefs()?.getString("gemini_key", "").orEmpty().trim()

    fun preset(): AiPreset = AiPreset.fromId(prefs()?.getString("ai_preset", null))

    /** Saved ChatGPT field used by [AiPreset.Current]. Other presets ignore this at call time. */
    fun savedOpenAiModel(): String =
        prefs()?.getString("openai_model", null)?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_OPENAI_MODEL

    /** Saved Gemini field used by [AiPreset.Current]. Retired IDs map back to the working default. */
    fun savedGeminiModel(): String {
        val saved = prefs()?.getString("gemini_model", null)?.trim()?.removePrefix("models/")
        return if (saved.isNullOrEmpty() || saved in RETIRED_GEMINI) DEFAULT_GEMINI_MODEL else saved
    }

    /** In-memory bench overrides. Never written to prefs; Current stays the saved default. */
    @Volatile private var benchOpenAi: String? = null
    @Volatile private var benchGemini: String? = null

    fun setBenchOverrides(openAi: String?, gemini: String?) {
        benchOpenAi = openAi?.trim()?.takeIf { it.isNotEmpty() }
        benchGemini = gemini?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearBenchOverrides() {
        benchOpenAi = null
        benchGemini = null
    }

    /** Active ChatGPT ID for this [role] (preset, bench override, or Current saved / default). */
    fun openAiModel(role: AiRole = AiRole.Ask, forceAdvanced: Boolean = false): String {
        benchOpenAi?.let { return it }
        if (forceAdvanced) return AiPreset.Advanced.openAiId(role)!!
        return preset().openAiId(role) ?: savedOpenAiModel()
    }

    /** Active Gemini ID for this [role] (preset, bench override, or Current saved / default). */
    fun geminiModel(role: AiRole = AiRole.Ask, forceAdvanced: Boolean = false): String {
        benchGemini?.let { return it }
        if (forceAdvanced) return AiPreset.Advanced.geminiId(role)!!
        return preset().geminiId(role) ?: savedGeminiModel()
    }

    /**
     * Last Ask/generate failure, safe to show on HUD/phone. Never contains a key.
     * Empty after a successful call.
     */
    @Volatile var lastProblem: String = ""
        private set

    /** Extra note when a same-class Flash fallback ran, or this turn used Advanced IDs. */
    @Volatile var lastCallNote: String = ""
        private set

    /** Last successful provider + model ID. Safe for HUD/phone activity. Never a key. */
    @Volatile var lastUsedLabel: String = ""
        private set

    fun usedLabel(provider: Provider, model: String): String = when (provider) {
        Provider.ChatGpt -> "Used ChatGPT $model"
        Provider.Gemini -> "Used Gemini $model"
        else -> "Used $model"
    }

    fun markUsed(provider: Provider, model: String) {
        lastUsedLabel = usedLabel(provider, model)
    }

    private class HttpError(val code: Int, message: String) : IllegalStateException(message)

    fun save(
        provider: Provider = provider(),
        openAiKey: String = openAiKey(),
        geminiKey: String = geminiKey(),
        openAiModel: String = savedOpenAiModel(),
        geminiModel: String = savedGeminiModel(),
        preset: AiPreset = preset(),
    ) {
        prefs()?.edit()
            ?.putString("provider", provider.name)
            ?.putString("openai_key", openAiKey.trim())
            ?.putString("gemini_key", geminiKey.trim())
            ?.putString("openai_model", openAiModel.trim())
            ?.putString("gemini_model", geminiModel.trim())
            ?.putString("ai_preset", preset.id)
            ?.apply()
    }

    fun providerOrder(): List<Provider> = order()

    private fun order(): List<Provider> = when (provider()) {
        Provider.Off -> emptyList()
        Provider.ChatGpt -> listOf(Provider.ChatGpt)
        Provider.Gemini -> listOf(Provider.Gemini)
        Provider.Auto -> listOf(Provider.ChatGpt, Provider.Gemini)
    }.filter { if (it == Provider.ChatGpt) openAiKey().isNotEmpty() else geminiKey().isNotEmpty() }

    fun isConfigured(): Boolean = order().isNotEmpty()

    /** Same-class Flash fallbacks only. Pro / Advanced never silently drop to a cheaper class. */
    fun allowFlashFallback(): Boolean = preset().productClass != AiPreset.ProductClass.Pro

    fun geminiFlashFallbacks(configured: String): LinkedHashSet<String> {
        val set = linkedSetOf(configured)
        if (!allowFlashFallback()) return set
        set += "gemini-3.5-flash-lite"
        set += "gemini-3.8-flash"
        set += DEFAULT_GEMINI_MODEL
        if (preset() == AiPreset.Current) {
            set += "gemini-2.5-flash"
            set += "gemini-2.5-flash-lite"
        }
        return set
    }

    fun openAiFlashFallbacks(configured: String): LinkedHashSet<String> {
        val set = linkedSetOf(configured)
        if (!allowFlashFallback()) return set
        set += "gpt-6-luna"
        set += DEFAULT_OPENAI_MODEL
        return set
    }

    private fun beginCall(note: String) {
        lastProblem = ""
        lastCallNote = note
        lastUsedLabel = ""
    }

    /**
     * Models this key can see, names only. Never logs key values.
     * Marks each catalogue ID as listed-by-provider vs available-to-this-account.
     */
    fun listAccountModels(): String {
        val out = StringBuilder()
        out.append("preset=${preset().id} provider=${provider().name} ")
        out.append("savedOpenAi=${savedOpenAiModel()} savedGemini=${savedGeminiModel()} ")
        out.append("openaiKey=${if (openAiKey().isNotEmpty()) "set" else "missing"} ")
        out.append("geminiKey=${if (geminiKey().isNotEmpty()) "set" else "missing"}")
        if (openAiKey().isNotEmpty()) {
            val listed = runCatching {
                val arr = JSONObject(get("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer ${openAiKey()}"))).optJSONArray("data")
                (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
            }.getOrElse { e ->
                out.append(" | openaiListError=${e.message?.take(80)}")
                emptyList()
            }
            if (listed.isNotEmpty()) {
                val hit = AiPreset.CATALOGUE_OPENAI.filter { id -> listed.any { it == id || it.startsWith("$id-") } }
                out.append(" | openaiAccount=${hit.joinToString(",").ifBlank { "(none of catalogue)" }} openaiTotal=${listed.size}")
            }
        }
        if (geminiKey().isNotEmpty()) {
            val listed = runCatching {
                val arr = JSONObject(get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to geminiKey()))).optJSONArray("models")
                (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).optString("name").removePrefix("models/") }.filter { it.isNotBlank() }
            }.getOrElse { e ->
                out.append(" | geminiListError=${e.message?.take(80)}")
                emptyList()
            }
            if (listed.isNotEmpty()) {
                val hit = AiPreset.CATALOGUE_GEMINI.filter { id -> listed.any { it == id || it.startsWith("$id") } }
                out.append(" | geminiAccount=${hit.joinToString(",").ifBlank { "(none of catalogue)" }} geminiTotal=${listed.size}")
            }
        }
        return out.toString()
    }

    fun clearConversation() = synchronized(history) { history.clear() }

    /** Record a turn answered by another provider (search, conversion...) so follow-up questions have context. */
    fun remember(question: String, answer: String) = synchronized(history) {
        history += "user" to question
        history += "assistant" to answer.take(600)
        while (history.size > 8) history.removeAt(0)
    }

    /** Ask the configured model; [onResult] runs on the main thread (answer, or null if every provider failed). */
    fun ask(question: String, onResult: (String?) -> Unit) {
        val (q, forceAdvanced) = AiPreset.stripAdvancedRequest(question)
        beginCall(if (forceAdvanced) "Advanced IDs for this question" else "")
        val providers = order()
        io.execute {
            var answer: String? = null
            var lastErr = ""
            for (p in providers) {
                answer = runCatching {
                    if (p == Provider.ChatGpt) askOpenAi(q, role = AiRole.Ask, forceAdvanced = forceAdvanced)
                    else askGemini(q, role = AiRole.Ask, forceAdvanced = forceAdvanced)
                }.onFailure {
                    lastErr = "${p.label}: ${it.message?.take(120).orEmpty()}"
                    Log.w(TAG, "${p.name} failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                }.getOrNull()
                if (!answer.isNullOrBlank()) break
            }
            val clean = answer?.let { tidy(it) }
            if (clean.isNullOrBlank()) {
                lastProblem = lastErr.ifBlank { "No ChatGPT or Gemini key answered." }
            } else synchronized(history) {
                history += "user" to q
                history += "assistant" to clean
                while (history.size > 8) history.removeAt(0)
            }
            main.post { onResult(clean?.takeIf { it.isNotBlank() }) }
        }
    }

    /**
     * One-shot call with its own [system] prompt and no conversation memory.
     * [onResult] runs on the main thread; null when no provider answered.
     */
    fun generate(
        system: String,
        user: String,
        maxTokens: Int = 300,
        limit: Int = 1400,
        role: AiRole = AiRole.Ask,
        onResult: (String?) -> Unit,
    ) {
        beginCall("")
        val providers = order()
        io.execute {
            var answer: String? = null
            var lastErr = ""
            for (p in providers) {
                answer = runCatching {
                    if (p == Provider.ChatGpt) askOpenAi(user, useHistory = false, system = system, maxTokens = maxTokens, role = role)
                    else askGemini(user, useHistory = false, system = system, maxTokens = maxTokens, role = role)
                }.onFailure {
                    lastErr = "${p.label}: ${it.message?.take(120).orEmpty()}"
                    Log.w(TAG, "${p.name} failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                }.getOrNull()
                if (!answer.isNullOrBlank()) break
            }
            if (answer.isNullOrBlank()) lastProblem = lastErr.ifBlank { "No ChatGPT or Gemini key answered." }
            val clean = answer?.let { tidy(it, limit) }?.takeIf { it.isNotBlank() }
            main.post { onResult(clean) }
        }
    }

    /** One-off check used by the Settings "Test" button. Returns an error message or null on success. */
    /**
     * Transcribes and summarises one audio recording with the user's Gemini key (the only configured provider that
     * accepts audio here). Blocking: call off the main thread. Returns (text, null) or (null, honest reason).
     */
    fun transcribeAndSummarise(audioBase64: String, mime: String, instructions: String): Pair<String?, String?> {
        val key = geminiKey()
        if (key.isBlank()) return null to "Transcribing needs a Gemini API key. Add one in MyMav Settings."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", audioBase64)))
                .put(JSONObject().put("text", "Transcribe and summarise this recording as instructed.")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 8000))
        return try {
            val models = geminiFlashFallbacks(geminiModel(AiRole.Summary))
            val res = GeminiApi.generate(models, key, body.toString(), 180_000)
            val parts = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val text = (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }.trim()
            if (text.isBlank()) null to "The AI returned nothing for this recording." else text to null
        } catch (e: GeminiApi.Failed) {
            null to (e.problem.userMessage() + if (e.problem.kind == GeminiApi.Kind.Quota || e.problem.kind == GeminiApi.Kind.RateLimit) " The recording is kept; use Retry summary later." else "")
        }
    }

    /** One minimal Gemini call per configured model, reporting exactly what Google answered (no key or prompt content logged). Blocking. */
    fun diagnoseGemini(): String {
        val key = geminiKey()
        if (key.isBlank()) return "No Gemini key saved."
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Say OK.")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 8)).toString()
        val out = StringBuilder()
        for (m in geminiFlashFallbacks(geminiModel())) {
            try {
                MymavHttp.request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", body, mapOf("x-goog-api-key" to key), 30_000)
                out.append("$m: OK. ")
            } catch (f: MymavHttp.Failure) {
                val p = GeminiApi.classify(f, m)
                out.append("$m: ${p.kind} http=${p.http} detail=${p.detail} retryAfter=${p.retryAfterSec}s. ")
            }
        }
        // Which models can this key list, and does Google Search grounding work on any of them?
        val listed = runCatching {
            val arr = JSONObject(MymavHttp.request("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100", null, mapOf("x-goog-api-key" to key), 20_000)).optJSONArray("models")
            (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.filter { m -> (0 until (m.optJSONArray("supportedGenerationMethods")?.length() ?: 0)).any { m.getJSONArray("supportedGenerationMethods").getString(it) == "generateContent" } }
                .map { it.optString("name").removePrefix("models/") }
        }.getOrDefault(emptyList())
        out.append("| models: ${listed.filter { it.contains("flash") }.take(8).joinToString(",")} | grounding: ")
        val gbody = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "What is today's date?")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))).put("generationConfig", JSONObject().put("maxOutputTokens", 30)).toString()
        for (m in (listOf(geminiModel()) + listed.filter { it.contains("flash") }).distinct().take(9)) {
            try {
                MymavHttp.request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", gbody, mapOf("x-goog-api-key" to key), 40_000)
                out.append("$m=OK ")
            } catch (f: MymavHttp.Failure) {
                val p = GeminiApi.classify(f, m)
                out.append("$m=${p.kind}(${p.http}${if (m == listed.firstOrNull { it.contains("flash") } || p.kind == GeminiApi.Kind.Quota) ": " + p.detail.take(230) else ""}) ")
            }
        }
        return out.toString().trim()
    }

    fun test(provider: Provider, onResult: (String?) -> Unit) {
        io.execute {
            val err = runCatching {
                val a = if (provider == Provider.ChatGpt) askOpenAi("Say OK.", useHistory = false)
                else askGemini("Say OK.", useHistory = false)
                if (a.isBlank()) "Empty reply" else null
            }.getOrElse { it.message?.take(140) ?: "Failed" }
            main.post { onResult(err) }
        }
    }

    private fun tidy(s: String, limit: Int = 900): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim().take(limit)

    /** The conversational prompt gets the user's saved memories; one-shot task prompts do not. */
    private fun sys(system: String) = if (system === SYSTEM_PROMPT) system + MymavMemory.promptBlock() else system

    private fun snapshot(): List<Pair<String, String>> = synchronized(history) { history.toList() }

    private fun askOpenAi(
        question: String,
        useHistory: Boolean = true,
        system: String = SYSTEM_PROMPT,
        maxTokens: Int = 380,
        forceAdvanced: Boolean = false,
        role: AiRole = AiRole.Ask,
    ): String {
        val model = openAiModel(role, forceAdvanced)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys(system)))
        if (useHistory) snapshot().forEach { (r, t) -> msgs.put(JSONObject().put("role", r).put("content", t)) }
        msgs.put(JSONObject().put("role", "user").put("content", question))
        val body = JSONObject().put("model", model).put("messages", msgs)
        decorateOpenAiChat(body, model, maxTokens)
        val res = try {
            post(
                "https://api.openai.com/v1/chat/completions", body.toString(),
                mapOf("Authorization" to "Bearer ${openAiKey()}"),
            )
        } catch (e: HttpError) {
            if (!model.startsWith("gpt-6") || e.message.orEmpty().contains("max_tokens", true).not()) throw e
            body.remove("max_tokens")
            body.put("max_completion_tokens", maxTokens)
            post(
                "https://api.openai.com/v1/chat/completions", body.toString(),
                mapOf("Authorization" to "Bearer ${openAiKey()}"),
            )
        }
        markUsed(Provider.ChatGpt, model)
        return JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    /** GPT-6 Chat Completions: Luna/Sol accept reasoning_effort=none; Astra does not. */
    private fun decorateOpenAiChat(body: JSONObject, model: String, maxTokens: Int) {
        if (model.startsWith("gpt-6")) {
            body.put("reasoning_effort", if (model.contains("astra")) "low" else "none")
            body.put("max_completion_tokens", maxTokens)
        } else {
            body.put("max_tokens", maxTokens)
        }
    }

    private fun askGemini(
        question: String,
        useHistory: Boolean = true,
        system: String = SYSTEM_PROMPT,
        maxTokens: Int = 380,
        forceAdvanced: Boolean = false,
        role: AiRole = AiRole.Ask,
    ): String {
        val configured = geminiModel(role, forceAdvanced)
        // Flash-class only: "-latest" aliases can point at slow preview models.
        // Pro / Advanced never falls back to Flash (different product class).
        val allowFlash = !forceAdvanced && allowFlashFallback()
        val candidates = if (allowFlash) geminiFlashFallbacks(configured) else linkedSetOf(configured)
        var last: Exception? = null
        var needDiscovery = false
        for (m in candidates) {
            try {
                val answer = askGeminiWith(m, question, useHistory, system, maxTokens)
                markUsed(Provider.Gemini, m)
                if (m != configured) {
                    lastCallNote = "Used $m after $configured failed (same Flash class)."
                    if (preset() == AiPreset.Current && benchGemini == null) prefs()?.edit()?.putString("gemini_model", m)?.apply()
                }
                return answer
            } catch (e: GeminiTimeout) {
                last = e
            } catch (e: HttpError) {
                val modelProblem = e.code == 404 || (e.code == 400 && Regex("model|no longer|not found|not supported", RegexOption.IGNORE_CASE)
                    .containsMatchIn(e.message.orEmpty()))
                if (!modelProblem) throw e
                needDiscovery = true
                last = e
            }
        }
        if (needDiscovery && allowFlash) {
            val found = discoverGeminiModel()
            if (found != null && found !in candidates) {
                val answer = askGeminiWith(found, question, useHistory, system, maxTokens)
                markUsed(Provider.Gemini, found)
                lastCallNote = "Used $found after $configured failed (same Flash class)."
                if (preset() == AiPreset.Current && benchGemini == null) prefs()?.edit()?.putString("gemini_model", found)?.apply()
                return answer
            }
        }
        throw last ?: IllegalStateException("Gemini failed")
    }

    private class GeminiTimeout(msg: String) : Exception(msg)

    /** Ask Google which models this key can use and choose the newest stable Flash model. */
    private fun discoverGeminiModel(): String? {
        val res = get(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
            mapOf("x-goog-api-key" to geminiKey()),
        )
        val arr = JSONObject(res).optJSONArray("models") ?: return null
        var best: String? = null
        var bestScore = -1.0
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            if ((0 until methods.length()).none { methods.getString(it) == "generateContent" }) continue
            if (!name.startsWith("gemini-") || !name.contains("flash")) continue
            if (Regex("image|tts|audio|live|embed|robotics|computer|thinking|latest").containsMatchIn(name)) continue
            val version = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            var score = version * 100
            if (name.contains("preview") || name.contains("exp")) score -= 30
            if (name.contains("lite")) score -= 20
            if (score > bestScore) { bestScore = score; best = name }
        }
        return best
    }

    private fun askGeminiWith(
        model: String, question: String, useHistory: Boolean, system: String, maxTokens: Int,
    ): String {
        val contents = JSONArray()
        if (useHistory) snapshot().forEach { (r, t) ->
            contents.put(
                JSONObject().put("role", if (r == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", t))),
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys(system)))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens + 100)
                // Thinking models can spend the whole budget (and many seconds) thinking; we want a quick short reply.
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val hdr = mapOf("x-goog-api-key" to geminiKey())
        val res = try {
            post(url, body.toString(), hdr)
        } catch (e: HttpError) {
            if (e.code != 400 || !e.message.orEmpty().contains("think", ignoreCase = true)) throw e
            // This model doesn't accept thinkingConfig — retry plain.
            body.getJSONObject("generationConfig").remove("thinkingConfig")
            post(url, body.toString(), hdr)
        }
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString(" ") { parts.getJSONObject(it).optString("text") }
    }

    private fun get(url: String, headers: Map<String, String>): String = request("GET", url, null, headers)

    private fun post(url: String, body: String, headers: Map<String, String>): String =
        request("POST", url, body, headers)

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = if (url.contains("generateContent")) 20_000 else 45_000
            doOutput = body != null
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw HttpError(code, "HTTP $code ${msg.orEmpty().take(140)}")
            }
            return text
        } catch (e: java.net.SocketTimeoutException) {
            val where = if (e.message.orEmpty().contains("connect", ignoreCase = true)) "connecting to" else "waiting for"
            val m = "Timed out $where ${URL(url).host}"
            if (url.contains("generateContent")) throw GeminiTimeout(m) else throw IllegalStateException(m)
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("Can't reach ${URL(url).host} (no internet / DNS)")
        } finally {
            conn.disconnect()
        }
    }
}
