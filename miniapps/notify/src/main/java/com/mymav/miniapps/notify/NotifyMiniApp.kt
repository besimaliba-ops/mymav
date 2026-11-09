package com.mymav.miniapps.notify

import android.graphics.Color
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccent
import com.mymav.glasses.api.HudReplyState
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.NotificationHudActions
import com.mymav.glasses.api.NotifyInboxState
import com.mymav.miniapps.notify.service.NotifyCompanionService
import com.mymav.miniapps.notify.ui.HudBitmapEncoder

object NotifyMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "notify",
        title = "Socials",
        subtitle = "WhatsApp / Instagram notifications",
        route = "mini/notify",
        requiredCapabilities = listOf("notifications", "hud"),
    )

    override fun onEnter(session: GlassesSession) {
        NotifySessionBridge.attach(session)
        GlassesHomeController.markMiniAppOpen("notify")
        if (NotifyCompanionService.reshowPending()) {
            // Companion will re-push the pending WhatsApp/IG glasslet for later reply.
            return
        }
        showReadyHud(session)
    }

    /** No pending message: show the real state (empty inbox, or notification access unavailable). */
    private fun showReadyHud(session: GlassesSession) {
        // Unknown (no context yet) counts as available: never claim access is off without evidence.
        val accessOn = NotifyCompanionService.listenerAccessEnabled() ?: true
        session.replaceHud(
            GlassesHud.Notification(
                appLabel = "Socials",
                accent = HudAccent.WhatsAppGreen,
                sender = "",
                body = "",
                replyState = HudReplyState.Idle,
                inboxCount = 0,
                inboxState = if (accessOn) NotifyInboxState.Empty else NotifyInboxState.AccessUnavailable,
                actions = NotificationHudActions(
                    onDismissRequested = { GlassesHomeController.requestHomeFromApp() },
                    onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                    onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
                ),
            ),
        )
    }

    override fun onLeave(session: GlassesSession) {
        // Keep session attached — companion FGS + NotificationListener need it
        // after the phone leaves the Notify screen (background / lock).
        // Do not clearHud — home board is already pushed; clearing empties the sim draw queue.
    }
}
