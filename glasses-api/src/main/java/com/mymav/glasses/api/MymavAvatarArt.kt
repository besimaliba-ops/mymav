package com.mymav.glasses.api

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/**
 * MyMav mascot sprites — 3D-rendered by [MymavAvatar3D] (lit, glossy, opaque).
 * Same art drives the glasses HUD sprite and the phone Settings preview.
 */
object MymavAvatarArt {
    data class Encoded(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        /** When true, [bytes] are raw RGBA (not PNG/JPEG) for SDK keepAlpha upload. */
        val rawRgba: Boolean = false,
    )

    enum class Pose {
        Idle,
        Wave,
        RunLeft,
        RunRight,
        Jump,
        Blink,
        ArmWave,
        /** Eyelids half closed — the in-between frame of a natural blink. */
        BlinkHalf,
    }

    @Volatile
    private var appContext: android.content.Context? = null

    fun install(context: android.content.Context) {
        appContext = context.applicationContext
        clearProductionCaches()
        // Render the docked pose set in the background before the glasses connect.
        prewarm()
    }

    fun encodeFromPrefs(
        sizePx: Int = 96,
        pose: Pose = Pose.Idle,
        frame: Int = 0,
    ): Encoded {
        val ctx = appContext
        val preset = if (ctx != null) MymavAvatarPrefs.preset(ctx) else MymavAvatarPrefs.Preset.Pip
        val accent = if (ctx != null) MymavAvatarPrefs.accentArgb(ctx) else 0xFF2EE6A6.toInt()
        return encode(preset, accent, sizePx, pose, frame)
    }

    /** Rendered frames, keyed by preset/accent/size/pose/frame (3D render is ~30ms). */
    private val frameCache = object : LinkedHashMap<String, Encoded>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Encoded>?): Boolean = size > 96
    }

    /** Wrap the controller's free-running frame counter into each pose's loop. */
    private fun canonicalFrame(pose: Pose, frame: Int): Int = when (pose) {
        Pose.Idle -> frame.mod(MymavAvatar3D.IDLE_FRAMES)
        Pose.Blink, Pose.BlinkHalf -> 0
        Pose.Wave, Pose.ArmWave -> frame.mod(MymavAvatar3D.WAVE_FRAMES)
        Pose.RunLeft, Pose.RunRight -> frame.mod(8)
        Pose.Jump -> 0
    }

    fun encode(
        preset: MymavAvatarPrefs.Preset,
        accentArgb: Int,
        sizePx: Int = 96,
        pose: Pose = Pose.Idle,
        frame: Int = 0,
    ): Encoded {
        val f = canonicalFrame(pose, frame)
        val key = "proc|${preset.name}|$accentArgb|$sizePx|${pose.name}|$f"
        synchronized(frameCache) { frameCache[key]?.let { return it } }
        val argb = MymavAvatar3D.render(preset, accentArgb, sizePx, pose, f, rich = true)
        val rgba = ByteArray(sizePx * sizePx * 4)
        var o = 0
        for (c in argb) {
            rgba[o++] = ((c ushr 16) and 0xff).toByte()
            rgba[o++] = ((c ushr 8) and 0xff).toByte()
            rgba[o++] = (c and 0xff).toByte()
            rgba[o++] = ((c ushr 24) and 0xff).toByte()
        }
        val encoded = Encoded(rgba, sizePx, sizePx, rawRgba = true)
        synchronized(frameCache) { frameCache[key] = encoded }
        return encoded
    }

    /**
     * Docked-mascot pose set: a handful of small, separate 72px textures that all
     * stay resident on the glasses. Switching pose = park one image, place another
     * (no upload, no flicker). A single big sprite sheet overflowed the glasses'
     * command queue / texture memory and corrupted neighbouring textures (logos
     * showed mascot fragments), so the sheet is gone.
     *
     * Cells: 0 idle · 1 blink half · 2 blink closed · 3/4/5 wave low/mid/high · 6 jump (both arms up).
     * 7 textures x 72px RGBA = ~145 KB, uploaded once. Page runs slide the idle cell
     * (a separate run texture left a stuck second mascot behind).
     * Idle "breathing" is a 1–2px float done by moving the image, not by frames.
     */
    const val DOCK_CELL = 72
    const val CELL_IDLE = 0
    const val CELL_BLINK_HALF = 1
    const val CELL_BLINK = 2
    const val CELL_WAVE_LOW = 3
    const val CELL_WAVE_MID = 4
    const val CELL_WAVE_HIGH = 5
    const val CELL_RUN_RIGHT = CELL_IDLE
    const val CELL_RUN_LEFT = CELL_IDLE
    /** Both arms up — used for the whole jump into an app. */
    const val CELL_JUMP = 6
    const val DOCK_POSE_COUNT = 7

    private val posesLock = Any()
    @Volatile private var poses: List<Encoded>? = null
    @Volatile private var posesKey: String? = null

    /** Pose image index for a controller pose/frame. */
    fun dockCell(pose: Pose, frame: Int): Int = when (pose) {
        Pose.Idle -> CELL_IDLE
        Pose.Blink -> CELL_BLINK
        Pose.BlinkHalf -> CELL_BLINK_HALF
        Pose.Wave, Pose.ArmWave -> when (frame) {
            1 -> CELL_WAVE_LOW
            4 -> CELL_WAVE_HIGH
            else -> CELL_WAVE_MID
        }
        Pose.RunRight -> CELL_RUN_RIGHT
        Pose.RunLeft -> CELL_RUN_LEFT
        Pose.Jump -> CELL_JUMP
    }

    /** The pose images for the current preset + accent (rendered once, then cached). */
    fun dockPoses(): List<Encoded> {
        val ctx = appContext
        val preset = if (ctx != null) MymavAvatarPrefs.preset(ctx) else MymavAvatarPrefs.Preset.Pip
        val accent = if (ctx != null) MymavAvatarPrefs.accentArgb(ctx) else 0xFF2EE6A6.toInt()
        val key = "${preset.name}|$accent"
        poses?.let { if (posesKey == key) return it }
        synchronized(posesLock) {
            poses?.let { if (posesKey == key) return it }
            val built = listOf(
                Pose.Idle to 0,
                Pose.BlinkHalf to 0,
                Pose.Blink to 0,
                Pose.ArmWave to 1,
                Pose.ArmWave to 3,
                Pose.ArmWave to 4,
                Pose.Jump to 0,
            ).mapIndexed { cell, (pose, frame) ->
                // Production sprites (assets/avatar/<preset>/<cell>.png) win; the procedural renderer is
                // only the interim fallback until the artwork in docs/MASCOT_ASSET_SPEC.md is delivered.
                (loadSprite(preset, cell) ?: renderCompact(preset, accent, DOCK_CELL, pose, frame).also {
                    android.util.Log.w("MYMAVART", "FALLBACK procedural dock cell $cell for ${preset.name}")
                })
            }
            poses = built
            posesKey = key
            return built
        }
    }

    /** Render the pose set off the UI thread (call at startup / after a preset change). */
    fun prewarm(@Suppress("UNUSED_PARAMETER") sizePx: Int = DOCK_CELL) {
        Thread({ runCatching { dockPoses() } }, "mymav-avatar-poses").apply { isDaemon = true }.start()
    }


    /** File stem for each pose cell, in cell order. */
    private val CELL_NAMES = listOf("idle", "blink_half", "blink", "wave_low", "wave_mid", "wave_high", "jump")

    private val prodLock = Any()
    private val prodSprites = HashMap<String, Encoded>()
    private val prodAvail = HashMap<MymavAvatarPrefs.Preset, Boolean>()
    private val thumbs = HashMap<MymavAvatarPrefs.Preset, Encoded?>()

    /** Test-only: log every artwork decision (DBGART). Cache misses are always logged (MYMAVART). */
    @Volatile var traceArt = false
    private fun artLog(msg: String) { if (traceArt) android.util.Log.i("DBGART", msg) }

    private fun clearProductionCaches() {
        synchronized(prodLock) { prodSprites.clear(); prodAvail.clear(); thumbs.clear() }
    }

    /** True when every cell of [preset] ships as a production sprite (otherwise the fallback is in use). */
    fun hasProductionSprites(preset: MymavAvatarPrefs.Preset): Boolean {
        val ctx = appContext ?: return false
        synchronized(prodLock) { prodAvail[preset]?.let { return it } }
        val ok = CELL_NAMES.all { name ->
            runCatching { ctx.assets.open("avatar/${preset.name.lowercase()}/$name.png").close() }.isSuccess
        }
        synchronized(prodLock) { prodAvail[preset] = ok }
        android.util.Log.i("MYMAVART", "production sprites for ${preset.name}: $ok")
        return ok
    }

    /**
     * Decode one production sprite, area-downscale to [size] and return straight RGBA (cached per preset/cell/size).
     * Only sprites actually requested are ever decoded.
     */
    fun productionSprite(preset: MymavAvatarPrefs.Preset, cell: Int, size: Int): Encoded? {
        val ctx = appContext ?: return null
        if (!hasProductionSprites(preset)) return null
        val key = "prod|${preset.name}|$cell|$size"
        synchronized(prodLock) { prodSprites[key]?.let { artLog("hit $key"); return it } }
        val path = "avatar/${preset.name.lowercase()}/${CELL_NAMES[cell]}.png"
        val enc = runCatching {
            val bmp = ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
            val scaled = if (bmp.width == size && bmp.height == size) bmp
            else Bitmap.createScaledBitmap(bmp, size, size, true).also { bmp.recycle() }
            val px = IntArray(size * size)
            scaled.getPixels(px, 0, size, 0, 0, size, size)
            scaled.recycle()
            val rgba = ByteArray(size * size * 4)
            for (i in px.indices) {
                val argb = px[i]
                val a = argb ushr 24
                if (a == 0) continue
                val o = i * 4
                rgba[o] = ((argb ushr 16) and 0xff).toByte()
                rgba[o + 1] = ((argb ushr 8) and 0xff).toByte()
                rgba[o + 2] = (argb and 0xff).toByte()
                rgba[o + 3] = a.toByte()
            }
            Encoded(rgba, size, size, rawRgba = true)
        }.getOrNull() ?: return null
        synchronized(prodLock) { prodSprites[key] = enc }
        android.util.Log.i("MYMAVART", "decoded $path -> ${size}px key=$key")
        return enc
    }

    private fun loadSprite(preset: MymavAvatarPrefs.Preset, cell: Int): Encoded? =
        productionSprite(preset, cell, DOCK_CELL)

    /** Selected character, or Pip when the app context is not installed yet. */
    private fun currentPreset(): MymavAvatarPrefs.Preset {
        val ctx = appContext
        return if (ctx != null) MymavAvatarPrefs.preset(ctx) else MymavAvatarPrefs.Preset.Pip
    }

    /**
     * Cache key for the summoned/answer mascot image. Production artwork is keyed by CELL (at most 7 distinct images per
     * size), never by the controller's free-running frame, so animation ticks cannot cause new uploads.
     */
    fun summonKey(sizePx: Int, pose: Pose, frame: Int): String {
        val preset = currentPreset()
        return if (hasProductionSprites(preset)) "prod|${preset.name}|${dockCell(pose, frame)}|$sizePx"
        else "proc|${preset.name}|${pose.name}|${canonicalFrame(pose, frame)}|$sizePx"
    }

    /** Mascot image for the Ask / answer / listening states: production sprite of the selected character. */
    fun summonImage(sizePx: Int, pose: Pose, frame: Int): Encoded {
        val preset = currentPreset()
        val cell = dockCell(pose, frame)
        productionSprite(preset, cell, sizePx)?.let {
            artLog("summon ${preset.name} pose=${pose.name} frame=$frame -> production avatar/${preset.name.lowercase()}/${CELL_NAMES[cell]}.png size=$sizePx")
            return it
        }
        android.util.Log.w("MYMAVART", "FALLBACK procedural for ${preset.name} pose=${pose.name} size=$sizePx (no production sprites)")
        return encodeFromPrefs(sizePx = sizePx, pose = pose, frame = frame)
    }

    /** Phone picker thumbnail (assets/avatar/<preset>/thumb.png, PNG bytes) or null when not shipped. */
    private fun launcherThumb(preset: MymavAvatarPrefs.Preset): Encoded? {
        val ctx = appContext ?: return null
        synchronized(prodLock) { if (thumbs.containsKey(preset)) return thumbs[preset] }
        val path = "avatar/${preset.name.lowercase()}/thumb.png"
        val enc = runCatching {
            val bytes = ctx.assets.open(path).use { it.readBytes() }
            Encoded(bytes, 192, 192)
        }.getOrNull()
        synchronized(prodLock) { thumbs[preset] = enc }
        android.util.Log.i("MYMAVART", "picker thumb $path -> ${if (enc != null) "production" else "FALLBACK procedural"}")
        return enc
    }

    /** Raw RGBA, full 8-bit colour, transparent pixels zeroed. */
    private fun renderCompact(
        preset: MymavAvatarPrefs.Preset,
        accent: Int,
        size: Int,
        pose: Pose,
        frame: Int,
    ): Encoded {
        val px = MymavAvatar3D.render(preset, accent, size, pose, frame, rich = true)
        val rgba = ByteArray(size * size * 4)
        for (i in px.indices) {
            val argb = px[i]
            val a = argb ushr 24
            if (a == 0) continue
            val o = i * 4
            rgba[o] = ((argb ushr 16) and 0xff).toByte()
            rgba[o + 1] = ((argb ushr 8) and 0xff).toByte()
            rgba[o + 2] = (argb and 0xff).toByte()
            rgba[o + 3] = a.toByte()
        }
        return Encoded(rgba, size, size, rawRgba = true)
    }

    fun encodeLauncher(
        preset: MymavAvatarPrefs.Preset,
        accentArgb: Int,
        sizePx: Int,
    ): Encoded {
        launcherThumb(preset)?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val sprite = MymavAvatar3D.render(preset, accentArgb, sizePx, Pose.Idle, 0)
        val spriteBmp = Bitmap.createBitmap(sprite, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(spriteBmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        spriteBmp.recycle()
        val stream = ByteArrayOutputStream(sizePx * sizePx)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bmp.recycle()
        return Encoded(stream.toByteArray(), sizePx, sizePx)
    }
}
