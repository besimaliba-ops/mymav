package com.mymav.miniapps.mymind

import android.content.Context
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.MymindTaskPage
import com.mymav.glasses.api.miniAppHomeActions

object MymindMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "mymind",
        title = "Mymind",
        subtitle = "Conversation memory + task extraction",
        route = "mini/mymind",
        requiredCapabilities = listOf("audio", "local-store", "hud"),
    )

    @Volatile
    var store: MymindStore? = null

    @Volatile
    private var appContext: Context? = null

    var activePage: MymindTaskPage = MymindTaskPage.Daily

    fun ensureStore(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (store == null) {
            store = MymindStore(app)
        }
        MymindBackgroundEngine.seedIfNeeded(app)
    }

    private fun storeOrNull(): MymindStore? {
        store?.let { return it }
        val ctx = appContext ?: return null
        return MymindStore(ctx).also { store = it }
    }

    override fun onEnter(session: GlassesSession) {
        appContext?.let { ensureStore(it) }
        GlassesHomeController.markMiniAppOpen("mymind")
        activePage = MymindTaskPage.Daily
        session.replaceHud(
            GlassesHud.AppEnter(
                appId = "mymind",
                title = "Mymind",
                subtitle = "Memories · people · tasks",
                accent = HudAccentTone.Blue,
                bullets = listOf(
                    "Conversation summaries on-lens",
                    "People stubs from recent chats",
                    "Daily / Weekly / Monthly task boards",
                ),
                metric = storeOrNull()?.openCount(MymindTaskPage.Daily)?.toString() ?: "0",
                metricLabel = "today",
                actions = miniAppHomeActions(),
            ),
        )
    }

    override fun onLeave(session: GlassesSession) {}

    fun pushBoardHud(session: GlassesSession, page: MymindTaskPage = activePage) {
        activePage = page
        val st = storeOrNull()
        val tasks = st?.tasksFor(page)?.map { it.title } ?: emptyList()
        val people = st?.people()?.map { "${it.name} (${it.note})" } ?: emptyList()
        session.replaceHud(
            GlassesHud.MymindBoard(
                page = page,
                memorySummary = st?.memorySummary() ?: "",
                taskLines = tasks,
                peopleLines = people,
                actions = miniAppHomeActions(),
            ),
        )
    }

    fun cyclePage(session: GlassesSession) {
        activePage = when (activePage) {
            MymindTaskPage.Daily -> MymindTaskPage.Weekly
            MymindTaskPage.Weekly -> MymindTaskPage.Monthly
            MymindTaskPage.Monthly -> MymindTaskPage.Daily
        }
        pushBoardHud(session, activePage)
    }
}
