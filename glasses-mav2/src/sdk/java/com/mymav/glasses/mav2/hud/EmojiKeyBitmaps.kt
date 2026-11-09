package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.everysight.mav2.sdk.uikit.drawables.M2Image

/**
 * Real colour emoji for the Maverick HUD.
 *
 * The glasses font has no emoji, so each emoji is rendered on the phone with
 * Android's own colour-emoji font (the same artwork as your phone keyboard) into
 * a transparent bitmap and uploaded as RGBA with alpha, drawn 1:1 (Native fit).
 */
object EmojiKeyBitmaps {
    /** Key glyph size (encode size == draw size). */
    const val SIZE = 40
    /** Inline glyph size for the typed-text line. */
    const val INLINE = 26

    data class EncodedEmoji(val bytes: ByteArray, val width: Int, val height: Int)

    private val cache = object : LinkedHashMap<String, EncodedEmoji>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EncodedEmoji>?) = size > 160
    }

    fun imageFor(emoji: String, x: Float, y: Float, w: Float, h: Float): M2Image? {
        val px = minOf(w, h).toInt().coerceAtLeast(12)
        val encoded = encode(emoji, px) ?: return null
        return createHudImage(
            encoded.bytes, encoded.width, encoded.height,
            x, y, px.toFloat(), px.toFloat(),
            circular = false,
            roundCorners = false,
        )
    }

    fun encode(emoji: String, size: Int = SIZE): EncodedEmoji? {
        val glyph = emoji.trim()
        if (glyph.isEmpty()) return null
        val key = "$glyph@$size"
        synchronized(cache) { cache[key]?.let { return it } }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
            textSize = size * 0.80f
        }
        // Fit the glyph's real ink bounds into the square.
        val bounds = Rect()
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val fit = minOf(size * 0.94f / bounds.width(), size * 0.94f / bounds.height(), 1.25f)
            paint.textSize *= fit
            paint.getTextBounds(glyph, 0, glyph.length, bounds)
        }
        val x = (size - bounds.width()) / 2f - bounds.left
        val y = (size - bounds.height()) / 2f - bounds.top
        canvas.drawText(glyph, x, y, paint)

        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        if (px.none { (it ushr 24) != 0 }) return null
        val rgba = ByteArray(w * h * 4)
        var o = 0
        for (c in px) {
            val a = c ushr 24
            if (a == 0) {
                o += 4
                continue
            }
            rgba[o++] = ((c ushr 16) and 0xff).toByte()
            rgba[o++] = ((c ushr 8) and 0xff).toByte()
            rgba[o++] = (c and 0xff).toByte()
            rgba[o++] = a.toByte()
        }
        val encoded = EncodedEmoji(rgba, w, h)
        synchronized(cache) { cache[key] = encoded }
        return encoded
    }

    /** True when [cp] starts an emoji / pictograph (needs an image, not text). */
    fun isEmojiStart(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp == 0x00A9 || cp == 0x00AE || cp in 0x2300..0x23FF ||
            cp in 0x2900..0x297F || cp in 0x3030..0x303D

    /**
     * Split text into runs of plain text and whole emoji (keeps ZWJ sequences,
     * skin tones and variation selectors together).
     */
    fun segments(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmojiStart(cp) || isRegional(cp)) {
                if (plain.isNotEmpty()) {
                    out += false to plain.toString()
                    plain.setLength(0)
                }
                val start = i
                i += Character.charCount(cp)
                if (isRegional(cp) && i < text.length && isRegional(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                } else {
                    while (i < text.length) {
                        val next = text.codePointAt(i)
                        when {
                            next == 0xFE0F || next == 0xFE0E || next in 0x1F3FB..0x1F3FF || next == 0x20E3 ->
                                i += Character.charCount(next)
                            next == 0x200D && i + 1 < text.length -> {
                                i += 1
                                val joined = text.codePointAt(i)
                                i += Character.charCount(joined)
                            }
                            else -> break
                        }
                    }
                }
                out += true to text.substring(start, i)
            } else {
                plain.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        if (plain.isNotEmpty()) out += false to plain.toString()
        return out
    }

    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    fun containsEmoji(text: String): Boolean = segments(text).any { it.first }
}
