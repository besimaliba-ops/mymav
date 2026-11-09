package com.mymav.glasses.api

import android.content.Context

/**
 * Word suggestions for the shared glasses keyboard — works like a phone keyboard:
 *
 *  - while typing a word: completions ranked by everyday frequency, boosted by
 *    the words you actually send (learned on-device), with one-typo correction
 *    when nothing starts with what you typed;
 *  - after a space: next-word predictions ("i" → am / will / have …);
 *  - capitalisation and common contractions are fixed up (im → I'm, dont → don't).
 */
object GazeKeyboardSuggestions {
    /** ~800 common English words, roughly most-frequent first. */
    private val ranked: List<String> = """
        the to and a i you it of in is that for me my on have this be with your so just we not was but are do at can
        all if no what get like out up know about will one there they he she her him his it's i'm don't how good now
        love time when see go going back from too by more yeah yes okay ok thanks thank really think want well day
        then today right here some need got am or as an been had has did would could should come make take say said
        tell let lol oh hey hi hello sure much very also only still even way people new our us them their because why
        who where which these those than other into over after before off last next first never always again here's
        that's what's can't won't didn't isn't i'll i've you're we're they're it'll let's there's would've home work
        night morning tomorrow tonight week weekend month year later soon yet ago already early late long little big
        great nice cool awesome amazing fine happy sorry please sad tired busy free ready done sick fun funny crazy
        hard easy best better bad worse worst sweet cute beautiful perfect true real same different call text message
        phone send sent meet meeting lunch dinner breakfast coffee tea food eat drink water beer wine party birthday
        game movie music song play watch read write talk speak listen look find give keep leave put run walk drive
        ride fly sleep wake start stop help try wait miss feel hope wish believe remember forget understand mean
        thought made went came gave took saw told asked called left found felt kept car bus train plane flight ticket
        hotel trip holiday travel road street city town house room office school class job money pay bill shop store
        buy bought sell price cost cash card bank mum mom dad mother father brother sister family friend friends baby
        kids kid son daughter wife husband girlfriend boyfriend guy guys girl man woman boy team boss everyone someone
        anyone nobody everything something anything nothing monday tuesday wednesday thursday friday saturday sunday
        january february march april may june july august september october november december two three four five six
        seven eight nine ten twenty hundred thousand minute minutes hour hours second seconds moment times half gym
        running workout training weights steps miles km health heart doctor hospital glasses mymav notify spotify
        whatsapp instagram reply photo picture video camera where's who's how's alright brilliant cheers mate babe
        honey dear xx haha hahaha omg btw idk tbh brb almost around away down through under between while during
        without within against along across behind beside although unless until whether since though might must shall
        ought dont cant wont im ive youre thats whats lets above among another any anybody anyway apart ask asking
        begin being below besides both bring brought built care careful carry case catch caught change check choose
        clear close closed cold comes coming company complete course cover dark date dead deal decide deep definitely
        difficult direction doing door double dream dress each easily else end enjoy enough evening event ever every
        exactly example except excited expect experience explain eye eyes face fact fair fall far fast fat fear
        feeling few field fight fill final finally finish fire fit floor follow forward full future general gets
        getting glad goes gone gonna gotta grab green grow guess hair hand hands hang happen happened happening hate
        head hear heard high hit hold hot huge hungry hurry hurt idea important inside instead interesting invite join
        joke jump kind kinda knew known lady land large laugh learn least less life light line list live lives living
        lock lose lost lot lots loud lovely low luck lucky main major makes making many match matter maybe meal means
        meant middle mind mine mistake mobile most move moved name near nearly needed needs nervous news nope normal
        note notice number often old open order outside own page paid pain parents park part pass past person pick
        piece place plan plans plus point pop post power pretty probably problem promise pull push question quick
        quickly quiet quite rain rather reach reason red relax rest return rich ring rock round rule safe save score
        seat seem seems seen sense serious set share short shot show shower side sign simple sing single sit size skin
        slow small smart smile snow soft sold sound south space special spend spent sport stand star stay stuff style
        sun super supposed surprise table taking talked taste teach terrible test thing things thinking till together
        top total touch tough towards tried trust truth turn ugly upset use used useful usual usually visit voice
        waiting wall wanna wanted warm wash waste wear weather weird welcome white whole win window winter wonder
        wonderful word words world worried worry worth wow wrong yesterday young yours yourself
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    private val rank: Map<String, Int> = ranked.withIndex().associate { it.value to it.index }

    private val fixups = mapOf(
        "i" to "I", "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
        "isnt" to "isn't", "doesnt" to "doesn't", "wasnt" to "wasn't", "arent" to "aren't",
        "youre" to "you're", "thats" to "that's", "whats" to "what's", "lets" to "let's",
        "theyre" to "they're", "were" to "we're", "theres" to "there's", "couldnt" to "couldn't",
        "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "havent" to "haven't",
    )

    private val nextWords: Map<String, List<String>> = mapOf(
        "" to listOf("I", "Thanks", "OK"),
        "i" to listOf("am", "will", "have"),
        "i'm" to listOf("on", "going", "not"),
        "you" to listOf("are", "can", "too"),
        "thank" to listOf("you", "u", "so"),
        "thanks" to listOf("for", "so", "mate"),
        "on" to listOf("my", "the", "it"),
        "my" to listOf("way", "phone", "friend"),
        "see" to listOf("you", "it", "u"),
        "how" to listOf("are", "is", "about"),
        "what" to listOf("time", "about", "is"),
        "are" to listOf("you", "we", "they"),
        "at" to listOf("the", "home", "work"),
        "in" to listOf("the", "a", "an"),
        "to" to listOf("the", "be", "go"),
        "will" to listOf("be", "do", "call"),
        "can" to listOf("you", "I", "we"),
        "call" to listOf("you", "me", "later"),
        "good" to listOf("morning", "night", "luck"),
        "love" to listOf("you", "it", "that"),
        "let" to listOf("me", "us", "you"),
        "let's" to listOf("go", "do", "meet"),
        "talk" to listOf("later", "soon", "to"),
        "be" to listOf("there", "back", "home"),
        "running" to listOf("late", "now", "a"),
        "sounds" to listOf("good", "great", "like"),
        "no" to listOf("problem", "worries", "way"),
        "of" to listOf("course", "the", "it"),
        "for" to listOf("the", "you", "a"),
        "the" to listOf("same", "best", "way"),
        "it" to listOf("is", "was", "will"),
        "it's" to listOf("ok", "fine", "a"),
        "that's" to listOf("great", "fine", "good"),
        "we" to listOf("are", "can", "will"),
        "do" to listOf("you", "it", "not"),
        "don't" to listOf("worry", "know", "forget"),
        "miss" to listOf("you", "it", "u"),
        "have" to listOf("a", "fun", "to"),
        "happy" to listOf("birthday", "to", "for"),
        "with" to listOf("you", "the", "me"),
        "just" to listOf("got", "now", "a"),
        "come" to listOf("home", "over", "on"),
        "going" to listOf("to", "home", "out"),
        "get" to listOf("home", "back", "a"),
        "need" to listOf("to", "a", "help"),
        "want" to listOf("to", "a", "some"),
    )

    private const val PREFS = "mymav_keyboard"
    private const val KEY_LEARNED = "learned"
    private val learned = HashMap<String, Int>()
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEARNED, "").orEmpty()
        synchronized(learned) {
            learned.clear()
            raw.split('\n').forEach { line ->
                val i = line.lastIndexOf(' ')
                if (i > 0) line.substring(i + 1).toIntOrNull()?.let { learned[line.substring(0, i)] = it }
            }
        }
    }

    /** Remember the words of a sent message so they rank higher next time. */
    fun learn(text: String) {
        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.length >= 2 }
        if (words.isEmpty()) return
        synchronized(learned) {
            words.forEach { learned[it] = (learned[it] ?: 0) + 1 }
            if (learned.size > 600) {
                learned.entries.sortedBy { it.value }.take(learned.size - 600).forEach { learned.remove(it.key) }
            }
        }
        val ctx = appContext ?: return
        val dump = synchronized(learned) { learned.entries.joinToString("\n") { "${it.key} ${it.value}" } }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LEARNED, dump).apply()
    }

    private fun score(word: String): Int {
        val r = rank[word] ?: 2_000
        val l = synchronized(learned) { learned[word] ?: 0 }
        return r - l * 400
    }

    fun suggest(buffer: String, limit: Int = 3): List<String> {
        val endsWithSpace = buffer.isEmpty() || buffer.last() == ' '
        val sentenceStart = buffer.isBlank() || buffer.trimEnd().lastOrNull() in setOf('.', '!', '?')
        if (endsWithSpace) {
            val prev = buffer.trim().lowercase().substringAfterLast(' ').trim('.', ',', '!', '?')
            val out = (nextWords[if (sentenceStart) "" else prev] ?: nextWords.getValue(""))
            return out.map { if (sentenceStart) cap(it) else it }.take(limit)
        }
        val token = buffer.substringAfterLast(' ')
        val lower = token.lowercase()
        if (lower.isEmpty() || !lower.first().isLetter()) return emptyList()
        val upperFirst = token.first().isUpperCase()
        val pool = (ranked + synchronized(learned) { learned.keys.toList() }).distinct()
        val out = LinkedHashSet<String>()
        fixups[lower]?.let { out += it }
        pool.filter { it.startsWith(lower) && it != lower }
            .sortedBy { score(it) }
            .take(limit + 2)
            .forEach { out += (fixups[it] ?: it) }
        if (out.size < limit) {
            pool.filter { it.length >= 3 && oneEditAway(lower, it) }
                .sortedBy { score(it) }
                .take(limit)
                .forEach { out += (fixups[it] ?: it) }
        }
        return out.filter { it.lowercase() != lower || fixups.containsKey(lower) }
            .map { if (upperFirst || sentenceStart) cap(it) else it }
            .distinct()
            .take(limit)
    }

    /** Should the next letter be upper-case (start of text / sentence)? */
    fun autoCapNext(buffer: String): Boolean {
        if (buffer.isEmpty()) return true
        val t = buffer.trimEnd()
        return buffer.endsWith(' ') && (t.isEmpty() || t.last() in setOf('.', '!', '?'))
    }

    /** Replace the word being typed with [word] and add a space. */
    fun applySuggestion(buffer: String, word: String): String {
        val base = if (buffer.isEmpty() || buffer.last() == ' ') buffer else buffer.substringBeforeLast(' ', "").let {
            if (it.isEmpty()) "" else "$it "
        }
        return "$base$word "
    }

    private fun cap(w: String): String = w.replaceFirstChar { it.uppercaseChar() }

    private fun oneEditAway(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1 || a == b) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
