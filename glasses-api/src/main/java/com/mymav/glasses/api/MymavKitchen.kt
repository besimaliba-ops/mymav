package com.mymav.glasses.api

/** Text helpers for cooking mode, shopping lists and recipe step timers. */
object MymavKitchen {
    /** Split a recipe method into short, readable steps (one screen each). */
    fun splitSteps(method: String, maxChars: Int = 170): List<String> {
        val cleaned = method.replace("\r", "")
            .replace(Regex("(?im)^\\s*(method|steps|instructions|directions)\\s*:?\\s*$"), "")
        var parts = cleaned.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.replace(Regex("^(step\\s*)?\\d+[.):]\\s*", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotEmpty() && !Regex("(?i)\\b(prep|cook|ready in|total time)\\s*:\\s*\\d").containsMatchIn(it) }
        // One big paragraph: split on sentences.
        if (parts.size <= 2 && cleaned.length > maxChars) {
            parts = cleaned.replace('\n', ' ').split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val out = ArrayList<String>()
        var cur = ""
        for (p in parts) {
            when {
                p.length > maxChars -> {
                    if (cur.isNotEmpty()) { out += cur; cur = "" }
                    // Break long sentence at clause boundaries / words.
                    var rest = p
                    while (rest.length > maxChars) {
                        val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
                        out += rest.take(cut).trim()
                        rest = rest.drop(cut).trim()
                    }
                    cur = rest
                }
                cur.isEmpty() -> cur = p
                cur.length + 1 + p.length <= maxChars && cur.length < maxChars / 2 -> cur = "$cur $p"
                else -> { out += cur; cur = p }
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** First cooking time mentioned in a step ("simmer for 20 minutes"), or null. Ranges use the upper bound. */
    fun stepTimerMs(step: String): Long? {
        val m = Regex(
            "(\\d+(?:\\.\\d+)?)(?:\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?))?\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b",
            RegexOption.IGNORE_CASE,
        ).find(step) ?: return null
        val n = (m.groupValues[2].ifBlank { m.groupValues[1] }).toDoubleOrNull() ?: return null
        val u = m.groupValues[3].lowercase()
        val ms = when {
            u.startsWith("h") -> n * 3_600_000
            u.startsWith("m") -> n * 60_000
            else -> n * 1000
        }.toLong()
        return ms.takeIf { it in 5_000L..(6 * 3_600_000L) }
    }

    fun shortDuration(ms: Long): String {
        val min = ms / 60_000L
        return when {
            ms >= 3_600_000L && min % 60 == 0L -> "${min / 60}h"
            ms >= 3_600_000L -> "${min / 60}h${min % 60}m"
            min >= 1 -> "${min}m"
            else -> "${ms / 1000}s"
        }
    }

    /** "milk, eggs and bread" -> [Milk, Eggs, Bread]. */
    fun splitItems(raw: String): List<String> =
        raw.replace(Regex("(?i)\\b(some|a few|a|an|the|my|more)\\s+"), "")
            .split(Regex(",|\\band\\b|\\&", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', ',', ' ') }
            .filter { it.isNotBlank() }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()
}
