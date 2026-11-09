package com.mymav.miniapps.shop

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor

object ShopMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "shop",
        title = "Shop",
        subtitle = "Scan products on glass — no checkout",
        route = "mini/shop",
        requiredCapabilities = listOf("camera", "vision", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        GlassesHomeController.markMiniAppOpen("shop")
        ShopScanEngine.reset()
        if (!ShopScanEngine.consumeSeed(session)) ShopScanEngine.pushReadyHud(session)
    }

    override fun onLeave(session: GlassesSession) {
        ShopScanEngine.cancel()
        ShopScanEngine.reset()
    }
}
