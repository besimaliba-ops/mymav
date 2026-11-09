package com.mymav.glasses.api

import kotlin.math.hypot

/**
 * Geometry of the turn arrow, shared by the glasses HUD and the phone so both draw the same shape. Shapes are returned as
 * flat triangle lists (6 floats per triangle) in screen coordinates, centred on (cx, cy) with half-size s. The caller
 * stacks a few copies shifted downwards in darker colours to get the dimensional (extruded) look.
 */
object NavArrow {
    private class Geo(val path: List<FloatArray>, val tip: FloatArray, val dir: FloatArray)

    private fun f(x: Float, y: Float) = floatArrayOf(x, y)

    private fun geo(kind: String): Geo = when (kind) {
        "right" -> Geo(listOf(f(-0.3f, 0.95f), f(-0.3f, -0.2f), f(0.4f, -0.2f)), f(0.98f, -0.2f), f(1f, 0f))
        "left" -> Geo(listOf(f(0.3f, 0.95f), f(0.3f, -0.2f), f(-0.4f, -0.2f)), f(-0.98f, -0.2f), f(-1f, 0f))
        "slight right" -> Geo(listOf(f(-0.2f, 0.95f), f(-0.2f, 0.25f), f(0.1f, -0.15f)), f(0.48f, -0.62f), f(0.64f, -0.77f))
        "slight left" -> Geo(listOf(f(0.2f, 0.95f), f(0.2f, 0.25f), f(-0.1f, -0.15f)), f(-0.48f, -0.62f), f(-0.64f, -0.77f))
        "sharp right" -> Geo(listOf(f(-0.4f, -0.95f), f(-0.4f, -0.1f), f(0.0f, 0.3f)), f(0.4f, 0.78f), f(0.64f, 0.77f))
        "sharp left" -> Geo(listOf(f(0.4f, -0.95f), f(0.4f, -0.1f), f(0.0f, 0.3f)), f(-0.4f, 0.78f), f(-0.64f, 0.77f))
        "uturn" -> Geo(listOf(f(-0.42f, 0.95f), f(-0.42f, -0.3f), f(0f, -0.72f), f(0.42f, -0.3f), f(0.42f, 0.1f)), f(0.42f, 0.72f), f(0f, 1f))
        else -> Geo(listOf(f(0f, 0.95f), f(0f, -0.3f)), f(0f, -1f), f(0f, -1f))
    }

    private const val HW = 0.2f
    private const val HEAD_LEN = 0.64f
    private const val HEAD_HALF = 0.58f

    fun triangles(kind: String, cx: Float, cy: Float, s: Float): FloatArray {
        val out = ArrayList<Float>()
        fun tri(a: Float, b: Float, c: Float, d: Float, e: Float, g: Float) { out += listOf(cx + a * s, cy + b * s, cx + c * s, cy + d * s, cx + e * s, cy + g * s) }
        if (kind == "arrive") {   // a flag
            tri(-0.36f, -0.95f, -0.24f, -0.95f, -0.24f, 0.95f); tri(-0.36f, -0.95f, -0.24f, 0.95f, -0.36f, 0.95f)
            tri(-0.3f, -0.95f, 0.8f, -0.5f, -0.3f, -0.05f)
            return out.toFloatArray()
        }
        val g = geo(kind)
        val bx = g.tip[0] - g.dir[0] * HEAD_LEN; val by = g.tip[1] - g.dir[1] * HEAD_LEN
        val nx = -g.dir[1]; val ny = g.dir[0]
        val pts = g.path.toMutableList().also { it[it.size - 1] = f(bx + g.dir[0] * 0.04f, by + g.dir[1] * 0.04f) }
        for (i in 0 until pts.size - 1) {
            var ax = pts[i][0]; var ay = pts[i][1]; var bx2 = pts[i + 1][0]; var by2 = pts[i + 1][1]
            val dx = bx2 - ax; val dy = by2 - ay; val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val ux = dx / l; val uy = dy / l
            ax -= ux * HW * 0.5f; ay -= uy * HW * 0.5f; bx2 += ux * HW * 0.5f; by2 += uy * HW * 0.5f
            val px = -uy * HW; val py = ux * HW
            tri(ax + px, ay + py, ax - px, ay - py, bx2 - px, by2 - py)
            tri(ax + px, ay + py, bx2 - px, by2 - py, bx2 + px, by2 + py)
        }
        tri(g.tip[0], g.tip[1], bx + nx * HEAD_HALF, by + ny * HEAD_HALF, bx - nx * HEAD_HALF, by - ny * HEAD_HALF)
        return out.toFloatArray()
    }

    /** Three points (x0,y0,x1,y1,x2,y2) along the arrow head for a thin rim light, or null for shapes without a head. */
    fun rim(kind: String, cx: Float, cy: Float, s: Float): FloatArray? {
        if (kind == "arrive") return null
        val g = geo(kind)
        val bx = g.tip[0] - g.dir[0] * HEAD_LEN; val by = g.tip[1] - g.dir[1] * HEAD_LEN
        val nx = -g.dir[1]; val ny = g.dir[0]
        return floatArrayOf(cx + (bx + nx * HEAD_HALF) * s, cy + (by + ny * HEAD_HALF) * s, cx + g.tip[0] * s, cy + g.tip[1] * s, cx + (bx - nx * HEAD_HALF) * s, cy + (by - ny * HEAD_HALF) * s)
    }
}
