package com.mymav.miniapps.mirror

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.miniAppHomeActions

object MirrorMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "mirror",
        title = "Mirror",
        subtitle = "Cast phone screen onto glasses",
        route = "mini/mirror",
        requiredCapabilities = listOf("media-projection", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        session.replaceHud(
            GlassesHud.AppEnter(
                appId = "mirror",
                title = "Mirror",
                subtitle = "Phone → glasses cast preview",
                accent = HudAccentTone.Blue,
                bullets = listOf(
                    "MediaProjection cast when permitted",
                    "Demo frame on lens if cast unavailable",
                    "Soft glass phone + HUD preview",
                ),
                metric = "CAST",
                actions = miniAppHomeActions(),
                metricLabel = "live",
            ),
        )
        GlassesHomeController.markMiniAppOpen("mirror")
    }

    override fun onLeave(session: GlassesSession) {
        // Do not clearHud — home board is already pushed.
    }
}
