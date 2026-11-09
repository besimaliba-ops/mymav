package com.mymav.miniapps.football

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HomeWidgetPreviewStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded live refresh (45s) of ESPN boards.
 *
 * **Ownership:** background polls always update [snapshot] + home-widget preview.
 * They call [FootballHudBuilder.pushBoard] only while Football is the foreground
 * glasses mini-app ([GlassesHomeController.acceptsFootballHud]). Leaving Football
 * (HOME / another app) bumps [generation] so in-flight refresh completions are ignored
 * for HUD draws.
 */
object FootballFeedMonitor {
    private const val REFRESH_MS = 45_000L

    private var job: Job? = null
    private val generation = AtomicInteger(0)

    @Volatile
    private var snapshot: FootballBoardSnapshot = FootballBoardSnapshot(
        feeds = emptyList(),
        provider = "ESPN scoreboard",
        updatedAtMs = 0L,
        error = "Not loaded yet",
    )

    fun currentSnapshot(): FootballBoardSnapshot = snapshot
    fun currentFeed(): LiveMatchFeed? = snapshot.feeds.firstOrNull()
    fun generation(): Int = generation.get()

    fun start(session: GlassesSession, scope: CoroutineScope, context: Context? = null) {
        stop()
        val gen = generation.incrementAndGet()
        job = scope.launch {
            while (isActive && generation.get() == gen) {
                val snap = runCatching { FootballLiveRepository.refresh(context) }
                    .getOrElse {
                        FootballBoardSnapshot(
                            feeds = snapshot.feeds.map { it.copy(stale = true) },
                            provider = "ESPN scoreboard",
                            updatedAtMs = snapshot.updatedAtMs,
                            error = it.message?.take(80) ?: it.javaClass.simpleName,
                        )
                    }
                if (generation.get() != gen) break
                applySnapshot(snap, session, pushHud = true)
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
    }

    /**
     * One-shot data refresh. Pushes the Football HUD only when Football owns the
     * foreground; otherwise updates snapshot + home widget only.
     */
    suspend fun refreshOnce(session: GlassesSession, context: Context?): FootballBoardSnapshot {
        val gen = generation.get()
        val snap = FootballLiveRepository.refresh(context)
        if (generation.get() != gen && job == null) {
            // Closed session after stop() — still keep latest data, never steal HUD.
            applySnapshot(snap, session, pushHud = false)
            return snap
        }
        applySnapshot(snap, session, pushHud = true)
        return snap
    }

    /** Data-only refresh for callers that must never steal the screen. */
    suspend fun refreshDataOnly(context: Context?): FootballBoardSnapshot {
        val snap = FootballLiveRepository.refresh(context)
        applySnapshot(snap, session = null, pushHud = false)
        return snap
    }

    private fun applySnapshot(
        snap: FootballBoardSnapshot,
        session: GlassesSession?,
        pushHud: Boolean,
    ) {
        snapshot = FootballLiveRepository.markStaleIfNeeded(snap)
        publishHomePreview(snapshot)
        if (!pushHud || session == null) return
        if (!GlassesHomeController.acceptsFootballHud()) return
        FootballHudBuilder.pushBoard(session, snapshot)
    }

    private fun publishHomePreview(board: FootballBoardSnapshot) {
        val primary = board.feeds.firstOrNull()
        HomeWidgetPreviewStore.put(
            "football",
            HomeWidgetPreviewStore.Preview(
                title = "Football",
                subtitle = when {
                    primary != null -> "${primary.status} ${primary.scoreLine()}"
                    board.error.isNotBlank() -> board.error.take(40)
                    else -> "No fixtures"
                },
                metricDetail = primary?.minuteLabel().orEmpty(),
                state = if (job != null) "live" else "idle",
            ),
        )
        GlassesHomeController.refreshHomeIfShowing()
    }
}
