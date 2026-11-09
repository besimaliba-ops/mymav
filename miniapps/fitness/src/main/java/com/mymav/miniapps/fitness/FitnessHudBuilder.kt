package com.mymav.miniapps.fitness

import com.mymav.glasses.api.FitnessHudActions
import com.mymav.glasses.api.FitnessHudPage
import com.mymav.glasses.api.FitnessHudRow
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import kotlin.math.roundToInt

/**
 * Builds [GlassesHud.FitnessSummary] pages (shown as **Health** on glass)
 * with icon keys that [HealthHudIcons] can draw (feet · pin · flame · bed…).
 */
object FitnessHudBuilder {

    fun build(
        store: HealthStatsStore?,
        page: FitnessHudPage = FitnessHudPage.Daily,
        onPageChanged: ((FitnessHudPage) -> Unit)? = null,
    ): GlassesHud.FitnessSummary {
        val today = store?.today ?: HealthStatsStore.demoDaily()
        val week = store?.week ?: HealthStatsStore.demoWeekly()
        val workouts = store?.workouts ?: HealthStatsStore.demoWorkouts()
        val status = store?.statusMessage?.take(48)?.ifBlank { null }
            ?: "Demo · open Health on phone to sync"

        fun steps(n: Long?) = n?.let { store?.formatSteps(it) ?: formatSteps(it) } ?: "--"
        fun dist(km: Double?) = km?.let { store?.formatDistance(it) ?: String.format("%.2f km", it) } ?: "--"
        fun kcal(v: Double?) = v?.let { store?.formatKcal(it) ?: "${it.roundToInt()} kcal" } ?: "--"

        val subtitle: String
        val heroValue: String
        val heroLabel: String
        val rows: List<FitnessHudRow>
        when (page) {
            FitnessHudPage.Daily -> {
                subtitle = if (store?.isCachedReading() == true) "Cached ${store.readingLabel()}" else "Today"
                heroValue = steps(today.steps)
                heroLabel = "steps"
                rows = listOf(
                    FitnessHudRow("steps", "Steps ${steps(today.steps)}", "today"),
                    FitnessHudRow("distance", dist(today.distanceKm), "distance"),
                    FitnessHudRow("active", "Active ${kcal(today.activeKcal)}", "active"),
                    FitnessHudRow("total", "Eaten ${kcal(today.eatenKcal)}", "eaten"),
                    FitnessHudRow("total", "Total ${kcal(today.totalKcal)}", "total"),
                )
            }
            FitnessHudPage.Weekly -> {
                subtitle = "Last 7 days"
                heroValue = steps(week.steps)
                heroLabel = "week steps"
                rows = listOf(
                    FitnessHudRow("steps", "Week ${steps(week.steps)}", "7 days"),
                    FitnessHudRow("distance", dist(week.distanceKm), "week"),
                    FitnessHudRow("active", "Active ${kcal(week.activeKcal)}", "active"),
                    FitnessHudRow("total", "Total ${kcal(week.totalKcal)}", "total"),
                )
            }
            FitnessHudPage.Workouts -> {
                subtitle = "Workouts · 7 days"
                heroValue = week.workoutsCount?.toString() ?: "--"
                heroLabel = "sessions"
                rows = if (workouts.isEmpty()) {
                    listOf(
                        FitnessHudRow("workout", "No workouts yet", "7 days"),
                        FitnessHudRow("steps", "Steps ${steps(week.steps)}", "week"),
                        FitnessHudRow("active", kcal(week.activeKcal), "active"),
                        FitnessHudRow("total", kcal(week.totalKcal), "total"),
                    )
                } else {
                    workouts.take(4).map { w ->
                        val icon = when {
                            w.title.contains("Walk", ignoreCase = true) -> "steps"
                            w.title.contains("Run", ignoreCase = true) -> "distance"
                            w.title.contains("Weight", ignoreCase = true) ||
                                w.title.contains("Strength", ignoreCase = true) -> "workout"
                            w.title.contains("Sleep", ignoreCase = true) -> "sleep"
                            else -> "workout"
                        }
                        // Samsung-style: name + mm:ss primary; km and/or kcal secondary.
                        val secondary = buildList {
                            if (w.distanceKm > 0.05) add(String.format("%.2f km", w.distanceKm))
                            if (w.activeKcal > 0.5) add("${w.activeKcal.roundToInt()} kcal")
                        }.joinToString(" · ").ifBlank { "session" }
                        FitnessHudRow(
                            icon,
                            "${w.title} ${w.durationLabel()}".take(28),
                            secondary.take(22),
                        )
                    }
                }
            }
            FitnessHudPage.Sleep -> {
                subtitle = "Sleep"
                heroValue = today.sleepHours?.let { String.format("%.1fh", it) } ?: "--"
                heroLabel = "last night"
                rows = listOf(
                    FitnessHudRow("sleep", today.sleepHours?.let { String.format("%.1f h last night", it) } ?: "--", "sleep"),
                    FitnessHudRow("calendar", week.sleepHours?.let { String.format("%.1f h week total", it) } ?: "--", "7 days"),
                    FitnessHudRow("steps", "Steps ${steps(today.steps)}", "today"),
                    FitnessHudRow("active", kcal(today.activeKcal), "active"),
                )
            }
        }
        return GlassesHud.FitnessSummary(
            page = page,
            title = "Health",
            subtitle = subtitle,
            heroValue = heroValue,
            heroLabel = heroLabel,
            rows = rows,
            statusLine = status,
            actions = FitnessHudActions(
                onPageChanged = onPageChanged,
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
            ),
        )
    }

    private fun formatSteps(n: Long): String = when {
        n >= 10_000 -> String.format("%.1fk", n / 1000.0)
        else -> n.toString()
    }
}
