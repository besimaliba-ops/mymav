package com.mymav.glasses.api

import android.content.Context

/**
 * Glasses text style, chosen on the phone (Settings → Glasses text).
 *
 * The Maverick SDK renders text from Everysight's own 2-bit font atlases. The
 * catalogue it ships today is the Roboto family (Medium, Bold, ExtraBold) plus
 * the built-in Everysight bitmap font, so every style here maps onto those.
 */
object MymavHudPrefs {
    enum class FontStyle(val label: String, val detail: String) {
        Modern("Modern", "Roboto Medium body, Bold headings"),
        Bold("Bold", "Roboto Bold everywhere — punchier in daylight"),
        Heavy("Heavy", "Roboto ExtraBold headings, Bold body"),
        Sans("Sans", "Inter Medium body, Bold headings — clean and modern"),
        SansItalic("Sans Italic", "Inter Italic body, Bold Italic headings"),
        Classic("Classic", "Everysight built-in bitmap font"),
    }

    enum class TextSize(val label: String, val step: Int) {
        Compact("Compact", -1),
        Standard("Standard", 0),
        Large("Large", 1),
    }

    private const val PREFS = "mymav_hud"
    private const val KEY_FONT = "font_style_v2"
    private const val KEY_SIZE = "text_size"
    private const val KEY_GLASS = "glass_opacity_pct"
    private const val KEY_PANEL_STYLE = "panel_style"

    @Volatile private var cachedFont: FontStyle? = null
    @Volatile private var cachedSize: TextSize? = null
    @Volatile private var appContext: Context? = null

    /** Called (main thread) whenever the phone changes the style. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun context(): Context? = appContext

    fun install(context: Context) {
        appContext = context.applicationContext
        cachedFont = null
        cachedSize = null
        cachedGlass = null
        cachedPanelStyle = null
    }

    /** Glass panel opacity as a percentage of the packaged artwork (100 = as designed). Text and icons are unaffected. */
    const val GLASS_MIN = 30
    const val GLASS_MAX = 300
    @Volatile private var cachedGlass: Int? = null

    /** Called (main thread) when the panel opacity changes; does not touch the font pipeline. */
    @Volatile var onGlassChanged: (() -> Unit)? = null

    /** Glasses panel surface artwork set (phone Settings -> Glasses panels). Changes glasses surfaces only. */
    enum class PanelStyle(val label: String, val detail: String, val dir: String) {
        Neutral("Neutral Glass", "Faint neutral body, soft silver corner reflections", "neutral"),
        ClearGloss("Clear Gloss", "Clearest body, more pronounced reflections", "clear"),
        SoftFrost("Soft Frost", "Soft silver veil (no real blur)", "frost"),
        Smoked("Smoked Glass", "Charcoal centre with silver highlights", "smoked"),
    }

    @Volatile private var cachedPanelStyle: PanelStyle? = null

    /** Called (main thread) when the panel style changes; does not touch the font pipeline. */
    @Volatile var onPanelStyleChanged: (() -> Unit)? = null

    fun panelStyle(): PanelStyle {
        cachedPanelStyle?.let { return it }
        val ctx = appContext ?: return PanelStyle.Neutral
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PANEL_STYLE, null)
        return (PanelStyle.entries.firstOrNull { it.name == raw } ?: PanelStyle.Neutral).also { cachedPanelStyle = it }
    }

    fun savePanelStyle(context: Context, style: PanelStyle) {
        appContext = context.applicationContext
        if (style == panelStyle()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PANEL_STYLE, style.name).apply()
        cachedPanelStyle = style
        glassVersion++
        runCatching { onPanelStyleChanged?.invoke() }
    }


    // ---- panel colour (tint), independent of the panel style -------------------------------------------------------

    /**
     * Bumped on every change to the glass look (style, opacity, any tint setting). Screens remember the value they were
     * built with and are rebuilt (never patched in place) when it differs; it is the rebuild/cache decision input.
     */
    @Volatile var glassVersion: Int = 0
        private set

    const val TINT_MIN = 10
    const val TINT_MAX = 100
    const val TINT_DEFAULT_STRENGTH = 60
    private const val KEY_TINT_DEFAULT = "tint_default"
    private const val KEY_TINT_STRENGTH = "tint_strength"
    private const val KEY_TINT_OVR = "tint_ovr_"

    /** Scope ids: widget:<id> for a Home widget, app:<id> for an app's panels (cards, buttons, pills inherit). */
    fun widgetScope(id: String) = "widget:$id"
    fun appScope(id: String) = "app:$id"

    private fun sp() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultTintKey(): String = sp()?.getString(KEY_TINT_DEFAULT, null)?.takeIf { it.isNotBlank() } ?: PanelTint.NEUTRAL

    fun tintStrength(): Int = (sp()?.getInt(KEY_TINT_STRENGTH, TINT_DEFAULT_STRENGTH) ?: TINT_DEFAULT_STRENGTH).coerceIn(TINT_MIN, TINT_MAX)

    /** The override stored for [scope], or null when the scope uses the default tint. */
    fun tintOverride(scope: String): String? = sp()?.getString(KEY_TINT_OVR + scope, null)?.takeIf { it.isNotBlank() }

    /** Tint key used to draw [scope] (override first, otherwise the default); null scope = default. */
    fun effectiveTintKey(scope: String?): String = (scope?.let { tintOverride(it) }) ?: defaultTintKey()

    private fun tintChanged() {
        glassVersion++
        runCatching { onGlassChanged?.invoke() }
    }

    fun saveDefaultTint(context: Context, key: String) {
        appContext = context.applicationContext
        if (key == defaultTintKey()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TINT_DEFAULT, key).apply()
        tintChanged()
    }

    fun saveTintStrength(context: Context, percent: Int) {
        appContext = context.applicationContext
        val p = percent.coerceIn(TINT_MIN, TINT_MAX)
        if (p == tintStrength()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TINT_STRENGTH, p).apply()
        tintChanged()
    }

    /** Set (or with null clear, i.e. "Use default") the override for [scope]. Style, opacity and other scopes are untouched. */
    fun saveTintOverride(context: Context, scope: String, key: String?) {
        appContext = context.applicationContext
        if (key == tintOverride(scope)) return
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (key == null) e.remove(KEY_TINT_OVR + scope) else e.putString(KEY_TINT_OVR + scope, key)
        e.apply()
        tintChanged()
    }

    /** Clear the default tint, strength and every override. The panel style and opacity are left as they are. */
    fun resetColours(context: Context) {
        appContext = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit().remove(KEY_TINT_DEFAULT).remove(KEY_TINT_STRENGTH)
        sp.all.keys.filter { it.startsWith(KEY_TINT_OVR) }.forEach { e.remove(it) }
        e.apply()
        tintChanged()
    }

    /** True when any override exists (for the phone UI). */
    fun hasOverrides(): Boolean = sp()?.all?.keys?.any { it.startsWith(KEY_TINT_OVR) } ?: false

    fun glassOpacityPercent(): Int {
        cachedGlass?.let { return it }
        val ctx = appContext ?: return 100
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GLASS, 100)
            .coerceIn(GLASS_MIN, GLASS_MAX).also { cachedGlass = it }
    }

    fun saveGlassOpacity(context: Context, percent: Int) {
        appContext = context.applicationContext
        val p = percent.coerceIn(GLASS_MIN, GLASS_MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GLASS, p).apply()
        cachedGlass = p
        glassVersion++
        runCatching { onGlassChanged?.invoke() }
    }

    fun fontStyle(): FontStyle {
        cachedFont?.let { return it }
        val ctx = appContext ?: return FontStyle.Bold
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FONT, null)
        return (FontStyle.entries.firstOrNull { it.name == raw } ?: FontStyle.Bold).also { cachedFont = it }
    }

    fun textSize(): TextSize {
        cachedSize?.let { return it }
        val ctx = appContext ?: return TextSize.Standard
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SIZE, null)
        return (TextSize.entries.firstOrNull { it.name == raw } ?: TextSize.Standard).also { cachedSize = it }
    }

    fun save(context: Context, font: FontStyle = fontStyle(), size: TextSize = textSize()) {
        appContext = context.applicationContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FONT, font.name)
            .putString(KEY_SIZE, size.name)
            .apply()
        cachedFont = font
        cachedSize = size
        runCatching { onChanged?.invoke() }
    }
}
