package com.mymav.glasses.api

enum class FitnessHudPage {
    Daily,
    Weekly,
    Workouts,
    Sleep,
}

/** Icon glyph (emoji/ascii) + primary value + secondary label for Fitness glass rows. */
data class FitnessHudRow(
    val icon: String,
    val primary: String,
    val secondary: String,
)

data class FitnessHudActions(
    val onPageChanged: ((FitnessHudPage) -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)
