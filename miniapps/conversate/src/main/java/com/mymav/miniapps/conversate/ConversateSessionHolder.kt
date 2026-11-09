package com.mymav.miniapps.conversate

import android.content.Context
import com.mymav.glasses.api.GlassesSession
import kotlinx.coroutines.CoroutineScope

object ConversateSessionHolder {
    @Volatile
    private var engine: ConversationEngine? = null

    fun getOrCreate(
        context: Context,
        scope: CoroutineScope,
        onState: (ConversateSessionState) -> Unit,
    ): ConversationEngine {
        engine?.let { return it }
        return ConversationEngine(context.applicationContext, scope, onState).also { engine = it }
    }

    fun premiumEnter(
        session: GlassesSession,
        context: Context,
        scope: CoroutineScope,
        mode: MeetingMode,
        prepNotes: String,
        briefing: String,
        onState: (ConversateSessionState) -> Unit,
    ) {
        val eng = getOrCreate(context, scope, onState)
        eng.onPremiumEnter(session, mode, prepNotes, briefing)
    }

    fun isBusy(): Boolean = engine?.currentState()?.let { it.listening || it.starting || it.stopping } == true

    /** Debug builds only (see DebugHudReceiver `cvsay`): same path as a final speech result. */
    fun debugTranscript(text: String) {
        engine?.debugTranscript(text)
    }

    fun release() {
        engine?.release()
        engine = null
    }
}
