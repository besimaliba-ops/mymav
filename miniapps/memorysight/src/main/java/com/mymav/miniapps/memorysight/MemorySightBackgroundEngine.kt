package com.mymav.miniapps.memorysight

import android.content.Context

/**
 * Stub background hook — seeds mock timeline while MyMav / companion FGS runs.
 * Real pipeline: AI camera detections append via [MemorySightStore.append].
 */
object MemorySightBackgroundEngine {
    @Volatile
    private var seeded = false

    fun seedIfNeeded(context: Context) {
        if (seeded) return
        seeded = true
        MemorySightStore(context).load()
    }

    fun resetSeedFlag() {
        seeded = false
    }
}
