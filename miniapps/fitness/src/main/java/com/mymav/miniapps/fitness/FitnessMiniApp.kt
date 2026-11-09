package com.mymav.miniapps.fitness

import android.content.Context
import com.mymav.glasses.api.FitnessHudPage
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HealthNutritionBridge
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FitnessMiniApp : MiniApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val descriptor = MiniAppDescriptor(
        id = "fitness",
        title = "Health",
        subtitle = "Steps, rings, workouts & sleep from Health Connect",
        route = "mini/fitness",
        requiredCapabilities = listOf("health-connect", "hud"),
    )

    @Volatile
    private var currentPage: FitnessHudPage = FitnessHudPage.Daily

    @Volatile
    private var pollerContext: Context? = null

    override fun onEnter(session: GlassesSession) {
        currentPage = FitnessHudPage.Daily
        val ctx = pollerContext
        if (ctx != null) {
            val store = HealthStatsStore.shared ?: HealthStatsStore(ctx).also { HealthStatsStore.shared = it }
            pushPage(session, currentPage, store)
            FitnessHealthPoller.start(ctx, session, scope)
        } else {
            pushPage(session, currentPage, HealthStatsStore.shared)
        }
        GlassesHomeController.markMiniAppOpen("fitness")
    }

    override fun onLeave(session: GlassesSession) {
        FitnessHealthPoller.stop()
    }

    /** Phone shell sets this before navigating so [onEnter] can poll Health Connect. */
    fun attachContext(context: Context) {
        pollerContext = context.applicationContext
        if (HealthStatsStore.shared == null) {
            HealthStatsStore.shared = HealthStatsStore(pollerContext!!)
        }
        HealthNutritionBridge.onMealWritten = { refreshAfterNutrition() }
        val store = HealthStatsStore.shared
        if (store?.needsMidnightRefresh() == true) {
            scope.launch { refreshStore(pollerContext!!) }
        }
    }

    fun refreshAfterNutrition() {
        val ctx = pollerContext ?: return
        scope.launch { refreshStore(ctx) }
    }

    private suspend fun refreshStore(ctx: Context) {
        val store = HealthStatsStore.shared ?: HealthStatsStore(ctx).also { HealthStatsStore.shared = it }
        SamsungHealthReader(ctx).refresh(store)
    }

    fun refreshHud(session: GlassesSession, store: HealthStatsStore?) {
        pushPage(session, currentPage, store)
    }

    fun pushPage(session: GlassesSession, page: FitnessHudPage) {
        pushPage(session, page, HealthStatsStore.shared)
    }

    private fun pushPage(session: GlassesSession, page: FitnessHudPage, store: HealthStatsStore?) {
        currentPage = page
        session.replaceHud(
            FitnessHudBuilder.build(
                store = store,
                page = page,
                onPageChanged = { next -> pushPage(session, next, store) },
            ),
        )
    }
}
