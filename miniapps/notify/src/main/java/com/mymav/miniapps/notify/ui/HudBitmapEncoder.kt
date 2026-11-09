package com.mymav.miniapps.notify.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class EncodedHudImage(
    /** JPEG/PNG file bytes for [M2ImageBytes] (raw RGBA fails on SDK 0.2.0). */
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Encode bitmaps for glasses at the exact pixel size drawn on the HUD
 * so the SDK does not muddy a second downscale.
 */
object HudBitmapEncoder {
    fun rgba(bitmap: Bitmap, targetSize: Int = bitmap.width.coerceAtMost(bitmap.height)): EncodedHudImage {
        val square = if (bitmap.width == targetSize && bitmap.height == targetSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val encoded = encodeJpeg(square)
        if (square !== bitmap) square.recycle()
        return encoded
    }

    /**
     * Photo-like contact avatar (gradient portrait disc + soft highlight), not a letter tile.
     * Deterministic from [sender] so demos stay stable across runs.
     */
    fun contactAvatar(sender: String, accentArgb: Int, sizePx: Int = 80): EncodedHudImage {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val seed = sender.trim().lowercase().fold(0) { acc, c -> acc * 31 + c.code }
        val skin = SKIN_TONES[abs(seed) % SKIN_TONES.size]
        val hair = HAIR_TONES[abs(seed / 7) % HAIR_TONES.size]
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx / 2f

        // Accent ring background
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentArgb
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, ring)

        // Soft portrait disc
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.15f, cy - r * 0.2f, r * 0.95f,
                intArrayOf(lighten(skin, 0.18f), skin, darken(skin, 0.22f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r * 0.88f, facePaint)

        // Hair / upper shade
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hair
            style = Paint.Style.FILL
            alpha = 220
        }
        canvas.drawArc(
            RectF(cx - r * 0.82f, cy - r * 0.9f, cx + r * 0.82f, cy + r * 0.15f),
            200f, 140f, true, hairPaint,
        )

        // Soft highlight
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cx - r * 0.4f, cy - r * 0.5f, cx + r * 0.2f, cy + r * 0.1f,
                Color.argb(70, 255, 255, 255), Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx - r * 0.12f, cy - r * 0.08f, r * 0.55f, highlight)

        // Subtle monogram in lower corner (not the primary visual)
        val initial = sender.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = sizePx * 0.22f
        }
        canvas.drawText(initial, cx + r * 0.28f, cy + r * 0.42f, textPaint)

        val encoded = rgba(bitmap, sizePx)
        bitmap.recycle()
        return encoded
    }

    fun fromThumbnail(bitmap: Bitmap?, targetW: Int, targetH: Int): EncodedHudImage? {
        if (bitmap == null || bitmap.isRecycled) return null
        val src = softwareCopy(bitmap) ?: return null
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val encoded = encodeOpaqueRgba(scaled)
        if (scaled !== src) scaled.recycle()
        if (src !== bitmap) src.recycle()
        return encoded
    }

    /**
     * Center-crop contact photo to a square HUD avatar. Forces opaque alpha —
     * WhatsApp Icons often decode with empty/zero alpha and render as a black disc.
     */
    fun fromAvatar(bitmap: Bitmap?, sizePx: Int = 80): EncodedHudImage? {
        if (bitmap == null || bitmap.isRecycled) return null
        val src = softwareCopy(bitmap) ?: return null
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val left = ((src.width - side) / 2).coerceAtLeast(0)
        val top = ((src.height - side) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(src, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
        val encoded = encodeOpaqueRgba(scaled)
        if (cropped !== src) cropped.recycle()
        if (scaled !== cropped) scaled.recycle()
        if (src !== bitmap) src.recycle()
        return encoded
    }

    private fun softwareCopy(bitmap: Bitmap): Bitmap? {
        return runCatching {
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        }.getOrNull()
    }

    private fun encodeOpaqueRgba(bitmap: Bitmap): EncodedHudImage {
        // Flatten onto opaque black so JPEG never sees weird alpha (WA icons often 0-alpha).
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val encoded = encodeJpeg(out)
        out.recycle()
        return encoded
    }

    private fun encodeJpeg(bitmap: Bitmap): EncodedHudImage {
        val stream = ByteArrayOutputStream(bitmap.width * bitmap.height)
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        val bytes = if (ok) {
            stream.toByteArray()
        } else {
            val png = ByteArrayOutputStream(bitmap.width * bitmap.height)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            png.toByteArray()
        }
        return EncodedHudImage(bytes, bitmap.width, bitmap.height)
    }

    private val SKIN_TONES = intArrayOf(
        Color.parseColor("#F1C27D"),
        Color.parseColor("#E0AC69"),
        Color.parseColor("#C68642"),
        Color.parseColor("#8D5524"),
        Color.parseColor("#FFDBAC"),
    )

    private val HAIR_TONES = intArrayOf(
        Color.parseColor("#3B2F2F"),
        Color.parseColor("#1C1C1C"),
        Color.parseColor("#6B4423"),
        Color.parseColor("#A0522D"),
        Color.parseColor("#2F1B0C"),
    )

    private fun lighten(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun darken(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}
