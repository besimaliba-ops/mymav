package com.mymav.miniapps.memorysight

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.miniAppHomeActions

object MemorySightMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "memorysight",
        title = "Memory Sight",
        subtitle = "Where your belongings were today",
        route = "mini/memorysight",
        requiredCapabilities = listOf("camera", "vision", "hud", "local-store"),
    )

    @Volatile
    var store: MemorySightStore? = null

    @Volatile
    private var appContext: Context? = null

    fun ensureStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (store == null) {
            store = MemorySightStore(app)
        }
        MemorySightBackgroundEngine.seedIfNeeded(app)
    }

    private fun storeOrNull(): MemorySightStore? {
        store?.let { return it }
        val ctx = appContext ?: return null
        return MemorySightStore(ctx).also { store = it }
    }

    override fun onEnter(session: GlassesSession) {
        appContext?.let { ensureStore(it) }
        GlassesHomeController.markMiniAppOpen("memorysight")
        val count = storeOrNull()?.trackedCount() ?: 3
        session.replaceHud(
            GlassesHud.AppEnter(
                appId = "memorysight",
                title = "Memory Sight",
                subtitle = "Belongings + places through the day",
                accent = HudAccentTone.Purple,
                bullets = listOf(
                    "AI camera remembers keys, wallet, gear",
                    "Timeline with time · place · context",
                    "Runs in background while MyMav is active",
                ),
                metric = count.toString(),
                metricLabel = "items",
                actions = miniAppHomeActions(),
            ),
        )
    }

    override fun onLeave(session: GlassesSession) {
        // Home board replaces HUD on return.
    }

    fun pushTimelineHud(session: GlassesSession) {
        val st = storeOrNull()
        val lines = st?.hudLines() ?: emptyList()
        session.replaceHud(
            GlassesHud.MemoryTimeline(
                title = "Memory Sight",
                entries = lines,
                statusLine = "Today · ${lines.size} sightings",
                actions = miniAppHomeActions(),
            ),
        )
    }
}
