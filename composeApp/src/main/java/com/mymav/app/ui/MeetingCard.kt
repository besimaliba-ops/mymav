package com.mymav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.app.MyMavMeeting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings -> Meeting recorder: explicit start/stop, recording status, summaries and transcripts, retry. */
@Composable
internal fun MeetingCard() {
    var tick by remember { mutableIntStateOf(0) }
    var mic by remember { mutableStateOf(MyMavMeeting.micGranted()) }
    var open by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val prev = MyMavMeeting.onChanged
        MyMavMeeting.onChanged = { tick++ }
        onDispose { MyMavMeeting.onChanged = prev }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { mic = it }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.UK) }
    val recording = MyMavMeeting.recording()
    GlassCard {
        CardTitle("Meeting recorder", "Records only when you start it (say \"start recording the meeting\" or tap Start). A notification shows while it records. The summary and transcript are saved to Notepad.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!mic) OutlinedButton(onClick = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
            else if (!recording) OutlinedButton(onClick = { MyMavMeeting.start { r -> msg = r.text; tick++ } }) { Text("Start recording") }
            else OutlinedButton(onClick = { MyMavMeeting.stop { r -> msg = r.text; tick++ } }) { Text("Stop recording") }
        }
        if (msg.isNotBlank()) Text(msg, color = MyMavColors.TextMuted, fontSize = 13.sp)
        val list = MyMavMeeting.records().take(5)
        if (list.isEmpty()) Text("No recordings yet.", color = MyMavColors.TextMuted, fontSize = 13.sp)
        list.forEach { r ->
            Text("${fmt.format(Date(r.startedAt))} - ${r.status}", color = MyMavColors.Text, fontSize = 14.sp)
            if (r.detail.isNotBlank()) Text(r.detail, color = MyMavColors.TextMuted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.status == "Failed" || r.status == "Interrupted" || r.status == "Done") {
                    if (r.status != "Done") OutlinedButton(onClick = { MyMavMeeting.retry(r.id) }) { Text("Retry summary") }
                }
                if (r.summary.isNotBlank()) OutlinedButton(onClick = { open = if (open == r.id) null else r.id }) { Text(if (open == r.id) "Hide text" else "Show summary + transcript") }
            }
            if (open == r.id) {
                Text("Summary", color = MyMavColors.Accent, fontSize = 13.sp)
                Text(r.summary, color = MyMavColors.TextMuted, fontSize = 13.sp)
                Text("Transcript", color = MyMavColors.Accent, fontSize = 13.sp)
                Text(r.transcript, color = MyMavColors.TextMuted, fontSize = 13.sp)
            }
        }
    }
}
