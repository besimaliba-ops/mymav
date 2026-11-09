package com.mymav.miniapps.food

import android.content.Context
import com.mymav.glasses.api.AppEnterActions
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.HudAccentTone
import com.mymav.glasses.api.HudReadableCard
import com.mymav.glasses.api.HudReadableMetric
import com.mymav.glasses.api.HudReadableMode
import com.mymav.glasses.api.HudReadableProduct
import com.mymav.glasses.api.MymavAi
import com.mymav.glasses.api.MymavCapture
import com.mymav.glasses.api.MymavScanVision
import com.mymav.glasses.api.MymavVision
import com.mymav.glasses.api.ScanCameraSession
import com.mymav.glasses.api.miniAppHomeActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

enum class FoodScanPhase {
    Idle,
    Ready,
    Camera,
    Scanning,
    Analysing,
    Result,
    Error,
    PermissionNeeded,
    NoCamera,
    Cancelled,
}

data class FoodScanResult(
    val label: String,
    val kcal: Int?,
    val proteinG: Int?,
    val carbsG: Int?,
    val fatG: Int?,
    val healthScore: Int?,
    val source: String,
    val uncertainty: String,
    val notes: String = "",
    val kind: String = "meal",
    val portion: String = "",
) {
    val isFood: Boolean get() = kind == "meal" || kind == "packaged_food" || kind == "drink"
}

/** Health Connect save state for the current scan; "Saved" is shown only after a confirmed read-back. */
enum class FoodHealthState { None, Saving, Confirmed, NeedsPermission, Failed }

/**
 * Food on the glasses: Scan → estimate (name, portion, kcal, macros) → Rescan · Portion · Save to Health.
 * Every action is a footer pill (tap or 1s gaze). Non-food (supplements, objects) is never logged as a meal.
 */
object FoodScanEngine {
    private val PORTIONS = listOf(1.0, 1.5, 2.0, 0.5)

    @Volatile
    var phase: FoodScanPhase = FoodScanPhase.Idle
        private set

    @Volatile
    var lastResult: FoodScanResult? = null
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile var health: FoodHealthState = FoodHealthState.None
        private set
    @Volatile var healthMessage: String = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var hudSession: GlassesSession? = null
    private var scanJob: Job? = null
    private var saveJob: Job? = null
    @Volatile private var cancelled = false
    private var busyText = ""
    private var lastFrame: MymavVision.Frame? = null
    private var scanId = ""
    private var scanAt: Instant = Instant.EPOCH
    private var portionIdx = 0
    private var savedPortionIdx = -1
    private var saveVersion = 0L
    private var focus = 0

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun cancel() {
        cancelled = true
        scanJob?.cancel()
        if (phase == FoodScanPhase.Scanning || phase == FoodScanPhase.Analysing || phase == FoodScanPhase.Camera) {
            phase = FoodScanPhase.Cancelled
            lastError = "Scan cancelled"
        }
    }

    fun reset() {
        cancelled = false
        phase = FoodScanPhase.Idle
        lastError = ""
        focus = 0
        ScanCameraSession.release("food")
    }

    fun markReady() {
        phase = FoodScanPhase.Ready
        lastError = ""
    }

    fun markPermissionNeeded() {
        phase = FoodScanPhase.PermissionNeeded
        lastError = "Camera permission needed"
    }

    fun markNoCamera(reason: String) {
        phase = FoodScanPhase.NoCamera
        lastError = reason
    }

    // ---- glasses actions ------------------------------------------------------------------------------------

    private fun footer(): List<String> = when (phase) {
        FoodScanPhase.Camera, FoodScanPhase.Scanning, FoodScanPhase.Analysing -> listOf("Cancel")
        FoodScanPhase.Result -> {
            val r = lastResult
            when {
                r != null && !r.isFood -> listOf("Rescan", "Identify")
                r != null && r.kcal == null -> listOf("Rescan")
                else -> listOf("Rescan", "Portion", saveLabel())
            }
        }
        FoodScanPhase.Error, FoodScanPhase.NoCamera, FoodScanPhase.PermissionNeeded, FoodScanPhase.Cancelled ->
            listOfNotNull("Rescan", if (lastResult != null) "Last meal" else null)
        else -> listOfNotNull("Scan", if (lastResult != null) "Last meal" else null)
    }

    private fun saveLabel(): String = when {
        health == FoodHealthState.Saving -> "Saving"
        health == FoodHealthState.Confirmed && savedPortionIdx == portionIdx -> "Saved"
        health == FoodHealthState.Confirmed -> "Update Health"
        health == FoodHealthState.Failed || health == FoodHealthState.NeedsPermission -> "Retry save"
        else -> "Save to Health"
    }

    private fun onFooter(index: Int) {
        val label = footer().getOrNull(index) ?: return
        when (label) {
            "Scan", "Rescan" -> scanFromGlasses()
            "Cancel" -> {
                cancel()
                pushHud()
            }
            "Last meal" -> {
                phase = FoodScanPhase.Result
                focus = 0
                pushHud()
            }
            "Portion" -> {
                portionIdx = (portionIdx + 1) % PORTIONS.size
                pushHud()
            }
            "Save to Health", "Update Health", "Retry save" -> saveToHealth()
            "Identify" -> {
                lastFrame?.let { MymavCapture.handOff(it) }
                GlassesHomeController.switchToApp("identify")
            }
        }
    }

    private fun actions(): AppEnterActions {
        val base = miniAppHomeActions()
        return AppEnterActions(
            onHomeRequested = base.onHomeRequested,
            onGazeNormalized = base.onGazeNormalized,
            onFooterFocusChanged = { idx ->
                focus = idx.coerceIn(0, footer().lastIndex)
                pushHud()
            },
            onFooterActivated = { idx -> scope.launch { onFooter(idx) } },
        )
    }

    /** Scan started from the glasses: shared capture service (phone camera test input, else glasses feed). */
    fun scanFromGlasses(session: GlassesSession? = hudSession) {
        if (scanJob?.isActive == true || health == FoodHealthState.Saving) return
        val s = session ?: return
        val ctx = appContext ?: return
        scanJob = scope.launch { runLiveScan(s, ctx, jpeg = null) }
    }

    private fun saveToHealth() {
        val ctx = appContext ?: return
        val result = lastResult ?: return
        if (!result.isFood || result.kcal == null || saveJob?.isActive == true) return
        if (health == FoodHealthState.Confirmed && savedPortionIdx == portionIdx) return
        health = FoodHealthState.Saving
        healthMessage = "Saving to Health Connect..."
        pushHud()
        val idx = portionIdx
        saveJob = scope.launch {
            val outcome = FoodHealthWriter.save(ctx, scanId, ++saveVersion, scanAt, result, PORTIONS[idx])
            when (outcome) {
                is FoodHealthWriter.Outcome.Confirmed -> {
                    health = FoodHealthState.Confirmed
                    savedPortionIdx = idx
                    healthMessage = "In Health Connect. Samsung Health shows it after its next sync."
                    com.mymav.glasses.api.HealthNutritionBridge.notifyMealWritten()
                }
                is FoodHealthWriter.Outcome.NeedsPermission -> {
                    health = FoodHealthState.NeedsPermission
                    healthMessage = outcome.message
                }
                is FoodHealthWriter.Outcome.Failed -> {
                    health = FoodHealthState.Failed
                    healthMessage = outcome.message
                }
            }
            pushHud()
        }
    }

    // ---- HUDs -----------------------------------------------------------------------------------------------

    fun pushHud(session: GlassesSession? = hudSession) {
        val s = session ?: return
        hudSession = s
        if (!GlassesHomeController.acceptsMiniAppHud("food")) return
        val hud = when (phase) {
            FoodScanPhase.Result -> lastResult?.let { if (it.isFood) resultHud(it) else nonFoodHud(it) } ?: readyHud()
            FoodScanPhase.Camera, FoodScanPhase.Scanning, FoodScanPhase.Analysing -> busyHud()
            FoodScanPhase.Error, FoodScanPhase.NoCamera, FoodScanPhase.PermissionNeeded -> errorHud()
            FoodScanPhase.Cancelled -> readyHud(note = "Scan cancelled.")
            else -> readyHud()
        }
        s.replaceHud(hud)
        GlassesHomeController.rememberAppHud(hud)
    }

    private fun base(chip: String, cards: List<HudReadableCard>, disabled: Set<Int> = emptySet()): GlassesHud.AppEnter {
        val labels = footer()
        val f = focus.coerceIn(0, labels.lastIndex).let { if (it in disabled) (labels.indices.firstOrNull { i -> i !in disabled } ?: it) else it }
        return GlassesHud.AppEnter(
            appId = "food",
            title = "Food",
            subtitle = chip,
            accent = HudAccentTone.Orange,
            readableMode = HudReadableMode.Food,
            statusChip = chip,
            cards = cards,
            footerLabels = labels,
            footerFocus = f,
            footerDisabled = disabled,
            sourceLabel = lastFrame?.source.orEmpty(),
            actions = actions(),
        )
    }

    private fun readyHud(note: String = ""): GlassesHud.AppEnter {
        val last = lastResult?.takeIf { it.isFood }
        val cards = buildList {
            add(
                HudReadableCard(
                    eyebrow = "READY",
                    body = note.ifBlank { "Look at your meal, then Scan. Results are estimates." },
                ),
            )
            if (last != null) {
                add(HudReadableCard(eyebrow = "LAST MEAL", body = "${last.label} - ${scaled(last.kcal) ?: "?"} kcal"))
            }
        }
        return base("", cards).copy(sourceLabel = "")
    }

    private fun busyHud(): GlassesHud.AppEnter {
        val (eyebrow, chip) = when (phase) {
            FoodScanPhase.Analysing -> "ANALYSING" to "Analysing"
            else -> "SCANNING" to "Scanning"
        }
        val hud = base(chip, listOf(HudReadableCard(eyebrow = eyebrow, body = busyText.ifBlank { "Hold still for a moment." })))
        val frame = lastFrame
        return if (frame != null && phase == FoodScanPhase.Analysing) {
            hud.copy(
                product = HudReadableProduct(
                    title = "Meal",
                    meta = "Estimating nutrition…",
                    status = busyText.ifBlank { "Keep the photo — a new scan replaces it." },
                    thumbBytes = frame.jpeg,
                ),
            )
        } else {
            hud
        }
    }

    private fun errorHud(): GlassesHud.AppEnter =
        base(
            "Not scanned",
            listOf(HudReadableCard(eyebrow = "COULD NOT SCAN", body = lastError.ifBlank { "Scan failed. Try again." })),
        )

    private fun scaled(v: Int?): Int? = v?.let { (it * PORTIONS[portionIdx]).roundToInt() }

    private fun portionText(r: FoodScanResult): String {
        val factor = PORTIONS[portionIdx]
        val p = r.portion.ifBlank { "1 serving" }
        return if (factor == 1.0) p else "$p x${if (factor % 1.0 == 0.0) factor.toInt().toString() else factor.toString()}"
    }

    private fun resultHud(r: FoodScanResult): GlassesHud.AppEnter {
        val metrics = listOf(
            HudReadableMetric(scaled(r.kcal)?.toString() ?: "?", "kcal"),
            HudReadableMetric(scaled(r.proteinG)?.let { "${it}g" } ?: "?", "protein"),
            HudReadableMetric(scaled(r.carbsG)?.let { "${it}g" } ?: "?", "carbs"),
            HudReadableMetric(scaled(r.fatG)?.let { "${it}g" } ?: "?", "fat"),
        )
        val healthLine = when {
            r.kcal == null -> "Couldn't estimate calories. Rescan closer."
            health == FoodHealthState.None -> "Estimates from a photo, not medical advice."
            else -> healthMessage
        }
        val meta = portionText(r)
        val chip = when (health) {
            FoodHealthState.Saving -> "Saving"
            FoodHealthState.Confirmed -> if (savedPortionIdx == portionIdx) "Saved" else "Portion changed"
            FoodHealthState.NeedsPermission, FoodHealthState.Failed -> "Not saved"
            FoodHealthState.None -> "Estimate"
        }
        val disabled = when {
            health == FoodHealthState.Saving -> setOf(0, 1, 2)
            health == FoodHealthState.Confirmed && savedPortionIdx == portionIdx -> setOf(2)
            else -> emptySet()
        }
        return base(chip, listOf(HudReadableCard(eyebrow = "NOTES", body = r.notes)), disabled).copy(
            product = HudReadableProduct(
                title = r.label,
                meta = meta,
                status = healthLine,
                thumbBytes = lastFrame?.jpeg,
            ),
            metrics = metrics,
        )
    }

    private fun nonFoodHud(r: FoodScanResult): GlassesHud.AppEnter {
        val label = r.label.trim().takeUnless { it.isBlank() || it.equals("unknown", true) }
        val what = when {
            r.kind == "supplement" -> "a supplement"
            label != null -> label.lowercase()
            else -> "something other than food"
        }
        return base(
            "Not food",
            listOf(
                HudReadableCard(
                    eyebrow = "NOT A MEAL",
                    body = "This looks like $what, so nothing is logged. Rescan a meal or Identify it.",
                ),
            ),
        ).copy(
            product = HudReadableProduct(
                title = label ?: "Not a meal",
                meta = "No calories logged",
                status = "",
                thumbBytes = lastFrame?.jpeg,
            ),
            metrics = emptyList(),
        )
    }

    // ---- compatibility with the phone screen ----------------------------------------------------------------

    fun pushReadyHud(session: GlassesSession) {
        hudSession = session
        phase = FoodScanPhase.Ready
        pushHud(session)
    }

    fun pushPhaseHud(session: GlassesSession, phase: FoodScanPhase, detail: String = "") {
        hudSession = session
        this.phase = phase
        if (detail.isNotBlank()) busyText = detail
        pushHud(session)
    }

    fun pushResultHud(session: GlassesSession, result: FoodScanResult) {
        hudSession = session
        phase = FoodScanPhase.Result
        lastResult = result
        pushHud(session)
    }

    /**
     * Full flow: scan → analyse → result (or error). [jpeg] comes from the phone screen's own capture;
     * when null the shared capture service is used (phone camera test input, else glasses/sim frame).
     */
    suspend fun runLiveScan(
        session: GlassesSession,
        context: Context,
        jpeg: ByteArray?,
        phoneSourceLabel: String = MymavCapture.PHONE_TEST_LABEL,
    ): FoodScanResult? {
        hudSession = session
        if (appContext == null) appContext = context.applicationContext
        cancelled = false
        ScanCameraSession.tryAcquire("food")?.let { busy ->
            lastError = busy
            pushPhaseHud(session, FoodScanPhase.Error)
            return null
        }
        try {
            if (!MymavAi.isConfigured()) {
                lastError = "No AI key yet. Add ChatGPT or Gemini in MyMav Settings on the phone."
                pushPhaseHud(session, FoodScanPhase.Error)
                return null
            }
            focus = 0
            pushPhaseHud(session, FoodScanPhase.Scanning, "Capturing a photo of your meal...")
            val frame = if (jpeg != null && jpeg.isNotEmpty()) {
                MymavVision.Frame(jpeg, phoneSourceLabel)
            } else {
                val shot = MymavCapture.capture()
                shot.frame ?: run {
                    lastError = shot.problem
                    pushPhaseHud(session, FoodScanPhase.NoCamera)
                    return null
                }
            }
            if (cancelled) return null
            lastFrame = frame
            pushPhaseHud(session, FoodScanPhase.Analysing, "Estimating the portion, calories and macros...")
            val dietary = FoodDietaryPrefs.load(context).notesForPrompt()
            val analysis = withContext(Dispatchers.IO) { MymavScanVision.analyseFood(frame, dietary) }
            if (cancelled) return null
            if (analysis.label == "Analysis failed") {
                lastError = "Couldn't analyse the photo: ${analysis.uncertainty}"
                pushPhaseHud(session, FoodScanPhase.Error)
                return null
            }
            val result = FoodScanResult(
                label = analysis.label,
                kcal = analysis.kcal,
                proteinG = analysis.proteinG,
                carbsG = analysis.carbsG,
                fatG = analysis.fatG,
                healthScore = analysis.healthScore,
                source = analysis.source,
                uncertainty = analysis.uncertainty,
                notes = analysis.notes,
                kind = analysis.kind,
                portion = analysis.portion,
            )
            scanId = UUID.randomUUID().toString()
            scanAt = Instant.now()
            portionIdx = 0
            savedPortionIdx = -1
            saveVersion = 0L
            health = FoodHealthState.None
            healthMessage = ""
            focus = if (result.isFood) 2 else 0
            pushResultHud(session, result)
            return result
        } finally {
            ScanCameraSession.release("food")
        }
    }
}
