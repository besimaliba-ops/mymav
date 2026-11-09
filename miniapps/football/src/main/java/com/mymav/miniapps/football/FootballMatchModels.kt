package com.mymav.miniapps.football

import com.mymav.glasses.api.MymavScores

/** HUD-facing match row backed by [MymavScores] (ESPN). Never invents scores. */
data class LiveMatchFeed(
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val minute: Int,
    val status: String,
    val detail: String = "",
    val events: List<String> = emptyList(),
    val provider: String = "ESPN scoreboard",
    val updatedAtMs: Long = 0L,
    val stale: Boolean = false,
) {
    fun scoreLine(): String = when (status) {
        "SCHEDULED", "POSTPONED" -> "$homeTeam v $awayTeam"
        else -> "$homeTeam $homeScore - $awayScore $awayTeam"
    }

    fun minuteLabel(): String = when {
        status.equals("HT", true) || detail.contains("HT", true) -> "HT"
        status.equals("LIVE", true) || status.equals("1H", true) || status.equals("2H", true) ->
            if (minute > 0) "${minute}'" else detail.ifBlank { "LIVE" }
        status.equals("FINAL", true) || status.equals("FT", true) -> "FT"
        status.equals("SCHEDULED", true) -> "SCHED"
        status.equals("POSTPONED", true) -> "PPD"
        stale -> "STALE"
        else -> status
    }

    companion object {
        fun from(match: MymavScores.Match, stale: Boolean = false): LiveMatchFeed {
            val (hs, as_) = match.scoreInts()
            val status = when {
                match.detail.contains("Postponed", true) -> "POSTPONED"
                match.state == "in" && match.detail.contains("HT", true) -> "HT"
                match.state == "in" -> "LIVE"
                match.state == "post" -> "FINAL"
                match.state == "pre" -> "SCHEDULED"
                else -> match.statusLabel()
            }
            val minute = Regex("""(\d+)""").find(match.detail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val events = buildList {
                if (match.detail.isNotBlank()) add(match.detail)
            }
            return LiveMatchFeed(
                leagueName = match.league,
                homeTeam = match.home,
                awayTeam = match.away,
                homeScore = hs,
                awayScore = as_,
                minute = minute,
                status = status,
                detail = match.detail,
                events = events,
                provider = "ESPN scoreboard",
                updatedAtMs = match.fetchedAtMs,
                stale = stale,
            )
        }
    }
}

data class FootballBoardSnapshot(
    val feeds: List<LiveMatchFeed>,
    val provider: String,
    val updatedAtMs: Long,
    val error: String = "",
)
