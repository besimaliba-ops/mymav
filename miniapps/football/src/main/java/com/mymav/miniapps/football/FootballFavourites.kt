package com.mymav.miniapps.football

import android.content.Context

/**
 * Favourite league keys for ESPN boards.
 * Migrates prior dashboard prefs `maverick_favourites` / `favourite_league_ids` (API-Sports ids)
 * into MyMav keys when present; never clears the old store.
 */
object FootballFavourites {
    private const val MYMAV_PREFS = "mymav_football_prefs"
    private const val KEY_LEAGUES = "favourite_league_keys"
    private const val LEGACY_PREFS = "maverick_favourites"
    private const val LEGACY_IDS = "favourite_league_ids"

    /** API-Sports league id → ESPN league key used by [com.mymav.glasses.api.MymavScores]. */
    private val LEGACY_ID_TO_KEY = mapOf(
        39L to "premier league",
        140L to "la liga",
        78L to "bundesliga",
        135L to "serie a",
        61L to "ligue 1",
        2L to "champions league",
    )

    fun loadKeys(context: Context): Set<String> {
        val app = context.applicationContext
        val mine = app.getSharedPreferences(MYMAV_PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_LEAGUES, null)
        if (!mine.isNullOrEmpty()) return mine

        val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getStringSet(LEGACY_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.mapNotNull { LEGACY_ID_TO_KEY[it] }
            ?.toSet()
            .orEmpty()
        if (legacy.isNotEmpty()) {
            saveKeys(app, legacy)
            return legacy
        }
        return setOf("premier league")
    }

    fun saveKeys(context: Context, keys: Set<String>) {
        context.applicationContext.getSharedPreferences(MYMAV_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_LEAGUES, keys)
            .apply()
    }

    fun toggle(context: Context, key: String): Set<String> {
        val next = loadKeys(context).toMutableSet()
        if (!next.add(key)) next.remove(key)
        if (next.isEmpty()) next.add("premier league")
        saveKeys(context, next)
        return next
    }
}
