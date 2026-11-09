package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.utils.M2Color
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud

/**
 * Objects / Identify result HUD — thumbnail + most specific ID, details card,
 * bottom Rescan · Details · Find prices (640×400 readable layout).
 */
class IdentifyResultScreen(
    initial: GlassesHud.IdentifyResult,
) : HudScreen(tag = "mymav-identify-result") {
    private var hud: GlassesHud.IdentifyResult = initial
    private val gazeDyn = mutableListOf<com.everysight.mav2.sdk.uikit.base.M2Drawable>()
    /** The session swaps screens 80ms before onCreate; patching an unbuilt screen would draw the row twice. */
    private var built = false

    /** Gaze focus/progress ticks patch the action row in place; anything else rebuilds. */
    override fun tryPatch(next: GlassesHud): Boolean {
        if (next !is GlassesHud.IdentifyResult || !built) return false
        val gazeOnly = next.copy(
            footerFocus = hud.footerFocus,
            footerGazeProgress = hud.footerGazeProgress,
            homeDwellProgress = hud.homeDwellProgress,
            actions = hud.actions,
        ) == hud
        if (!gazeOnly) return false
        hud = next
        gazeDyn.forEach { d -> runCatching { remove(d) } }
        gazeDyn.clear()
        drawActionRow()
        sanitizeAdded(gazeDyn)
        patchHomeDwell(next.homeDwellProgress)
        return true
    }

    private fun drawActionRow() {
        val before = getDrawables().toHashSet()
        val labels = hud.footerLabels.ifEmpty { listOf("Rescan", "Details", "Find prices") }
        GlassKit.readableActionRow(this, labels, hud.footerFocus, gazeProgress = hud.footerGazeProgress, disabled = hud.footerDisabled)
        getDrawables().forEach { if (it !in before) gazeDyn.add(it) }
    }
    override val glassScope: String? = com.mymav.glasses.api.MymavHudPrefs.appScope("identify")

    private val accent = hud.accent.toM2Color()
    private val background = HudPrimitives.fullBleedBackground()

    override fun build() {
        add(background)
        GlassKit.appPanel(this)
        val iconId = "identify".takeIf { PremiumHudIcons.has(it) }
        GlassKit.readableHeader(this, hud.headerTitle.ifBlank { "Objects" }, iconId)

        if (hud.showingDetails) {
            drawDetailsPages()
        } else {
            drawIdentity()
            drawAttributes()
        }

        drawActionRow()
        if (hud.sourceLabel.isNotBlank() && !hud.showingDetails) {
            add(HudPrimitives.labelInk(hud.sourceLabel, 32f, 308f, 0.6f, HudTheme.InkMuted, maxWidth = 576f))
        }
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
        built = true
    }

    private fun drawIdentity() {
        val y = GlassKit.READABLE_CONTENT_TOP
        val h = 92f
        GlassKit.card(this, 32f, y, 576f, h, radius = 16f, glass = false)
        var textX = 48f
        val img = hud.images.firstOrNull()
        if (img != null) {
            createHudImage(
                img.bytes, img.width, img.height,
                44f, y + 14f, 64f, 64f,
                circular = false, cornerRadius = 12f,
            )?.let { add(it); textX = 124f }
        }
        val eyebrow = hud.identityEyebrow.ifBlank { "IDENTIFIED FROM LABEL" }
        add(HudPrimitives.labelInk(eyebrow, textX, y + 10f, 0.62f, HudTheme.InkMuted, maxWidth = 576f - textX - 24f))

        // Brand / name / variant ahead of generic description.
        val primary = buildList {
            if (hud.brand.isNotBlank() && hud.productName.isNotBlank()) {
                add("${hud.brand} ${hud.productName}".trim())
            } else if (hud.productName.isNotBlank()) {
                add(hud.productName)
            } else {
                add(hud.label)
            }
        }.first()
        val meta = listOf(hud.brand.takeIf { hud.productName.isNotBlank() && it.isNotBlank() && !primary.startsWith(it) },
            hud.variant, hud.size.takeIf { it.isNotBlank() })
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank {
                listOf(hud.variant, hud.productType).filter { it.isNotBlank() }.joinToString(" · ")
            }

        // Char-count wrap — avoid HudText measure mid-build (scrambles later labels on cold fonts).
        val titleW = 576f - textX - 24f
        val titleLines = primary.wrapHudLines(if (textX > 100f) 28 else 36).take(2)
        titleLines.forEachIndexed { i, line ->
            add(
                HudPrimitives.label(
                    line, textX, y + 28f + i * 22f, 1.05f, M2Color.White, bold = true,
                    maxWidth = titleW,
                ),
            )
        }
        if (meta.isNotBlank()) {
            val metaY = y + 28f + titleLines.size * 22f + 2f
            val metaLine = meta.wrapHudLines(if (textX > 100f) 32 else 40).firstOrNull().orEmpty()
            if (metaLine.isNotBlank()) {
                add(HudPrimitives.labelInk(metaLine, textX, metaY, 0.72f, HudTheme.InkMuted, maxWidth = titleW))
            }
        }
    }

    private fun drawAttributes() {
        val y = GlassKit.READABLE_CONTENT_TOP + 100f
        val h = GlassKit.READABLE_CONTENT_BOTTOM - y - 4f
        GlassKit.card(this, 32f, y, 576f, h, radius = 16f, glass = false)
        val attrs = hud.attributes.ifEmpty {
            buildList {
                if (hud.size.isNotBlank()) add(com.mymav.glasses.api.HudReadableAttribute("Size", hud.size))
                if (hud.productType.isNotBlank()) add(com.mymav.glasses.api.HudReadableAttribute("Type", hud.productType))
                if (hud.barcode.isNotBlank()) {
                    add(com.mymav.glasses.api.HudReadableAttribute("Barcode", hud.barcode))
                } else {
                    add(com.mymav.glasses.api.HudReadableAttribute("Barcode", "not captured"))
                }
                if (hud.confidence > 0f) {
                    add(com.mymav.glasses.api.HudReadableAttribute("Match", "${(hud.confidence * 100).toInt()}%"))
                }
            }
        }
        val rowH = 22f
        attrs.take(5).forEachIndexed { i, attr ->
            val ry = y + 14f + i * rowH
            add(HudPrimitives.labelInk(attr.label, 50f, ry, 0.72f, HudTheme.InkMuted, maxWidth = 140f))
            val value = attr.value.wrapHudLines(34).firstOrNull().orEmpty()
            if (value.isNotBlank()) {
                add(HudPrimitives.label(value, 200f, ry, 0.78f, M2Color.White, maxWidth = 380f))
            }
        }
        val instruction = hud.nextInstruction.ifBlank { hud.uncertainty }
        if (instruction.isNotBlank()) {
            val cue = instruction.wrapHudLines(48).firstOrNull().orEmpty()
            add(
                HudPrimitives.labelInk(
                    cue, 50f, y + h - 28f, 0.68f, HudTheme.InkMuted, maxWidth = 540f,
                ),
            )
        }
    }

    private fun drawDetailsPages() {
        val y = GlassKit.READABLE_CONTENT_TOP
        val h = GlassKit.READABLE_CONTENT_BOTTOM - y - 4f
        GlassKit.card(this, 32f, y, 576f, h, radius = 16f, glass = false)
        add(HudPrimitives.labelInk("DETAILS", 50f, y + 12f, 0.65f, HudTheme.InkMuted, maxWidth = 200f))
        val body = hud.detailsBody.ifBlank {
            listOf(hud.label, hud.detail, hud.uncertainty, hud.nextInstruction)
                .filter { it.isNotBlank() }.joinToString("\n")
        }
        val bodyScale = 0.85f
        val textTop = y + 36f
        val bodyStep = HudText.lineHeight(bodyScale, false) + 4f
        val maxLines = ((h - 56f) / bodyStep).toInt().coerceIn(3, 8)
        val all = body.split('\n').flatMap { para ->
            if (para.isBlank()) listOf("") else GlassKit.wrapAll(para, 540f, bodyScale, false)
        }
        val pages = GlassKit.paginateLines(all, maxLines)
        val pageIndex = hud.contentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        pages.getOrElse(pageIndex) { emptyList() }.take(maxLines).forEachIndexed { i, line ->
            add(HudPrimitives.label(line, 50f, textTop + i * bodyStep, bodyScale, M2Color.White, bold = false, maxWidth = 540f))
        }
        if (pages.size > 1) {
            add(
                HudPrimitives.labelInk(
                    "Page ${pageIndex + 1}/${pages.size}",
                    50f, y + h - 24f, 0.65f, HudTheme.InkMuted,
                ),
            )
        }
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        val labels = hud.footerLabels.ifEmpty { listOf("Rescan", "Details", "Find prices") }
        when (touch) {
            M2Touch.Backward, M2Touch.Up -> {
                if (hud.showingDetails && hud.contentPageCount > 1 && touch == M2Touch.Backward) {
                    hud.actions.onContentPageChanged?.invoke((hud.contentPage - 1).coerceAtLeast(0))
                } else {
                    hud.actions.onFooterFocusChanged?.invoke((hud.footerFocus - 1).coerceAtLeast(0))
                        ?: run {
                            GlassesHomeController.rememberAppHud(hud)
                            GlassesHomeController.simulateHomePillGazeDwell()
                        }
                }
            }
            M2Touch.Forward, M2Touch.Down -> {
                if (hud.showingDetails && hud.contentPageCount > 1 && touch == M2Touch.Forward) {
                    hud.actions.onContentPageChanged?.invoke(
                        (hud.contentPage + 1).coerceAtMost((hud.contentPageCount - 1).coerceAtLeast(0)),
                    )
                } else {
                    hud.actions.onFooterFocusChanged?.invoke(
                        (hud.footerFocus + 1).coerceAtMost(labels.lastIndex),
                    )
                }
            }
            M2Touch.Tap -> {
                val idx = hud.footerFocus.coerceIn(0, labels.lastIndex)
                val activated = hud.actions.onFooterActivated
                if (activated != null) {
                    GlassesHomeController.activateAppFooter(hud, idx)
                } else {
                    when (labels.getOrNull(idx)?.lowercase()) {
                        "find prices" -> hud.actions.onFindPrices?.invoke()
                            ?: hud.actions.onAgain?.invoke()
                        "details" -> hud.actions.onDetails?.invoke()
                        else -> hud.actions.onAgain?.invoke()
                    }
                }
            }
            M2Touch.LongTap -> {
                val home = hud.actions.onHomeRequested
                if (home != null) home.invoke() else hud.actions.onDismiss?.invoke()
            }
        }
    }
}
