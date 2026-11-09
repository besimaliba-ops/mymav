package com.mymav.miniapps.football

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.miniAppHomeActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FootballHudBuilder {

    fun premiumEnter(): GlassesHud.AppEnter = GlassesHud.AppEnter(
        appId = "football",
        title = "Football",
        subtitle = "ESPN live scoreboard",
        accent = HudAccentTone.Green,
        bullets = listOf(
            "Same feed as Ask MyMav sports",
            "LIVE / HT / FINAL / SCHEDULED labelled",
            "No fabricated scores",
        ),
        metric = "—",
        actions = miniAppHomeActions(),
        metricLabel = "live",
    )

    fun pushBoard(session: GlassesSession, board: FootballBoardSnapshot) {
        // Never steal Home / another mini-app — feed monitor updates data separately.
        if (!GlassesHomeController.acceptsFootballHud()) return
        val stamp = if (board.updatedAtMs > 0) {
            SimpleDateFormat("HH:mm", Locale.UK).format(Date(board.updatedAtMs))
        } else {
            "—"
        }
        val primary = board.feeds.firstOrNull()
        if (primary == null) {
            val hud = GlassesHud.AppEnter(
                appId = "football",
                title = "Football",
                subtitle = board.error.ifBlank { "No fixtures" }.take(40),
                accent = HudAccentTone.Green,
                bullets = listOf(
                    board.provider,
                    "Updated $stamp",
                    board.error.ifBlank { "Try another favourite league on phone" },
                    "Assistant uses the same ESPN feed",
                ),
                metric = "—",
                metricLabel = "feed",
                actions = miniAppHomeActions(),
            )
            session.replaceHud(hud)
            GlassesHomeController.rememberAppHud(hud)
            return
        }
        liveFeed(session, primary, board)
    }

    fun liveFeed(
        session: GlassesSession,
        feed: LiveMatchFeed,
        board: FootballBoardSnapshot? = null,
    ) {
        if (!GlassesHomeController.acceptsFootballHud()) return
        val stamp = if (feed.updatedAtMs > 0) {
            SimpleDateFormat("HH:mm", Locale.UK).format(Date(feed.updatedAtMs))
        } else {
            "—"
        }
        val others = board?.feeds.orEmpty().drop(1).take(2).map {
            "${it.status} ${it.scoreLine()}"
        }
        val hud = GlassesHud.AppEnter(
            appId = "football",
            title = feed.scoreLine(),
            subtitle = "${feed.minuteLabel()} · ${feed.leagueName}" + if (feed.stale) " · STALE" else "",
            accent = HudAccentTone.Green,
            bullets = buildList {
                add("${feed.provider} · $stamp")
                if (feed.detail.isNotBlank()) add(feed.detail.take(72))
                others.forEach { add(it.take(72)) }
                if (board?.error?.isNotBlank() == true) add(board.error.take(72))
            }.take(4),
            metric = when (feed.status) {
                "SCHEDULED", "POSTPONED" -> "—"
                else -> "${feed.homeScore}-${feed.awayScore}"
            },
            metricLabel = feed.minuteLabel(),
            actions = miniAppHomeActions(),
        )
        session.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }
}
