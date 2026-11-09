package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudReplyState
import com.mymav.glasses.api.NotifyChannelFilter
import com.mymav.glasses.api.NotifyInboxState
import com.mymav.glasses.api.NotifyStage
import java.util.Locale

/**
 * Socials app panel on the shared [GlassKit] design: one smoked-glass panel with a compact header
 * (brand icons, title, HOME), All/WhatsApp/Instagram filter tabs, a large message card and a reply row.
 */
class NotifyNotificationScreen(
    private val hud: GlassesHud.Notification,
) : HudScreen(tag = "mymav-notify-message") {
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("notify")
    private val accent = hud.accent.toM2Color()
    private val bodyPageSize = if (hud.hasMedia) MAX_BODY_WITH_MEDIA else MAX_BODY
    private val bodyPage = hud.body.pageForHud(hud.messagePage, bodyPageSize)

    private val background = HudPrimitives.fullBleedBackground()

    private val hasMessage = hud.inboxState == NotifyInboxState.Messages
    private val showFilters = hud.stage == NotifyStage.Inbox && hud.replyState == HudReplyState.Idle
    /** Filters sit along the bottom; the reply row (messages only) sits just above them. */
    private val replyY = if (showFilters) 244f else 300f
    private val cardY = 76f
    private val cardH = if (hasMessage) replyY - 8f - cardY else (if (showFilters) 300f else 340f) - 8f - cardY
    private val cardW = if (hud.hasMedia) 372f else 576f
    private val messageCard = GlassSurface.withScope(glassScope) { HudPrimitives.frostedPanel(32f, cardY, cardW, cardH, HudTheme.PanelBorder, radius = 16f, gradient = false, glass = true) }
    private val mediaCard = GlassSurface.withScope(glassScope) { HudPrimitives.frostedPanel(416f, cardY, 192f, cardH, HudTheme.PanelBorder, radius = 16f, gradient = false, glass = true) }
    private val replyCard = GlassSurface.withScope(glassScope) { HudPrimitives.frostedPanel(32f, replyY, 576f, 44f, HudTheme.PanelBorder, radius = 16f, gradient = false, glass = true) }

    private val actionPill = PillStyle.apply(HudPrimitives.softPill(ACTION_X, replyY + 4f, ACTION_W, 36f, HudTheme.MicPill), HudTheme.MicPill)
    private val actionText = HudPrimitives.centeredPillLabel(
        actionLine(), ACTION_X, replyY + 4f, ACTION_W, 36f, 0.8f, M2Color.White, tracked = true,
    )

    private val avatarImage = createHudImage(
        hud.avatarImageBytes, hud.avatarImageWidth, hud.avatarImageHeight,
        46f, cardY + 12f, 52f, 52f,
        circular = true,
    )
    private val avatarTile = M2RectFilled(accent.withAlpha(0.85f)).apply {
        setDimensions(46f, cardY + 12f, 52f, 52f)
        softCorners(26f)
    }
    private val avatarInitial = M2Text(hud.sender.avatarInitial()).apply {
        setXY(64f, cardY + 24f)
        setColor(M2Color.White)
        setScale(1.2f)
    }
    private val appName = HudPrimitives.labelInk(
        hud.appLabel, 112f, cardY + 12f, 0.75f, HudTheme.InkMuted,
        maxWidth = cardW - 100f,
    )
    private val sender = HudPrimitives.label(
        hud.sender, 112f, cardY + 32f, 1.1f, M2Color.White, bold = true,
        maxWidth = cardW - 100f,
    )
    private val bodyScale = if (hud.hasMedia) 0.8f else 0.92f
    /** Body text with emoji drawn as small pictures inline (the HUD font has no emoji glyphs). */
    private val bodyAdds: List<() -> Unit> = run {
        val maxW = cardW - 28f
        val emojiW = EmojiKeyBitmaps.INLINE + 6f
        val spaceW = HudText.width(" ", bodyScale)
        data class Tok(val text: String, val emoji: Boolean)
        val toks = ArrayList<Tok>()
        for ((isEmoji, run) in EmojiKeyBitmaps.segments(bodyPage.replace(Regex("\\s+"), " "))) {
            if (isEmoji) toks += Tok(run, true)
            else run.split(' ').filter { it.isNotEmpty() }.forEach { toks += Tok(hudSafe(it), false) }
        }
        val adds = ArrayList<() -> Unit>()
        var line = 0
        var x = 0f
        var emojiCount = 0
        loop@ for (t in toks) {
            val w = if (t.emoji) emojiW else HudText.width(t.text, bodyScale)
            if (x > 0f && x + w > maxW) { line++; x = 0f }
            if (line >= 4) break@loop
            val y = cardY + 74f + line * 27f
            val px = 46f + x
            if (t.emoji) {
                if (emojiCount < 10) {
                    val img = EmojiKeyBitmaps.imageFor(t.text, px, y + 1f, EmojiKeyBitmaps.INLINE.toFloat(), EmojiKeyBitmaps.INLINE.toFloat())
                    if (img != null) { emojiCount++; adds += { add(img) } }
                }
            } else {
                val lbl = HudPrimitives.label(t.text, px, y, bodyScale, M2Color.White)
                adds += { add(lbl) }
            }
            x += w + spaceW
        }
        adds
    }
    private val pageIndicator = HudPrimitives.labelInk(
        hud.pageIndicator(bodyPageSize), 46f, cardY + cardH - 24f, 0.7f, HudTheme.InkMuted, maxWidth = cardW - 40f,
    )
    private val mediaImage = createHudImage(
        hud.mediaImageBytes, hud.mediaImageWidth, hud.mediaImageHeight,
        426f, cardY + 10f, 172f, cardH - 20f,
        circular = false,
        cornerRadius = 14f,
    )
    private val mediaShade = M2RectFilled(accent.withAlpha(0.45f)).apply {
        setDimensions(426f, cardY + 10f, 172f, cardH - 20f)
        softCorners(14f)
    }
    private val mediaLabel = HudPrimitives.centeredPillLabel(
        hud.mediaKindLabel.ifBlank { "Photo" }, 426f, cardY + 10f, 172f, cardH - 20f, 0.9f, M2Color.White,
    )
    private val replyPreview = HudPrimitives.label(
        replyLine(), 48f, replyY + 11f, 0.85f, M2Color.White, maxWidth = ACTION_X - 60f,
    )
    private val emptyTitleText =
        if (hud.inboxState == NotifyInboxState.AccessUnavailable) "Notification access is off" else "All caught up"
    private val emptyDetailText = if (hud.inboxState == NotifyInboxState.AccessUnavailable) {
        "Allow notification access for MyMav on the phone"
    } else {
        "New messages will appear here."
    }
    private val emptyCentreX = 32f + cardW / 2f
    private val emptyTitle = HudPrimitives.label(
        emptyTitleText,
        emptyCentreX - HudText.width(emptyTitleText, 1.3f, true) / 2f, cardY + 104f, 1.3f, M2Color.White,
        bold = true, maxWidth = cardW - 40f,
    )
    private val emptyDetail = HudPrimitives.labelInk(
        emptyDetailText,
        emptyCentreX - HudText.width(emptyDetailText, 0.85f, false) / 2f, cardY + 144f, 0.85f, HudTheme.InkMuted,
        maxWidth = cardW - 40f,
    )

    override fun build() {
        add(background)
        GlassKit.appPanel(this, accent = M2Color.Green, glass = true)
        // Header: brand icons, title, HOME (fixed gaze position).
        BrandHudIcons.whatsApp(36).let { e ->
            createHudImage(e.bytes, e.width, e.height, 32f, 18f, 36f, 36f, circular = false, roundCorners = false)?.let { add(it) }
        }
        BrandHudIcons.instagram(36).let { e ->
            createHudImage(e.bytes, e.width, e.height, 72f, 18f, 36f, 36f, circular = false, roundCorners = false)?.let { add(it) }
        }
        add(HudPrimitives.label("Socials", 120f, 24f, 1.4f, maxWidth = 350f))
        if (showFilters) {
            val idx = when (hud.channelFilter) {
                NotifyChannelFilter.All -> 0
                NotifyChannelFilter.WhatsApp -> 1
                NotifyChannelFilter.Instagram -> 2
            }
            GlassKit.pillTabs(this, listOf("All", "WhatsApp", "Instagram"), idx, y = 302f, h = 36f)
        }
        HudPrimitives.addPanel(this, messageCard)
        if (!hasMessage) {
            // Centred empty-state icon: a speech bubble with three dots (drawn, so no asset is needed).
            val bw = 58f
            val bh = 42f
            val bx = emptyCentreX - bw / 2f
            val by = cardY + 36f
            add(M2RectOutline(HudTheme.InkMuted).apply { setDimensions(bx, by, bw, bh); softCorners(16f); setStyle(2f) })
            for (i in -1..1) {
                add(M2RectFilled(HudTheme.InkMuted).apply { setDimensions(emptyCentreX + i * 14f - 3f, by + bh / 2f - 3f, 6f, 6f); softCorners(3f) })
            }
            add(emptyTitle)
            add(emptyDetail)
        } else {
            if (hud.hasMedia) {
                HudPrimitives.addPanel(this, mediaCard)
                if (mediaImage != null) add(mediaImage) else {
                    add(mediaShade)
                    add(mediaLabel)
                }
            }
            if (avatarImage != null) add(avatarImage) else {
                add(avatarTile)
                add(avatarInitial)
            }
            add(appName)
            add(sender)
            bodyAdds.forEach { it() }
            if (hud.stage == NotifyStage.Detail &&
                hud.body.replace(Regex("\\s+"), " ").trim().length > bodyPageSize
            ) {
                add(pageIndicator)
            }
        }
        if (hasMessage) {
            HudPrimitives.addPanel(this, replyCard)
            GlassKit.selectedPill(this, ACTION_X, replyY + 4f, ACTION_W, 36f)
            add(actionText)
            add(replyPreview)
        }
        GlassKit.footer(
            this, if (hasMessage) hintLine() else "Long tap changes the filter",
            if (hasMessage && hud.inboxCount > 1) "${hud.inboxIndex} / ${hud.inboxCount}" else "",
            y = 356f,
        )
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.Tap -> {
                when (hud.replyState) {
                    HudReplyState.ConfirmSend -> hud.actions.onSendConfirmed?.invoke()
                    HudReplyState.Idle -> when (hud.stage) {
                        NotifyStage.Inbox -> {
                            // Select / open the focused chat — do NOT jump to keyboard yet.
                            val open = hud.actions.onOpenSelected
                            if (open != null) open.invoke() else hud.actions.onReplyRequested?.invoke()
                        }
                        NotifyStage.Detail -> {
                            // Reply only after a message is selected.
                            val kb = hud.actions.onKeyboardRequested
                            if (kb != null) kb.invoke() else hud.actions.onReplyRequested?.invoke()
                        }
                    }
                    else -> hud.actions.onReplyRequested?.invoke()
                }
            }
            // Flick through individual messages (same person or different) with Forward/Down.
            M2Touch.Forward, M2Touch.Down -> hud.actions.onScrollDown?.invoke()
            M2Touch.Backward -> hud.actions.onScrollUp?.invoke()
            M2Touch.Up -> {
                GlassesHomeController.rememberAppHud(hud)
                GlassesHomeController.simulateHomePillGazeDwell()
            }
            M2Touch.LongTap -> {
                // Long-tap cycles WA / IG / ALL filter; home remains via 1s gaze on HOME.
                if (hud.replyState == HudReplyState.Idle) {
                    hud.actions.onCycleChannelFilter?.invoke()
                        ?: hud.actions.onHomeRequested?.invoke()
                        ?: hud.actions.onDismissRequested?.invoke()
                } else {
                    hud.actions.onHomeRequested?.invoke()
                        ?: hud.actions.onDismissRequested?.invoke()
                }
            }
        }
    }

    private fun replyLine(): String = when (hud.replyState) {
        HudReplyState.Idle -> when (hud.stage) {
            NotifyStage.Inbox -> "Tap to open this chat"
            NotifyStage.Detail -> "Tap to reply"
        }
        HudReplyState.Listening -> "Listening..."
        HudReplyState.ConfirmSend -> "Send? \"${hud.draftReply.orEmpty().takeForHud(MAX_REPLY)}\""
        HudReplyState.Sent -> "Sent"
        HudReplyState.Cancelled -> "Cancelled"
    }

    private fun hintLine(): String = when (hud.replyState) {
        HudReplyState.ConfirmSend -> "Tap to send - Back to cancel"
        HudReplyState.Listening -> "Speak now - Back to cancel"
        else -> when (hud.stage) {
            NotifyStage.Inbox ->
                "Swipe chats - Long tap filter"
            NotifyStage.Detail -> "Back to inbox - HOME keeps it pending"
        }
    }

    private fun actionLine(): String = when (hud.replyState) {
        HudReplyState.ConfirmSend -> "SEND"
        HudReplyState.Listening -> "MIC"
        else -> when (hud.stage) {
            NotifyStage.Inbox -> "OPEN"
            NotifyStage.Detail -> "REPLY"
        }
    }

    private fun channelHint(): String = when (hud.channelFilter) {
        NotifyChannelFilter.All -> "WA/IG FILTER"
        NotifyChannelFilter.WhatsApp -> "WA ONLY"
        NotifyChannelFilter.Instagram -> "IG ONLY"
    }

    private companion object {
        /** Header text stops before the HOME pill. */
        const val HEADER_TEXT_MAX_X = 434f
        const val ACTION_X = 452f
        const val ACTION_W = 148f
        const val MAX_SENDER = 28
        const val MAX_BODY = 90
        const val MAX_BODY_WITH_MEDIA = 55
        const val MAX_REPLY = 56
    }
}

private fun GlassesHud.Notification.pageIndicator(pageSize: Int): String {
    val pages = ((body.replace(Regex("\\s+"), " ").trim().length - 1) / pageSize) + 1
    return if (pages > 1) "PAGE ${messagePage + 1}/$pages  SWIPE OR GAZE" else ""
}

private fun String.pageForHud(page: Int, pageSize: Int): String {
    val normalized = replace(Regex("[\\s\\u2013\\u2014\\u2026]+"), " ").trim()
    val start = (page.coerceAtLeast(0) * pageSize).coerceAtMost(normalized.length)
    val rest = normalized.drop(start)
    // Keep emoji: they are drawn as inline pictures later (hudSafe would strip them).
    return if (rest.length <= pageSize) rest else rest.take(pageSize - 3).trimEnd() + "..."
}

private fun String.avatarInitial(): String {
    return trim()
        .firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
}
