package com.mymav.glasses.mav2.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log

/**
 * Official WhatsApp / Instagram / Spotify marks for the glasses HUD.
 *
 * Clean transparent PNG masters (assets/brand, "-clean.png", 512px) are
 * down-sampled in high-quality halving steps to the **exact** on-glass size and
 * uploaded as RGBA with alpha (Native 1:1 fit — the same path as the 3D mascot).
 * No frosted plate, no JPEG, no corner clipping.
 */
object BrandHudIcons {
    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    /** Kept for callers that still ask for a corner radius (marks are drawn unclipped). */
    const val PLATE_CORNER_FRAC = 0.22f

    @Volatile
    private var appContext: Context? = null
    private val cache = HashMap<String, Encoded>()

    fun install(context: Context) {
        appContext = context.applicationContext
        invalidate()
    }

    /** Application context captured at install (shared with other asset loaders). */
    internal fun contextOrNull(): Context? = appContext

    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    fun whatsApp(sizePx: Int = 64): Encoded =
        mark("wa", sizePx, listOf("brand/whatsapp-clean.png", "brand/whatsapp.png")) { proceduralWhatsApp(it) }

    fun instagram(sizePx: Int = 64): Encoded =
        mark("ig", sizePx, listOf("brand/instagram-clean.png", "brand/instagram.png")) { proceduralInstagram(it) }

    fun spotify(sizePx: Int = 64): Encoded =
        mark("sp", sizePx, listOf("brand/spotify-clean.png")) { proceduralSpotify(it) }

    @Deprecated("Use spotify()")
    fun musicNote(sizePx: Int = 56): Encoded = spotify(sizePx)

    private fun mark(tag: String, sizePx: Int, assets: List<String>, fallback: (Int) -> Bitmap): Encoded {
        val key = "$tag@$sizePx"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = assets.firstNotNullOfOrNull { loadAsset(it, sizePx) } ?: fallback(sizePx)
        val encoded = rgba(bmp)
        bmp.recycle()
        synchronized(cache) { cache[key] = encoded }
        return encoded
    }

    /** Decode a master and scale it to exactly [sizePx]², keeping alpha. */
    private fun loadAsset(path: String, sizePx: Int): Bitmap? {
        val ctx = appContext ?: return null
        return runCatching {
            val src = ctx.assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = true
                })
            } ?: return@runCatching null
            val square = squareOnTransparent(src)
            if (square !== src) src.recycle()
            downscale(square, sizePx)
        }.onFailure { Log.w("BrandHudIcons", "load $path failed: ${it.message}") }.getOrNull()
    }

    private fun squareOnTransparent(src: Bitmap): Bitmap {
        if (src.width == src.height) return src
        val side = maxOf(src.width, src.height)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, ((side - src.width) / 2f), ((side - src.height) / 2f), null)
        return out
    }

    /**
     * Progressive 2× halving (bilinear) then one final exact step —
     * avoids the aliasing a single big down-sample produces.
     */
    private fun downscale(src: Bitmap, target: Int): Bitmap {
        var cur = src
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        while (cur.width / 2 >= target * 2) {
            val half = cur.width / 2
            val next = Bitmap.createBitmap(half, half, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(cur, Rect(0, 0, cur.width, cur.height), RectF(0f, 0f, half.toFloat(), half.toFloat()), paint)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(cur, Rect(0, 0, cur.width, cur.height), RectF(0f, 0f, target.toFloat(), target.toFloat()), paint)
        if (cur !== src) cur.recycle()
        src.recycle()
        return out
    }

    /** Straight (un-premultiplied) RGBA bytes for the SDK's keepAlpha PNG upload. */
    private fun rgba(bmp: Bitmap): Encoded {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        var o = 0
        for (c in px) {
            out[o++] = ((c ushr 16) and 0xff).toByte()
            out[o++] = ((c ushr 8) and 0xff).toByte()
            out[o++] = (c and 0xff).toByte()
            out[o++] = ((c ushr 24) and 0xff).toByte()
        }
        return Encoded(out, w, h)
    }

    // ---- vector fallbacks (only if the asset is missing) -------------------------------

    private fun canvasFor(sizePx: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun proceduralWhatsApp(sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val r = sizePx * 0.48f
        c.drawCircle(sizePx / 2f, sizePx / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 211, 102) })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
        }
        c.drawCircle(sizePx / 2f, sizePx / 2f, r * 0.62f, white)
        return bmp
    }

    private fun proceduralInstagram(sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val s = sizePx.toFloat()
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, s, s, 0f,
                intArrayOf(Color.rgb(252, 176, 69), Color.rgb(253, 29, 29), Color.rgb(131, 58, 180)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, grad)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = s * 0.07f
        }
        c.drawRoundRect(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), s * 0.16f, s * 0.16f, white)
        c.drawCircle(s / 2f, s / 2f, s * 0.14f, white)
        c.drawCircle(s * 0.68f, s * 0.32f, s * 0.04f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bmp
    }

    private fun proceduralSpotify(sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val cx = sizePx / 2f
        val r = sizePx * 0.48f
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 215, 96) })
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 16, 16)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        fun stroke(yOff: Float, halfW: Float, thick: Float) {
            arc.strokeWidth = thick
            c.drawArc(RectF(cx - halfW, cx + yOff - halfW * 0.55f, cx + halfW, cx + yOff + halfW * 0.55f), 200f, 140f, false, arc)
        }
        stroke(-r * 0.12f, r * 0.6f, r * 0.15f)
        stroke(r * 0.1f, r * 0.46f, r * 0.13f)
        stroke(r * 0.3f, r * 0.32f, r * 0.11f)
        return bmp
    }
}
