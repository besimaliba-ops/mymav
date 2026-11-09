package com.mymav.glasses.api

/**
 * Lightweight on-device assistant for the home mascot.
 * Parses "hey MyMav" follow-ups: open apps, short Q&A, picture requests.
 */
object MyMavAssistant {

    data class Reply(
        val speech: String,
        /** Mini-app id to open after paging home, if any. */
        val openAppId: String? = null,
        /** Optional image caption when showing a picture stub. */
        val showPictureQuery: String? = null,
    )

    fun handle(raw: String): Reply {
        val text = raw.trim().lowercase()
            .removePrefix("hey mymav")
            .removePrefix("hey my mav")
            .removePrefix("hi mymav")
            .trim()
            .trimStart(',', ' ', '.', '!')
            .trim()
        if (text.isBlank()) {
            return Reply("How can I help?")
        }
        openAppId(text)?.let { id ->
            val title = HomeBoardCatalog.widgets.firstOrNull { it.id == id }?.title ?: id
            return Reply("Opening $title…", openAppId = id)
        }
        if (text.startsWith("show ") || text.startsWith("picture ") ||
            text.startsWith("show me ") || text.contains("picture of") ||
            text.contains("photo of") || text.contains("image of")
        ) {
            val q = text
                .replace("show me a picture of", "")
                .replace("show me pictures of", "")
                .replace("show me an image of", "")
                .replace("show me", "")
                .replace("picture of", "")
                .replace("photo of", "")
                .replace("image of", "")
                .replace("show", "")
                .replace("pictures", "")
                .replace("picture", "")
                .trim()
                .ifBlank { "that" }
            return Reply("Looking up pictures of $q…", showPictureQuery = q)
        }
        return Reply(answerQuestion(text))
    }

    private fun openAppId(text: String): String? {
        val aliases = listOf(
            "spotify" to "spotify",
            "music" to "spotify",
            "socials" to "notify",
            "notify" to "notify",
            "whatsapp" to "notify",
            "instagram" to "notify",
            "health" to "fitness",
            "fitness" to "fitness",
            "workouts" to "workouts",
            "workout" to "workouts",
            "running" to "workouts",
            "recipe book" to "recipes",
            "recipes" to "recipes",
            "notepad" to "notepad",
            "notes" to "notepad",
            "tasks" to "tasks",
            "task" to "tasks",
            "gallery" to "gallery",
            "mirror" to "mirror",
            "identify" to "identify",
            "food" to "food",
            "shopping list" to "shopping",
            "shop" to "shop",
            "football" to "football",
            "conversate" to "conversate",
        )
        val open = text.contains("open") || text.contains("launch") || text.contains("start") ||
            text.startsWith("go to")
        if (!open && !aliases.any { text == it.first }) return null
        for ((alias, id) in aliases) {
            if (text.contains(alias)) return id
        }
        return null
    }

    private fun answerQuestion(text: String): String {
        return when {
            text.contains("who are you") || text.contains("what are you") ->
                "I'm MyMav — your glasses AI. Gaze me 1s or say hey MyMav."
            text.contains("time") && !text.contains("date") ->
                "Check the top bar for the current time on your home board."
            MyMavWeather.isDateQuery(text) ->
                MyMavWeather.todayDateSpeech()
            MyMavWeather.isWeatherQuery(text) ->
                // GlassesHomeController fetches live weather asynchronously.
                "Checking the weather…"
            text.contains("steps") || text.contains("health") ->
                "Open Health for today's steps, rings, workouts and sleep."
            text.contains("help") ->
                "Try: what's the weather · what's today's date · open Health · open Spotify."
            text.endsWith("?") || text.startsWith("what") || text.startsWith("how") ||
                text.startsWith("why") || text.startsWith("who") || text.startsWith("when") ||
                text.startsWith("where") ->
                "Good question. On-device answers are limited for now — I'll get smarter online soon. " +
                    "For apps, say open Health or open Spotify."
            else ->
                "Got it. Say open <app>, ask a question, or show me a picture of…"
        }
    }
}
