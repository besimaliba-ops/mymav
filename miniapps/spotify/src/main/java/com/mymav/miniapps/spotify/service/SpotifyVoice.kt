package com.mymav.miniapps.spotify.service

import android.content.Context
import com.mymav.glasses.api.MymavActions
import com.mymav.miniapps.spotify.api.SpotifyAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Voice control of the user's Spotify account through the existing Web API login. Every outcome is the real
 * HTTP result: success is only reported after Spotify accepted the command, and account limits (Premium required,
 * no active device, reconnect needed) are explained instead of hidden.
 */
class SpotifyVoice(context: Context) : MymavActions.SpotifyBridge {
    private val auth = SpotifyAuthManager(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastKey = ""
    private var lastAt = 0L

    override fun connected(): Boolean = auth.isConnected()

    override fun run(cmd: String, query: String, done: (MymavActions.SpotifyOutcome) -> Unit) {
        val key = "$cmd|$query"
        val now = System.currentTimeMillis()
        synchronized(this) {
            // A retry or reconnect replaying the same request must not act twice.
            if (key == lastKey && now - lastAt < 2_500L && cmd != "current") {
                done(MymavActions.SpotifyOutcome(true, "Already done."))
                return
            }
            lastKey = key; lastAt = now
        }
        scope.launch {
            val out = runCatching { execute(cmd, query) }
                .getOrElse { MymavActions.SpotifyOutcome(false, "Spotify didn't respond (${it.javaClass.simpleName}). Nothing was changed.") }
            done(out)
        }
    }

    private class Http(val code: Int, val body: String)

    private suspend fun call(method: String, url: String, body: String? = null): Http? {
        val token = auth.getValidAccessToken()?.takeIf { it.isNotBlank() } ?: return null
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            else if (method == "PUT" || method == "POST") { doOutput = true; setFixedLengthStreamingMode(0) }
        }
        return try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Http(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun failure(h: Http?, what: String): MymavActions.SpotifyOutcome {
        if (h == null) return MymavActions.SpotifyOutcome(false, "Spotify isn't signed in. Open Apps > Spotify in MyMav and connect it.")
        val reason = runCatching { JSONObject(h.body).optJSONObject("error")?.optString("reason").orEmpty() }.getOrDefault("")
        val text = when {
            h.code == 401 -> "Spotify needs reconnecting. Open Apps > Spotify in MyMav and connect again."
            h.code == 403 && reason == "PREMIUM_REQUIRED" -> "Spotify only lets apps control playback on Premium accounts, so I can't $what."
            h.code == 403 -> "Spotify refused this ($reason). The account or device may not allow it. Reconnecting Spotify in MyMav may help."
            h.code == 404 -> "No active Spotify device. Start playing once in the Spotify app on your phone or speaker, then ask again."
            h.code == 429 -> "Spotify is rate-limiting requests. Try again in a minute."
            else -> "Spotify couldn't $what (HTTP ${h.code})."
        }
        return MymavActions.SpotifyOutcome(false, text)
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    private suspend fun execute(cmd: String, query: String): MymavActions.SpotifyOutcome {
        val api = "https://api.spotify.com/v1"
        return when (cmd) {
            "current" -> {
                val h = call("GET", "$api/me/player/currently-playing")
                when {
                    h == null -> failure(null, "check what's playing")
                    h.code == 204 || h.body.isBlank() -> MymavActions.SpotifyOutcome(true, "Nothing is playing on Spotify right now.")
                    h.code != 200 -> failure(h, "check what's playing")
                    else -> {
                        val o = JSONObject(h.body)
                        val item = o.optJSONObject("item")
                        if (item == null) MymavActions.SpotifyOutcome(true, "Spotify is open but no track is playing.")
                        else {
                            val artists = item.optJSONArray("artists") ?: JSONArray()
                            val names = (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") }
                            val state = if (o.optBoolean("is_playing")) "Playing" else "Paused"
                            MymavActions.SpotifyOutcome(true, "$state on Spotify: ${item.optString("name")} by $names.")
                        }
                    }
                }
            }
            "pause" -> simple(call("PUT", "$api/me/player/pause"), "pause", "Paused Spotify.")
            "resume", "play" -> simple(call("PUT", "$api/me/player/play"), "resume", "Resumed Spotify.")
            "next" -> simple(call("POST", "$api/me/player/next"), "skip", "Skipped to the next track.")
            "previous" -> simple(call("POST", "$api/me/player/previous"), "go back", "Went back a track.")
            "search" -> {
                val h = call("GET", "$api/search?q=${enc(query)}&type=track&limit=1")
                if (h == null || h.code != 200) return failure(h, "search Spotify")
                val item = JSONObject(h.body).optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
                    ?: return MymavActions.SpotifyOutcome(false, "Spotify found no track for \"$query\".")
                val artists = item.optJSONArray("artists") ?: JSONArray()
                val names = (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") }
                val play = call("PUT", "$api/me/player/play", JSONObject().put("uris", JSONArray().put(item.optString("uri"))).toString())
                if (play != null && play.code in 200..299) MymavActions.SpotifyOutcome(true, "Playing ${item.optString("name")} by $names.")
                else failure(play, "play that")
            }
            "mood" -> {
                val h = call("GET", "$api/search?q=${enc(query)}&type=playlist&limit=10")
                if (h == null || h.code != 200) return failure(h, "search Spotify")
                // Spotify's playlist search can contain null entries; use the first real playlist.
                val list = JSONObject(h.body).optJSONObject("playlists")?.optJSONArray("items")
                val pl = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.firstOrNull()
                if (pl != null) {
                    val play = call("PUT", "$api/me/player/play", JSONObject().put("context_uri", pl.optString("uri")).toString())
                    return if (play != null && play.code in 200..299)
                        MymavActions.SpotifyOutcome(true, "Playing the Spotify playlist \"${pl.optString("name")}\" (searched for \"$query\"; not personalised).")
                    else failure(play, "play that")
                }
                // No playlist came back: fall back to tracks matching the same words.
                val t = call("GET", "$api/search?q=${enc(query)}&type=track&limit=10")
                if (t == null || t.code != 200) return failure(t, "search Spotify")
                val tl = JSONObject(t.body).optJSONObject("tracks")?.optJSONArray("items")
                val uris = JSONArray()
                for (k in 0 until (tl?.length() ?: 0)) tl!!.optJSONObject(k)?.optString("uri")?.takeIf { it.isNotBlank() }?.let { uris.put(it) }
                if (uris.length() == 0) return MymavActions.SpotifyOutcome(false, "Spotify found nothing for \"$query\".")
                val play = call("PUT", "$api/me/player/play", JSONObject().put("uris", uris).toString())
                if (play != null && play.code in 200..299)
                    MymavActions.SpotifyOutcome(true, "Playing ${uris.length()} Spotify tracks matching \"$query\" (no playlist found; not personalised).")
                else failure(play, "play that")
            }
            else -> MymavActions.SpotifyOutcome(false, "I don't know that Spotify command.")
        }
    }

    private fun simple(h: Http?, what: String, ok: String): MymavActions.SpotifyOutcome =
        if (h != null && h.code in 200..299) MymavActions.SpotifyOutcome(true, ok) else failure(h, what)
}
