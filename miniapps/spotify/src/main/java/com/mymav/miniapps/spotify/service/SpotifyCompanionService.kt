package com.mymav.miniapps.spotify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GazeDwellController
import com.mymav.glasses.api.HomeWidgetPreviewStore
import com.mymav.glasses.api.LyricsControlState
import com.mymav.glasses.api.LyricsHudActions
import com.mymav.glasses.api.SongListHudActions
import com.mymav.glasses.api.SongPickItem
import com.mymav.miniapps.spotify.SpotifySessionBridge
import com.mymav.miniapps.spotify.api.ActiveLyricWindow
import com.mymav.miniapps.spotify.api.HudRgbaEncoder
import com.mymav.miniapps.spotify.api.LrclibClient
import com.mymav.miniapps.spotify.api.LyricSyncEngine
import com.mymav.miniapps.spotify.api.SpotifyApiClient
import com.mymav.miniapps.spotify.api.SpotifyArtworkLoader
import com.mymav.miniapps.spotify.api.SpotifyAuthManager
import com.mymav.miniapps.spotify.model.EncodedSpotifyArt
import com.mymav.miniapps.spotify.model.SpotifyPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground companion for the Spotify mini-app.
 * Pushes lyrics / now-playing HUDs through [SpotifySessionBridge].
 */
class SpotifyCompanionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spotifyAuth by lazy { SpotifyAuthManager(applicationContext) }
    private val spotifyClient by lazy { SpotifyApiClient { spotifyAuth.getValidAccessToken() } }
    private val artworkLoader by lazy { SpotifyArtworkLoader { spotifyAuth.getValidAccessToken() } }
    private val lrclibClient by lazy { LrclibClient() }

    private var spotifyPollingJob: Job? = null
    private var trackEnrichJob: Job? = null
    private var lastSpotifyTrackId: String? = null
    private var latestPlaybackState: SpotifyPlaybackState? = null
    private var latestArt: EncodedSpotifyArt? = null
    private var activeLyricWindow = ActiveLyricWindow(current = null, next = null, progressMs = 0L)

    private val lyricSyncEngine = LyricSyncEngine(serviceScope) { window ->
        activeLyricWindow = window
        showLyricsHud()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting MyMav Spotify…"))
        lyricSyncEngine.loadLrc(LrclibClient.WAITING_LRC)
        startSpotifyLoop()
        // Do not steal the home board — lyrics only draw when Spotify is the active glasses app.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_LYRICS -> {
                GlassesHomeController.markMiniAppOpen("spotify")
                showLyricsHud(force = true)
            }
            ACTION_SEARCH_SONG -> openSongSearchKeyboard()
            ACTION_LIKED_SONGS -> openLikedSongs()
            ACTION_STOP -> stopSelf()
            else -> startSpotifyLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        spotifyPollingJob?.cancel()
        trackEnrichJob?.cancel()
        lyricSyncEngine.stop()
        serviceScope.cancel()
        SpotifySessionBridge.clearHud()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSpotifyLoop() {
        if (spotifyPollingJob?.isActive == true) return
        lyricSyncEngine.start()
        publishState("loading")
        spotifyPollingJob = serviceScope.launch {
            while (isActive) {
                val playback = runCatching { spotifyClient.getCurrentlyPlaying() }.getOrNull()
                if (playback != null) {
                    val trackChanged = playback.trackId != null && playback.trackId != lastSpotifyTrackId
                    if (trackChanged) {
                        lastSpotifyTrackId = playback.trackId
                        onTrackChanged(playback)
                    }
                    latestPlaybackState = playback
                    lastPlaybackAtMs = System.currentTimeMillis()
                    // Progress polls must not force a full HUD rebuild (cover flicker).
                    lyricSyncEngine.updatePlayback(playback, force = false)
                    publishHomePreview(playback, latestArt)
                    updateNotification("${playback.trackName} — ${playback.artistName}")
                } else if (spotifyAuth.isConnected() && latestPlaybackState == null) {
                    // Signed in but Spotify returned nothing at all (no history or the API is failing).
                    publishState("unavailable")
                } else if (spotifyAuth.isConnected()) {
                    // Signed in; a failed poll after earlier success keeps the last state unless it is stale.
                    if (System.currentTimeMillis() - lastPlaybackAtMs > STALE_MS) publishState("unavailable")
                } else if (latestPlaybackState == null || latestPlaybackState?.trackId == "demo") {
                    val demo = SpotifyPlaybackState(
                        trackId = "demo",
                        trackName = "MyMav Spotify Demo",
                        artistName = "Everysight",
                        albumName = "Simulator",
                        albumImageUrl = null,
                        progressMs = (System.currentTimeMillis() % 25_000L),
                        isPlaying = true,
                        durationMs = 25_000L,
                    )
                    lastSpotifyTrackId = demo.trackId
                    latestPlaybackState = demo
                    lyricSyncEngine.loadLrc(LyricSyncEngine.MOCK_LRC)
                    lyricSyncEngine.updatePlayback(demo)
                    // Placeholder JPEG cover for demo (no Spotify image URL).
                    latestArt = HudRgbaEncoder.demoPlaceholder()
                    // Simulator demo track feeds the lyrics screen only; the home tile reports the real state.
                    publishState("disconnected")
                    // Preview only on home — never auto-open lyrics from gaze/poll.
                    showLyricsHud()
                }
                delay(SPOTIFY_POLL_MS)
            }
        }
    }

    private fun onTrackChanged(playback: SpotifyPlaybackState) {
        latestArt = null
        lyricSyncEngine.loadLrc(LrclibClient.WAITING_LRC)
        lyricSyncEngine.updatePlayback(playback)
        showLyricsHud()
        trackEnrichJob?.cancel()
        trackEnrichJob = serviceScope.launch {
            val art = runCatching {
                artworkLoader.loadVisual(playback.trackId, playback.albumImageUrl)
            }.getOrNull()
            if (art != null && playback.trackId == lastSpotifyTrackId) {
                latestArt = art
                Log.i(TAG, "Art ready video=${art.fromVideoPreview} ${art.width}x${art.height}")
                publishHomePreview(playback, art)
                showLyricsHud(force = true)
            }

            val lrc = runCatching {
                lrclibClient.fetchLrc(
                    trackName = playback.trackName,
                    artistName = playback.artistName,
                    albumName = playback.albumName,
                    durationMs = playback.durationMs,
                )
            }.getOrNull()
            if (playback.trackId != lastSpotifyTrackId) return@launch
            if (!lrc.isNullOrBlank()) {
                lyricSyncEngine.loadLrc(lrc)
                Log.i(TAG, "Lyrics loaded for ${playback.trackName}")
            } else {
                lyricSyncEngine.loadLrc(LrclibClient.MISSING_LRC)
                Log.i(TAG, "No lyrics for ${playback.trackName}")
            }
            latestPlaybackState?.let { lyricSyncEngine.updatePlayback(it, force = true) }
            showLyricsHud(force = true)
        }
    }

    private var lastHomePreviewKey: String? = null
    private var lastPlaybackAtMs = 0L
    private var lastPublishedState: String? = null

    /** Report a non-track Spotify state (loading / disconnected / unavailable) to the home tile and lyrics screen. */
    private fun publishState(state: String) {
        LyricsControlState.setStatus(state)
        if (state == lastPublishedState) return
        lastPublishedState = state
        lastHomePreviewKey = null
        HomeWidgetPreviewStore.put("spotify", HomeWidgetPreviewStore.Preview(title = "Spotify", subtitle = "", state = state))
        if (GlassesHomeController.isShowingHome()) GlassesHomeController.showHome()
    }

    private fun publishHomePreview(playback: SpotifyPlaybackState, art: EncodedSpotifyArt?) {
        val state = if (playback.isPlaying) "playing" else "paused"
        val key = "${playback.trackId}|${playback.trackName}|${art?.width}|${art?.fromVideoPreview}|$state"
        val changed = key != lastHomePreviewKey
        lastHomePreviewKey = key
        lastPublishedState = state
        HomeWidgetPreviewStore.put(
            "spotify",
            HomeWidgetPreviewStore.Preview(
                title = "Spotify",
                subtitle = playback.trackName.take(28),
                metric = if (art?.fromVideoPreview == true) "VID" else "♪",
                metricDetail = playback.artistName.take(16),
                imageBytes = art?.bytes,
                imageWidth = art?.width ?: 0,
                imageHeight = art?.height ?: 0,
                isVideo = art?.fromVideoPreview == true,
                state = state,
            ),
        )
        LyricsControlState.setStatus(state)
        // Only rebuild home when cover/title actually changed — poll ticks caused flicker.
        if (changed && GlassesHomeController.isShowingHome()) {
            GlassesHomeController.showHome()
        }
    }

    private fun showLyricsHud(force: Boolean = false) {
        if (!force && !GlassesHomeController.acceptsSpotifyLyricsHud()) {
            return
        }
        if (force && !GlassesHomeController.acceptsSpotifyLyricsHud()) {
            // Explicit Show lyrics / open path already marks mini-app; still require app surface.
            if (!GlassesHomeController.isShowingApp() ||
                GlassesHomeController.activeMiniAppId() != "spotify"
            ) {
                return
            }
        }
        val playback = latestPlaybackState
        val current = activeLyricWindow.current?.text ?: "Waiting for lyrics"
        val next = activeLyricWindow.next?.text ?: ""
        val art = latestArt
        val hud = GlassesHud.Lyrics(
            trackName = playback?.trackName ?: "Waiting for track",
            artistName = playback?.artistName ?: "Spotify",
            albumName = playback?.albumName,
            currentLyric = current,
            nextLyric = next,
            progressMs = activeLyricWindow.progressMs,
            isPlaying = playback?.isPlaying != false,
            albumArtBytes = art?.bytes,
            albumArtWidth = art?.width ?: 0,
            albumArtHeight = art?.height ?: 0,
            artIsVideoPreview = art?.fromVideoPreview == true,
            actions = LyricsHudActions(
                onControl = { id -> dwell.runOnce(id) },
                onPreviousTrack = { dwell.runOnce("prev") },
                onTogglePlayback = { dwell.runOnce("play") },
                onNextTrack = { dwell.runOnce("next") },
                onSearchRequested = { dwell.runOnce("search") },
                onLikedSongsRequested = { dwell.runOnce("liked") },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y ->
                    feedLyricsControlGaze(x, y)
                    GlassesHomeController.feedAppHomeGaze(x, y)
                },
            ),
        )
        // Real gaze samples reach the app surface through the controller; hook the dwell controls to them.
        GlassesHomeController.appGazeListener = { x, y -> feedLyricsControlGaze(x, y) }
        val session = SpotifySessionBridge.session()
        if (session != null && session.patchLyricsHud(hud)) {
            Log.i(TAG, "Lyrics patched: ${playback?.trackName} | $current")
            return
        }
        SpotifySessionBridge.replaceHud(hud)
        Log.i(TAG, "Lyrics HUD: ${playback?.trackName} | $current")
    }

    private fun openSongSearchKeyboard() {
        GlassesHomeController.markMiniAppOpen("spotify")
        GlassesHomeController.openKeyboard(
            title = "Search Spotify",
            initial = "",
            searchSubmitsBuffer = true,
            onDone = { query ->
                val q = query.trim()
                if (q.isBlank()) {
                    updateNotification("Search cancelled")
                    showLyricsHud(force = true)
                    return@openKeyboard
                }
                updateNotification("Searching: $q")
                serviceScope.launch {
                    val hit = runCatching { spotifyClient.searchBestTrack(q) }.getOrNull()
                    if (hit?.trackId == null) {
                        updateNotification("No Spotify match for “$q”")
                        showLyricsHud(force = true)
                        return@launch
                    }
                    val started = runCatching { spotifyClient.playTrack(hit.trackId) }.getOrDefault(false)
                    latestPlaybackState = hit.copy(isPlaying = started || hit.isPlaying)
                    lastSpotifyTrackId = null // force enrich on next poll / immediate
                    onTrackChanged(latestPlaybackState!!)
                    lyricSyncEngine.updatePlayback(latestPlaybackState!!)
                    updateNotification(
                        if (started) "Playing ${hit.trackName}"
                        else "Found ${hit.trackName} — open Spotify if play failed",
                    )
                    showLyricsHud(force = true)
                }
            },
            onCancel = {
                updateNotification("Song search cancelled")
                showLyricsHud(force = true)
            },
        )
        updateNotification("Gaze keyboard · search song · Search submits")
    }

    private var likedFocusIndex: Int = 0
    private var likedItems: List<SongPickItem> = emptyList()
    private var likedSelectRunnable: Runnable? = null
    private val likedMainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun openLikedSongs() {
        GlassesHomeController.markMiniAppOpen("spotify")
        updateNotification("Loading Liked Songs…")
        serviceScope.launch {
            likedItems = runCatching { spotifyClient.fetchLikedTracks(20) }.getOrDefault(emptyList())
            likedFocusIndex = 0
            if (likedItems.isEmpty()) {
                updateNotification("Liked Songs empty — reconnect Spotify for library scope")
            } else {
                updateNotification("Liked Songs · ${likedItems.size} tracks")
            }
            pushLikedSongsHud()
        }
    }

    private fun pushLikedSongsHud(selectProgress: Float = 0f) {
        cancelLikedSelectGaze()
        val hud = GlassesHud.SongList(
            title = "Liked Songs",
            items = likedItems,
            focusedIndex = likedFocusIndex,
            selectDwellProgress = selectProgress,
            statusLine = if (likedItems.isEmpty()) {
                "Reconnect Spotify for Liked Songs scope"
            } else {
                "Tap / 1s gaze to play"
            },
            actions = SongListHudActions(
                onSelect = { item -> playSongPick(item) },
                onFocusChanged = { idx ->
                    likedFocusIndex = idx.coerceIn(0, (likedItems.size - 1).coerceAtLeast(0))
                    // Reset arm — do not auto-fire until real LOS via feedLikedSelectGaze.
                    pushLikedSongsHud()
                },
                onBack = { showLyricsHud(force = true) },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y ->
                    feedLikedSelectGaze()
                    GlassesHomeController.feedAppHomeGaze(x, y)
                },
            ),
        )
        SpotifySessionBridge.replaceHud(hud)
    }

    /** Arm 1s select only while LOS is actively feeding gaze (not on focus alone). */
    private fun feedLikedSelectGaze() {
        if (likedItems.isEmpty()) return
        if (likedSelectRunnable != null) return
        val idx = likedFocusIndex
        val fire = Runnable {
            likedItems.getOrNull(idx)?.let { playSongPick(it) }
            likedSelectRunnable = null
        }
        likedSelectRunnable = fire
        likedMainHandler.postDelayed(fire, 1_000L)
    }

    private fun cancelLikedSelectGaze() {
        likedSelectRunnable?.let { likedMainHandler.removeCallbacks(it) }
        likedSelectRunnable = null
    }

    private var lyricsControlId: String? = null
    /** Gaze dwell (1 s) and taps share one gate, so a control runs once per intent. */
    private val dwell = GazeDwellController(onFire = { id -> runControl(id) })

    private fun feedLyricsControlGaze(nx: Float, ny: Float) = dwell.feed(nx, ny)

    private fun cancelLyricsControlGaze() = dwell.reset()

    private fun runControl(id: String) {
        when (id) {
            "prev" -> previousSpotifyTrack()
            "play" -> toggleSpotifyPlayback()
            "next" -> nextSpotifyTrack()
            "search" -> openSongSearchKeyboard()
            "liked" -> openLikedSongs()
        }
    }

    private fun playSongPick(item: SongPickItem) {
        cancelLikedSelectGaze()
        updateNotification("Playing ${item.title}")
        serviceScope.launch {
            val started = runCatching { spotifyClient.playTrack(item.trackId) }.getOrDefault(false)
            latestPlaybackState = SpotifyPlaybackState(
                trackId = item.trackId,
                trackName = item.title,
                artistName = item.artist,
                albumName = null,
                albumImageUrl = null,
                progressMs = 0L,
                isPlaying = started,
                durationMs = 0L,
                previewUrl = null,
            )
            lastSpotifyTrackId = null
            onTrackChanged(latestPlaybackState!!)
            lyricSyncEngine.updatePlayback(latestPlaybackState!!)
            showLyricsHud(force = true)
            updateNotification(
                if (started) "Playing ${item.title}"
                else "Selected ${item.title} — open Spotify if play failed",
            )
        }
    }

    private fun previousSpotifyTrack() = playerCommand("previous") { spotifyClient.skipToPreviousTrack() }

    private fun nextSpotifyTrack() = playerCommand("next") { spotifyClient.skipToNextTrack() }

    private fun toggleSpotifyPlayback() {
        val shouldPlay = latestPlaybackState?.isPlaying != true
        playerCommand(if (shouldPlay) "play" else "pause") { spotifyClient.setPlaybackPlaying(shouldPlay) }
    }

    /**
     * Send a player command and only report success when Spotify accepted it AND a fresh poll confirms the state.
     * Failures show an honest notice on glass; nothing is shown as "playing" just because a command was sent.
     */
    private fun playerCommand(label: String, send: suspend () -> Boolean) {
        if (!spotifyAuth.isConnected()) {
            LyricsControlState.setNotice("Spotify is not connected")
            return
        }
        serviceScope.launch {
            val accepted = runCatching { send() }.getOrDefault(false)
            if (!accepted) {
                LyricsControlState.setNotice("Spotify did not accept $label")
                return@launch
            }
            delay(700L)
            val fresh = runCatching { spotifyClient.getCurrentlyPlaying() }.getOrNull()
            if (fresh != null) {
                latestPlaybackState = fresh
                lastPlaybackAtMs = System.currentTimeMillis()
                val wantPlaying = when (label) { "play" -> true; "pause" -> false; else -> null }
                if (wantPlaying != null && fresh.isPlaying != wantPlaying) {
                    LyricsControlState.setNotice("Spotify has not confirmed $label yet")
                }
                if (fresh.trackId != lastSpotifyTrackId) {
                    lastSpotifyTrackId = fresh.trackId
                    onTrackChanged(fresh)
                }
                publishHomePreview(fresh, latestArt)
                showLyricsHud()
            } else {
                LyricsControlState.setNotice("Could not confirm $label")
            }
            updateNotification("Spotify $label")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MyMav Spotify",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SpotifyCompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MyMav Spotify")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "MyMavSpotify"
        private const val CHANNEL_ID = "mymav_spotify"
        private const val NOTIFICATION_ID = 4402
        private const val SPOTIFY_POLL_MS = 5_000L
        /** No successful poll for this long while signed in = Spotify is unavailable. */
        private const val STALE_MS = 30_000L

        const val ACTION_SHOW_LYRICS = "com.mymav.miniapps.spotify.SHOW_LYRICS"
        const val ACTION_SEARCH_SONG = "com.mymav.miniapps.spotify.SEARCH_SONG"
        const val ACTION_LIKED_SONGS = "com.mymav.miniapps.spotify.LIKED_SONGS"
        const val ACTION_STOP = "com.mymav.miniapps.spotify.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SpotifyCompanionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpotifyCompanionService::class.java).setAction(ACTION_STOP),
            )
        }

        fun showLyrics(context: Context) {
            context.startForegroundService(
                Intent(context, SpotifyCompanionService::class.java).setAction(ACTION_SHOW_LYRICS),
            )
        }

        fun searchSong(context: Context) {
            context.startForegroundService(
                Intent(context, SpotifyCompanionService::class.java).setAction(ACTION_SEARCH_SONG),
            )
        }

        fun likedSongs(context: Context) {
            context.startForegroundService(
                Intent(context, SpotifyCompanionService::class.java).setAction(ACTION_LIKED_SONGS),
            )
        }
    }
}
