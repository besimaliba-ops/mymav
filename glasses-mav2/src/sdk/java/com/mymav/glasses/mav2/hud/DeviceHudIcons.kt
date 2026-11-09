package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.mymav.glasses.api.MymavHudPrefs

/**
 * Smartphone and glasses device marks for the home status area. Source art is the supplied pair of
 * transparent PNGs (trimmed, stray pixels removed, alpha kept); each is downsampled in halving steps to
 * the exact on-glass size and uploaded as straight RGBA. Decoded cost: roughly 44x13 + 15x26 px.
 */
internal object DeviceHudIcons {
    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    private val cache = HashMap<String, Encoded>()

    /** Glasses mark scaled to [height] px tall, aspect preserved. */
    fun glasses(height: Int): Encoded? = load("hud/device-glasses.png", height)

    /** Phone mark scaled to [height] px tall, aspect preserved. */
    fun phone(height: Int): Encoded? = load("hud/device-phone.png", height)

    @Synchronized
    private fun load(path: String, height: Int): Encoded? {
        val key = "$path@$height"
        cache[key]?.let { return it }
        val ctx = MymavHudPrefs.context() ?: return null
        val out = runCatching {
            val src = ctx.assets.open(path).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = true
                })
            } ?: return null
            val w = (src.width * height / src.height.toFloat()).toInt().coerceAtLeast(1)
            val scaled = downscale(src, w, height)
            val px = IntArray(w * height)
            scaled.getPixels(px, 0, w, 0, 0, w, height)
            scaled.recycle()
            val rgba = ByteArray(px.size * 4)
            var o = 0
            for (c in px) {
                // getPixels returns straight (un-premultiplied) ARGB.
                rgba[o++] = ((c ushr 16) and 0xff).toByte()
                rgba[o++] = ((c ushr 8) and 0xff).toByte()
                rgba[o++] = (c and 0xff).toByte()
                rgba[o++] = ((c ushr 24) and 0xff).toByte()
            }
            Encoded(rgba, w, height)
        }.onFailure { Log.w("DeviceHudIcons", "load $path failed: ${it.message}") }.getOrNull()
        if (out != null) cache[key] = out
        return out
    }

    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        while (cur.height / 2 >= h * 2 && cur.width / 2 >= w * 2) {
            val next = Bitmap.createBitmap(cur.width / 2, cur.height / 2, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, Rect(0, 0, cur.width, cur.height), RectF(0f, 0f, next.width.toFloat(), next.height.toFloat()), paint)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(cur, Rect(0, 0, cur.width, cur.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        if (cur !== src) cur.recycle()
        src.recycle()
        return out
    }
}
