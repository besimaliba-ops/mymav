package com.mymav.miniapps.notify.service

import android.content.Context

/** Remembers that the user wants Notify companion kept alive in the background. */
object NotifyCompanionPrefs {
    private const val PREFS = "mymav_notify_companion"
    private const val KEY_WANTED = "wanted"

    fun isWanted(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANTED, false)
    }

    fun setWanted(context: Context, wanted: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WANTED, wanted)
            .apply()
    }
}
