package com.mymav.glasses.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Live, final and scheduled results from ESPN's public scoreboard feed (no key, unofficial).
 * Assistant and Football mini-app share this feed — nothing fabricated when the feed is empty.
 */
object MymavScores {
    class League(val key: String, val path: String, val label: String)

    val LEAGUES: LinkedHashMap<String, League> = linkedMapOf(
        "premier league" to League("premier league", "soccer/eng.1", "Premier League"),
        "la liga" to League("la liga", "soccer/esp.1", "La Liga"),
        "bundesliga" to League("bundesliga", "soccer/ger.1", "Bundesliga"),
        "serie a" to League("serie a", "soccer/ita.1", "Serie A"),
        "ligue 1" to League("ligue 1", "soccer/fra.1", "Ligue 1"),
        "champions league" to League("champions league", "soccer/uefa.champions", "Champions League"),
        "nba" to League("nba", "basketball/nba", "NBA"),
        "nfl" to League("nfl", "football/nfl", "NFL"),
        "nhl" to League("nhl", "hockey/nhl", "NHL"),
        "mlb" to League("mlb", "baseball/mlb", "MLB"),
    )
    private val SOCCER_KEYS = listOf("premier league", "la liga", "bundesliga", "serie a", "ligue 1", "champions league")
    private val STRONG = Regex("\\b(scores?|fixtures?|who won|who is winning|results?|did .+ win|is .+ playing|when do(?:es)? .+ play|next match|next game)\\b")
    private val SPORT_WORD = Regex("\\b(football|soccer|match|game|league|nba|nfl|nhl|mlb|basketball|hockey|baseball|vs|v)\\b")
    private val NOISE = setOf(
        "what", "whats", "what's", "the", "score", "scores", "of", "in", "for", "is", "are", "was", "did", "do", "does", "win", "won",
        "who", "match", "game", "latest", "last", "next", "today", "tonight", "yesterday", "tomorrow", "results", "result", "fixtures",
        "fixture", "playing", "play", "when", "football", "soccer", "league", "premier", "la", "liga", "bundesliga", "serie", "ligue",
        "champions", "nba", "nfl", "nhl", "mlb", "me", "tell", "give", "show", "current", "right", "now", "a", "an", "and", "my", "vs", "v",
        "this", "weekend", "week", "winning", "to", "on", "any", "live", "us", "at", "how", "get", "with",
    )

    data class Match(
        val league: String,
        val leagueKey: String,
        val home: String,
        val away: String,
        val homeScore: String,
        val awayScore: String,
        /** ESPN state: in / pre / post */
        val state: String,
        val detail: String,
        val whenMs: Long,
        val fetchedAtMs: Long,
    ) {
        fun statusLabel(): String = when (state) {
            "in" -> "LIVE"
            "post" -> "FINAL"
            "pre" -> "SCHEDULED"
            else -> state.uppercase().ifBlank { "UNKNOWN" }
        }

        fun scoreInts(): Pair<Int, Int> =
            (homeScore.toIntOrNull() ?: 0) to (awayScore.toIntOrNull() ?: 0)
    }

    data class Board(
        val matches: List<Match>,
        val fetchedAtMs: Long,
        val provider: String = "ESPN scoreboard",
        val errors: List<String> = emptyList(),
    )

    fun isRequest(c: String): Boolean =
        STRONG.containsMatchIn(c) && (LEAGUES.keys.any { c.contains(it) } || SPORT_WORD.containsMatchIn(c) || teamWords(c).isNotEmpty())

    private fun teamWords(c: String) =
        c.replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 2 && it !in NOISE }

    /** Soccer boards for the Football mini-app (and anyone else). Bounded network: one GET per league. */
    fun fetchSoccerBoard(
        leagueKeys: List<String> = SOCCER_KEYS,
        daysBack: Int = 0,
        daysForward: Int = 2,
    ): Board {
        val leagues = leagueKeys.mapNotNull { LEAGUES[it] }
        return fetchBoard(leagues, daysBack, daysForward)
    }

    fun fetchBoard(leagues: List<League>, daysBack: Int, daysForward: Int): Board {
        val fetchedAt = System.currentTimeMillis()
        val fmtDay = SimpleDateFormat("yyyyMMdd", Locale.US)
        val to = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysForward) }.time
        val from = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysBack) }.time
        val range = fmtDay.format(from) + "-" + fmtDay.format(to)
        val matches = ArrayList<Match>()
        val errors = ArrayList<String>()
        for (l in leagues) {
            try {
                val base = "https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard"
                val res = try {
                    MymavHttp.request("GET", "$base?dates=$range", timeoutMs = 12_000)
                } catch (e: MymavHttp.Failure) {
                    MymavHttp.request("GET", base, timeoutMs = 12_000)
                }
                val ev = JSONObject(res).optJSONArray("events") ?: continue
                for (i in 0 until ev.length()) {
                    val e = ev.getJSONObject(i)
                    val comp = e.getJSONArray("competitions").getJSONObject(0)
                    val cs = comp.getJSONArray("competitors")
                    var home: JSONObject? = null
                    var away: JSONObject? = null
                    for (k in 0 until cs.length()) {
                        cs.getJSONObject(k).let {
                            if (it.optString("homeAway") == "home") home = it else away = it
                        }
                    }
                    if (home == null || away == null) continue
                    val st = e.getJSONObject("status").getJSONObject("type")
                    val whenMs = runCatching {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.parse(e.optString("date"))!!.time
                    }.getOrDefault(0L)
                    matches += Match(
                        league = l.label,
                        leagueKey = l.key,
                        home = home!!.getJSONObject("team").optString("displayName"),
                        away = away!!.getJSONObject("team").optString("displayName"),
                        homeScore = home!!.optString("score"),
                        awayScore = away!!.optString("score"),
                        state = st.optString("state"),
                        detail = st.optString("shortDetail"),
                        whenMs = whenMs,
                        fetchedAtMs = fetchedAt,
                    )
                }
            } catch (e: Exception) {
                errors += "${l.label}: ${e.message?.take(60) ?: e.javaClass.simpleName}"
            }
        }
        return Board(matches, fetchedAt, errors = errors)
    }

    fun fetch(c: String): AssistantRouter.Outcome? {
        val chosen = LEAGUES.filterKeys { c.contains(it) }.values.toList()
        val team = teamWords(c)
        val leagues = if (chosen.isNotEmpty()) {
            chosen
        } else if (Regex("\\b(nba|basketball)\\b").containsMatchIn(c)) {
            listOf(LEAGUES.getValue("nba"))
        } else {
            SOCCER_KEYS.map { LEAGUES.getValue(it) }
        }
        val board = fetchBoard(leagues, daysBack = if (team.isEmpty()) 0 else 3, daysForward = 7)
        if (board.errors.size == leagues.size && board.matches.isEmpty()) {
            val lastErr = board.errors.lastOrNull().orEmpty()
            return AssistantRouter.Outcome(
                "I couldn't reach the live scores feed just now, so I can't give you scores ($lastErr).",
                AssistantLog.Kind.Error, emptyList(), "",
            )
        }
        val picked = if (team.isEmpty()) {
            board.matches
        } else {
            board.matches.filter { m -> team.all { w -> (m.home + " " + m.away).lowercase().contains(w) } }
        }
        if (picked.isEmpty()) {
            if (team.isNotEmpty()) return null
            val names = leagues.joinToString(", ") { it.label }
            return AssistantRouter.Outcome(
                "No $names matches are listed by the scores feed for the next 7 days.",
                AssistantLog.Kind.Data,
                listOf(AssistantLog.Source("ESPN scoreboard", "https://www.espn.com/soccer/scoreboard")),
                "ESPN scores, updated " + SimpleDateFormat("HH:mm", Locale.UK).format(Date(board.fetchedAtMs)),
            )
        }
        val day = SimpleDateFormat("EEE HH:mm", Locale.UK)
        val shown = if (team.isEmpty()) {
            val live = picked.filter { it.state == "in" }
            val rest = picked.filter { it.state == "pre" }.sortedBy { it.whenMs }
            (live + rest).take(6).ifEmpty { picked.take(6) }
        } else {
            val live = picked.filter { it.state == "in" }
            val past = picked.filter { it.state == "post" }.sortedByDescending { it.whenMs }.take(1)
            val next = picked.filter { it.state == "pre" }.sortedBy { it.whenMs }.take(1)
            (live + past + next).distinct()
        }
        val lines = shown.joinToString("\n") { m ->
            when (m.state) {
                "in" -> "LIVE ${m.detail}: ${m.home} ${m.homeScore}-${m.awayScore} ${m.away}"
                "post" -> "FINAL: ${m.home} ${m.homeScore}-${m.awayScore} ${m.away}"
                else -> "SCHEDULED ${day.format(Date(m.whenMs))}: ${m.home} v ${m.away}"
            }
        }
        val stamp = SimpleDateFormat("HH:mm", Locale.UK).format(Date(board.fetchedAtMs))
        return AssistantRouter.Outcome(
            lines, AssistantLog.Kind.Data,
            listOf(AssistantLog.Source("ESPN scoreboard (fetched $stamp)", "https://www.espn.com/soccer/scoreboard")),
            "ESPN scores, updated $stamp",
        )
    }
}
