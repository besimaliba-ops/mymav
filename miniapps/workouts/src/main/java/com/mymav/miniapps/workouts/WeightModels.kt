package com.mymav.miniapps.workouts

import com.mymav.glasses.api.WeightBodyPart
import com.mymav.glasses.api.WeightExerciseHudItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** Catalog entry for the phone session builder. */
data class WeightCatalogEntry(
    val id: String,
    val name: String,
    val defaultBodyPart: WeightBodyPart,
)

object WeightExerciseCatalog {
    val entries: List<WeightCatalogEntry> = listOf(
        WeightCatalogEntry("bench_press", "Bench press", WeightBodyPart.Chest),
        WeightCatalogEntry("squat", "Squat", WeightBodyPart.Thighs),
        WeightCatalogEntry("calf_raise", "Calf raise", WeightBodyPart.Calves),
        WeightCatalogEntry("overhead_press", "Overhead press", WeightBodyPart.Shoulders),
        WeightCatalogEntry("curl", "Curl", WeightBodyPart.Arms),
        WeightCatalogEntry("deadlift", "Deadlift", WeightBodyPart.Back),
        WeightCatalogEntry("lat_pulldown", "Lat pulldown", WeightBodyPart.Back),
        WeightCatalogEntry("lunge", "Lunge", WeightBodyPart.Thighs),
        WeightCatalogEntry("tricep_pushdown", "Tricep pushdown", WeightBodyPart.Arms),
        WeightCatalogEntry("lateral_raise", "Lateral raise", WeightBodyPart.Shoulders),
        WeightCatalogEntry("plank", "Plank", WeightBodyPart.Core),
        WeightCatalogEntry("row", "Seated row", WeightBodyPart.Back),
    )

    fun byId(id: String): WeightCatalogEntry? = entries.firstOrNull { it.id == id }
}

/** Planned exercise in a persisted weight session (phone → glasses). */
data class WeightPlanExercise(
    val id: String = UUID.randomUUID().toString(),
    val catalogId: String,
    val name: String,
    val bodyPart: WeightBodyPart,
    val sets: Int,
    val reps: Int,
    /** Working weight in kilograms. */
    val weightKg: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("catalogId", catalogId)
        .put("name", name)
        .put("bodyPart", bodyPart.name)
        .put("sets", sets)
        .put("reps", reps)
        .put("weightKg", weightKg.toDouble())

    fun toHudItem(setsDone: List<Boolean>? = null): WeightExerciseHudItem {
        val flags = setsDone?.take(sets)?.let { partial ->
            partial + List((sets - partial.size).coerceAtLeast(0)) { false }
        } ?: List(sets.coerceIn(1, 10)) { false }
        return WeightExerciseHudItem(
            id = id,
            name = name,
            bodyPart = bodyPart,
            reps = reps,
            setsDone = flags.take(sets.coerceIn(1, 10)),
            weightKg = weightKg,
        )
    }

    companion object {
        fun fromJson(o: JSONObject): WeightPlanExercise = WeightPlanExercise(
            id = o.optString("id", UUID.randomUUID().toString()),
            catalogId = o.optString("catalogId", ""),
            name = o.optString("name", "Exercise"),
            bodyPart = WeightBodyPart.fromKey(o.optString("bodyPart", "Chest")),
            sets = o.optInt("sets", 3).coerceIn(1, 10),
            reps = o.optInt("reps", 10).coerceIn(1, 30),
            weightKg = o.optDouble("weightKg", 0.0).toFloat().coerceIn(0f, 500f),
        )

        fun fromCatalog(
            entry: WeightCatalogEntry,
            sets: Int = 3,
            reps: Int = 10,
            weightKg: Float = 20f,
            bodyPart: WeightBodyPart = entry.defaultBodyPart,
        ): WeightPlanExercise = WeightPlanExercise(
            catalogId = entry.id,
            name = entry.name,
            bodyPart = bodyPart,
            sets = sets.coerceIn(1, 10),
            reps = reps.coerceIn(1, 30),
            weightKg = weightKg.coerceIn(0f, 500f),
        )

        fun demoBench(): WeightPlanExercise = WeightPlanExercise(
            catalogId = "bench_press",
            name = "Bench press",
            bodyPart = WeightBodyPart.Chest,
            sets = 5,
            reps = 5,
            weightKg = 60f,
        )
    }
}

/** Named training day (e.g. Push / Pull / Legs) with its exercise list. */
data class WorkoutDayPlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val exercises: List<WeightPlanExercise> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("exercises", JSONArray(exercises.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): WorkoutDayPlan {
            val arr = o.optJSONArray("exercises") ?: JSONArray()
            val ex = (0 until arr.length()).map { WeightPlanExercise.fromJson(arr.getJSONObject(it)) }
            return WorkoutDayPlan(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Day"),
                exercises = ex,
            )
        }

        fun demoDays(): List<WorkoutDayPlan> = listOf(
            WorkoutDayPlan(name = "Push", exercises = listOf(WeightPlanExercise.demoBench())),
            WorkoutDayPlan(
                name = "Pull",
                exercises = listOf(
                    WeightPlanExercise.fromCatalog(WeightExerciseCatalog.byId("row")!!, 4, 8, 40f),
                    WeightPlanExercise.fromCatalog(WeightExerciseCatalog.byId("lat_pulldown")!!, 3, 10, 35f),
                ),
            ),
            WorkoutDayPlan(
                name = "Legs",
                exercises = listOf(
                    WeightPlanExercise.fromCatalog(WeightExerciseCatalog.byId("squat")!!, 4, 6, 80f),
                    WeightPlanExercise.fromCatalog(WeightExerciseCatalog.byId("calf_raise")!!, 3, 12, 40f),
                ),
            ),
        )
    }
}

/** One completed set logged for history. */
data class CompletedSetLog(
    val exerciseName: String,
    val catalogId: String,
    val setIndex: Int,
    val reps: Int,
    val weightKg: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("exerciseName", exerciseName)
        .put("catalogId", catalogId)
        .put("setIndex", setIndex)
        .put("reps", reps)
        .put("weightKg", weightKg.toDouble())

    companion object {
        fun fromJson(o: JSONObject): CompletedSetLog = CompletedSetLog(
            exerciseName = o.optString("exerciseName", "Exercise"),
            catalogId = o.optString("catalogId", ""),
            setIndex = o.optInt("setIndex", 0),
            reps = o.optInt("reps", 0),
            weightKg = o.optDouble("weightKg", 0.0).toFloat(),
        )
    }
}

/** A finished training session for weekly / monthly records. */
data class WorkoutHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val dayName: String,
    val completedAtMs: Long = System.currentTimeMillis(),
    val sets: List<CompletedSetLog> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("dayName", dayName)
        .put("completedAtMs", completedAtMs)
        .put("sets", JSONArray(sets.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): WorkoutHistoryEntry {
            val arr = o.optJSONArray("sets") ?: JSONArray()
            val sets = (0 until arr.length()).map { CompletedSetLog.fromJson(arr.getJSONObject(it)) }
            return WorkoutHistoryEntry(
                id = o.optString("id", UUID.randomUUID().toString()),
                dayName = o.optString("dayName", "Session"),
                completedAtMs = o.optLong("completedAtMs", System.currentTimeMillis()),
                sets = sets,
            )
        }

        fun fromPlan(day: WorkoutDayPlan): WorkoutHistoryEntry {
            val logs = day.exercises.flatMap { ex ->
                (0 until ex.sets).map { i ->
                    CompletedSetLog(
                        exerciseName = ex.name,
                        catalogId = ex.catalogId,
                        setIndex = i,
                        reps = ex.reps,
                        weightKg = ex.weightKg,
                    )
                }
            }
            return WorkoutHistoryEntry(dayName = day.name, sets = logs)
        }
    }
}

fun List<WeightPlanExercise>.toJson(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun weightPlanFromJson(raw: String?): List<WeightPlanExercise> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { WeightPlanExercise.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

fun List<WorkoutDayPlan>.daysToJson(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun workoutDaysFromJson(raw: String?): List<WorkoutDayPlan> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { WorkoutDayPlan.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

fun List<WorkoutHistoryEntry>.historyToJson(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun workoutHistoryFromJson(raw: String?): List<WorkoutHistoryEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { WorkoutHistoryEntry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

fun List<WorkoutHistoryEntry>.inCurrentWeek(): List<WorkoutHistoryEntry> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    return filter { it.completedAtMs >= start }
}

fun List<WorkoutHistoryEntry>.inCurrentMonth(): List<WorkoutHistoryEntry> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    return filter { it.completedAtMs >= start }
}
