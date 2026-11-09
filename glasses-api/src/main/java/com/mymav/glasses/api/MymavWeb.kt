package com.mymav.glasses.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless online lookups for MyMav AI (blocking — call off the main thread):
 *  - pictures via Wikipedia page thumbnails
 *  - recipes via TheMealDB (name, ingredients, method, photo)
 */
object MymavWeb {
    /** A downloaded picture. [credit]/[license] come from Wikimedia Commons metadata; [pageUrl] is the article, [fileUrl] the Commons file page. */
    class Img(
        val bytes: ByteArray,
        val caption: String,
        val credit: String = "",
        val license: String = "",
        val pageUrl: String = "",
        val fileUrl: String = "",
        val title: String = "",
    )

    /** Outcome of a picture search: what was found, and how many candidates could not be downloaded. */
    class ImageSearch(val images: List<Img>, val candidates: Int, val failedDownloads: Int)

    class Recipe(
        val name: String,
        val meta: String,
        val image: ByteArray?,
        val ingredients: List<String>,
        val steps: String,
    )

    private const val UA = "MyMav/1.0 (Everysight glasses companion)"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, timeoutMs: Int = 10_000): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject = JSONObject(String(get(url), Charsets.UTF_8))

    /** Up to [max] pictures (with a short caption) for [query]. */
    fun searchImages(query: String, max: Int = 5): List<Img> = searchImagesWithSources(query, max).images

    /** Download candidates ([title, extract, thumbUrl, commonsFile, pageUrl]) and attach Commons credit/licence. */
    private fun searchImagesDetailed(cands: List<Array<String>>, max: Int): ImageSearch {
        val meta = commonsMeta(cands.map { it[3] }.filter { it.isNotBlank() })
        val out = ArrayList<Img>()
        var failed = 0
        for (c in cands) {
            if (out.size >= max) break
            val bytes = runCatching { get(c[2], 12_000) }.getOrNull()
            if (bytes == null) { failed++; continue }
            val m = meta[c[3]]
            val ex = c[1].replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            val credit = m?.first.orEmpty()
            val lic = m?.second.orEmpty()
            val cap = buildString {
                append(c[0])
                if (ex.isNotBlank()) append("\n").append(ex.take(100))
                if (credit.isNotBlank() || lic.isNotBlank()) append("\n").append(listOf(credit.take(28), lic).filter { it.isNotBlank() }.joinToString(", "))
            }
            out += Img(bytes, cap, credit, lic, c[4], m?.third.orEmpty(), c[0])
        }
        return ImageSearch(out, cands.size, failed)
    }

    /** Commons file -> (artist, licence, file page url). Missing entries simply have no credit. */
    private fun commonsMeta(files: List<String>): Map<String, Triple<String, String, String>> {
        if (files.isEmpty()) return emptyMap()
        val titles = files.joinToString("|") { "File:" + it.replace('_', ' ') }
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=extmetadata%7Curl&titles=${enc(titles)}"
        val out = HashMap<String, Triple<String, String, String>>()
        runCatching {
            val pages = getJson(url).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching
            for (k in pages.keys()) {
                val p = pages.getJSONObject(k)
                val name = p.optString("title").removePrefix("File:")
                val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val ext = info.optJSONObject("extmetadata")
                val artist = ext?.optJSONObject("Artist")?.optString("value").orEmpty().replace(Regex("<[^>]*>"), "").trim()
                val lic = ext?.optJSONObject("LicenseShortName")?.optString("value").orEmpty().trim()
                val key = files.firstOrNull { it.replace('_', ' ') == name } ?: name
                out[key] = Triple(artist, lic, info.optString("descriptionurl"))
            }
        }
        return out
    }

    /** Picture search with attribution details and download-failure counts, for the assistant. */
    fun searchImagesWithSources(query: String, max: Int = 5): ImageSearch {
        val q = query.trim().replace(Regex("^(an?|the|some)\\s+", RegexOption.IGNORE_CASE), "")
        if (q.isBlank()) return ImageSearch(emptyList(), 0, 0)
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1" +
            "&generator=search&gsrsearch=${enc(q)}&gsrnamespace=0&gsrlimit=8" +
            "&prop=pageimages%7Cextracts%7Cinfo&piprop=thumbnail%7Cname&pithumbsize=640&inprop=url" +
            "&exintro=1&explaintext=1&exsentences=1&exlimit=max"
        val pages = getJson(url).optJSONObject("query")?.optJSONObject("pages") ?: return ImageSearch(emptyList(), 0, 0)
        val cands = ArrayList<Pair<Int, Array<String>>>()
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k)
            val src = p.optJSONObject("thumbnail")?.optString("source").orEmpty()
            if (src.isBlank() || src.lowercase().let { it.endsWith(".svg") || it.contains(".svg/") }) continue
            cands += p.optInt("index", 99) to arrayOf(p.optString("title"), p.optString("extract"), src, p.optString("pageimage"), p.optString("fullurl"))
        }
        return searchImagesDetailed(cands.sortedBy { it.first }.map { it.second }.take(max + 3), max)
    }

    /** Best matching online recipe for a dish / ingredient phrase, or null. */
    fun findRecipe(query: String): Recipe? {
        val q = query.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) return null
        val base = "https://www.themealdb.com/api/json/v1/1"
        fun search(term: String): JSONObject? =
            runCatching { getJson("$base/search.php?s=${enc(term)}").optJSONArray("meals")?.optJSONObject(0) }.getOrNull()

        var meal = search(q)
        val words = q.split(' ').filter { it.length > 2 && it !in STOP }
        if (meal == null) {
            // Try the longest words, then ingredient filters.
            for (w in words.sortedByDescending { it.length }) {
                meal = search(w)
                if (meal != null) break
            }
        }
        if (meal == null) {
            for (w in words.sortedByDescending { it.length }) {
                val first = runCatching {
                    getJson("$base/filter.php?i=${enc(w)}").optJSONArray("meals")?.optJSONObject(0)
                }.getOrNull() ?: continue
                meal = runCatching {
                    getJson("$base/lookup.php?i=${first.optString("idMeal")}").optJSONArray("meals")?.optJSONObject(0)
                }.getOrNull()
                if (meal != null) break
            }
        }
        val m = meal ?: return null
        val ingredients = ArrayList<String>()
        for (i in 1..20) {
            val ing = m.optString("strIngredient$i").trim()
            if (ing.isBlank() || ing == "null") continue
            val meas = m.optString("strMeasure$i").trim().let { if (it == "null") "" else it }
            ingredients += listOf(meas, ing).filter { it.isNotBlank() }.joinToString(" ")
        }
        val thumb = m.optString("strMealThumb").takeIf { it.isNotBlank() && it != "null" }
        val image = thumb?.let { runCatching { get("$it/medium", 12_000) }.getOrNull()
            ?: runCatching { get(it, 12_000) }.getOrNull() }
        val meta = listOf(m.optString("strCategory"), m.optString("strArea"))
            .filter { it.isNotBlank() && it != "null" }.joinToString(" - ")
        val steps = m.optString("strInstructions").replace("\r", "")
            .replace(Regex("(?i)\\bstep\\s*\\d+[:.]?\\s*"), "")
            .replace(Regex("\\n{2,}"), "\n").trim()
        return Recipe(m.optString("strMeal"), meta, image, ingredients, steps)
    }

    private val STOP = setOf(
        "the", "and", "with", "for", "recipe", "recipes", "make", "cook", "how", "some", "dish", "easy", "best",
    )
}
