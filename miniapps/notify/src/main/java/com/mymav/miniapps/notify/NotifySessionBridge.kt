package com.mymav.miniapps.notify

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.ShellSessionHolder

/**
 * Shell-owned [GlassesSession] handle for Notify services.
 * Uses [ShellSessionHolder] (strong, process-lifetime) so HUD still works when
 * the phone UI is closed or the Activity is destroyed.
 */
object NotifySessionBridge {
    fun attach(session: GlassesSession) {
        ShellSessionHolder.install(session)
        GlassesHomeController.attach(session)
    }

    /** No longer drops the session — companion / listener need it in background. */
    fun detach() {
        // Intentionally keep ShellSessionHolder + home controller attached.
    }

    fun session(): GlassesSession? = ShellSessionHolder.get()

    fun replaceHud(screenId: String, title: String, lines: List<String>) {
        session()?.replaceHud(screenId, title, lines)
    }

    fun replaceHud(hud: GlassesHud) {
        session()?.replaceHud(hud)
    }

    fun clearHud() {
        session()?.clearHud()
    }
}
