package com.mymav.miniapps.football

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object FootballMiniApp : MiniApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    override val descriptor = MiniAppDescriptor(
        id = "football",
        title = "Football",
        subtitle = "ESPN live scoreboard on glasses",
        route = "mini/football",
        requiredCapabilities = listOf("hud", "network"),
    )

    override fun onEnter(session: GlassesSession) {
        GlassesHomeController.markMiniAppOpen("football")
        val enter = FootballHudBuilder.premiumEnter()
        session.replaceHud(enter)
        GlassesHomeController.rememberAppHud(enter)
        FootballFeedMonitor.start(session, scope, appContext)
    }

    override fun onLeave(session: GlassesSession) {
        FootballFeedMonitor.stop()
    }
}
