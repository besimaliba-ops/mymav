package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.everysight.mav2.sdk.uikit.base.M2Drawable
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudReadableMode
import java.io.ByteArrayOutputStream

/**
 * Premium mini-app enter / idle glasslet — soft rounded hero + accent metric.
 * Conversate / Shop use the shared readable layout (cards + bottom action row).
 * HOME pill: 1s gaze dwell (or Up to arm sim dwell); long-tap fallback.
 */
class AppEnterScreen(
    initial: GlassesHud.AppEnter,
) : HudScreen(tag = "mymav-enter-${initial.appId}") {
    private var hud: GlassesHud.AppEnter = initial
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope(hud.appId)

    private val accent = hud.accent.toM2Color()
    private val background = HudPrimitives.fullBleedBackground()
    private val readable = hud.readableMode != HudReadableMode.Classic
    /** Action row + offer gaze bars: the only drawables a gaze tick replaces. */
    private val gazeDyn = mutableListOf<M2Drawable>()
    /** Conversate card labels (not the glass plates) so a new caption does not rebuild the screen. */
    private val liveText = mutableListOf<M2Drawable>()
    /** The session swaps screens 80ms before onCreate; patching an unbuilt screen would draw the row twice. */
    private var built = false

    /**
     * Gaze ticks patch the action row. Conversate caption/cue/status text patches the live labels.
     * Structural changes (footer, product, offers, card count) still rebuild.
     */
    override fun tryPatch(next: GlassesHud): Boolean {
        if (next !is GlassesHud.AppEnter || !readable || !built) return false
        val gazeOnly = next.copy(
            footerFocus = hud.footerFocus,
            footerGazeProgress = hud.footerGazeProgress,
            offerGazeIndex = hud.offerGazeIndex,
            offerGazeProgress = hud.offerGazeProgress,
            homeDwellProgress = hud.homeDwellProgress,
            actions = hud.actions,
        ) == hud
        if (gazeOnly) {
            hud = next
            redrawGaze()
            return true
        }
        if (!sameChrome(next)) return false
        hud = next
        liveText.forEach { d -> runCatching { remove(d) } }
        liveText.clear()
        drawConversateText()
        redrawGaze()
        HudPerf.count("hud.patch.live")
        return true
    }

    private fun sameChrome(next: GlassesHud.AppEnter): Boolean =
        next.appId == hud.appId &&
            next.readableMode == HudReadableMode.Conversate &&
            hud.readableMode == HudReadableMode.Conversate &&
            next.footerLabels == hud.footerLabels &&
            next.footerDisabled == hud.footerDisabled &&
            next.cards.size == hud.cards.size &&
            next.product == null && hud.product == null &&
            next.offers.isEmpty() && hud.offers.isEmpty()

    private fun redrawGaze() {
        gazeDyn.forEach { d -> runCatching { remove(d) } }
        gazeDyn.clear()
        drawGazeLayer()
        sanitizeAdded(gazeDyn)
        patchHomeDwell(hud.homeDwellProgress)
    }

    private fun drawGazeLayer() {
        val before = getDrawables().toHashSet()
        if (hud.offers.isNotEmpty() && hud.offerGazeIndex in 0 until hud.offers.size.coerceAtMost(2) && hud.offerGazeProgress > 0.02f) {
            val row = if (hud.product != null) {
                com.mymav.glasses.api.HudActionLayout.offerRow(hud.offerGazeIndex)
            } else {
                null
            }
            if (row != null) GlassKit.progress(this, hud.offerGazeProgress, row.y + row.h - 6f, x = 48f, w = 544f)
        }
        if (hud.footerLabels.isNotEmpty()) {
            val danger = if (hud.readableMode == HudReadableMode.Conversate) {
                hud.footerLabels.indexOfFirst { it.equals("Stop", ignoreCase = true) }
            } else {
                -1
            }
            GlassKit.readableActionRow(
                this, hud.footerLabels, hud.footerFocus, dangerIndex = danger,
                gazeProgress = hud.footerGazeProgress, disabled = hud.footerDisabled,
            )
        }
        getDrawables().forEach { if (it !in before) gazeDyn.add(it) }
    }

    override fun build() {
        add(background)
        GlassKit.appPanel(this)
        if (readable) {
            buildReadable()
        } else {
            buildClassic()
        }
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
        built = true
    }

    private fun buildClassic() {
        GlassKit.header(this, hud.title, hud.appId.takeIf { PremiumHudIcons.has(it) })
        add(HudPrimitives.labelInk(hud.subtitle.takeForHud(42), 32f, 72f, 0.85f, HudTheme.InkMuted, maxWidth = 560f))

        val hasMetric = hud.metric.any { it.isLetterOrDigit() }
        val bulletMaxW = if (hasMetric) 330f else 540f
        hud.bullets.take(4).forEachIndexed { i, line ->
            val y = 118f + i * 36f
            add(
                M2RectFilled(HudTheme.FocusGlow).apply {
                    setDimensions(36f, y + 8f, 8f, 8f)
                    softCorners(4f)
                },
            )
            add(HudPrimitives.label(line.takeForHud(42), 54f, y, 0.85f, maxWidth = bulletMaxW))
        }

        if (hasMetric) {
            GlassKit.card(this, 396f, 112f, 212f, 150f)
            add(HudPrimitives.label(hud.metric, 412f, 130f, 1.6f, accent, bold = true, maxWidth = 180f))
            add(HudPrimitives.labelInk(hud.metricLabel, 412f, 190f, 0.8f, HudTheme.InkMuted, maxWidth = 180f))
        }

        GlassKit.footer(this, hud.title, "Look at HOME 1s to leave", y = 354f)
    }

    private fun buildReadable() {
        val iconId = hud.appId.takeIf { PremiumHudIcons.has(it) }
        GlassKit.readableHeader(
            this, hud.title, iconId, hud.statusChip,
            headingShadow = hud.readableMode == HudReadableMode.Conversate && HeadingShadowTrial.enabled,
        )
        when (hud.readableMode) {
            HudReadableMode.Conversate -> drawConversate()
            HudReadableMode.Shop -> drawShop()
            HudReadableMode.Food -> if (hud.product != null) drawFood() else drawConversate()
            HudReadableMode.Classic -> Unit
        }
        drawGazeLayer()
        // Overflow / paging hints live inside the card (see drawConversateText).
        // Never draw them on the action-row band — they sat on SUMMARY/SAVE/STOP.
        if (hud.contentOverflowHint.isBlank() && hud.sourceLabel.isNotBlank() && hud.offerPageLabel.isBlank()) {
            add(HudPrimitives.labelInk(hud.sourceLabel, 32f, 308f, 0.6f, HudTheme.InkMuted, maxWidth = 576f))
        }
    }

    private fun drawConversate() {
        val cards = hud.cards
        val gap = 10f
        val top = GlassKit.READABLE_CONTENT_TOP
        val sourceRoom = if (hud.sourceLabel.isNotBlank()) 18f else 0f
        val available = GlassKit.READABLE_CONTENT_BOTTOM - top - 8f - sourceRoom
        val cardH = if (cards.size >= 2) (available - gap) / 2f else available
        cards.take(2).forEachIndexed { i, _ ->
            val y = top + i * (cardH + gap)
            GlassKit.card(this, 32f, y, 576f, cardH, radius = 16f, glass = false)
        }
        if (cards.isEmpty()) {
            GlassKit.card(this, 32f, top, 576f, 120f, radius = 16f)
        }
        drawConversateText()
    }

    private fun drawConversateText() {
        val cards = hud.cards
        val gap = 10f
        val top = GlassKit.READABLE_CONTENT_TOP
        val sourceRoom = if (hud.sourceLabel.isNotBlank()) 18f else 0f
        val available = GlassKit.READABLE_CONTENT_BOTTOM - top - 8f - sourceRoom
        val cardH = if (cards.size >= 2) (available - gap) / 2f else available
        val bodyScale = 0.9f
        val eyebrowScale = 0.65f
        val padX = 18f
        val bodyStep = HudText.lineHeight(bodyScale, false) + 4f
        val eyebrowStep = HudText.lineHeight(eyebrowScale, false) + 2f
        val bodyMaxW = 576f - padX * 2f
        val before = getDrawables().toHashSet()
        cards.take(2).forEachIndexed { i, card ->
            val y = top + i * (cardH + gap)
            val eyebrowRaw = card.eyebrow.ifBlank { if (i == 0) "LIVE CAPTION" else "SUGGESTED REPLY" }
            if (eyebrowRaw == "WRAPPROBE") {
                val textTop = y + 10f + HudText.lineHeight(0.65f, false) + 6f
                add(HudPrimitives.labelInk("WRAPPROBE", 32f + padX, y + 10f, 0.65f, HudTheme.InkMuted, maxWidth = 0f))
                card.body.split('\n').forEachIndexed { li, line ->
                    add(HudPrimitives.label(line, 32f + padX, textTop + li * bodyStep, bodyScale, M2Color.White, bold = false, maxWidth = 0f))
                }
                return@forEachIndexed
            }
            // Measured wrap, never mid-word ellipsis — saved titles like REVIEW must stay whole.
            val eyebrowLines = GlassKit.wrapMeasured(eyebrowRaw, bodyMaxW, eyebrowScale, false).take(2)
            eyebrowLines.forEachIndexed { ei, line ->
                GlassKit.addMeasuredLine(
                    this, line, 32f + padX, y + 10f + ei * eyebrowStep, eyebrowScale,
                    ink = HudTheme.InkMuted,
                )
            }
            val textTopInset = 10f + eyebrowLines.size.coerceAtLeast(1) * eyebrowStep + 4f
            val hint = if (i == 0) hud.contentOverflowHint.trim() else ""
            val maxLinesNoHint = ((cardH - textTopInset - 6f) / bodyStep).toInt().coerceIn(1, 8)
            // Only keep the overflow line when the card still has two body lines.
            // Half-height live cards drop it rather than sit it on SUMMARY/SAVE/STOP.
            val drawHint = hint.isNotBlank() && maxLinesNoHint >= 3
            val maxLines = if (drawHint) maxLinesNoHint - 1 else maxLinesNoHint
            val textTop = y + textTopInset
            val source = card.body.ifBlank { " " }
            val page = if (i == 0) hud.contentPage else 0
            // Full card width minus L/R pad. Measured wrap; 42-char objects only as
            // same-baseline chunks (isolated probe: 42 draws, 44+ vanishes).
            val all = source.split('\n').flatMap { para ->
                if (para.isBlank()) listOf("") else GlassKit.wrapMeasured(para, bodyMaxW, bodyScale, false)
            }
            val pages = GlassKit.paginateLines(all, maxLines)
            val pageIndex = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            val lines = pages.getOrElse(pageIndex) { emptyList() }
            lines.take(maxLines).forEachIndexed { li, line ->
                GlassKit.addMeasuredLine(
                    this, line, 32f + padX, textTop + li * bodyStep, bodyScale, M2Color.White, bold = false,
                )
            }
            val footerY = y + cardH - 18f
            val showPage = pages.size > 1 && i == 0
            if (drawHint) {
                val hintW = if (showPage) bodyMaxW - 56f else bodyMaxW
                val hintLine = GlassKit.wrapMeasured(hint, hintW, 0.65f, false).firstOrNull().orEmpty()
                GlassKit.addMeasuredLine(this, hintLine, 32f + padX, footerY, 0.65f, ink = HudTheme.InkMuted)
            }
            if (showPage) {
                add(
                    HudPrimitives.labelInk(
                        "${pageIndex + 1}/${pages.size}",
                        32f + 576f - 56f, footerY, 0.65f, HudTheme.InkMuted,
                    ),
                )
            }
        }
        if (cards.isEmpty()) {
            add(HudPrimitives.labelInk(hud.subtitle.ifBlank { "Ready" }, 50f, top + 40f, 0.9f, HudTheme.InkMuted, maxWidth = 540f))
        }
        getDrawables().forEach { if (it !in before) liveText.add(it) }
        sanitizeAdded(liveText)
    }

    private fun drawShop() {
        val top = GlassKit.READABLE_CONTENT_TOP
        val product = hud.product
        var y = top
        // Inner cards: glass=false so live labels stay above opaque plate fills (same as ListApp rows).
        if (product != null) {
            val prodH = 88f
            GlassKit.card(this, 32f, y, 576f, prodH, radius = 16f, glass = false)
            var textX = 48f
            val thumb = product?.thumbBytes
            if (thumb != null && product.thumbWidth > 0 && product.thumbHeight > 0) {
                val drew = croppedJpeg(thumb, 64, 64)?.let { c ->
                    createHudImage(
                        c.bytes, c.w, c.h, 44f, y + 12f, 64f, 64f,
                        circular = false, cornerRadius = 12f,
                    )?.also { add(it); textX = 124f }
                } != null
                if (!drew) {
                    createHudImage(
                        thumb, product.thumbWidth, product.thumbHeight,
                        44f, y + 12f, 64f, 64f,
                        circular = false, cornerRadius = 12f,
                    )?.let { add(it); textX = 124f }
                }
            }
            val title = product.title.ifBlank { hud.title }
            val meta = product.meta
            val status = product.status
            val titleW = 576f - textX - 24f
            val titleScale = 0.95f
            // Char-count wrap — no HudText.width mid-build (scrambles later offer labels).
            val titleLines = title.wrapHudLines(if (textX > 100f) 26 else 34).take(2)
            titleLines.forEachIndexed { i, line ->
                add(HudPrimitives.label(line, textX, y + 10f + i * 22f, titleScale, M2Color.White, bold = true, maxWidth = titleW))
            }
            if (meta.isNotBlank() && !(titleLines.size > 1 && status.isNotBlank())) {
                val metaY = y + 10f + titleLines.size * 22f + 2f
                val metaLine = meta.wrapHudLines(if (textX > 100f) 30 else 38).firstOrNull().orEmpty()
                add(HudPrimitives.labelInk(metaLine, textX, if (status.isNotBlank()) metaY.coerceAtMost(y + prodH - 40f) else metaY, 0.72f, HudTheme.InkMuted, maxWidth = titleW))
            }
            if (status.isNotBlank()) {
                add(HudPrimitives.labelInk(status.wrapHudLines(40).first(), textX, y + prodH - 22f, 0.65f, HudTheme.InkMuted, maxWidth = titleW))
            }
            y += prodH + 8f
        }

        val offers = hud.offers
        if (offers.isNotEmpty()) {
            val rowH = 52f
            val gap = 6f
            offers.take(2).forEachIndexed { i, offer ->
                val oy = if (product != null) com.mymav.glasses.api.HudActionLayout.offerRow(i).y else y + i * (rowH + gap)
                GlassKit.card(this, 32f, oy, 576f, rowH, selected = offer.selected, radius = 14f, glass = false)
                // Soft clip via maxWidth — full retailer/price remain in Open-on-phone / paging, no "…".
                val price = offer.priceLabel.wrapHudLines(14).first()
                val priceW = HudText.width(price, 0.95f, true).coerceAtMost(180f)
                val priceY = oy + ((rowH - HudText.lineHeight(0.95f, true)) / 2f).coerceAtLeast(2f)
                add(HudPrimitives.label(offer.retailer.wrapHudLines(26).first(), 48f, oy + 8f, 0.85f, M2Color.White, bold = true, maxWidth = (592f - priceW - 16f - 48f).coerceAtMost(300f)))
                add(HudPrimitives.label(price, 592f - priceW, priceY, 0.95f, M2Color.White, bold = true, maxWidth = 180f))
                if (offer.delivery.isNotBlank()) {
                    add(HudPrimitives.labelInk(offer.delivery.wrapHudLines(42).first(), 48f, oy + 30f, 0.68f, HudTheme.InkMuted, maxWidth = (592f - priceW - 16f - 48f).coerceAtMost(360f)))
                }
            }
            y += offers.size.coerceAtMost(2) * (rowH + gap)
        } else if (hud.cards.isNotEmpty()) {
            val card = hud.cards.first()
            val h = (GlassKit.READABLE_CONTENT_BOTTOM - y - 4f).coerceIn(80f, 140f)
            GlassKit.card(this, 32f, y, 576f, h, radius = 16f, glass = false)
            if (card.eyebrow.isNotBlank()) {
                add(HudPrimitives.labelInk(card.eyebrow, 50f, y + 14f, 0.65f, HudTheme.InkMuted, maxWidth = 540f))
            }
            GlassKit.wrapAll(card.body, 540f, 0.88f, false).take(3).forEachIndexed { i, line ->
                add(HudPrimitives.label(line, 50f, y + 40f + i * 24f, 0.88f, M2Color.White, bold = false, maxWidth = 540f))
            }
        }

        if (hud.offerPageLabel.isNotBlank()) {
            add(
                HudPrimitives.labelInk(
                    hud.offerPageLabel, 32f, GlassKit.READABLE_CONTENT_BOTTOM - 14f, 0.65f, HudTheme.InkMuted, maxWidth = 576f,
                ),
            )
        }
    }

    /** Food result: photo + name/portion + value tiles, then estimate notes. */
    private fun drawFood() {
        val product = hud.product ?: return
        val top = GlassKit.READABLE_CONTENT_TOP
        val cardH = 176f
        GlassKit.card(this, 32f, top, 576f, cardH, radius = 16f, glass = false)
        var textX = 48f
        val thumb = product.thumbBytes
        if (thumb != null) {
            val drew = croppedJpeg(thumb, 128, 128)?.let { c ->
                createHudImage(c.bytes, c.w, c.h, 46f, top + 24f, 128f, 128f, circular = false, cornerRadius = 14f)
                    ?.also { add(it); textX = 190f }
            } != null
            if (!drew) {
                val tw = if (product.thumbWidth > 0) product.thumbWidth else 128
                val th = if (product.thumbHeight > 0) product.thumbHeight else 128
                createHudImage(thumb, tw, th, 46f, top + 24f, 128f, 128f, circular = false, cornerRadius = 14f)
                    ?.also { add(it); textX = 190f }
            }
        }
        val textW = 592f - textX
        val titleLines = product.title.wrapHudLines(if (textX > 100f) 24 else 40).take(2)
        titleLines.forEachIndexed { i, line ->
            add(HudPrimitives.label(line, textX, top + 14f + i * 26f, 1.1f, M2Color.White, bold = true, maxWidth = textW))
        }
        val metaY = top + 14f + titleLines.size * 26f + 4f
        if (product.meta.isNotBlank()) {
            add(HudPrimitives.labelInk(product.meta.wrapHudLines(40).first(), textX, metaY, 0.72f, HudTheme.InkMuted, maxWidth = textW))
        }
        val tiles = hud.metrics.take(4)
        val tileY = top + cardH - 72f
        if (tiles.isNotEmpty()) {
            val gap = 10f
            val tw = (textW - gap * (tiles.size - 1)) / tiles.size
            tiles.forEachIndexed { i, m ->
                val tx = textX + i * (tw + gap)
                GlassKit.card(this, tx, tileY, tw, 58f, radius = 12f, glass = false)
                add(HudPrimitives.centeredPillLabel(m.value, tx, tileY + 2f, tw, 32f, 1.0f, M2Color.White, bold = true))
                add(HudPrimitives.centeredPillLabel(m.label, tx, tileY + 28f, tw, 26f, 0.62f, HudTheme.TextMuted))
            }
        } else {
            hud.cards.firstOrNull()?.body?.let { GlassKit.wrapAll(it, textW, 0.82f, false) }?.take(3)?.forEachIndexed { i, line ->
                add(HudPrimitives.label(line, textX, tileY - 8f + i * 22f, 0.82f, M2Color.White, bold = false, maxWidth = textW))
            }
        }
        val notes = product.status
        if (notes.isNotBlank()) {
            GlassKit.wrapAll(notes, 570f, 0.7f, false).take(2).forEachIndexed { i, line ->
                add(HudPrimitives.labelInk(line, 36f, top + cardH + 10f + i * 22f, 0.7f, HudTheme.InkMuted, maxWidth = 570f))
            }
        }
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        when (touch) {
            M2Touch.LongTap -> hud.actions.onHomeRequested?.invoke()
            M2Touch.Up -> {
                if (readable && hud.footerLabels.isNotEmpty()) {
                    // Prefer footer focus for sim Up when readable; classic keeps HOME dwell arm.
                    val n = hud.footerLabels.size
                    val next = (hud.footerFocus - 1).coerceAtLeast(0)
                    if (next != hud.footerFocus) {
                        hud.actions.onFooterFocusChanged?.invoke(next)
                    } else {
                        GlassesHomeController.rememberAppHud(hud)
                        GlassesHomeController.simulateHomePillGazeDwell()
                    }
                } else {
                    GlassesHomeController.rememberAppHud(hud)
                    GlassesHomeController.simulateHomePillGazeDwell()
                }
            }
            M2Touch.Backward -> {
                if (readable && hud.footerLabels.isNotEmpty()) {
                    hud.actions.onFooterFocusChanged?.invoke((hud.footerFocus - 1).coerceAtLeast(0))
                }
            }
            M2Touch.Forward, M2Touch.Down -> {
                if (readable && hud.footerLabels.isNotEmpty()) {
                    hud.actions.onFooterFocusChanged?.invoke(
                        (hud.footerFocus + 1).coerceAtMost(hud.footerLabels.lastIndex),
                    )
                }
            }
            M2Touch.Tap -> {
                if (readable && hud.footerLabels.isNotEmpty()) {
                    GlassesHomeController.activateAppFooter(hud, hud.footerFocus.coerceIn(0, hud.footerLabels.lastIndex))
                }
            }
        }
    }

    private class Cropped(val bytes: ByteArray, val w: Int, val h: Int)

    private companion object {
        /** Display-size crops of recent product/meal photos: rebuilding a result screen never re-decodes the photo. */
        private val crops = object : LinkedHashMap<String, Cropped>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cropped>?): Boolean = size > 6
        }
    }

    private fun croppedJpeg(bytes: ByteArray, w: Int, h: Int): Cropped? {
        val key = "${w}x$h:${bytes.size}:${bytes.contentHashCode()}"
        synchronized(crops) { crops[key] }?.let { HudPerf.count("img.crop.hit"); return it }
        return decodeCropped(bytes, w, h)?.also { c -> synchronized(crops) { crops[key] = c } }
    }

    private fun decodeCropped(bytes: ByteArray, w: Int, h: Int): Cropped? = HudPerf.timed("img.crop") {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) {
                // Likely raw RGBA from Identify synthetic thumbs — skip JPEG path.
                return@runCatching null
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w * 2 && bounds.outHeight / (sample * 2) >= h * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val srcAspect = decoded.width.toFloat() / decoded.height
            val dstAspect = w.toFloat() / h
            val src = if (srcAspect > dstAspect) {
                val cw = (decoded.height * dstAspect).toInt().coerceAtLeast(1)
                val l = ((decoded.width - cw) / 2).coerceAtLeast(0)
                Rect(l, 0, l + cw, decoded.height)
            } else {
                val ch = (decoded.width / dstAspect).toInt().coerceAtLeast(0)
                val t = ((decoded.height - ch) / 2).coerceAtLeast(0)
                Rect(0, t, decoded.width, t + ch)
            }
            Canvas(bmp).drawBitmap(decoded, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val st = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, st)
            decoded.recycle(); bmp.recycle()
            Cropped(st.toByteArray(), w, h)
        }.getOrNull()
    }
}
