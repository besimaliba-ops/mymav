package com.mymav.glasses.api

/** Glasses Workouts selection — Running vs Weight training. */
enum class WorkoutKind {
    Running,
    WeightTraining,
}

/** Muscle region for weight exercises (phone silhouette + glass label). */
enum class WeightBodyPart(val label: String) {
    Chest("Chest"),
    Thighs("Thighs"),
    Calves("Calves"),
    Shoulders("Shoulders"),
    Arms("Arms"),
    Back("Back"),
    Core("Core"),
    ;

    companion object {
        fun fromKey(raw: String?): WeightBodyPart =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: entries.firstOrNull { it.label.equals(raw, ignoreCase = true) }
                ?: Chest
    }
}

data class WorkoutSelectHudActions(
    val onFocusChanged: ((Int) -> Unit)? = null,
    val onSelect: ((WorkoutKind) -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

data class RunningPaceHudActions(
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
    /** Reserved for cycling Pace / HR / Split demo views. */
    val onCycleView: (() -> Unit)? = null,
)

/** One exercise row on the Weight Session glasslet. */
data class WeightExerciseHudItem(
    val id: String,
    val name: String,
    val bodyPart: WeightBodyPart,
    val reps: Int,
    /** Per-set completion flags (length = planned sets). */
    val setsDone: List<Boolean> = emptyList(),
    /** Working weight in kg (from phone session builder). */
    val weightKg: Float = 0f,
) {
    val setsTotal: Int get() = setsDone.size
    val setsCompleted: Int get() = setsDone.count { it }
    val setsRemaining: Int get() = setsDone.count { !it }
}

data class WeightSessionHudActions(
    val onFocusChanged: ((Int) -> Unit)? = null,
    /** Toggle / complete set at [setIndex] for [exerciseId]. */
    val onToggleSet: ((String, Int) -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
    /** Tap or 1s gaze — check off next incomplete set on focused exercise. */
    val onSelectFocused: (() -> Unit)? = null,
)
