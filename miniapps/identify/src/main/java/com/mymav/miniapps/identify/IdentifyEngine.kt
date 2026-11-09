package com.mymav.miniapps.identify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.mymav.glasses.api.AppEnterActions
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.HudReadableAttribute
import com.mymav.glasses.api.HudReadableCard
import com.mymav.glasses.api.HudReadableMode
import com.mymav.glasses.api.IdentifyHudActions
import com.mymav.glasses.api.IdentifyImage
import com.mymav.glasses.api.MymavAi
import com.mymav.glasses.api.MymavCapture
import com.mymav.glasses.api.MymavScanVision
import com.mymav.glasses.api.MymavVision
import com.mymav.glasses.api.miniAppHomeActions
import com.mymav.miniapps.shop.ShopScanEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scan → identify the most specific product → Rescan · Details · Find prices.
 * Frames come from the shared capture service (or a hand-off from Food); Find prices passes the
 * product and the same photo to Shop. Never guesses from bottle shape alone.
 */
object IdentifyEngine {
    data class Result(
        val label: String,
        val confidence: Float,
        val detail: String,
        val brand: String,
        val productName: String,
        val variant: String,
        val size: String,
        val barcode: String,
        val searchQuery: String,
        val confirmed: Boolean,
        val needsCloserLook: Boolean,
        val recaptureHint: String,
        val images: List<IdentifyImage>,
        val source: String,
        val heldJpeg: ByteArray? = null,
    )

    private val sessionGen = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var lastResult: Result? = null
        private set

    @Volatile
    private var showingDetails: Boolean = false
    @Volatile
    private var detailsPage: Int = 0
    @Volatile
    private var footerFocus: Int = 2
    @Volatile
    private var hudSession: GlassesSession? = null
    @Volatile
    private var scanJob: Job? = null
    @Volatile
    private var idleFocus: Int = 0

    private val busy get() = scanJob?.isActive == true

    /** Glasses open: a frame handed over by Food is identified straight away, otherwise Scan · Last result. */
    fun enter(session: GlassesSession) {
        hudSession = session
        showingDetails = false
        detailsPage = 0
        idleFocus = 0
        if (MymavCapture.takeHandoff()?.let { scanFromGlasses(session, it) } == null) pushIdleHud(session)
    }

    private fun idleFooter() = listOfNotNull("Scan", if (lastResult?.let { usable(it) } == true) "Last result" else null)

    fun pushIdleHud(session: GlassesSession) {
        hudSession = session
        val footer = idleFooter()
        val last = lastResult?.takeIf { usable(it) }
        val body = "Look at the object or product label, then Scan." +
            (last?.let { " Last: ${it.label}".take(60) } ?: "")
        showCard(
            session, "", "READY", body, footer, idleFocus.coerceIn(0, footer.lastIndex),
            onActivate = { idx ->
                when (footer.getOrNull(idx)) {
                    "Scan" -> scanFromGlasses(session)
                    "Last result" -> lastResult?.let { pushResultHud(session, it) }
                }
            },
            onFocus = { idx ->
                idleFocus = idx
                pushIdleHud(session)
            },
        )
    }

    private fun pushBusy(session: GlassesSession, chip: String, text: String) {
        showCard(session, chip, chip.uppercase(), text, listOf("Cancel"), 0, onActivate = {
            cancel()
            pushIdleHud(session)
        })
    }

    private fun showCard(
        session: GlassesSession,
        chip: String,
        eyebrow: String,
        body: String,
        footer: List<String>,
        focus: Int,
        onActivate: (Int) -> Unit,
        onFocus: ((Int) -> Unit)? = null,
    ) {
        if (!GlassesHomeController.acceptsMiniAppHud("identify")) return
        val base = miniAppHomeActions()
        val hud = GlassesHud.AppEnter(
            appId = "identify",
            title = "Identify",
            subtitle = chip,
            accent = HudAccentTone.Teal,
            readableMode = HudReadableMode.Conversate,
            cards = listOf(HudReadableCard(eyebrow = eyebrow, body = body, fullBody = body)),
            footerLabels = footer,
            footerFocus = focus,
            actions = AppEnterActions(
                onHomeRequested = base.onHomeRequested,
                onGazeNormalized = base.onGazeNormalized,
                onFooterFocusChanged = onFocus,
                onFooterActivated = { idx -> scope.launch { onActivate(idx) } },
            ),
        )
        session.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    private fun usable(r: Result) = r.label !in listOf("No frame", "AI key needed", "Unavailable", "Cancelled")

    /** Scan from the glasses: [frame] when handed over, else the shared capture service. */
    fun scanFromGlasses(session: GlassesSession? = hudSession, frame: MymavVision.Frame? = null): Job? {
        val s = session ?: return null
        if (busy) return null
        showingDetails = false
        detailsPage = 0
        footerFocus = 2
        return scope.launch { runIdentify(s, frame = frame) }.also { scanJob = it }
    }

    /**
     * @param jpeg phone CameraX bytes when non-null; else tries glasses/sim frame
     * @param phoneSourceLabel shown discreetly as the result source
     */
    suspend fun runIdentify(
        session: GlassesSession,
        jpeg: ByteArray? = null,
        phoneSourceLabel: String = MymavCapture.PHONE_TEST_LABEL,
        frame: MymavVision.Frame? = null,
    ): Result {
        val gen = sessionGen.incrementAndGet()
        hudSession = session
        GlassesHomeController.markMiniAppOpen("identify")
        if (!MymavAi.isConfigured()) {
            val result = Result(
                label = "AI key needed",
                confidence = 0f,
                detail = "Add a ChatGPT or Gemini key in Settings for live Identify.",
                brand = "", productName = "", variant = "", size = "", barcode = "",
                searchQuery = "", confirmed = false, needsCloserLook = false, recaptureHint = "",
                images = emptyList(),
                source = "none",
            )
            lastResult = result
            pushResultHud(session, result)
            return result
        }

        val result = run {
            val shot = when {
                frame != null -> frame
                jpeg != null && jpeg.isNotEmpty() -> MymavVision.Frame(jpeg, phoneSourceLabel)
                else -> {
                    pushBusy(session, "Scanning", "Capturing what you're looking at...")
                    val c = MymavCapture.capture()
                    c.frame ?: run { MymavVision.lastProblem = c.problem; null }
                }
            }
            val frame = shot
            if (sessionGen.get() != gen) return cancelledResult()
            if (frame == null) {
                Result(
                    label = "No frame",
                    confidence = 0f,
                    detail = MymavVision.lastProblem.ifBlank {
                        "No camera frame. Keep MyMav open on the phone and Rescan."
                    },
                    brand = "", productName = "", variant = "", size = "", barcode = "",
                    searchQuery = "", confirmed = false, needsCloserLook = false, recaptureHint = "",
                    images = emptyList(),
                    source = "none",
                )
            } else {
                pushBusy(session, "Identifying", "Reading the label for brand, variant and size...")
                val a = withContext(Dispatchers.IO) { MymavScanVision.analyseIdentify(frame) }
                if (sessionGen.get() != gen) return cancelledResult()
                val identity = buildList {
                    if (a.brand.isNotBlank()) add(a.brand)
                    if (a.productName.isNotBlank()) add(a.productName)
                    if (a.variant.isNotBlank()) add(a.variant)
                    if (a.size.isNotBlank()) add(a.size)
                }.joinToString(" · ")
                val status = when {
                    a.needsCloserLook -> a.recaptureHint.ifBlank { "Move closer to front label" }
                    a.confirmed -> "Confirmed"
                    else -> "Uncertain — verify label"
                }
                Result(
                    label = a.label.takeUnless { it.isBlank() || it.equals("unknown", true) } ?: "Not recognised",
                    confidence = a.confidence,
                    detail = listOf(identity, a.detail, status, "Source: ${a.source}")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    brand = a.brand,
                    productName = a.productName,
                    variant = a.variant,
                    size = a.size,
                    barcode = a.barcode,
                    searchQuery = a.searchQuery.ifBlank { a.label },
                    confirmed = a.confirmed && !a.needsCloserLook,
                    needsCloserLook = a.needsCloserLook,
                    recaptureHint = a.recaptureHint,
                    images = listOf(
                        photoThumb(frame.jpeg, a.label)
                            ?: syntheticThumb(a.label, Color.parseColor("#1A6B6B"), Color.parseColor("#0E2A2A")),
                    ),
                    source = a.source,
                    heldJpeg = frame.jpeg,
                )
            }
        }
        if (sessionGen.get() != gen) return cancelledResult()
        lastResult = result
        pushResultHud(session, result)
        return result
    }

    /** @deprecated Use [runIdentify]; kept name for phone button compatibility. */
    suspend fun runDemoIdentify(session: GlassesSession): Result = runIdentify(session)

    fun cancel() {
        sessionGen.incrementAndGet()
        scanJob?.cancel()
    }

    /** Glasses Find prices: Shop opens with this product and photo — no recapture, no re-guess. */
    private fun findPricesOnGlasses(r: Result) {
        ShopScanEngine.findPricesFromIdentify(
            title = r.label,
            brand = r.brand,
            productName = r.productName.ifBlank { r.label },
            variant = r.variant,
            size = r.size,
            barcode = r.barcode,
            searchQuery = r.searchQuery,
            frame = r.heldJpeg?.let { MymavVision.Frame(it, r.source) },
        )
    }

    suspend fun findPrices(session: GlassesSession): String {
        val r = lastResult ?: return "Identify a product first"
        if (r.needsCloserLook || r.label in listOf("No frame", "AI key needed", "Unavailable", "Cancelled")) {
            return r.recaptureHint.ifBlank { "Need a clearer label before price lookup" }
        }
        val shop = ShopScanEngine.findPricesForProduct(
            session = session,
            title = r.label,
            brand = r.brand,
            productName = r.productName.ifBlank { r.label },
            variant = r.variant,
            size = r.size,
            barcode = r.barcode,
            searchQuery = r.searchQuery,
            source = "identify→shop",
        )
        return when {
            shop == null -> ShopScanEngine.lastError.ifBlank { "Price lookup failed" }
            shop.offers.isNotEmpty() -> "Offers: ${shop.offers.size} on Shop HUD"
            !shop.providerReady -> shop.offerDisclaimer
            else -> shop.offerDisclaimer.ifBlank { "No verified prices found" }
        }
    }

    fun pushResultHud(session: GlassesSession, result: Result) {
        if (!GlassesHomeController.acceptsMiniAppHud("identify")) return
        hudSession = session
        val canPrice = result.confirmed || (!result.needsCloserLook && result.searchQuery.isNotBlank())
        val productType = guessType(result)
        val attrs = buildList {
            if (result.size.isNotBlank()) add(HudReadableAttribute("Size", result.size))
            if (productType.isNotBlank()) add(HudReadableAttribute("Type", productType))
            add(
                HudReadableAttribute(
                    "Barcode",
                    result.barcode.ifBlank { "not captured" },
                ),
            )
            if (result.variant.isNotBlank()) add(HudReadableAttribute("Variant", result.variant))
            if (result.confirmed) add(HudReadableAttribute("Status", "Confirmed"))
            else if (result.needsCloserLook) add(HudReadableAttribute("Status", "Needs clearer view"))
            else add(HudReadableAttribute("Status", "Uncertain"))
        }
        val instruction = when {
            result.needsCloserLook -> result.recaptureHint.ifBlank { "Show the barcode to confirm the variant." }
            !result.confirmed -> result.recaptureHint.ifBlank { "Verify front label · rescan if wrong" }
            result.barcode.isBlank() -> "Show the barcode to confirm the variant."
            else -> ""
        }
        val detailsBody = buildString {
            appendLine(result.label)
            if (result.brand.isNotBlank()) appendLine("Brand: ${result.brand}")
            if (result.productName.isNotBlank()) appendLine("Name: ${result.productName}")
            if (result.variant.isNotBlank()) appendLine("Variant: ${result.variant}")
            if (result.size.isNotBlank()) appendLine("Size: ${result.size}")
            if (result.barcode.isNotBlank()) appendLine("Barcode: ${result.barcode}")
            appendLine(result.detail)
            if (instruction.isNotBlank()) {
                appendLine()
                append(instruction)
            }
        }.trim()
        val pageCount = estimateDetailPages(detailsBody)
        val hud = GlassesHud.IdentifyResult(
            label = result.label,
            confidence = result.confidence,
            detail = result.detail,
            images = result.images,
            accent = HudAccentTone.Teal,
            headerTitle = "Objects",
            brand = result.brand,
            productName = result.productName.ifBlank { result.label },
            variant = result.variant,
            size = result.size,
            barcode = result.barcode,
            productType = productType,
            identityEyebrow = when {
                result.needsCloserLook -> "NEEDS CLEARER VIEW"
                result.confirmed -> "IDENTIFIED FROM LABEL"
                else -> "POSSIBLE MATCH"
            },
            uncertainty = if (result.confirmed) "" else "Uncertain — verify label",
            nextInstruction = instruction,
            attributes = attrs,
            showingDetails = showingDetails,
            detailsBody = detailsBody,
            contentPage = detailsPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
            contentPageCount = pageCount,
            footerLabels = listOf("Rescan", "Details", "Find prices"),
            footerDisabled = if (canPrice) emptySet() else setOf(2),
            footerFocus = footerFocus.coerceIn(0, 2),
            actions = IdentifyHudActions(
                onAgain = { scanFromGlasses(session) },
                onFindPrices = if (canPrice) {
                    { findPricesOnGlasses(result) }
                } else {
                    null
                },
                onDetails = {
                    if (showingDetails) {
                        // Page through full answer; wrap closes details.
                        if (detailsPage + 1 < pageCount) {
                            detailsPage += 1
                        } else {
                            showingDetails = false
                            detailsPage = 0
                        }
                    } else {
                        showingDetails = true
                        detailsPage = 0
                    }
                    lastResult?.let { pushResultHud(session, it) }
                },
                onFooterFocusChanged = {
                    footerFocus = it.coerceIn(0, 2)
                    lastResult?.let { pushResultHud(session, it) }
                },
                onFooterActivated = { idx ->
                    when (idx) {
                        0 -> scanFromGlasses(session)
                        1 -> {
                            // Same as onDetails
                            if (showingDetails) {
                                if (detailsPage + 1 < pageCount) detailsPage += 1
                                else {
                                    showingDetails = false
                                    detailsPage = 0
                                }
                            } else {
                                showingDetails = true
                                detailsPage = 0
                            }
                            lastResult?.let { pushResultHud(session, it) }
                        }
                        2 -> if (canPrice) findPricesOnGlasses(result)
                    }
                },
                onContentPageChanged = {
                    detailsPage = it.coerceAtLeast(0)
                    lastResult?.let { pushResultHud(session, it) }
                },
                onDismiss = { GlassesHomeController.requestHomeFromApp() },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
            ),
        )
        session.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    private fun guessType(result: Result): String {
        val blob = listOf(result.label, result.detail, result.variant, result.productName)
            .joinToString(" ").lowercase()
        return when {
            listOf("edp", "edt", "parfum", "perfume", "cologne", "fragrance").any { it in blob } -> "fragrance"
            listOf("ml", "oz").any { it in blob } && result.size.isNotBlank() -> "fragrance"
            result.productName.isNotBlank() || result.brand.isNotBlank() -> "product"
            else -> ""
        }
    }

    private fun estimateDetailPages(body: String): Int {
        // Approximate pages for ~8 lines at readable body scale; screen recomputes precisely.
        val approxLines = body.split('\n').sumOf { line ->
            (line.length / 48).coerceAtLeast(1)
        }.coerceAtLeast(1)
        return ((approxLines + 7) / 8).coerceAtLeast(1)
    }

    private fun cancelledResult() = Result(
        label = "Cancelled",
        confidence = 0f,
        detail = "Identify cancelled",
        brand = "", productName = "", variant = "", size = "", barcode = "",
        searchQuery = "", confirmed = false, needsCloserLook = false, recaptureHint = "",
        images = emptyList(),
        source = "none",
    )

    /** Centre-cropped square of the captured photo, so the result shows what was actually scanned. */
    private fun photoThumb(jpeg: ByteArray, caption: String, size: Int = 96): IdentifyImage? = runCatching {
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val edge = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - edge) / 2, (src.height - edge) / 2, edge, edge)
        val bmp = Bitmap.createScaledBitmap(square, size, size, true)
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)
        val rgba = ByteArray(size * size * 4)
        var i = 0
        for (p in pixels) {
            rgba[i++] = ((p shr 16) and 0xFF).toByte()
            rgba[i++] = ((p shr 8) and 0xFF).toByte()
            rgba[i++] = (p and 0xFF).toByte()
            rgba[i++] = ((p shr 24) and 0xFF).toByte()
        }
        IdentifyImage(bytes = rgba, width = size, height = size, caption = caption)
    }.getOrNull()

    fun syntheticThumb(caption: String, c0: Int, c1: Int, size: Int = 96): IdentifyImage {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            c0, c1, Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 18f, 18f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = 14f
        paint.isFakeBoldText = true
        canvas.drawText(caption.take(10), 10f, size / 2f + 5f, paint)
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)
        val rgba = ByteArray(size * size * 4)
        var i = 0
        for (p in pixels) {
            rgba[i++] = ((p shr 16) and 0xFF).toByte()
            rgba[i++] = ((p shr 8) and 0xFF).toByte()
            rgba[i++] = (p and 0xFF).toByte()
            rgba[i++] = ((p shr 24) and 0xFF).toByte()
        }
        return IdentifyImage(bytes = rgba, width = size, height = size, caption = caption)
    }
}
