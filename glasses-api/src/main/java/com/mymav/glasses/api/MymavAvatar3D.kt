package com.mymav.glasses.api

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software-rendered 3D MyMav mascot.
 *
 * Every body part is a real 3D primitive (ellipsoids and sphere-swept limbs)
 * rasterised with a depth buffer at 4× supersampling, lit with a warm key light,
 * a cool fill, a rim light and Blinn-Phong highlights, then box-filtered down for
 * clean anti-aliased edges. Interior pixels are fully opaque.
 *
 * Poses are continuous ([frame] drives breathing / waving / running phase), so
 * the HUD can play a smooth loop by swapping pre-rendered frames.
 */
internal object MymavAvatar3D {
    /** Supersampling factor: 4× for small HUD sprites, less for big phone previews. */
    private fun ssFor(sizePx: Int, rich: Boolean): Int = when {
        // Glasses sprites are tiny and shown 1:1, so spend more samples on smooth edges.
        rich && sizePx >= 100 -> 5
        rich -> 6
        sizePx >= 140 -> 2
        sizePx >= 100 -> 3
        else -> 4
    }
    /** Character fills more of the sprite (world [-1,1] scaled up). */
    private const val ZOOM = 1.2f

    /** Frames in one idle breathing loop (the controller cycles 0 until IDLE_FRAMES). */
    const val IDLE_FRAMES = 10
    /** Frames in one wave gesture. */
    const val WAVE_FRAMES = 12

    private class Mat(
        val r: Float,
        val g: Float,
        val b: Float,
        val spec: Float,
        val shine: Float,
        val emissive: Float = 0f,
        val eye: Boolean = false,
        val lidR: Float = 0f,
        val lidG: Float = 0f,
        val lidB: Float = 0f,
        val blink: Float = 0f,
    )

    private class Prim(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val rx: Float,
        val ry: Float,
        val rz: Float,
        val mat: Mat,
    )

    private class Scene {
        val prims = ArrayList<Prim>(96)
        fun ell(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, m: Mat) {
            prims += Prim(cx, cy, cz, rx, ry, rz, m)
        }
        fun ball(cx: Float, cy: Float, cz: Float, r: Float, m: Mat) = ell(cx, cy, cz, r, r, r, m)

        /** Tapered capsule from a to b as a dense chain of spheres. */
        fun limb(
            ax: Float, ay: Float, az: Float, ar: Float,
            bx: Float, by: Float, bz: Float, br: Float,
            m: Mat,
        ) {
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
            val steps = max(2, (len / (min(ar, br) * 0.45f)).toInt())
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                ball(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, ar + (br - ar) * t, m)
            }
        }
    }

    // ---- colour helpers (linear light) -------------------------------------------------

    private fun lin(c: Int): Float = (c / 255f).pow(2.2f)
    private fun mat(argb: Int, spec: Float, shine: Float, emissive: Float = 0f, sat: Float = 1f): Mat {
        var r = lin(Color.red(argb)); var g = lin(Color.green(argb)); var b = lin(Color.blue(argb))
        if (sat != 1f) {
            // Push chroma away from grey (glasses additive display washes colour towards white).
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = max(0f, l + (r - l) * sat); g = max(0f, l + (g - l) * sat); b = max(0f, l + (b - l) * sat)
        }
        return Mat(r, g, b, spec, shine, emissive)
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    // ---- lights --------------------------------------------------------------------------

    private val key = norm(-0.55f, -0.70f, 0.85f)
    private val fill = norm(0.75f, -0.05f, 0.55f)
    private val halfKey = norm(key[0], key[1], key[2] + 1f)
    private val halfFill = norm(fill[0], fill[1], fill[2] + 1f)

    private fun norm(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }

    /**
     * Render one frame as ARGB pixels ([sizePx]²). Transparent outside the
     * character, fully opaque inside.
     */
    fun render(
        preset: MymavAvatarPrefs.Preset,
        accentArgb: Int,
        sizePx: Int,
        pose: MymavAvatarArt.Pose,
        frame: Int,
        /** Richer glasses look (more saturated, glossier, smoother edges). Phone previews keep the original. */
        rich: Boolean = false,
    ): IntArray {
        val SS = ssFor(sizePx, rich)
        val n = sizePx * SS
        val scene = buildScene(preset, accentArgb or 0xFF000000.toInt(), pose, frame, rich)

        // World space: x,y in [-1,1] across the sprite (y down), z toward viewer.
        val toPx = n / 2f
        val zbuf = FloatArray(n * n) { -1e9f }
        val hit = IntArray(n * n) { -1 }
        scene.prims.forEachIndexed { index, p ->
            val x0 = max(0, (((p.cx - p.rx) * ZOOM + 1f) * toPx).toInt())
            val x1 = min(n - 1, (((p.cx + p.rx) * ZOOM + 1f) * toPx).toInt() + 1)
            val y0 = max(0, (((p.cy - p.ry) * ZOOM + 1f) * toPx).toInt())
            val y1 = min(n - 1, (((p.cy + p.ry) * ZOOM + 1f) * toPx).toInt() + 1)
            for (py in y0..y1) {
                val wy = ((py + 0.5f) / toPx - 1f) / ZOOM
                val dy = (wy - p.cy) / p.ry
                val dy2 = dy * dy
                if (dy2 >= 1f) continue
                val row = py * n
                for (px in x0..x1) {
                    val wx = ((px + 0.5f) / toPx - 1f) / ZOOM
                    val dx = (wx - p.cx) / p.rx
                    val d2 = dx * dx + dy2
                    if (d2 >= 1f) continue
                    val z = p.cz + p.rz * sqrt(1f - d2)
                    val i = row + px
                    if (z > zbuf[i]) {
                        zbuf[i] = z
                        hit[i] = index
                    }
                }
            }
        }

        // Shade every covered sample.
        val sr = FloatArray(n * n)
        val sg = FloatArray(n * n)
        val sb = FloatArray(n * n)
        for (py in 0 until n) {
            val wy = ((py + 0.5f) / toPx - 1f) / ZOOM
            for (px in 0 until n) {
                val i = py * n + px
                val h = hit[i]
                if (h < 0) continue
                val p = scene.prims[h]
                val wx = ((px + 0.5f) / toPx - 1f) / ZOOM
                val dx = (wx - p.cx) / p.rx
                val dy = (wy - p.cy) / p.ry
                val dz = sqrt(max(0f, 1f - dx * dx - dy * dy))
                var nx = dx / p.rx
                var ny = dy / p.ry
                var nz = dz / p.rz
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= nl; ny /= nl; nz /= nl
                shade(p, dx, dy, dz, nx, ny, nz, sr, sg, sb, i, rich)
            }
        }

        // Box-filter down to the target size (premultiplied), back to sRGB.
        val out = IntArray(sizePx * sizePx)
        val inv = 1f / (SS * SS)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (sy in 0 until SS) {
                    val row = (y * SS + sy) * n + x * SS
                    for (sx in 0 until SS) {
                        val i = row + sx
                        if (hit[i] >= 0) {
                            r += sr[i]; g += sg[i]; b += sb[i]; a += 1f
                        }
                    }
                }
                if (a <= 0f) continue
                val cov = a * inv
                val ri = (encode(r / a, rich) * 255f + 0.5f).toInt()
                val gi = (encode(g / a, rich) * 255f + 0.5f).toInt()
                val bi = (encode(b / a, rich) * 255f + 0.5f).toInt()
                // Fully opaque inside; anti-aliased only along the silhouette.
                val ai = (min(1f, cov * (if (rich) 1.0f else 1.08f)) * 255f + 0.5f).toInt()
                out[y * sizePx + x] = Color.argb(ai, ri, gi, bi)
            }
        }
        overlayFace(out, sizePx, preset, accentArgb, pose, frame)
        return out
    }

    /** Linear light -> sRGB. Rich look soft-clips highlights (keeps hue instead of blowing out to white). */
    private fun encode(v: Float, rich: Boolean): Float {
        val x = max(0f, v)
        val lit = if (rich) {
            val k = 1.6f
            (1f - exp(-k * x)) / (1f - exp(-k))
        } else x
        return min(1f, lit).pow(1f / 2.2f)
    }

    private fun shade(
        p: Prim,
        lx: Float, ly: Float, lz: Float,
        nx: Float, ny: Float, nz: Float,
        sr: FloatArray, sg: FloatArray, sb: FloatArray, i: Int,
        rich: Boolean,
    ) {
        val m = p.mat
        var ar = m.r; var ag = m.g; var ab = m.b
        var spec = m.spec
        var shine = m.shine
        if (m.eye) {
            // Eyelid (blink): upper part of the eyeball takes the body colour.
            val lidLine = -1f + 2.05f * m.blink
            if (m.blink > 0f && ly < lidLine) {
                ar = m.lidR; ag = m.lidG; ab = m.lidB
                spec = 0.25f; shine = 24f
            } else {
                // Iris / pupil around the forward gaze direction (slightly down-in).
                val gx = 0f; val gy = 0.08f; val gz = 0.996f
                val c = lx * gx + ly * gy + lz * gz
                when {
                    c > 0.93f -> { ar = 0.004f; ag = 0.005f; ab = 0.010f }        // pupil
                    c > 0.80f -> {                                               // iris
                        val t = (c - 0.80f) / 0.13f
                        ar = 0.02f + 0.02f * t; ag = 0.07f + 0.10f * t; ab = 0.20f + 0.25f * t
                    }
                    c > 0.76f -> { ar = 0.01f; ag = 0.02f; ab = 0.05f }          // limbal ring
                }
                spec = 1.2f; shine = 140f
            }
        }
        val ndk = nx * key[0] + ny * key[1] + nz * key[2]
        val ndf = nx * fill[0] + ny * fill[1] + nz * fill[2]
        // Wrapped diffuse = soft, toy-like form shading.
        val dk = max(0f, (ndk + 0.35f) / 1.35f)
        val df = max(0f, (ndf + 0.2f) / 1.2f)
        val sky = 0.5f - ny * 0.5f // hemisphere ambient, brighter from above
        // Raised ambient floor: on the additive glasses display, a dim/shadowed
        // pixel is literally invisible (you see straight through to the real
        // world there), so the mascot's shadow side was reading as "transparent"
        // even though it's fully opaque in the texture's alpha channel.
        val amb = if (rich) 0.24f + 0.12f * sky else 0.34f + 0.20f * sky
        val hk = max(0f, nx * halfKey[0] + ny * halfKey[1] + nz * halfKey[2]).pow(shine)
        val hf = max(0f, nx * halfFill[0] + ny * halfFill[1] + nz * halfFill[2]).pow(shine * 0.6f)
        val fres = (1f - max(0f, nz)).pow(3f)
        val rim = fres * (if (rich) 0.30f else 0.55f)
        // Broad soft sheen on top of the tight highlight reads as glossy plastic (not eyes).
        val sheen = if (rich && !m.eye) max(0f, nx * halfKey[0] + ny * halfKey[1] + nz * halfKey[2]).pow(9f) * 0.10f else 0f

        val lightR = amb + dk * 1.10f + df * 0.30f + m.emissive
        val lightG = amb + dk * 1.05f + df * 0.36f + m.emissive
        val lightB = amb + dk * 0.98f + df * 0.48f + m.emissive
        if (rich) {
            // Tinted rim: coloured edge glow instead of a white wash.
            val tr = rim * (0.25f + 0.45f * ar.pow(0.45f))
            val tg = rim * (0.35f + 0.45f * ag.pow(0.45f))
            val tb = rim * (0.55f + 0.45f * ab.pow(0.45f))
            sr[i] = ar * lightR + spec * (hk * 1.0f + hf * 0.25f) + sheen + tr
            sg[i] = ag * lightG + spec * (hk * 1.0f + hf * 0.28f) + sheen + tg
            sb[i] = ab * lightB + spec * (hk * 0.95f + hf * 0.32f) + sheen + tb
        } else {
            sr[i] = ar * lightR + spec * (hk * 1.0f + hf * 0.25f) + rim * 0.55f
            sg[i] = ag * lightG + spec * (hk * 1.0f + hf * 0.28f) + rim * 0.80f
            sb[i] = ab * lightB + spec * (hk * 0.95f + hf * 0.32f) + rim * 1.00f
        }
    }

    // ---- character construction ------------------------------------------------------------

    private fun buildScene(
        preset: MymavAvatarPrefs.Preset,
        accent: Int,
        pose: MymavAvatarArt.Pose,
        frame: Int,
        rich: Boolean,
    ): Scene {
        val s = Scene()
        val t = frame.toFloat()
        val loop = (2.0 * PI * (frame % IDLE_FRAMES) / IDLE_FRAMES).toFloat()
        val running = pose == MymavAvatarArt.Pose.RunLeft || pose == MymavAvatarArt.Pose.RunRight
        val waving = pose == MymavAvatarArt.Pose.Wave || pose == MymavAvatarArt.Pose.ArmWave
        val jumping = pose == MymavAvatarArt.Pose.Jump
        val dir = when (pose) {
            MymavAvatarArt.Pose.RunLeft -> -1f
            MymavAvatarArt.Pose.RunRight -> 1f
            else -> 0f
        }

        // Breathing bob + squash/stretch.
        val bob = when {
            jumping -> -0.07f
            running -> -abs(sin(t * 1.6f)) * 0.05f
            else -> sin(loop) * 0.025f
        }
        val stretch = when {
            jumping -> 1.08f
            running -> 1f + abs(sin(t * 1.6f)) * 0.03f
            else -> 1f + sin(loop) * 0.018f
        }
        val lean = dir * 0.08f

        val bodyColor = accent
        val sat = if (rich) 1.7f else 1f
        val body = if (rich) mat(bodyColor, spec = 0.85f, shine = 70f, sat = sat) else mat(bodyColor, spec = 0.55f, shine = 36f)
        val bodyDark = mat(mix(bodyColor, Color.BLACK, 0.45f), spec = 0.3f, shine = 20f, sat = sat)
        val white = mat(Color.rgb(246, 248, 252), spec = 0.6f, shine = 48f)
        val tip = mat(mix(bodyColor, Color.WHITE, 0.45f), spec = 0.9f, shine = 80f, emissive = 0.25f, sat = sat)

        val (bx, by, bz) = Triple(lean, 0.06f + bob, 0f)
        var bodyRx = 0.50f
        var bodyRy = 0.50f / stretch * 1.0f
        when (preset) {
            MymavAvatarPrefs.Preset.Bolt -> { bodyRx = 0.44f; bodyRy = 0.54f }
            MymavAvatarPrefs.Preset.Orbit -> { bodyRx = 0.46f; bodyRy = 0.46f }
            else -> Unit
        }
        bodyRy *= stretch
        bodyRx /= sqrt(stretch)

        // Feet (behind body, slightly forward).
        val stride = if (running) sin(t * 1.6f) * 0.12f else 0f
        val footY = by + bodyRy * 0.86f + if (jumping) -0.04f else 0f
        s.ell(bx - 0.20f + stride * dir, footY, 0.10f, 0.15f, 0.08f, 0.13f, bodyDark)
        s.ell(bx + 0.20f - stride * dir, footY, 0.10f, 0.15f, 0.08f, 0.13f, bodyDark)

        // Body.
        s.ell(bx, by, bz, bodyRx, bodyRy, 0.48f, body)

        // Eyes: glossy spheres set into the face.
        val blink = when (pose) {
            MymavAvatarArt.Pose.Blink -> 1f
            MymavAvatarArt.Pose.BlinkHalf -> 0.5f
            else -> 0f
        }
        val eyeMat = Mat(
            0.92f, 0.94f, 0.98f, spec = 1.2f, shine = 140f, eye = true,
            lidR = body.r, lidG = body.g, lidB = body.b, blink = blink,
        )
        val eyeY = by - bodyRy * 0.12f
        val eyeDx = bodyRx * 0.38f
        val eyeR = bodyRx * 0.30f
        s.ball(bx - eyeDx + lean * 0.4f, eyeY, bz + 0.33f, eyeR, eyeMat)
        s.ball(bx + eyeDx + lean * 0.4f, eyeY, bz + 0.33f, eyeR, eyeMat)

        // Arms: white sphere-swept limbs with round hands.
        val shoulderY = by + bodyRy * 0.18f
        val armR = 0.075f
        val handR = 0.105f
        // Left arm (viewer's left) hangs / swings.
        val lSwing = if (running) sin(t * 1.6f) * 0.35f else sin(loop) * 0.06f
        // Jump: both arms thrown up and out (mirror of the right arm), hands always in frame.
        val lAng = if (jumping) PI.toFloat() * 1.36f else (PI.toFloat() * 0.62f) + lSwing
        val lsx = bx - bodyRx * 0.82f
        val lLen = if (jumping) 0.28f else 0.22f
        val lhx = lsx + cos(lAng) * lLen
        val lhy = shoulderY + sin(lAng) * lLen
        s.limb(lsx, shoulderY, 0.12f, armR, lhx, lhy, 0.18f, armR * 0.9f, white)
        s.ball(lhx, lhy, 0.18f, handR, white)
        // Right arm waves when greeting.
        val rAng = when {
            waving -> -(PI.toFloat() * 0.36f) + sin(t * 1.05f) * 0.42f
            jumping -> -(PI.toFloat() * 0.36f)
            running -> (PI.toFloat() * 0.38f) - sin(t * 1.6f) * 0.35f
            else -> (PI.toFloat() * 0.38f) - sin(loop) * 0.06f
        }
        val rsx = bx + bodyRx * 0.82f
        val armLen = if (waving) 0.30f else if (jumping) 0.28f else 0.22f
        val rhx = rsx + cos(rAng) * armLen
        val rhy = shoulderY + sin(rAng) * armLen
        s.limb(rsx, shoulderY, 0.12f, armR, rhx, rhy, 0.18f, armR * 0.9f, white)
        s.ball(rhx, rhy, 0.18f, handR, white)

        // Preset crowns.
        val top = by - bodyRy
        when (preset) {
            MymavAvatarPrefs.Preset.Pip -> {
                val sway = if (waving) sin(t) * 0.05f else sin(loop) * 0.02f
                s.limb(bx, top + 0.04f, 0.02f, 0.03f, bx + sway, top - 0.15f, 0.04f, 0.022f, body)
                s.ball(bx + sway, top - 0.19f, 0.05f, 0.072f, tip)
            }
            MymavAvatarPrefs.Preset.Bolt -> {
                // Zig-zag lightning antenna.
                val x0 = bx + 0.02f
                s.limb(x0, top + 0.04f, 0.02f, 0.035f, x0 + 0.10f, top - 0.12f, 0.03f, 0.03f, tip)
                s.limb(x0 + 0.10f, top - 0.12f, 0.03f, 0.03f, x0 - 0.04f, top - 0.18f, 0.03f, 0.03f, tip)
                s.limb(x0 - 0.04f, top - 0.18f, 0.03f, 0.03f, x0 + 0.08f, top - 0.28f, 0.03f, 0.02f, tip)
            }
            MymavAvatarPrefs.Preset.Nova -> {
                // Floating star sparks around the head.
                for (k in 0 until 4) {
                    val a = (PI.toFloat() * (0.15f + 0.233f * k)) + sin(loop) * 0.05f
                    val r = bodyRx * 1.12f
                    s.ball(bx - cos(a) * r, by - sin(a) * r * 1.02f, 0.05f, 0.055f + 0.012f * (k % 2), tip)
                }
            }
            MymavAvatarPrefs.Preset.Orbit -> {
                // Tilted ring as a chain of beads — passes behind and in front of the body.
                val ringR = bodyRx * 1.42f
                val tilt = 0.22f
                val spin = (frame % IDLE_FRAMES) * (2f * PI.toFloat() / IDLE_FRAMES) // one turn per loop (seamless)
                val beads = 140
                for (k in 0 until beads) {
                    val a = 2f * PI.toFloat() * k / beads + spin
                    s.ball(bx + cos(a) * ringR, by + 0.04f + sin(a) * ringR * tilt, sin(a) * ringR * 0.9f, 0.036f, white)
                }
                val ma = spin * 4f
                s.ball(bx + cos(ma) * ringR, by + 0.04f + sin(ma) * ringR * tilt, sin(ma) * ringR * 0.9f + 0.02f, 0.07f, tip)
            }
        }
        return s
    }

    /** Mouth + cheeks: painted on the face after shading (crisp, anti-aliased). */
    private fun overlayFace(
        argb: IntArray,
        sizePx: Int,
        preset: MymavAvatarPrefs.Preset,
        accentArgb: Int,
        pose: MymavAvatarArt.Pose,
        frame: Int,
    ) {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, sizePx, 0, 0, sizePx, sizePx)
        val c = Canvas(bmp)
        val half = sizePx / 2f
        val loop = (2.0 * PI * (frame % IDLE_FRAMES) / IDLE_FRAMES).toFloat()
        val running = pose == MymavAvatarArt.Pose.RunLeft || pose == MymavAvatarArt.Pose.RunRight
        val jumping = pose == MymavAvatarArt.Pose.Jump
        val bob = when {
            jumping -> -0.07f
            running -> -abs(sin(frame * 1.6f)) * 0.05f
            else -> sin(loop) * 0.025f
        }
        val lean = when (pose) {
            MymavAvatarArt.Pose.RunLeft -> -0.08f
            MymavAvatarArt.Pose.RunRight -> 0.08f
            else -> 0f
        }
        val bodyRy = if (preset == MymavAvatarPrefs.Preset.Bolt) 0.54f else if (preset == MymavAvatarPrefs.Preset.Orbit) 0.46f else 0.5f
        val bodyRx = if (preset == MymavAvatarPrefs.Preset.Bolt) 0.44f else if (preset == MymavAvatarPrefs.Preset.Orbit) 0.46f else 0.5f
        val z = ZOOM
        val cx = (lean * z + 1f) * half
        val by = 0.06f + bob
        val mouthY = ((by + bodyRy * 0.24f) * z + 1f) * half
        val w = bodyRx * 0.28f * half * z

        // Cheeks: soft blush.
        val cheekY = ((by + bodyRy * 0.16f) * z + 1f) * half
        val cheekR = bodyRx * 0.16f * half * z
        for (side in listOf(-1f, 1f)) {
            val ccx = cx + side * bodyRx * 0.66f * half * z
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(ccx, cheekY, cheekR, Color.argb(120, 255, 110, 150), Color.argb(0, 255, 110, 150), Shader.TileMode.CLAMP)
            }
            c.drawCircle(ccx, cheekY, cheekR, p)
        }

        val open = pose == MymavAvatarArt.Pose.Wave || pose == MymavAvatarArt.Pose.ArmWave || jumping
        if (open) {
            // Happy open mouth with tongue.
            val r = RectF(cx - w, mouthY - w * 0.35f, cx + w, mouthY + w * 0.95f)
            c.drawArc(r, 0f, 180f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 14, 30) })
            val tongue = RectF(cx - w * 0.5f, mouthY + w * 0.25f, cx + w * 0.5f, mouthY + w * 0.95f)
            c.drawArc(tongue, 180f, 180f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 110, 130) })
        } else {
            val smile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(48, 16, 30)
                style = Paint.Style.STROKE
                strokeWidth = max(1.4f, sizePx * 0.030f)
                strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(RectF(cx - w, mouthY - w * 0.55f, cx + w, mouthY + w * 0.55f), 20f, 140f, false, smile)
        }
        if (pose == MymavAvatarArt.Pose.Blink) {
            // Closed-eye lash lines over the lids.
            val eyeY = ((by - bodyRy * 0.12f) * z + 1f) * half
            val lash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 12, 24)
                style = Paint.Style.STROKE
                strokeWidth = max(1.2f, sizePx * 0.026f)
                strokeCap = Paint.Cap.ROUND
            }
            val ew = bodyRx * 0.24f * half * z
            for (side in listOf(-1f, 1f)) {
                val ex = cx + side * bodyRx * 0.38f * half * z
                c.drawArc(RectF(ex - ew, eyeY - ew * 0.6f, ex + ew, eyeY + ew * 0.6f), 10f, 160f, false, lash)
            }
        }
        bmp.getPixels(argb, 0, sizePx, 0, 0, sizePx, sizePx)
        bmp.recycle()
    }
}
