package com.mymav.glasses.mav2

import com.everysight.mav2.sdk.Evs

/**
 * Glasses display brightness through the Everysight SDK display service. The SDK works on a 0-255 scale (the
 * simulator's percentage is value / 255); this object speaks percent (0-100) to the rest of the app. Every call
 * returns null / false when the glasses (or simulator) aren't connected yet, so callers can say so instead of guessing.
 */
object Mav2Brightness {
    private const val RAW_MAX = 255

    /** Current brightness as a percentage, or null when the display service isn't available. */
    fun get(): Int? = runCatching { Math.round(Evs.displayService.getBrightness() * 100f / RAW_MAX) }.getOrNull()

    fun set(percent: Int): Boolean = runCatching {
        Evs.displayService.setBrightness(Math.round(percent.coerceIn(0, 100) * RAW_MAX / 100f)); true
    }.getOrDefault(false)

    fun autoOn(): Boolean? = runCatching { Evs.displayService.isAutoBrightnessOn() }.getOrNull()

    fun setAuto(on: Boolean): Boolean = runCatching { Evs.displayService.enableAutoBrightness(on); true }.getOrDefault(false)
}
