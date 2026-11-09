package com.mymav.miniapps.spotify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.mymav.miniapps.spotify.api.SpotifyAuthManager
import com.mymav.miniapps.spotify.service.SpotifyCompanionService
import kotlinx.coroutines.launch

@Composable
fun SpotifyScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by session.statusMessage.collectAsState()
    val spotifyAuth = remember { SpotifyAuthManager(context) }
    var clientId by remember { mutableStateOf(spotifyAuth.getClientId()) }
    var spotifyStatus by remember { mutableStateOf(spotifyAuth.statusLabel()) }
    var localNote by remember { mutableStateOf("Spotify mini-app — lyrics & now playing on glass.") }

    DisposableEffect(session) {
        SpotifySessionBridge.attach(session)
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Spotify", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect Spotify, sync lyrics, push now-playing glasslets to the 640×400 lens. " +
                "Separate from Notify (WhatsApp / Instagram only).",
        )
        Text("Glasses: $status", style = MaterialTheme.typography.bodySmall)
        Text(localNote, style = MaterialTheme.typography.bodySmall)

        Text("1 · Companion", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                SpotifySessionBridge.attach(session)
                SpotifyCompanionService.start(context)
                localNote = "Spotify companion started — lyrics HUD looping"
            }) { Text("Start") }
            OutlinedButton(onClick = {
                SpotifyCompanionService.stop(context)
                localNote = "Spotify companion stopped"
            }) { Text("Stop") }
        }
        Button(
            onClick = {
                SpotifySessionBridge.attach(session)
                GlassesHomeController.markMiniAppOpen("spotify")
                SpotifyCompanionService.showLyrics(context)
                localNote = "Spotify lyrics HUD on lens · long-tap HOME"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Show lyrics / now playing HUD") }
        OutlinedButton(
            onClick = {
                SpotifySessionBridge.attach(session)
                GlassesHomeController.attach(session)
                GlassesHomeController.markMiniAppOpen("spotify")
                SpotifyCompanionService.searchSong(context)
                localNote = "Shared gaze keyboard · Search submits song (not emoji)"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search song (gaze keyboard)") }
        OutlinedButton(
            onClick = {
                SpotifySessionBridge.attach(session)
                GlassesHomeController.attach(session)
                GlassesHomeController.markMiniAppOpen("spotify")
                SpotifyCompanionService.likedSongs(context)
                localNote = "Liked Songs on glass · tap / 1s gaze to play"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Liked Songs (glasses)") }

        Text("2 · Connect", style = MaterialTheme.typography.titleMedium)
        Text(spotifyStatus, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Spotify Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                spotifyAuth.saveClientId(clientId)
                spotifyStatus = spotifyAuth.statusLabel()
                localNote = "Client ID saved"
            }) { Text("Save ID") }
            OutlinedButton(onClick = {
                spotifyAuth.saveClientId(clientId)
                runCatching {
                    context.startActivity(spotifyAuth.createAuthorizationIntent())
                    localNote = "Browser opened for Spotify OAuth"
                }.onFailure {
                    localNote = "Spotify auth: ${it.message}"
                }
                spotifyStatus = spotifyAuth.statusLabel()
            }) { Text("Connect Spotify") }
            OutlinedButton(onClick = {
                scope.launch {
                    val token = spotifyAuth.getValidAccessToken()
                    spotifyStatus = spotifyAuth.statusLabel()
                    localNote = if (token != null) "Access token OK" else "No Spotify token yet"
                }
            }) { Text("Refresh") }
        }
        Text(
            "Redirect URI for Spotify dashboard: ${SpotifyAuthManager.REDIRECT_URI}",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(onClick = onBack) { Text("Back to home") }
    }
}
