package com.mymav.miniapps.spotify

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.ShellSessionHolder

/** Shell-owned [GlassesSession] handle for Spotify services. */
object SpotifySessionBridge {
    fun attach(session: GlassesSession) {
        ShellSessionHolder.install(session)
        GlassesHomeController.attach(session)
    }

    fun detach() {
        // Keep process-lifetime session for background companions.
    }

    fun session(): GlassesSession? = ShellSessionHolder.get()

    fun replaceHud(hud: GlassesHud) {
        session()?.replaceHud(hud)
    }

    fun clearHud() {
        session()?.clearHud()
    }
}
