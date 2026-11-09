package com.mymav.miniapps.workouts

import android.content.Context

/**
 * Persists named training days (exercises + kg), the active day, and
 * weekly/monthly completion history.
 */
class WeightSessionStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadDays(): List<WorkoutDayPlan> {
        val days = workoutDaysFromJson(prefs.getString(KEY_DAYS, null))
        if (days.isNotEmpty()) return days
        // Migrate legacy flat session_json → single "Session" day.
        val legacy = weightPlanFromJson(prefs.getString(KEY_LEGACY, null))
        return if (legacy.isNotEmpty()) {
            listOf(WorkoutDayPlan(name = "Session", exercises = legacy)).also { saveDays(it) }
        } else {
            WorkoutDayPlan.demoDays().also { saveDays(it) }
        }
    }

    fun saveDays(days: List<WorkoutDayPlan>) {
        prefs.edit().putString(KEY_DAYS, days.daysToJson()).apply()
    }

    fun selectedDayId(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelectedDayId(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun selectedDay(): WorkoutDayPlan {
        val days = loadDays()
        val id = selectedDayId()
        return days.firstOrNull { it.id == id } ?: days.first()
    }

    fun saveSelectedDayExercises(exercises: List<WeightPlanExercise>) {
        val days = loadDays().toMutableList()
        val sel = selectedDay()
        val idx = days.indexOfFirst { it.id == sel.id }
        if (idx >= 0) {
            days[idx] = sel.copy(exercises = exercises)
            saveDays(days)
        }
    }

    /** Legacy API used by glasses push — returns exercises for the selected day. */
    fun load(): List<WeightPlanExercise> = selectedDay().exercises

    fun save(exercises: List<WeightPlanExercise>) = saveSelectedDayExercises(exercises)

    fun loadOrDemo(): List<WeightPlanExercise> {
        val loaded = load()
        return loaded.ifEmpty { listOf(WeightPlanExercise.demoBench()) }
    }

    fun loadHistory(): List<WorkoutHistoryEntry> =
        workoutHistoryFromJson(prefs.getString(KEY_HISTORY, null))
            .sortedByDescending { it.completedAtMs }

    fun appendHistory(entry: WorkoutHistoryEntry) {
        val next = (listOf(entry) + loadHistory()).take(200)
        prefs.edit().putString(KEY_HISTORY, next.historyToJson()).apply()
    }

    fun logSelectedDayComplete() {
        appendHistory(WorkoutHistoryEntry.fromPlan(selectedDay()))
    }

    companion object {
        private const val PREFS = "mymav_weight_session"
        private const val KEY_LEGACY = "session_json"
        private const val KEY_DAYS = "days_json_v1"
        private const val KEY_SELECTED = "selected_day_id"
        private const val KEY_HISTORY = "history_json_v1"
    }
}
