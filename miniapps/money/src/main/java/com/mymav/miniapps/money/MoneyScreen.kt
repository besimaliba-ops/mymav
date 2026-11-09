package com.mymav.miniapps.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun MoneyMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        MoneyMiniApp.ensureStore(context)
        MoneyMiniApp.store!!
    }
    var homeCurrency by remember { mutableStateOf(store.homeCurrency) }
    var note by remember {
        mutableStateOf(store.lastQuote()?.let { "${it.foreignLabel()} → ${it.homeLabel()}" } ?: "No quote yet")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Money", style = MaterialTheme.typography.headlineMedium)
        Text("Gaze 1s at a foreign price to convert to your home currency (default GBP).")
        OutlinedTextField(
            value = homeCurrency,
            onValueChange = {
                homeCurrency = it.uppercase().take(3)
                store.homeCurrency = homeCurrency
            },
            label = { Text("Home currency") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(note, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                MoneyMiniApp.simulateGazeConvert(session)
                note = store.lastQuote()?.let { "${it.foreignLabel()} → ${it.homeLabel()}" } ?: note
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demo gaze: EUR €12.50 → $homeCurrency") }
        Button(
            onClick = { MoneyMiniApp.pushQuoteHud(session) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Push quote to glasses HUD") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
