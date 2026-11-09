package com.mymav.miniapps.memorysight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mymav.glasses.api.GlassesSession

@Composable
fun MemorySightMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        MemorySightMiniApp.ensureStore(context)
        MemorySightMiniApp.store!!
    }
    var items by remember { mutableStateOf(store.load()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Memory Sight", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Revu-style memory: AI camera tracks belongings and where you left them. " +
                "Mock timeline: keys, wallet, headphones.",
        )
        items.forEach { d ->
            Text(d.hudLine(), style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                MemorySightMiniApp.pushTimelineHud(session)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Push timeline to glasses HUD") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
