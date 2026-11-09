package com.mymav.glasses.api

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure

/**
 * Nested heart progress tracks — MyMav adaptation of the Samsung-style three-ring heart.
 * Green = steps, cyan = active kcal, coral = calories eaten. Not a copy of their asset.
 */
object HealthHeartTracks {
    val STEPS = Color.rgb(52, 199, 89)
    val ACTIVE = Color.rgb(50, 184, 220)
    val EATEN = Color.rgb(242, 120, 107)

    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        steps: Float?,
        active: Float?,
        eaten: Float?,
        light: Boolean = false,
    ) {
        val stroke = (size * 0.09f).coerceIn(3.6f, 10f)
        val gap = stroke * 1.85f
        val outer = size * 0.44f
        ring(canvas, cx, cy, outer, stroke, STEPS, steps, light)
        ring(canvas, cx, cy, outer - gap, stroke, ACTIVE, active, light)
        ring(canvas, cx, cy, outer - gap * 2f, stroke, EATEN, eaten, light)
    }

    private fun ring(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        stroke: Float,
        fill: Int,
        progress: Float?,
        light: Boolean,
    ) {
        val path = heartPath(cx, cy, radius * 2f)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedTrack(fill, light)
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, track)
        // Zero or no goal: muted same-hue track only. Never invent a sliver of fill.
        if (progress == null || progress <= 0f) return
        val measure = PathMeasure(path, true)
        val dest = Path()
        val length = measure.length
        if (length <= 0f) return
        val sweep = (progress.coerceAtMost(1f) * length).coerceAtLeast(length * 0.03f)
        measure.getSegment(0f, sweep, dest, true)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(dest, arc)
    }

    /** Remaining arc: a quieter mix of the ring colour, not a shared grey. */
    fun mutedTrack(fill: Int, light: Boolean): Int {
        val r = Color.red(fill)
        val g = Color.green(fill)
        val b = Color.blue(fill)
        return if (light) {
            Color.rgb((r + 255 * 2) / 3, (g + 255 * 2) / 3, (b + 255 * 2) / 3)
        } else {
            Color.argb(
                230,
                (r * 72 / 100).coerceAtLeast(40),
                (g * 72 / 100).coerceAtLeast(40),
                (b * 72 / 100).coerceAtLeast(40),
            )
        }
    }

    /** Closed heart starting at the top cleft, clockwise through the right lobe. */
    fun heartPath(cx: Float, cy: Float, size: Float): Path {
        val s = size / 2f
        return Path().apply {
            moveTo(cx, cy - s * 0.16f)
            cubicTo(
                cx + s * 0.12f, cy - s * 0.78f,
                cx + s * 1.02f, cy - s * 0.52f,
                cx + s * 0.94f, cy + s * 0.04f,
            )
            cubicTo(
                cx + s * 0.86f, cy + s * 0.52f,
                cx + s * 0.28f, cy + s * 0.78f,
                cx, cy + s * 0.92f,
            )
            cubicTo(
                cx - s * 0.28f, cy + s * 0.78f,
                cx - s * 0.86f, cy + s * 0.52f,
                cx - s * 0.94f, cy + s * 0.04f,
            )
            cubicTo(
                cx - s * 1.02f, cy - s * 0.52f,
                cx - s * 0.12f, cy - s * 0.78f,
                cx, cy - s * 0.16f,
            )
            close()
        }
    }
}
