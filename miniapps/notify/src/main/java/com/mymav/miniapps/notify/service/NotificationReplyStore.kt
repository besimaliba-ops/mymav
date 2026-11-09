package com.mymav.miniapps.notify.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

object NotificationReplyStore {
    private val latestTargetsByPackage = ConcurrentHashMap<String, ReplyTarget>()
    private val targetsByNotificationKey = ConcurrentHashMap<String, ReplyTarget>()

    fun forget(notificationKey: String) {
        val removed = targetsByNotificationKey.remove(notificationKey) ?: return
        if (latestTargetsByPackage[removed.packageName]?.notificationKey == notificationKey) {
            latestTargetsByPackage.remove(removed.packageName)
        }
    }

    fun remember(packageName: String, statusBarNotification: StatusBarNotification) {
        val replyAction = statusBarNotification.notification.actions
            ?.firstOrNull { action -> action.remoteInputs?.isNotEmpty() == true }
            ?: return
        val pendingIntent = replyAction.actionIntent ?: return

        val target = ReplyTarget(
            packageName = packageName,
            notificationKey = statusBarNotification.key,
            pendingIntent = pendingIntent,
            remoteInputs = replyAction.remoteInputs ?: emptyArray(),
        )
        latestTargetsByPackage[packageName] = target
        targetsByNotificationKey[statusBarNotification.key] = target
    }

    fun replyTo(
        context: Context,
        packageName: String,
        replyText: String,
        notificationKey: String? = null,
    ): Boolean {
        val target = notificationKey?.let { targetsByNotificationKey[it] }
            ?: latestTargetsByPackage[packageName]
            ?: return false
        val fillInIntent = Intent()
        val results = Bundle().apply {
            target.remoteInputs.forEach { input ->
                putCharSequence(input.resultKey, replyText)
            }
        }
        RemoteInput.addResultsToIntent(target.remoteInputs, fillInIntent, results)
        return runCatching {
            target.pendingIntent.send(context, 0, fillInIntent)
        }.isSuccess
    }

    @Deprecated("Use replyTo", ReplaceWith("replyTo(context, packageName, replyText)"))
    fun replyToLatest(context: Context, packageName: String, replyText: String): Boolean {
        return replyTo(context, packageName, replyText)
    }

    private data class ReplyTarget(
        val packageName: String,
        val notificationKey: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
    )
}
