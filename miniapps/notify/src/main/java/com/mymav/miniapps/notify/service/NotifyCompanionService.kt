package com.mymav.miniapps.notify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mymav.glasses.api.ConnectionState
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HomeWidgetPreviewStore
import com.mymav.glasses.api.HudAccent
import com.mymav.glasses.api.HudReplyState
import com.mymav.glasses.api.NotificationHudActions
import com.mymav.glasses.api.NotifyChannelFilter
import com.mymav.glasses.api.NotifyStage
import com.mymav.glasses.api.ShellSessionHolder
import com.mymav.glasses.api.SimulatorPrefs
import com.mymav.miniapps.notify.NotifySessionBridge
import com.mymav.miniapps.notify.model.CompanionSourceApp
import com.mymav.miniapps.notify.model.IncomingNotification
import com.mymav.miniapps.notify.model.NotificationMediaKind
import com.mymav.miniapps.notify.ui.GazeIntentController
import com.mymav.miniapps.notify.ui.HudBitmapEncoder
import com.mymav.miniapps.notify.ui.NoOpLineOfSightSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground companion for Notify mini-app (WhatsApp / Instagram only).
 * Sticky FGS + [android:stopWithTask]=false so swipe-away / lock screen keep
 * the notification mirror and glasses session alive.
 */
class NotifyCompanionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var latestNotification: IncomingNotification? = null
    private var pendingVoiceReply: PendingVoiceReply? = null
    private var notificationMessagePage = 0
    private var lastNotificationPackage: String? = null
    private var currentReplyState = HudReplyState.Idle
    private var browseStage = NotifyStage.Inbox
    private val voiceReplyTranscriber = VoiceReplyTranscriber()
    private val lineOfSightSource = NoOpLineOfSightSource()
    private val gazeIntentController = GazeIntentController(
        onDownwardGlance = ::handleDownwardGaze,
        onUpwardGlance = ::handleUpwardGaze,
        onReplyTargetGaze = ::handleReplyTargetGaze,
        onMusicControlGaze = { /* Spotify mini-app owns music gaze */ },
    )

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        createNotificationChannel()
        promoteToForeground("MyMav Notify running — WhatsApp / Instagram")
        ensureGlassesSession()
        lineOfSightSource.start { h, v -> gazeIntentController.onLineOfSightSample(h, v) }
        updateNotification("Waiting for WhatsApp / Instagram · keep this notification")
        publishNotifyHomeCounts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_NOTIFICATION -> handleIncomingNotification(intent)
            ACTION_VOICE_REPLY -> handleVoiceReply(intent)
            ACTION_DEMO_NOTIFICATION -> injectDemoNotification()
            ACTION_DEMO_INSTAGRAM -> injectDemoInstagramNotification()
            ACTION_INJECT_TEST_WHATSAPP -> injectDemoNotification()
            ACTION_INJECT_TEST_INSTAGRAM -> injectDemoInstagramNotification()
            ACTION_DEMO_GAZE_REPLY -> handleReplyTargetGaze()
            ACTION_RESHOW_PENDING -> {
                GlassesHomeController.markMiniAppOpen("notify")
                reshowPendingNotification()
            }
            ACTION_DISMISS_PENDING -> dismissPendingNotification()
            ACTION_HYDRATE_ACTIVE -> {
                NotifyNotificationListenerService.requestHydrateActive(this)
                refreshStickyStatus("Synced active notifications")
            }
            ACTION_SET_CHANNEL -> {
                val raw = intent.getStringExtra(EXTRA_CHANNEL_FILTER).orEmpty()
                val filter = NotifyChannelFilter.entries.firstOrNull {
                    it.name.equals(raw, ignoreCase = true)
                } ?: NotifyChannelFilter.All
                NotifyInbox.setChannelFilter(filter)
                browseStage = NotifyStage.Inbox
                if (GlassesHomeController.acceptsNotifyHud()) {
                    showNotificationHud(HudReplyState.Idle)
                }
                refreshStickyStatus()
            }
            ACTION_ENSURE_RUNNING -> {
                NotifyCompanionPrefs.setWanted(this, true)
                ensureGlassesSession()
                NotifyNotificationListenerService.requestHydrateActive(this)
                refreshStickyStatus()
            }
            ACTION_STOP -> {
                NotifyCompanionPrefs.setWanted(this, false)
                latestNotification = null
                pendingSummary = null
                NotifyInbox.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                NotifyCompanionPrefs.setWanted(this, true)
                ensureGlassesSession()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Recents swipe — keep companion if user still wants it.
        if (NotifyCompanionPrefs.isWanted(this)) {
            Log.i(TAG, "onTaskRemoved — restarting sticky companion")
            val restart = Intent(applicationContext, NotifyCompanionService::class.java)
                .setAction(ACTION_ENSURE_RUNNING)
            applicationContext.startForegroundService(restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        lineOfSightSource.stop()
        serviceScope.cancel()
        if (!NotifyCompanionPrefs.isWanted(this)) {
            NotifySessionBridge.clearHud()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureGlassesSession() {
        val session = ShellSessionHolder.get() ?: return
        NotifySessionBridge.attach(session)
        val host = SimulatorPrefs.host(this)
        session.setSimulatorHost(host)
        val state = session.connectionState.value
        if (state != ConnectionState.Connected && state != ConnectionState.Connecting) {
            Log.i(TAG, "Reconnecting simulator host=$host state=$state")
            runCatching { session.connectSimulator() }
                .onFailure { Log.w(TAG, "Simulator reconnect failed", it) }
        }
    }

    private fun handleIncomingNotification(intent: Intent) {
        val sourceApp = CompanionSourceApp.fromPackageName(
            intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty(),
        ) ?: return
        val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY)
            .orEmpty()
            .ifBlank { "${sourceApp.packageName}:${System.currentTimeMillis()}" }
        val incoming = IncomingNotification(
            sourceApp = sourceApp,
            sender = intent.getStringExtra(EXTRA_SENDER).orEmpty().ifBlank { sourceApp.displayName },
            body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            notificationKey = key,
            mediaKind = intent.getStringExtra(EXTRA_MEDIA_KIND)
                ?.let { runCatching { NotificationMediaKind.valueOf(it) }.getOrNull() }
                ?: NotificationMediaKind.None,
            mediaKey = intent.getStringExtra(EXTRA_MEDIA_KEY) ?: key,
        )
        NotifyInbox.upsert(incoming)
        latestNotification = incoming
        pendingSummary = "${NotifyInbox.size()} chats · ${incoming.sender}"
        pendingVoiceReply = null
        notificationMessagePage = 0
        currentReplyState = HudReplyState.Idle
        browseStage = NotifyStage.Inbox
        lastNotificationPackage = sourceApp.packageName
        refreshStickyStatus()
        showNotificationHud(HudReplyState.Idle)
    }

    private fun injectDemoNotification() {
        val demos = listOf(
            Triple("Alex", "Hey — are you free for coffee later?", "demo-wa-alex"),
            Triple("Sam", "Running 10 mins late, start without me.", "demo-wa-sam"),
            Triple("Jordan", "Can you send the photos from Sunday?", "demo-wa-jordan"),
        )
        demos.forEach { (sender, body, key) ->
            handleIncomingNotification(
                Intent(ACTION_SHOW_NOTIFICATION).apply {
                    putExtra(EXTRA_SOURCE_PACKAGE, CompanionSourceApp.WhatsApp.packageName)
                    putExtra(EXTRA_SENDER, sender)
                    putExtra(EXTRA_BODY, body)
                    putExtra(EXTRA_NOTIFICATION_KEY, key)
                    putExtra(EXTRA_MEDIA_KIND, NotificationMediaKind.None.name)
                    putExtra(EXTRA_MEDIA_KEY, key)
                },
            )
        }
    }

    private fun injectDemoInstagramNotification() {
        handleIncomingNotification(
            Intent(ACTION_SHOW_NOTIFICATION).apply {
                putExtra(EXTRA_SOURCE_PACKAGE, CompanionSourceApp.Instagram.packageName)
                putExtra(EXTRA_SENDER, "Maya")
                putExtra(EXTRA_BODY, "Loved your story — Bali looks unreal. Reply when you can?")
                putExtra(EXTRA_NOTIFICATION_KEY, "demo-ig-maya")
                putExtra(EXTRA_MEDIA_KIND, NotificationMediaKind.None.name)
                putExtra(EXTRA_MEDIA_KEY, "demo-ig-maya")
            },
        )
    }

    private fun handleVoiceReply(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
            ?: lastNotificationPackage
            ?: return
        val text = voiceReplyTranscriber.transcribe(
            intent.getStringExtra(EXTRA_VOICE_PAYLOAD).orEmpty(),
        )
        if (text.isBlank()) return
        pendingVoiceReply = PendingVoiceReply(packageName = packageName, text = text)
        updateNotification("Voice reply ready. Confirm send.")
        showNotificationHud(HudReplyState.ConfirmSend, text)
    }

    private fun showNotificationHud(
        replyState: HudReplyState,
        draftReply: String? = pendingVoiceReply?.text,
    ) {
        if (!GlassesHomeController.acceptsNotifyHud() && replyState == HudReplyState.Idle) {
            // Still update sticky; don't steal home board unless Notify is open.
            refreshStickyStatus()
            return
        }
        val notification = NotifyInbox.focused() ?: latestNotification ?: return
        latestNotification = notification
        currentReplyState = replyState
        val inboxCount = NotifyInbox.size().coerceAtLeast(1)
        val inboxIndex = NotifyInbox.focusedIndex() + 1
        val accent = when (notification.sourceApp) {
            CompanionSourceApp.WhatsApp -> HudAccent.WhatsAppGreen
            CompanionSourceApp.Instagram -> HudAccent.InstagramRed
        }
        val accentArgb = when (notification.sourceApp) {
            CompanionSourceApp.WhatsApp -> Color.parseColor("#25D366")
            CompanionSourceApp.Instagram -> Color.parseColor("#E1306C")
        }
        val media = NotificationMediaStore.get(notification.mediaKey)
        val avatar = HudBitmapEncoder.fromAvatar(media?.avatar, sizePx = 80)
            ?: HudBitmapEncoder.contactAvatar(notification.sender, accentArgb, sizePx = 80)
        val mediaEncoded = if (notification.hasMedia && browseStage == NotifyStage.Detail) {
            HudBitmapEncoder.fromThumbnail(media?.thumbnail, targetW = 176, targetH = 136)
        } else {
            null
        }
        val filterLabel = when (NotifyInbox.channelFilter) {
            NotifyChannelFilter.All -> "ALL"
            NotifyChannelFilter.WhatsApp -> "WA"
            NotifyChannelFilter.Instagram -> "IG"
        }
        NotifySessionBridge.replaceHud(
            GlassesHud.Notification(
                appLabel = "${notification.sourceApp.displayName} · $filterLabel",
                accent = accent,
                sender = notification.sender,
                body = notification.body,
                messagePage = notificationMessagePage,
                replyState = replyState,
                draftReply = draftReply,
                hasMedia = notification.hasMedia && mediaEncoded != null,
                mediaKindLabel = notification.mediaKind.label.uppercase(),
                stage = browseStage,
                inboxIndex = inboxIndex,
                inboxCount = inboxCount,
                channelFilter = NotifyInbox.channelFilter,
                avatarImageBytes = avatar.bytes,
                avatarImageWidth = avatar.width,
                avatarImageHeight = avatar.height,
                mediaImageBytes = mediaEncoded?.bytes,
                mediaImageWidth = mediaEncoded?.width ?: 0,
                mediaImageHeight = mediaEncoded?.height ?: 0,
                actions = NotificationHudActions(
                    onReplyRequested = {
                        if (browseStage == NotifyStage.Inbox) {
                            openSelectedMessage()
                        } else {
                            openGazeKeyboardForReply(notification.sender)
                        }
                    },
                    onSendConfirmed = ::confirmPendingVoiceReply,
                    onScrollUp = ::scrollNotificationUp,
                    onScrollDown = ::scrollNotificationDown,
                    onDismissRequested = ::dismissPendingNotification,
                    onOpenSelected = ::openSelectedMessage,
                    onBackToInbox = ::backToInbox,
                    onCycleChannelFilter = ::cycleChannelFilter,
                    onKeyboardRequested = {
                        if (browseStage == NotifyStage.Detail) {
                            openGazeKeyboardForReply(notification.sender)
                        } else {
                            openSelectedMessage()
                        }
                    },
                    onHomeRequested = {
                        pendingVoiceReply = null
                        GlassesHomeController.requestHomeFromApp()
                        refreshStickyStatus()
                    },
                    onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
                ),
            ),
        )
        Log.i(
            TAG,
            "Notification HUD: ${notification.sender} stage=$browseStage $inboxIndex/$inboxCount reply=$replyState",
        )
    }

    private fun openSelectedMessage() {
        val focused = NotifyInbox.focused() ?: return
        latestNotification = focused
        lastNotificationPackage = focused.sourceApp.packageName
        browseStage = NotifyStage.Detail
        notificationMessagePage = 0
        pendingVoiceReply = null
        showNotificationHud(HudReplyState.Idle)
        refreshStickyStatus("Opened · ${focused.sender}")
    }

    private fun backToInbox() {
        pendingVoiceReply = null
        browseStage = NotifyStage.Inbox
        notificationMessagePage = 0
        showNotificationHud(HudReplyState.Idle)
        refreshStickyStatus()
    }

    private fun cycleChannelFilter() {
        val next = NotifyInbox.cycleChannelFilter()
        browseStage = NotifyStage.Inbox
        notificationMessagePage = 0
        pendingVoiceReply = null
        showNotificationHud(HudReplyState.Idle)
        refreshStickyStatus("Filter · ${next.name}")
    }

    private fun reshowPendingNotification(): Boolean {
        val pending = NotifyInbox.focused() ?: latestNotification
        if (pending == null || NotifyInbox.isEmpty()) {
            pendingSummary = null
            refreshStickyStatus("No pending message — waiting for WhatsApp / Instagram")
            return false
        }
        ensureGlassesSession()
        latestNotification = pending
        pendingVoiceReply = null
        currentReplyState = HudReplyState.Idle
        browseStage = NotifyStage.Inbox
        showNotificationHud(HudReplyState.Idle)
        refreshStickyStatus()
        Log.i(TAG, "Reshow pending inbox (${NotifyInbox.size()}): ${pending.sender}")
        return true
    }

    private fun dismissPendingNotification() {
        val focused = NotifyInbox.focused()
        if (focused != null) {
            NotifyInbox.remove(focused.notificationKey)
        }
        pendingVoiceReply = null
        latestNotification = NotifyInbox.focused()
        pendingSummary = if (latestNotification != null) {
            "${NotifyInbox.size()} chats · ${latestNotification!!.sender}"
        } else {
            null
        }
        currentReplyState = HudReplyState.Idle
        browseStage = NotifyStage.Inbox
        if (NotifyInbox.isEmpty()) {
            GlassesHomeController.requestHomeFromApp()
            refreshStickyStatus("Waiting for WhatsApp / Instagram · keep this notification")
        } else {
            showNotificationHud(HudReplyState.Idle)
            refreshStickyStatus()
        }
        Log.i(TAG, "Dismissed one pending; remaining=${NotifyInbox.size()}")
    }

    private fun refreshStickyStatus(override: String? = null) {
        val pending = NotifyInbox.focused() ?: latestNotification
        val text = override ?: if (pending != null) {
            "Inbox ${NotifyInbox.size()} · ${pending.sender} — Show on glasses"
        } else {
            "Waiting for WhatsApp / Instagram · keep this notification"
        }
        updateNotification(text)
        publishNotifyHomeCounts()
    }

    private var lastNotifyHomeKey: String? = null

    /** Home Notify widget: WA / IG counts (not a single unread total). */
    private fun publishNotifyHomeCounts() {
        val wa = NotifyInbox.whatsAppCount()
        val ig = NotifyInbox.instagramCount()
        val key = "$wa|$ig"
        val changed = key != lastNotifyHomeKey
        lastNotifyHomeKey = key
        HomeWidgetPreviewStore.put(
            "notify",
            HomeWidgetPreviewStore.Preview(
                title = "Socials",
                subtitle = "",
                metric = "WA:$wa",
                metricDetail = "IG:$ig",
            ),
        )
        if (changed && GlassesHomeController.isShowingHome()) {
            GlassesHomeController.showHome()
        }
    }

    private fun openGazeKeyboardForReply(senderLabel: String) {
        val notification = NotifyInbox.focused() ?: latestNotification ?: return
        val packageName = notification.sourceApp.packageName
        browseStage = NotifyStage.Detail
        GlassesHomeController.openKeyboard(
            title = "Reply · $senderLabel",
            initial = "",
            doneLabel = "Send",
            onDone = { text ->
                if (text.isBlank()) {
                    browseStage = NotifyStage.Detail
                    showNotificationHud(HudReplyState.Idle)
                    return@openKeyboard
                }
                pendingVoiceReply = PendingVoiceReply(
                    packageName = packageName,
                    text = text.trim(),
                    notificationKey = notification.notificationKey,
                )
                browseStage = NotifyStage.Detail
                showNotificationHud(HudReplyState.ConfirmSend, text.trim())
                refreshStickyStatus("Keyboard reply ready · confirm send")
            },
            onCancel = {
                // BACK from keyboard → message detail (not home).
                browseStage = NotifyStage.Detail
                showNotificationHud(HudReplyState.Idle)
                refreshStickyStatus("Back to ${notification.sender}")
            },
        )
        refreshStickyStatus("Keyboard · BACK returns to ${notification.sender}")
    }

    private fun confirmPendingVoiceReply() {
        val pending = pendingVoiceReply ?: return
        val sent = NotificationReplyStore.replyTo(
            this,
            pending.packageName,
            pending.text,
            pending.notificationKey,
        )
        pendingVoiceReply = null
        browseStage = NotifyStage.Detail
        showNotificationHud(if (sent) HudReplyState.Sent else HudReplyState.Cancelled)
        refreshStickyStatus(if (sent) "Reply sent · still in inbox" else "No reply action available")
        if (sent) {
            serviceScope.launch {
                delay(2_000)
                if (latestNotification != null && currentReplyState == HudReplyState.Sent) {
                    browseStage = NotifyStage.Detail
                    showNotificationHud(HudReplyState.Idle)
                    refreshStickyStatus()
                }
            }
        }
    }

    private fun handleDownwardGaze() {
        scrollNotificationDown()
    }

    private fun handleUpwardGaze() {
        scrollNotificationUp()
    }

    private fun handleReplyTargetGaze() {
        if (pendingVoiceReply != null) {
            confirmPendingVoiceReply()
        } else if (browseStage == NotifyStage.Inbox) {
            openSelectedMessage()
        } else if (latestNotification != null) {
            openGazeKeyboardForReply(latestNotification!!.sender)
        }
    }

    private fun scrollNotificationDown() {
        if (browseStage == NotifyStage.Inbox) {
            NotifyInbox.focusNext()?.let {
                latestNotification = it
                notificationMessagePage = 0
                showNotificationHud(HudReplyState.Idle)
            }
            return
        }
        val notification = latestNotification ?: return
        val pageSize = if (notification.hasMedia) 55 else 90
        val maxPage = ((notification.body.replace(Regex("\\s+"), " ").trim().length - 1)
            .coerceAtLeast(0) / pageSize)
        if (notificationMessagePage < maxPage) {
            notificationMessagePage += 1
            showNotificationHud(HudReplyState.Idle)
        } else {
            // End of this message body → flick to the next message (any sender).
            NotifyInbox.focusNext()?.let {
                latestNotification = it
                notificationMessagePage = 0
                browseStage = NotifyStage.Detail
                showNotificationHud(HudReplyState.Idle)
            }
        }
    }

    private fun scrollNotificationUp() {
        if (browseStage == NotifyStage.Inbox) {
            NotifyInbox.focusPrev()?.let {
                latestNotification = it
                notificationMessagePage = 0
                showNotificationHud(HudReplyState.Idle)
            }
            return
        }
        if (notificationMessagePage > 0) {
            notificationMessagePage -= 1
            showNotificationHud(HudReplyState.Idle)
        } else {
            // Previous message, or back to inbox list when at the first.
            val prev = NotifyInbox.focusPrev()
            if (prev != null && prev.focusId != latestNotification?.focusId) {
                latestNotification = prev
                notificationMessagePage = 0
                browseStage = NotifyStage.Detail
                showNotificationHud(HudReplyState.Idle)
            } else {
                backToInbox()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MyMav Notify companion",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Notify + glasses connection alive while the phone is locked"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        // dataSync only — connectedDevice needs BT/NFC/USB runtime perms and crashes the process on API 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reshowIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, NotifyCompanionService::class.java).setAction(ACTION_RESHOW_PENDING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, NotifyCompanionService::class.java).setAction(ACTION_DISMISS_PENDING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NotifyCompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hasInbox = !NotifyInbox.isEmpty()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                if (hasInbox) {
                    "MyMav Notify · ${NotifyInbox.size()} pending"
                } else {
                    "MyMav Notify · keep running"
                },
            )
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(if (hasInbox) reshowIntent else openAppIntent)
        if (hasInbox) {
            builder.addAction(android.R.drawable.ic_menu_view, "Show on glasses", reshowIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissIntent)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
        return builder.build()
    }

    companion object {
        private const val TAG = "MyMavNotify"
        private const val CHANNEL_ID = "mymav_notify"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_SHOW_NOTIFICATION = "com.mymav.miniapps.notify.SHOW_NOTIFICATION"
        const val ACTION_VOICE_REPLY = "com.mymav.miniapps.notify.VOICE_REPLY"
        const val ACTION_DEMO_NOTIFICATION = "com.mymav.miniapps.notify.DEMO_NOTIFICATION"
        const val ACTION_DEMO_INSTAGRAM = "com.mymav.miniapps.notify.DEMO_INSTAGRAM"
        const val ACTION_INJECT_TEST_WHATSAPP = "com.mymav.miniapps.notify.INJECT_TEST_WHATSAPP"
        const val ACTION_INJECT_TEST_INSTAGRAM = "com.mymav.miniapps.notify.INJECT_TEST_INSTAGRAM"
        const val ACTION_DEMO_GAZE_REPLY = "com.mymav.miniapps.notify.DEMO_GAZE_REPLY"
        const val ACTION_RESHOW_PENDING = "com.mymav.miniapps.notify.RESHOW_PENDING"
        const val ACTION_DISMISS_PENDING = "com.mymav.miniapps.notify.DISMISS_PENDING"
        const val ACTION_ENSURE_RUNNING = "com.mymav.miniapps.notify.ENSURE_RUNNING"
        const val ACTION_HYDRATE_ACTIVE = "com.mymav.miniapps.notify.HYDRATE_ACTIVE"
        const val ACTION_SET_CHANNEL = "com.mymav.miniapps.notify.SET_CHANNEL"
        const val ACTION_STOP = "com.mymav.miniapps.notify.STOP"

        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_BODY = "body"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_MEDIA_KIND = "media_kind"
        const val EXTRA_MEDIA_KEY = "media_key"
        const val EXTRA_VOICE_PAYLOAD = "voice_payload"
        const val EXTRA_CHANNEL_FILTER = "channel_filter"

        fun start(context: Context) {
            appContext = context.applicationContext
            NotifyCompanionPrefs.setWanted(context, true)
            context.startForegroundService(
                Intent(context, NotifyCompanionService::class.java)
                    .setAction(ACTION_ENSURE_RUNNING),
            )
        }

        fun stop(context: Context) {
            appContext = context.applicationContext
            NotifyCompanionPrefs.setWanted(context, false)
            context.startService(
                Intent(context, NotifyCompanionService::class.java).setAction(ACTION_STOP),
            )
        }

        fun demoNotification(context: Context) {
            appContext = context.applicationContext
            NotifyCompanionPrefs.setWanted(context, true)
            context.startForegroundService(
                Intent(context, NotifyCompanionService::class.java)
                    .setAction(ACTION_INJECT_TEST_WHATSAPP),
            )
        }

        fun demoInstagramNotification(context: Context) {
            appContext = context.applicationContext
            NotifyCompanionPrefs.setWanted(context, true)
            context.startForegroundService(
                Intent(context, NotifyCompanionService::class.java)
                    .setAction(ACTION_INJECT_TEST_INSTAGRAM),
            )
        }

        fun hydrateActive(context: Context) {
            appContext = context.applicationContext
            context.startForegroundService(
                Intent(context, NotifyCompanionService::class.java)
                    .setAction(ACTION_HYDRATE_ACTIVE),
            )
        }

        fun setChannelFilter(context: Context, filter: NotifyChannelFilter) {
            appContext = context.applicationContext
            context.startForegroundService(
                Intent(context, NotifyCompanionService::class.java)
                    .setAction(ACTION_SET_CHANNEL)
                    .putExtra(EXTRA_CHANNEL_FILTER, filter.name),
            )
        }

        @Volatile
        private var appContext: Context? = null

        /** True/false when the notification-listener permission is known, null before the app context exists. */
        fun listenerAccessEnabled(): Boolean? {
            val ctx = appContext ?: return null
            val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.contains(ctx.packageName)
        }

        /** Process-visible summary of the pending glasslet (survives HOME). */
        @Volatile
        var pendingSummary: String? = null
            private set

        fun hasPending(): Boolean = pendingSummary != null || !NotifyInbox.isEmpty()

        /** Re-push the last WhatsApp/IG glasslet (after HOME) so the user can reply later. */
        fun reshowPending(context: Context? = appContext): Boolean {
            val ctx = context?.applicationContext ?: return false
            appContext = ctx
            if (!hasPending()) return false
            ctx.startForegroundService(
                Intent(ctx, NotifyCompanionService::class.java)
                    .setAction(ACTION_RESHOW_PENDING),
            )
            return true
        }
    }

    private data class PendingVoiceReply(
        val packageName: String,
        val text: String,
        val notificationKey: String = "",
    )
}
