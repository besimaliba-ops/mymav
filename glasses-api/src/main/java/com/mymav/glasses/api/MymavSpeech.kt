package com.mymav.glasses.api

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Optional read-aloud for answers, through the phone's text-to-speech engine (plays on the phone or whatever audio
 * output it is connected to). Off unless the user turns it on in Settings or asks "read that aloud".
 */
object MymavSpeech {
    private const val PREFS = "mymav_speech"
    @Volatile private var ctx: Context? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: String? = null

    fun install(context: Context) { ctx = context.applicationContext }

    private fun prefs() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoRead(): Boolean = prefs()?.getBoolean("auto", false) ?: false
    fun setAutoRead(on: Boolean) { prefs()?.edit()?.putBoolean("auto", on)?.apply(); if (!on) stop() }
    fun available(): Boolean = !failed

    /** Drops the trailing source/"how this was answered" line so only the answer itself is spoken. */
    fun spokenText(answer: String): String {
        val lines = answer.lines()
        val last = lines.lastOrNull().orEmpty()
        val body = if (lines.size > 1 && Regex("^(Web:|Data:|AI answer|AI translation|AI summary|Calculated on|From your|ESPN )").containsMatchIn(last)) lines.dropLast(1) else lines
        return body.joinToString(". ") { it.trim().removePrefix("- ") }.replace(Regex("\\s+"), " ").trim()
    }

    /** Returns false when speech can't start (no engine). Speaks asynchronously. */
    fun speak(text: String): Boolean {
        val c = ctx ?: return false
        val t = spokenText(text).take(1500)
        if (t.isBlank()) return true
        synchronized(this) {
            if (failed) return false
            val engine = tts
            if (engine == null) {
                pending = t
                tts = TextToSpeech(c) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
                        tts?.language = Locale.getDefault()
                        pending?.let { p -> tts?.speak(p, TextToSpeech.QUEUE_FLUSH, null, "mymav") }
                        pending = null
                    } else { failed = true; tts = null }
                }
            } else if (ready) engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "mymav") else pending = t
        }
        return true
    }

    fun stop() { synchronized(this) { pending = null; if (ready) tts?.stop() } }
}
