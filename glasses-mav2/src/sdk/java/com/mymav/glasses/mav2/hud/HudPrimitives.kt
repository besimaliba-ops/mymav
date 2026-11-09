package com.mymav.glasses.mav2.hud

import android.util.Log
import com.everysight.mav2.sdk.resources.M2CacheScope
import com.everysight.mav2.sdk.resources.M2FontResource
import com.everysight.mav2.sdk.resources.M2ImageBytes
import com.everysight.mav2.sdk.uikit.data.M2GradientFill
import com.everysight.mav2.sdk.uikit.data.M2GradientLength
import com.everysight.mav2.sdk.uikit.data.M2GradientLinear
import com.everysight.mav2.sdk.uikit.data.M2ImageType
import com.everysight.mav2.sdk.uikit.data.M2RoundedCorners
import com.everysight.mav2.sdk.uikit.data.M2TextureFit
import com.everysight.mav2.sdk.uikit.drawables.M2Image
import com.everysight.mav2.sdk.uikit.drawables.M2Rect
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.HomeBoardCatalog

/** Off-canvas park position for live layers that are temporarily hidden. */
internal const val PARK = -400f

internal fun M2Rect.park() {
    setDimensions(PARK, PARK, 1f, 1f)
}

/**
 * Text metrics backed by the SDK's own glyph table ([M2FontResource.getMeasuredContentWidth]).
 * The old code guessed `11.2px × scale` per character, which is why pill labels sat
 * off-centre and lyrics / titles spilled past their panels.
 */
object HudText {
    private const val TAG = "HudText"
    private const val EST_CHAR_W = 11.2f
    private const val EST_LINE_H = 22f

    @Volatile private var measureBroken = false

    /**
     * The glasses/simulator reuse the layout of a string they have already drawn: a label such as "Health" that was
     * first drawn in a 16 px font keeps drawing at 16 px after the screen asks for 32 px (observed on the simulator,
     * also after app restarts and font reloads). Every drawn string therefore carries a short run of zero-width
     * characters that encodes (font object, scale, process); a different font/size/generation is a different string.
     * Nothing is visible (zero advance); text is measured and fitted without the token.
     *
     * SCOPE / LIMITS: this is a simulator-observed workaround for a layout cache (SDK 0.2.0 shared 50-entry cache),
     * not a documented SDK feature. It is applied to every M2Text through [HudScreen] sanitising and
     * [com.mymav.glasses.mav2.hud.HudText.setHudText]. Whether zero-width characters are inert on the physical
     * Maverick glasses has NOT been verified (the glyphs might render as boxes or add advance). Until that is
     * checked on hardware, set [layoutTokens] to false to get the plain-string path; the font lifecycle itself
     * does not depend on the tokens. Preferred long-term fix: a supported layout invalidation / distinct font
     * resource identity from the SDK.
     */
    @Volatile var layoutTokens: Boolean = true
    private val salt: Int = (System.nanoTime() xor (System.currentTimeMillis() shl 7)).toInt()
    private val zw = charArrayOf('\u200B', '\u200C', '\u200D', '\u2060')

    fun withLayoutToken(text: String, font: M2FontResource?, scale: Float): String {
        if (!layoutTokens || text.isBlank()) return text
        var h = salt xor ((font?.let { System.identityHashCode(it) } ?: 0) * 31 + java.lang.Float.floatToIntBits(scale))
        h = h xor (h ushr 16); h *= 0x45d9f3b; h = h xor (h ushr 16)
        var v = h and 0x3FFF
        val sb = StringBuilder(text.length + 7).append(text)
        repeat(7) { sb.append(zw[v and 3]); v = v ushr 2 }
        return sb.toString()
    }

    /** Default weight for a label designed at stock [scale]. */
    fun boldFor(scale: Float, tracked: Boolean = false): Boolean = tracked || scale >= 1.05f

    fun width(text: String, scale: Float, bold: Boolean = boldFor(scale)): Float {
        if (text.isEmpty()) return 0f
        val pick = HudFonts.pick(scale, bold)
        if (!measureBroken) {
            val measured = runCatching { pick.font.getMeasuredContentWidth(text, pick.scale) }
                .onFailure {
                    measureBroken = true
                    Log.w(TAG, "font metrics unavailable, using estimate: ${it.message}")
                }
                .getOrDefault(-1f)
            if (measured > 0f) return measured
        }
        return text.length * EST_CHAR_W * scale
    }

    fun lineHeight(scale: Float, bold: Boolean = boldFor(scale)): Float {
        val pick = HudFonts.pick(scale, bold)
        if (!measureBroken) {
            val h = runCatching { pick.font.getMeasuredContentHeight("Hg", pick.scale) }.getOrDefault(-1f)
            if (h > 0f) return h
        }
        return EST_LINE_H * scale
    }

    /**
     * Line box including descenders (`g j p q y`) and a typical accent (`Áç`).
     * Use this for row/clip bounds. Does not change the drawn font size.
     */
    fun boxHeight(scale: Float, bold: Boolean = boldFor(scale)): Float {
        val pick = HudFonts.pick(scale, bold)
        if (!measureBroken) {
            val h = runCatching { pick.font.getMeasuredContentHeight("HgypqÁç", pick.scale) }.getOrDefault(-1f)
            if (h > 0f) return h + 2f
        }
        return lineHeight(scale, bold) + 3f
    }

    /**
     * Title line box: includes descender overshoot (`g j p q y`) plus a few px so
     * SansBold 24 bottoms are not clipped. Does not change the drawn font size.
     */
    fun titleHeight(scale: Float): Float = boxHeight(scale, true) + 1f

    /** Trim [raw] with a trailing "..." so it fits in [maxWidth] px. */
    fun fit(
        raw: String,
        maxWidth: Float,
        scale: Float,
        bold: Boolean = boldFor(scale),
        /** Keep the end of the text (typing fields) instead of the start. */
        fromEnd: Boolean = false,
    ): String {
        val text = hudSafe(raw)
        if (maxWidth <= 0f || width(text, scale, bold) <= maxWidth) return text
        if (fromEnd) {
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (width(text.substring(mid), scale, bold) <= maxWidth) hi = mid else lo = mid + 1
            }
            return text.substring(lo)
        }
        val dots = "..."
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(text.take(mid).trimEnd() + dots, scale, bold) <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo <= 0) dots else text.take(lo).trimEnd() + dots
    }

    /**
     * Word-wrap to a pixel width. The last allowed line is ellipsised when the
     * text is longer than [maxLines].
     */
    fun wrap(raw: String, maxWidth: Float, scale: Float, maxLines: Int, bold: Boolean = boldFor(scale)): List<String> {
        val words = hudSafe(raw).replace(Regex("\\s+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (width(candidate, scale, bold) <= maxWidth) {
                current = candidate
                index++
                continue
            }
            if (current.isEmpty()) {
                current = fit(word, maxWidth, scale, bold)
                index++
            }
            lines += current
            current = ""
            if (lines.size == maxLines) break
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current
        if (index < words.size && lines.isNotEmpty()) {
            val rest = (listOf(lines.last()) + words.drop(index)).joinToString(" ")
            lines[lines.size - 1] = fit(rest, maxWidth, scale, bold)
        }
        return lines
    }

    /** Build an [M2Text] in the font chosen for [scale] / [bold]. */
    fun make(text: String, x: Float, y: Float, scale: Float, bold: Boolean): M2Text {
        val pick = HudFonts.pick(scale, bold)
        return M2Text(withLayoutToken(text.ifEmpty { " " }, pick.font, pick.scale)).apply {
            if (pick.roboto) setFont(pick.font)
            setXY(x, y)
            setScale(pick.scale)
        }
    }

    /**
     * Move a raw stock-font [M2Text] (built directly by a screen) onto Roboto.
     * Its designed stock scale is preserved as the size request.
     */
    fun upgrade(t: M2Text) {
        if (!HudFonts.ready()) return
        val current = runCatching { t.font }.getOrNull() ?: return
        if (!current.isStockFont()) return
        val pick = HudFonts.pick(t.scale, boldFor(t.scale))
        if (!pick.roboto) return
        t.setFont(pick.font)
        t.setScale(pick.scale)
    }
}

/**
 * Smoked-glass HUD helpers — rounded panels, hairline edges, soft focus ring.
 */
object HudPrimitives {
    data class SoftPanel(
        val fill: M2RectFilled,
        val outline: M2RectOutline,
        /** Subtle top sheen so panels read as glass, not flat plates. */
        val gloss: M2RectFilled? = null,
        /** Bright hairline highlight just inside the top edge. */
        val rim: M2Rect? = null,
        /** Small reflected highlight near the bottom-right corner (large panels only). */
        val gloss2: M2RectFilled? = null,
        /** Corner reflections from the glass artwork (see [GlassSurface]); drawn above the body, below content. */
        val shineTl: M2Image? = null,
        val shineBr: M2Image? = null,
        /** Fine corner rim highlights (see [GlassSurface.cornerRims]); drawn above the border, below content. */
        val cornerRimTl: M2Rect? = null,
        val cornerRimBr: M2Rect? = null,
    )

    fun fullBleedBackground(): M2RectFilled =
        M2RectFilled(M2Color.Black).apply {
            setDimensions(0f, 0f, HudTheme.W, HudTheme.H)
        }

    /**
     * Smoked-glass panel: deep translucent body + hairline edge + faint top sheen.
     * [border] = [HudTheme.PanelBorder] means "neutral"; any other colour is an accent edge.
     */
    fun frostedPanel(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        border: M2Color = HudTheme.PanelBorder,
        radius: Float = HudTheme.PANEL_RADIUS,
        fillAlpha: Float = HudTheme.PANEL_ALPHA,
        borderAlpha: Float = HudTheme.BORDER_ALPHA,
        gradient: Boolean = true,
        /** Bright inner edge strip — widgets and app panels only (tall panels), not keys/pills. */
        innerGlow: Boolean = false,
        /** Draw the image glass surface (translucent body, rim, corner shine) instead of code gradients. */
        glass: Boolean = false,
    ): SoftPanel {
        val r = radius.coerceAtMost(minOf(w, h) / 2f)
        val useGlass = glass && w >= 40f && h >= 24f && GlassSurface.available()
        val pillShape = r >= h / 2f - 0.5f && h >= 24f
        val bodyAlpha = if (useGlass) (if (pillShape) GlassSurface.BODY_ALPHA_PILL else GlassSurface.BODY_ALPHA_PANEL) * GlassSurface.opacityFactor().coerceAtMost(1.2f) else fillAlpha
        val glossy = gradient && h >= 28f && !useGlass
        // Body: neutral graphite, lighter at the top fading to near-black at the bottom.
        val fill = M2RectFilled((if (useGlass) 0xFF1A3258u else HudTheme.InkPanelTop).withAlpha(if (useGlass) bodyAlpha else bodyAlpha)).apply {
            setDimensions(x, y, w, h)
            softCorners(r)
            // Body gradient is one fill spec (no extra drawable) — keys get it too.
            if (h >= 20f) {
                setFill(
                    M2GradientLinear(
                        M2GradientFill.Stop.Start,
                        M2GradientLength.fraction(1f),
                        90f,
                        (if (useGlass) 0xFF06112Au else HudTheme.InkPanel).withAlpha(if (useGlass) (bodyAlpha + 0.08f).coerceAtMost(0.9f) else bodyAlpha),
                    ),
                )
                softCorners(r)
            }
        }
        // Glass: softly lit blue-grey upper area that fades into the calm dark-blue centre and lower part.
        val topGlow = if (useGlass && h >= 60f) {
            M2RectFilled(0xFF7FA3D0u.withAlpha(0.26f)).apply {
                setDimensions(x + 1f, y + 1f, w - 2f, h * 0.55f)
                softCorners((r - 1f).coerceAtLeast(0f))
                setFill(
                    M2GradientLinear(
                        M2GradientFill.Stop.Start,
                        M2GradientLength.fraction(1f),
                        90f,
                        0xFF7FA3D0u.withAlpha(0.0f),
                    ),
                )
                softCorners((r - 1f).coerceAtLeast(0f))
            }
        } else null
        // Glass sheen over the upper part of the panel.
        val gloss = if (glossy) {
            M2RectFilled(M2Color.White.withAlpha(0.01f)).apply {
                setDimensions(x + 1f, y + 1f, w - 2f, h * HudTheme.PANEL_GLOSS_FRAC)
                softCorners((r - 1f).coerceAtLeast(0f))
                setFill(
                    M2GradientLinear(
                        M2GradientFill.Stop.Start,
                        M2GradientLength.fraction(1f),
                        90f,
                        M2Color.White.withAlpha(HudTheme.PANEL_GLOSS_ALPHA),
                    ),
                )
                softCorners((r - 1f).coerceAtLeast(0f))
            }
        } else {
            null
        }
        // Large panels: soft white-blue highlight at the top-left and a smaller reflection at the
        // bottom-right, both clipped inside the panel and drawn under the content.
        val big = gradient && w >= 120f && h >= 60f && !useGlass
        val glossMain = if (big) {
            M2RectFilled(M2Color.White.withAlpha(HudTheme.CORNER_SHEEN_ALPHA)).apply {
                setDimensions(x + 1f, y + 1f, w * 0.66f, h * 0.6f)
                setFill(
                    M2GradientLinear(
                        M2GradientFill.Stop.Start,
                        M2GradientLength.fraction(1f),
                        45f,
                        M2Color.White.withAlpha(0.0f),
                    ),
                )
                softCorners((r - 1f).coerceAtLeast(0f))
            }
        } else gloss
        val glossCorner = if (big) {
            val gw = (w * 0.32f).coerceAtMost(140f)
            val gh = (h * 0.30f).coerceAtMost(70f)
            // Mirror of the top-left sheen: strongest in the very corner, fading toward the panel centre.
            M2RectFilled(M2Color.White.withAlpha(HudTheme.CORNER_REFLECT_ALPHA)).apply {
                setDimensions(x + w - 1f - gw, y + h - 1f - gh, gw, gh)
                setFill(
                    M2GradientLinear(
                        M2GradientFill.Stop.Start,
                        M2GradientLength.fraction(1f),
                        225f,
                        M2Color.White.withAlpha(0.0f),
                    ),
                )
                softCorners((r - 1f).coerceAtLeast(0f))
            }
        } else null
        // Specular rim: a bright hairline just inside the top edge.
        // Bright strip just inside every edge (all four sides).
        val rim = if (innerGlow && w > 24f && h > 24f) {
            val inset = 4f
            M2RectOutline(HudTheme.InkRim.withAlpha(HudTheme.RIM_ALPHA)).apply {
                setDimensions(x + inset, y + inset, w - 2f * inset, h - 2f * inset)
                softCorners((r - inset).coerceAtLeast(2f))
                setStyle(1.5f)
            }
        } else {
            null
        }
        val neutral = border == HudTheme.PanelBorder
        val edgeColor = if (neutral) {
            HudTheme.InkEdgeBright.withAlpha(if (useGlass) 0.72f else 1f)
        } else {
            border.withAlpha(borderAlpha.coerceIn(0.45f, 1f))
        }
        val outline = M2RectOutline(edgeColor).apply {
            setDimensions(x, y, w, h)
            softCorners(r)
            setStyle(HudTheme.PANEL_STROKE_BRIGHT)
        }
        if (useGlass && GlassSurface.hasSurface(w, h, r)) {
            // Packaged neutral-glass surface replaces the code body + shine entirely (not stacked): the body fill is
            // parked, the single texture carries body + reflections, the fine edge stays code-drawn above it.
            val surface = GlassSurface.surface(x, y, w, h, r)
            if (surface != null) {
                val parked = M2RectFilled(M2Color.White.withAlpha(0.01f)).apply { park() }
                val rims = GlassSurface.cornerRims(x, y, w, h, r, accent = !neutral)
                return SoftPanel(parked, outline, null, null, null, shineTl = surface, shineBr = null, cornerRimTl = rims?.first, cornerRimBr = rims?.second)
            }
        }
        if (useGlass) {
            val (tl, br) = GlassSurface.shines(x, y, w, h, r)
            return SoftPanel(fill, outline, topGlow, null, null, shineTl = tl, shineBr = br)
        }
        return SoftPanel(fill, outline, glossMain, rim, glossCorner)
    }

    /** Focus = soft inner wash + crisp ring (no fat blurry halo). */
    fun focusGlow(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: M2Color = HudTheme.FocusGlow,
        radius: Float = HudTheme.PANEL_RADIUS,
    ): List<M2Rect> {
        val glowFill = M2RectFilled(color.withAlpha(HudTheme.FOCUS_FILL_ALPHA)).apply {
            setDimensions(x, y, w, h)
            softCorners(radius.coerceAtMost(minOf(w, h) / 2f))
        }
        val ring = M2RectOutline(color.withAlpha(HudTheme.FOCUS_ALPHA)).apply {
            setDimensions(x - 2f, y - 2f, w + 4f, h + 4f)
            softCorners((radius + 2f).coerceAtMost(minOf(w, h) / 2f + 2f))
            setStyle(HudTheme.FOCUS_STROKE)
        }
        return listOf(glowFill, ring)
    }

    /**
     * A single movable focus indicator. Screens add it once and [moveTo] it on
     * focus changes instead of rebuilding the whole scene.
     */
    class FocusLayer(color: M2Color = HudTheme.FocusGlow) {
        val wash = M2RectFilled(color.withAlpha(HudTheme.FOCUS_FILL_ALPHA)).apply { park() }
        val ring = M2RectOutline(color.withAlpha(HudTheme.FOCUS_ALPHA)).apply {
            park()
            setStyle(HudTheme.FOCUS_STROKE)
        }

        fun moveTo(x: Float, y: Float, w: Float, h: Float, radius: Float) {
            val r = radius.coerceAtMost(minOf(w, h) / 2f)
            wash.setDimensions(x, y, w, h)
            wash.softCorners(r)
            // Flush with the panel edge: selection recolours the single edge instead of adding a second ring.
            ring.setDimensions(x, y, w, h)
            ring.softCorners(r)
        }

        fun hide() {
            wash.park()
            ring.park()
        }
    }

    /** Soft action pill (full corner radius). */
    fun softPill(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        fill: M2Color = HudTheme.MicPill,
    ): SoftPanel {
        val r = h / 2f
        val body = M2RectFilled(fill.withAlpha(1f)).apply {
            setDimensions(x, y, w, h)
            softCorners(r)
        }
        // Glossy sheen over the top half, like the frosted selection pills.
        val gloss = M2RectFilled(M2Color.White.withAlpha(0.01f)).apply {
            setDimensions(x + 2f, y + 1.5f, w - 4f, h * 0.5f)
            softCorners((r - 2f).coerceAtLeast(1f))
            setFill(
                M2GradientLinear(
                    M2GradientFill.Stop.Start,
                    M2GradientLength.fraction(1f),
                    90f,
                    M2Color.White.withAlpha(0.38f),
                ),
            )
            softCorners((r - 2f).coerceAtLeast(1f))
        }
        val edge = M2RectOutline(M2Color.White.withAlpha(1f)).apply {
            setDimensions(x, y, w, h)
            softCorners(r)
            setStyle(1.5f)
        }
        return SoftPanel(body, edge, gloss)
    }

    /**
     * Dark-tinted pill for white labels. The display is additive, so a bright fill with black text
     * loses the text; a mid-tone fill with a bright rim keeps white text readable.
     */
    fun solidPill(x: Float, y: Float, w: Float, h: Float, fill: UInt, selected: Boolean = false): SoftPanel {
        val r = h / 2f
        val body = M2RectFilled(fill).apply { setDimensions(x, y, w, h); softCorners(r) }
        val edge = M2RectOutline(0xFFFFFFFFu.withAlpha(if (selected) 1f else 0.55f)).apply {
            setDimensions(x, y, w, h); softCorners(r); setStyle(if (selected) 2f else 1.2f)
        }
        return SoftPanel(body, edge)
    }

    /** Soft circular / rounded avatar mask over an [M2Image] or accent tile. */
    fun softAvatarMask(drawable: M2Rect, size: Float) {
        drawable.softCorners(size / 2f)
    }

    fun softDot(x: Float, y: Float, size: Float, color: M2Color): M2RectFilled =
        M2RectFilled(color).apply {
            setDimensions(x, y, size, size)
            softCorners(size / 2f)
        }

    /**
     * Text label. Always font-safe ([hudSafe]); never smaller than
     * [HudTheme.MIN_TEXT_SCALE]; trimmed with "..." when [maxWidth] is given.
     */
    fun label(
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: M2Color = HudTheme.TextPrimary,
        tracked: Boolean = false,
        maxWidth: Float = 0f,
        bold: Boolean = HudText.boldFor(scale, tracked),
    ): M2Text {
        val s = scale.coerceAtLeast(HudTheme.MIN_TEXT_SCALE)
        val base = if (tracked) trackedCaps(text) else hudSafe(text)
        val shown = if (maxWidth > 0f) HudText.fit(base, maxWidth, s, bold) else base
        return HudText.make(shown, x, y, s, bold).apply { setColor(color) }
    }

    /** Text label in a raw ARGB ink (e.g. [HudTheme.InkMuted]). */
    fun labelInk(
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        ink: UInt,
        maxWidth: Float = 0f,
    ): M2Text {
        val s = scale.coerceAtLeast(HudTheme.MIN_TEXT_SCALE)
        val base = hudSafe(text)
        val shown = if (maxWidth > 0f) HudText.fit(base, maxWidth, s, false) else base
        return HudText.make(shown, x, y, s, false).apply { setColor(ink) }
    }

    /** Centre [text] in a pill/rect using measured glyph widths. */
    fun centeredPillLabel(
        text: String,
        pillX: Float,
        pillY: Float,
        pillW: Float,
        pillH: Float,
        scale: Float,
        color: M2Color = HudTheme.TextPrimary,
        tracked: Boolean = false,
        bold: Boolean? = null,
    ): M2Text {
        val s = scale.coerceAtLeast(HudTheme.MIN_TEXT_SCALE)
        val bold = bold ?: HudText.boldFor(s, tracked)
        val base = if (tracked) trackedCaps(text) else hudSafe(text)
        val display = HudText.fit(base, pillW - 12f, s, bold)
        val textW = HudText.width(display, s, bold)
        val textH = HudText.boxHeight(s, bold)
        val x = pillX + ((pillW - textW) / 2f).coerceAtLeast(6f)
        val y = (pillY + ((pillH - textH) / 2f)).coerceIn(pillY + 1f, (pillY + pillH - textH - 1f).coerceAtLeast(pillY + 1f))
        return HudText.make(display, x, y, s, bold).apply { setColor(color) }
    }

    fun addPanel(screen: M2Screen, panel: SoftPanel, focused: Boolean = false) {
        screen.add(panel.fill)
        panel.gloss?.let { screen.add(it) }
        panel.gloss2?.let { screen.add(it) }
        panel.shineTl?.let { screen.add(it) }
        panel.shineBr?.let { screen.add(it) }
        panel.rim?.let { screen.add(it) }
        screen.add(panel.outline)
        panel.cornerRimTl?.let { screen.add(it) }
        panel.cornerRimBr?.let { screen.add(it) }
    }

    fun addPanel(screen: M2Screen, fill: M2RectFilled, outline: M2RectOutline) {
        screen.add(fill)
        screen.add(outline)
    }

    /** Live handle on the HOME pill so gaze-dwell progress patches in place. */
    class HomePillHandle(
        private val ring: M2RectOutline,
        private val bar: M2RectFilled,
        private val x: Float,
        private val y: Float,
        private val w: Float,
        private val h: Float,
    ) {
        fun update(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            if (p <= 0.02f) {
                ring.park()
                bar.park()
                return
            }
            ring.setDimensions(x - 3f, y - 3f, w + 6f, h + 6f)
            ring.softCorners(h / 2f + 3f)
            val barW = ((w - 24f) * p).coerceAtLeast(4f)
            bar.setDimensions(x + 12f, y + h - 7f, barW, 3f)
            bar.softCorners(1.5f)
        }
    }

    /**
     * HOME pill with 1s gaze-dwell progress. Coordinates match
     * [HomeBoardCatalog.HOME_PILL_X]. On a [HudScreen] the pill registers itself so
     * later dwell ticks patch the ring/bar instead of rebuilding the app screen.
     */
    fun drawHomePill(screen: M2Screen, dwellProgress: Float = 0f) {
        val x = HomeBoardCatalog.HOME_PILL_X
        val y = HomeBoardCatalog.HOME_PILL_Y
        val w = HomeBoardCatalog.HOME_PILL_W
        val h = HomeBoardCatalog.HOME_PILL_H
        // Glass HOME button: body + reflections first, crisp white label above, dwell feedback last.
        val pill = frostedPanel(x, y, w, h, radius = h / 2f, gradient = false, glass = true)
        addPanel(screen, pill)
        val iconSize = 14
        val labelScale = 0.62f
        val textW = HudText.width("HOME", labelScale, true)
        val textH = HudText.lineHeight(labelScale, true)
        val total = iconSize + 6f + textW
        val startX = x + (w - total) / 2f
        val iconY = y + (h - iconSize) / 2f
        createHudImage(
            GlassSurface.houseIcon(iconSize), iconSize, iconSize, startX, iconY,
            iconSize.toFloat(), iconSize.toFloat(), roundCorners = false, resident = true,
        )?.let { screen.add(it) }
        // Inter sits high vs the house; nudge lettering down so the pair is optically centred.
        val textY = iconY + (iconSize - textH) / 2f + 2f
        screen.add(HudPrimitives.label("HOME", startX + iconSize + 6f, textY, labelScale, M2Color.White, bold = true))
        val ring = M2RectOutline(HudTheme.FocusGlow.withAlpha(HudTheme.FOCUS_ALPHA)).apply {
            park()
            setStyle(2f)
        }
        val bar = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.9f)).apply { park() }
        screen.add(ring)
        screen.add(bar)
        val handle = HomePillHandle(ring, bar, x, y, w, h)
        handle.update(dwellProgress)
        (screen as? HudScreen)?.homePill = handle
    }
}

internal fun M2Rect.softCorners(radius: Float) {
    // SDK requires a positive radius; MIN_ROUNDED_CORNER_THICKNESS is internal.
    setCornerRadius(radius.coerceAtLeast(1f), M2RoundedCorners.All)
}

/** Explicitly clear rounded-corner mask (default None can be dirtied by prior style). */
internal fun M2Rect.sharpCorners() {
    setCornerRadius(0f, M2RoundedCorners.None)
}

/** Set text on a live [M2Text] through the font-safe filter. */
internal fun M2Text.setHudText(raw: String) {
    val safe = hudSafe(raw).ifEmpty { " " }
    val shown = HudText.withLayoutToken(safe, runCatching { font }.getOrNull(), scale)
    if (text != shown) setText(shown)
}

/** Build an [M2ImageBytes] resource for Stretch/Native HUD upload (JPEG or raw RGBA). */
internal fun hudImageResource(
    bytes: ByteArray,
    width: Int,
    height: Int,
    resident: Boolean = false,
): M2ImageBytes? {
    if (bytes.isEmpty() || width <= 0 || height <= 0) return null
    // Glasses-side image cache shares the 48-row table with fonts. Resident
    // CachedManualDelete rows (and freeResource on trim) evicted type-4 font
    // slots mid-navigation, so labels jumped size/face. Default is off: every
    // texture is NotCached. Packed-PNG encode cache stays on the phone.
    if (resident && HudImageCache.enabled && width * height <= HudImageCache.MAX_RESIDENT_PIXELS) {
        return HudImageCache.get(bytes, width, height)
    }
    HudPerf.count("img.tex.nocache")
    return newImageResource(bytes, width, height, M2CacheScope.NotCached)
}

internal fun newImageResource(source: ByteArray, width: Int, height: Int, scope: M2CacheScope): M2ImageBytes {
    val isRawRgba = source.size == width * height * 4
    if (!isRawRgba) return M2ImageBytes(source.copyOf(), width, height, scope)
    val key = HudEncodedCache.key(source, width, height)
    HudEncodedCache.get(key)?.let { return HudEncodedCache.reuse(it, width, height, scope) }
    HudPerf.count("img.enc.miss")
    // The SDK encodes raw RGBA in place, so a cached array handed over a second
    // time comes back scrambled (logos / mascot showing shredded copies after a
    // page change). Always give it a private copy.
    val res = M2ImageBytes(
        source.copyOf(),
        M2ImageType.Png,
        width,
        height,
        scope,
        /* encode = */ true,
        /* keepAlpha = */ true,
        /* dither = */ false,
    )
    HudEncodedCache.remember(key, res)
    return res
}

/**
 * Resident image textures shared across screens (LRU, bounded). One resource
 * object per distinct image content; evicted ones are freed on the glasses.
 */
internal object HudImageCache {
    const val MAX_RESIDENT_PIXELS = 128 * 128
    // Shared glasses table is ~48 rows (fonts + images). Wide ≤128px caching stole
    // type-4 font rows and blanked labels; keep this well below remaining slots.
    private const val MAX_ENTRIES = 16
    private const val MAX_PIXELS = 16 * 72 * 72

    /** Kill switch for the glasses-side texture trial (`texcache on|off`). Off: fonts keep their slots. */
    @Volatile var enabled: Boolean = false

    private class Entry(val res: M2ImageBytes, val pixels: Int)

    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var pixels = 0

    @Synchronized
    fun get(bytes: ByteArray, width: Int, height: Int): M2ImageBytes {
        val key = "${width}x$height#${bytes.size}:${bytes.contentHashCode()}"
        map[key]?.let {
            HudPerf.count("img.tex.hit")
            if (it.res.wasUploadedToGlasses()) HudPerf.count("img.tex.resident")
            return it.res
        }
        HudPerf.count("img.tex.miss")
        val res = newImageResource(bytes, width, height, M2CacheScope.CachedManualDelete)
        map[key] = Entry(res, width * height)
        pixels += width * height
        trim()
        return res
    }

    private fun trim() {
        val it = map.entries.iterator()
        while ((map.size > MAX_ENTRIES || pixels > MAX_PIXELS) && it.hasNext()) {
            val e = it.next()
            // Never drop the newest few (the screen being built right now uses them).
            if (map.size <= 8) break
            it.remove()
            pixels -= e.value.pixels
            // Do not freeResource: that releases a shared cache-table row index and
            // can evict whichever font later landed in the same slot.
        }
    }

    /** Forget everything (glasses reconnected / cache wiped). Free glasses rows so CRC aliases cannot revive stale blobs. */
    @Synchronized
    fun reset() {
        for (e in map.values) {
            runCatching { com.everysight.mav2.sdk.Evs.resourcesService.freeResource(e.res) }
        }
        map.clear()
        pixels = 0
    }

    @Synchronized
    fun dump(): String = "texcache enabled=$enabled entries=${map.size} px=$pixels cap=$MAX_ENTRIES"
}

internal fun createHudImage(
    bytes: ByteArray?,
    width: Int,
    height: Int,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    circular: Boolean = false,
    cornerRadius: Float = HudTheme.PANEL_RADIUS,
    /** When false, force [M2RoundedCorners.None] — softCorners was cropping brand logos to arcs. */
    roundCorners: Boolean = true,
    /** True for unchanged icons that may occupy a glasses cache row across screens. */
    resident: Boolean = false,
): M2Image? {
    if (bytes == null || bytes.isEmpty() || width <= 0 || height <= 0) return null
    return runCatching {
        val resource = hudImageResource(bytes, width, height, resident) ?: return@runCatching null
        // 1:1 encode↔draw → Native (no UV crop). Otherwise Stretch.
        // Never use the M2Image(resource) ctor: it defaults to ShapeToImage → resizeToFill()
        // which sizes the plane to the bitmap; a later smaller setDimensions then clips
        // the mark to a horizontal half (WA/IG/Spotify “cut in half”).
        val oneToOne = kotlin.math.abs(width.toFloat() - w) < 0.5f &&
            kotlin.math.abs(height.toFloat() - h) < 0.5f
        val fit = if (oneToOne) M2TextureFit.Native else M2TextureFit.Stretch
        M2Image().apply {
            setDimensions(x, y, w, h)
            setFill(resource, fit, true)
            // Re-assert after fill — some SDK paths re-touch fit on bind.
            setFill(resource, fit, true)
            setDimensions(x, y, w, h)
            if (roundCorners) {
                if (circular) {
                    softCorners(minOf(w, h) / 2f)
                } else {
                    softCorners(cornerRadius.coerceAtMost(minOf(w, h) / 2f))
                }
            } else {
                sharpCorners()
            }
        }
    }.onFailure {
        Log.e("HudImg", "createHudImage failed ${width}x${height}: ${it.message}", it)
    }.getOrNull()
}

internal fun String.takeForHud(maxChars: Int): String {
    val normalized = hudSafe(this).replace(Regex("\\s+"), " ").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 3).trimEnd() + "..."
}

internal fun String.wrapHudLines(maxChars: Int): List<String> {
    val words = hudSafe(this).replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= maxChars) {
            current.append(' ').append(word)
        } else {
            lines += current.toString()
            current.clear()
            current.append(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines
}
