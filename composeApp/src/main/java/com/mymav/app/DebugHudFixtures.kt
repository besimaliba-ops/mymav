package com.mymav.app

import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccent
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.HudReadableAttribute
import com.mymav.glasses.api.HudReadableCard
import com.mymav.glasses.api.HudReadableMetric
import com.mymav.glasses.api.HudReadableMode
import com.mymav.glasses.api.HudReadableOffer
import com.mymav.glasses.api.HudReadableProduct
import com.mymav.glasses.api.IdentifyImage
import com.mymav.glasses.api.NotifyChannelFilter
import com.mymav.glasses.api.NotifyInboxState
import com.mymav.glasses.api.NotifyStage
import com.mymav.glasses.api.SongPickItem
import com.mymav.glasses.api.WeightBodyPart
import com.mymav.glasses.api.WeightExerciseHudItem

/** Test-only sample HUD models for the simulator (reached only from DebugHudReceiver's TEST_ONLY "fixture" command). */
internal object DebugHudFixtures {
    /** Plain RGBA8888 gradient used as a stand-in avatar / thumbnail / cover. */
    private fun art(w: Int, h: Int, seed: Int): ByteArray {
        val b = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            b[i] = ((x * 255 / w + seed * 40) and 0xFF).toByte()
            b[i + 1] = ((y * 255 / h + seed * 70) and 0xFF).toByte()
            b[i + 2] = (((x + y) * 255 / (w + h) + seed * 20) and 0xFF).toByte()
            b[i + 3] = 0xFF.toByte()
        }
        return b
    }

    fun build(name: String): GlassesHud? = when (name) {
        "notif-wa" -> GlassesHud.Notification(
            appLabel = "WhatsApp", accent = HudAccent.WhatsAppGreen, sender = "Sam Carter",
            body = "Are we still on for lunch tomorrow? I can do 12:30 near the station.",
            inboxIndex = 1, inboxCount = 3, avatarImageBytes = art(72, 72, 1), avatarImageWidth = 72, avatarImageHeight = 72,
        )
        "notif-ig" -> GlassesHud.Notification(
            appLabel = "Instagram", accent = HudAccent.InstagramRed, sender = "maya.codes",
            body = "Loved the demo reel!", inboxIndex = 2, inboxCount = 3, channelFilter = NotifyChannelFilter.Instagram,
            avatarImageBytes = art(72, 72, 2), avatarImageWidth = 72, avatarImageHeight = 72,
        )
        "notif-media" -> GlassesHud.Notification(
            appLabel = "WhatsApp", accent = HudAccent.WhatsAppGreen, sender = "Jo Park", body = "",
            hasMedia = true, mediaKindLabel = "Photo", inboxIndex = 3, inboxCount = 3,
            avatarImageBytes = art(72, 72, 3), avatarImageWidth = 72, avatarImageHeight = 72,
            mediaImageBytes = art(160, 120, 4), mediaImageWidth = 160, mediaImageHeight = 120,
        )
        "notif-detail" -> GlassesHud.Notification(
            appLabel = "WhatsApp", accent = HudAccent.WhatsAppGreen, sender = "Sam Carter", stage = NotifyStage.Detail,
            body = "Are we still on for lunch tomorrow? I can do 12:30 near the station, or later if that suits you better.",
            avatarImageBytes = art(72, 72, 1), avatarImageWidth = 72, avatarImageHeight = 72,
        )
        "notif-wa-filter" -> GlassesHud.Notification(
            appLabel = "WhatsApp", accent = HudAccent.WhatsAppGreen, sender = "Sam Carter", body = "Filter: WhatsApp only",
            channelFilter = NotifyChannelFilter.WhatsApp, avatarImageBytes = art(72, 72, 1), avatarImageWidth = 72, avatarImageHeight = 72,
        )
        "notif-empty-wa" -> GlassesHud.Notification(
            appLabel = "", accent = HudAccent.WhatsAppGreen, sender = "", body = "",
            channelFilter = NotifyChannelFilter.WhatsApp, inboxState = NotifyInboxState.Empty, inboxCount = 0,
        )
        "notif-empty-ig" -> GlassesHud.Notification(
            appLabel = "", accent = HudAccent.InstagramRed, sender = "", body = "",
            channelFilter = NotifyChannelFilter.Instagram, inboxState = NotifyInboxState.Empty, inboxCount = 0,
        )
        "notif-noaccess" -> GlassesHud.Notification(
            appLabel = "", accent = HudAccent.WhatsAppGreen, sender = "", body = "", inboxState = NotifyInboxState.AccessUnavailable, inboxCount = 0,
        )
        "lyrics" -> GlassesHud.Lyrics(
            trackName = "Midnight City", artistName = "M83", albumName = "Hurry Up, We're Dreaming",
            currentLyric = "Waiting in a car, waiting for a ride in the dark", nextLyric = "The city is my church",
            progressMs = 62_000L, albumArtBytes = art(96, 96, 5), albumArtWidth = 96, albumArtHeight = 96,
        )
        "lyrics-nocomma" -> GlassesHud.Lyrics(
            trackName = "Midnight City", artistName = "M83", currentLyric = "Waiting in a car waiting for a ride in the dark",
            nextLyric = "The city is my church", progressMs = 62_000L,
        )
        "lyrics-short" -> GlassesHud.Lyrics(
            trackName = "Midnight City", artistName = "M83", currentLyric = "Hi, there you", nextLyric = "The city is my church", progressMs = 62_000L,
        )
        "songs" -> GlassesHud.SongList(
            items = listOf(
                SongPickItem("1", "Midnight City", "M83"), SongPickItem("2", "A very long song title that keeps going and going", "Some Artist"),
                SongPickItem("3", "Genesis", "Grimes"), SongPickItem("4", "Open Eye Signal", "Jon Hopkins"),
            ),
            statusLine = "Tap or look 1s to play",
        )
        "identify" -> GlassesHud.IdentifyResult(
            label = "Example fragrance",
            confidence = 0.91f,
            detail = "Brand · Eau de parfum · 100 ml",
            images = listOf(IdentifyImage(art(96, 96, 6), 96, 96, "Product")),
            headerTitle = "Objects",
            brand = "Brand",
            productName = "Example fragrance",
            variant = "Eau de parfum",
            size = "100 ml",
            barcode = "",
            productType = "fragrance",
            identityEyebrow = "IDENTIFIED FROM LABEL",
            nextInstruction = "Show the barcode to confirm the variant.",
            attributes = listOf(
                HudReadableAttribute("Size", "100 ml"),
                HudReadableAttribute("Type", "fragrance"),
                HudReadableAttribute("Barcode", "not captured"),
            ),
            footerLabels = listOf("Rescan", "Details", "Find prices"),
            footerFocus = 2,
        )
        "conversate" -> GlassesHud.AppEnter(
            appId = "conversate",
            title = "Conversate",
            subtitle = "Listening",
            accent = HudAccentTone.Blue,
            readableMode = HudReadableMode.Conversate,
            statusChip = "Listening",
            cards = listOf(
                HudReadableCard(
                    eyebrow = "LIVE CAPTION",
                    body = "Can we move the client meeting to tomorrow afternoon?",
                ),
                HudReadableCard(
                    eyebrow = "SUGGESTED REPLY",
                    body = "Would 3 pm work? I can send an updated agenda beforehand.",
                ),
            ),
            footerLabels = listOf("Summary", "Save", "Stop"),
            footerFocus = 0,
        )
        "conversate-long" -> GlassesHud.AppEnter(
            appId = "conversate",
            title = "Conversate",
            subtitle = "Listening",
            accent = HudAccentTone.Blue,
            readableMode = HudReadableMode.Conversate,
            statusChip = "Listening",
            cards = listOf(
                HudReadableCard(
                    eyebrow = "LIVE CAPTION",
                    body = "Can we move the client meeting to tomorrow afternoon after the design review and also pull in the London office for the budget check?",
                    fullBody = "Can we move the client meeting to tomorrow afternoon after the design review and also pull in the London office for the budget check? We still need sign-off from legal.",
                ),
                HudReadableCard(
                    eyebrow = "SUGGESTED REPLY",
                    body = "Would 3 pm work for everyone including London? I can send an updated agenda with the budget appendix beforehand and flag the legal open questions.",
                    fullBody = "Would 3 pm work for everyone including London? I can send an updated agenda with the budget appendix beforehand and flag the legal open questions.",
                ),
            ),
            footerLabels = listOf("Summary", "Save", "Stop"),
            footerFocus = 0,
            contentOverflowHint = "Long cue — full text kept for Details",
        )
        "conversate-summary" -> GlassesHud.AppEnter(
            appId = "conversate",
            title = "Conversate",
            subtitle = "Listening",
            accent = HudAccentTone.Blue,
            readableMode = HudReadableMode.Conversate,
            statusChip = "Recording",
            cards = listOf(
                HudReadableCard(
                    eyebrow = "SUMMARY SO FAR · 1/2",
                    body = listOf(
                        "• Move the client meeting to tomorrow afternoon after the design review",
                        "• Pull in the London office for the budget check",
                        "• Legal still needs to sign off the appendix",
                        "Decision: keep 3 pm as the working time",
                        "Task: send an updated agenda with the budget appendix",
                        "Task: flag the legal open questions before the call",
                        "Open: whether finance can join from Dublin",
                        "• Recap the last pricing assumption with Sam",
                        "• Confirm room booking and dial-in for London",
                        "Task: share the deck 30 minutes beforehand",
                        "Open: who owns the follow-up note after the meeting",
                    ).joinToString("\n"),
                ),
            ),
            footerLabels = listOf("Previous", "Next", "Back"),
            footerFocus = 2,
            footerDisabled = setOf(0),
        )
        "conversate-saved-long" -> GlassesHud.AppEnter(
            appId = "conversate",
            title = "Conversate",
            subtitle = "Idle",
            accent = HudAccentTone.Blue,
            readableMode = HudReadableMode.Conversate,
            statusChip = "Saved",
            cards = listOf(
                HudReadableCard(
                    eyebrow = "QUARTERLY CLIENT BUDGET REVIEW",
                    body = listOf(
                        "Quarterly client budget review notes from the live session.",
                        "• Move the client meeting to tomorrow afternoon",
                        "• Pull London in for the budget check",
                        "Decision: 3 pm is the working time",
                        "Task: send the updated agenda and appendix",
                        "Task: flag remaining legal questions",
                        "Open: Dublin finance attendance",
                        "• Recap pricing with Sam before the call",
                        "More on the next page.",
                    ).joinToString("\n"),
                ),
            ),
            footerLabels = listOf("Previous", "Next", "Back"),
            footerFocus = 2,
            footerDisabled = setOf(0),
        )
        "wrap-probe" -> GlassesHud.AppEnter(
            appId = "conversate",
            title = "Conversate",
            subtitle = "Probe",
            accent = HudAccentTone.Blue,
            readableMode = HudReadableMode.Conversate,
            statusChip = "Probe",
            cards = listOf(
                HudReadableCard(
                    eyebrow = "WRAPPROBE",
                    body = listOf(
                        nChars(40),
                        nChars(42),
                        nChars(44),
                        nChars(48),
                        nChars(55),
                        nChars(60, 'i'),
                        nChars(24, 'W'),
                        "TR|ışıöğüçışıöğüçışıöğüçış",
                    ).joinToString("\n"),
                ),
            ),
            footerLabels = listOf("Previous", "Next", "Back"),
            footerFocus = 2,
            footerDisabled = setOf(0, 1),
        )
        "shop" -> GlassesHud.AppEnter(
            appId = "shop",
            title = "Shop",
            subtitle = "Exact match",
            accent = HudAccentTone.Purple,
            readableMode = HudReadableMode.Shop,
            product = HudReadableProduct(
                title = "Example fragrance",
                meta = "Eau de parfum · 100 ml",
                status = "Exact match · Checked 19:40",
                thumbBytes = art(64, 64, 9),
                thumbWidth = 64,
                thumbHeight = 64,
            ),
            offers = listOf(
                HudReadableOffer("Retailer A", "£79.00", "Delivery £3.95", selected = true),
                HudReadableOffer("Retailer B", "£84.00", "Free delivery", selected = false),
            ),
            offerPageLabel = "Offer 1–2 of 4",
            footerLabels = listOf("Previous", "Next", "Open on phone"),
            footerFocus = 2,
        )
        "shop-long" -> GlassesHud.AppEnter(
            appId = "shop",
            title = "Shop",
            subtitle = "Exact match",
            accent = HudAccentTone.Purple,
            readableMode = HudReadableMode.Shop,
            product = HudReadableProduct(
                title = "Very Long Example Fragrance Name With Extra Words That Must Wrap Fully",
                meta = "Eau de parfum · Limited edition · 100 ml",
                status = "Exact match · Checked 19:40",
            ),
            offers = listOf(
                HudReadableOffer("Very Long Retailer Name Marketplace", "£179.00", "Delivery £4.95", selected = true),
            ),
            offerPageLabel = "Offer 1–1 of 3",
            footerLabels = listOf("Previous", "Next", "Open on phone"),
            footerFocus = 1,
        )
        "food" -> GlassesHud.AppEnter(
            appId = "food",
            title = "Food",
            subtitle = "Estimate",
            accent = HudAccentTone.Orange,
            readableMode = HudReadableMode.Food,
            statusChip = "Estimate",
            product = HudReadableProduct(
                title = "spaghetti bolognese",
                meta = "1 plate (~300 g)",
                status = "Estimates from a photo, not medical advice.",
                thumbBytes = art(128, 128, 11),
                thumbWidth = 128,
                thumbHeight = 128,
            ),
            metrics = listOf(
                HudReadableMetric("600", "kcal"),
                HudReadableMetric("25g", "protein"),
                HudReadableMetric("75g", "carbs"),
                HudReadableMetric("25g", "fat"),
            ),
            footerLabels = listOf("Rescan", "Portion", "Save to Health"),
            footerFocus = 2,
        )
        "shop-searching" -> GlassesHud.AppEnter(
            appId = "shop",
            title = "Shop",
            subtitle = "SEARCHING",
            accent = HudAccentTone.Purple,
            readableMode = HudReadableMode.Shop,
            cards = listOf(
                HudReadableCard("SEARCHING", "SerpAPI Google Shopping · Live offers only"),
            ),
            footerLabels = listOf("Previous", "Next", "Open on phone"),
        )
        "objects-uncertain" -> GlassesHud.IdentifyResult(
            label = "Possible fragrance bottle",
            confidence = 0.42f,
            detail = "Label unclear",
            images = emptyList(),
            headerTitle = "Objects",
            brand = "",
            productName = "Possible fragrance bottle",
            identityEyebrow = "NEEDS CLEARER VIEW",
            uncertainty = "Uncertain — verify label",
            nextInstruction = "Move closer to front label or show barcode",
            attributes = listOf(
                HudReadableAttribute("Size", "unknown"),
                HudReadableAttribute("Type", "fragrance"),
                HudReadableAttribute("Barcode", "not captured"),
            ),
            footerLabels = listOf("Rescan", "Details", "Find prices"),
            footerFocus = 0,
        )
        "lens" -> GlassesHud.SongList(
            title = "Length probe",
            items = listOf(41, 43, 45, 47).mapIndexed { i, n -> SongPickItem("p$i", "n$n " + "x".repeat(n - ("n$n ").length), "len $n") },
        )
        "running" -> GlassesHud.RunningPace()
        "weight" -> GlassesHud.WeightSession(
            exercises = listOf(
                WeightExerciseHudItem("e1", "Bench press", WeightBodyPart.entries.first(), 8, listOf(true, true, false, false), 60f),
                WeightExerciseHudItem("e2", "Row", WeightBodyPart.entries.first(), 10, listOf(false, false, false), 45f),
            ),
        )
        else -> null
    }

    /** Visible-length probe line: `NN|` plus fill, exactly [n] characters. */
    private fun nChars(n: Int, fill: Char = 'x'): String {
        val prefix = n.toString().padStart(2, '0') + "|"
        return prefix + fill.toString().repeat((n - prefix.length).coerceAtLeast(0))
    }
}
