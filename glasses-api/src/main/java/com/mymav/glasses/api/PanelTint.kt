package com.mymav.glasses.api

/**
 * Panel colour (tint) for the glasses glass surfaces, independent of the four panel styles.
 *
 * A tint is stored as a key: a preset key ("neutral", "ice", ...) or a custom "#RRGGBB". The transform below is
 * applied once, when a packaged surface is decoded (glasses) or previewed (phone), so no per-colour asset pack
 * and no per-colour upload path exists: the same artwork is recoloured in memory and cached only for the colours
 * actually in use.
 *
 * The body is tinted gently and the corner reflections stay mostly neutral silver-white: tint weight falls off
 * quickly where the artwork's alpha rises above its body level (that is where the reflections are).
 */
object PanelTint {
    enum class Preset(val key: String, val label: String, val argb: Int?) {
        Neutral("neutral", "Neutral", null),
        Ice("ice", "Ice Blue", 0xFF8EC9FF.toInt()),
        Cyan("cyan", "Cyan", 0xFF39D6E6.toInt()),
        Emerald("emerald", "Emerald", 0xFF3FDC98.toInt()),
        Rose("rose", "Rose", 0xFFFF7FAA.toInt()),
        Violet("violet", "Violet", 0xFFA68CFF.toInt()),
        Amber("amber", "Amber", 0xFFFFB347.toInt()),
    }

    const val NEUTRAL = "neutral"

    /** Colour for a tint key, or null for Neutral / an unreadable key (treated as Neutral). */
    fun argbOf(key: String?): Int? {
        if (key == null) return null
        Preset.entries.firstOrNull { it.key == key }?.let { return it.argb }
        if (key.length == 7 && key[0] == '#') {
            val v = key.substring(1).toIntOrNull(16) ?: return null
            return 0xFF000000.toInt() or v
        }
        return null
    }

    fun customKey(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    fun labelOf(key: String?): String =
        Preset.entries.firstOrNull { it.key == key }?.label ?: if (argbOf(key) != null) "Custom" else "Neutral"

    /**
     * Tint straight-alpha ARGB pixels in place. [strengthPct] 0..100. Transparent pixels and the artwork's own
     * alpha shape (silhouette, soft edge, reflections) are preserved.
     */
    fun apply(px: IntArray, tintArgb: Int, strengthPct: Int) {
        val s = (strengthPct.coerceIn(0, 100)) / 100f
        if (s <= 0f) return
        val hist = IntArray(256)
        var nz = 0
        for (v in px) {
            val a = v ushr 24
            if (a > 0) { hist[a]++; nz++ }
        }
        if (nz == 0) return
        // Body level: the 35th percentile of non-zero alpha (the body dominates the area; reflections are the upper tail).
        var acc = 0
        var b0 = 1
        val target = (nz * 0.35f).toInt()
        for (a in 1..255) { acc += hist[a]; if (acc >= target) { b0 = a; break } }
        val body = b0.coerceAtLeast(1) / 255f
        val tr = ((tintArgb ushr 16) and 0xff).toFloat()
        val tg = ((tintArgb ushr 8) and 0xff).toFloat()
        val tb = (tintArgb and 0xff).toFloat()
        for (i in px.indices) {
            val v = px[i]
            val a8 = v ushr 24
            if (a8 == 0) continue
            val a = a8 / 255f
            val mask = (a / body).coerceAtMost(1f)
            val shine = if (a > body) ((a - body) / a).coerceIn(0f, 1f) else 0f
            val keep = (1f - shine)
            val w = keep * keep * mask
            val r0 = ((v ushr 16) and 0xff).toFloat()
            val g0 = ((v ushr 8) and 0xff).toFloat()
            val b0c = (v and 0xff).toFloat()
            val mix = (s * 0.85f * w).coerceIn(0f, 1f)
            val r1 = r0 + (tr - r0) * mix
            val g1 = g0 + (tg - g0) * mix
            val b1 = b0c + (tb - b0c) * mix
            val ta = (s * 0.24f * w).coerceIn(0f, 1f)
            val oa = a + ta * (1f - a)
            val k = ta * (1f - a)
            val r2 = (r1 * a + tr * k) / oa
            val g2 = (g1 * a + tg * k) / oa
            val b2 = (b1 * a + tb * k) / oa
            px[i] = ((oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
                (r2.toInt().coerceIn(0, 255) shl 16) or
                (g2.toInt().coerceIn(0, 255) shl 8) or
                b2.toInt().coerceIn(0, 255)
        }
    }
}
