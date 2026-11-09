package com.mymav.miniapps.mymind

import android.content.Context

/**
 * Stub pipeline: conversation ingest → summary + extracted tasks.
 * [seedIfNeeded] loads mock conversation memory when MyMav runs.
 */
object MymindBackgroundEngine {
    @Volatile
    private var seeded = false

    fun seedIfNeeded(context: Context) {
        if (seeded) return
        seeded = true
        MymindStore(context).loadTasks()
    }

    fun resetSeedFlag() {
        seeded = false
    }
}
