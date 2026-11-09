package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GazeKeyboardCatalog
import com.mymav.glasses.api.GazeKeyboardLayout
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.KeyboardMode
import com.mymav.glasses.api.VoiceInputMode

/**
 * Shared glasses keyboard — phone-style 4-row layout (letters ⇄ 123 symbols),
 * tappable word suggestions, real colour emoji, voice pills and ABC / Emojis /
 * Prev / Next / Search. Every key works with Tap or a 1s look; D-pad moves
 * focus by geometry. Key positions come from [GazeKeyboardLayout] so the gaze
 * hit-test and the drawing always agree.
 */
class GazeKeyboardScreen(
    initial: GlassesHud.Keyboard,
) : HudScreen(tag = "mymav-gaze-keyboard") {

    private var hud: GlassesHud.Keyboard = initial
    private val structureKey = structureKeyOf(initial)
    private val contentKeys: List<String>
    private val pageCount: Int
    private val rects: LinkedHashMap<String, GazeKeyboardLayout.KeyRect>
    private val allKeys: List<String>
    private var focused: String

    /** Key geometry + label, so focus moves can patch instead of rebuild. */
    private class KeySlot(val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val label: M2Text?)
    private val slots = HashMap<String, KeySlot>()
    private val focusLayer = HudPrimitives.FocusLayer()
    private val bufferText: M2Text = HudPrimitives.label(" ", BUF_X, 30f, 0.72f, HudTheme.TextPrimary, bold = false)
    private val caret = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.9f)).apply { park() }
    private val gazeBar = M2RectFilled(HudTheme.FocusGlow.withAlpha(0.85f)).apply { park() }
    /** Typed-text line with emoji: live drawables, re-laid out when the text changes. */
    private val richText = ArrayList<com.everysight.mav2.sdk.uikit.base.M2Drawable>()
    private val richEmoji = HashMap<String, com.everysight.mav2.sdk.uikit.drawables.M2Image>()
    private var shownBuffer: String? = null

    init {
        val visible = GazeKeyboardCatalog.visibleKeys(
            initial.mode, initial.categoryId, initial.pageIndex, initial.searchQuery, initial.symbols,
        )
        contentKeys = visible.first
        pageCount = visible.second.coerceAtLeast(1)
        val sugCount = if (initial.mode == KeyboardMode.Letters) initial.suggestions.size else 0
        rects = GazeKeyboardLayout.rects(initial.mode, contentKeys, sugCount)
        allKeys = rects.keys.toList()
        focused = resolveFocus(initial.focusedKeyId)
    }

    private fun resolveFocus(id: String): String = when {
        id in rects -> id
        else -> contentKeys.firstOrNull { it in rects } ?: allKeys.first()
    }

    override fun build() {
        add(HudPrimitives.fullBleedBackground())

        // ---- header: BACK | title + typed text | VTXT | VNOTE ----
        val backLabel = if (hud.title.contains("Ask", ignoreCase = true)) "CANCEL" else "BACK"
        rects["back"]?.let { r ->
            HudPrimitives.addPanel(this, PillStyle.apply(HudPrimitives.softPill(r.x, r.y, r.w, r.h, HudTheme.MicPill), HudTheme.MicPill))
            add(HudPrimitives.centeredPillLabel(backLabel, r.x, r.y, r.w, r.h, scale = 0.7f, color = PillStyle.LABEL, tracked = true))
            slots["back"] = KeySlot(r.x, r.y, r.w, r.h, r.h / 2f, null)
        }

        val titlePanel = HudPrimitives.frostedPanel(118f, 8f, 276f, 48f, radius = 16f)
        HudPrimitives.addPanel(this, titlePanel)
        add(HudPrimitives.label(hud.title, BUF_X, 12f, 0.7f, HudTheme.FocusGlow, maxWidth = BUF_W))
        add(bufferText)
        add(caret)

        val vtxtArmed = hud.voiceArmed && hud.voiceMode == VoiceInputMode.ToText
        val vnoteArmed = hud.voiceArmed && hud.voiceMode == VoiceInputMode.Note
        drawVoicePill("vtxt", "VTXT", "to text", if (vtxtArmed) M2Color.Orange else HudTheme.MicPill)
        drawVoicePill("vnote", "VNOTE", "voice", if (vnoteArmed) M2Color.Orange else HudTheme.FocusGlow)
        if (hud.voiceStatus.isNotBlank()) {
            add(HudPrimitives.labelInk(hud.voiceStatus, 404f, 50f, 0.7f, HudTheme.InkMuted, maxWidth = 224f))
        }

        // ---- suggestions (letters) / mode strip ----
        val sugY = GazeKeyboardLayout.SUGGEST_Y
        val sugH = GazeKeyboardLayout.SUGGEST_H
        if (hud.mode == KeyboardMode.Letters) {
            val sugKeys = GazeKeyboardLayout.suggestionKeys(hud.suggestions.size)
            if (sugKeys.isEmpty()) {
                HudPrimitives.addPanel(this, HudPrimitives.frostedPanel(12f, sugY, 616f, sugH, radius = 14f, gradient = false))
                add(HudPrimitives.labelInk("Suggestions appear as you type", 26f, sugY + 7f, 0.7f, HudTheme.InkMuted))
            } else {
                sugKeys.forEachIndexed { i, id ->
                    val r = rects[id] ?: return@forEachIndexed
                    HudPrimitives.addPanel(
                        this,
                        HudPrimitives.frostedPanel(
                            r.x, r.y, r.w, r.h, radius = 14f, gradient = false,
                            border = if (i == 0) HudTheme.FocusGlow else HudTheme.PanelBorder,
                            borderAlpha = if (i == 0) 0.55f else HudTheme.BORDER_ALPHA,
                        ),
                    )
                    val text = HudPrimitives.centeredPillLabel(hud.suggestions[i], r.x, r.y, r.w, r.h, 0.6f, labelColor(id, false))
                    add(text)
                    slots[id] = KeySlot(r.x, r.y, r.w, r.h, 14f, text)
                }
            }
        } else {
            val modeLabel = when (hud.mode) {
                KeyboardMode.Emoji -> "EMOJI - ${GazeKeyboardCatalog.category(hud.categoryId).title.uppercase()} - ${hud.pageIndex + 1}/$pageCount"
                KeyboardMode.Categories -> "CHOOSE AN EMOJI SET"
                KeyboardMode.Search -> "SEARCH - ${hud.searchQuery.ifBlank { "type to filter" }}"
                else -> ""
            }
            HudPrimitives.addPanel(this, HudPrimitives.frostedPanel(12f, sugY, 616f, sugH, radius = 14f, gradient = false))
            add(HudPrimitives.labelInk(modeLabel, 26f, sugY + 7f, 0.7f, HudTheme.InkMuted, maxWidth = 588f))
        }

        contentKeys.forEach { id -> rects[id]?.let { drawKey(id, it) } }
        GazeKeyboardCatalog.FUNCTION_KEYS.forEach { id ->
            val active = when (id) {
                "fn_abc" -> hud.mode == KeyboardMode.Letters
                "fn_cat" -> hud.mode == KeyboardMode.Categories || hud.mode == KeyboardMode.Emoji
                "fn_search" -> hud.mode == KeyboardMode.Search
                else -> false
            }
            rects[id]?.let { drawKey(id, it, activeMode = active) }
        }

        add(HudPrimitives.labelInk("Tap or look 1s to press - Long tap deletes", 16f, GazeKeyboardLayout.HINT_Y, 0.7f, HudTheme.InkMuted))

        // Live layers on top: focus ring + gaze progress.
        add(focusLayer.wash)
        add(focusLayer.ring)
        add(gazeBar)
        applyLiveState()
    }

    private fun drawVoicePill(id: String, title: String, sub: String, fill: M2Color) {
        val r = rects[id] ?: return
        HudPrimitives.addPanel(this, PillStyle.apply(HudPrimitives.softPill(r.x, r.y, r.w, r.h, fill), fill))
        add(HudPrimitives.centeredPillLabel(title, r.x, r.y + 1f, r.w, 24f, 0.52f, PillStyle.LABEL, tracked = true))
        add(HudPrimitives.centeredPillLabel(sub, r.x, r.y + 19f, r.w, 20f, 0.46f, PillStyle.LABEL))
        slots[id] = KeySlot(r.x, r.y, r.w, r.h, r.h / 2f, null)
    }

    /**
     * Typed text with real emoji images. Emoji already on screen are kept (no
     * re-upload); only new ones upload, so typing never blanks the keyboard.
     */
    private fun layoutRichBuffer(buffer: String): Float {
        val scale = 0.72f
        val emojiPx = EmojiKeyBitmaps.INLINE.toFloat()
        val segs = EmojiKeyBitmaps.segments(buffer)
        val shown = ArrayList<Pair<Boolean, String>>()
        var used = 0f
        for (i in segs.indices.reversed()) {
            val (isEmoji, text) = segs[i]
            if (isEmoji) {
                if (used + emojiPx + 2f > BUF_W) break
                used += emojiPx + 2f
                shown.add(0, segs[i])
            } else {
                val safe = hudSafe(text)
                val w = HudText.width(safe, scale, false)
                if (used + w <= BUF_W) {
                    used += w
                    shown.add(0, false to safe)
                } else {
                    val cut = HudText.fit(safe, BUF_W - used, scale, false, fromEnd = true)
                    if (cut.isNotEmpty()) {
                        used += HudText.width(cut, scale, false)
                        shown.add(0, false to cut)
                    }
                    break
                }
            }
        }
        richText.forEach { runCatching { remove(it) } }
        richText.clear()
        val keep = HashSet<String>()
        var x = BUF_X
        shown.forEachIndexed { index, (isEmoji, text) ->
            if (isEmoji) {
                val key = "$index|$text|${x.toInt()}"
                keep += key
                if (!richEmoji.containsKey(key)) {
                    EmojiKeyBitmaps.imageFor(text, x, 29f, emojiPx, emojiPx)?.let {
                        add(it)
                        richEmoji[key] = it
                    }
                }
                x += emojiPx + 2f
            } else {
                val label = HudPrimitives.label(text, x, 30f, scale, HudTheme.TextPrimary, bold = false)
                add(label)
                richText += label
                x += HudText.width(text, scale, false)
            }
        }
        richEmoji.keys.filter { it !in keep }.forEach { k -> richEmoji.remove(k)?.let { runCatching { remove(it) } } }
        return x
    }

    private var richCaretX: Float = -1f

    // ---- live patching ---------------------------------------------------------

    override fun tryPatch(next: GlassesHud): Boolean {
        if (next !is GlassesHud.Keyboard) return false
        if (structureKeyOf(next) != structureKey) return false
        val previousFocus = focused
        hud = next
        focused = resolveFocus(next.focusedKeyId)
        return runCatching {
            if (previousFocus != focused) {
                slots[previousFocus]?.label?.setColor(labelColor(previousFocus, false))
            }
            applyLiveState()
            true
        }.getOrDefault(false)
    }

    private fun applyLiveState() {
        val caretX = if (EmojiKeyBitmaps.containsEmoji(hud.buffer)) {
            if (shownBuffer != hud.buffer) {
                bufferText.setHudText(" ")
                richCaretX = layoutRichBuffer(hud.buffer)
                shownBuffer = hud.buffer
            }
            richCaretX
        } else {
            if (richText.isNotEmpty() || richEmoji.isNotEmpty()) layoutRichBuffer("")
            shownBuffer = hud.buffer
            val shown = HudText.fit(hudSafe(hud.buffer).takeLast(80).ifBlank { "" }, BUF_W, 0.72f, false, fromEnd = true)
            bufferText.setHudText(shown.ifBlank { " " })
            BUF_X + if (shown.isBlank()) 0f else HudText.width(shown, 0.72f, false)
        }
        caret.setDimensions((caretX + 2f).coerceAtMost(BUF_X + BUF_W + 4f), 31f, 2f, 20f)
        val slot = slots[focused]
        if (slot == null) {
            focusLayer.hide()
        } else {
            focusLayer.moveTo(slot.x, slot.y, slot.w, slot.h, slot.radius)
            slot.label?.setColor(labelColor(focused, true))
        }
        val p = hud.keyGazeProgress
        if (p > 0.02f && slot != null) {
            gazeBar.setDimensions(slot.x + 8f, slot.y + slot.h - 6f, ((slot.w - 16f) * p.coerceIn(0f, 1f)).coerceAtLeast(3f), 3f)
            gazeBar.softCorners(1.5f)
        } else {
            gazeBar.park()
        }
    }

    private fun labelColor(keyId: String, isFocused: Boolean): M2Color {
        val capsOn = keyId == "caps" && hud.capsLocked
        return if (isFocused || capsOn) HudTheme.FocusGlow else HudTheme.TextPrimary
    }

    // ---- keys ------------------------------------------------------------------

    private fun drawKey(
        keyId: String,
        r: GazeKeyboardLayout.KeyRect,
        activeMode: Boolean = false,
    ) {
        val x = r.x
        val y = r.y
        val w = r.w
        val h = r.h
        val isAction = keyId == "del" || keyId == "bs" || keyId == "clr" || keyId == "clear" ||
            keyId == "done" || keyId == "caps" || keyId == "sym"
        val capsOn = keyId == "caps" && hud.capsLocked
        val panel = HudPrimitives.frostedPanel(
            x, y, w, h,
            border = when {
                activeMode || capsOn || keyId == "done" -> HudTheme.FocusGlow
                isAction -> HudTheme.MicPill
                else -> HudTheme.PanelBorder
            },
            radius = HudTheme.KEY_RADIUS,
            borderAlpha = when {
                activeMode || capsOn || keyId == "done" -> 0.75f
                isAction -> 0.55f
                else -> HudTheme.BORDER_ALPHA
            },
            gradient = false,
        )
        add(panel.fill)
        add(panel.outline)
        val label = GazeKeyboardCatalog.labelForKey(
            keyId, hud.mode, hud.categoryId, hud.pageIndex, hud.searchQuery,
            capsLocked = hud.capsLocked,
            doneLabel = hud.doneLabel,
            symbols = hud.symbols,
        )
        if (keyId.startsWith("emoji_") || keyId.startsWith("semoji_")) {
            val size = EmojiKeyBitmaps.SIZE.toFloat().coerceAtMost(h - 4f)
            drawEmojiBitmap(label, x + (w - size) / 2f, y + (h - size) / 2f, size, size)
            slots[keyId] = KeySlot(x, y, w, h, HudTheme.KEY_RADIUS, null)
            return
        }
        if (keyId.startsWith("cat_")) {
            val cat = GazeKeyboardCatalog.category(keyId.removePrefix("cat_"))
            val size = EmojiKeyBitmaps.SIZE.toFloat()
            drawEmojiBitmap(cat.glyph, x + 8f, y + (h - size) / 2f, size, size)
            val text = HudPrimitives.label(
                cat.shortTitle,
                x + size + 18f,
                y + (h - HudText.lineHeight(0.54f)) / 2f,
                0.54f,
                labelColor(keyId, false),
                maxWidth = w - size - 28f,
            )
            add(text)
            slots[keyId] = KeySlot(x, y, w, h, HudTheme.KEY_RADIUS, text)
            return
        }
        val scale = when {
            keyId == "spc" -> 0.6f
            keyId.startsWith("fn_") || keyId.length > 1 -> 0.52f
            else -> 0.8f
        }
        val text = HudPrimitives.centeredPillLabel(label, x, y, w, h, scale, labelColor(keyId, false))
        add(text)
        slots[keyId] = KeySlot(x, y, w, h, HudTheme.KEY_RADIUS, text)
    }

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        val delta = FocusGridNav.delta(touch)
        if (delta != null) {
            val (dx, dy) = delta
            hud.actions.onFocusChanged?.invoke(GazeKeyboardLayout.move(rects, focused, dx, dy))
            return
        }
        when (touch) {
            M2Touch.Tap -> handleTap(focused)
            M2Touch.LongTap -> {
                when (focused) {
                    "mic", "vtxt" -> invokeVoiceToText()
                    "vnote" -> invokeVoiceNote()
                    else -> hud.actions.onBackspace?.invoke()
                }
            }
            else -> Unit
        }
    }

    /**
     * Distinct per-cell glyph. Prefer RGBA upload; always draw vector shapes too so
     * Maverick never shows identical blank/dark plates when M2Image upload blanks.
     */
    private fun drawEmojiBitmap(emoji: String, x: Float, y: Float, w: Float, h: Float) {
        val image = EmojiKeyBitmaps.imageFor(emoji, x, y, w, h)
        if (image != null) {
            add(image)
        } else {
            // Vector fallback only when the bitmap could not be built.
            drawDistinctGlyph(emoji, x, y, w, h)
        }
    }

    /** Soft-glass vector glyphs — unique shape/colour per emoji family (not one face tinted). */
    private fun drawDistinctGlyph(emoji: String, x: Float, y: Float, w: Float, h: Float) {
        val size = minOf(w, h) * 0.72f
        val cx = x + (w - size) / 2f
        val cy = y + (h - size) / 2f
        val heart = emoji.contains("❤") || emoji.contains("♥") ||
            emoji in setOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎",
                "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝",
            )
        val hand = emoji in setOf(
            "👍", "👎", "👏", "🙌", "🙏", "🤝", "✌️", "🤞", "🤟", "🤘",
            "👌", "🤌", "👆", "👇", "👈", "👉", "👋", "💪",
        )
        val fireStar = emoji in setOf("🔥", "⭐", "✨", "💯", "✅", "❌")
        val obj = emoji in setOf(
            "📱", "💻", "⌚", "🎧", "🎤", "📷", "🚗", "✈️", "🏠", "⏰",
            "📅", "📌", "💡", "🔑", "🔒", "✉️", "📦", "🛒", "⚽", "🏋️",
            "🍕", "☕", "🎵", "🎮", "🎉", "🎊", "🎈", "🎁", "🥳", "🍾",
        )
        when {
            heart -> {
                val fill = when (emoji) {
                    "🧡" -> M2Color.Orange
                    "💛" -> M2Color.Yellow
                    "💚" -> M2Color.Green
                    "💙" -> M2Color.Blue
                    "💜" -> M2Color.Purple
                    "🖤" -> M2Color.Black
                    "🤍" -> M2Color.White
                    else -> M2Color.Red
                }
                // Two lobes + tip approximate a heart without Path support.
                add(M2RectFilled(fill).apply {
                    setDimensions(cx + size * 0.12f, cy + size * 0.12f, size * 0.34f, size * 0.34f)
                    softCorners(size * 0.17f)
                })
                add(M2RectFilled(fill).apply {
                    setDimensions(cx + size * 0.54f, cy + size * 0.12f, size * 0.34f, size * 0.34f)
                    softCorners(size * 0.17f)
                })
                add(M2RectFilled(fill).apply {
                    setDimensions(cx + size * 0.22f, cy + size * 0.32f, size * 0.56f, size * 0.48f)
                    softCorners(size * 0.12f)
                })
            }
            hand -> {
                add(M2RectFilled(M2Color.Orange).apply {
                    setDimensions(cx + size * 0.22f, cy + size * 0.12f, size * 0.56f, size * 0.76f)
                    softCorners(size * 0.18f)
                })
                add(
                    HudPrimitives.label(
                        GazeKeyboardCatalog.asciiFallback(emoji),
                        cx + size * 0.18f,
                        cy + size * 0.32f,
                        0.55f,
                        M2Color.Black,
                    ),
                )
            }
            fireStar -> {
                val fill = if (emoji == "🔥") M2Color.Orange else M2Color.Yellow
                add(M2RectFilled(fill).apply {
                    setDimensions(cx + size * 0.15f, cy + size * 0.15f, size * 0.7f, size * 0.7f)
                    softCorners(size * 0.12f)
                })
                add(
                    HudPrimitives.label(
                        if (emoji == "🔥") "!" else "*",
                        cx + size * 0.35f,
                        cy + size * 0.28f,
                        0.85f,
                        M2Color.Black,
                    ),
                )
            }
            obj -> {
                add(M2RectFilled(M2Color.Blue).apply {
                    setDimensions(cx + size * 0.1f, cy + size * 0.18f, size * 0.8f, size * 0.64f)
                    softCorners(size * 0.14f)
                })
                add(
                    HudPrimitives.label(
                        GazeKeyboardCatalog.asciiFallback(emoji),
                        cx + size * 0.22f,
                        cy + size * 0.32f,
                        0.55f,
                        M2Color.White,
                    ),
                )
            }
            else -> {
                // Unique face colour + mouth shape keyed by emoji — not one shared plate.
                val fill = when (emoji) {
                    "😍", "🥰", "😘" -> M2Color.Orange
                    "😢", "😭" -> M2Color.Blue
                    "😤", "🤯" -> M2Color.Red
                    "😎", "🤔" -> M2Color.Teal
                    "😴" -> M2Color.Purple
                    else -> M2Color.Yellow
                }
                add(M2RectFilled(fill).apply {
                    setDimensions(cx, cy, size, size)
                    softCorners(size / 2f)
                })
                val ink = M2Color.Black
                val eye = (size * 0.12f).coerceAtLeast(3f)
                val eyeY = cy + size * 0.30f
                val openSmile = emoji in setOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣")
                add(M2RectFilled(ink).apply {
                    setDimensions(cx + size * 0.28f, eyeY, eye, eye)
                    softCorners(eye / 2f)
                })
                add(M2RectFilled(ink).apply {
                    setDimensions(cx + size * 0.58f, eyeY, eye, eye)
                    softCorners(eye / 2f)
                })
                if (openSmile) {
                    add(M2RectFilled(ink).apply {
                        setDimensions(cx + size * 0.28f, cy + size * 0.58f, size * 0.44f, size * 0.22f)
                        softCorners(size * 0.12f)
                    })
                } else {
                    add(M2RectFilled(ink).apply {
                        setDimensions(cx + size * 0.30f, cy + size * 0.62f, size * 0.40f, size * 0.10f)
                        softCorners(size * 0.08f)
                    })
                }
            }
        }
    }

    private fun invokeVoiceToText() {
        val handler = hud.actions.onVoiceToText ?: hud.actions.onVoiceRequested
        handler?.invoke()
    }

    private fun invokeVoiceNote() {
        val handler = hud.actions.onVoiceNote ?: hud.actions.onVoiceRequested
        handler?.invoke()
    }

    private fun handleTap(keyId: String) {
        com.mymav.glasses.api.KeyboardPress.press(hud, keyId)
    }

    companion object {
        /** Fields that need a rebuild; focus / gaze progress / buffer patch live. */
        fun structureKeyOf(h: GlassesHud.Keyboard): String = listOf(
            HudFonts.epoch, h.title, h.mode.name, h.pageIndex, h.pageCount, h.categoryId, h.searchQuery,
            h.suggestions.joinToString("\u0001"), h.voiceArmed, h.voiceMode.name, h.voiceStatus,
            h.capsLocked, h.doneLabel, h.symbols,
        ).joinToString("|")

        private const val BUF_X = 132f
        private const val BUF_W = 248f
    }
}
