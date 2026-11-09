package com.mymav.miniapps.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

class SamsungHealthReader(private val context: Context) {

    val requiredPermissions: Set<String> = PERMISSIONS
    val nutritionPermissions: Set<String> = NUTRITION_PERMISSIONS
    val allPermissions: Set<String> = PERMISSIONS + NUTRITION_PERMISSIONS

    suspend fun refresh(store: HealthStatsStore): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val status = HealthConnectClient.getSdkStatus(app)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            store.applyDemo(
                "Health Connect not installed. Install it and enable Samsung Health → Health Connect sync.",
            )
            return@withContext false
        }
        val client = HealthConnectClient.getOrCreate(app)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(PERMISSIONS)) {
            store.applyDemo(
                "Grant Health Connect read access, then sync Samsung Health → Health Connect.",
            )
            return@withContext false
        }
        runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val todayStats = readDay(client, today, zone)
            val dailyWeek = (6 downTo 0).map { offset ->
                readDay(client, today.minusDays(offset.toLong()), zone)
            }
            val weekStats = WeeklyStats(
                steps = dailyWeek.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.sum(),
                distanceKm = dailyWeek.mapNotNull { it.distanceKm }.takeIf { it.isNotEmpty() }?.sum(),
                activeKcal = dailyWeek.mapNotNull { it.activeKcal }.takeIf { it.isNotEmpty() }?.sum(),
                totalKcal = dailyWeek.mapNotNull { it.totalKcal }.takeIf { it.isNotEmpty() }?.sum(),
                eatenKcal = dailyWeek.mapNotNull { it.eatenKcal }.takeIf { it.isNotEmpty() }?.sum(),
                sleepHours = dailyWeek.mapNotNull { it.sleepHours }.takeIf { it.isNotEmpty() }?.sum(),
                workoutsCount = dailyWeek.mapNotNull { it.workoutsCount }.takeIf { it.isNotEmpty() }?.sum(),
                daily = dailyWeek,
            )
            val workouts = readWorkouts(client, today.minusDays(6), today, zone)
            val nutritionOk = granted.containsAll(NUTRITION_PERMISSIONS)
            val message = if (nutritionOk) {
                "Live from Health Connect (aggregate, no double-count)."
            } else {
                "Live from Health Connect. Calories eaten unavailable — grant Nutrition."
            }
            store.setFromHealthConnect(
                todayStats = todayStats,
                weekStats = weekStats,
                workoutList = workouts,
                message = message,
            )
            true
        }.getOrElse { err ->
            store.applyDemo(
                "Could not read Health Connect (${err.message ?: "error"}). " +
                    "Open Samsung Health → Settings → Health Connect and sync steps & workouts.",
            )
            false
        }
    }

    private suspend fun readDay(
        client: HealthConnectClient,
        day: LocalDate,
        zone: ZoneId,
    ): DailyStats {
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        // AggregateRequest deduplicates overlapping origin records (summing StepsRecord
        // double-counts Samsung Health + other writers — ~5k overcount).
        val agg = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                ),
                timeRangeFilter = range,
            ),
        )
        val steps = agg[StepsRecord.COUNT_TOTAL] ?: 0L
        val distanceM = agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
        val activeKcal = agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        val totalKcal = agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
        val sleepHours = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range))
            .records.sumOf { session ->
                ChronoUnit.MINUTES.between(session.startTime, session.endTime) / 60.0
            }
        val workouts = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range))
            .records.size
        val eatenKcal = readEatenKcal(client, range)
        return DailyStats(
            steps = steps,
            distanceKm = distanceM / 1000.0,
            activeKcal = activeKcal,
            totalKcal = totalKcal,
            eatenKcal = eatenKcal,
            sleepHours = sleepHours,
            workoutsCount = workouts,
        )
    }

    /**
     * Today's calories eaten from Nutrition records, including MyMav meals.
     * Copies that Samsung Health synced from the same write are collapsed so they are not counted twice.
     */
    private suspend fun readEatenKcal(
        client: HealthConnectClient,
        range: TimeRangeFilter,
    ): Double? {
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if (!granted.containsAll(NUTRITION_PERMISSIONS)) return null
        val recs = runCatching {
            client.readRecords(ReadRecordsRequest(NutritionRecord::class, range)).records
        }.getOrElse { return null }
        if (recs.isEmpty()) return 0.0
        return recs.groupBy { rec ->
            val kcal = rec.energy?.inKilocalories?.let { kotlin.math.round(it).toInt() } ?: 0
            val minute = rec.startTime.epochSecond / 60
            val name = rec.name?.trim()?.lowercase().orEmpty()
            "$minute|$kcal|$name"
        }.values.sumOf { group ->
            val mine = group.firstOrNull { it.metadata.clientRecordId?.startsWith("mymav-food-") == true }
            (mine ?: group.first()).energy?.inKilocalories ?: 0.0
        }
    }

    private suspend fun readWorkouts(
        client: HealthConnectClient,
        fromDay: LocalDate,
        toDay: LocalDate,
        zone: ZoneId,
    ): List<WorkoutEntry> {
        val start = fromDay.atStartOfDay(zone).toInstant()
        val end = toDay.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        return client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range))
            .records
            .sortedByDescending { it.startTime }
            .take(12)
            .map { session ->
                val sec = max(
                    1,
                    ChronoUnit.SECONDS.between(session.startTime, session.endTime).toInt(),
                )
                val minutes = max(1, (sec + 30) / 60)
                val sessionRange = TimeRangeFilter.between(session.startTime, session.endTime)
                val agg = runCatching {
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(
                                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                                TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                                DistanceRecord.DISTANCE_TOTAL,
                            ),
                            timeRangeFilter = sessionRange,
                        ),
                    )
                }.getOrNull()
                val active = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    ?.inKilocalories ?: 0.0
                val total = agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                    ?.inKilocalories ?: 0.0
                // Samsung Health cards often show the higher of active/total for the session.
                val kcal = maxOf(active, total)
                val distKm = (agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters ?: 0.0) / 1000.0
                WorkoutEntry(
                    title = workoutTitle(session),
                    durationMin = minutes,
                    activeKcal = kcal,
                    durationSec = sec,
                    distanceKm = distKm,
                )
            }
    }

    /**
     * Prefer Samsung Health session titles when they are real names
     * (e.g. "Walking (auto)", "Weight machines"). Generic placeholders fall through
     * to exercise-type mapping so glass never shows bare "Workout".
     */
    private fun workoutTitle(session: ExerciseSessionRecord): String {
        val titled = session.title?.trim().orEmpty()
        if (titled.isNotBlank() && !isGenericWorkoutTitle(titled)) return titled
        return titleForExerciseType(session.exerciseType, titled)
    }

    private fun isGenericWorkoutTitle(title: String): Boolean {
        val t = title.trim().lowercase()
        return t.isEmpty() || t == "workout" || t == "exercise" || t == "other" ||
            t == "session" || t == "activity"
    }

    private fun titleForExerciseType(exerciseType: Int, fallbackTitle: String): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking (auto)"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            -> "Weight machines"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            -> "Cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            -> "Swimming"
            else -> {
                val raw = exerciseType.toString()
                when {
                    raw.contains("STRENGTH", ignoreCase = true) ||
                        raw.contains("WEIGHT", ignoreCase = true) ||
                        raw.contains("CALISTHENICS", ignoreCase = true) -> "Weight machines"
                    raw.contains("WALK", ignoreCase = true) -> "Walking (auto)"
                    raw.contains("RUN", ignoreCase = true) -> "Running"
                    fallbackTitle.isNotBlank() -> fallbackTitle
                    else -> "Workout"
                }
            }
        }
    }

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )
        val NUTRITION_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class),
        )
    }
}
