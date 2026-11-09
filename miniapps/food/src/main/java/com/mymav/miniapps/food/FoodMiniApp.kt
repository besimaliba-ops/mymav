package com.mymav.miniapps.food

import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor

object FoodMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "food",
        title = "Food",
        subtitle = "Meal macros & product health on glass",
        route = "mini/food",
        requiredCapabilities = listOf("camera", "vision", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        GlassesHomeController.markMiniAppOpen("food")
        FoodScanEngine.reset()
        FoodScanEngine.pushReadyHud(session)
    }

    override fun onLeave(session: GlassesSession) {
        FoodScanEngine.cancel()
        FoodScanEngine.reset()
    }
}
