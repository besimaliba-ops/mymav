package com.mymav.miniapps.shop

import android.content.Intent
import android.net.Uri
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MymavAi
import com.mymav.glasses.api.MymavCapture
import com.mymav.glasses.api.MymavScanVision
import com.mymav.glasses.api.MymavShopOffers
import com.mymav.glasses.api.MymavVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ShopScanPhase {
    Idle,
    Ready,
    Camera,
    Scanning,
    Analysing,
    LookingUp,
    Ambiguous,
    Confirm,
    Offers,
    Result,
    Error,
    PermissionNeeded,
    NoCamera,
    Cancelled,
    SetupNeeded,
}

data class ShopScanResult(
    val title: String,
    val brand: String,
    val productName: String,
    val variant: String,
    val size: String,
    val detail: String,
    val priceHint: String,
    val barcode: String,
    val searchQuery: String,
    val matchQuality: String,
    val source: String,
    val uncertainty: String,
    val offers: List<MymavShopOffers.Offer>,
    val offerDisclaimer: String,
    val providerReady: Boolean,
    /** The provider call itself failed (network/HTTP) — Retry prices makes sense. */
    val lookupFailed: Boolean = false,
)

/**
 * Shop on the glasses: Scan → identify product/variant/size → live offers.
 * Identify's Find prices lands here through [findPricesForProduct] with the same product and photo,
 * so both apps share one product-match + offer path and never recapture.
 */
object ShopScanEngine {
    @Volatile
    var phase: ShopScanPhase = ShopScanPhase.Idle
        private set

    @Volatile
    var lastResult: ShopScanResult? = null
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var offerIndex: Int = 0
        private set

    @Volatile
    var offerPage: Int = 0
        private set

    @Volatile
    var footerFocus: Int = 0
        private set

    private val sessionGen = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var scanJob: Job? = null
    @Volatile private var cancelled = false
    @Volatile private var heldJpeg: ByteArray? = null
    @Volatile private var hudSession: GlassesSession? = null
    @Volatile private var notice: String = ""
    @Volatile private var pendingAnalysis: MymavScanVision.ProductAnalysis? = null
    @Volatile private var pendingSeed: Pair<MymavScanVision.ProductAnalysis, MymavVision.Frame?>? = null
    private val phoneContext = AtomicReference<android.content.Context?>(null)

    fun bindPhone(context: android.content.Context) {
        phoneContext.set(context.applicationContext)
    }

    private val busy get() = phase in setOf(
        ShopScanPhase.Camera, ShopScanPhase.Scanning, ShopScanPhase.Analysing, ShopScanPhase.LookingUp,
    )

    fun cancel() {
        cancelled = true
        sessionGen.incrementAndGet()
        scanJob?.cancel()
        if (busy) {
            phase = ShopScanPhase.Cancelled
            lastError = "Scan cancelled"
        }
    }

    /** Leaving the app: stop work but keep the last product so the idle screen can offer Last result. */
    fun reset() {
        cancelled = false
        sessionGen.incrementAndGet()
        scanJob?.cancel()
        phase = ShopScanPhase.Idle
        lastError = ""
        footerFocus = 0
        notice = ""
        pendingAnalysis = null
    }

    fun markPermissionNeeded() {
        phase = ShopScanPhase.PermissionNeeded
        lastError = "Camera permission needed"
    }

    fun markNoCamera(reason: String) {
        phase = ShopScanPhase.NoCamera
        lastError = reason
    }

    fun currentOffer(): MymavShopOffers.Offer? {
        val offers = lastResult?.offers.orEmpty()
        if (offers.isEmpty()) return null
        return offers[offerIndex.coerceIn(0, offers.lastIndex)]
    }

    fun nextOffer(session: GlassesSession): MymavShopOffers.Offer? {
        val offers = lastResult?.offers.orEmpty()
        if (offers.isEmpty()) return null
        offerIndex = (offerIndex + 1) % offers.size
        notice = ""
        syncOfferPage()
        pushOffersHud(session)
        return currentOffer()
    }

    fun prevOffer(session: GlassesSession): MymavShopOffers.Offer? {
        val offers = lastResult?.offers.orEmpty()
        if (offers.isEmpty()) return null
        offerIndex = if (offerIndex <= 0) offers.lastIndex else offerIndex - 1
        notice = ""
        syncOfferPage()
        pushOffersHud(session)
        return currentOffer()
    }

    /**
     * Android blocks activity starts from the background and behind the lock screen, so the link opens
     * directly only while MyMav is in front; otherwise it waits as a tappable notification.
     */
    fun openCurrentOfferOnPhone(context: android.content.Context): String {
        val offer = currentOffer() ?: return "No offer selected"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(offer.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val inFront = state.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            keyguard?.isKeyguardLocked != true
        if (inFront && runCatching { context.startActivity(view) }.isSuccess) return "Opened ${offer.retailer} on the phone"
        return if (notifyOffer(context, offer, view)) {
            "Link sent to phone notifications"
        } else {
            "Allow MyMav notifications to send links"
        }
    }

    private fun notifyOffer(context: android.content.Context, offer: MymavShopOffers.Offer, view: Intent): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
        if (nm.getNotificationChannel(OFFER_CHANNEL) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(OFFER_CHANNEL, "Shop offers", android.app.NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val pi = android.app.PendingIntent.getActivity(
            context, offer.url.hashCode(), view,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, OFFER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("${offer.retailer} - ${offer.priceLabel()}")
            .setContentText(offer.title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(OFFER_NOTIFICATION_ID, n)
        return true
    }

    private const val OFFER_CHANNEL = "mymav_shop_offers"
    private const val OFFER_NOTIFICATION_ID = 7341

    private fun syncOfferPage() {
        val offers = lastResult?.offers.orEmpty()
        if (offers.isEmpty()) {
            offerPage = 0
            return
        }
        val per = ShopHudBuilder.offersPerPage(offers)
        offerPage = (offerIndex / per).coerceAtLeast(0)
    }

    // ---- glasses actions ------------------------------------------------------------------------------------

    private fun footer(): List<String> {
        val r = lastResult
        return when (phase) {
            ShopScanPhase.Camera, ShopScanPhase.Scanning, ShopScanPhase.Analysing, ShopScanPhase.LookingUp ->
                listOf("Cancel")
            ShopScanPhase.Offers -> listOf("Scan", "Next offer", "Open on phone")
            ShopScanPhase.Confirm -> listOf("Find prices", "Rescan")
            ShopScanPhase.Result -> if (r != null) listOf("Retry prices", "Rescan") else listOf("Rescan")
            ShopScanPhase.Ambiguous, ShopScanPhase.SetupNeeded, ShopScanPhase.Error,
            ShopScanPhase.NoCamera, ShopScanPhase.PermissionNeeded, ShopScanPhase.Cancelled ->
                listOfNotNull("Rescan", if (r != null && r.offers.isNotEmpty()) "Last result" else null)
            ShopScanPhase.Idle, ShopScanPhase.Ready ->
                listOfNotNull("Scan", if (r != null) "Last result" else null)
        }
    }

    private fun disabled(labels: List<String>): Set<Int> = buildSet {
        val n = lastResult?.offers?.size ?: 0
        labels.forEachIndexed { i, l -> if (l == "Next offer" && n <= 1) add(i) }
    }

    private fun onFooter(index: Int) {
        val label = footer().getOrNull(index) ?: return
        when (label) {
            "Scan", "Rescan" -> scanFromGlasses()
            "Cancel" -> {
                cancel()
                pushHud()
            }
            "Next offer" -> hudSession?.let { nextOffer(it) }
            "Open on phone" -> {
                val ctx = phoneContext.get()
                notice = if (ctx == null) "Open MyMav on the phone to launch links." else openCurrentOfferOnPhone(ctx)
                pushHud()
            }
            "Find prices" -> {
                val a = pendingAnalysis ?: return
                scanJob = scope.launch { lookUpOffers(a) }
            }
            "Retry prices" -> {
                val r = lastResult ?: return
                scanJob = scope.launch { lookUpOffers(r.toAnalysis()) }
            }
            "Last result" -> {
                val r = lastResult ?: return
                offerIndex = offerIndex.coerceIn(0, (r.offers.size - 1).coerceAtLeast(0))
                showResult(r)
            }
        }
    }

    private fun actions() = ShopHudBuilder.shopActions(
        onFooterActivated = { idx -> scope.launch { onFooter(idx) } },
        onFooterFocus = { idx ->
            footerFocus = idx.coerceIn(0, footer().lastIndex)
            pushHud()
        },
        onOfferSelected = { idx ->
            val offers = lastResult?.offers.orEmpty()
            if (phase == ShopScanPhase.Offers && offers.isNotEmpty()) {
                val per = ShopHudBuilder.offersPerPage(offers)
                val picked = (offerPage * per + idx).coerceIn(0, offers.lastIndex)
                if (picked != offerIndex) {
                    offerIndex = picked
                    notice = ""
                    pushHud()
                }
            }
        },
    )

    /** Scan started from the glasses: a frame handed over by Identify/Food, else the shared capture service. */
    fun scanFromGlasses(session: GlassesSession? = hudSession) {
        if (scanJob?.isActive == true) return
        val s = session ?: return
        hudSession = s
        scanJob = scope.launch {
            val handed = MymavCapture.takeHandoff()
            val frame = handed ?: run {
                pushPhaseHud(s, ShopScanPhase.Scanning, "Capturing the product label...")
                val shot = MymavCapture.capture()
                shot.frame ?: run {
                    lastError = shot.problem
                    pushPhaseHud(s, ShopScanPhase.NoCamera)
                    return@launch
                }
            }
            analyseAndLookUp(s, frame)
        }
    }

    // ---- HUDs -----------------------------------------------------------------------------------------------

    private fun pushHud(session: GlassesSession? = hudSession) {
        val s = session ?: return
        when (phase) {
            ShopScanPhase.Offers -> pushOffersHud(s)
            ShopScanPhase.Result, ShopScanPhase.Confirm, ShopScanPhase.SetupNeeded ->
                lastResult?.let { pushIdentityHud(s, it) } ?: pushPhaseHud(s, phase)
            ShopScanPhase.Idle, ShopScanPhase.Ready -> pushReadyHud(s)
            else -> pushPhaseHud(s, phase)
        }
    }

    private fun show(session: GlassesSession, hud: com.mymav.glasses.api.GlassesHud.AppEnter) {
        hudSession = session
        if (!GlassesHomeController.acceptsMiniAppHud("shop")) return
        session.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    fun pushReadyHud(session: GlassesSession) {
        phase = ShopScanPhase.Ready
        val labels = footer()
        show(session, ShopHudBuilder.ready(labels, footerFocus.coerceIn(0, labels.lastIndex), lastResult, actions()))
    }

    fun pushPhaseHud(session: GlassesSession, phase: ShopScanPhase, detail: String = "") {
        this.phase = phase
        val labels = footer()
        show(
            session,
            ShopHudBuilder.phase(
                phase, detail, lastError, labels, footerFocus.coerceIn(0, labels.lastIndex), actions(),
                thumbJpeg = heldJpeg.takeIf { phase == ShopScanPhase.LookingUp },
            ),
        )
    }

    fun pushOffersHud(session: GlassesSession) {
        val result = lastResult ?: return
        if (result.offers.isEmpty()) {
            pushIdentityHud(session, result)
            return
        }
        phase = ShopScanPhase.Offers
        syncOfferPage()
        val labels = footer()
        show(
            session,
            ShopHudBuilder.offers(
                result = result,
                offerIndex = offerIndex,
                offerPage = offerPage,
                footer = labels,
                footerFocus = footerFocus.coerceIn(0, labels.lastIndex),
                disabled = disabled(labels),
                notice = notice,
                thumbJpeg = heldJpeg,
                actions = actions(),
            ),
        )
    }

    private fun pushIdentityHud(session: GlassesSession, result: ShopScanResult) {
        if (phase != ShopScanPhase.Confirm) {
            phase = if (!result.providerReady) ShopScanPhase.SetupNeeded else ShopScanPhase.Result
        }
        val labels = footer()
        show(
            session,
            ShopHudBuilder.identity(
                result = result,
                confirming = phase == ShopScanPhase.Confirm,
                footer = labels,
                footerFocus = footerFocus.coerceIn(0, labels.lastIndex),
                thumbJpeg = heldJpeg,
                actions = actions(),
            ),
        )
    }

    private fun showResult(r: ShopScanResult) {
        val s = hudSession ?: return
        footerFocus = 0
        if (r.offers.isNotEmpty()) pushOffersHud(s) else pushIdentityHud(s, r)
    }

    // ---- product match + offers -----------------------------------------------------------------------------

    /** Phone-screen capture (Shop screen buttons). Null [jpeg] uses the shared capture service. */
    suspend fun runLiveScan(
        session: GlassesSession,
        jpeg: ByteArray?,
        phoneSourceLabel: String = MymavCapture.PHONE_TEST_LABEL,
    ): ShopScanResult? {
        hudSession = session
        val frame = if (jpeg != null && jpeg.isNotEmpty()) {
            MymavVision.Frame(jpeg, phoneSourceLabel)
        } else {
            pushPhaseHud(session, ShopScanPhase.Scanning, "Capturing the product label...")
            val shot = MymavCapture.capture()
            shot.frame ?: run {
                lastError = shot.problem
                pushPhaseHud(session, ShopScanPhase.NoCamera)
                return null
            }
        }
        return analyseAndLookUp(session, frame)
    }

    private suspend fun analyseAndLookUp(session: GlassesSession, frame: MymavVision.Frame): ShopScanResult? {
        cancelled = false
        val gen = sessionGen.incrementAndGet()
        footerFocus = 0
        notice = ""
        heldJpeg = frame.jpeg
        if (!MymavAi.isConfigured()) {
            lastError = "No ChatGPT or Gemini key. Add one in MyMav Settings to identify products."
            pushPhaseHud(session, ShopScanPhase.Error)
            return null
        }
        pushPhaseHud(session, ShopScanPhase.Analysing, "Reading brand, variant and size...")
        val analysis = withContext(Dispatchers.IO) { MymavScanVision.analyseProduct(frame) }
        if (cancelled || sessionGen.get() != gen) return null
        if (analysis.title == "Analysis failed") {
            lastError = "Couldn't read the product. ${analysis.uncertainty}".trim()
            pushPhaseHud(session, ShopScanPhase.Error)
            return null
        }
        val named = analysis.searchQuery.isNotBlank() && (analysis.brand.isNotBlank() || analysis.productName.isNotBlank())
        if (!named || analysis.needsCloserLook && analysis.brand.isBlank()) {
            lastError = analysis.recaptureHint.ifBlank { "Move closer to the front label or show the barcode." }
            pushPhaseHud(session, ShopScanPhase.Ambiguous, lastError)
            return null
        }
        if (analysis.matchQuality != "exact" || analysis.needsCloserLook) {
            pendingAnalysis = analysis
            lastResult = analysis.toResult(emptyList(), "", MymavShopOffers.isConfigured())
            phase = ShopScanPhase.Confirm
            pushIdentityHud(session, lastResult!!)
            return lastResult
        }
        return lookUpOffers(analysis, session, gen)
    }

    private suspend fun lookUpOffers(
        analysis: MymavScanVision.ProductAnalysis,
        session: GlassesSession? = hudSession,
        gen: Int = sessionGen.incrementAndGet(),
    ): ShopScanResult? {
        val s = session ?: return null
        cancelled = false
        pendingAnalysis = null
        footerFocus = 0
        notice = ""
        if (!MymavShopOffers.isConfigured()) {
            val r = analysis.toResult(emptyList(), MymavShopOffers.setupNeededMessage(), providerReady = false)
            lastResult = r
            pushIdentityHud(s, r)
            return r
        }
        pushPhaseHud(s, ShopScanPhase.LookingUp, "Checking live prices for ${analysis.title}".take(80))
        val lookup = withContext(Dispatchers.IO) {
            MymavShopOffers.lookup(
                searchQuery = analysis.searchQuery.ifBlank { analysis.title },
                brand = analysis.brand,
                variant = analysis.variant,
                size = analysis.size,
                barcode = analysis.barcode,
            )
        }
        if (cancelled || sessionGen.get() != gen) return null
        val r = analysis.toResult(
            lookup.offers,
            lookup.disclaimer.ifBlank { lookup.setupNeeded },
            lookup.providerReady,
            lookupFailed = lookup.error.isNotBlank() && lookup.offers.isEmpty(),
        )
        lastResult = r
        offerIndex = 0
        offerPage = 0
        phase = ShopScanPhase.Result
        showResult(r)
        return r
    }

    /** Identify → Find prices: open Shop with the resolved product and its photo, no recapture or re-guess. */
    fun findPricesFromIdentify(
        title: String,
        brand: String,
        productName: String,
        variant: String,
        size: String,
        barcode: String,
        searchQuery: String,
        frame: MymavVision.Frame?,
    ) {
        pendingSeed = seedAnalysis(title, brand, productName, variant, size, barcode, searchQuery, "identify") to frame
        GlassesHomeController.switchToApp("shop")
    }

    /** Called by [ShopMiniApp.onEnter]: true when an Identify hand-off was waiting and is now being priced. */
    fun consumeSeed(session: GlassesSession): Boolean {
        val (seed, frame) = pendingSeed ?: return false
        pendingSeed = null
        hudSession = session
        heldJpeg = frame?.jpeg
        scanJob = scope.launch { lookUpOffers(seed, session) }
        return true
    }

    /** Phone Identify screen: price a product in place (Shop HUD takes over the glasses). */
    suspend fun findPricesForProduct(
        session: GlassesSession,
        title: String,
        brand: String = "",
        productName: String = "",
        variant: String = "",
        size: String = "",
        barcode: String = "",
        searchQuery: String = "",
        source: String = "identify",
    ): ShopScanResult? {
        GlassesHomeController.markMiniAppOpen("shop")
        hudSession = session
        return lookUpOffers(seedAnalysis(title, brand, productName, variant, size, barcode, searchQuery, source), session)
    }

    private fun seedAnalysis(
        title: String,
        brand: String,
        productName: String,
        variant: String,
        size: String,
        barcode: String,
        searchQuery: String,
        source: String,
    ) = MymavScanVision.ProductAnalysis(
        title = title,
        brand = brand,
        productName = productName.ifBlank { title },
        variant = variant,
        size = size,
        detail = listOf(brand, productName, variant, size).filter { it.isNotBlank() }.joinToString(" · "),
        priceHint = "",
        barcode = barcode,
        labelText = "",
        searchQuery = searchQuery.ifBlank {
            listOf(brand, productName.ifBlank { title }, variant, size).filter { it.isNotBlank() }.joinToString(" ")
        },
        matchQuality = if (brand.isNotBlank() && (productName.isNotBlank() || title.isNotBlank())) "exact" else "possible",
        needsCloserLook = false,
        recaptureHint = "",
        uncertainty = "From Identify",
        source = source,
        raw = "",
    )

    private fun MymavScanVision.ProductAnalysis.toResult(
        offers: List<MymavShopOffers.Offer>,
        disclaimer: String,
        providerReady: Boolean,
        lookupFailed: Boolean = false,
    ) = ShopScanResult(
        title = title,
        brand = brand,
        productName = productName,
        variant = variant,
        size = size,
        detail = detail,
        priceHint = priceHint,
        barcode = barcode,
        searchQuery = searchQuery,
        matchQuality = matchQuality,
        source = source,
        uncertainty = uncertainty,
        offers = offers,
        offerDisclaimer = disclaimer,
        providerReady = providerReady,
        lookupFailed = lookupFailed,
    )

    private fun ShopScanResult.toAnalysis() = MymavScanVision.ProductAnalysis(
        title = title,
        brand = brand,
        productName = productName,
        variant = variant,
        size = size,
        detail = detail,
        priceHint = priceHint,
        barcode = barcode,
        labelText = "",
        searchQuery = searchQuery,
        matchQuality = matchQuality,
        needsCloserLook = false,
        recaptureHint = "",
        uncertainty = uncertainty,
        source = source,
        raw = "",
    )
}
