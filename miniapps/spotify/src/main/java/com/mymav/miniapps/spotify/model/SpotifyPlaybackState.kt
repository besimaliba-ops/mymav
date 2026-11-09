package com.mymav.miniapps.spotify.model

data class SpotifyPlaybackState(
    val trackId: String?,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val albumImageUrl: String?,
    val progressMs: Long,
    val isPlaying: Boolean,
    val durationMs: Long = 0L,
    /** Spotify 30s audio preview URL when present (not a video). */
    val previewUrl: String? = null,
    val sampledAtMillis: Long = System.currentTimeMillis(),
)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

data class EncodedSpotifyArt(
    /** JPEG or PNG file bytes (not raw RGBA — Maverick SDK 0.2.0 rejects raw). */
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val fromVideoPreview: Boolean = false,
)
