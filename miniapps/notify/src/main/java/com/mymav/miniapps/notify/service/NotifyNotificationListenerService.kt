package com.mymav.miniapps.notify.service

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mymav.miniapps.notify.model.CompanionSourceApp
import com.mymav.miniapps.notify.model.IncomingNotification
import com.mymav.miniapps.notify.model.NotificationMediaKind
import java.util.Locale

class NotifyNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        liveInstance = this
        hydrateActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (liveInstance === this) liveInstance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (CompanionSourceApp.fromPackageName(sbn.packageName) == null) return
        NotifyInbox.remove(sbn.key)
        NotificationMediaStore.remove(sbn.key)
        NotificationReplyStore.forget(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sourceApp = CompanionSourceApp.fromPackageName(sbn.packageName)
        if (sourceApp == null) {
            // WhatsApp / Instagram / WA Business only — ignore everything else.
            return
        }
        val (sender, body) = extractSenderAndBody(sbn)
        if (sender.isBlank() && body.isBlank()) return
        if (isAggregateSummary(sbn, sender, body)) {
            android.util.Log.i(
                "MyMavNotifyListener",
                "Skip summary from ${sbn.packageName}: title=$sender body=$body",
            )
            return
        }

        android.util.Log.i(
            "MyMavNotifyListener",
            "Forwarding ${sourceApp.displayName} from ${sbn.packageName}: $sender — $body",
        )
        NotificationReplyStore.remember(sbn.packageName, sbn)
        val media = extractNotificationMedia(sbn, body)
        NotificationMediaStore.remember(sbn.key, media)

        val intent = Intent(this, NotifyCompanionService::class.java).apply {
            action = NotifyCompanionService.ACTION_SHOW_NOTIFICATION
            putExtra(NotifyCompanionService.EXTRA_SOURCE_PACKAGE, sourceApp.packageName)
            putExtra(NotifyCompanionService.EXTRA_SENDER, sender.ifBlank { sourceApp.displayName })
            putExtra(NotifyCompanionService.EXTRA_BODY, body)
            putExtra(NotifyCompanionService.EXTRA_NOTIFICATION_KEY, sbn.key)
            putExtra(NotifyCompanionService.EXTRA_MEDIA_KIND, media.kind.name)
            putExtra(NotifyCompanionService.EXTRA_MEDIA_KEY, sbn.key)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Prefer MessagingStyle extras (real chat content). Fall back to title/text / text lines.
     * Parsed from extras so we don't depend on MessagingStyle helper APIs at compile time.
     */
    private fun extractSenderAndBody(sbn: StatusBarNotification): Pair<String, String> {
        val extras = sbn.notification.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            .ifBlank {
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            }

        val conv = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val messages = extras.getParcelableArrayList<Bundle>("android.messages")
        val last = messages?.lastOrNull()
        if (last != null) {
            val msgText = last.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (msgText.isNotBlank()) text = msgText
            val personName = last.getCharSequence("sender")?.toString()?.trim().orEmpty()
                .ifBlank {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        last.getParcelableCompat<android.app.Person>("sender_person")
                            ?.name?.toString()?.trim().orEmpty()
                    } else {
                        ""
                    }
                }
            title = when {
                conv.isNotBlank() && personName.isNotBlank() &&
                    !conv.equals(personName, ignoreCase = true) -> "$conv · $personName"
                conv.isNotBlank() -> conv
                personName.isNotBlank() -> personName
                else -> title
            }
        } else if (conv.isNotBlank()) {
            title = conv
        }

        if (text.isBlank()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null) {
                text = lines.lastOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
            }
        }
        return title.trim() to text.trim()
    }

    /** WhatsApp/IG post a group summary like "7 messages from 4 chats" — never show that. */
    private fun isAggregateSummary(sbn: StatusBarNotification, title: String, body: String): Boolean {
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (sbn.notification.extras.getBoolean("android.isGroupSummary", false)) return true

        val t = title.trim()
        val b = body.trim()
        val appTitles = setOf("whatsapp", "instagram", "whatsapp business")
        if (t.lowercase(Locale.US) in appTitles) {
            if (b.contains("messages from", ignoreCase = true)) return true
            if (Regex("""\d+\s+messages?""", RegexOption.IGNORE_CASE).containsMatchIn(b)) return true
        }
        if (Regex(
                """^\d+\s+messages?\s+from\s+\d+\s+chats?\.?$""",
                RegexOption.IGNORE_CASE,
            ).matches(b)
        ) {
            return true
        }
        if (b.equals("Checking for new messages", ignoreCase = true)) return true
        if (b.isBlank() && t.lowercase(Locale.US) in appTitles) return true
        return false
    }

    private fun extractNotificationMedia(
        sbn: StatusBarNotification,
        body: String,
    ): NotificationMedia {
        val extras = sbn.notification.extras
        val avatar = sbn.notification.getLargeIcon()?.toArgbBitmap()
            ?: extras.getParcelableCompat<Icon>(Notification.EXTRA_LARGE_ICON)?.toArgbBitmap()
            ?: extras.getParcelableCompat<Bitmap>(Notification.EXTRA_LARGE_ICON)?.softwareCopy()

        val picture = extras.getParcelableCompat<Bitmap>(Notification.EXTRA_PICTURE)?.softwareCopy()
        val pictureIcon = extras.getParcelableCompat<Icon>(Notification.EXTRA_PICTURE_ICON)
            ?.toArgbBitmap()
        val thumbnail = picture ?: pictureIcon
        val kind = when {
            thumbnail != null -> NotificationMediaKind.Image
            body.contains("video", ignoreCase = true) -> NotificationMediaKind.Video
            body.contains("photo", ignoreCase = true) ||
                body.contains("image", ignoreCase = true) ||
                body.contains("gif", ignoreCase = true) ||
                body.contains("sticker", ignoreCase = true) -> NotificationMediaKind.Image
            else -> NotificationMediaKind.None
        }
        return NotificationMedia(kind = kind, thumbnail = thumbnail, avatar = avatar)
    }

    private inline fun <reified T : Parcelable> android.os.Bundle.getParcelableCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key) as? T
        }
    }

    private fun Icon.toArgbBitmap(sizeHint: Int = 128): Bitmap? {
        return runCatching {
            val drawable = loadDrawable(this@NotifyNotificationListenerService) ?: return null
            if (drawable is BitmapDrawable) {
                val bmp = drawable.bitmap ?: return null
                return bmp.softwareCopy()
            }
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizeHint
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizeHint
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            out
        }.getOrNull()
    }

    private fun Bitmap.softwareCopy(): Bitmap? {
        return runCatching {
            when (config) {
                Bitmap.Config.ARGB_8888 -> this
                Bitmap.Config.HARDWARE -> copy(Bitmap.Config.ARGB_8888, false)
                else -> copy(Bitmap.Config.ARGB_8888, false)
            }
        }.getOrNull()
    }

    /**
     * Pull currently posted WA/IG shade notifications into the inbox so previous
     * (non-archived) chats that still have an active notification can be browsed.
     * Full DM history is not available without private app databases.
     */
    private fun hydrateActiveNotifications() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        var count = 0
        for (sbn in active) {
            val sourceApp = CompanionSourceApp.fromPackageName(sbn.packageName) ?: continue
            val (sender, body) = extractSenderAndBody(sbn)
            if (sender.isBlank() && body.isBlank()) continue
            if (isAggregateSummary(sbn, sender, body)) continue
            NotificationReplyStore.remember(sbn.packageName, sbn)
            val media = extractNotificationMedia(sbn, body)
            NotificationMediaStore.remember(sbn.key, media)
            NotifyInbox.upsert(
                IncomingNotification(
                    sourceApp = sourceApp,
                    sender = sender.ifBlank { sourceApp.displayName },
                    body = body.ifBlank { "(no text)" },
                    notificationKey = sbn.key,
                    mediaKind = media.kind,
                    mediaKey = sbn.key,
                    postedAtMillis = sbn.postTime,
                ),
            )
            count++
        }
        android.util.Log.i("MyMavNotifyListener", "Hydrated $count active WA/IG notifications")
    }

    companion object {
        @Volatile
        private var liveInstance: NotifyNotificationListenerService? = null

        fun requestHydrateActive(context: android.content.Context) {
            val live = liveInstance
            if (live != null) {
                live.hydrateActiveNotifications()
            } else {
                android.util.Log.i(
                    "MyMavNotifyListener",
                    "Hydrate skipped — listener not connected (${context.packageName})",
                )
            }
        }
    }
}
