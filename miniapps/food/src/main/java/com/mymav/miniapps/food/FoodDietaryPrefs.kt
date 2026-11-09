package com.mymav.miniapps.food

import android.content.Context

/**
 * Lightweight dietary prefs for Food HUD analysis.
 * Keys kept stable under [PREFS]; does not clear or migrate other apps' DataStores.
 */
object FoodDietaryPrefs {
    private const val PREFS = "mymav_food_prefs"
    private const val KEY_VEGAN = "vegan"
    private const val KEY_VEGETARIAN = "vegetarian"
    private const val KEY_LOW_SALT = "low_salt"
    private const val KEY_ALLERGENS = "allergens_csv"

    data class Profile(
        val vegan: Boolean = false,
        val vegetarian: Boolean = false,
        val lowSalt: Boolean = false,
        val allergensCsv: String = "",
    ) {
        fun notesForPrompt(): String = buildList {
            if (vegan) add("vegan")
            if (vegetarian) add("vegetarian")
            if (lowSalt) add("low salt")
            val a = allergensCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (a.isNotEmpty()) add("avoid allergens: ${a.joinToString(", ")}")
        }.joinToString("; ").ifBlank { "none stated" }
    }

    fun load(context: Context): Profile {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(
            vegan = p.getBoolean(KEY_VEGAN, false),
            vegetarian = p.getBoolean(KEY_VEGETARIAN, false),
            lowSalt = p.getBoolean(KEY_LOW_SALT, false),
            allergensCsv = p.getString(KEY_ALLERGENS, "").orEmpty(),
        )
    }

    fun save(context: Context, profile: Profile) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VEGAN, profile.vegan)
            .putBoolean(KEY_VEGETARIAN, profile.vegetarian)
            .putBoolean(KEY_LOW_SALT, profile.lowSalt)
            .putString(KEY_ALLERGENS, profile.allergensCsv)
            .apply()
    }
}
