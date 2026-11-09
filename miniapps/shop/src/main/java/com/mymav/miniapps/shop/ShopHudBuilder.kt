package com.mymav.miniapps.shop

import com.mymav.glasses.api.AppEnterActions
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.HudReadableCard
import com.mymav.glasses.api.HudReadableMode
import com.mymav.glasses.api.HudReadableOffer
import com.mymav.glasses.api.HudReadableProduct
import com.mymav.glasses.api.MymavShopOffers
import com.mymav.glasses.api.miniAppHomeActions

/**
 * Readable Shop HUD — product strip + up to two selectable offer rows per page.
 * Layout only; product match and offers live in [ShopScanEngine].
 */
object ShopHudBuilder {

    fun offersPerPage(offers: List<MymavShopOffers.Offer>): Int = if (offers.isEmpty()) 1 else 2

    private fun base(
        subtitle: String,
        footer: List<String>,
        footerFocus: Int,
        actions: AppEnterActions,
        disabled: Set<Int> = emptySet(),
    ) = GlassesHud.AppEnter(
        appId = "shop",
        title = "Shop",
        subtitle = subtitle,
        accent = HudAccentTone.Purple,
        readableMode = HudReadableMode.Shop,
        footerLabels = footer,
        footerFocus = footerFocus.coerceIn(0, (footer.size - 1).coerceAtLeast(0)),
        footerDisabled = disabled,
        actions = actions,
    )

    fun ready(
        footer: List<String>,
        footerFocus: Int,
        last: ShopScanResult?,
        actions: AppEnterActions,
    ): GlassesHud.AppEnter {
        val body = buildString {
            append("Look at the product label, then Scan. ")
            append(
                if (MymavShopOffers.isConfigured()) "Prices are live from Google Shopping."
                else "Live prices need a SerpAPI key in phone Settings.",
            )
            if (last != null) append(" Last: ${last.title}".take(60))
        }
        return base("", footer, footerFocus, actions).copy(
            cards = listOf(HudReadableCard(eyebrow = "READY", body = body, fullBody = body)),
        )
    }

    fun phase(
        phase: ShopScanPhase,
        detail: String,
        lastError: String,
        footer: List<String>,
        footerFocus: Int,
        actions: AppEnterActions,
        thumbJpeg: ByteArray? = null,
    ): GlassesHud.AppEnter {
        val (chip, eyebrow, body) = when (phase) {
            ShopScanPhase.Camera, ShopScanPhase.Scanning ->
                Triple("Scanning", "SCANNING", detail.ifBlank { "Capturing the product label..." })
            ShopScanPhase.Analysing ->
                Triple("Identifying", "IDENTIFYING", detail.ifBlank { "Reading brand, variant and size..." })
            ShopScanPhase.LookingUp ->
                Triple("Searching", "CHECKING PRICES", detail.ifBlank { "Checking live prices..." })
            ShopScanPhase.Ambiguous ->
                Triple("Unclear", "COULDN'T CONFIRM PRODUCT", detail.ifBlank { lastError }.ifBlank { "Move closer to the front label." })
            ShopScanPhase.SetupNeeded ->
                Triple("No prices", "NO LIVE PRICES", MymavShopOffers.setupNeededMessage())
            ShopScanPhase.Error ->
                Triple("Failed", "COULD NOT SCAN", lastError.ifBlank { detail }.ifBlank { "Scan failed. Try again." })
            ShopScanPhase.PermissionNeeded ->
                Triple("Camera off", "CAMERA PERMISSION", "Allow the camera for MyMav on the phone once, then Rescan.")
            ShopScanPhase.NoCamera ->
                Triple("No frame", "NO CAMERA FRAME", lastError.ifBlank { "No camera frame. Keep MyMav open on the phone." })
            ShopScanPhase.Cancelled -> Triple("", "CANCELLED", "Scan stopped.")
            else -> Triple("", "SHOP", detail.ifBlank { "Ready" })
        }
        val hud = base(chip, footer, footerFocus, actions).copy(
            cards = listOf(HudReadableCard(eyebrow = eyebrow, body = body, fullBody = body)),
        )
        return if (thumbJpeg != null && phase == ShopScanPhase.LookingUp) {
            hud.copy(
                product = HudReadableProduct(
                    title = "Checking prices",
                    meta = "",
                    status = body,
                    thumbBytes = thumbJpeg,
                    thumbWidth = 64,
                    thumbHeight = 64,
                ),
            )
        } else {
            hud
        }
    }

    fun offers(
        result: ShopScanResult,
        offerIndex: Int,
        offerPage: Int,
        footer: List<String>,
        footerFocus: Int,
        disabled: Set<Int>,
        notice: String,
        thumbJpeg: ByteArray?,
        actions: AppEnterActions,
    ): GlassesHud.AppEnter {
        val all = result.offers
        val per = offersPerPage(all)
        val pageCount = ((all.size + per - 1) / per).coerceAtLeast(1)
        val page = offerPage.coerceIn(0, pageCount - 1)
        val start = page * per
        val pageOffers = all.drop(start).take(per)
        val selected = offerIndex.coerceIn(0, all.lastIndex)
        val exact = all.count { it.matchKind == MymavShopOffers.MatchKind.Exact }
        val checked = all.firstOrNull()?.checkedLabel().orEmpty()

        val rows = pageOffers.mapIndexed { i, o ->
            HudReadableOffer(
                retailer = o.retailer.ifBlank { "Retailer" },
                priceLabel = o.priceLabel(),
                delivery = offerLine(o),
                selected = start + i == selected,
            )
        }
        val status = notice.ifBlank {
            if (exact > 0) "$exact exact match${if (exact == 1) "" else "es"} - cheapest first"
            else "No exact match - check size/variant"
        }
        return base("${all.size} offers", footer, footerFocus, actions, disabled).copy(
            product = HudReadableProduct(
                title = result.title.ifBlank { result.productName }.ifBlank { "Product" },
                meta = listOf(result.variant, result.size).filter { it.isNotBlank() }.joinToString(" - ")
                    .ifBlank { result.brand },
                status = status,
                thumbBytes = thumbJpeg,
                thumbWidth = if (thumbJpeg != null) 64 else 0,
                thumbHeight = if (thumbJpeg != null) 64 else 0,
            ),
            offers = rows,
            offerPageLabel = "Offer ${selected + 1} of ${all.size} - prices checked $checked",
            contentPage = page,
            contentPageCount = pageCount,
        )
    }

    private const val LINE_CHARS = 42

    /** Mismatch note first (it decides whether the price applies); delivery only when it fits whole. */
    private fun offerLine(o: MymavShopOffers.Offer): String {
        if (o.matchNote.isBlank()) return o.delivery.ifBlank { "Delivery not listed" }
        val note = o.matchNote.replaceFirstChar { it.uppercase() }
        val both = "$note - ${o.delivery}"
        return if (o.delivery.isNotBlank() && both.length <= LINE_CHARS) both else note
    }

    fun identity(
        result: ShopScanResult,
        confirming: Boolean,
        footer: List<String>,
        footerFocus: Int,
        thumbJpeg: ByteArray?,
        actions: AppEnterActions,
    ): GlassesHud.AppEnter {
        val meta = listOf(result.brand, result.variant, result.size).filter { it.isNotBlank() }.joinToString(" - ")
        val (chip, eyebrow, body) = when {
            confirming -> Triple(
                "Confirm",
                "IS THIS THE PRODUCT?",
                "Possible match. Check the variant and size, then Find prices or Rescan the label.",
            )
            !result.providerReady -> Triple("No prices", "NO LIVE PRICES", result.offerDisclaimer)
            result.lookupFailed -> Triple(
                "Failed",
                "PRICE CHECK FAILED",
                "${result.offerDisclaimer.ifBlank { "The price service didn't answer" }}. Retry prices; the product is kept.",
            )
            else -> Triple(
                "No offers",
                "NO VERIFIED PRICES",
                "No live offers matched this product. Rescan the label, or Retry prices.",
            )
        }
        return base(chip, footer, footerFocus, actions).copy(
            product = HudReadableProduct(
                title = result.title,
                meta = meta.ifBlank { result.detail },
                status = if (result.size.isBlank()) "Size not read from label" else "",
                thumbBytes = thumbJpeg,
                thumbWidth = if (thumbJpeg != null) 64 else 0,
                thumbHeight = if (thumbJpeg != null) 64 else 0,
            ),
            cards = listOf(HudReadableCard(eyebrow = eyebrow, body = body, fullBody = body)),
        )
    }

    fun shopActions(
        onFooterActivated: (Int) -> Unit,
        onFooterFocus: (Int) -> Unit,
        onOfferSelected: (Int) -> Unit,
    ): AppEnterActions {
        val base = miniAppHomeActions()
        return AppEnterActions(
            onHomeRequested = base.onHomeRequested,
            onGazeNormalized = base.onGazeNormalized,
            onFooterFocusChanged = onFooterFocus,
            onFooterActivated = onFooterActivated,
            onOfferSelected = onOfferSelected,
        )
    }
}
