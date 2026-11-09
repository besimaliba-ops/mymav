package com.mymav.miniapps.fitness

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.HealthHeartSnapshot
import com.mymav.glasses.api.HomeWidgetPreviewStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class DailyStats(
    val steps: Long? = null,
    val distanceKm: Double? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val eatenKcal: Double? = null,
    val sleepHours: Double? = null,
    val workoutsCount: Int? = null,
) {
    val anyAvailable: Boolean
        get() = steps != null || activeKcal != null || totalKcal != null || eatenKcal != null
}

data class WeeklyStats(
    val steps: Long? = null,
    val distanceKm: Double? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val eatenKcal: Double? = null,
    val sleepHours: Double? = null,
    val workoutsCount: Int? = null,
    val daily: List<DailyStats> = emptyList(),
)

data class WorkoutEntry(
    val title: String,
    /** Whole minutes (compat). */
    val durationMin: Int,
    val activeKcal: Double,
    /** Exact duration in seconds (Samsung-style mm:ss). */
    val durationSec: Int = durationMin * 60,
    val distanceKm: Double = 0.0,
) {
    fun durationLabel(): String {
        val s = durationSec.coerceAtLeast(0)
        val m = s / 60
        val r = s % 60
        return "%d:%02d".format(m, r)
    }
}

data class HealthGoals(
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val eatenKcal: Double? = null,
)

/**
 * In-memory fitness metrics with SharedPreferences cache (demo fallback when HC unavailable).
 */
class HealthStatsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var statusMessage: String = ""
        private set

    var today: DailyStats = DailyStats()
        private set

    var week: WeeklyStats = WeeklyStats()
        private set

    var workouts: List<WorkoutEntry> = emptyList()
        private set

    var goals: HealthGoals = HealthGoals()
        private set

    /** When the stored reading came from Health Connect (epoch ms); 0 = unknown. Persisted with the cache. */
    var readingAt: Long = 0L
        private set

    /** True once Health Connect has answered in this process; until then a restored cache is shown as cached. */
    private var freshThisSession = false

    init {
        loadCache()
        loadGoals()
        // Publish the cached reading to the Home tile only when it came from Health Connect, so Home is not
        // blank until Health is opened, and never shows demo numbers as if they were the user's.
        if (statusMessage.startsWith("Live")) publishHomePreview()
    }

    fun setFromHealthConnect(
        todayStats: DailyStats,
        weekStats: WeeklyStats,
        workoutList: List<WorkoutEntry>,
        message: String,
    ) {
        today = todayStats
        week = weekStats
        workouts = workoutList
        statusMessage = message
        readingAt = System.currentTimeMillis()
        freshThisSession = true
        saveCache()
        publishHomePreview()
    }

    fun applyDemo(message: String) {
        statusMessage = message
        val keepLiveCache = readingAt > 0L && today.anyAvailable &&
            prefs.getString(KEY_BUNDLE, null)?.contains("\"status\":\"Live") == true
        if (!keepLiveCache) {
            today = DailyStats()
            week = WeeklyStats()
            workouts = emptyList()
            readingAt = 0L
            saveCache()
        }
        publishHomePreview()
    }

    fun setGoals(next: HealthGoals) {
        goals = HealthGoals(
            steps = next.steps?.takeIf { it > 0L },
            activeKcal = next.activeKcal?.takeIf { it > 0.0 },
            eatenKcal = next.eatenKcal?.takeIf { it > 0.0 },
        )
        prefs.edit()
            .putString(KEY_GOAL_STEPS, goals.steps?.toString() ?: "")
            .putString(KEY_GOAL_ACTIVE, goals.activeKcal?.let { it.roundToInt().toString() } ?: "")
            .putString(KEY_GOAL_EATEN, goals.eatenKcal?.let { it.roundToInt().toString() } ?: "")
            .apply()
        publishHomePreview()
    }

    fun snapshot(): HealthHeartSnapshot {
        val kind = when {
            !today.anyAvailable || !statusMessage.startsWith("Live") -> HealthHeartSnapshot.Kind.None
            isPreviousLocalDay() -> HealthHeartSnapshot.Kind.Stale
            isCachedReading() -> HealthHeartSnapshot.Kind.Cached
            else -> HealthHeartSnapshot.Kind.Live
        }
        return HealthHeartSnapshot(
            steps = today.steps,
            activeKcal = today.activeKcal,
            eatenKcal = today.eatenKcal,
            totalKcal = today.totalKcal,
            stepGoal = goals.steps,
            activeGoal = goals.activeKcal,
            eatenGoal = goals.eatenKcal,
            freshness = if (kind == HealthHeartSnapshot.Kind.None) "" else readingLabel(),
            kind = kind,
        )
    }

    /** Push steps / active / eaten / total burnt onto the Health home tile. */
    fun publishHomePreview() {
        val snap = snapshot()
        val encoded = snap.encode()
        HomeWidgetPreviewStore.put(
            "fitness",
            HomeWidgetPreviewStore.Preview(
                title = "Health",
                subtitle = "",
                metric = encoded.first,
                metricDetail = encoded.second,
                state = encoded.third.first,
                lines = encoded.third.second,
            ),
        )
        GlassesHomeController.refreshHomeIfShowing()
    }

    fun needsMidnightRefresh(): Boolean = isPreviousLocalDay() && statusMessage.startsWith("Live")

    fun isPreviousLocalDay(): Boolean {
        if (readingAt <= 0L) return false
        val then = java.util.Calendar.getInstance().apply { timeInMillis = readingAt }
        val now = java.util.Calendar.getInstance()
        return then.get(java.util.Calendar.YEAR) != now.get(java.util.Calendar.YEAR) ||
            then.get(java.util.Calendar.DAY_OF_YEAR) != now.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** "14:05" for a reading from today, "13 Oct" for an older one, "earlier" when the time was never stored. */
    fun readingLabel(): String {
        if (readingAt <= 0L) return "earlier"
        val then = java.util.Calendar.getInstance().apply { timeInMillis = readingAt }
        val now = java.util.Calendar.getInstance()
        val sameDay = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) "HH:mm" else "d MMM"
        return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(readingAt))
    }

    /** True while the numbers shown are a restored cache that Health Connect has not refreshed yet. */
    fun isCachedReading(): Boolean = !freshThisSession && statusMessage.startsWith("Live")

    fun formatSteps(n: Long): String = HealthHeartSnapshot.formatCount(n)

    fun formatSteps(n: Long?): String = n?.let { formatSteps(it) } ?: "--"

    fun formatDistance(km: Double): String = String.format("%.2f km", km)

    fun formatDistance(km: Double?): String = km?.let { formatDistance(it) } ?: "--"

    fun formatKcal(kcal: Double): String = "${kcal.roundToInt()} kcal"

    fun formatKcal(kcal: Double?): String = kcal?.let { formatKcal(it) } ?: "--"

    fun glassesLines(): List<String> = listOf(
        "Steps ${formatSteps(today.steps)}",
        formatDistance(today.distanceKm),
        "Active ${formatKcal(today.activeKcal)}",
        "Eaten ${formatKcal(today.eatenKcal)}",
        "Total ${formatKcal(today.totalKcal)}",
    )

    private fun loadGoals() {
        goals = HealthGoals(
            steps = prefs.getString(KEY_GOAL_STEPS, "")?.toLongOrNull()?.takeIf { it > 0L },
            activeKcal = prefs.getString(KEY_GOAL_ACTIVE, "")?.toDoubleOrNull()?.takeIf { it > 0.0 },
            eatenKcal = prefs.getString(KEY_GOAL_EATEN, "")?.toDoubleOrNull()?.takeIf { it > 0.0 },
        )
    }

    private fun loadCache() {
        val raw = prefs.getString(KEY_BUNDLE, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            today = parseDaily(root.getJSONObject("today"))
            week = parseWeekly(root.getJSONObject("week"))
            workouts = parseWorkouts(root.optJSONArray("workouts"))
            statusMessage = root.optString("status", "")
            readingAt = root.optLong("savedAt", 0L)
        }
    }

    private fun saveCache() {
        val root = JSONObject()
            .put("status", statusMessage)
            .put("savedAt", readingAt)
            .put("today", dailyToJson(today))
            .put("week", weeklyToJson(week))
            .put("workouts", workoutsToJson(workouts))
        prefs.edit().putString(KEY_BUNDLE, root.toString()).apply()
    }

    companion object {
        @Volatile
        var shared: HealthStatsStore? = null

        private const val PREFS = "mymav_fitness_stats"
        /** Bump when demo / schema changes so stale "Workout"/wrong-kcal cache is dropped. */
        private const val KEY_BUNDLE = "bundle_v3"
        private const val KEY_GOAL_STEPS = "goal_steps"
        private const val KEY_GOAL_ACTIVE = "goal_active_kcal"
        private const val KEY_GOAL_EATEN = "goal_eaten_kcal"

        fun demoDaily(): DailyStats = DailyStats(
            steps = 8_649L,
            distanceKm = 6.04,
            activeKcal = 412.0,
            totalKcal = 2_184.0,
            eatenKcal = 850.0,
            sleepHours = 6.8,
            workoutsCount = 3,
        )

        fun demoWeekly(): WeeklyStats {
            val daily = (6 downTo 0).map { offset ->
                val factor = 0.85 + (offset % 3) * 0.08
                DailyStats(
                    steps = (8_649 * factor).roundToInt().toLong(),
                    distanceKm = 6.04 * factor,
                    activeKcal = 412.0 * factor,
                    totalKcal = 2_184.0,
                    eatenKcal = 850.0 * factor,
                    sleepHours = 6.5 + offset * 0.15,
                    workoutsCount = if (offset % 2 == 0) 1 else 0,
                )
            }
            return WeeklyStats(
                steps = 60_449L,
                distanceKm = 42.28,
                activeKcal = 2_290.0,
                totalKcal = 15_288.0,
                eatenKcal = 5_950.0,
                sleepHours = 47.6,
                workoutsCount = 12,
                daily = daily,
            )
        }

        /** Matches Samsung Health history cards (names, mm:ss, km, kcal). */
        fun demoWorkouts(): List<WorkoutEntry> = listOf(
            WorkoutEntry("Walking (auto)", 14, 47.0, durationSec = 14 * 60 + 14, distanceKm = 1.30),
            WorkoutEntry("Weight machines", 44, 388.0, durationSec = 44 * 60 + 25),
            WorkoutEntry("Walking (auto)", 11, 34.0, durationSec = 11 * 60 + 12, distanceKm = 1.03),
            WorkoutEntry("Walking (auto)", 11, 65.0, durationSec = 11 * 60 + 3, distanceKm = 0.60),
        )

        private fun dailyToJson(d: DailyStats): JSONObject = JSONObject()
            .put("steps", d.steps ?: JSONObject.NULL)
            .put("distanceKm", d.distanceKm ?: JSONObject.NULL)
            .put("activeKcal", d.activeKcal ?: JSONObject.NULL)
            .put("totalKcal", d.totalKcal ?: JSONObject.NULL)
            .put("eatenKcal", d.eatenKcal ?: JSONObject.NULL)
            .put("sleepHours", d.sleepHours ?: JSONObject.NULL)
            .put("workoutsCount", d.workoutsCount ?: JSONObject.NULL)

        private fun parseDaily(o: JSONObject): DailyStats = DailyStats(
            steps = o.optLongOrNull("steps"),
            distanceKm = o.optDoubleOrNull("distanceKm"),
            activeKcal = o.optDoubleOrNull("activeKcal"),
            totalKcal = o.optDoubleOrNull("totalKcal"),
            eatenKcal = o.optDoubleOrNull("eatenKcal"),
            sleepHours = o.optDoubleOrNull("sleepHours"),
            workoutsCount = o.optIntOrNull("workoutsCount"),
        )

        private fun weeklyToJson(w: WeeklyStats): JSONObject = JSONObject()
            .put("steps", w.steps ?: JSONObject.NULL)
            .put("distanceKm", w.distanceKm ?: JSONObject.NULL)
            .put("activeKcal", w.activeKcal ?: JSONObject.NULL)
            .put("totalKcal", w.totalKcal ?: JSONObject.NULL)
            .put("eatenKcal", w.eatenKcal ?: JSONObject.NULL)
            .put("sleepHours", w.sleepHours ?: JSONObject.NULL)
            .put("workoutsCount", w.workoutsCount ?: JSONObject.NULL)
            .put("daily", JSONArray(w.daily.map { dailyToJson(it) }))

        private fun parseWeekly(o: JSONObject): WeeklyStats {
            val days = mutableListOf<DailyStats>()
            val arr = o.optJSONArray("daily")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    days += parseDaily(arr.getJSONObject(i))
                }
            }
            return WeeklyStats(
                steps = o.optLongOrNull("steps"),
                distanceKm = o.optDoubleOrNull("distanceKm"),
                activeKcal = o.optDoubleOrNull("activeKcal"),
                totalKcal = o.optDoubleOrNull("totalKcal"),
                eatenKcal = o.optDoubleOrNull("eatenKcal"),
                sleepHours = o.optDoubleOrNull("sleepHours"),
                workoutsCount = o.optIntOrNull("workoutsCount"),
                daily = days,
            )
        }

        private fun workoutsToJson(list: List<WorkoutEntry>): JSONArray {
            val arr = JSONArray()
            list.forEach { w ->
                arr.put(
                    JSONObject()
                        .put("title", w.title)
                        .put("durationMin", w.durationMin)
                        .put("durationSec", w.durationSec)
                        .put("activeKcal", w.activeKcal)
                        .put("distanceKm", w.distanceKm),
                )
            }
            return arr
        }

        private fun parseWorkouts(arr: JSONArray?): List<WorkoutEntry> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val min = o.getInt("durationMin")
                    add(
                        WorkoutEntry(
                            title = o.getString("title"),
                            durationMin = min,
                            activeKcal = o.getDouble("activeKcal"),
                            durationSec = o.optInt("durationSec", min * 60),
                            distanceKm = o.optDouble("distanceKm", 0.0),
                        ),
                    )
                }
            }
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}
