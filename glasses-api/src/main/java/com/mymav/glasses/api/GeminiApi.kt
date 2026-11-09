package com.mymav.glasses.api

import org.json.JSONObject

/**
 * Calls to Google's Gemini API with honest failure handling. A 429 can mean two very different things: a temporary
 * per-minute rate limit (retrying shortly works) or an exhausted quota / no quota for that model on the key's plan
 * (retrying never works; the account needs a different plan, billing, or model). We tell them apart from the error body.
 */
internal object GeminiApi {
    enum class Kind { RateLimit, Quota, Auth, ModelMissing, Network, Other }
    class Problem(val kind: Kind, val http: Int, val detail: String, val retryAfterSec: Int, val model: String) {
        /** The exact thing the user should do next. */
        fun userMessage(): String = when (kind) {
            Kind.Quota -> "Gemini says this key has no quota left for $model ($detail). Retrying won't help. Fix: in Google AI Studio (aistudio.google.com), open the key's project and enable billing, or use a key from a billed project, or pick another model in MyMav Settings. Free-tier keys have a small daily limit that resets daily."
            Kind.RateLimit -> "Gemini is rate-limiting this key for the moment ($detail). I retried and it is still limited. Wait a minute and ask again."
            Kind.Auth -> "Gemini rejected the key (HTTP $http). Check the key in MyMav Settings."
            Kind.ModelMissing -> "Gemini doesn't know the model \"$model\". Check the model name in MyMav Settings."
            Kind.Network -> detail
            Kind.Other -> "Gemini failed (HTTP $http${if (detail.isNotBlank()) ": $detail" else ""})."
        }
    }

    class Failed(val problem: Problem) : Exception(problem.userMessage())

    /** Last model that returned 2xx. Name only; never a key. */
    @Volatile var lastSucceededModel: String = ""
        private set

    fun classify(f: MymavHttp.Failure, model: String): Problem {
        if (f.code < 0) return Problem(Kind.Network, f.code, f.message.orEmpty(), 0, model)
        var status = ""; var msg = ""; var quotaId = ""; var retry = 0
        runCatching {
            val e = JSONObject(f.body).optJSONObject("error")
            status = e?.optString("status").orEmpty(); msg = e?.optString("message").orEmpty()
            val details = e?.optJSONArray("details")
            for (i in 0 until (details?.length() ?: 0)) {
                val d = details!!.getJSONObject(i)
                val v = d.optJSONArray("violations")
                if (v != null && v.length() > 0) quotaId = v.getJSONObject(0).optString("quotaId").ifBlank { v.getJSONObject(0).optString("quotaMetric") }
                d.optString("retryDelay").takeIf { it.endsWith("s") }?.dropLast(1)?.toDoubleOrNull()?.let { retry = it.toInt() + 1 }
            }
        }
        val short = (quotaId.ifBlank { status }).take(80)
        val google = msg.replace(Regex("\\s+"), " ").take(160)
        return when {
            f.code == 429 -> {
                val perMinute = quotaId.contains("PerMinute", true) || (msg.contains("per minute", true) && !quotaId.contains("PerDay", true))
                val exhausted = quotaId.contains("PerDay", true) || msg.contains("limit: 0", true) || msg.contains("billing", true) ||
                    msg.contains("exceeded your current quota", true) && !perMinute || retry > 90
                Problem(if (exhausted && !perMinute) Kind.Quota else Kind.RateLimit, 429, (short.ifBlank { "HTTP 429" }) + if (google.isNotBlank()) " - Google: $google" else "", retry, model)
            }
            f.code == 401 || f.code == 403 -> Problem(Kind.Auth, f.code, short, 0, model)
            f.code == 404 -> Problem(Kind.ModelMissing, 404, short, 0, model)
            else -> Problem(Kind.Other, f.code, (msg.ifBlank { short }).take(100), 0, model)
        }
    }

    /**
     * POSTs [body] to Gemini trying each of [models] in turn. A temporary rate limit is retried at most twice (waiting
     * what Google asks, capped at 20 s each); a quota problem moves to the next model, since quotas are per model.
     * Returns the raw response, or throws [Failed] with the most useful problem found.
     */
    fun generate(models: Collection<String>, key: String, body: String, timeoutMs: Int): String {
        var best: Problem? = null
        for (m in models) {
            var attempt = 0
            while (true) {
                try {
                    val res = MymavHttp.request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", body, mapOf("x-goog-api-key" to key), timeoutMs)
                    lastSucceededModel = m
                    return res
                } catch (f: MymavHttp.Failure) {
                    val p = classify(f, m)
                    if (best == null || rank(p) > rank(best)) best = p
                    if (p.kind == Kind.RateLimit && attempt < 2 && p.retryAfterSec <= 20) { attempt++; Thread.sleep(((p.retryAfterSec.coerceAtLeast(3)) * 1000L)); continue }
                    if (p.kind == Kind.Auth || p.kind == Kind.Network) throw Failed(p)
                    break  // quota / rate limit that persists / missing model: try the next model
                }
            }
        }
        throw Failed(best ?: Problem(Kind.Other, 0, "", 0, models.firstOrNull().orEmpty()))
    }

    private fun rank(p: Problem) = when (p.kind) { Kind.Quota -> 3; Kind.RateLimit -> 2; Kind.Other -> 1; else -> 0 }
}
