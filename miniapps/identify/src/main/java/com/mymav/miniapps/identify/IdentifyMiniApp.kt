package com.mymav.miniapps.identify

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor

object IdentifyMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "identify",
        title = "Identify",
        subtitle = "Look · name · pictures in ~2s",
        route = "mini/identify",
        requiredCapabilities = listOf("camera", "vision", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        GlassesHomeController.markMiniAppOpen("identify")
        IdentifyEngine.enter(session)
    }

    override fun onLeave(session: GlassesSession) {
        // Do not clearHud — home board is already pushed; clearing empties the sim draw queue.
        IdentifyEngine.cancel()
    }
}
