package com.mymav.glasses.mav2.hud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.everysight.mav2.sdk.uikit.data.M2GradientFill
import com.everysight.mav2.sdk.uikit.data.M2GradientLength
import com.everysight.mav2.sdk.uikit.data.M2GradientLinear
import com.everysight.mav2.sdk.uikit.data.M2RoundedCorners
import com.everysight.mav2.sdk.uikit.drawables.M2Image
import com.everysight.mav2.sdk.uikit.drawables.M2Rect
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.utils.M2Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Corner reflections for the glass panels.
 *
 * Each reflection is a small procedural tile (one per panel corner size, generated once and cached) that follows
 * the panel's rounded corner and adjoining edges: brightest on the edge, fading smoothly inward and along the edge
 * to zero alpha at the tile boundary, so there is no visible texture edge. The top-left reflection is larger and
 * stronger than the bottom-right one. No large textures are used; the translucent body and the fine edge stay
 * code-drawn so centre transparency is tuned separately from the reflections.
 *
 * Layering contract: body fill -> top glow -> these corner shines -> live icons/text/controls -> focus outlines.
 */
internal object GlassSurface {
    /** Translucency of the smoked-blue body (alpha): panels let more scenery through than the small pills. */
    const val BODY_ALPHA_PANEL = 0.60f
    const val BODY_ALPHA_PILL = 0.74f

    private const val PEAK_TL = 0.55f
    private const val PEAK_BR = 0.28f
    private const val BR_SCALE = 0.58f
    private const val LINE_TL = 0.42f
    private const val LINE_BR = 0.16f
    /** Controls up to this many pixels are generated at full resolution so edges stay crisp (one texture per control). */
    private const val FINE_PIXELS = 60000

    private val cache = HashMap<String, ByteArray>()

    fun available(): Boolean = true

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Glow alpha (0..peak) for a pixel centre (u, v) measured from the corner apex of a tw x th corner region. */
    private fun glow(u: Float, v: Float, r: Float, tw: Float, th: Float, peak: Float, depth: Float = Float.NaN, line: Float = 0f): Float {
        if (u > tw || v > th) return 0f
        val rr = r.coerceAtMost(min(tw, th) - 2f).coerceAtLeast(2f)
        val sigma = max(4f, min(tw, th) * 0.30f)
        val len = max(tw, th) - rr
        val diag = hypot(tw, th)
        val e: Float
        val mask: Float
        if (u < rr && v < rr) {
            e = rr - hypot(rr - u, rr - v)
            mask = (e + 0.5f).coerceIn(0f, 1f)
        } else {
            e = min(u, v)
            mask = 1f
        }
        val a = hypot(max(u - rr, 0f), max(v - rr, 0f))
        val along = (1f - smooth(a / len)).pow(1.5f)
        val edge = exp(-(e / sigma).pow(2))
        val diffuse = exp(-(hypot(u, v) / (diag * 0.55f)).pow(2)) * (1f - smooth(hypot(u, v) / (diag * 0.95f)))
        // Thin contour line: ~1 px wide, hugging the true rounded silhouette (depth = distance inside it), fading along it.
        val contour = if (line > 0f && !depth.isNaN()) line * along * exp(-((depth - 0.9f) / 0.7f).pow(2)) else 0f
        return (peak * along * (0.62f * edge + 0.38f * diffuse) * mask + contour).coerceIn(0f, 1f)
    }

    /** Signed distance (negative inside) from the point (x, y) to the rounded rectangle 0..w x 0..h with corner radius r. */
    private fun roundedSd(x: Float, y: Float, w: Float, h: Float, r: Float): Float {
        val qx = abs(x - w / 2f) - (w / 2f - r)
        val qy = abs(y - h / 2f) - (h / 2f - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /**
     * One small texture per panel holding both reflections (top-left large/strong, bottom-right small/weak),
     * generated at 1/q resolution (the glow is smooth) and drawn scaled up. One upload per panel keeps the
     * texture load low; the result is cached per panel size and radius.
     */
    private fun panelTexture(w: Int, h: Int, radius: Float, q: Int, tlW: Int, tlH: Int, brW: Int, brH: Int): ByteArray {
        val key = "p$w x $h r${radius.toInt()} q$q tl$tlW,$tlH br$brW,$brH"
        synchronized(cache) { cache[key]?.let { return it } }
        HudPerf.count("shine.gen")
        val tw = (w + q - 1) / q
        val th = (h + q - 1) / q
        val r = radius / q
        val rr0 = radius.coerceAtMost(min(w, h) / 2f)
        val fine = q == 1
        val bytes = ByteArray(tw * th * 4)
        for (py in 0 until th) {
            for (px in 0 until tw) {
                // Depth inside the real rounded silhouette (full-res px) -> one shared mask for every layer of the shine.
                val depth = -roundedSd((px + 0.5f) * q, (py + 0.5f) * q, w.toFloat(), h.toFloat(), rr0)
                val cover = ((depth - 0.75f * q) / q + 0.5f).coerceIn(0f, 1f)
                var alpha = 0f
                if (cover > 0f) {
                    val a1 = glow(px + 0.5f, py + 0.5f, r, tlW.toFloat() / q, tlH.toFloat() / q, PEAK_TL, if (fine) depth else Float.NaN, LINE_TL)
                    val a2 = glow(tw - 1 - px + 0.5f, th - 1 - py + 0.5f, r, brW.toFloat() / q, brH.toFloat() / q, PEAK_BR, if (fine) depth else Float.NaN, LINE_BR)
                    alpha = (a1 + a2 - a1 * a2) * cover
                }
                val o = (py * tw + px) * 4
                bytes[o] = 214.toByte()
                bytes[o + 1] = 230.toByte()
                bytes[o + 2] = 255.toByte()
                bytes[o + 3] = (alpha * 255f + 0.5f).toInt().toByte()
            }
        }
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    /** Both corner reflections for the rectangle as one image (second value always null). */
    fun shines(x: Float, y: Float, w: Float, h: Float, radius: Float): Pair<M2Image?, M2Image?> {
        val pill = radius >= h / 2f - 0.5f && h >= 24f
        val tlW: Int
        val tlH: Int
        if (pill) {
            tlH = Math.round(h)
            tlW = min(w * 0.5f, h * 2.4f).toInt().coerceAtLeast(tlH)
        } else {
            val big = w >= 400f && h >= 300f
            val d = (min(w, h) * 0.60f).coerceIn(28f, if (big) 110f else 100f).toInt()
            tlW = d
            tlH = d
        }
        val brW = max((tlW * BR_SCALE).toInt(), (radius + 6f).toInt())
        val brH = if (pill) tlH else max((tlH * BR_SCALE).toInt(), (radius + 6f).toInt())
        val iw = Math.round(w)
        val ih = Math.round(h)
        val q = if (iw * ih <= FINE_PIXELS) 1 else max(1, (max(iw, ih) + 149) / 150)
        val tw = (iw + q - 1) / q
        val th = (ih + q - 1) / q
        val bytes = panelTexture(iw, ih, radius, q, tlW, tlH, brW, brH)
        return createHudImage(bytes, tw, th, x, y, w, h, roundCorners = false)?.apply { softCorners(radius.coerceAtMost(min(w, h) / 2f)) } to null
    }


    // ---- packaged neutral-glass surfaces (assets/glass/surface_<w>x<h>_r<r>.png) ----------------------------------

    private class Surface(val bytes: ByteArray, val w: Int, val h: Int)

    /** Decoded surfaces for the colours in use; bounded (LRU) so tint variants can never accumulate. */
    private const val MAX_DECODED = 48
    private val surfaces = object : LinkedHashMap<String, Surface?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Surface?>?): Boolean = size > MAX_DECODED
    }
    private var assetNames: Set<String>? = null
    private var loadedDir: String? = null

    private fun styleDir(): String = com.mymav.glasses.api.MymavHudPrefs.panelStyle().dir

    /** Only the selected style is ever decoded or kept: switching styles drops the other style's decoded buffers. */
    private fun scopeTo(dir: String) {
        if (loadedDir != dir) {
            synchronized(surfaces) { surfaces.clear() }
            assetNames = null
            loadedDir = dir
        }
    }

    private fun names(): Set<String> {
        val dir = styleDir()
        scopeTo(dir)
        assetNames?.let { return it }
        val ctx = BrandHudIcons.contextOrNull() ?: return emptySet()
        val found = runCatching { ctx.assets.list("glass/$dir")?.toSet() }.getOrNull() ?: emptySet()
        // Do not cache an empty result: the context may simply not be installed yet.
        if (found.isNotEmpty()) assetNames = found
        return found
    }

    private fun fileFor(w: Int, h: Int, r: Int) = "surface_${w}x${h}_r$r.png"

    /**
     * Packaged file for a destination: the exact size, else (dynamic widths only) the closest artwork of the same
     * height and radius within [NEAR_PX] px, so a control is never drawn from a substantially different artwork.
     */
    private fun resolve(w: Float, h: Float, radius: Float): String? {
        val iw = Math.round(w)
        val ih = Math.round(h)
        val ir = Math.round(radius)
        val exact = fileFor(iw, ih, ir)
        val all = names()
        if (exact in all) return exact
        val re = Regex("surface_(\\d+)x${ih}_r$ir\\.png")
        val best = all.mapNotNull { n -> re.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()?.let { n to it } }
            .filter { Math.abs(it.second - w) <= NEAR_PX }
            .minByOrNull { Math.abs(it.second - w) }
        if (best == null && all.isNotEmpty() && reported.add("${styleDir()}/$exact")) {
            android.util.Log.w("GlassSurface", "no packaged surface for ${iw}x${ih} r$ir (${styleDir()}): procedural fallback")
        }
        return best?.first
    }

    private const val NEAR_PX = 6f
    private val reported = HashSet<String>()

    /** True when a packaged surface exists for this destination (exact or near-width, see [resolve]). */
    fun hasSurface(w: Float, h: Float, radius: Float): Boolean = resolve(w, h, radius) != null

    /**
     * Smallest packaged width >= [w] for a pill of height [h]/radius [r] (so dynamic widths land on a real
     * artwork size); [w] unchanged when none exists.
     */
    fun snapWidth(w: Float, h: Int, r: Int): Float {
        val re = Regex("surface_(\\d+)x${h}_r$r\\.png")
        val widths = names().mapNotNull { re.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.sorted()
        // Exact or within 2 px only: never stretch a control to a substantially different artwork width.
        val snapped = widths.firstOrNull { it >= Math.round(w) && it - w <= 2f }
        return (snapped ?: Math.round(w)).toFloat()
    }

    /** User panel-opacity multiplier (phone Settings → Glasses panels); 1.0 = the artwork as packaged. */
    fun opacityFactor(): Float = com.mymav.glasses.api.MymavHudPrefs.glassOpacityPercent() / 100f

    // ---- panel colour scope -----------------------------------------------------------------------------------------

    /** Tint scope of the surfaces being built right now ("widget:<id>" / "app:<id>"), set by [withScope]. */
    @Volatile private var currentScope: String? = null

    /** Run [block] (a screen/tile build) with surfaces coloured for [scope]; nests and always restores. */
    inline fun <T> withScope(scope: String?, block: () -> T): T {
        val prev = enterScope(scope)
        try {
            return block()
        } finally {
            enterScope(prev)
        }
    }

    @PublishedApi internal fun enterScope(scope: String?): String? {
        val prev = currentScope
        currentScope = scope
        return prev
    }

    private fun tintKeyNow(): String = com.mymav.glasses.api.MymavHudPrefs.effectiveTintKey(currentScope)

    private fun load(file: String): Surface? = synchronized(surfaces) {
        val factor = opacityFactor()
        val dir = styleDir()
        scopeTo(dir)
        val tintKey = tintKeyNow()
        val tintArgb = com.mymav.glasses.api.PanelTint.argbOf(tintKey)
        val strength = com.mymav.glasses.api.MymavHudPrefs.tintStrength()
        val cacheKey = "$dir/$file@${Math.round(factor * 100)}" + if (tintArgb == null) "" else "@$tintKey@$strength"
        if (surfaces.containsKey(cacheKey)) return@synchronized surfaces[cacheKey]
        val built: Surface? = HudPerf.timed("surf.decode") {
            val ctx = BrandHudIcons.contextOrNull() ?: return@timed null
            runCatching {
                val bmp = ctx.assets.open("glass/$dir/$file").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inPremultiplied = false
                    })
                } ?: return@runCatching null
                val bw = bmp.width
                val bh = bmp.height
                val px = IntArray(bw * bh)
                bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                bmp.recycle()
                if (tintArgb != null) com.mymav.glasses.api.PanelTint.apply(px, tintArgb, strength)
                val out = ByteArray(bw * bh * 4)
                var o = 0
                for (v in px) {
                    out[o++] = ((v ushr 16) and 0xff).toByte()
                    out[o++] = ((v ushr 8) and 0xff).toByte()
                    out[o++] = (v and 0xff).toByte()
                    out[o++] = (((v ushr 24) and 0xff) * factor).toInt().coerceIn(0, 255).toByte()
                }
                Surface(out, bw, bh)
            }.getOrNull()
        }
        // A missing context / failed decode is not cached, so the next build retries.
        if (built != null) surfaces[cacheKey] = built
        built
    }

    /** The packaged surface drawn at exactly (x, y, w, h); its (reduced) texture is scaled to the destination size. */
    fun surface(x: Float, y: Float, w: Float, h: Float, radius: Float): M2Image? {
        val sf = load(resolve(w, h, radius) ?: return null) ?: return null
        return createHudImage(sf.bytes, sf.w, sf.h, x, y, w, h, roundCorners = false)?.apply { softCorners(radius.coerceAtMost(min(w, h) / 2f)) }
    }

    // ---- fine corner rim (code-drawn, follows the rounded contour, fades to zero) ---------------------------------

    /**
     * Fine reflective line on the panel contour: a ~1 px stroke on exactly the panel rectangle/radius (same
     * geometry as the code-drawn border), rounded on ONE corner only, drawn as a short corner-sized outline whose
     * stroke colour fades linearly to alpha 0 along the adjoining edges. Strong top-left, weaker bottom-right.
     * Smaller controls get a shorter, softer rim so it never reads as a ring. Accent borders get a weaker one.
     * No textures; two tiny drawables per panel.
     */
    fun cornerRims(x: Float, y: Float, w: Float, h: Float, radius: Float, accent: Boolean): Pair<M2Rect, M2Rect>? {
        if (w < 40f || h < 24f) return null
        val pill = radius >= h / 2f - 0.5f
        // Pill-end rims read as faint circles (HOME, ADD/DUE/DONE/BACK). Keep the
        // packaged/procedural edge shine; do not add a second rounded-square stroke.
        if (pill) return null
        val small = h < 64f
        val r = radius.coerceAtMost(min(w, h) / 2f).coerceAtLeast(1f)
        val ext = if (small) 7f else (min(w, h) * 0.12f).coerceIn(12f, 30f)
        val l = min(r + ext, min(w, h))
        val k = if (accent) 0.55f else 1f
        val tlPeak = (if (small) 0.30f else 0.42f) * k
        val brPeak = (if (small) 0.12f else 0.20f) * k
        val lBr = if (small) min(r + ext * 0.7f, min(w, h)) else min(r + ext * 0.75f, min(w, h))
        fun rim(rx: Float, ry: Float, size: Float, corner: M2RoundedCorners, peak: Float, angle: Float) =
            M2RectOutline(0xFFEAF3FFu.withAlpha(peak)).apply {
                setDimensions(rx, ry, size, size)
                setCornerRadius(r, corner)
                setStyle(1f)
                setFill(M2GradientLinear(M2GradientFill.Stop.Start, M2GradientLength.fraction(0.5f), angle, 0xFFEAF3FFu.withAlpha(0f)))
            }
        return rim(x, y, l, M2RoundedCorners.TopLeft, tlPeak, 45f) to
            rim(x + w - lBr, y + h - lBr, lBr, M2RoundedCorners.BottomRight, brPeak, 225f)
    }

    /** Small white house outline for the compact HOME pill (straight RGBA, cached). */
    fun houseIcon(size: Int): ByteArray {
        val key = "house$size"
        synchronized(cache) { cache[key]?.let { return it } }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
            strokeWidth = s * 0.12f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val roof = Path().apply { moveTo(s * 0.08f, s * 0.50f); lineTo(s * 0.5f, s * 0.12f); lineTo(s * 0.92f, s * 0.50f) }
        val body = Path().apply {
            moveTo(s * 0.20f, s * 0.44f); lineTo(s * 0.20f, s * 0.88f); lineTo(s * 0.80f, s * 0.88f); lineTo(s * 0.80f, s * 0.44f)
        }
        val door = Path().apply { moveTo(s * 0.44f, s * 0.88f); lineTo(s * 0.44f, s * 0.62f); lineTo(s * 0.56f, s * 0.62f); lineTo(s * 0.56f, s * 0.88f) }
        c.drawPath(roof, paint); c.drawPath(body, paint); c.drawPath(door, paint)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        val bytes = ByteArray(size * size * 4)
        var o = 0
        for (v in px) {
            bytes[o++] = ((v ushr 16) and 0xff).toByte()
            bytes[o++] = ((v ushr 8) and 0xff).toByte()
            bytes[o++] = (v and 0xff).toByte()
            bytes[o++] = ((v ushr 24) and 0xff).toByte()
        }
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }
}
