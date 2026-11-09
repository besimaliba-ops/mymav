package com.mymav.miniapps.spotify.api

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mymav.miniapps.spotify.model.EncodedSpotifyArt
import java.io.ByteArrayOutputStream

/**
 * JPEG/PNG file bytes for [com.everysight.mav2.sdk.resources.M2ImageBytes].
 * Raw RGBA fails with "Failed to encode image data to PNG or JPEG" on SDK 0.2.0.
 */
internal object HudRgbaEncoder {
    /**
     * Official Spotify green circle + three black arcs (not a music note).
     * Used when no cover URL exists (demo / offline home tile).
     */
    fun demoPlaceholder(sizePx: Int = 88): EncodedSpotifyArt {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Emissive plate — pure black keys out on Maverick glass.
        canvas.drawColor(Color.rgb(36, 44, 54))
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = sizePx * 0.38f
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 215, 96) }
        canvas.drawCircle(cx, cy, r, green)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Three arcs — longest on top (official mark).
        fun strokeArc(yOff: Float, halfW: Float, thick: Float) {
            arc.strokeWidth = thick
            val oval = android.graphics.RectF(cx - halfW, cy + yOff - halfW * 0.55f, cx + halfW, cy + yOff + halfW * 0.55f)
            canvas.drawArc(oval, 200f, 140f, false, arc)
        }
        strokeArc(-r * 0.12f, r * 0.55f, r * 0.16f)
        strokeArc(r * 0.08f, r * 0.42f, r * 0.14f)
        strokeArc(r * 0.26f, r * 0.28f, r * 0.12f)
        val stream = ByteArrayOutputStream(sizePx * sizePx)
        out.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        out.recycle()
        return EncodedSpotifyArt(stream.toByteArray(), sizePx, sizePx, fromVideoPreview = false)
    }

    fun fromBitmap(
        bitmap: Bitmap,
        targetW: Int,
        targetH: Int,
        fromVideoPreview: Boolean = false,
    ): EncodedSpotifyArt {
        val src = softwareCopy(bitmap) ?: bitmap
        val squared = centerCropSquare(src)
        val scaled = if (squared.width == targetW && squared.height == targetH) {
            squared
        } else {
            // Prefer sharper bilinear scale up to HQ cover size (no soft over-downscale).
            Bitmap.createScaledBitmap(squared, targetW, targetH, true)
        }
        // Draw onto an opaque canvas so JPEG/PNG encode never sees weird alpha.
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // Soft rounded look is applied by M2Image softCorners; keep pixels rectangular.

        val png = ByteArrayOutputStream(targetW * targetH)
        // Max JPEG quality for album art on SDK 0.2.0 glass.
        val ok = out.compress(Bitmap.CompressFormat.JPEG, 100, png)
        val bytes = if (ok) png.toByteArray() else {
            val png2 = ByteArrayOutputStream(targetW * targetH)
            out.compress(Bitmap.CompressFormat.PNG, 100, png2)
            png2.toByteArray()
        }

        if (scaled !== squared) scaled.recycle()
        if (squared !== src) squared.recycle()
        if (src !== bitmap) src.recycle()
        out.recycle()
        return EncodedSpotifyArt(bytes, targetW, targetH, fromVideoPreview)
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        if (src.width == side && src.height == side) return src
        val left = ((src.width - side) / 2).coerceAtLeast(0)
        val top = ((src.height - side) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, side, side)
    }

    private fun softwareCopy(bitmap: Bitmap): Bitmap? {
        return runCatching {
            when (bitmap.config) {
                Bitmap.Config.ARGB_8888 -> bitmap
                Bitmap.Config.HARDWARE -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
                else -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        }.getOrNull()
    }
}
