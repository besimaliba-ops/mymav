package com.mymav.miniapps.gallery

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.miniAppHomeActions

object GalleryMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "gallery",
        title = "Gallery",
        subtitle = "Browse phone photos & videos on glass",
        route = "mini/gallery",
        requiredCapabilities = listOf("read-media", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        session.replaceHud(
            GlassesHud.AppEnter(
                appId = "gallery",
                title = "Gallery",
                subtitle = "Photos & videos on glass",
                accent = HudAccentTone.Purple,
                bullets = listOf(
                    "READ_MEDIA_* / picker when granted",
                    "Demo thumbnails until library open",
                    "Gaze through album on HUD",
                ),
                metric = "12",
                actions = miniAppHomeActions(),
                metricLabel = "items",
            ),
        )
        GlassesHomeController.markMiniAppOpen("gallery")
    }

    override fun onLeave(session: GlassesSession) {
        // Do not clearHud — home board is already pushed.
    }
}
