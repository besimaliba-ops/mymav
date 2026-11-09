package com.mymav.miniapps.spotify.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fetches synced / plain lyrics from LRCLIB (https://lrclib.net). */
class LrclibClient {
    suspend fun fetchLrc(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        val durationSec = (durationMs / 1000L).toInt().coerceIn(0, 3600)
        exactGet(trackName, artistName, albumName, durationSec)
            ?: searchFallback(trackName, artistName, durationSec)
    }

    private fun exactGet(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSec: Int,
    ): String? {
        val q = buildString {
            append("track_name=").append(trackName.urlEncode())
            append("&artist_name=").append(artistName.urlEncode())
            if (!albumName.isNullOrBlank()) {
                append("&album_name=").append(albumName.urlEncode())
            }
            if (durationSec > 0) {
                append("&duration=").append(durationSec)
            }
        }
        return parseLyricsBody(httpGet("$GET_URL?$q"))
    }

    private fun searchFallback(trackName: String, artistName: String, durationSec: Int): String? {
        val q = "q=${"$trackName $artistName".urlEncode()}"
        val body = httpGet("$SEARCH_URL?$q") ?: return null
        return runCatching {
            val arr = JSONArray(body)
            var best: JSONObject? = null
            var bestDelta = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optBoolean("instrumental", false)) continue
                val hasSynced = !item.optString("syncedLyrics").isNullOrBlank()
                val hasPlain = !item.optString("plainLyrics").isNullOrBlank()
                if (!hasSynced && !hasPlain) continue
                val dur = item.optInt("duration", 0)
                val delta = if (durationSec > 0 && dur > 0) kotlin.math.abs(dur - durationSec) else 50
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = item
                }
            }
            best?.let { lyricsFromJson(it) }
        }.getOrNull()
    }

    private fun parseLyricsBody(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching { lyricsFromJson(JSONObject(body)) }.getOrNull()
    }

    private fun lyricsFromJson(json: JSONObject): String? {
        if (json.optBoolean("instrumental", false)) {
            return INSTRUMENTAL_LRC
        }
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
        if (synced != null) return synced
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() } ?: return null
        return plainToTimedLrc(plain, json.optInt("duration", 0) * 1000L)
    }

    /** Spread unsynced lines across the track duration so the HUD still advances. */
    fun plainToTimedLrc(plain: String, durationMs: Long): String {
        val lines = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return ""
        val total = durationMs.coerceAtLeast(lines.size * 2_000L)
        val step = (total / lines.size).coerceAtLeast(1_500L)
        return lines.mapIndexed { index, text ->
            val ms = index * step
            val m = ms / 60_000
            val s = (ms % 60_000) / 1_000
            val cs = (ms % 1_000) / 10
            "[%02d:%02d.%02d] %s".format(m, s, cs, text)
        }.joinToString("\n")
    }

    private fun httpGet(url: String): String? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                when (connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> {
                        Log.i(TAG, "lrclib HTTP ${connection.responseCode}")
                        null
                    }
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "lrclib failed: ${it.message}") }.getOrNull()
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val TAG = "MyMavLrclib"
        private const val GET_URL = "https://lrclib.net/api/get"
        private const val SEARCH_URL = "https://lrclib.net/api/search"
        private const val USER_AGENT = "MyMav/1.0 (https://github.com/mymav)"
        val INSTRUMENTAL_LRC = """
            [00:00.00] Instrumental
            [00:04.00] No lyrics for this track
        """.trimIndent()
        val WAITING_LRC = """
            [00:00.00] Looking up lyrics…
        """.trimIndent()
        val MISSING_LRC = """
            [00:00.00] No synced lyrics found
            [00:05.00] Play continues on Spotify
        """.trimIndent()
    }
}
