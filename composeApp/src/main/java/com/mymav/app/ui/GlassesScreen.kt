package com.mymav.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymav.glasses.api.ConnectionState
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.KeyboardMode
import com.mymav.glasses.api.MiniAppRegistry

@Composable
fun GlassesScreen(
    session: GlassesSession,
    contentPadding: PaddingValues,
    onOpenMini: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by session.connectionState.collectAsState()
    val status by session.statusMessage.collectAsState()
    val host by session.simulatorHost.collectAsState()
    var note by remember { mutableStateOf("Preview shows what the glasses HUD will look like") }
    var askMyMavText by remember { mutableStateOf("") }
    var previewMode by remember { mutableStateOf(LensPreviewMode.Home) }
    var selectedAppId by remember {
        mutableStateOf(MiniAppRegistry.all().firstOrNull()?.descriptor?.id)
    }

    fun attach() {
        GlassesHomeController.attach(session, onOpenMiniApp = { id -> onOpenMini(id) })
    }

    MyMavShellBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_glasses")
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            ScreenHeader("Glasses", "Connection, controls and live HUD preview")

            // ---- connection ----
            GlassCard(modifier = Modifier.animateContentSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusDot(stateColor(state))
                    Text(
                        stateLabel(state),
                        style = MaterialTheme.typography.titleMedium,
                        color = MyMavColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        session.backendName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MyMavColors.TextFaint,
                    )
                }
                Text(
                    "ws://$host:8765/ws",
                    style = MaterialTheme.typography.bodySmall,
                    color = MyMavColors.TextMuted,
                )
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MyMavColors.TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                KitButtonRow {
                    KitButton(
                        "Connect sim",
                        onClick = {
                            com.mymav.glasses.api.SimulatorPrefs.setAutoConnect(context, true)
                            session.connectSimulator()
                            com.mymav.app.GlassesLinkService.start(context, retryNow = true)
                            note = "Connecting to simulator..."
                        },
                        kind = KitButtonKind.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    KitButton(
                        "Show home",
                        onClick = {
                            attach()
                            GlassesHomeController.showHome()
                            previewMode = LensPreviewMode.Home
                            note = "Glasses home pushed"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- Ask MyMav ----
            GlassCard {
                CardTitle(
                    "Ask MyMav",
                    "Summon on the glasses without gaze. Real glasses use a 1s look at MyMav.",
                )
                KitButton(
                    "Activate MyMav",
                    onClick = {
                        attach()
                        GlassesHomeController.summonMyMav()
                        previewMode = LensPreviewMode.Home
                        note = "MyMav summoned - AUDIO / TEXT / CANCEL on glasses"
                    },
                    kind = KitButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                KitTextField(
                    value = askMyMavText,
                    onValueChange = { askMyMavText = it },
                    label = "Command or question",
                    placeholder = "open Health, who are you, help",
                )
                KitButtonRow {
                    KitButton(
                        "Send",
                        onClick = {
                            val q = askMyMavText.trim()
                            if (q.isEmpty()) {
                                note = "Type a command first"
                            } else {
                                attach()
                                GlassesHomeController.showHome()
                                GlassesHomeController.summonMyMav(q)
                                previewMode = LensPreviewMode.Home
                                note = "Sent to MyMav - reply on glasses"
                            }
                        },
                        kind = KitButtonKind.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    KitButton(
                        "Clear",
                        onClick = { askMyMavText = "" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            var openGroup by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
            fun toggle(k: String) { openGroup = if (openGroup == k) "" else k }
            SectionLabel("Tools", modifier = Modifier.padding(start = 4.dp))
            SettingsGroup("Try on glasses", "Keyboards, page turns, open widget", openGroup == "try", { toggle("try") }) {
                GlassCard {
                    CardTitle("Try on glasses", "Drive the HUD without gaze hardware")
                    KitButtonRow {
                        KitButton(
                            "Gaze keyboard",
                            onClick = {
                                attach()
                                GlassesHomeController.openKeyboard(
                                    title = "Demo keyboard",
                                    initial = "",
                                    onDone = { text ->
                                        note = "Keyboard done: $text"
                                        GlassesHomeController.showHome()
                                    },
                                    onCancel = {
                                        note = "Keyboard cancelled"
                                        GlassesHomeController.showHome()
                                    },
                                )
                                previewMode = LensPreviewMode.Keyboard
                                note = "Gaze keyboard on lens (ABC / emoji / search)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        KitButton(
                            "Emoji keyboard",
                            onClick = {
                                attach()
                                GlassesHomeController.openKeyboard(
                                    title = "Draft",
                                    initial = "Hello, ",
                                    initialMode = KeyboardMode.Categories,
                                    onDone = { text ->
                                        note = "Keyboard done: $text"
                                        GlassesHomeController.showHome()
                                    },
                                    onCancel = {
                                        note = "Keyboard cancelled"
                                        GlassesHomeController.showHome()
                                    },
                                )
                                previewMode = LensPreviewMode.Keyboard
                                note = "Emoji categories - VTXT / VNOTE"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    KitButtonRow {
                        KitButton(
                            "Page back",
                            onClick = {
                                attach()
                                GlassesHomeController.showHome()
                                GlassesHomeController.simulateSideGazeDwell(next = false)
                                note = "Simulated 1s gaze left (previous page)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        KitButton(
                            "Page next",
                            onClick = {
                                attach()
                                GlassesHomeController.showHome()
                                GlassesHomeController.simulateSideGazeDwell(next = true)
                                note = "Simulated 1s gaze right (next page)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    KitButton(
                        "Open focused widget",
                        onClick = {
                            attach()
                            GlassesHomeController.showHome()
                            GlassesHomeController.simulateOpenGazeDwell()
                            note = "Simulated 1s gaze on focused widget"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

            }
            SettingsGroup("HUD preview", "640 x 400 lens mock", openGroup == "prev", { toggle("prev") }) {
                GlassCard {
                    CardTitle("HUD preview", "640 x 400 lens mock")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KitChip("Home", previewMode == LensPreviewMode.Home, { previewMode = LensPreviewMode.Home })
                        KitChip("Apps", previewMode == LensPreviewMode.App, { previewMode = LensPreviewMode.App })
                        KitChip("Keyboard", previewMode == LensPreviewMode.Keyboard, { previewMode = LensPreviewMode.Keyboard })
                    }
                    if (previewMode == LensPreviewMode.App) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            MiniAppRegistry.all().forEach { app ->
                                KitChip(
                                    app.descriptor.title,
                                    selectedAppId == app.descriptor.id,
                                    { selectedAppId = app.descriptor.id },
                                )
                            }
                        }
                    }
                    HudLensPreview(
                        mode = previewMode,
                        selectedAppId = selectedAppId,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp)),
                    )
                }

            }
            SettingsGroup("Diagnostics", "State: $state", openGroup == "diag", { toggle("diag") }) {
                GlassCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                    SectionLabel("Diagnostics")
                    MetaLine("State", "$state")
                    MetaLine("Last HUD", status)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting..."
    ConnectionState.Ready -> "Ready to connect"
    ConnectionState.Initializing -> "Starting SDK..."
    ConnectionState.Failed -> "Connection failed"
    ConnectionState.Stopped -> "Stopped"
    ConnectionState.Idle -> "Idle"
}

private fun stateColor(state: ConnectionState) = when (state) {
    ConnectionState.Connected -> MyMavColors.Good
    ConnectionState.Connecting, ConnectionState.Initializing -> MyMavColors.Amber
    ConnectionState.Failed -> MyMavColors.Bad
    else -> MyMavColors.TextFaint
}
