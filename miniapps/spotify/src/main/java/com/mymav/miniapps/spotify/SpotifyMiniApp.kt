package com.mymav.miniapps.spotify

import android.content.Context
import android.content.Intent
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.LyricsHudActions
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.miniapps.spotify.service.SpotifyCompanionService

object SpotifyMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "spotify",
        title = "Spotify",
        subtitle = "Now playing + synced lyrics on glass",
        route = "mini/spotify",
        requiredCapabilities = listOf("spotify", "hud"),
    )

    @Volatile
    private var appContext: Context? = null

    fun ensureContext(context: Context) {
        appContext = context.applicationContext
    }

    override fun onEnter(session: GlassesSession) {
        SpotifySessionBridge.attach(session)
        GlassesHomeController.markMiniAppOpen("spotify")
        // Prefer live lyrics HUD (SEARCH + LIKED + transport visible). Kick companion if needed.
        val ctx = appContext
        if (ctx != null) {
            runCatching {
                ctx.startForegroundService(
                    Intent(ctx, SpotifyCompanionService::class.java).apply {
                        action = SpotifyCompanionService.ACTION_SHOW_LYRICS
                    },
                )
            }
        }
        // Immediate glasslet so SEARCH / LIKED / << PLAY >> are never missing while companion boots.
        session.replaceHud(
            GlassesHud.Lyrics(
                trackName = "Spotify",
                artistName = "Connect · play a track",
                albumName = null,
                currentLyric = "SEARCH · LIKED · gaze / tap transport",
                nextLyric = "Double-Forward opens Liked Songs",
                progressMs = 0L,
                isPlaying = false,
                actions = LyricsHudActions(
                    onPreviousTrack = {
                        // Companion overrides these once it pushes a live HUD.
                    },
                    onTogglePlayback = {},
                    onNextTrack = {},
                    onSearchRequested = {
                        ctx?.let {
                            runCatching {
                                it.startForegroundService(
                                    Intent(it, SpotifyCompanionService::class.java).apply {
                                        action = SpotifyCompanionService.ACTION_SEARCH_SONG
                                    },
                                )
                            }
                        }
                    },
                    onLikedSongsRequested = {
                        ctx?.let {
                            runCatching {
                                it.startForegroundService(
                                    Intent(it, SpotifyCompanionService::class.java).apply {
                                        action = SpotifyCompanionService.ACTION_LIKED_SONGS
                                    },
                                )
                            }
                        }
                    },
                    onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                    onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
                ),
            ),
        )
    }

    override fun onLeave(session: GlassesSession) {
        // Do not clearHud — home board is already pushed; clearing empties the sim draw queue.
        SpotifySessionBridge.detach()
    }
}
