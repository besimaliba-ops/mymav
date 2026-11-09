package com.mymav.glasses.api

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Routes a spoken or typed request to a real provider before the general chat model sees it:
 * unit/currency conversion, translation, headlines, and current-information search.
 * Returns false when the request is not one of these (the caller then uses the normal assistant path).
 * Every outcome says how it was produced ([AssistantLog.Kind]) and which sources back it.
 */
object AssistantRouter {
    class Outcome(
        val text: String,
        val kind: AssistantLog.Kind,
        val sources: List<AssistantLog.Source>,
        val note: String,
    ) {
        /** Last line on the glasses: where the answer came from. Empty for errors. */
        val footer: String
            get() = if (note == "ask") "" else when (kind) {
                AssistantLog.Kind.Web -> if (sources.isEmpty()) "Web search - live, no page links" else "Web: " + sources.map { it.name.substringBefore(':').take(24) }.distinct().take(3).joinToString(", ")
                AssistantLog.Kind.Data ->
                    if (sources.firstOrNull()?.url.isNullOrBlank() && note.isNotBlank()) note
                    else "Data: " + sources.firstOrNull()?.name?.substringBefore('(')?.trim().orEmpty()
                AssistantLog.Kind.Ai -> if (note.startsWith("AI summary") || note.startsWith("Cards made") || note.contains("Image from") || note.startsWith("Used ")) note else "AI answer - not searched"
                AssistantLog.Kind.Local -> "Calculated on phone"
                AssistantLog.Kind.Error -> ""
            }
    }

    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    private val LANGS = setOf(
        "turkish", "spanish", "french", "german", "italian", "portuguese", "dutch", "polish", "russian", "arabic",
        "japanese", "chinese", "mandarin", "cantonese", "korean", "hindi", "urdu", "greek", "swedish", "norwegian",
        "danish", "finnish", "czech", "hungarian", "romanian", "ukrainian", "hebrew", "persian", "farsi", "thai",
        "vietnamese", "indonesian", "malay", "swahili", "welsh", "irish", "latin", "english", "bengali", "punjabi",
    )
    private val HOW_SAY = Regex("^how (?:do|would|can|should) (?:i|you|we) say (.+?) in ([a-z]+)$")
    private val TRANSLATE = Regex("^translate (.+?) (?:in)?to ([a-z]+)$")
    private val WHAT_IS_IN = Regex("^what(?:'s| is| does) (.+?) (?:in|mean in) ([a-z]+)$")

    private fun clean(s: String) = s.trim().trim('"', '\'', '‘', '’', '“', '”').trim()

    private fun translationRequest(cleaned: String): Pair<String, String>? {
        val t = cleaned.trim().removeSuffix("?").removeSuffix(".").trim()
        for (r in listOf(HOW_SAY, TRANSLATE, WHAT_IS_IN)) {
            val m = r.find(t) ?: continue
            val lang = m.groupValues[2]
            if (lang in LANGS) return clean(m.groupValues[1]) to lang
        }
        return null
    }

    private val MOODS = setOf("upbeat", "chill", "relaxing", "calm", "happy", "sad", "energetic", "focus", "workout", "party", "sleep", "romantic", "acoustic", "jazz", "classical", "lofi", "lo-fi")

    /** (command, query) for Spotify voice requests, or null. Whole-phrase matches so ordinary sentences are not hijacked. */
    private fun spotifyRequest(cleaned: String): Pair<String, String>? {
        val t = cleaned.trim().removeSuffix(".").removeSuffix("?").removeSuffix("!").replace(Regex("\\s+on spotify$"), "").trim()
        val onSpotify = cleaned.contains("spotify")
        return when {
            Regex("^(what('s| is| was)?|whats) (currently |now )?playing( now| right now)?( on spotify)?$").matches(t) ||
                Regex("^(what('s| is)?|whats) (this|the current) (track|song)( on spotify)?$").matches(t) && onSpotify ||
                Regex("^(what|which) (song|track) is (this|playing)( on spotify)?$").matches(t) && onSpotify ||
                t == "current track" || t == "what am i listening to" -> "current" to ""
            t in setOf("pause", "pause music", "pause the music", "pause spotify", "stop the music", "stop music", "stop spotify") -> "pause" to ""
            t in setOf("resume", "resume music", "resume the music", "resume spotify", "continue music", "play music", "play spotify", "unpause", "play") -> "resume" to ""
            t in setOf("skip", "skip song", "skip track", "skip this song", "next", "next song", "next track", "play next song") -> "next" to ""
            t in setOf("previous", "previous song", "previous track", "go back a song", "last song", "play previous song") -> "previous" to ""
            Regex("^play (?:me )?(?:something |some |a )?(.+)$").find(t)?.let { m -> m.groupValues[1].trim() in MOODS || m.groupValues[1].trim().removeSuffix(" music") in MOODS } == true -> {
                "mood" to Regex("^play (?:me )?(?:something |some |a )?(.+)$").find(t)!!.groupValues[1].removeSuffix(" music").trim() + " music"
            }
            Regex("^play (?:the )?(?:song |track )?(.+)$").find(t) != null && (onSpotify || Regex("^play (?:the )?(?:song |track )").containsMatchIn(t)) ->
                "search" to Regex("^play (?:the )?(?:song |track )?(.+)$").find(t)!!.groupValues[1].trim()
            else -> null
        }
    }

    /** Calendar conversation first (so "yes" confirms a pending event), then optionally everything else. */
    fun route(raw: String, cleaned: String, status: (String) -> Unit, deliver: (Outcome) -> Unit, calendarOnly: Boolean = false): Boolean {
        fun post(o: Outcome) = main.post { deliver(o) }

        if (AssistantNav.hasPending() && AssistantNav.continuePending(cleaned) { post(it) }) return true
        if (AssistantLearning.hasSession() && AssistantLearning.continueSession(cleaned) { post(it) }) return true
        if (AssistantMessages.hasPending() && AssistantMessages.continuePending(cleaned) { post(it) }) return true
        if (AssistantCalendar.hasPending() && AssistantCalendar.continuePending(cleaned) { post(it) }) return true
        AssistantMessages.parse(raw)?.let { p -> AssistantCalendar.clear(); AssistantMessages.start(p) { post(it) }; return true }
        if (AssistantCalendar.isCreate(cleaned)) { AssistantMessages.clear(); AssistantCalendar.start(cleaned) { post(it) }; return true }
        if (AssistantCalendar.isRead(cleaned)) { io.execute { AssistantCalendar.read(cleaned) { post(it) } }; return true }
        AssistantMemory.handle(raw, cleaned)?.let { o -> post(o); return true }
        if (MymavCaptions.isStop(cleaned)) { post(Outcome(if (MymavCaptions.active) "Captions stopped." else "Captions weren't on.", AssistantLog.Kind.Local, emptyList(), "ask").also { MymavCaptions.stop(false) }); return true }
        MymavCaptions.parse(cleaned)?.let { (from, to) ->
            val why = MymavCaptions.start(from, to)
            if (why != null) post(Outcome(why, AssistantLog.Kind.Error, emptyList(), ""))
            return true
        }
        if (AssistantLearning.isStart(cleaned)) { io.execute { AssistantLearning.start(cleaned) { post(it) } }; return true }
        if (AssistantMeeting.isRequest(cleaned)) { io.execute { AssistantMeeting.run(cleaned) { post(it) } }; return true }
        if (AssistantNav.isRequest(cleaned)) { io.execute { AssistantNav.run(raw, cleaned, status) { post(it) } }; return true }
        if (AssistantHome.isRequest(cleaned)) { post(AssistantHome.outcome()); return true }
        if (AssistantCamera.isRequest(cleaned)) { io.execute { AssistantCamera.run(raw) { post(it) } }; return true }
        if (AssistantNotes.isRequest(cleaned)) { io.execute { AssistantNotes.run(cleaned) { post(it) } }; return true }
        if (calendarOnly) return false

        spotifyRequest(cleaned)?.let { (cmd, query) ->
            val bridge = MymavActions.spotify
            if (bridge == null || !bridge.connected()) {
                deliver(Outcome("Spotify isn't connected. Open Apps > Spotify in MyMav and sign in.", AssistantLog.Kind.Error, emptyList(), ""))
                return true
            }
            status(if (cmd == "current") "Checking Spotify..." else "Asking Spotify...")
            bridge.run(cmd, query) { r ->
                post(
                    Outcome(
                        r.text, if (r.ok) AssistantLog.Kind.Data else AssistantLog.Kind.Error,
                        if (r.ok) listOf(AssistantLog.Source("Spotify", "https://open.spotify.com/")) else emptyList(), "",
                    ),
                )
            }
            return true
        }

        if (Regex("^(what song is this|what'?s this song|what song is playing around me|identify (this|the) (song|music)|name this (song|tune)|shazam.*)\\??$").matches(cleaned.trim())) {
            deliver(
                Outcome(
                    "Recognising a song from the microphone isn't connected yet: it needs an audio-recognition service. " +
                        "To hear what Spotify itself is playing, say: what's playing on Spotify.",
                    AssistantLog.Kind.Error, emptyList(), "",
                ),
            )
            return true
        }

        MymavConvert.parse(cleaned)?.let { req ->
            val local = MymavConvert.convertUnits(req)
            if (local != null) { deliver(Outcome(local.text, local.kind, local.sources, local.note)); return true }
            status("Checking the exchange rate...")
            io.execute {
                val r = runCatching { MymavConvert.convertCurrency(req) }
                    .getOrElse { MymavConvert.Result("The conversion failed: ${it.message?.take(80)}", AssistantLog.Kind.Error) }
                post(Outcome(r.text, r.kind, r.sources, r.note))
            }
            return true
        }

        translationRequest(cleaned)?.let { (phrase, lang) ->
            if (!MymavAi.isConfigured()) {
                deliver(Outcome("Translation needs a ChatGPT or Gemini key. Add one in MyMav Settings.", AssistantLog.Kind.Error, emptyList(), ""))
                return true
            }
            status("Translating...")
            val system = "You are a translator. Output exactly two lines and nothing else. " +
                "Line 1 starts with 'T: ' then the translation into ${lang.replaceFirstChar { it.uppercase() }} in its normal script. " +
                "Line 2 starts with 'P: ' then a simple English-letter pronunciation guide."
            MymavAi.generate(system, phrase, 120, 300) { out ->
                val t = out?.lines()?.firstOrNull { it.trim().startsWith("T:") }?.substringAfter("T:")?.trim()
                val p = out?.lines()?.firstOrNull { it.trim().startsWith("P:") }?.substringAfter("P:")?.trim()
                deliver(
                    if (t.isNullOrBlank()) Outcome("I couldn't translate that right now.", AssistantLog.Kind.Error, emptyList(), "")
                    else Outcome(
                        "\"$phrase\" in ${lang.replaceFirstChar { it.uppercase() }}:\n$t" + if (p.isNullOrBlank()) "" else "\nSay it: $p",
                        AssistantLog.Kind.Ai, emptyList(), "AI translation - check important phrases with a native speaker",
                    ),
                )
            }
            return true
        }

        if (MymavSearch.isHeadlineRequest(cleaned)) {
            status("Searching the news...")
            io.execute {
                val r = runCatching { MymavSearch.headlines(MymavSearch.topicOf(cleaned)) }
                    .getOrElse { MymavSearch.Result("I couldn't load the news: ${it.message?.take(80)}", AssistantLog.Kind.Error) }
                post(Outcome(r.text, r.kind, r.sources, r.note))
            }
            return true
        }

        if (MymavScores.isRequest(cleaned) || MymavSearch.looksCurrent(cleaned)) {
            val scores = MymavScores.isRequest(cleaned)
            status(if (scores) "Checking live scores..." else "Searching the web...")
            val turns = AssistantLog.recentTurns(4)
            io.execute {
                if (scores) {
                    val o = runCatching { MymavScores.fetch(cleaned) }.getOrNull()
                    if (o != null) { post(o); return@execute }
                    status("Searching the web...")
                }
                val r = runCatching { MymavSearch.grounded(raw, turns) }
                    .getOrElse { MymavSearch.Result("The search failed: ${it.message?.take(80)}", AssistantLog.Kind.Error) }
                post(Outcome(r.text, r.kind, r.sources, r.note))
            }
            return true
        }
        return false
    }
}
