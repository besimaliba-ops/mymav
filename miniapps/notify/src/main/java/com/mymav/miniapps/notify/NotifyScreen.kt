package com.mymav.miniapps.notify

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.NotifyChannelFilter
import com.mymav.miniapps.notify.service.NotifyCompanionService
import com.mymav.miniapps.notify.service.NotifyInbox
import com.mymav.miniapps.notify.service.NotifyNotificationListenerService

@Composable
fun NotifyScreen(session: GlassesSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by session.statusMessage.collectAsState()
    var localNote by remember {
        mutableStateOf("Keep companion running, then lock the phone — WhatsApp / Instagram still reach the glasses.")
    }
    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var channelFilter by remember { mutableStateOf(NotifyInbox.channelFilter) }
    var inboxTick by remember { mutableStateOf(0) }
    val inboxItems = remember(channelFilter, inboxTick) { NotifyInbox.items() }

    DisposableEffect(session) {
        NotifySessionBridge.attach(session)
        onDispose { }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listenerEnabled = isNotificationListenerEnabled(context)
                batteryUnrestricted = isBatteryUnrestricted(context)
                channelFilter = NotifyInbox.channelFilter
                inboxTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Socials", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Mirrors WhatsApp / Instagram (and WhatsApp Business) onto the glasses HUD. " +
                "Spotify lyrics live in the Spotify mini-app.",
        )
        Text("Glasses: $status", style = MaterialTheme.typography.bodySmall)
        Text(localNote, style = MaterialTheme.typography.bodySmall)

        Text("Keep running in background", style = MaterialTheme.typography.titleMedium)
        Text(
            "After Start, leave the sticky “MyMav Notify · keep running” notification. " +
                "You can close the MyMav UI and lock the phone — companion + notification access stay alive. " +
                "On aggressive OEMs (Xiaomi / Huawei / Samsung), also allow unrestricted battery.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("1 · Permissions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Notification access: ${if (listenerEnabled) "ON" else "OFF — enable MyMav Notify mirror"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                localNote = "Turn on “MyMav Notify mirror” (or MyMav), then return here"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open notification access") }
        Text(
            "Battery: ${if (batteryUnrestricted) "unrestricted / OK" else "may kill companion — set Unrestricted"}",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                openBatterySettings(context)
                localNote = "Set MyMav battery usage to Unrestricted / Don’t optimize"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Battery / OEM allow background") }

        Text("2 · Companion service", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                NotifySessionBridge.attach(session)
                NotifyCompanionService.start(context)
                NotifyCompanionService.hydrateActive(context)
                inboxTick++
                localNote = "Companion started — sticky notification should stay while locked"
            }) { Text("Start companion") }
            OutlinedButton(onClick = {
                NotifyCompanionService.stop(context)
                localNote = "Companion stopped"
            }) { Text("Stop") }
        }

        Text("Chats · WhatsApp / Instagram", style = MaterialTheme.typography.titleMedium)
        Text(
            "Browse previous chats that still have an active shade notification (not archived). " +
                "Full WA/IG history is not readable without private app access — MyMav uses notification inbox only.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                NotifyChannelFilter.All to "All",
                NotifyChannelFilter.WhatsApp to "WhatsApp",
                NotifyChannelFilter.Instagram to "Instagram",
            ).forEach { (filter, label) ->
                FilterChip(
                    selected = channelFilter == filter,
                    onClick = {
                        channelFilter = filter
                        NotifyCompanionService.setChannelFilter(context, filter)
                        inboxTick++
                        localNote = "Filter · $label"
                    },
                    label = { Text(label) },
                )
            }
        }
        OutlinedButton(
            onClick = {
                NotifyCompanionService.hydrateActive(context)
                inboxTick++
                localNote = "Synced active shade notifications"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh active chats from shade") }
        if (inboxItems.isEmpty()) {
            Text(
                "No chats yet — start companion, enable notification access, or inject a test message.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            inboxItems.asReversed().forEach { chat ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            NotifyInbox.focusKey(chat.notificationKey)
                            GlassesHomeController.attach(session)
                            GlassesHomeController.markMiniAppOpen("notify")
                            NotifyCompanionService.reshowPending(context)
                            inboxTick++
                            localNote = "Opened ${chat.sender} on glasses"
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        "${chat.sourceApp.shortLabel} · ${chat.sender}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        chat.body.take(120),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Test with real WhatsApp", style = MaterialTheme.typography.titleMedium)
        Text(
            "Preferred: physical phone with WhatsApp / Instagram installed. " +
                "Enable notification access → Start companion → connect glasses/sim → " +
                "leave MyMav running → lock phone → send a WA/IG message → inbox shows each chat. " +
                "↑↓ select · TAP open · TAP reply · keyboard BACK returns to the message. " +
                "Group summaries like “N messages from M chats” are ignored.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("3 · Inject test (no WA/IG app needed)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use these on emulator or when you cannot install WhatsApp/Instagram. " +
                "Same HUD path as a real NotificationListener event.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                NotifySessionBridge.attach(session)
                NotifyCompanionService.demoNotification(context)
                localNote = "Injected test WhatsApp → check glasses / simulator HUD"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Inject test WhatsApp notification") }
        Button(
            onClick = {
                NotifySessionBridge.attach(session)
                NotifyCompanionService.demoInstagramNotification(context)
                localNote = "Injected test Instagram → check glasses / simulator HUD"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Inject test Instagram notification") }
        OutlinedButton(
            onClick = {
                NotifySessionBridge.attach(session)
                GlassesHomeController.attach(session)
                GlassesHomeController.markMiniAppOpen("notify")
                GlassesHomeController.openKeyboard(
                    title = "Reply · Notify",
                    initial = "",
                    doneLabel = "Send",
                    onDone = { text ->
                        localNote = "Keyboard reply: $text"
                        NotifyCompanionService.demoNotification(context)
                    },
                    onCancel = { localNote = "Keyboard cancelled" },
                )
                localNote = "Shared gaze keyboard on lens (reply)"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demo gaze keyboard (reply)") }

        Button(onClick = onBack) { Text("Back to home") }
    }
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    val expected = ComponentName(context, NotifyNotificationListenerService::class.java)
    return flat.split(':').any { ComponentName.unflattenFromString(it) == expected } ||
        flat.contains(context.packageName)
}

private fun isBatteryUnrestricted(context: android.content.Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: android.content.Context) {
    val pkg = context.packageName
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$pkg")
    }
    val opened = runCatching { context.startActivity(request) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                },
            )
        }
    }
}
