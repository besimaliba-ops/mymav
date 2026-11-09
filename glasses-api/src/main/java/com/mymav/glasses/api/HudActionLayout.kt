package com.mymav.glasses.api

/**
 * Shared 640×400 geometry for the readable bottom action row and Shop offer rows.
 * The renderer (GlassKit) and the gaze hit-test both read these numbers so the pill
 * you see is exactly the pill that dwells.
 */
object HudActionLayout {
    const val ACTION_Y = 332f
    const val ACTION_H = 40f
    private const val ROW_X = 32f
    private const val ROW_W = 576f
    private const val GAP = 10f
    private const val MAX_PILL_W = 188f
    /** Extra vertical slack so a gaze resting just above/below a pill still counts. */
    private const val HIT_PAD_Y = 10f

    const val OFFER_TOP = 166f
    const val OFFER_ROW_H = 52f
    const val OFFER_GAP = 6f

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun contains(px: Float, py: Float, padY: Float = 0f): Boolean =
            px >= x && px <= x + w && py >= y - padY && py <= y + h + padY
    }

    fun pillWidth(count: Int): Float {
        val n = count.coerceAtLeast(1)
        return ((ROW_W - (n - 1) * GAP) / n).coerceAtMost(MAX_PILL_W)
    }

    fun pills(count: Int): List<Box> {
        val n = count.coerceAtLeast(1)
        val w = pillWidth(n)
        val total = n * w + (n - 1) * GAP
        val x0 = ROW_X + (ROW_W - total) / 2f
        return (0 until n).map { i -> Box(x0 + i * (w + GAP), ACTION_Y, w, ACTION_H) }
    }

    fun hitPill(count: Int, px: Float, py: Float): Int? {
        if (count <= 0) return null
        return pills(count).indexOfFirst { it.contains(px, py, HIT_PAD_Y) }.takeIf { it >= 0 }
    }

    fun offerRow(i: Int): Box = Box(ROW_X, OFFER_TOP + i * (OFFER_ROW_H + OFFER_GAP), ROW_W, OFFER_ROW_H)

    fun hitOffer(count: Int, px: Float, py: Float): Int? =
        (0 until count.coerceAtMost(2)).firstOrNull { offerRow(it).contains(px, py) }
}
