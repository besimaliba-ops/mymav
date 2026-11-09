package com.mymav.glasses.api

import android.content.Context

/**
 * Persistent MyMav home/launcher avatar preset + accent.
 * Presets are drawn procedurally (not Pokémon clones).
 */
object MymavAvatarPrefs {
    enum class Preset(val label: String) {
        Pip("Pip"),
        Bolt("Bolt"),
        Nova("Nova"),
        Orbit("Orbit"),
    }

    /** Glasses home mascot behaviour. */
    enum class Mode(val label: String) {
        /** Full motion — page runs, jumps into apps, top-bar dock. */
        Active("Active"),
        /** Bottom-left dock; no swipe/jump motion; 1s gaze still summons bubble. */
        Sleep("Sleep"),
        /** Completely hidden on glasses home. */
        Off("Off"),
    }

    private const val PREFS = "mymav_avatar"
    private const val KEY_PRESET = "preset"
    private const val KEY_ACCENT = "accent_argb"
    private const val KEY_MODE = "mode"

    fun preset(context: Context): Preset {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, Preset.Pip.name)
        return Preset.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Preset.Pip
    }

    fun accentArgb(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCENT, 0xFF2EE6A6.toInt())
    }

    fun mode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.Active.name)
        return Mode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Mode.Active
    }

    fun save(context: Context, preset: Preset, accentArgb: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESET, preset.name)
            .putInt(KEY_ACCENT, accentArgb)
            .apply()
    }

    fun saveMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
