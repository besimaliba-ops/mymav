package com.mymav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.MymavMemory

/** Settings -> Memory: what MyMav was explicitly asked to remember. View, edit, delete, or switch it off. */
@Composable
internal fun MemoryCard() {
    var on by remember { mutableStateOf(MymavMemory.enabled()) }
    var items by remember { mutableStateOf(MymavMemory.all()) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    fun refresh() { items = MymavMemory.all() }
    GlassCard {
        CardTitle("Memory", "Things you asked MyMav to remember, like \"remember I'm vegetarian\". Saved only on this phone. Chat context is temporary and is not saved here.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (on) "Memory is on" else "Memory is off", color = MyMavColors.Text, fontSize = 15.sp)
            Switch(checked = on, onCheckedChange = { on = it; MymavMemory.setEnabled(it) })
        }
        if (items.isEmpty()) Text("Nothing saved yet.", color = MyMavColors.TextMuted, fontSize = 13.sp)
        items.forEach { m ->
            if (editing == m.id) {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { MymavMemory.update(m.id, editText); editing = null; refresh() }) { Text("Save") }
                    OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
                }
            } else {
                Text(m.text, color = MyMavColors.Text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = m.id; editText = m.text }) { Text("Edit") }
                    OutlinedButton(onClick = { MymavMemory.delete(m.id); refresh() }) { Text("Delete") }
                }
            }
        }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Add a memory") },
        )
        OutlinedButton(enabled = draft.isNotBlank(), onClick = { MymavMemory.add(draft); draft = ""; refresh() }) { Text("Remember") }
    }
}

/** Settings -> Read aloud: speak every answer through the phone's text-to-speech. Off by default. */
@Composable
internal fun ReadAloudCard() {
    var on by remember { mutableStateOf(com.mymav.glasses.api.MymavSpeech.autoRead()) }
    GlassCard {
        CardTitle("Read aloud", "Speak answers through this phone's text-to-speech. You can also say \"read that aloud\" for one answer. Saying cancel or stop silences it.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (on) "Reading answers aloud" else "Read aloud is off", color = MyMavColors.Text, fontSize = 15.sp)
            Switch(checked = on, onCheckedChange = { on = it; com.mymav.glasses.api.MymavSpeech.setAutoRead(it) })
        }
    }
}

/** Settings -> Learning: per-note progress from voice quizzes ("quiz me on my notes about gym"). */
@Composable
internal fun LearningCard() {
    var items by remember { mutableStateOf(com.mymav.glasses.api.MymavLearning.all()) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.UK) }
    GlassCard {
        CardTitle("Learning progress", "Say \"quiz me on my notes about <topic>\". Cards are made only from your saved notes; you mark each one got it or missed it.")
        if (items.isEmpty()) Text("No quizzes yet.", color = MyMavColors.TextMuted, fontSize = 13.sp)
        items.forEach { p -> Text("${p.title}: ${p.correct} of ${p.seen} correct (${fmt.format(java.util.Date(p.lastAt))})", color = MyMavColors.Text, fontSize = 14.sp) }
        if (items.isNotEmpty()) OutlinedButton(onClick = { com.mymav.glasses.api.MymavLearning.clear(); items = emptyList() }) { Text("Reset progress") }
    }
}
