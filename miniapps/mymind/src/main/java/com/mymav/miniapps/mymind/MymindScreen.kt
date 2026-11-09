package com.mymav.miniapps.mymind

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymindTaskPage

@Composable
fun MymindMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        MymindMiniApp.ensureStore(context)
        MymindMiniApp.store!!
    }
    var page by remember { mutableStateOf(MymindTaskPage.Daily) }
    val tasks = remember(page) { store.tasksFor(page) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mymind", style = MaterialTheme.typography.headlineMedium)
        Text(store.memorySummary())
        Text(store.conversationSnippet(), style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MymindTaskPage.entries.forEach { p ->
                val selected = page == p
                Text(
                    p.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Color(0xFF4DD0E1) else Color(0xFFE8E6E1))
                        .clickable {
                            page = p
                            MymindMiniApp.pushBoardHud(session, p)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        store.people().forEach { person ->
            Text("· ${person.name} — ${person.note}")
        }
        tasks.forEach { task ->
            Text("□ ${task.title}", fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = { MymindMiniApp.pushBoardHud(session, page) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Push ${page.name} board to glasses HUD") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
