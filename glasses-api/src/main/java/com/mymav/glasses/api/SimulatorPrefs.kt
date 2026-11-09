package com.mymav.glasses.api

import android.content.Context

/** Persists simulator host so services can reconnect after process death. */
object SimulatorPrefs {
    private const val PREFS = "mymav_simulator"
    private const val KEY_HOST = "host"
    private const val DEFAULT_HOST = "10.0.2.2"

    fun host(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, DEFAULT_HOST)
            ?.trim()
            ?.ifEmpty { DEFAULT_HOST }
            ?: DEFAULT_HOST
    }

    private const val KEY_AUTO = "auto_connect"

    /** Keep the link up in the background (off only after the user taps Disconnect). */
    fun autoConnect(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoConnect(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun saveHost(context: Context, host: String) {
        val value = host.trim().ifEmpty { DEFAULT_HOST }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, value)
            .apply()
    }
}
