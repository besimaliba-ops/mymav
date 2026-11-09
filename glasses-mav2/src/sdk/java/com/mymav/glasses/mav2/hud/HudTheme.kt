package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.HudAccent
import com.mymav.glasses.api.HudAccentTone

/**
 * Shared premium HUD tokens for the 640×400 Maverick AI optical plane.
 *
 * Design language: deep "smoked glass" panels, crisp hairline edges, one cool
 * focus accent, white primary type. Kept dark on purpose — the Maverick display
 * is additive, so pale/grey fills read as a milky haze over the real world.
 *
 * Named tokens stay [M2Color] so every existing call site keeps compiling;
 * the custom ARGB ([UInt]) ink below is used inside [HudPrimitives].
 */
object HudTheme {
    const val W = 640f
    const val H = 400f

    const val MARGIN = 16f
    /** Corner radius for glass panels / tiles (SDK setCornerRadius). */
    const val PANEL_RADIUS = 18f
    /** Pill / status chip radius. */
    const val PILL_RADIUS = 18f
    /** Keyboard key / small chip radius. */
    const val KEY_RADIUS = 12f
    /** Soft focus ring stroke. */
    const val FOCUS_STROKE = 2f
    /** Hairline glass edge. */
    const val PANEL_STROKE = 1f

    /** Kept for call sites that still pass a panel tint explicitly. */
    val PanelFill: M2Color = M2Color.BlueGrey
    /** Neutral edge token (callers pass it as `border` = "not focused"). */
    val PanelBorder: M2Color = M2Color.BlueGrey
    /** Gaze / selection accent. */
    val FocusGlow: M2Color = M2Color.Cyan
    val TextPrimary: M2Color = M2Color.White
    val TextMuted: M2Color = M2Color.White
    val Gain: M2Color = M2Color.LightGreen
    val Warning: M2Color = M2Color.Amber
    val Danger: M2Color = M2Color.Red
    val BrandSpotify: M2Color = M2Color.Green
    val MicPill: M2Color = M2Color.Amber

    /** Fill opacity for frosted panels — fully opaque; the additive display has
     *  no way to show "see-through" other than genuinely dark/low-alpha pixels,
     *  so anything meant to read as a solid card/avatar/bubble must be alpha 1. */
    const val PANEL_ALPHA = 0.92f
    /** Neutral edge opacity. */
    const val BORDER_ALPHA = 0.42f
    /** Focus ring opacity. */
    const val FOCUS_ALPHA = 0.95f
    /** Soft wash inside / around the focused element. */
    const val FOCUS_FILL_ALPHA = 0.10f
    /** Top-edge sheen strength (glossy but not milky on the additive display). */
    const val PANEL_GLOSS_ALPHA = 0.09f
    /** Specular rim highlight strength. */
    const val RIM_ALPHA = 0.26f
    /** Panel edge stroke — brighter, slightly heavier hairline. */
    const val PANEL_STROKE_BRIGHT = 1f
    /** Vertical fraction of the panel covered by the sheen. */
    const val PANEL_GLOSS_FRAC = 0.38f
    /** Top-left corner sheen peak alpha (large panels) and bottom-right reflection peak alpha. */
    const val CORNER_SHEEN_ALPHA = 0.40f
    const val CORNER_REFLECT_ALPHA = 0.28f

    /** Minimum text scale — below this the stock bitmap font breaks up on glass. */
    const val MIN_TEXT_SCALE = 0.46f

    // ---- Custom ARGB ink (UInt) -------------------------------------------------

    /** Smoked-glass panel body — bottom of the body gradient.
     *
     * The Maverick display is additive/optical-see-through: there is no way to
     * block the real world, only to add light on top of it, so alpha only
     * blends *within* the HUD — a dark fill, however opaque its alpha channel,
     * still reads as "see-through" because it adds almost no light over a
     * bright background. Confirmed on the simulator: the AUDIO/TEXT pills
     * (bright amber/cyan) and the CANCEL pill (M2Color.BlueGrey, ~46%
     * luminance) read as solid; the old near-black panel body did not. Raised
     * to roughly BlueGrey's brightness so panels read the same way. */
    val InkPanel: UInt = 0xFF0A1830u
    /** Top of the body gradient (lifted, brighter steel blue). */
    val InkPanelTop: UInt = 0xFF21406Cu
    /** Bright panel edge. */
    val InkEdgeBright: UInt = 0xFF6F8FB8u
    /** Specular rim — pure white highlight. */
    val InkRim: UInt = 0xFFFFFFFFu
    /** Slightly lifted surface for keys / chips — brightened alongside [InkPanel]. */
    val InkRaised: UInt = 0xFF233447u
    /** Neutral hairline. */
    val InkEdge: UInt = 0xFF4A4A4Au
    /** Secondary text — bright enough to read as solid, not hazy, on glass. */
    val InkMuted: UInt = 0xFFD2E0F4u
    /** Focus accent (soft cyan). */
    val InkFocus: UInt = 0xFF45D6F5u
}

/** Apply [alpha] (0..1) to a raw ARGB colour. */
fun UInt.withAlpha(alpha: Float): UInt {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toUInt()
    return (this and 0x00FFFFFFu) or (a shl 24)
}

fun HudAccentTone.toM2Color(): M2Color = when (this) {
    HudAccentTone.Teal -> M2Color.Teal
    HudAccentTone.Green -> M2Color.Green
    HudAccentTone.Yellow -> M2Color.Amber
    HudAccentTone.Red -> M2Color.Red
    HudAccentTone.Blue -> M2Color.LightBlue
    HudAccentTone.Orange -> M2Color.Orange
    HudAccentTone.Purple -> M2Color.Purple
    HudAccentTone.Spotify -> M2Color.Green
}

fun HudAccent.toM2Color(): M2Color = when (this) {
    HudAccent.WhatsAppGreen, HudAccent.SpotifyGreen -> M2Color.Green
    HudAccent.InstagramRed -> M2Color.Pink
}

fun HudAccent.toTone(): HudAccentTone = when (this) {
    HudAccent.WhatsAppGreen, HudAccent.SpotifyGreen -> HudAccentTone.Green
    HudAccent.InstagramRed -> HudAccentTone.Red
}

/**
 * Short premium ALL-CAPS label.
 *
 * Previously this interleaved U+200A hair spaces between letters; the glasses'
 * stock font has no glyph for them, so they rendered as nothing and real spaces
 * collapsed ("SYNCED195S"). Now it is plain upper-case text.
 */
fun trackedCaps(text: String): String = hudSafe(text.trim()).uppercase()

/**
 * Map characters the Maverick stock font cannot draw to ASCII equivalents.
 * Missing glyphs render as blanks on glass, which silently swallowed separators
 * ("·"), arrows, ellipses and every emoji in notification text.
 */
fun hudSafe(raw: String): String {
    if (raw.isEmpty()) return raw
    var needs = false
    for (ch in raw) {
        if (ch.code > 0x7E || ch.code < 0x20) {
            needs = true
            break
        }
    }
    if (!needs) return raw
    val out = StringBuilder(raw.length + 8)
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when (cp) {
            0x09, 0x0A, 0x0D -> out.append(' ')
            0x00B7, 0x2022, 0x2219, 0x22C5 -> out.append(" - ")
            0x2013, 0x2014, 0x2015, 0x2212 -> out.append('-')
            0x2018, 0x2019, 0x201A, 0x2032 -> out.append('\'')
            0x201C, 0x201D, 0x201E, 0x2033 -> out.append('"')
            0x2026 -> out.append("...")
            0x00D7 -> out.append('x')
            0x2190 -> out.append("<")
            0x2192 -> out.append(">")
            0x2191 -> out.append("^")
            0x2193 -> out.append("v")
            0x25A1, 0x2610 -> out.append("[ ]")
            0x2713, 0x2714, 0x2611 -> out.append("[x]")
            0x25B6, 0x25BA -> out.append(">")
            0x266A, 0x266B -> out.append("~")
            0x00A3 -> out.append("GBP ")
            0x20AC -> out.append("EUR ")
            0x00B0 -> out.append(" deg")
            0x00A0, 0x2009, 0x200A, 0x2002, 0x2003, 0x202F -> out.append(' ')
            0x200B, 0x200C, 0x200D, 0xFE0F, 0xFE0E -> Unit
            else -> when {
                cp in 0x20..0x7E -> out.appendCodePoint(cp)
                // Latin letters with accents: fold (é -> e) — the stock font is ASCII.
                cp in 0xC0..0x24F -> out.append(foldLatin(cp))
                // Emoji / pictographs / everything else: drop.
                else -> Unit
            }
        }
    }
    return out.toString().replace(Regex(" {3,}"), "  ")
}

private fun foldLatin(cp: Int): String {
    val s = String(Character.toChars(cp))
    val norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    val base = norm.filter { it.code in 0x20..0x7E }
    return when {
        base.isNotEmpty() -> base
        cp == 0xDF -> "ss"
        cp == 0xC6 -> "AE"
        cp == 0xE6 -> "ae"
        cp == 0xD8 -> "O"
        cp == 0xF8 -> "o"
        else -> ""
    }
}
