package com.mymav.glasses.api

/**
 * Process-lifetime handle for the shell [GlassesSession].
 * Foreground services (Notify / Spotify) must use this so HUD still works when
 * the Activity is destroyed (app swiped away or phone locked).
 */
object ShellSessionHolder {
    @Volatile
    private var session: GlassesSession? = null

    fun install(session: GlassesSession) {
        this.session = session
    }

    fun get(): GlassesSession? = session
}
