package com.mymav.miniapps.spotify.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = appContext.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)

    fun getClientId(): String = settings.getString(KEY_CLIENT_ID, DEFAULT_CLIENT_ID).orEmpty()

    fun saveClientId(clientId: String) {
        settings.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
    }

    fun isConnected(): Boolean {
        return settings.getString(KEY_REFRESH_TOKEN, null)?.isNotBlank() == true ||
            settings.getString(KEY_ACCESS_TOKEN, null)?.isNotBlank() == true
    }

    fun statusLabel(): String {
        return when {
            getClientId().isBlank() -> "Spotify: paste Client ID"
            isConnected() -> "Spotify: connected"
            else -> "Spotify: ready to connect"
        }
    }

    fun createAuthorizationIntent(): Intent {
        val clientId = getClientId()
        require(clientId.isNotBlank()) { "Spotify Client ID is required" }

        val verifier = generateCodeVerifier()
        val challenge = codeChallenge(verifier)
        val state = generateCodeVerifier().take(24)
        settings.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_AUTH_STATE, state)
            .apply()

        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()

        return Intent(Intent.ACTION_VIEW, uri)
    }

    suspend fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != REDIRECT_SCHEME || uri.host != REDIRECT_HOST) return false
        val expectedState = settings.getString(KEY_AUTH_STATE, null)
        val returnedState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank() || expectedState.isNullOrBlank() || expectedState != returnedState) {
            return false
        }
        return exchangeCodeForToken(code)
    }

    suspend fun getValidAccessToken(): String? {
        val accessToken = settings.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = settings.getLong(KEY_EXPIRES_AT, 0L)
        if (!accessToken.isNullOrBlank() && System.currentTimeMillis() < expiresAt - REFRESH_SKEW_MS) {
            return accessToken
        }
        return if (refreshAccessToken()) settings.getString(KEY_ACCESS_TOKEN, null) else accessToken
    }

    private suspend fun exchangeCodeForToken(code: String): Boolean {
        val verifier = settings.getString(KEY_CODE_VERIFIER, null) ?: return false
        return requestToken(
            form = mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to getClientId(),
                "code_verifier" to verifier,
            )
        )
    }

    private suspend fun refreshAccessToken(): Boolean {
        val refreshToken = settings.getString(KEY_REFRESH_TOKEN, null) ?: return false
        return requestToken(
            form = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to getClientId(),
            )
        )
    }

    private suspend fun requestToken(form: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            doOutput = true
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) return@withContext false
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val accessToken = json.optString("access_token")
            if (accessToken.isBlank()) return@withContext false
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: settings.getString(KEY_REFRESH_TOKEN, null)
            val expiresInSeconds = json.optLong("expires_in", 3_600L)
            settings.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (expiresInSeconds * 1_000L))
                .apply()
            true
        } finally {
            connection.disconnect()
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        const val REDIRECT_SCHEME = "mymav"
        const val REDIRECT_HOST = "spotify-callback"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SETTINGS_NAME = "mymav_notify_settings"
        private const val KEY_CLIENT_ID = "spotify_client_id"
        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "spotify_expires_at"
        private const val KEY_CODE_VERIFIER = "spotify_code_verifier"
        private const val KEY_AUTH_STATE = "spotify_auth_state"
        private const val REFRESH_SKEW_MS = 60_000L
        private const val SCOPES =
            "user-read-currently-playing user-read-playback-state user-modify-playback-state user-read-recently-played user-library-read"
        private const val DEFAULT_CLIENT_ID = "16fe6d4ae5434a86bd7f7aca2ecbeaf7"
    }
}
