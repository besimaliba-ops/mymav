package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.everysight.mav2.sdk.uikit.drawables.M2Image
import com.mymav.glasses.api.MymavHudPrefs

/** Image-backed jewel badges, decoded once at their exact on-glass pixel size. */
internal object PremiumHudIcons {
    private val ids = listOf(
        "notify", "spotify", "conversate", "food",
        "shop", "identify", "football", "fitness",
        "workouts", "mirror", "gallery", "tasks",
        "shopping", "recipes", "notepad", "memorysight",
        "money", "mymind", "calendar", "timer", "nav",
    )
    // Measured centres in the 1254px master; generated art is not a uniform grid.
    private val centersX = intArrayOf(184, 480, 772, 1072)
    private val centersY = intArrayOf(138, 376, 620, 862, 1106)
    private val cache = HashMap<String, ByteArray>()

    fun has(id: String): Boolean = id in ids

    fun image(id: String, size: Int, x: Float, y: Float): M2Image? {
        val index = ids.indexOf(id)
        if (index < 0 || size !in listOf(36, 56, 72)) return null
        val bytes = pixels(index, size) ?: return null
        return createHudImage(bytes, size, size, x, y, size.toFloat(), size.toFloat(), roundCorners = false, resident = true)
    }

    @Synchronized
    private fun pixels(index: Int, size: Int): ByteArray? {
        val key = "$index/$size"
        cache[key]?.let { return it }
        val context = MymavHudPrefs.context() ?: return null
        return runCatching {
            val navBadge = ids[index] == "nav"
            val master = context.assets.open(if (navBadge) "hud/nav-badge.png" else "hud/premium-app-badges.png").use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            try {
                // Build only the requested badge; retain just its small RGBA buffer.
                listOf(index).forEach { cell ->
                    val cx = if (navBadge) 0 else centersX[cell % 4]
                    val cy = if (navBadge) 0 else centersY[cell / 4]
                    val source = if (navBadge) Rect(0, 0, master.width, master.height) else Rect(cx - 122, cy - 122, cx + 122, cy + 122)
                    val output = downscale(master, source, size)
                    try {
                        val colors = IntArray(size * size)
                        output.getPixels(colors, 0, size, 0, 0, size, size)
                        val rgba = ByteArray(size * size * 4)
                        colors.forEachIndexed { i, color ->
                            rgba[i * 4] = (color ushr 16).toByte()
                            rgba[i * 4 + 1] = (color ushr 8).toByte()
                            rgba[i * 4 + 2] = color.toByte()
                            rgba[i * 4 + 3] = (color ushr 24).toByte()
                        }
                        cache["$cell/$size"] = rgba
                    } finally {
                        output.recycle()
                    }
                }
            } finally {
                master.recycle()
            }
            cache[key]
        }.onFailure { Log.w("PremiumHudIcons", "Badge artwork unavailable", it) }.getOrNull()
    }

    /**
     * Area-style downscale: crop to the target aspect, then halve repeatedly (bilinear on
     * each 2x step averages 2x2 blocks) until within 2x of [size], then a final fit.
     * A single 244 -> 36 bilinear tap skips most source pixels and looks pixelated/sparkly.
     */
    private fun downscale(master: Bitmap, source: Rect, size: Int): Bitmap {
        var cur = Bitmap.createBitmap(master, source.left, source.top, source.width(), source.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        while (cur.width >= size * 2) {
            val next = Bitmap.createBitmap(cur.width / 2, cur.height / 2, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, null, RectF(0f, 0f, next.width.toFloat(), next.height.toFloat()), paint)
            cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(cur, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        cur.recycle()
        return out
    }
}
