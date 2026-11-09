package com.mymav.glasses.api

/**
 * Shared daily Health snapshot for the glasses Home widget and the phone Health screen.
 * Missing values stay unavailable (`null` / `--`) instead of a fabricated 0.
 */
data class HealthHeartSnapshot(
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val eatenKcal: Double? = null,
    val totalKcal: Double? = null,
    val stepGoal: Long? = null,
    val activeGoal: Double? = null,
    val eatenGoal: Double? = null,
    val freshness: String = "",
    val kind: Kind = Kind.None,
) {
    enum class Kind(val key: String) {
        Live("live"),
        Cached("cached"),
        Stale("stale"),
        None("none"),
    }

    val available: Boolean
        get() = kind != Kind.None && (steps != null || activeKcal != null || eatenKcal != null || totalKcal != null)

    fun stepsProgress(): Float? = ringProgress(steps?.toDouble(), stepGoal?.toDouble())
    fun activeProgress(): Float? = ringProgress(activeKcal, activeGoal)
    fun eatenProgress(): Float? = ringProgress(eatenKcal, eatenGoal)

    fun stepsLabel(): String = formatCount(steps)
    fun activeLabel(compact: Boolean = true): String = formatKcal(activeKcal, compact)
    fun eatenLabel(compact: Boolean = true): String = formatKcal(eatenKcal, compact)
    fun totalLabel(compact: Boolean = true): String = formatKcal(totalKcal, compact)

    fun freshnessLabel(): String = when (kind) {
        Kind.None -> ""
        Kind.Live -> if (freshness.isBlank()) "" else "Updated $freshness"
        Kind.Cached -> if (freshness.isBlank()) "Cached" else "Cached $freshness"
        Kind.Stale -> if (freshness.isBlank()) "Stale" else "Stale $freshness"
    }

    fun encode(): Triple<String, String, Pair<String, List<String>>> {
        val state = "${kind.key}:$freshness"
        val detail = listOf(num(activeKcal), num(eatenKcal), num(totalKcal)).joinToString("|")
        val goals = "g:${num(stepGoal)}|${num(activeGoal)}|${num(eatenGoal)}"
        return Triple(stepsLabel(), detail, state to listOf(goals))
    }

    companion object {
        fun ringProgress(value: Double?, goal: Double?): Float? {
            if (value == null || goal == null || goal <= 0.0) return null
            return (value / goal).toFloat().coerceAtLeast(0f)
        }

        fun quantize(progress: Float?): Int = when (progress) {
            null -> -1
            else -> (progress.coerceIn(0f, 1f) * 20f).toInt()
        }

        fun decode(
            metric: String,
            metricDetail: String,
            state: String,
            lines: List<String>,
            live: Boolean,
        ): HealthHeartSnapshot {
            val kindRaw = state.substringBefore(':', "")
            val freshness = state.substringAfter(':', "")
            val kind = Kind.entries.firstOrNull { it.key == kindRaw }
                ?: when {
                    !live -> Kind.None
                    kindRaw == "cached" -> Kind.Cached
                    else -> Kind.Live
                }
            val parts = metricDetail.split('|').map { it.trim() }
            val goals = lines.firstOrNull { it.startsWith("g:") }
                ?.removePrefix("g:")
                ?.split('|')
                .orEmpty()
            return HealthHeartSnapshot(
                steps = parseCount(metric),
                activeKcal = parseNum(parts.getOrNull(0)),
                eatenKcal = parseNum(parts.getOrNull(1)),
                totalKcal = parseNum(parts.getOrNull(2)),
                stepGoal = parseCount(goals.getOrNull(0)),
                activeGoal = parseNum(goals.getOrNull(1)),
                eatenGoal = parseNum(goals.getOrNull(2)),
                freshness = freshness,
                kind = kind,
            )
        }

        fun formatCount(n: Long?): String = when {
            n == null -> "--"
            n >= 10_000L -> String.format("%.1fk", n / 1000.0)
            else -> grouped(n.toInt())
        }

        fun formatKcal(v: Double?, compact: Boolean): String {
            if (v == null) return "--"
            val n = kotlin.math.round(v).toInt()
            return "${grouped(n)} kcal"
        }

        private fun grouped(n: Int): String = String.format(java.util.Locale.US, "%,d", n)

        private fun num(v: Long?): String = v?.toString() ?: "--"
        private fun num(v: Double?): String = v?.let { kotlin.math.round(it).toInt().toString() } ?: "--"

        private fun parseCount(raw: String?): Long? {
            val s = raw?.trim()?.lowercase()?.replace(",", "").orEmpty()
            if (s.isEmpty() || s == "--") return null
            if (s.endsWith("k")) return s.removeSuffix("k").toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            return s.toLongOrNull()
        }

        private fun parseNum(raw: String?): Double? {
            val s = raw?.trim()?.replace(",", "")?.removeSuffix(" kcal")?.removeSuffix("kcal").orEmpty()
            if (s.isEmpty() || s == "--") return null
            return s.toDoubleOrNull()
        }
    }
}

/** Phone Food notifies Health after a confirmed meal write — no second poller. */
object HealthNutritionBridge {
    @Volatile
    var onMealWritten: (() -> Unit)? = null

    fun notifyMealWritten() {
        onMealWritten?.invoke()
    }
}
