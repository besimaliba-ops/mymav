package com.mymav.miniapps.spotify.api

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.mymav.miniapps.spotify.model.EncodedSpotifyArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads album cover RGBA, and when Spotify Canvas / video preview is available,
 * extracts a still frame for the HUD thumbnail.
 */
class SpotifyArtworkLoader(
    private val accessTokenProvider: suspend () -> String?,
) {
    private var cachedTrackId: String? = null
    private var cachedArt: EncodedSpotifyArt? = null

    suspend fun loadVisual(
        trackId: String?,
        albumImageUrl: String?,
        sizePx: Int = COVER_SIZE,
    ): EncodedSpotifyArt? = withContext(Dispatchers.IO) {
        if (trackId != null && trackId == cachedTrackId && cachedArt != null) {
            return@withContext cachedArt
        }
        val video = trackId?.takeIf { it != "demo" }?.let { fetchCanvasFrame(it, sizePx) }
        val art = video
            ?: albumImageUrl?.let { downloadImage(it, sizePx, fromVideoPreview = false) }
            ?: HudRgbaEncoder.demoPlaceholder(sizePx)
        cachedTrackId = trackId
        cachedArt = art
        art
    }

    private fun downloadImage(url: String, sizePx: Int, fromVideoPreview: Boolean): EncodedSpotifyArt? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                HudRgbaEncoder.fromBitmap(bitmap, sizePx, sizePx, fromVideoPreview).also {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "image download failed: ${it.message}") }.getOrNull()
    }

    /**
     * Best-effort Spotify Canvas (looping vertical video). Not part of the public Web API;
     * falls back quietly when unavailable.
     */
    private suspend fun fetchCanvasFrame(trackId: String, sizePx: Int): EncodedSpotifyArt? {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() } ?: return null
        val canvasUrl = resolveCanvasUrl(token, trackId) ?: return null
        return extractVideoFrame(canvasUrl, sizePx)
    }

    private fun resolveCanvasUrl(token: String, trackId: String): String? {
        return runCatching {
            val body = JSONObject()
                .put(
                    "entities",
                    JSONArray().put(
                        JSONObject().put("entity_uri", "spotify:track:$trackId"),
                    ),
                )
                .toString()
            val connection = (URL(CANVAS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 6_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) return null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val canvases = json.optJSONArray("canvases") ?: return null
                if (canvases.length() == 0) return null
                val first = canvases.optJSONObject(0) ?: return null
                first.optString("canvas_url").takeIf { it.isNotBlank() }
                    ?: first.optString("url").takeIf { it.isNotBlank() }
                    ?: first.optString("canvasUrl").takeIf { it.isNotBlank() }
                    ?: first.optJSONObject("canvas")?.optString("url")?.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.i(TAG, "canvas lookup skipped: ${it.message}") }.getOrNull()
    }

    private fun extractVideoFrame(videoUrl: String, sizePx: Int): EncodedSpotifyArt? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoUrl, hashMapOf("User-Agent" to USER_AGENT))
                // Try several timestamps — Canvas loops vary; first readable frame wins.
                val candidates = longArrayOf(0L, 250_000L, 500_000L, 1_000_000L, 2_000_000L)
                var frame: android.graphics.Bitmap? = null
                for (ts in candidates) {
                    frame = retriever.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) break
                }
                frame = frame ?: retriever.frameAtTime
                val bitmap = frame ?: return null
                HudRgbaEncoder.fromBitmap(bitmap, sizePx, sizePx, fromVideoPreview = true).also {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure { Log.w(TAG, "video frame failed: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "MyMavSpotifyArt"
        /**
         * Highest practical encode for home full-bleed + lyrics cover on 640×400.
         * Home tiles re-crop to ~300×300; 88px was visibly pixelated.
         */
        private const val COVER_SIZE = 300
        private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
        private const val USER_AGENT = "MyMav/1.0 (Android)"
    }
}
