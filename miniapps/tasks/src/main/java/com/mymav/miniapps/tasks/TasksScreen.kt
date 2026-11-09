package com.mymav.miniapps.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun TasksMiniScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember {
        TasksMiniApp.ensureStore(context)
        TasksMiniApp.store!!
    }
    var tasks by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf(TaskCadence.Daily) }
    var note by remember { mutableStateOf("Persisted locally · due alerts via notification.") }

    fun refreshHud() {
        TasksMiniApp.pushListHud(session)
        note = "${store.openCount()} open · HUD refreshed"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tasks", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Daily + weekly to-dos with times, due dates, and notifications when due.")
        Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("New task") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TaskCadence.Daily, TaskCadence.Weekly, TaskCadence.Once).forEach { c ->
                val selected = cadence == c
                Text(
                    c.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Color(0xFFFFB74D) else Color(0xFFE8E6E1))
                        .clickable { cadence = c }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (selected) Color.Black else Color(0xFF444444),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Button(
            onClick = {
                val title = draft.trim().ifBlank { return@Button }
                val hour = when (cadence) {
                    TaskCadence.Daily -> 9
                    TaskCadence.Weekly -> 11
                    TaskCadence.Once -> 17
                }
                val task = MavTask(
                    title = title,
                    cadence = cadence,
                    dayOfWeek = if (cadence == TaskCadence.Weekly) 6 else 0,
                    dueMinuteOfDay = hour * 60,
                    notifyWhenDue = true,
                )
                tasks = store.upsert(task)
                draft = ""
                refreshHud()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add task") }

        tasks.forEach { task ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFF4F2EE))
                    .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    (if (task.done) "✓ " else "") + task.title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (task.done) Color(0xFF888888) else Color(0xFF1A1A1A),
                )
                Text(task.dueLabel(), fontSize = 12.sp, color = Color(0xFF666666))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        tasks = store.toggleDone(task.id)
                        refreshHud()
                    }) { Text(if (task.done) "Undo" else "Done") }
                    OutlinedButton(onClick = {
                        tasks = store.delete(task.id)
                        refreshHud()
                    }) { Text("Delete") }
                }
            }
        }

        Button(onClick = { refreshHud() }, modifier = Modifier.fillMaxWidth()) {
            Text("Push list to glasses HUD")
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
    }
}
