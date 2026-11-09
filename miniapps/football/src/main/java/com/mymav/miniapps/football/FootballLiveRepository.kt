package com.mymav.miniapps.football

import android.content.Context
import com.mymav.glasses.api.MymavScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live football board from the same ESPN scoreboard as the assistant ([MymavScores]).
 * Bounded refresh; never fabricates scores. Empty feed is an honest empty/error state.
 */
object FootballLiveRepository {
    private const val STALE_AFTER_MS = 5 * 60_000L

    @Volatile
    private var lastBoard: FootballBoardSnapshot? = null

    fun lastSnapshot(): FootballBoardSnapshot? = lastBoard

    suspend fun refresh(context: Context? = null): FootballBoardSnapshot = withContext(Dispatchers.IO) {
        val keys = context?.let { FootballFavourites.loadKeys(it).toList() }
            ?: listOf("premier league", "champions league")
        val board = MymavScores.fetchSoccerBoard(leagueKeys = keys, daysBack = 1, daysForward = 2)
        val now = System.currentTimeMillis()
        val feeds = board.matches
            .sortedWith(
                compareBy<MymavScores.Match> {
                    when (it.state) {
                        "in" -> 0
                        "pre" -> 1
                        "post" -> 2
                        else -> 3
                    }
                }.thenBy { it.whenMs },
            )
            .map { LiveMatchFeed.from(it, stale = false) }
        val error = when {
            board.matches.isEmpty() && board.errors.isNotEmpty() ->
                "Could not reach ESPN: ${board.errors.take(2).joinToString("; ")}"
            board.matches.isEmpty() ->
                "No fixtures listed by ESPN for selected leagues in this window."
            else -> board.errors.takeIf { it.isNotEmpty() }?.joinToString("; ").orEmpty()
        }
        val snap = FootballBoardSnapshot(
            feeds = feeds,
            provider = board.provider,
            updatedAtMs = board.fetchedAtMs.takeIf { it > 0 } ?: now,
            error = error,
        )
        lastBoard = snap
        snap
    }

    fun markStaleIfNeeded(snap: FootballBoardSnapshot): FootballBoardSnapshot {
        val age = System.currentTimeMillis() - snap.updatedAtMs
        if (age < STALE_AFTER_MS) return snap
        return snap.copy(
            feeds = snap.feeds.map { it.copy(stale = true) },
            error = listOfNotNull(snap.error.takeIf { it.isNotBlank() }, "Feed older than 5 min — refreshing…")
                .joinToString(" · "),
        )
    }
}
