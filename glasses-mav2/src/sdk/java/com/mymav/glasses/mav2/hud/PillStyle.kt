package com.mymav.glasses.mav2.hud

import com.everysight.mav2.sdk.uikit.data.M2GradientFill
import com.everysight.mav2.sdk.uikit.data.M2GradientLength
import com.everysight.mav2.sdk.uikit.data.M2GradientLinear
import com.everysight.mav2.sdk.utils.M2Color

/**
 * Lit pills (selected tab, action button) use a deep saturated body with WHITE text.
 * Black text emits no light on the additive display, so it vanished on bright fills.
 */
internal object PillStyle {
    /** (body, top-highlight) ARGB for a bright accent; unknown accents fall back to teal. */
    fun deep(accent: M2Color): Pair<UInt, UInt> = when (accent) {
        M2Color.Amber, M2Color.Orange -> 0xFFA85600u to 0xFFE08A0Au
        M2Color.Green, M2Color.LightGreen -> 0xFF14702Fu to 0xFF2FAE55u
        M2Color.Red, M2Color.Pink -> 0xFF98203Au to 0xFFD04060u
        M2Color.Purple -> 0xFF5A2AA0u to 0xFF8654D0u
        else -> 0xFF06667Au to 0xFF1AA0BEu
    }

    /** Recolour a [HudPrimitives.softPill] body to the deep form of [accent]. */
    fun apply(pill: HudPrimitives.SoftPanel, accent: M2Color): HudPrimitives.SoftPanel {
        val (body, top) = deep(accent)
        pill.fill.setColor(body)
        pill.fill.setFill(
            M2GradientLinear(M2GradientFill.Stop.Start, M2GradientLength.fraction(1f), 90f, top),
        )
        return pill
    }

    val LABEL: M2Color = M2Color.White
}
