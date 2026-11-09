package com.mymav.glasses.mav2.hud

import android.content.Context
import com.everysight.mav2.sdk.uikit.data.M2GradientFill
import com.everysight.mav2.sdk.uikit.data.M2GradientLength
import com.everysight.mav2.sdk.uikit.data.M2GradientLinear
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.drawables.ext.setColor
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.MymavHudPrefs

/**
 * Optional 1 px charcoal duplicate behind the Conversate heading only.
 * SDK 0.2.0 [com.everysight.mav2.sdk.uikit.drawables.M2Text] has no text-shadow / glow API
 * (javap: setText, setFont, setScale, setColor, setAlign — no shadow). Same [HudFonts.pick]
 * resource as the heading, no extra font upload, no image texture.
 * Toggle with DEBUG_HUD `hshadow on|off`. Default **off**; persisted so it survives restart.
 * Not a global rollout.
 */
object HeadingShadowTrial {
    private const val PREFS = "mymav_hud"
    private const val KEY = "heading_shadow_trial"

    @Volatile var enabled: Boolean = false
        private set
    const val OFFSET_PX = 1f
    /** Low-opacity charcoal ARGB (not an extra font or texture). */
    val charcoal: UInt get() = 0xFF2A2A2Au.withAlpha(0.38f)

    /** Read the persisted flag (missing key = off, then write that default). Call after [MymavHudPrefs.install]. */
    fun load() {
        val ctx = MymavHudPrefs.context() ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY)) {
            enabled = sp.getBoolean(KEY, false)
        } else {
            enabled = false
            sp.edit().putBoolean(KEY, false).apply()
        }
    }

    /** Set the trial flag and persist it so the next process start keeps the same value. */
    fun setEnabled(on: Boolean) {
        enabled = on
        val ctx = MymavHudPrefs.context() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Premium glass design kit for glasses app screens (640x400).
 *
 * Every app screen composes these parts instead of drawing its own chrome, so headers, tabs, filters,
 * value rows, empty states and footers look and behave the same everywhere. See glasses-mav2/PREMIUM_UI.md.
 *
 * Rules: one fine panel edge (no stacked outlines), deep blue smoked-glass body, white primary text,
 * blue-grey support text ([HudTheme.InkMuted]), cyan only for selection. Text sizes never drop below the
 * ramp in [HudTheme.MIN_TEXT_SCALE]. Live text stays text; only surfaces and icons are images.
 */
internal object GlassKit {
    const val MARGIN = 16f
    const val HEADER_H = 54f

    /** Selected-segment body (top, bottom): bright enough to read as lit glass on the additive display. */
    private const val SEL_TOP = 0xFF4BB4F7u
    private const val SEL_BODY = 0xFF2169C4u

    /** The big app panel: one smoked-glass surface that carries header, tabs and content. */
    fun appPanel(
        screen: M2Screen,
        x: Float = MARGIN,
        y: Float = 10f,
        w: Float = 608f,
        h: Float = 380f,
        /** Accent for the main panel outline (app colour); null keeps the neutral glass edge. */
        accent: M2Color? = null,
        glass: Boolean = true,
    ) {
        HudPrimitives.addPanel(
            screen,
            HudPrimitives.frostedPanel(
                x, y, w, h,
                border = accent ?: HudTheme.PanelBorder,
                radius = 22f,
                borderAlpha = 0.75f,
                glass = glass,
            ),
        )
    }

    /**
     * Header: glossy icon + natural-case title (+ optional muted subtitle). The HOME pill is drawn by the
     * caller via [HudPrimitives.drawHomePill] at its fixed gaze-hit position (right side).
     */
    fun header(screen: M2Screen, title: String, iconId: String?, subtitle: String = "") {
        var x = 32f
        if (iconId != null) {
            PremiumHudIcons.image(iconId, 36, x - 4f, 22f)?.let { screen.add(it); x += 46f }
        }
        val titleScale = 1.4f
        screen.add(HudPrimitives.label(title, x, 24f, titleScale, maxWidth = 440f - x))
        if (subtitle.isNotBlank()) {
            val tw = HudText.width(hudSafe(title), titleScale, true)
            val sx = x + tw + 14f
            if (sx < 440f) {
                screen.add(HudPrimitives.labelInk(subtitle, sx, 33f, 0.7f, HudTheme.InkMuted, maxWidth = 460f - sx))
            }
        }
    }

    /** Segmented tabs: one glass track, the selected segment lit. Returns the track's bottom edge. */
    fun tabs(
        screen: M2Screen,
        labels: List<String>,
        selected: Int,
        y: Float = 74f,
        x: Float = 32f,
        w: Float = 576f,
        h: Float = 36f,
        glass: Boolean = true,
    ): Float {
        HudPrimitives.addPanel(screen, HudPrimitives.frostedPanel(x, y, w, h, radius = h / 2f, gradient = false, glass = glass))
        val seg = w / labels.size
        labels.forEachIndexed { i, label ->
            val sx = x + i * seg
            if (i == selected) {
                screen.add(
                    M2RectFilled(SEL_BODY).apply {
                        setDimensions(sx + 3f, y + 3f, seg - 6f, h - 6f)
                        setFill(M2GradientLinear(M2GradientFill.Stop.Start, M2GradientLength.fraction(1f), 90f, SEL_TOP))
                        softCorners((h - 6f) / 2f)
                    },
                )
                screen.add(
                    M2RectOutline(0xFFFFFFFFu.withAlpha(0.55f)).apply {
                        setDimensions(sx + 3f, y + 3f, seg - 6f, h - 6f)
                        softCorners((h - 6f) / 2f)
                        setStyle(1f)
                    },
                )
            }
            screen.add(
                HudPrimitives.centeredPillLabel(
                    label, sx, y, seg, h, 0.85f,
                    M2Color.White,
                    bold = i == selected,
                ),
            )
        }
        return y + h
    }

    /** Selected / primary action pill: luminous cyan-blue fill with a fine light edge. */
    fun selectedPill(screen: M2Screen, x: Float, y: Float, w: Float, h: Float) {
        screen.add(
            M2RectFilled(SEL_BODY).apply {
                setDimensions(x, y, w, h)
                setFill(M2GradientLinear(M2GradientFill.Stop.Start, M2GradientLength.fraction(1f), 90f, SEL_TOP))
                softCorners(h / 2f)
            },
        )
        screen.add(
            M2RectOutline(0xFFBFE6FFu.withAlpha(0.8f)).apply {
                setDimensions(x, y, w, h)
                softCorners(h / 2f)
                setStyle(1f)
            },
        )
    }

    /** Separate rounded filter pills with gaps (selected = luminous cyan-blue fill + fine edge), centred in [x, x+w]. */
    fun pillTabs(
        screen: M2Screen,
        labels: List<String>,
        selected: Int,
        y: Float,
        h: Float = 36f,
        x: Float = 32f,
        w: Float = 576f,
        gap: Float = 14f,
        scale: Float = 0.85f,
    ) {
        // Snap each pill to a packaged artwork width (>= text + 44, min 92) so the surface is drawn 1:1.
        val widths = labels.map { GlassSurface.snapWidth((HudText.width(hudSafe(it), scale, true) + 44f).coerceAtLeast(92f), h.toInt(), Math.round(h / 2f)) }
        val total = widths.sum() + gap * (labels.size - 1)
        var px = x + (w - total) / 2f
        labels.forEachIndexed { i, label ->
            val pw = widths[i]
            if (i == selected) {
                selectedPill(screen, px, y, pw, h)
            } else {
                HudPrimitives.addPanel(screen, HudPrimitives.frostedPanel(px, y, pw, h, radius = h / 2f, gradient = false, glass = true))
            }
            screen.add(HudPrimitives.centeredPillLabel(label, px, y, pw, h, scale, M2Color.White, bold = i == selected))
            px += pw + gap
        }
    }

    /** Centred hero value with its unit/label beside it. */
    fun hero(screen: M2Screen, value: String, label: String, y: Float, centerX: Float = 320f, vs: Float = 2.0f) {
        val ls = if (vs >= 2.5f) 1.0f else 0.9f
        val shown = HudText.fit(value, 340f, vs, true)
        val vw = HudText.width(shown, vs, true)
        val lw = if (label.isBlank()) 0f else HudText.width(hudSafe(label), ls, false)
        val gap = if (lw > 0f) 12f else 0f
        val x0 = centerX - (vw + gap + lw) / 2f
        val vh = HudText.lineHeight(vs, true)
        val lh = HudText.lineHeight(ls, false)
        screen.add(HudPrimitives.label(shown, x0, y, vs, M2Color.White, bold = true))
        if (lw > 0f) screen.add(HudPrimitives.labelInk(label, x0 + vw + gap, y + vh - lh - 4f, ls, HudTheme.InkMuted))
    }

    /** One aligned row: optional icon, label, value right-aligned at [valueRight], optional muted unit at [unitRight]. */
    class Row(val icon: HealthHudIcons.Encoded?, val label: String, val value: String, val unit: String = "")

    fun rows(
        screen: M2Screen,
        rows: List<Row>,
        y: Float,
        rowH: Float = 32f,
        x: Float = 32f,
        w: Float = 576f,
        valueRight: Float = 430f,
        showUnit: Boolean = true,
    ) {
        rows.forEachIndexed { i, row ->
            val ry = y + i * rowH
            if (i > 0) {
                screen.add(M2RectFilled(HudTheme.InkEdgeBright.withAlpha(0.35f)).apply { setDimensions(x, ry - 1f, w, 1f) })
            }
            val ts = 0.85f
            val th = HudText.boxHeight(ts, false)
            val ty = ry + (rowH - th) / 2f
            var lx = x + 4f
            if (row.icon != null) {
                createHudImage(
                    row.icon.bytes, row.icon.width, row.icon.height,
                    lx, ry + (rowH - 26f) / 2f, 26f, 26f, roundCorners = false,
                )?.let { screen.add(it) }
                lx += 36f
            }
            screen.add(HudPrimitives.label(row.label, lx, ty, ts, M2Color.White, maxWidth = valueRight - lx - 150f))
            val v = HudText.fit(row.value, 220f, ts, true)
            val vw = HudText.width(v, ts, true)
            screen.add(HudPrimitives.label(v, valueRight - vw, ty, ts, M2Color.White, bold = true))
            if (showUnit && row.unit.isNotBlank()) {
                val u = HudText.fit(row.unit, x + w - valueRight - 30f, 0.7f, false)
                val uw = HudText.width(u, 0.7f, false)
                screen.add(HudPrimitives.labelInk(u, x + w - 4f - uw, ty + 2f, 0.7f, HudTheme.InkMuted))
            }
        }
    }

    /** Footer hint line, muted, inside the panel bottom. */
    fun footer(screen: M2Screen, left: String, right: String = "", y: Float = 354f) {
        screen.add(M2RectFilled(HudTheme.InkEdgeBright.withAlpha(0.35f)).apply { setDimensions(32f, y - 8f, 576f, 1f) })
        screen.add(HudPrimitives.labelInk(left.takeForHud(44), 32f, y, 0.7f, HudTheme.InkMuted, maxWidth = 400f))
        if (right.isNotBlank()) {
            val w = HudText.width(hudSafe(right), 0.7f, true)
            screen.add(HudPrimitives.label(right, 608f - w, y, 0.7f, HudTheme.FocusGlow, bold = true))
        }
    }

    /** Content card; [selected] lights the single edge in cyan and adds a faint wash (no extra ring). */
    fun card(screen: M2Screen, x: Float, y: Float, w: Float, h: Float, selected: Boolean = false, radius: Float = 16f, glass: Boolean = true) {
        HudPrimitives.addPanel(
            screen,
            HudPrimitives.frostedPanel(
                x, y, w, h,
                border = if (selected) HudTheme.FocusGlow else HudTheme.PanelBorder,
                radius = radius,
                borderAlpha = if (selected) HudTheme.FOCUS_ALPHA else HudTheme.BORDER_ALPHA,
                gradient = false,
                glass = glass,
            ),
        )
        if (selected) {
            screen.add(
                M2RectFilled(HudTheme.FocusGlow.withAlpha(HudTheme.FOCUS_FILL_ALPHA)).apply {
                    setDimensions(x, y, w, h)
                    softCorners(radius.coerceAtMost(minOf(w, h) / 2f))
                },
            )
        }
    }

    /**
     * Pill button with centred label: the same glass pill surface as the filter pills; [selected] lights it with the
     * luminous cyan-blue fill (focus), exactly like the Socials selected pill.
     */
    fun button(screen: M2Screen, label: String, x: Float, y: Float, w: Float, h: Float = 36f, scale: Float = 0.85f, selected: Boolean = false) {
        if (selected) {
            selectedPill(screen, x, y, w, h)
        } else {
            HudPrimitives.addPanel(screen, HudPrimitives.frostedPanel(x, y, w, h, radius = h / 2f, gradient = false, glass = true))
        }
        screen.add(HudPrimitives.centeredPillLabel(label, x, y, w, h, scale, M2Color.White, tracked = true, bold = if (selected) true else null))
    }

    /** Thin dwell/progress bar along the bottom of a panel. */
    fun progress(screen: M2Screen, fraction: Float, y: Float, color: M2Color = HudTheme.FocusGlow, x: Float = 32f, w: Float = 576f) {
        if (fraction <= 0.02f) return
        screen.add(
            M2RectFilled(color.withAlpha(0.9f)).apply {
                setDimensions(x, y, w * fraction.coerceIn(0f, 1f), 4f)
                softCorners(2f)
            },
        )
    }

    /** Muted empty/loading/error message inside a card area. */
    fun message(screen: M2Screen, title: String, detail: String, y: Float) {
        screen.add(HudPrimitives.label(title.takeForHud(30), 48f, y, 1.2f, M2Color.White, bold = true, maxWidth = 540f))
        if (detail.isNotBlank()) screen.add(HudPrimitives.labelInk(detail.takeForHud(42), 48f, y + 36f, 0.8f, HudTheme.InkMuted, maxWidth = 540f))
    }

    // ── Readable HUD (Conversate / Shop / Objects) ──────────────────────────
    // Compact header · full-width content · reserved bottom action row.
    // Measure with installed font metrics; wrap at word boundaries; no font shrink.

    const val READABLE_CONTENT_TOP = 70f
    const val READABLE_ACTION_Y = 332f
    const val READABLE_ACTION_H = 40f
    const val READABLE_CONTENT_BOTTOM = 320f

    /**
     * Word-wrap using [HudText.width] metrics. Never ellipsises — leftover words
     * become later pages via [paginateLines]. Default cap is 42 visible characters:
     * SDK 0.2.0 drops a longer M2Text entirely (40 left unused column; 48 vanished).
     */
    fun wrapAll(raw: String, maxWidth: Float, scale: Float, bold: Boolean = false, maxChars: Int = 42): List<String> {
        val words = hudSafe(raw).replace(Regex("\\s+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        val cap = maxChars.coerceAtLeast(8)
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (HudText.width(candidate, scale, bold) <= maxWidth && candidate.length <= cap) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            // Overlong single token: hard-split by measured fit / char cap without "…" (paging owns overflow).
            if (HudText.width(word, scale, bold) <= maxWidth && word.length <= cap) {
                current = word
            } else {
                var rest = word
                while (rest.isNotEmpty()) {
                    var lo = 1
                    var hi = rest.length
                    while (lo < hi) {
                        val mid = (lo + hi + 1) / 2
                        if (HudText.width(rest.take(mid), scale, bold) <= maxWidth && mid <= cap) lo = mid else hi = mid - 1
                    }
                    lines += rest.take(lo.coerceAtLeast(1))
                    rest = rest.drop(lo.coerceAtLeast(1))
                }
                current = ""
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /**
     * Conversate layout wrap: measured width only. The 42-char cap stays as a
     * per-object draw limit in [addMeasuredLine], not as a line-break rule.
     */
    fun wrapMeasured(raw: String, maxWidth: Float, scale: Float, bold: Boolean = false): List<String> =
        wrapAll(raw, maxWidth, scale, bold, maxChars = 10_000)

    /** Isolated probe: SDK 0.2.0 draws 42 visible chars and drops 44+. */
    const val SAFE_M2TEXT_CHARS = 42

    /**
     * Draw one visual line as one or more safe-length [M2Text] objects on the
     * same baseline, placed with [HudText.width] of the consumed prefix.
     */
    fun addMeasuredLine(
        screen: M2Screen,
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: M2Color = M2Color.White,
        bold: Boolean = false,
        ink: UInt? = null,
    ) {
        val line = hudSafe(text)
        if (line.isEmpty()) return
        var consumed = 0
        for (chunk in chunkSafe(line, SAFE_M2TEXT_CHARS)) {
            val cx = x + HudText.width(line.take(consumed), scale, bold)
            val d = if (ink != null) {
                HudPrimitives.labelInk(chunk, cx, y, scale, ink, maxWidth = 0f)
            } else {
                HudPrimitives.label(chunk, cx, y, scale, color, bold = bold, maxWidth = 0f)
            }
            screen.add(d)
            consumed += chunk.length
        }
    }

    fun chunkSafe(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = (i + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', startIndex = end - 1)
                if (space > i) end = space + 1
            }
            while (end < text.length && !canSplit(text, end)) end++
            if (end <= i) end = nextBoundary(text, i + 1)
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    private fun canSplit(s: String, index: Int): Boolean {
        if (index <= 0 || index >= s.length) return true
        val prev = s[index - 1]
        val next = s[index]
        if (prev.isHighSurrogate() && next.isLowSurrogate()) return false
        val type = Character.getType(next)
        return type != Character.NON_SPACING_MARK.toInt() &&
            type != Character.COMBINING_SPACING_MARK.toInt() &&
            type != Character.ENCLOSING_MARK.toInt()
    }

    private fun nextBoundary(s: String, from: Int): Int {
        var i = from.coerceAtMost(s.length)
        while (i < s.length && !canSplit(s, i)) i++
        return i.coerceAtLeast((from).coerceAtMost(s.length)).coerceAtLeast(1).coerceAtMost(s.length)
    }

    fun paginateLines(lines: List<String>, linesPerPage: Int): List<List<String>> {
        if (lines.isEmpty()) return listOf(emptyList())
        val n = linesPerPage.coerceAtLeast(1)
        return lines.chunked(n).ifEmpty { listOf(emptyList()) }
    }

    /** Compact header: app icon + title + optional status chip; HOME pill drawn by caller. */
    fun readableHeader(
        screen: M2Screen,
        title: String,
        iconId: String?,
        statusChip: String = "",
        headingShadow: Boolean = false,
    ) {
        var x = 32f
        if (iconId != null) {
            PremiumHudIcons.image(iconId, 36, x - 4f, 20f)?.let { screen.add(it); x += 46f }
        }
        if (headingShadow) {
            // 1 px charcoal duplicate, same pick as the heading (scale 1.35 → SansBold 32).
            screen.add(
                HudPrimitives.label(
                    title, x + HeadingShadowTrial.OFFSET_PX, 22f + HeadingShadowTrial.OFFSET_PX,
                    1.35f, M2Color.Black, maxWidth = 300f,
                ).also { it.setColor(HeadingShadowTrial.charcoal) },
            )
            HudPerf.count("heading.shadow")
        }
        screen.add(HudPrimitives.label(title, x, 22f, 1.35f, maxWidth = 300f))
        if (statusChip.isNotBlank()) {
            val tw = HudText.width(hudSafe(title), 1.35f, true)
            val chipX = (x + tw + 12f).coerceAtMost(400f)
            val chipW = (HudText.width(hudSafe(statusChip), 0.7f, true) + 28f).coerceIn(72f, 150f)
            screen.add(
                M2RectFilled(HudTheme.FocusGlow.withAlpha(0.22f)).apply {
                    setDimensions(chipX, 24f, chipW, 26f)
                    softCorners(13f)
                },
            )
            screen.add(
                M2RectFilled(0xFF3B82F6u).apply {
                    setDimensions(chipX + 8f, 32f, 8f, 8f)
                    softCorners(4f)
                },
            )
            screen.add(
                HudPrimitives.labelInk(
                    statusChip, chipX + 22f, 28f, 0.7f, HudTheme.InkMuted, maxWidth = chipW - 28f,
                ),
            )
        }
    }

    /** Reserved bottom action row — three equal glass pills, selected = luminous. */
    fun readableActionRow(
        screen: M2Screen,
        labels: List<String>,
        focused: Int,
        y: Float = READABLE_ACTION_Y,
        h: Float = READABLE_ACTION_H,
        dangerIndex: Int = -1,
        gazeProgress: Float = 0f,
        disabled: Set<Int> = emptySet(),
    ) {
        val n = labels.size.coerceAtLeast(1)
        val boxes = com.mymav.glasses.api.HudActionLayout.pills(n)
        labels.forEachIndexed { i, label ->
            val box = boxes[i]
            val x = box.x
            val w = box.w
            val selected = i == focused.coerceIn(0, n - 1) && i !in disabled
            if (i in disabled) {
                card(screen, x, y, w, h, radius = h / 2f)
                screen.add(HudPrimitives.centeredPillLabel(label, x, y, w, h, 0.82f, M2Color.BlueGrey, tracked = true))
                return@forEachIndexed
            }
            if (selected && gazeProgress > 0.02f) {
                screen.add(
                    M2RectFilled(HudTheme.InkEdgeBright.withAlpha(0.35f)).apply {
                        setDimensions(x + 12f, y + h + 4f, w - 24f, 4f)
                        softCorners(2f)
                    },
                )
                progress(screen, gazeProgress, y + h + 4f, x = x + 12f, w = w - 24f)
            }
            if (selected) {
                if (i == dangerIndex) {
                    screen.add(
                        M2RectFilled(0xFF8B1E1Eu).apply {
                            setDimensions(x, y, w, h)
                            setFill(
                                M2GradientLinear(
                                    M2GradientFill.Stop.Start,
                                    M2GradientLength.fraction(1f),
                                    90f,
                                    0xFFC93636u,
                                ),
                            )
                            softCorners(h / 2f)
                        },
                    )
                    screen.add(
                        M2RectOutline(0xFFFFB4B4u.withAlpha(0.85f)).apply {
                            setDimensions(x, y, w, h)
                            softCorners(h / 2f)
                            setStyle(1f)
                        },
                    )
                    screen.add(
                        HudPrimitives.centeredPillLabel(label, x, y, w, h, 0.82f, M2Color.White, tracked = true, bold = true),
                    )
                } else {
                    button(screen, label, x, y, w, h, scale = 0.82f, selected = true)
                }
            } else {
                card(screen, x, y, w, h, radius = h / 2f)
                screen.add(HudPrimitives.centeredPillLabel(label, x, y, w, h, 0.82f, M2Color.White, tracked = true))
            }
        }
    }

    /** Draw wrapped lines inside a card; returns page info for overflow paging. */
    fun readableCardText(
        screen: M2Screen,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        scale: Float,
        maxLines: Int,
        page: Int,
        bold: Boolean = false,
        color: M2Color = M2Color.White,
        /** Cap step so cold/broken font metrics cannot shove lines below the card. */
        lineStep: Float = 0f,
    ): ReadableTextPage {
        val all = wrapAll(text, maxWidth, scale, bold)
        val pages = paginateLines(all, maxLines)
        val pageIndex = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val lines = pages.getOrElse(pageIndex) { emptyList() }
        val measured = HudText.lineHeight(scale, bold) + 4f
        val lh = when {
            lineStep > 0f -> lineStep
            measured in 12f..32f -> measured
            else -> (22f * scale + 4f).coerceIn(16f, 28f)
        }
        lines.forEachIndexed { i, line ->
            screen.add(HudPrimitives.label(line, x, y + i * lh, scale, color, bold = bold, maxWidth = maxWidth))
        }
        return ReadableTextPage(pageIndex = pageIndex, pageCount = pages.size.coerceAtLeast(1), lines = lines)
    }

    data class ReadableTextPage(val pageIndex: Int, val pageCount: Int, val lines: List<String>)
}
