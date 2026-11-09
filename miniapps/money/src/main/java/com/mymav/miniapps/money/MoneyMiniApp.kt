package com.mymav.miniapps.money

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.miniAppHomeActions

object MoneyMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "money",
        title = "Money",
        subtitle = "Gaze a price · convert to home currency",
        route = "mini/money",
        requiredCapabilities = listOf("vision", "hud", "local-store"),
    )

    @Volatile
    var store: MoneyStore? = null

    @Volatile
    private var appContext: Context? = null

    fun ensureStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (store == null) {
            store = MoneyStore(app)
        }
        MoneyBackgroundEngine.seedIfNeeded(app)
    }

    private fun storeOrNull(): MoneyStore? {
        store?.let { return it }
        val ctx = appContext ?: return null
        return MoneyStore(ctx).also { store = it }
    }

    override fun onEnter(session: GlassesSession) {
        appContext?.let { ensureStore(it) }
        GlassesHomeController.markMiniAppOpen("money")
        val home = storeOrNull()?.homeCurrency ?: "GBP"
        session.replaceHud(
            GlassesHud.AppEnter(
                appId = "money",
                title = "Money",
                subtitle = "Gaze 1s at a price tag",
                accent = HudAccentTone.Green,
                bullets = listOf(
                    "Detect currency from symbol (€ $ £)",
                    "Convert to home currency ($home)",
                    "Override home currency in Settings",
                ),
                metric = home,
                metricLabel = "home",
                actions = miniAppHomeActions(),
            ),
        )
    }

    override fun onLeave(session: GlassesSession) {}

    fun pushQuoteHud(session: GlassesSession, dwellProgress: Float = 0f) {
        val st = storeOrNull()
        val quote = st?.lastQuote() ?: st?.seedDemoQuote()
        if (quote == null) return
        val rate = if (quote.foreignAmount > 0) quote.homeAmount / quote.foreignAmount else 0.0
        session.replaceHud(
            GlassesHud.MoneyQuote(
                foreignLabel = quote.foreignLabel(),
                homeLabel = "≈ ${quote.homeLabel()}",
                rateLabel = "1 ${quote.foreignCurrency} = ${"%.3f".format(rate)} ${quote.homeCurrency}",
                dwellProgress = dwellProgress,
                actions = miniAppHomeActions(),
            ),
        )
    }

    /** Demo: simulate 1s gaze on EUR €12.50 → GBP. */
    fun simulateGazeConvert(session: GlassesSession) {
        storeOrNull()?.convert("EUR", 12.50, "Shelf tag") ?: return
        pushQuoteHud(session, dwellProgress = 1f)
    }
}
