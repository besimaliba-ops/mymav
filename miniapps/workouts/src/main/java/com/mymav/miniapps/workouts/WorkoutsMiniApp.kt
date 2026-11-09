package com.mymav.miniapps.workouts

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniApp
import com.mymav.glasses.api.MiniAppDescriptor
import com.mymav.glasses.api.RunningPaceHudActions
import com.mymav.glasses.api.WeightExerciseHudItem
import com.mymav.glasses.api.WeightSessionHudActions
import com.mymav.glasses.api.WorkoutKind
import com.mymav.glasses.api.WorkoutSelectHudActions
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

object WorkoutsMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "workouts",
        title = "Workouts",
        subtitle = "Run · Lift pace HUD",
        route = "mini/workouts",
        requiredCapabilities = listOf("hud"),
    )

    @Volatile
    var store: WeightSessionStore? = null

    @Volatile
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var selectRunnable: Runnable? = null
    private var selectTickRunnable: Runnable? = null
    private var selectFocus: Int = 0

    /** Live weight session (runtime set completion). */
    private var weightItems: List<WeightExerciseHudItem> = emptyList()
    private var weightFocus: Int = 0
    private var selectStartedAt: Long = 0L

    /** Demo run clock — seconds since “start”. */
    private var demoElapsedSec: Int = 16 * 60 + 57
    private var demoPaceSecPerKm: Int = 4 * 60 + 57
    private val demoTargetSecPerKm: Int = 5 * 60
    private var demoDistanceM: Double = 3410.0
    private var demoSplitM: Int = 585

    fun ensureStore(context: Context): WeightSessionStore {
        val app = context.applicationContext
        appContext = app
        val existing = store
        if (existing != null) return existing
        return WeightSessionStore(app).also { store = it }
    }

    private fun storeOrNull(): WeightSessionStore? {
        store?.let { return it }
        val ctx = appContext ?: return null
        return WeightSessionStore(ctx).also { store = it }
    }

    override fun onEnter(session: GlassesSession) {
        stopDemoTick()
        cancelSelectGaze()
        selectFocus = 0
        GlassesHomeController.markMiniAppOpen("workouts")
        pushSelectHud(session)
    }

    override fun onLeave(session: GlassesSession) {
        stopDemoTick()
        cancelSelectGaze()
    }

    fun pushSelectHud(session: GlassesSession) {
        stopDemoTick()
        cancelSelectGaze()
        selectFocus = selectFocus.coerceIn(0, 1)
        session.replaceHud(
            GlassesHud.WorkoutSelect(
                focusedIndex = selectFocus,
                actions = WorkoutSelectHudActions(
                    onFocusChanged = { idx ->
                        selectFocus = idx.coerceIn(0, 1)
                        pushSelectHud(session)
                    },
                    onSelect = { kind ->
                        when (kind) {
                            WorkoutKind.Running -> startRunning(session)
                            WorkoutKind.WeightTraining -> startWeightSession(session)
                        }
                    },
                    onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                    onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
                ),
            ),
        )
    }

    fun startRunning(session: GlassesSession) {
        cancelSelectGaze()
        GlassesHomeController.markMiniAppOpen("workouts")
        demoElapsedSec = 16 * 60 + 57
        demoPaceSecPerKm = 4 * 60 + 57
        demoDistanceM = 3410.0
        demoSplitM = 585
        pushRunningHud(session)
        startDemoTick(session)
    }

    /** Open Weight Session HUD from store (demo Bench 5×5 chest if empty). */
    fun startWeightSession(session: GlassesSession) {
        stopDemoTick()
        cancelSelectGaze()
        GlassesHomeController.markMiniAppOpen("workouts")
        val plan = storeOrNull()?.loadOrDemo() ?: listOf(WeightPlanExercise.demoBench())
        weightItems = plan.map { it.toHudItem() }
        weightFocus = 0
        pushWeightHud(session)
    }

    @Deprecated("Use startWeightSession", ReplaceWith("startWeightSession(session)"))
    fun pushWeightStub(session: GlassesSession) = startWeightSession(session)

    private fun pushWeightHud(
        session: GlassesSession,
        selectProgress: Float = 0f,
        keepSelectGaze: Boolean = false,
    ) {
        if (!keepSelectGaze) cancelSelectGaze()
        val maxFocus = (weightItems.size - 1).coerceAtLeast(0)
        weightFocus = weightFocus.coerceIn(0, maxFocus)
        val focused = weightItems.getOrNull(weightFocus)
        val remaining = weightItems.sumOf { it.setsRemaining }
        val hud = GlassesHud.WeightSession(
            exercises = weightItems,
            focusedIndex = weightFocus,
            selectDwellProgress = selectProgress,
            statusLine = when {
                weightItems.isEmpty() -> "No exercises"
                remaining == 0 -> "Session complete"
                focused != null ->
                    "${focused.setsRemaining} sets left · ${focused.bodyPart.label}"
                else -> "TAP OR 1S · NEXT SET"
            },
            actions = WeightSessionHudActions(
                onFocusChanged = { idx ->
                    weightFocus = idx.coerceIn(0, maxFocus)
                    pushWeightHud(session)
                },
                onToggleSet = { exerciseId, setIndex ->
                    toggleSet(exerciseId, setIndex)
                    pushWeightHud(session)
                },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y ->
                    feedSelectGaze(session)
                    GlassesHomeController.feedAppHomeGaze(x, y)
                },
                onSelectFocused = { completeNextSet(session) },
            ),
        )
        GlassesHomeController.rememberAppHud(hud)
        session.replaceHud(hud)
    }

    private fun toggleSet(exerciseId: String, setIndex: Int) {
        weightItems = weightItems.map { ex ->
            if (ex.id != exerciseId) return@map ex
            if (setIndex !in ex.setsDone.indices) return@map ex
            val next = ex.setsDone.toMutableList()
            next[setIndex] = !next[setIndex]
            ex.copy(setsDone = next)
        }
    }

    private fun completeNextSet(session: GlassesSession) {
        cancelSelectGaze()
        val ex = weightItems.getOrNull(weightFocus) ?: return
        val nextIncomplete = ex.setsDone.indexOfFirst { !it }
        if (nextIncomplete < 0) {
            // Advance to next exercise with remaining sets, if any.
            val nextEx = weightItems.indexOfFirst { it.setsRemaining > 0 }
            if (nextEx >= 0) {
                weightFocus = nextEx
                pushWeightHud(session)
            }
            return
        }
        toggleSet(ex.id, nextIncomplete)
        // Auto-advance focus when this exercise finishes.
        val updated = weightItems.getOrNull(weightFocus)
        if (updated != null && updated.setsRemaining == 0) {
            val nextEx = weightItems.indexOfFirst { it.setsRemaining > 0 }
            if (nextEx >= 0) weightFocus = nextEx
        }
        pushWeightHud(session)
    }

    /** Arm 1s select while LOS is feeding gaze; updates [selectDwellProgress]. */
    private fun feedSelectGaze(session: GlassesSession) {
        if (selectRunnable != null) return
        selectStartedAt = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - selectStartedAt).toFloat()
                val progress = (elapsed / 1_000f).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    selectTickRunnable = null
                    selectRunnable = null
                    completeNextSet(session)
                } else {
                    pushWeightHud(
                        session,
                        selectProgress = progress.coerceAtLeast(0.05f),
                        keepSelectGaze = true,
                    )
                    selectTickRunnable = this
                    mainHandler.postDelayed(this, 80L)
                }
            }
        }
        selectRunnable = tick
        selectTickRunnable = tick
        mainHandler.post(tick)
    }

    private fun cancelSelectGaze() {
        selectRunnable?.let { mainHandler.removeCallbacks(it) }
        selectTickRunnable?.let { mainHandler.removeCallbacks(it) }
        selectRunnable = null
        selectTickRunnable = null
        selectStartedAt = 0L
    }

    private fun pushRunningHud(session: GlassesSession) {
        session.replaceHud(buildRunningPace())
    }

    private fun buildRunningPace(): GlassesHud.RunningPace {
        val deltaSec = ((demoTargetSecPerKm - demoPaceSecPerKm) * (demoDistanceM / 1000.0))
            .roundToInt()
        val ahead = deltaSec >= 0
        val absDelta = abs(deltaSec)
        val status = if (ahead) "AHEAD" else "BEHIND"
        val sign = if (ahead) "-" else "+"
        return GlassesHud.RunningPace(
            statusLabel = status,
            deltaLabel = "$sign${formatClock(absDelta)}",
            paceLabel = formatPace(demoPaceSecPerKm),
            paceUnit = "/KM",
            targetLabel = "TGT ${formatPace(demoTargetSecPerKm)}",
            paceSliderNorm = paceSliderNorm(demoPaceSecPerKm, demoTargetSecPerKm),
            elapsedLabel = formatClock(demoElapsedSec),
            splitMeters = demoSplitM,
            distanceKmLabel = String.format("%.2f", demoDistanceM / 1000.0),
            actions = RunningPaceHudActions(
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
                onCycleView = null,
            ),
        )
    }

    private fun startDemoTick(session: GlassesSession) {
        stopDemoTick()
        val tick = object : Runnable {
            override fun run() {
                demoElapsedSec += 1
                demoDistanceM += 1000.0 / demoPaceSecPerKm.coerceAtLeast(1)
                demoSplitM = ((demoDistanceM % 1000.0).roundToInt()).coerceIn(0, 999)
                if (Random.nextInt(3) == 0) {
                    demoPaceSecPerKm = (4 * 60 + 57 + Random.nextInt(-2, 3)).coerceIn(4 * 60 + 50, 5 * 60 + 10)
                }
                pushRunningHud(session)
                tickRunnable = this
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        tickRunnable = tick
        mainHandler.postDelayed(tick, 1_000L)
    }

    private fun stopDemoTick() {
        tickRunnable?.let { mainHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun paceSliderNorm(paceSec: Int, targetSec: Int): Float {
        val delta = (targetSec - paceSec).toFloat()
        return (0.5f + delta / 60f).coerceIn(0.08f, 0.92f)
    }

    private fun formatPace(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun formatClock(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
