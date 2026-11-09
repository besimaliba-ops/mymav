package com.mymav.miniapps.notify.service

import android.graphics.Bitmap
import com.mymav.miniapps.notify.model.NotificationMediaKind
import java.util.concurrent.ConcurrentHashMap

data class NotificationMedia(
    val kind: NotificationMediaKind,
    val thumbnail: Bitmap?,
    /** Contact / sender photo from notification largeIcon (not message media). */
    val avatar: Bitmap? = null,
)

object NotificationMediaStore {
    private val mediaByNotificationKey = ConcurrentHashMap<String, NotificationMedia>()

    fun remember(notificationKey: String, media: NotificationMedia) {
        if (media.kind == NotificationMediaKind.None && media.avatar == null && media.thumbnail == null) {
            return
        }
        mediaByNotificationKey[notificationKey] = media
    }

    fun get(notificationKey: String?): NotificationMedia? {
        if (notificationKey == null) return null
        return mediaByNotificationKey[notificationKey]
    }

    fun remove(notificationKey: String) {
        mediaByNotificationKey.remove(notificationKey)
    }
}
