package com.mymav.miniapps.conversate

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymavAi
import kotlinx.coroutines.launch

@Composable
fun ConversateMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var briefing by remember { mutableStateOf("") }
    var prepNotes by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MeetingMode.General) }
    var engineState by remember { mutableStateOf(ConversateSessionState()) }
    var startError by remember { mutableStateOf<String?>(null) }
    var notepadMsg by remember { mutableStateOf<String?>(null) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { micGranted = it }
    val modes = MeetingMode.entries.toList()
    val engine = remember {
        ConversateSessionHolder.getOrCreate(context, scope) { engineState = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conversate", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Provisional captions update in place. Cues gated (not every sentence). " +
                "Stop offers Notepad save — never silent tasks.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "AI: ${if (MymavAi.isConfigured()) "configured" else "not set — local cues only"} · Mic: ${if (micGranted) "allowed" else "needed"}",
            style = MaterialTheme.typography.labelMedium,
        )
        if (!micGranted) {
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Allow microphone")
            }
        }
        OutlinedTextField(
            value = prepNotes,
            onValueChange = { prepNotes = it },
            label = { Text("Prep notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = briefing,
            onValueChange = { briefing = it },
            label = { Text("Briefing pack") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Mode: ${mode.label}")
        Button(onClick = {
            mode = modes[(modes.indexOf(mode) + 1) % modes.size]
        }) { Text("Cycle meeting mode") }
        Button(onClick = {
            GlassesHomeController.markMiniAppOpen("conversate")
            ConversateSessionHolder.premiumEnter(session, context, scope, mode, prepNotes, briefing) {
                engineState = it
            }
            startError = null
        }) { Text("Premium enter HUD (briefing cues)") }
        Button(
            onClick = {
                if (!micGranted) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@Button
                }
                GlassesHomeController.markMiniAppOpen("conversate")
                val err = engine.start(
                    SessionStartConfig(
                        meetingMode = mode,
                        prepNotes = prepNotes,
                        briefingMaterials = briefing,
                    ),
                    session,
                )
                startError = err
                engineState = engine.currentState()
                if (err == null) engine.pushHud(session)
            },
            enabled = !engineState.listening,
        ) { Text("Start listening") }
        OutlinedButton(
            onClick = {
                engine.toggleRollingSummary(session)
                engineState = engine.currentState()
            },
            enabled = engineState.listening || !engineState.rollingSummary.isBlank() || engineState.pendingNotepadOffer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (engineState.showRollingSummary) "Summary · return to live" else "Summary")
        }
        Button(
            onClick = {
                notepadMsg = engine.saveSummaryToNotepad()
                engineState = engine.currentState()
            },
            enabled = engineState.listening || engineState.pendingNotepadOffer ||
                !engineState.rollingSummary.isBlank() || engineState.summary.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (engineState.listening) "Save (Notepad snapshot · keep listening)" else "Save") }
        Button(
            onClick = {
                scope.launch {
                    engine.stopListening(session)
                    engineState = engine.currentState()
                    startError = null
                    notepadMsg = null
                }
            },
            enabled = engineState.listening,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop") }
        if (engineState.pendingNotepadOffer) {
            OutlinedButton(
                onClick = {
                    engine.dismissNotepadOffer()
                    engineState = engine.currentState()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done · don't save again") }
        }
        notepadMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        startError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (engineState.statusLine.isNotBlank()) {
            Text("Status: ${engineState.statusLine}", style = MaterialTheme.typography.bodySmall)
        }
        if (engineState.captionProvisional && engineState.livePartial.isNotBlank()) {
            Text(
                "Caption… ${engineState.livePartial.takeLast(160)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else if (engineState.committedTranscript.isNotBlank()) {
            Text(
                "Caption: ${engineState.committedTranscript.takeLast(160)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        engineState.activeCue?.let { cue ->
            Text("${cue.type.icon} ${cue.headline}", style = MaterialTheme.typography.titleMedium)
            Text(cue.detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!engineState.rollingSummary.isBlank()) {
            Text("Rolling summary", style = MaterialTheme.typography.titleSmall)
            engineState.rollingSummary.decisions.forEach {
                Text("Decision: $it", style = MaterialTheme.typography.bodySmall)
            }
            engineState.rollingSummary.actions.forEach {
                Text("Action: $it", style = MaterialTheme.typography.bodySmall)
            }
            engineState.rollingSummary.unresolved.forEach {
                Text("Open: $it", style = MaterialTheme.typography.bodySmall)
            }
            engineState.rollingSummary.keyPoints.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        val t = engineState.timings
        if (t.transcriptionSamples > 0 || t.cueSamples > 0) {
            Text(
                "Timings — STT last ${t.lastTranscriptionDelayMs}ms (avg ${t.avgTranscriptionDelayMs}ms, n=${t.transcriptionSamples}) · " +
                    "cue last ${t.lastCueDelayMs}ms (avg ${t.avgCueDelayMs}ms, n=${t.cueSamples})",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (engineState.summary.isNotBlank()) {
            Text("Summary: ${engineState.summary}", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBack) { Text("Back to home") }
    }
}
