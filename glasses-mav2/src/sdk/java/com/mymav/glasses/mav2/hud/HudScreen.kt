package com.mymav.glasses.mav2.hud

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everysight.mav2.sdk.uikit.animators.ext.fadeout
import com.everysight.mav2.sdk.uikit.base.M2Drawable
import com.everysight.mav2.sdk.uikit.base.M2DrawableList
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Text
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud

/**
 * Base for every MyMav glasslet (640×400).
 *
 * - [build] replaces `onCreate` in subclasses.
 * - After building, every [M2Text] is passed through [hudSafe] so glyphs the
 *   glasses font lacks can never blank a label.
 * - A full-screen veil fades out on entry, turning hard screen swaps into a
 *   short dissolve instead of a flash.
 * - [tryPatch] lets the session update a live screen in place (focus, dwell
 *   progress, buffer text) instead of tearing the scene down.
 */
abstract class HudScreen(tag: String) : M2Screen(width = HudTheme.W, height = HudTheme.H, tag = tag) {

    /** Registered by [HudPrimitives.drawHomePill]. */
    internal var homePill: HudPrimitives.HomePillHandle? = null

    protected abstract fun build()

    /** Glass look (style, opacity, panel colours) version this screen was built with; a mismatch forces a rebuild. */
    private var builtGlassVersion: Int = -1

    /**
     * Colour scope for this screen's panels ("app:<id>"): its cards, rows, buttons and pills inherit the app's
     * override, else the default tint. Null = default tint only (e.g. Home, which scopes each widget itself).
     */
    protected open val glassScope: String? = null

    final override fun onCreate() {
        builtGlassVersion = com.mymav.glasses.api.MymavHudPrefs.glassVersion
        HudPerf.timed("hud.build") { GlassSurface.withScope(glassScope) { build() } }
        HudPerf.timed("hud.sanitize") { sanitizeTexts() }
        HudPerf.count("hud.images", runCatching { countImages(getDrawables()) }.getOrDefault(0).toLong())
        if (enterFade) HudMotion.enter(this)
    }

    /** Subclasses can opt out of the entry dissolve (e.g. rapid same-screen rebuilds). */
    protected open val enterFade: Boolean = true

    /**
     * Apply [next] to this live screen without rebuilding. Return false when the
     * change is structural and the session must build a fresh screen.
     */
    open fun tryPatch(next: GlassesHud): Boolean = false

    /** Patch only the HOME pill dwell ring/bar. */
    fun patchHomeDwell(progress: Float): Boolean {
        val handle = homePill ?: return false
        if (builtGlassVersion != com.mymav.glasses.api.MymavHudPrefs.glassVersion) return false
        return runCatching {
            handle.update(progress)
            true
        }.getOrDefault(false)
    }

    /** Same font/glyph pass as onCreate, for drawables a [tryPatch] adds later. */
    protected fun sanitizeAdded(added: List<M2Drawable>) {
        runCatching { added.forEach { sanitize(it) } }
    }

    private fun countImages(list: List<M2Drawable>): Int = list.sumOf { d ->
        when (d) {
            is com.everysight.mav2.sdk.uikit.drawables.M2Image -> 1
            is M2DrawableList<*> -> countImages(d.getDrawables())
            else -> 0
        }
    }

    private fun sanitizeTexts() {
        runCatching { getDrawables().forEach { sanitize(it) } }
            .onFailure { Log.w("HudScreen", "text sanitize skipped: ${it.message}") }
    }

    private fun sanitize(d: M2Drawable) {
        when (d) {
            is M2Text -> {
                // Font first (stock labels move onto Roboto), then sanitise + layout token for that font.
                HudText.upgrade(d)
                d.setHudText(d.text)
            }
            is M2DrawableList<*> -> d.getDrawables().forEach { sanitize(it) }
            else -> Unit
        }
    }
}

/** Screen entry motion (dissolve-in via a fading black veil). */
object HudMotion {
    private const val TAG = "HudMotion"
    private const val ENTER_MS = 140L
    /** Hard stop: the veil is removed even if the animator never runs. */
    private const val SAFETY_MS = 650L

    private val main = Handler(Looper.getMainLooper())

    fun enter(screen: M2Screen) {
        val veil = runCatching {
            M2RectFilled(M2Color.Black).apply {
                setDimensions(0f, 0f, HudTheme.W, HudTheme.H)
            }.also { screen.add(it) }
        }.getOrNull() ?: return

        fun drop() {
            runCatching {
                if (veil.isAttachedToScreen()) screen.remove(veil)
            }
        }

        // Start on the next loop so the screen (and veil) are fully created.
        main.post {
            val started = runCatching {
                veil.fadeout(ENTER_MS, true).start()
            }.onFailure {
                Log.w(TAG, "enter fade failed: ${it.message}")
            }.getOrDefault(false)
            if (!started) drop()
        }
        main.postDelayed({ drop() }, SAFETY_MS)
    }
}
