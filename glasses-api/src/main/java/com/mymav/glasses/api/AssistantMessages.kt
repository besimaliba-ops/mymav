package com.mymav.glasses.api

import java.util.concurrent.Executors

/**
 * Sending a message by voice. The recipient is resolved from the phone's contacts (or from someone who just messaged
 * on WhatsApp / Instagram), the exact text is read back, and NOTHING is sent until the user says yes. Only the user's
 * own words can confirm; text that arrives from a notification, web page or document is never routed here.
 * Outcomes are what Android reports; a missing permission or contact is an error, never a pretend success.
 */
internal object AssistantMessages {
    private enum class Stage { Choose, Confirm }

    private class Draft(val service: String, val name: String, val text: String, val call: Boolean = false) {
        var stage = Stage.Choose
        var smsChoices: List<MymavActions.Contact> = emptyList()
        var replyChoices: List<MymavActions.ReplyTarget> = emptyList()
        var contact: MymavActions.Contact? = null
        var reply: MymavActions.ReplyTarget? = null
        var expiresAt = System.currentTimeMillis() + 3 * 60_000L
    }

    class Req(val service: String, val name: String, val text: String, val call: Boolean = false, val loose: Boolean = false)

    @Volatile private var draft: Draft? = null
    @Volatile private var sending = false
    private var lastKey = ""
    private var lastAt = 0L
    private val io = Executors.newSingleThreadExecutor()

    private val FORMS = listOf(
        Regex("^(?:please )?send (?:an? )?(?:(whatsapp|instagram|sms|text)(?: message)?|message) to (.+?) (?:saying|that says|that|with the message|:) (.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:please )?(text|message|whatsapp|sms|dm|tell) (.+?) (?:saying|that|to say|:) ?(.+)$", RegexOption.IGNORE_CASE),
    )

    private val CALL = Regex("^(?:please )?(?:call|phone|ring|dial) (.+)$", RegexOption.IGNORE_CASE)
    private val LOOSE = Regex("^(?:please )?(text|message|sms|whatsapp) (.+)$", RegexOption.IGNORE_CASE)

    /** A send-a-message or call request, or null. Loose form ("text sonay hello") is split by matching contacts. */
    fun parse(raw: String): Req? {
        val t = raw.trim().removeSuffix(".").removeSuffix("?")
        FORMS[0].find(t)?.let { m -> return Req(serviceOf(m.groupValues[1].lowercase()), m.groupValues[2].trim(), m.groupValues[3].trim()) }
        FORMS[1].find(t)?.let { m ->
            val name = m.groupValues[2].trim()
            if (name.length > 40 || name.split(' ').size > 4) return null
            return Req(serviceOf(m.groupValues[1].lowercase()), name, m.groupValues[3].trim())
        }
        CALL.find(t)?.let { m ->
            val name = m.groupValues[1].trim().removePrefix("my ").trim()
            if (name.isBlank() || name.split(' ').size > 4 || name.length > 40) return null
            return Req("sms", name, "", call = true)
        }
        LOOSE.find(t)?.let { m ->
            val rest = m.groupValues[2].trim()
            if (rest.split(' ').size < 2) return null
            return Req(serviceOf(m.groupValues[1].lowercase()), rest, "", loose = true)
        }
        return null
    }

    private fun serviceOf(v: String) = when (v) {
        "text", "sms" -> "sms"
        "whatsapp", "instagram", "dm" -> "app"
        else -> "any"
    }

    fun hasPending() = draft?.let { it.expiresAt > System.currentTimeMillis() } == true
    fun clear() { draft = null }

    private fun say(text: String) = AssistantRouter.Outcome(text, AssistantLog.Kind.Local, emptyList(), "ask")
    private fun err(text: String) = AssistantRouter.Outcome(text, AssistantLog.Kind.Error, emptyList(), "")

    fun start(req: Req, deliver: (AssistantRouter.Outcome) -> Unit) {
        val bridge = MymavActions.messages
        if (bridge == null) { deliver(err("Messaging isn't available in this build.")); return }
        var name = req.name; var text = req.text; val service = req.service
        if (req.loose) {
            // "text sonay hello": the contact is the longest leading run of words that matches someone.
            val words = req.name.split(' ')
            var hit = false
            for (k in minOf(3, words.size - 1) downTo 1) {
                val n = words.take(k).joinToString(" ")
                val found = (bridge.findContacts(n).orEmpty().isNotEmpty()) || (service != "sms" && bridge.replyTargets(n).isNotEmpty())
                if (found) { name = n; text = words.drop(k).joinToString(" "); hit = true; break }
            }
            if (!hit) {
                if (bridge.findContacts(words[0]) == null) { deliver(err("I can't look up contacts yet: contacts permission is off. Open MyMav Settings and tap Allow messaging.")); return }
                deliver(err("I couldn't tell who to message. Try: text ${words[0]} saying ${words.drop(1).joinToString(" ")}")); return
            }
        }
        if (!req.call && text.isBlank()) { deliver(err("What should the message say?")); return }
        val d = Draft(service, name, text, req.call)
        draft = d
        val replies = if (service != "sms" && !req.call) bridge.replyTargets(name) else emptyList()
        val contacts = if (service != "app") bridge.findContacts(name) else emptyList()
        if (service == "app" && replies.isEmpty()) {
            draft = null
            deliver(err("I can only reply on WhatsApp or Instagram to someone who has just messaged you, and I found no recent message from \"$name\". I can send an SMS instead: say text $name saying..."))
            return
        }
        if (service != "app" && contacts == null && replies.isEmpty()) {
            draft = null
            deliver(err("I can't look up contacts yet: contacts permission is off. Open MyMav Settings and tap Allow messaging."))
            return
        }
        if (!req.call && service != "app" && contacts != null && !bridge.canSendSms() && replies.isEmpty()) {
            draft = null
            deliver(err("I can't send SMS yet: messaging permission is off. Open MyMav Settings and tap Allow messaging."))
            return
        }
        // Prefer SMS contacts for text/message; fall back to a recent app conversation only for "any".
        val sms = if (req.call || bridge.canSendSms()) contacts.orEmpty() else emptyList()
        d.smsChoices = if (service == "app") emptyList() else sms.distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
        d.replyChoices = if (service == "sms") emptyList() else replies
        if (d.smsChoices.isEmpty() && d.replyChoices.isEmpty()) {
            draft = null
            deliver(err("I couldn't find a contact called \"$name\"."))
            return
        }
        resolve(d, deliver)
    }

    private fun label(c: MymavActions.Contact) = "${c.name} (...${c.number.takeLast(4)})"

    private fun resolve(d: Draft, deliver: (AssistantRouter.Outcome) -> Unit) {
        val options = d.smsChoices.size + d.replyChoices.size
        if (options == 1) {
            d.contact = d.smsChoices.firstOrNull(); d.reply = d.replyChoices.firstOrNull()
            return confirm(d, deliver)
        }
        d.stage = Stage.Choose
        d.expiresAt = System.currentTimeMillis() + 3 * 60_000L
        val lines = d.smsChoices.mapIndexed { i, c -> "${i + 1}. ${label(c)} by SMS" } +
            d.replyChoices.mapIndexed { i, r -> "${d.smsChoices.size + i + 1}. ${r.sender} on ${r.app}" }
        deliver(say("Which one?\n" + lines.take(6).joinToString("\n")))
    }

    private fun confirm(d: Draft, deliver: (AssistantRouter.Outcome) -> Unit) {
        d.stage = Stage.Confirm
        d.expiresAt = System.currentTimeMillis() + 3 * 60_000L
        val to = d.contact?.let { "${label(it)} by SMS" } ?: d.reply!!.let { "${it.sender} on ${it.app}" }
        if (d.call) deliver(say("Call ${label(d.contact!!)}? I'll open the dialler with the number; you press call.\nSay yes to open it, or no to cancel."))
        else deliver(say("Send to $to:\n\"${d.text}\"\nSay yes to send it, or no to cancel."))
    }

    fun continuePending(cleaned: String, deliver: (AssistantRouter.Outcome) -> Unit): Boolean {
        val d = draft ?: return false
        if (d.expiresAt < System.currentTimeMillis()) { draft = null; return false }
        val c = cleaned.trim().removeSuffix(".").removeSuffix("!")
        if (c in setOf("cancel", "no", "never mind", "nevermind", "stop", "don't", "don't send it", "no thanks", "forget it")) {
            draft = null; deliver(say("Okay, I didn't send anything.")); return true
        }
        when (d.stage) {
            Stage.Choose -> {
                val n = c.removePrefix("number ").toIntOrNull()
                val all = d.smsChoices.size + d.replyChoices.size
                val idx = n?.minus(1)
                if (idx != null && idx in 0 until all) {
                    if (idx < d.smsChoices.size) d.contact = d.smsChoices[idx] else d.reply = d.replyChoices[idx - d.smsChoices.size]
                    confirm(d, deliver); return true
                }
                val hit = d.smsChoices.firstOrNull { c.contains(it.name.lowercase()) || it.name.lowercase().contains(c) }
                if (hit != null && c.length > 1 && d.smsChoices.count { it.name.equals(hit.name, true) } == 1) { d.contact = hit; confirm(d, deliver); return true }
                deliver(say("Please say the number of the one you mean.")); return true
            }
            Stage.Confirm -> {
                if (c in setOf("yes", "yeah", "yep", "send", "send it", "yes send it", "confirm", "go ahead", "yes please", "do it")) {
                    send(d, deliver); return true
                }
                draft = null  // anything else is a new request, never a confirmation
                return false
            }
        }
    }

    private fun send(d: Draft, deliver: (AssistantRouter.Outcome) -> Unit) {
        val bridge = MymavActions.messages ?: run { draft = null; deliver(err("Messaging isn't available.")); return }
        if (sending) { deliver(say("Still sending.")); return }
        val key = (if (d.call) "call:" else "") + (d.contact?.number ?: d.reply!!.key) + "|" + d.text
        if (key == lastKey && System.currentTimeMillis() - lastAt < 120_000L) {
            draft = null; deliver(say("That exact message was just sent. I didn't send it twice.")); return
        }
        sending = true
        val who = d.contact?.name ?: d.reply!!.sender
        val finish = { r: MymavActions.SendResult ->
            sending = false; draft = null
            if (r.ok) { lastKey = key; lastAt = System.currentTimeMillis() }
            deliver(
                if (r.ok) AssistantRouter.Outcome((if (d.call) "Call to $who: " else "Message to $who: ") + r.text, AssistantLog.Kind.Data, emptyList(), "")
                else err(r.text),
            )
        }
        io.execute {
            runCatching {
                if (d.call) bridge.dial(d.contact!!.number, finish)
                else d.contact?.let { bridge.sendSms(it.number, d.text, finish) } ?: bridge.reply(d.reply!!, d.text, finish)
            }.onFailure { finish(MymavActions.SendResult(false, "Sending failed (${it.javaClass.simpleName}). Nothing was confirmed.")) }
        }
    }
}
