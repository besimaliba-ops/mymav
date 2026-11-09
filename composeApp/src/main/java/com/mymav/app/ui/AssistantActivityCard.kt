package com.mymav.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymav.glasses.api.AssistantLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings -> Assistant activity: every answer MyMav gave, how it was produced (retrieved from the web, retrieved
 * from a data service, AI answer that was not searched, calculated, or not completed) and the source names with links.
 */
@Composable
fun AssistantActivityCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val previous = AssistantLog.onChanged
        AssistantLog.onChanged = { tick++ }
        onDispose { AssistantLog.onChanged = previous }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val entries = AssistantLog.recent().take(8)
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.UK) }
    GlassCard {
        CardTitle(
            "Assistant activity",
            "What MyMav answered, how it got the answer, and its sources. Retrieved answers have links; AI answers that were not searched are labelled.",
        )
        // Typed requests go through exactly the same assistant as the avatar's voice and the glasses keyboard.
        var typed by remember { mutableStateOf("") }
        var waitingSince by remember { mutableStateOf(0L) }       // 0 = nothing in flight
        var seenFirst by remember { mutableStateOf<Any?>(AssistantLog.recent().firstOrNull()) }
        var notice by remember { mutableStateOf("") }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        // A request finishes when the log gets a new entry; if nothing arrives in 75 s say so and let the user try again.
        LaunchedEffect(tick, waitingSince) {
            if (waitingSince != 0L && AssistantLog.recent().firstOrNull() !== seenFirst) { waitingSince = 0L; notice = "" }
        }
        LaunchedEffect(waitingSince) {
            if (waitingSince != 0L) {
                kotlinx.coroutines.delay(75_000L)
                if (waitingSince != 0L) { waitingSince = 0L; notice = "No answer came back in 75 seconds. Check your connection and try again." }
            }
        }
        fun submit() {
            val q = typed.trim()
            if (q.isEmpty() || waitingSince != 0L) return
            typed = ""; notice = ""
            seenFirst = AssistantLog.recent().firstOrNull()
            waitingSince = System.currentTimeMillis()
            keyboard?.hide(); focus.clearFocus()
            com.mymav.glasses.api.GlassesHomeController.handleAssistantUtterance(q)
        }
        OutlinedTextField(
            value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth().testTag("assistant_ask_field"),
            singleLine = true, label = { Text("Ask MyMav") }, enabled = waitingSince == 0L,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waitingSince == 0L) {
                OutlinedButton(enabled = typed.isNotBlank(), onClick = { submit() }, modifier = Modifier.testTag("assistant_ask_button")) { Text("Ask") }
            } else {
                OutlinedButton(onClick = {
                    com.mymav.glasses.api.GlassesHomeController.cancelAssistant()
                    waitingSince = 0L; notice = "Cancelled."
                }, modifier = Modifier.testTag("assistant_ask_cancel")) { Text("Cancel (working...)") }
            }
        }
        Text(
            if (notice.isNotBlank()) notice else "The answer shows on the glasses and in the list below.",
            color = MyMavColors.TextMuted, fontSize = 12.sp, modifier = Modifier.testTag("assistant_ask_note"),
        )
        if (entries.isEmpty()) {
            Text("Nothing yet. Ask the avatar a question.", color = MyMavColors.TextMuted, fontSize = 13.sp, modifier = Modifier.testTag("assistant_activity_empty"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("assistant_activity")) {
            entries.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(e.query, color = MyMavColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "${e.kind.label} - ${fmt.format(Date(e.at))}",
                        color = if (e.kind == AssistantLog.Kind.Error) MyMavColors.TextMuted else MyMavColors.Accent,
                        fontSize = 12.sp,
                    )
                    Text(e.answer, color = MyMavColors.TextMuted, fontSize = 13.sp, maxLines = 6)
                    if (e.note.isNotBlank()) Text(e.note, color = MyMavColors.TextFaint, fontSize = 12.sp)
                    e.sources.forEach { s ->
                        if (s.url.isBlank()) {
                            // A reference to something on this phone (e.g. a saved note), not a web link.
                            Text(s.name, color = MyMavColors.TextMuted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                        } else Text(
                            s.name,
                            color = MyMavColors.Accent,
                            fontSize = 13.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                },
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            Row { OutlinedButton(onClick = { AssistantLog.clear() }) { Text("Clear activity") } }
        }
    }
}
