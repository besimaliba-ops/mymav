package com.mymav.miniapps.fitness

import android.content.Context
import com.mymav.glasses.api.GlassesSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Refreshes Health Connect (or demo fallback) while Health is open and re-pushes glass HUD pages.
 */
object FitnessHealthPoller {
    private const val POLL_MS = 25_000L
    private var job: Job? = null

    fun start(context: Context, session: GlassesSession, scope: CoroutineScope) {
        stop()
        val app = context.applicationContext
        val store = HealthStatsStore.shared ?: HealthStatsStore(app).also { HealthStatsStore.shared = it }
        val reader = SamsungHealthReader(app)
        job = scope.launch {
            while (isActive) {
                reader.refresh(store)
                FitnessMiniApp.refreshHud(session, store)
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
