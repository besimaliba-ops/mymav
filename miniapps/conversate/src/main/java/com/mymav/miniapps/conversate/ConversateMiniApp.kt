package com.mymav.miniapps.conversate

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object ConversateMiniApp : MiniApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    /** Call once from app startup so glasses enter can build the engine without a Compose LocalContext. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    override val descriptor = MiniAppDescriptor(
        id = "conversate",
        title = "Conversate",
        subtitle = "Meeting cues, captions, gaze",
        route = "mini/conversate",
        requiredCapabilities = listOf("mic", "hud", "gaze", "cloud-ai"),
    )

    override fun onEnter(session: GlassesSession) {
        GlassesHomeController.markMiniAppOpen("conversate")
        val enter = ConversateHudBuilder.premiumEnter()
        session.replaceHud(enter)
        GlassesHomeController.rememberAppHud(enter)
        val ctx = appContext
        if (ctx != null) {
            ConversateSessionHolder.premiumEnter(
                session = session,
                context = ctx,
                scope = scope,
                mode = MeetingMode.General,
                prepNotes = "",
                briefing = "",
                onState = {},
            )
        }
    }

    override fun onLeave(session: GlassesSession) {
        // HOME while recording keeps the mic; the home widget shows the live state and reopening offers Stop.
        if (ConversateSessionHolder.isBusy()) return
        ConversateSessionHolder.release()
    }
}
