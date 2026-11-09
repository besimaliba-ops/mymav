package com.mymav.miniapps.memorysight

import android.content.Context

class MemorySightStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<MemoryDetection> {
        val list = MemoryDetection.listFromJson(prefs.getString(KEY, null))
        return if (list.isEmpty()) seed() else list
    }

    fun save(items: List<MemoryDetection>) {
        prefs.edit().putString(KEY, MemoryDetection.listToJson(items)).apply()
    }

    fun append(detection: MemoryDetection): List<MemoryDetection> {
        val next = load() + detection
        save(next)
        return next
    }

    fun trackedCount(): Int = load().map { it.item.lowercase() }.distinct().size

    fun hudLines(limit: Int = 6): List<String> = load().takeLast(limit).map { it.hudLine() }

    private fun seed(): List<MemoryDetection> {
        val seeded = listOf(
            MemoryDetection(
                item = "Keys",
                place = "Kitchen counter",
                timeLabel = "08:12",
                context = "Seen leaving for commute",
            ),
            MemoryDetection(
                item = "Wallet",
                place = "Coat pocket",
                timeLabel = "09:40",
                context = "Coffee shop tap-to-pay",
            ),
            MemoryDetection(
                item = "Headphones",
                place = "Desk",
                timeLabel = "14:05",
                context = "Case open · charging",
            ),
        )
        save(seeded)
        return seeded
    }

    companion object {
        private const val PREFS = "mymav_memorysight"
        private const val KEY = "timeline_json"
    }
}
