package com.mymav.miniapps.spotify.api

import com.mymav.glasses.api.SongPickItem
import com.mymav.miniapps.spotify.model.SpotifyPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class SpotifyApiClient(
    private val accessTokenProvider: suspend () -> String?,
) {
    suspend fun getCurrentlyPlaying(): SpotifyPlaybackState? = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val live = fetchCurrentlyPlaying(token)
        if (live != null) return@withContext live
        // Nothing active — still surface last track so album art / search stay useful on glasses.
        fetchRecentlyPlayed(token)
    }

    private fun fetchCurrentlyPlaying(token: String): SpotifyPlaybackState? {
        val connection = (URL(CURRENTLY_PLAYING_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parsePlayback(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NO_CONTENT -> null
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchRecentlyPlayed(token: String): SpotifyPlaybackState? {
        val connection = (URL(RECENTLY_PLAYED_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = root.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val track = items.getJSONObject(0).optJSONObject("track") ?: return null
            parseTrackItem(track, progressMs = 0L, isPlaying = false)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun skipToNextTrack(): Boolean {
        return sendPlayerCommand("POST", "$PLAYER_URL/next")
    }

    suspend fun skipToPreviousTrack(): Boolean {
        return sendPlayerCommand("POST", "$PLAYER_URL/previous")
    }

    suspend fun setPlaybackPlaying(playing: Boolean): Boolean {
        val action = if (playing) "play" else "pause"
        return sendPlayerCommand("PUT", "$PLAYER_URL/$action")
    }

    /** Saved / Liked Songs (requires user-library-read). */
    suspend fun fetchLikedTracks(limit: Int = 20): List<SongPickItem> = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val connection = (URL("$API_ROOT/me/tracks?limit=${limit.coerceIn(1, 50)}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = root.optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until items.length()) {
                    val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                    val id = track.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val artists = track.optJSONArray("artists")
                    val artist = if (artists != null && artists.length() > 0) {
                        artists.optJSONObject(0)?.optString("name").orEmpty()
                    } else {
                        ""
                    }
                    add(
                        SongPickItem(
                            trackId = id,
                            title = track.optString("name", "Unknown"),
                            artist = artist.ifBlank { "Unknown artist" },
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Search tracks; returns best match for playback. */
    suspend fun searchBestTrack(query: String): SpotifyPlaybackState? = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return@withContext null
        val connection = (URL("$API_ROOT/search?q=$q&type=track&limit=5").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext null
            if (items.length() == 0) return@withContext null
            parseTrackItem(items.getJSONObject(0), progressMs = 0L, isPlaying = true)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun playTrack(trackId: String): Boolean = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext false
        val body = JSONObject().put("uris", JSONArray().put("spotify:track:$trackId")).toString()
        val connection = (URL("$PLAYER_URL/play").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sendPlayerCommand(method: String, endpoint: String): Boolean = withContext(Dispatchers.IO) {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext false
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            doOutput = method == "PUT"
        }

        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlayback(json: String): SpotifyPlaybackState? {
        val root = JSONObject(json)
        val item = root.optJSONObject("item") ?: return null
        return parseTrackItem(
            item,
            progressMs = root.optLong("progress_ms", 0L).coerceAtLeast(0L),
            isPlaying = root.optBoolean("is_playing", false),
        )
    }

    private fun parseTrackItem(
        item: JSONObject,
        progressMs: Long,
        isPlaying: Boolean,
    ): SpotifyPlaybackState {
        val artists = item.optJSONArray("artists")
        val artistName = if (artists != null && artists.length() > 0) {
            artists.optJSONObject(0)?.optString("name").orEmpty()
        } else {
            ""
        }
        val album = item.optJSONObject("album")
        val albumImageUrl = pickImageUrl(album?.optJSONArray("images"))
            ?: pickImageUrl(item.optJSONArray("images"))

        return SpotifyPlaybackState(
            trackId = item.optString("id").takeIf { it.isNotBlank() },
            trackName = item.optString("name", "Unknown track"),
            artistName = artistName.ifBlank { "Unknown artist" },
            albumName = album?.optString("name")?.takeIf { it.isNotBlank() },
            albumImageUrl = albumImageUrl,
            progressMs = progressMs,
            isPlaying = isPlaying,
            durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0L),
            previewUrl = item.optString("preview_url").takeIf { it.isNotBlank() },
        )
    }

    /** Prefer ~300px cover for faster download; fall back to largest. */
    private fun pickImageUrl(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null
        var bestUrl: String? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            val url = img.optString("url").takeIf { it.isNotBlank() } ?: continue
            val h = img.optInt("height", 0)
            val score = if (h > 0) abs(h - 300) else 10_000
            if (score < bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private companion object {
        const val API_ROOT = "https://api.spotify.com/v1"
        const val PLAYER_URL = "$API_ROOT/me/player"
        const val CURRENTLY_PLAYING_URL = "$PLAYER_URL/currently-playing"
        const val RECENTLY_PLAYED_URL = "$PLAYER_URL/recently-played?limit=1"
    }
}
