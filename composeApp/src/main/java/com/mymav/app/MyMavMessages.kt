package com.mymav.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.mymav.glasses.api.MymavActions
import com.mymav.miniapps.notify.service.NotificationReplyStore
import com.mymav.miniapps.notify.service.NotifyInbox
import java.util.UUID

/**
 * Real message sending for the assistant. SMS goes out through the phone's SmsManager; replies to WhatsApp or
 * Instagram go through the reply action of that person's own notification. Results are what Android reports:
 * an SMS is "handed to the carrier" (delivery is not confirmed) and a notification reply is "passed to the app".
 */
class MyMavMessages(private val context: Context) : MymavActions.MessageBridge {
    companion object {
        fun contactsGranted(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        fun smsGranted(c: Context) = c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    override fun findContacts(name: String): List<MymavActions.Contact>? {
        if (!contactsGranted(context)) return null
        val out = LinkedHashMap<String, MymavActions.Contact>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    val n = c.getString(0).orEmpty(); val num = c.getString(1).orEmpty().replace(Regex("[^+0-9]"), "")
                    if (num.isNotBlank()) out.putIfAbsent(num, MymavActions.Contact(n, num))
                }
            }
        }
        return out.values.toList()
    }

    override fun canSendSms(): Boolean = smsGranted(context)

    override fun replyTargets(name: String): List<MymavActions.ReplyTarget> {
        val needle = name.trim().lowercase()
        return NotifyInbox.allItems()
            .filter { it.sender.lowercase().contains(needle) && it.notificationKey.isNotBlank() }
            .sortedByDescending { it.postedAtMillis }
            .distinctBy { it.sender to it.sourceApp }
            .map { MymavActions.ReplyTarget(it.sourceApp.displayName, it.sender, it.notificationKey, it.sourceApp.packageName) }
    }

    override fun sendSms(number: String, text: String, done: (MymavActions.SendResult) -> Unit) {
        if (!smsGranted(context)) { done(MymavActions.SendResult(false, "SMS permission is off. Allow messaging in MyMav Settings. Nothing was sent.")); return }
        val action = "com.mymav.app.SMS_SENT." + UUID.randomUUID()
        var finished = false
        val receiver = object : BroadcastReceiver() {
            private var parts = 0
            private var failed: String? = null
            override fun onReceive(c: Context, i: Intent) {
                val code = resultCode
                if (code != android.app.Activity.RESULT_OK) failed = when (code) {
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode or radio off"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "bad message"
                    else -> "the carrier refused it (code $code)"
                }
                parts--
                if (parts <= 0 && !finished) {
                    finished = true
                    runCatching { context.unregisterReceiver(this) }
                    done(
                        if (failed == null) MymavActions.SendResult(true, "Sent as an SMS (handed to your carrier; delivery isn't confirmed).")
                        else MymavActions.SendResult(false, "The SMS was not sent: $failed.")
                    )
                }
            }
            fun expect(n: Int) { parts = n }
        }
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val pieces = sms.divideMessage(text)
            receiver.expect(pieces.size)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            val sent = ArrayList<PendingIntent>()
            repeat(pieces.size) { sent += PendingIntent.getBroadcast(context, it, Intent(action).setPackage(context.packageName), flags) }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, IntentFilter(action))
            sms.sendMultipartTextMessage(number, null, pieces, sent, null)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!finished) {
                    finished = true
                    runCatching { context.unregisterReceiver(receiver) }
                    done(MymavActions.SendResult(false, "I couldn't confirm the SMS was sent within 25 seconds. Check your messages before trying again."))
                }
            }, 25_000L)
        } catch (e: SecurityException) {
            done(MymavActions.SendResult(false, "Android blocked SMS sending. Allow messaging in MyMav Settings. Nothing was sent."))
        } catch (e: Exception) {
            done(MymavActions.SendResult(false, "The SMS failed (${e.javaClass.simpleName}). Nothing was sent."))
        }
    }

    override fun dial(number: String, done: (MymavActions.SendResult) -> Unit) {
        try {
            val i = Intent(Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            done(MymavActions.SendResult(true, "Opened the phone dialler with the number. I can't press call for you, so tap Call on the phone."))
        } catch (e: Exception) {
            done(MymavActions.SendResult(false, "Android wouldn't open the dialler (${e.javaClass.simpleName}). Open your phone app and call from there."))
        }
    }

    override fun reply(target: MymavActions.ReplyTarget, text: String, done: (MymavActions.SendResult) -> Unit) {
        val ok = NotificationReplyStore.replyTo(context, target.packageName, text, target.key)
        done(
            if (ok) MymavActions.SendResult(true, "Passed your reply to ${target.app} for ${target.sender}. ${target.app} doesn't report delivery back to me.")
            else MymavActions.SendResult(false, "${target.app} no longer has that conversation open to reply to. Nothing was sent.")
        )
    }
}
