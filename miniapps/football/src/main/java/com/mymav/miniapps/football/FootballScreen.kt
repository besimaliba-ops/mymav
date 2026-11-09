package com.mymav.miniapps.football

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymavScores
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FootballMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf(FootballFeedMonitor.currentSnapshot()) }
    var favs by remember { mutableStateOf(FootballFavourites.loadKeys(context)) }
    val soccerKeys = listOf(
        "premier league", "la liga", "bundesliga", "serie a", "ligue 1", "champions league",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Football", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Live ESPN scoreboard (same as Ask MyMav). Favourites migrate from prior dashboard prefs when present. No fabricated scores.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Favourites", style = MaterialTheme.typography.titleSmall)
        soccerKeys.chunked(2).forEach { row ->
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val label = MymavScores.LEAGUES[key]?.label ?: key
                    FilterChip(
                        selected = key in favs,
                        onClick = {
                            favs = FootballFavourites.toggle(context, key)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        val stamp = if (board.updatedAtMs > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date(board.updatedAtMs))
        } else {
            "never"
        }
        Text("${board.provider} · updated $stamp", style = MaterialTheme.typography.labelMedium)
        if (board.error.isNotBlank()) {
            Text(board.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (board.feeds.isEmpty()) {
            Text("No fixtures in this window — not a demo match.", style = MaterialTheme.typography.bodySmall)
        } else {
            board.feeds.take(12).forEach { feed ->
                Text(
                    "${feed.status} ${feed.minuteLabel()} · ${feed.scoreLine()} · ${feed.leagueName}" +
                        if (feed.stale) " · STALE" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (feed.detail.isNotBlank()) {
                    Text("  ${feed.detail}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Button(
            onClick = {
                // Explicit open — HUD refresh only while Football owns the lens.
                GlassesHomeController.markMiniAppOpen("football")
                FootballFeedMonitor.start(session, scope, context)
                board = FootballFeedMonitor.currentSnapshot()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start live HUD refresh (45s)") }
        Button(
            onClick = {
                scope.launch {
                    GlassesHomeController.markMiniAppOpen("football")
                    board = FootballFeedMonitor.refreshOnce(session, context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh now → HUD") }
        Button(onClick = {
            FootballFeedMonitor.stop()
        }) { Text("Stop refresh") }
        Button(onClick = onBack) { Text("Back to home") }
    }
}
