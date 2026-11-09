package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mymav.glasses.api.HealthHeartSnapshot
import com.mymav.glasses.api.HealthHeartTracks

/**
 * Small procedural icons for Health glass rows (M2Text cannot render emoji reliably).
 * Keys: steps, distance, active, total, sleep, workout, calendar.
 */
object HealthHudIcons {
    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    fun icon(key: String, sizePx: Int = 28): Encoded = when (key.lowercase()) {
        "steps", "feet", "👟", "👣" -> feet(sizePx)
        "distance", "pin", "📍" -> pin(sizePx)
        "active", "fire", "🔥" -> flame(sizePx)
        "total", "bolt", "⚡" -> bolt(sizePx)
        "sleep", "bed", "😴", "🛏️" -> bed(sizePx)
        "workout", "lift", "🏋️" -> dumbbell(sizePx)
        "calendar", "📅" -> calendar(sizePx)
        else -> feet(sizePx)
    }

    fun feet(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 180, 255) }
        // Two shoe prints
        c.drawOval(RectF(sizePx * 0.12f, sizePx * 0.2f, sizePx * 0.42f, sizePx * 0.78f), p)
        c.drawOval(RectF(sizePx * 0.52f, sizePx * 0.28f, sizePx * 0.88f, sizePx * 0.86f), p)
        val sole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 100, 180) }
        c.drawOval(RectF(sizePx * 0.16f, sizePx * 0.48f, sizePx * 0.38f, sizePx * 0.72f), sole)
        c.drawOval(RectF(sizePx * 0.56f, sizePx * 0.56f, sizePx * 0.84f, sizePx * 0.8f), sole)
        return rgba(bmp)
    }

    fun pin(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 70, 70) }
        val cx = sizePx / 2f
        c.drawCircle(cx, sizePx * 0.38f, sizePx * 0.28f, p)
        val path = Path().apply {
            moveTo(cx - sizePx * 0.22f, sizePx * 0.48f)
            lineTo(cx, sizePx * 0.92f)
            lineTo(cx + sizePx * 0.22f, sizePx * 0.48f)
            close()
        }
        c.drawPath(path, p)
        c.drawCircle(cx, sizePx * 0.38f, sizePx * 0.1f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        return rgba(bmp)
    }

    fun flame(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 140, 40) }
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 60) }
        val path = Path().apply {
            moveTo(sizePx * 0.5f, sizePx * 0.1f)
            cubicTo(sizePx * 0.85f, sizePx * 0.4f, sizePx * 0.9f, sizePx * 0.75f, sizePx * 0.5f, sizePx * 0.92f)
            cubicTo(sizePx * 0.1f, sizePx * 0.75f, sizePx * 0.15f, sizePx * 0.4f, sizePx * 0.5f, sizePx * 0.1f)
            close()
        }
        c.drawPath(path, orange)
        c.drawOval(RectF(sizePx * 0.35f, sizePx * 0.45f, sizePx * 0.65f, sizePx * 0.85f), yellow)
        return rgba(bmp)
    }

    fun bolt(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 220, 60) }
        val path = Path().apply {
            moveTo(sizePx * 0.58f, sizePx * 0.08f)
            lineTo(sizePx * 0.28f, sizePx * 0.52f)
            lineTo(sizePx * 0.48f, sizePx * 0.52f)
            lineTo(sizePx * 0.38f, sizePx * 0.92f)
            lineTo(sizePx * 0.72f, sizePx * 0.42f)
            lineTo(sizePx * 0.52f, sizePx * 0.42f)
            close()
        }
        c.drawPath(path, p)
        return rgba(bmp)
    }

    fun bed(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 180, 255) }
        c.drawRoundRect(RectF(sizePx * 0.12f, sizePx * 0.45f, sizePx * 0.88f, sizePx * 0.72f), 4f, 4f, p)
        c.drawRoundRect(RectF(sizePx * 0.12f, sizePx * 0.28f, sizePx * 0.42f, sizePx * 0.5f), 4f, 4f, p)
        c.drawRect(sizePx * 0.14f, sizePx * 0.7f, sizePx * 0.2f, sizePx * 0.88f, p)
        c.drawRect(sizePx * 0.8f, sizePx * 0.7f, sizePx * 0.86f, sizePx * 0.88f, p)
        return rgba(bmp)
    }

    fun dumbbell(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 230) }
        c.drawRoundRect(RectF(sizePx * 0.08f, sizePx * 0.28f, sizePx * 0.28f, sizePx * 0.72f), 3f, 3f, p)
        c.drawRoundRect(RectF(sizePx * 0.72f, sizePx * 0.28f, sizePx * 0.92f, sizePx * 0.72f), 3f, 3f, p)
        c.drawRect(sizePx * 0.26f, sizePx * 0.44f, sizePx * 0.74f, sizePx * 0.56f, p)
        return rgba(bmp)
    }

    fun calendar(sizePx: Int): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 24, 32))
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 60, 60) }
        c.drawRoundRect(RectF(sizePx * 0.15f, sizePx * 0.2f, sizePx * 0.85f, sizePx * 0.88f), 4f, 4f, body)
        c.drawRect(sizePx * 0.15f, sizePx * 0.2f, sizePx * 0.85f, sizePx * 0.4f, header)
        return rgba(bmp)
    }

    /** Nested heart progress tracks for the Health home widget. */
    fun activityHeart(
        sizePx: Int,
        stepsProgress: Float?,
        activeProgress: Float?,
        kcalProgress: Float?,
    ): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        HealthHeartTracks.draw(
            canvas = c,
            cx = sizePx / 2f,
            cy = sizePx / 2f,
            size = sizePx.toFloat(),
            steps = stepsProgress,
            active = activeProgress,
            eaten = kcalProgress,
            light = false,
        )
        punchDarkFringe(bmp)
        return rgba(bmp)
    }

    fun heartCacheKey(sizePx: Int, steps: Float?, active: Float?, eaten: Float?): String =
        "$sizePx:${HealthHeartSnapshot.quantize(steps)}:${HealthHeartSnapshot.quantize(active)}:${HealthHeartSnapshot.quantize(eaten)}"

    /**
     * Single step-progress ring: faint full track plus a bright round-capped arc. [progress] is the fraction of
     * the 10,000-step reference reached today (the app has no per-user target); null draws the track only.
     */
    fun stepRing(sizePx: Int, progress: Float?): Encoded {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        val stroke = sizePx * 0.11f
        val r = sizePx / 2f - stroke / 2f - 1f
        val oval = RectF(sizePx / 2f - r, sizePx / 2f - r, sizePx / 2f + r, sizePx / 2f + r)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 120, 150, 200); style = Paint.Style.STROKE; strokeWidth = stroke
        }
        c.drawArc(oval, 0f, 360f, false, track)
        if (progress != null && progress > 0f) {
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(60, 230, 110); style = Paint.Style.STROKE; strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(oval, -90f, 360f * progress.coerceIn(0.03f, 1f), false, arc)
        }
        return rgba(bmp)
    }

    private fun punchDarkFringe(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xff
            if (a == 0) continue
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8) and 0xff
            val b = c and 0xff
            if (r < 40 && g < 40 && b < 40) {
                px[i] = Color.TRANSPARENT
            } else if (a < 40 && r + g + b < 120) {
                px[i] = Color.TRANSPARENT
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun rgba(bmp: Bitmap): Encoded {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle()
        val out = ByteArray(w * h * 4)
        var o = 0
        for (c in argb) {
            out[o++] = ((c ushr 16) and 0xff).toByte()
            out[o++] = ((c ushr 8) and 0xff).toByte()
            out[o++] = (c and 0xff).toByte()
            out[o++] = ((c ushr 24) and 0xff).toByte()
        }
        return Encoded(out, w, h)
    }
}
