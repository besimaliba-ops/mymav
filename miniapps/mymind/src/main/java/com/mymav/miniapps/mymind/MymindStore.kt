package com.mymav.miniapps.mymind

import android.content.Context
import com.mymav.glasses.api.MymindTaskPage

class MymindStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadTasks(): List<MymindTask> {
        val list = MymindTask.listFromJson(prefs.getString(KEY_TASKS, null))
        return if (list.isEmpty()) seed() else list
    }

    fun tasksFor(page: MymindTaskPage): List<MymindTask> =
        loadTasks().filter { it.page == page && !it.done }

    fun memorySummary(): String =
        prefs.getString(KEY_SUMMARY, null) ?: DEFAULT_SUMMARY

    fun people(): List<MymindPerson> = DEFAULT_PEOPLE

    fun conversationSnippet(): String =
        prefs.getString(KEY_SNIPPET, null) ?: DEFAULT_SNIPPET

    private fun saveTasks(tasks: List<MymindTask>) {
        prefs.edit().putString(KEY_TASKS, MymindTask.listToJson(tasks)).apply()
    }

    private fun seed(): List<MymindTask> {
        val seeded = listOf(
            MymindTask(title = "Reply to Alex re: sprint demo", page = MymindTaskPage.Daily),
            MymindTask(title = "Book flights for Lisbon offsite", page = MymindTaskPage.Weekly),
            MymindTask(title = "Renew domain for side project", page = MymindTaskPage.Monthly),
            MymindTask(title = "Send grandma photos from brunch", page = MymindTaskPage.Daily),
        )
        prefs.edit()
            .putString(KEY_TASKS, MymindTask.listToJson(seeded))
            .putString(KEY_SUMMARY, DEFAULT_SUMMARY)
            .putString(KEY_SNIPPET, DEFAULT_SNIPPET)
            .apply()
        return seeded
    }

    fun openCount(page: MymindTaskPage): Int = tasksFor(page).size

    companion object {
        private const val PREFS = "mymav_mymind"
        private const val KEY_TASKS = "tasks_json"
        private const val KEY_SUMMARY = "memory_summary"
        private const val KEY_SNIPPET = "conversation_snippet"

        private const val DEFAULT_SUMMARY =
            "Lunch with Alex & Priya — agreed to ship glasses demo Friday; Priya owns QA checklist."

        private const val DEFAULT_SNIPPET =
            "Alex: \"Can we show Memory Sight on the board?\" · You: \"Yes, mock timeline is enough for Friday.\""

        private val DEFAULT_PEOPLE = listOf(
            MymindPerson(name = "Alex", note = "Sprint lead"),
            MymindPerson(name = "Priya", note = "QA"),
        )
    }
}
