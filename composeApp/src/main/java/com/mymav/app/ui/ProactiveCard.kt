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
import com.mymav.app.MyMavProactive

@Composable
private fun TimeField(label: String, key: String, def: String) {
    var v by remember { mutableStateOf(MyMavProactive.time(key, def)) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; MyMavProactive.setTime(key, it) }, singleLine = true,
        label = { Text(label) }, isError = !Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(v), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KindRow(title: String, kind: String) {
    var on by remember { mutableStateOf(MyMavProactive.kindOn(kind)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = MyMavColors.Text, fontSize = 14.sp)
        Switch(checked = on, onCheckedChange = { on = it; MyMavProactive.setKindOn(kind, it) })
    }
}

/** Settings -> Proactive help: opt-in nudges with quiet hours, a daily cap, and a test button. */
@Composable
internal fun ProactiveCard() {
    var on by remember { mutableStateOf(MyMavProactive.enabled()) }
    var cap by remember { mutableStateOf(MyMavProactive.cap()) }
    var msg by remember { mutableStateOf("") }
    GlassCard {
        CardTitle("Proactive help", "Optional nudges: a dinner idea and today's tasks, at times you choose. Off by default. Nudges arrive while MyMav is running, never during quiet hours, and each kind at most once a day. Snooze or dismiss from the notification.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (on) "Proactive help is on" else "Proactive help is off", color = MyMavColors.Text, fontSize = 15.sp)
            Switch(checked = on, onCheckedChange = { on = it; MyMavProactive.setEnabled(it) })
        }
        KindRow("Dinner suggestion (AI)", "recipe")
        TimeField("Dinner suggestion time (HH:MM)", "time_recipe", "18:00")
        KindRow("Today's tasks", "tasks")
        TimeField("Tasks reminder time (HH:MM)", "time_tasks", "09:00")
        TimeField("Quiet hours start", "quiet_start", "22:00")
        TimeField("Quiet hours end", "quiet_end", "08:00")
        Text("Most nudges per day", color = MyMavColors.TextMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { n -> KitChip(n.toString(), cap == n, { cap = n; MyMavProactive.setCap(n) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { MyMavProactive.deliver("recipe", true); msg = "Sent a test dinner nudge (it ignores time, quiet hours and the cap)." }) { Text("Test dinner nudge") }
            OutlinedButton(onClick = { MyMavProactive.deliver("tasks", true); msg = "Sent a test tasks nudge." }) { Text("Test tasks nudge") }
        }
        if (msg.isNotBlank()) Text(msg, color = MyMavColors.TextMuted, fontSize = 13.sp)
    }
}
