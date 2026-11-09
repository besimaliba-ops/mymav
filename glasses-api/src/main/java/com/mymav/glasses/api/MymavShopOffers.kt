package com.mymav.glasses.api

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Live retailer offers via SerpAPI Google Shopping (ported from MaverickShopAI backend).
 *
 * **Never** treats LLM-remembered prices as live quotes. Without a SerpAPI key in
 * Settings, [isConfigured] is false and callers must state the exact setup needed.
 * Keys stay on-device and are never logged.
 */
object MymavShopOffers {
    private const val TAG = "MymavShopOffers"
    private const val PREFS = "mymav_ai"
    private const val KEY = "serpapi_key"

    data class Offer(
        val title: String,
        val retailer: String,
        val price: Double,
        val currency: String,
        val url: String,
        val checkedAtMs: Long,
        val delivery: String = "",
        val matchKind: MatchKind = MatchKind.Possible,
        val source: String = "serpapi",
        /** Why this listing may not be the scanned item, e.g. "listed as 500g" (blank when it matches). */
        val matchNote: String = "",
    ) {
        fun priceLabel(): String {
            val symbol = when (currency.uppercase(Locale.UK)) {
                "GBP" -> "£"
                "EUR" -> "€"
                "USD" -> "$"
                else -> "$currency "
            }
            val amount = if (price == price.toLong().toDouble()) {
                price.toLong().toString()
            } else {
                String.format(Locale.UK, "%.2f", price)
            }
            return "$symbol$amount"
        }

        fun checkedLabel(): String =
            SimpleDateFormat("HH:mm", Locale.UK).format(Date(checkedAtMs))
    }

    enum class MatchKind { Exact, Possible }

    data class LookupResult(
        val offers: List<Offer>,
        val searchQuery: String,
        val providerReady: Boolean,
        val setupNeeded: String,
        val disclaimer: String,
        val error: String = "",
    )

    @Volatile private var appContext: Context? = null
    private val lastOffers = AtomicReference<List<Offer>>(emptyList())
    private val lastQuery = AtomicReference("")

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serpApiKey(): String = prefs()?.getString(KEY, "").orEmpty().trim()

    fun saveSerpApiKey(key: String) {
        prefs()?.edit()?.putString(KEY, key.trim())?.apply()
    }

    fun isConfigured(): Boolean = serpApiKey().isNotEmpty()

    /** Debug-only: the next lookup fails as if the network dropped (verifies Retry prices). */
    @Volatile var debugFailNext = false

    fun setupNeededMessage(): String =
        "Add a SerpAPI key in Settings → MyMav AI (Shopping prices) for live Google Shopping offers. " +
            "Create a key at serpapi.com — never paste keys in chat."

    fun lastOffers(): List<Offer> = lastOffers.get()
    fun lastQuery(): String = lastQuery.get()

    /**
     * Blocking SerpAPI Google Shopping lookup. Call off the main thread.
     * Empty offers with [LookupResult.providerReady]=false means setup required;
     * empty with providerReady=true means no verified prices found.
     */
    fun lookup(
        searchQuery: String,
        preferExact: Boolean = true,
        brand: String = "",
        variant: String = "",
        size: String = "",
        barcode: String = "",
    ): LookupResult {
        val q = buildQuery(searchQuery, brand, variant, size, barcode)
        lastQuery.set(q)
        if (!isConfigured()) {
            lastOffers.set(emptyList())
            return LookupResult(
                offers = emptyList(),
                searchQuery = q,
                providerReady = false,
                setupNeeded = setupNeededMessage(),
                disclaimer = setupNeededMessage(),
                error = "SerpAPI not connected",
            )
        }
        if (q.isBlank()) {
            return LookupResult(
                offers = emptyList(),
                searchQuery = q,
                providerReady = true,
                setupNeeded = "",
                disclaimer = "Need a clearer product name, label, or barcode before lookup.",
                error = "Empty search query",
            )
        }
        return try {
            if (debugFailNext) {
                debugFailNext = false
                throw java.io.IOException("debug: simulated network failure")
            }
            val url = buildString {
                append("https://serpapi.com/search.json?")
                append("engine=google_shopping")
                append("&q=${java.net.URLEncoder.encode(q, Charsets.UTF_8.name())}")
                append("&gl=uk&google_domain=google.co.uk&hl=en&sort_by=1")
                append("&api_key=${java.net.URLEncoder.encode(serpApiKey(), Charsets.UTF_8.name())}")
            }
            val body = MymavHttp.request("GET", url, null, emptyMap(), timeoutMs = 25_000)
            val json = JSONObject(body)
            val arr = json.optJSONArray("shopping_results")
            val checked = System.currentTimeMillis()
            val offers = mutableListOf<Offer>()
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 10)) {
                    val item = arr.getJSONObject(i)
                    val price = item.optDouble("extracted_price", Double.NaN).takeIf { !it.isNaN() }
                        ?: parsePrice(item.optString("price"))
                    if (price == null || price <= 0.0) continue
                    val title = item.optString("title", q).ifBlank { q }
                    val retailer = item.optString("source", "Store").ifBlank { "Store" }
                    val link = item.optString("link").ifBlank {
                        "https://www.google.co.uk/search?tbm=shop&q=${java.net.URLEncoder.encode(q, Charsets.UTF_8.name())}"
                    }
                    val (kind, note) = classifyMatch(title, brand, variant, size, preferExact)
                    offers += Offer(
                        title = title.take(120),
                        retailer = retailer.take(48),
                        price = price,
                        currency = guessCurrency(item.optString("price")),
                        url = link,
                        checkedAtMs = checked,
                        delivery = item.optString("delivery", "").trim().take(40),
                        matchKind = kind,
                        source = "serpapi",
                        matchNote = note,
                    )
                }
            }
            val ranked = offers.sortedWith(
                compareBy<Offer> { if (it.matchKind == MatchKind.Exact) 0 else 1 }
                    .thenBy { it.price },
            )
            lastOffers.set(ranked)
            LookupResult(
                offers = ranked,
                searchQuery = q,
                providerReady = true,
                setupNeeded = "",
                disclaimer = if (ranked.isEmpty()) {
                    "No verified prices found for \"$q\"."
                } else {
                    "Live Google Shopping via SerpAPI · checked ${SimpleDateFormat("HH:mm", Locale.UK).format(Date(checked))}"
                },
            )
        } catch (e: Exception) {
            val http = e as? MymavHttp.Failure
            val serpError = http?.body?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }.orEmpty()
            Log.w(TAG, "SerpAPI lookup failed: ${e.javaClass.simpleName} code=${http?.code} error=${serpError.take(120)}")
            lastOffers.set(emptyList())
            val reason = when {
                http?.code == 401 || serpError.contains("api key", true) -> "SerpAPI key was rejected"
                http?.code == 429 || serpError.contains("run out", true) -> "SerpAPI search quota is used up"
                http?.code == -1 -> "The price service timed out"
                http != null && http.code < 0 -> "No connection to the price service"
                else -> "The price service didn't answer"
            }
            LookupResult(
                offers = emptyList(),
                searchQuery = q,
                providerReady = true,
                setupNeeded = "",
                disclaimer = reason,
                error = e.message?.take(80) ?: e.javaClass.simpleName,
            )
        }
    }

    /**
     * Vision-read barcodes are often a digit off, and Google Shopping rarely matches bare GTINs, so the
     * text identity wins; digits are only the query when nothing else was read.
     */
    private fun buildQuery(
        searchQuery: String,
        brand: String,
        variant: String,
        size: String,
        barcode: String,
    ): String {
        val base = searchQuery.trim()
        val seen = base.lowercase(Locale.UK).replace(" ", "")
        val extra = listOf(brand, variant, size)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase(Locale.UK).replace(" ", "") !in seen }
            .distinct()
        val q = (listOf(base) + extra).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (q.isNotBlank()) return q
        val digits = barcode.filter { it.isDigit() }
        return if (digits.length >= 8) digits else ""
    }

    private val sizeRx = Regex("""(\d+(?:[.,]\d+)?)\s?(ml|l|g|kg|oz|cl)\b""", RegexOption.IGNORE_CASE)

    private fun normSize(raw: String): String? =
        sizeRx.find(raw)?.let { m -> m.groupValues[1].replace(',', '.').trimEnd('0').trimEnd('.') + m.groupValues[2].lowercase(Locale.UK) }

    private fun classifyMatch(
        title: String,
        brand: String,
        variant: String,
        size: String,
        preferExact: Boolean,
    ): Pair<MatchKind, String> {
        val t = title.lowercase(Locale.UK)
        val brandOk = brand.isBlank() || brand.lowercase(Locale.UK) in t
        val variantOk = variant.isBlank() || variant.lowercase(Locale.UK).split(Regex("\\s+"))
            .filter { it.length >= 3 }.all { it in t }
        val want = normSize(size)
        val listed = normSize(title)
        val note = when {
            want != null && listed != null && want != listed -> "listed as ${sizeRx.find(title)!!.value}"
            !brandOk -> "different brand?"
            !variantOk -> "variant unconfirmed"
            want != null && listed == null -> "size not listed"
            else -> ""
        }
        val exact = preferExact && note.isEmpty()
        return (if (exact) MatchKind.Exact else MatchKind.Possible) to note
    }

    private fun parsePrice(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""[\d]+(?:[.,]\d+)?""").find(raw.replace(",", "")) ?: return null
        return m.value.toDoubleOrNull()
    }

    private fun guessCurrency(price: String?): String = when {
        price == null -> "GBP"
        price.contains("£") -> "GBP"
        price.contains("€") -> "EUR"
        price.contains("$") -> "USD"
        else -> "GBP"
    }
}
