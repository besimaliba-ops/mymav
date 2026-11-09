package com.mymav.glasses.api

/** Voice control of [MymavMemory]: remember, list, forget. Only explicit "remember ..." saves anything. */
internal object AssistantMemory {
    private val SAVE = Regex("^(?:please )?(?:remember|keep in mind|don't forget|do not forget)(?: that)? (.+)$", RegexOption.IGNORE_CASE)
    private val LIST = Regex("what (?:do you|have you) (?:remember|remembered|know|saved)(?: about me)?|what do you know about me|show (?:me )?(?:my )?(?:memory|memories)|list (?:my )?memories")
    private val FORGET = Regex("^(?:please )?(?:forget|delete|remove) (?:that |about |my memory (?:of|about) )?(.+)$", RegexOption.IGNORE_CASE)
    private val FORGET_ALL = Regex("^(?:please )?(?:forget everything|clear (?:my |your )?memory|delete (?:all )?(?:my )?memor(?:y|ies)|forget everything about me)$", RegexOption.IGNORE_CASE)
    @Volatile private var pendingClear = 0L

    private fun out(t: String, ask: Boolean = true) =
        AssistantRouter.Outcome(t, AssistantLog.Kind.Local, emptyList(), if (ask) "ask" else "")

    fun handle(raw: String, cleaned: String): AssistantRouter.Outcome? {
        val t = raw.trim().removeSuffix(".").removeSuffix("?")
        if (pendingClear > System.currentTimeMillis()) {
            pendingClear = 0
            if (cleaned in setOf("yes", "yeah", "yes clear it", "yes forget everything", "confirm", "do it")) {
                val n = MymavMemory.all().size; MymavMemory.clear(); return out("Forgot all $n saved memories.")
            }
            if (cleaned in setOf("no", "cancel", "never mind", "nevermind")) return out("Okay, I kept your memories.")
        }
        if (FORGET_ALL.matches(cleaned)) {
            val n = MymavMemory.all().size
            if (n == 0) return out("I have nothing saved to forget.")
            pendingClear = System.currentTimeMillis() + 120_000L
            return out("Forget all $n saved memories? Say yes to clear them, or no to keep them.")
        }
        if (LIST.containsMatchIn(cleaned)) {
            val l = MymavMemory.all()
            val off = if (MymavMemory.enabled()) "" else "\n(Memory is switched off, so I'm not using these.)"
            return if (l.isEmpty()) out("I haven't been asked to remember anything yet. Say: remember I'm vegetarian.")
            else out("I remember:\n" + l.take(8).joinToString("\n") { "- " + it.text } + off)
        }
        SAVE.find(t)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.length < 3 || Regex("^(to|the)\\b").containsMatchIn(what.lowercase())) return null  // "remember to call mum" is a task, not a preference
            if (!MymavMemory.enabled()) return out("Memory is switched off, so I didn't save that. Turn it on in MyMav Settings, Memory.", false)
            return if (MymavMemory.add(what)) out("Okay, I'll remember: $what")
            else out("I already remember that.")
        }
        FORGET.find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            val hits = MymavMemory.matching(q)
            if (hits.isEmpty()) return null  // may be about something else entirely ("forget it")
            if (hits.size > 1) return out("That matches ${hits.size} memories: " + hits.take(3).joinToString("; ") { it.text } + ". Say which one, or delete it in Settings.")
            MymavMemory.delete(hits[0].id)
            return out("Forgot: ${hits[0].text}")
        }
        return null
    }
}
