package com.mymav.glasses.api

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shell-owned glasses home board + shared gaze keyboard state machine.
 * Mini-apps open the keyboard via [openKeyboard]; home opens via [showHome].
 *
 * **Back to home from a mini-app:** 1s gaze dwell on the HOME pill, or long-tap fallback
 * (documented on HUD as `1S GAZE · HOME` / `LONG TAP · HOME`).
 * Phone shell is notified via [onReturnHome] so it can pop the mini-app route.
 *
 * **Gaze page turn:** 1s continuous dwell on left/right side of the 640×400 canvas
 * (normalized X ≤ [HomeBoardCatalog.GAZE_LEFT_MAX] / ≥ [HomeBoardCatalog.GAZE_RIGHT_MIN]).
 * Sim fallback when real LOS unavailable: [simulateSideGazeDwell], D-pad / arrow keys
 * (Up/Down/Left/Right → [M2Touch] mapped in HUD screens), or yaw→normalized X into [feedHomeGaze].
 */
object GlassesHomeController {
    /** Docked mascot — top-bar center (same line as date/time pills ~y12–48). */
    private const val AVATAR_REST_X = 320f
    /** Dock centre Y — sits between the status pills, clear of the tile grid (top 80). */
    private const val AVATAR_REST_Y = 40f
    /** Center Y while speaking / summoned — lower so the opaque bubble fits under. */
    private const val AVATAR_SUMMON_Y = 108f
    /** Draw/encode size while docked on the date/time line (larger, no plate). */
    private const val AVATAR_DOCK_SIZE = 72
    /** Larger while summoned so the bubble reads under the mascot. */
    private const val AVATAR_SUMMON_SIZE = 120
    /** Sleep mode — bottom-left corner, no page-run / jump motion. */
    private const val AVATAR_SLEEP_X = 56f
    private const val AVATAR_SLEEP_Y = 348f
    private const val AVATAR_HIT_R = 44f
    /** Idle animation: sprite-sheet cell swaps (no uploads), ~7 fps. */
    /** Head movement (px on the HUD) that hands focus back to gaze after D-pad input. */
    private const val GAZE_RESUME_PX = 24f
    /** Diagnostic: page turns without the run animation. */
    private const val DIAG_NO_RUN = false
    /** Docked mascot as a pose set (blink / wave / run frames) vs one image. */
    private const val DOCK_POSES = true
    private const val ARM_UNSET = "\u0000unset"
    private const val ARM_ARMED = "\u0000armed"
    private const val ACTION_GATE_MS = 900L
    /** Any action briefly blocks every other one, so a tap landing just after a gaze fire can't hit the next screen. */
    private const val ANY_ACTION_GATE_MS = 500L
    /** No gaze sample for this long = gaze left the HUD; cancel any action dwell. */
    private const val ACTION_LOST_MS = 450L
    private const val IDLE_FRAME_MS = 100L
    /** 140 ticks ≈ 14s: two blinks and one wave per cycle. */
    private const val IDLE_CYCLE_TICKS = 140
    /** Blink starts within the 140-tick cycle: two spaced blinks plus a quick double blink. */
    private val BLINK_STARTS = intArrayOf(38, 104, 111)
    /** Each blink = half-lid, closed, half-lid (one tick each). */
    private val BLINK_SEQ = arrayOf(
        MymavAvatarArt.Pose.BlinkHalf, MymavAvatarArt.Pose.Blink, MymavAvatarArt.Pose.BlinkHalf,
    )
    private const val WAVE_AT = 72
    /** Arm swing as wave-cell frames (1 = low, 3 = mid, 4 = high), pausing at each extreme. */
    private val WAVE_SEQ = intArrayOf(3, 4, 4, 3, 1, 1, 3, 4, 4, 3, 1, 1, 3, 4, 4, 3, 3, 3)
    private const val SUMMON_EASE_MS = 240L
    /** 28 ticks ≈ 2.8s per float cycle. */
    private const val IDLE_FLOAT_TICKS = 28
    /** Normalized Y at or above this band counts as glance-up (top of canvas). */
    private const val GAZE_UP_MAX = 0.22f
    private const val AVATAR_SUMMON_DWELL_MS = 1_000L
    /** Ask MyMav AUDIO / TEXT / CANCEL — same 1s dwell as other gaze controls. */
    private const val ASK_CHOICE_DWELL_MS = 1_000L

    private var session: GlassesSession? = null
    /** Mascot behaviour from Settings (Active / Sleep / Off). */
    private var avatarMode: MymavAvatarPrefs.Mode = MymavAvatarPrefs.Mode.Active
    private var pageIndex: Int = 0
    private var focusedWidgetId: String = HomeBoardCatalog.widgets.first().id
    private var keyboardBuffer: String = ""
    private var focusedKeyId: String = "a"
    private var voiceArmed: Boolean = false
    private var voiceMode: VoiceInputMode = VoiceInputMode.None
    private var voiceStatus: String = ""
    private var keyboardTitle: String = "Keyboard"
    private var keyboardMode: KeyboardMode = KeyboardMode.Letters
    private var keyboardPageIndex: Int = 0
    private var emojiCategoryId: String = "smileys"
    private var searchQuery: String = ""
    private var keyboardCapsLocked: Boolean = false
    private var keyboardDoneLabel: String = "OK"
    private var onKeyboardDone: ((String) -> Unit)? = null
    private var onKeyboardCancel: (() -> Unit)? = null
    /** When true, bottom Search submits buffer (Spotify songs) instead of emoji filter. */
    private var keyboardSearchSubmitsBuffer: Boolean = false
    private var onOpenMiniApp: ((String) -> Unit)? = null
    private var onReturnHome: (() -> Unit)? = null
    private var activeMiniAppId: String? = null
    private var surface: Surface = Surface.None
    private var cachedAppHud: GlassesHud? = null

    /** 1s gaze-hold activates the focused keyboard key (Tap alternative). */
    private var keyGazeKeyId: String? = null
    private var keyGazeStartedAt: Long = 0L
    private var keyGazeProgress: Float = 0f
    private var keyGazeRunnable: Runnable? = null
    private var keyGazeUiRunnable: Runnable? = null

    /** Idle mascot motion — blink ~2s, arms ~3s — without full board redraw. */
    private var avatarIdleRunnable: Runnable? = null

    private val pageDwell by lazy { GazeDwell(mainHandler) }
    private val openDwell by lazy { GazeDwell(mainHandler) }
    private val keyDwell by lazy { GazeDwell(mainHandler) }
    private var pageFlipNeedsCenter = false
    private var lastGazePx = -1f
    private var lastGazePy = -1f
    private var gazeHeld = false
    private var gazeHoldX = 0f
    private var gazeHoldY = 0f
    /** Head-gaze samples are ignored until this uptime (a simulated dwell is running). */
    private var simGazeUntil = 0L
    /** Home open-gaze arming: ARM_UNSET → first tile seen → ARM_ARMED once gaze moves. */
    private var homeGazeArmTile: String = ARM_UNSET
    /** Bumped on page/home so a previous page's dwell callback cannot open an app. */
    private var homeGazeGen: Int = 0
    /** After page/home, the tile that appeared under gaze must be left before any open can arm. */
    private var homeGazeHoldPending: Boolean = true
    private var homeGazeHoldTile: String? = null
    /** Keyboard: same idea — the key focused on open never fires by itself. */
    private var keyGazeArmKey: String = ARM_UNSET
    private var lastKeyboardHud: GlassesHud.Keyboard? = null
    private var keyboardSymbols: Boolean = false
    private var dwellSide: Side? = null
    private var dwellStartedAt: Long = 0L
    private var dwellProgress: Float = 0f
    private var lastDwellUiTick: Int = -1

    private var homePillDwellStartedAt: Long = 0L
    private var homePillDwellProgress: Float = 0f
    private var homePillDwelling: Boolean = false
    private var lastHomePillUiTick: Int = -1

    /** Home mascot — docked top-middle; page-runs L/R; jumps only on open. */
    private var avatarVisible: Boolean = true
    private var avatarPose: MymavAvatarArt.Pose = MymavAvatarArt.Pose.Idle
    private var avatarFrame: Int = 0
    private var avatarX: Float = AVATAR_REST_X
    private var avatarY: Float = AVATAR_REST_Y
    private var avatarAnimRunnable: Runnable? = null
    private var pendingOpenAppId: String? = null
    private var avatarBusy: Boolean = false
    private var avatarSpeech: String = ""
    /** Invalidates in-flight AI answers once the user cancels / moves on. */
    private var aiTicket = 0
    /** Expanded (recipe / long answer / pictures) card state. */
    private var askPages: List<AskPage> = emptyList()
    private var askPageIndex = 0
    private var askExpanded = false
    private val webIo = java.util.concurrent.Executors.newCachedThreadPool()

    /** What the current answer would be saved as (Recipe Book / Notepad). */
    private sealed class Saveable {
        class RecipeItem(val recipe: MymavWeb.Recipe) : Saveable()
        class Info(val title: String, val text: String, val images: List<ByteArray>) : Saveable()
    }
    private var saveable: Saveable? = null
    /** Last recipe shown (online, AI or saved) - source for cooking mode and the shopping list. */
    private var lastRecipe: MymavWeb.Recipe? = null
    private class Cook(val recipe: MymavWeb.Recipe, val steps: List<String>)
    private var cook: Cook? = null
    private var cookNotice = ""
    private var avatarGazeProgress: Float = 0f
    private var avatarGazeStartedAt: Long = 0L
    private var avatarGazeRunnable: Runnable? = null
    private var assistantListening: Boolean = false
    /** Generation token — cancels stale page-run spawns after clear. */
    private var pageRunGeneration: Int = 0
    /** Ask pill under gaze: "audio" | "text" | "cancel". */
    private var askChoiceFocus: String? = null
    private var askChoiceStartedAt: Long = 0L
    private var askChoiceGazeProgress: Float = 0f
    private var askChoiceRunnable: Runnable? = null
    private var lastAskChoiceUiTick: Int = -1

    /** Cached avatar texture — avoid re-encode / re-upload every dwell tick (home flicker). */
    private var cachedAvatarKey: String? = null
    private var cachedAvatar: MymavAvatarArt.Encoded? = null
    private var lastStatusMinute: String = ""

    /** HOME never hides a live recording: the status pill shows REC until Conversate is stopped. */
    private fun homeStatusLabel(minuteKey: String): String =
        if (HomeWidgetPreviewStore.get("conversate")?.state == "live") {
            "REC Conversate · " + minuteKey.substringAfterLast(", ")
        } else {
            minuteKey
        }

    /** Center-gaze open dwell on the focused widget. */
    private var openDwelling: Boolean = false
    private var openDwellStartedAt: Long = 0L
    private var openDwellProgress: Float = 0f
    private var lastOpenDwellUiTick: Int = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var simDwellRunnable: Runnable? = null
    private var simHomePillRunnable: Runnable? = null
    private var simOpenDwellRunnable: Runnable? = null

    fun attach(
        session: GlassesSession,
        onOpenMiniApp: ((String) -> Unit)? = null,
        onReturnHome: (() -> Unit)? = null,
    ) {
        this.session = session
        if (onOpenMiniApp != null) {
            this.onOpenMiniApp = onOpenMiniApp
        }
        if (onReturnHome != null) {
            this.onReturnHome = onReturnHome
        }
    }

    fun setOpenMiniAppHandler(handler: (String) -> Unit) {
        onOpenMiniApp = handler
    }

    fun setReturnHomeHandler(handler: () -> Unit) {
        onReturnHome = handler
    }

    fun detach() {
        cancelSimDwell()
        cancelSimHomePill()
        cancelSimOpenDwell()
        cancelAvatarAnim()
        cancelKeyGaze()
        stopAvatarIdleMotion()
        resetOpenDwell()
        session = null
        onOpenMiniApp = null
        onReturnHome = null
        onKeyboardDone = null
        onKeyboardCancel = null
        keyboardSearchSubmitsBuffer = false
        activeMiniAppId = null
        cachedAppHud = null
        surface = Surface.None
    }

    /** Mark a mini-app as the active glasses surface (after open from home / phone). */
    fun markMiniAppOpen(appId: String) {
        activeMiniAppId = appId
        surface = Surface.App
        cancelSimDwell()
        resetDwell()
        resetHomePillDwell()
        actionScreenKey = ""
        resetActionGaze(requireLeave = true)
    }

    /**
     * Cache the current mini-app HUD so HOME-pill dwell can re-push with progress.
     * Never steals [Surface.Home] — background companions must not flip home → app on gaze.
     */
    fun rememberAppHud(hud: GlassesHud) {
        when (hud) {
            is GlassesHud.AppEnter,
            is GlassesHud.Notification,
            is GlassesHud.Lyrics,
            is GlassesHud.IdentifyResult,
            is GlassesHud.SongList,
            is GlassesHud.FitnessSummary,
            is GlassesHud.TasksBoard,
            is GlassesHud.ListApp,
            is GlassesHud.WorkoutSelect,
            is GlassesHud.RunningPace,
            is GlassesHud.WeightSession,
            -> {
                if (surface == Surface.App) {
                    val key = actionScreenKey(hud)
                    if (key != actionScreenKey) {
                        actionScreenKey = key
                        resetActionGaze(requireLeave = true)
                    }
                    cachedAppHud = hud
                }
            }
            else -> Unit
        }
    }

    /** Lyrics / Spotify companion may draw only while Spotify is the active glasses app. */
    fun acceptsSpotifyLyricsHud(): Boolean =
        surface == Surface.App && activeMiniAppId == "spotify"

    /** Notify companion may draw only while Notify is the active glasses app. */
    fun acceptsNotifyHud(): Boolean =
        surface == Surface.App && activeMiniAppId == "notify"

    /**
     * Football live refresh may replace the glasses HUD only while Football is the
     * foreground mini-app. Background ESPN polls must update data only — never steal
     * Home, Conversate, Shop, or another app screen.
     */
    fun acceptsFootballHud(): Boolean =
        surface == Surface.App && activeMiniAppId == "football"

    /** Generic foreground gate for mini-app HUD pushes (session ownership). */
    fun acceptsMiniAppHud(appId: String): Boolean =
        surface == Surface.App && activeMiniAppId == appId

    /**
     * Glasses HOME pill / long-tap — leave mini-app HUD and show glasses home board.
     * Invokes [onReturnHome] so the phone shell can pop `mini/…` routes.
     *
     * Always pushes home **before** mini-app [MiniApp.onLeave] so the simulator
     * never sits on an empty "waiting for draw commands" queue after long-tap HOME.
     */
    fun requestHomeFromApp() {
        // Leaving the navigation screen (HOME pill / long-tap) must not end the journey: it keeps running, speaking and
        // tracking until the user stops it, and "show navigation" brings the map back.
        if (activeMiniAppId == "nav") { navHidden = MymavNav.mapActive(); navMapShown = false }
        cancelSimHomePill()
        resetHomePillDwell()
        val s = session
        val id = activeMiniAppId
        activeMiniAppId = null
        cachedAppHud = null
        // Mascot reappears on the focused home tile after leaving a mini-app.
        revealAvatar(MymavAvatarArt.Pose.Idle)
        // Push home first — never clearHud into an empty draw queue.
        showHome()
        onReturnHome?.invoke()
        // Soft leave (no clearHud) after home is already drawing.
        if (id != null && s != null) {
            runCatching { MiniAppRegistry.get(id)?.onLeave(s) }
        }
    }

    /**
     * Phone “Back to home” / leave mini — pop phone shell only.
     * Glasses mini-app HUD stays open until HOME on glasses.
     */
    fun leaveMiniFromPhoneOnly() {
        onReturnHome?.invoke()
    }

    fun setAvatarMode(mode: MymavAvatarPrefs.Mode) {
        avatarMode = mode
        when (mode) {
            MymavAvatarPrefs.Mode.Off -> {
                avatarVisible = false
                avatarSpeech = ""
                stopAvatarIdleMotion()
                cancelAvatarAnim()
            }
            MymavAvatarPrefs.Mode.Sleep -> {
                avatarVisible = true
                avatarSpeech = ""
                avatarX = AVATAR_SLEEP_X
                avatarY = AVATAR_SLEEP_Y
                stopAvatarIdleMotion()
                cancelAvatarAnim()
                avatarBusy = false
            }
            MymavAvatarPrefs.Mode.Active -> {
                avatarVisible = true
                avatarX = AVATAR_REST_X
                avatarY = AVATAR_REST_Y
            }
        }
        invalidateAvatarCache()
        if (surface == Surface.Home) showHomeBoardWithoutIdle()
    }

    fun avatarMode(): MymavAvatarPrefs.Mode = avatarMode

    // ---- navigation map ---------------------------------------------------------------------------------------

    private val NAV_OPEN = Regex("^(?:please )?(?:open|launch|show|start|go to|resume|reopen)(?: up)?(?: the| my)? (?:navigation(?: app)?|nav(?: app)?|maps?|map view|route|journey|directions)$")
    @Volatile private var navMapShown = false
    private var lastNavWidgetSig = ""
    private var lastNavWidgetAt = 0L

    /** The Navigation widget reads [MymavNav]; redraw Home when what it shows would change (at most about every 1.5 s). */
    fun refreshNavWidget() {
        mainHandler.post {
            HomeWidgetPreviewStore.invalidate(NavWidget.ID)
            val p = NavWidget.preview()
            val sig = "${p.state}|${p.title}|${p.subtitle}|${p.metric}|${p.metricDetail}|${p.lines.joinToString("~")}"
            if (sig == lastNavWidgetSig) return@post
            lastNavWidgetSig = sig
            if (surface != Surface.Home) return@post
            val now = android.os.SystemClock.uptimeMillis()
            val wait = (1500L - (now - lastNavWidgetAt)).coerceAtLeast(0L)
            mainHandler.removeCallbacks(navWidgetRedraw)
            mainHandler.postDelayed(navWidgetRedraw, wait)
        }
    }
    private val navWidgetRedraw = Runnable {
        lastNavWidgetAt = android.os.SystemClock.uptimeMillis()
        if (surface == Surface.Home) showHome()
    }

    /** Show place choices inside the Navigation app. False when there is no glasses session to show them on. */
    fun showNavResults(query: String, places: List<MymavNav.Place>): Boolean {
        val s = session ?: return false
        mainHandler.post { navHidden = false; navMapShown = false; surface = Surface.App; activeMiniAppId = "nav"; NavigationMiniApp.showResults(s, query, places) }
        return true
    }
    @Volatile private var navHidden = false

    /** Bring the navigation map back after the user went Home with a journey (or a route preview) still open. */
    fun reopenNav() { mainHandler.post { navHidden = false; MymavNav.state.value?.let { pushNav(it) } } }
    private var lastNavPush = 0L
    private var pendingNavState: MymavNav.NavState? = null

    /** Called by [MymavNav] whenever the preview or the walk changes (null = navigation is over). */
    fun onNavState(st: MymavNav.NavState?) {
        mainHandler.post {
            if (st == null) {
                pendingNavState = null
                navHidden = false
                if (navMapShown) { navMapShown = false; requestHomeFromApp() }
                return@post
            }
            if (navHidden) {
                if (st.mode == MymavNav.Mode.Preview) navHidden = false else return@post   // journey carries on in the background
            }
            // Heavy screen: at most about two updates a second, and always keep the latest one.
            val now = android.os.SystemClock.uptimeMillis()
            val first = !navMapShown
            if (!first && now - lastNavPush < 450L) {
                pendingNavState = st
                mainHandler.postDelayed({ pendingNavState?.let { pushNav(it) } }, 450L - (now - lastNavPush))
                return@post
            }
            pushNav(st)
        }
    }

    private fun pushNav(st: MymavNav.NavState) {
        pendingNavState = null
        NavMapData.onLoaded = { MymavNav.state.value?.let { onNavState(it) } }
        val s = session ?: return
        lastNavPush = android.os.SystemClock.uptimeMillis()
        if (!navMapShown) { navMapShown = true; cancelAskChoiceGaze(); assistantListening = false }
        surface = Surface.App; activeMiniAppId = "nav"
        val hud = GlassesHud.NavMap(st, NavMapActions(
            onStart = { handleAssistantUtterance("yes") },
            onStop = { MymavNav.stop(quiet = true); navMapShown = false; navHidden = false; requestHomeFromApp() },
        ), homeDwellProgress = homePillDwellProgress)
        cachedAppHud = hud
        s.replaceHud(hud)
    }

    fun showHome() {
        val s = session ?: return
        val arrivingHome = surface != Surface.Home
        surface = Surface.Home
        if (arrivingHome) invalidateHomeOpenGaze()
        keyDwell.cancel()
        cachedAppHud = null
        actionScreenKey = ""
        resetActionGaze(requireLeave = true)
        // Never clear pendingOpenAppId here — jump animation relies on it (TAP open bug).
        cancelSimHomePill()
        resetHomePillDwell()
        val pageCount = HomeBoardCatalog.pageCount()
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageWidgets = HomeBoardCatalog.page(pageIndex)
        if (pageWidgets.none { it.id == focusedWidgetId }) {
            focusedWidgetId = pageWidgets.firstOrNull()?.id ?: focusedWidgetId
        }
        // Idle home: no speech bubble — bubble only after Activate / 1s gaze summon.
        if (!assistantListening && !avatarBusy) {
            avatarSpeech = ""
        }
        // Apply mascot mode dock (Active top-bar / Sleep bottom-left / Off hidden).
        if (!avatarBusy && pendingOpenAppId == null) {
            applyAvatarDockPose(summoned = assistantListening && avatarSpeech.isNotBlank())
        }
        val minuteKey = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date())
        lastStatusMinute = minuteKey
        val avatar = avatarImageCached()
        s.replaceHud(
            GlassesHud.HomeBoard(
                pageIndex = pageIndex,
                pageCount = pageCount,
                focusedWidgetId = focusedWidgetId,
                widgets = pageWidgets,
                batteryPercent = MymavDeviceStatus.glassesPercent,
                phoneBatteryPercent = MymavDeviceStatus.phonePercent(),
                glassesCharging = MymavDeviceStatus.glassesCharging,
                phoneCharging = MymavDeviceStatus.phoneCharging(),
                // Date/time only — mascot owns the top-bar center (no “· MyMav” collision).
                statusLabel = homeStatusLabel(minuteKey),
                dwellSide = when (dwellSide) {
                    Side.Left -> "left"
                    Side.Right -> "right"
                    null -> null
                },
                dwellProgress = dwellProgress,
                openDwellProgress = openDwellProgress,
                avatarVisible = avatarVisible,
                avatarPose = avatarPose,
                avatarFrame = avatarFrame,
                avatarX = avatarX,
                avatarY = avatarY,
                avatarImageBytes = avatar?.bytes,
                avatarImageWidth = avatar?.width ?: 0,
                avatarImageHeight = avatar?.height ?: 0,
                avatarCellSize = avatarCellSize(),
                avatarCell = avatarCell(),
                avatarPoses = avatarPosesBytes(),
                avatarSpeech = avatarSpeech,
                avatarGazeProgress = avatarGazeProgress,
                askChoiceVisible = assistantListening && avatarSpeech.isNotBlank(),
                askChoiceFocus = askChoiceFocus,
                askChoiceGazeProgress = askChoiceGazeProgress,
                askExpanded = askExpanded && avatarSpeech.isNotBlank(),
                askPageLabel = askPageLabelText(),
                askHasMore = askPages.size > 1,
                askCanSave = saveable != null && cook == null,
                askCanCook = canCook(),
                askCooking = cook != null,
                askTimerLabel = askTimerLabelText(),
                askImageBytes = askPages.getOrNull(askPageIndex)?.image,
                actions = homeBoardActions(),
            ),
        )
        startAvatarIdleMotion()
    }

    private fun homeBoardActions(): HomeBoardActions = HomeBoardActions(
        onFocusChanged = { id ->
            focusedWidgetId = id
            holdGazeForManualInput()
            resetOpenDwell()
            showHomeBoardWithoutIdle()
        },
        onOpenWidget = { id ->
            if (!assistantListening) {
                resetDwell()
                invalidateHomeOpenGaze()
                beginAvatarJumpIntoApp(id)
            }
        },
        onPageChanged = { next ->
            val prev = pageIndex
            pageIndex = next.coerceIn(0, HomeBoardCatalog.pageCount() - 1)
            focusedWidgetId = HomeBoardCatalog.page(pageIndex).firstOrNull()?.id
                ?: focusedWidgetId
            beginAvatarPageRun(sideImpliesRight(prev, pageIndex))
        },
        onGazeNormalized = { x, y -> feedHomeGaze(x, y) },
        onAvatarTapped = { onAvatarInteract() },
        onAskAudio = { beginAskAudio() },
        onAskText = { beginAskText() },
        onAskCancel = { cancelAskMyMav() },
        onAskMore = { nextAskPage() },
        onAskSave = { saveCurrent() },
        onAskCook = { (saveable as? Saveable.RecipeItem)?.let { startCooking(it.recipe) } },
        onAskPrev = { cookMove(-1) },
        onAskTimer = { cookStartTimer() },
    )

    private fun sideImpliesRight(prev: Int, next: Int): Boolean {
        val count = HomeBoardCatalog.pageCount().coerceAtLeast(1)
        return next == (prev + 1) % count
    }

    /** Glance-up or home interaction — show the mascot docked top-middle. */
    fun revealAvatar(pose: MymavAvatarArt.Pose = MymavAvatarArt.Pose.Wave) {
        if (avatarMode == MymavAvatarPrefs.Mode.Off) return
        avatarVisible = true
        avatarPose = pose
        if (!avatarBusy) applyAvatarDockPose(summoned = false)
    }

    /** Wake phrase / phone wake — slide down a bit, wave + opaque speech bubble. */
    fun summonMyMav(utterance: String? = null) {
        if (avatarMode == MymavAvatarPrefs.Mode.Off) return
        // From the quiet top-bar dock: glide down first, then open the bubble.
        if (DOCK_POSES && avatarMode == MymavAvatarPrefs.Mode.Active && cook == null && surface == Surface.Home &&
            !assistantListening && !avatarBusy && avatarVisible && avatarSpeech.isBlank() && session != null
        ) {
            stopAvatarIdleMotion()
            cancelAvatarAnim()
            avatarBusy = true
            avatarPose = MymavAvatarArt.Pose.ArmWave
            avatarFrame = 3
            avatarX = AVATAR_REST_X
            pushAvatarFrame()
            easeDockedAvatarY(AVATAR_SUMMON_Y, SUMMON_EASE_MS) {
                avatarBusy = false
                summonMyMavNow(utterance)
            }
            return
        }
        summonMyMavNow(utterance)
    }

    private fun summonMyMavNow(utterance: String?) {
        if (avatarMode == MymavAvatarPrefs.Mode.Off) return
        if (cook != null) {
            // Keep the recipe on screen; only handle what was said.
            if (surface != Surface.Home) showHome()
            if (!utterance.isNullOrBlank()) handleAssistantUtterance(utterance) else showHomeBoardWithoutIdle()
            return
        }
        if (surface != Surface.Home) showHome()
        cancelAvatarGaze()
        cancelAskChoiceGaze()
        cancelAvatarAnim()
        clearAskPages()
        assistantListening = true
        avatarVisible = true
        avatarBusy = false
        avatarPose = MymavAvatarArt.Pose.Wave
        avatarFrame = 1
        avatarX = AVATAR_REST_X
        // Slide lower from the top bar so the bubble has room underneath.
        avatarY = AVATAR_SUMMON_Y
        avatarSpeech = "How can I help?"
        invalidateAvatarCache()
        stopAvatarIdleMotion()
        showHomeBoardWithoutIdle()
        // Do not start idle bob while Ask choice (AUDIO/TEXT/CANCEL) is up.
        if (!utterance.isNullOrBlank()) {
            mainHandler.postDelayed({ handleAssistantUtterance(utterance) }, 450L)
        }
    }

    private fun summonX(): Float =
        if (askExpanded && avatarSpeech.isNotBlank()) HomeBoardCatalog.AskLayout.EXP_AVATAR_X else AVATAR_REST_X

    private fun clearAskPages() {
        cook = null
        cookNotice = ""
        saveable = null
        askPages = emptyList()
        askPageIndex = 0
        askExpanded = false
    }

    /** Show a compact bubble, or the expanded card when the text is long. */
    @Volatile private var lastAnswerText = ""

    private fun showAnswer(text: String, save: Saveable? = null) {
        lastAnswerText = text
        if (MymavSpeech.autoRead() && !suppressSpeech) MymavSpeech.speak(text)
        if (AskPager.needsExpansion(text)) {
            showPages(AskPager.paginate(text), save)
            return
        }
        clearAskPages()
        saveable = save
        (save as? Saveable.RecipeItem)?.let { lastRecipe = it.recipe }
        avatarX = AVATAR_REST_X
        avatarY = AVATAR_SUMMON_Y
        avatarSpeech = text
        avatarPose = MymavAvatarArt.Pose.Wave
        invalidateAvatarCache()
        showHomeBoardWithoutIdle()
        // Saveable answers keep SAVE / AUDIO / TEXT / CANCEL up.
        assistantListening = save != null
    }

    private fun showPages(pages: List<AskPage>, save: Saveable? = null) {
        if (pages.isEmpty()) return
        cook = null
        cookNotice = ""
        saveable = save
        (save as? Saveable.RecipeItem)?.let { lastRecipe = it.recipe }
        askPages = pages
        askPageIndex = 0
        askExpanded = true
        avatarX = HomeBoardCatalog.AskLayout.EXP_AVATAR_X
        avatarY = AVATAR_SUMMON_Y
        avatarSpeech = pages[0].text.ifBlank { " " }
        avatarPose = MymavAvatarArt.Pose.Wave
        assistantListening = true
        invalidateAvatarCache()
        showHomeBoardWithoutIdle()
    }

    private fun nextAskPage() {
        if (cook != null) { cookMove(1); return }
        if (askPages.size < 2) return
        askPageIndex = (askPageIndex + 1) % askPages.size
        avatarSpeech = askPages[askPageIndex].text.ifBlank { " " }
        showHomeBoardWithoutIdle()
    }

    private fun askPillIds(): List<String> =
        HomeBoardCatalog.AskLayout.pillIds(
            askPages.size > 1, saveable != null,
            canCook = canCook(), cooking = cook != null, hasTimer = cookStepTimerMs() != null,
        )

    private fun canCook() = cook == null && saveable is Saveable.RecipeItem

    private fun askPageLabelText(): String {
        val c = cook
        if (c != null) {
            val base = if (askPageIndex == 0) "${c.steps.size} steps" else "Step $askPageIndex/${c.steps.size}"
            return if (cookNotice.isNotBlank()) "$base - $cookNotice" else base
        }
        return if (askPages.size > 1) "${askPageIndex + 1}/${askPages.size}" else ""
    }

    private fun askTimerLabelText(): String {
        val ms = cookStepTimerMs() ?: return ""
        return if (stepTimerRunning()) "ON" else MymavKitchen.shortDuration(ms)
    }

    private fun applyAvatarDockPose(summoned: Boolean) {
        when (avatarMode) {
            MymavAvatarPrefs.Mode.Off -> {
                avatarVisible = false
            }
            MymavAvatarPrefs.Mode.Sleep -> {
                avatarVisible = true
                if (summoned) {
                    avatarX = summonX()
                    avatarY = AVATAR_SUMMON_Y
                } else {
                    avatarX = AVATAR_SLEEP_X
                    avatarY = AVATAR_SLEEP_Y
                }
            }
            MymavAvatarPrefs.Mode.Active -> {
                avatarVisible = true
                avatarX = if (summoned) summonX() else AVATAR_REST_X
                avatarY = if (summoned) AVATAR_SUMMON_Y else AVATAR_REST_Y
            }
        }
    }

    /** Return mascot to the top-bar dock (clears speech). */
    fun dockAvatarQuiet() {
        clearAskPages()
        avatarSpeech = ""
        avatarY = AVATAR_REST_Y
        avatarX = AVATAR_REST_X
        avatarPose = MymavAvatarArt.Pose.Idle
        assistantListening = false
        invalidateAvatarCache()
        if (surface == Surface.Home) showHomeBoardWithoutIdle()
    }

    /** Routes to calendar / conversion / translation / search / Spotify providers. True when handled. */
    private fun routeAssistant(raw: String, cleaned: String, calendarOnly: Boolean): Boolean {
            val ticket = ++aiTicket
            val question = raw.trim().replace(Regex("^(?i)(hey|hi),? ?my ?mav[,.! ]*"), "").ifBlank { raw.trim() }
            var thinkingAt = 0L
            val started = AssistantRouter.route(question, cleaned, { msg -> if (ticket == aiTicket) { thinking(msg); thinkingAt = android.os.SystemClock.uptimeMillis() } }, { o ->
                // The HUD skips a redraw while the previous screen is still being released, so a very fast answer
                // (an error, a cached rate) would leave a blank bubble: hold the "thinking" screen briefly first.
                val wait = if (thinkingAt == 0L) 0L else (1100L - (android.os.SystemClock.uptimeMillis() - thinkingAt)).coerceAtLeast(0L)
                mainHandler.postDelayed({
                    if (ticket != aiTicket) return@postDelayed
                    AssistantLog.add(question, o.text, o.kind, o.sources, if (o.note == "ask" || o.note == "map") "" else o.note)
                    if (o.kind != AssistantLog.Kind.Error) MymavAi.remember(question, o.text)
                    android.util.Log.i("DBGANS", "[${o.kind}] ${o.text.take(200).replace("\n", " | ")}")
                    if (o.note == "map" && MymavNav.mapActive()) return@postDelayed   // the map is on screen and carries this message
                    val shown = if (o.footer.isBlank()) o.text else o.text + "\n" + o.footer
                    showAnswer(shown)
                }, wait)
            }, calendarOnly)
            return started
    }

    fun handleAssistantUtterance(raw: String) {
        val cleaned = raw.trim().lowercase()
            .removePrefix("hey mymav")
            .removePrefix("hey my mav")
            .removePrefix("hi mymav")
            .trim()
            .trimStart(',', ' ', '.', '!')
            .trim()
        if (Regex("^(?:please )?(?:read|say) (?:that|this|it|the answer|that answer|it back)(?: out)?(?: aloud| out loud| again)?$|^read aloud$|^read (?:that )?out(?: loud)?$").matches(cleaned)) {
            if (lastAnswerText.isBlank()) showAnswer("There's nothing to read yet.")
            else if (!MymavSpeech.speak(lastAnswerText)) showAnswer("I can't read aloud: this phone has no text-to-speech engine available.")
            return
        }
        if (cleaned == "stop" || cleaned == "be quiet" || cleaned == "stop reading") { MymavSpeech.stop(); return }
        MymavSpeech.stop()
        if (cook != null && handleCookCommand(cleaned)) return
        if (NAV_OPEN.matches(cleaned)) {
            // Same app either way: with a journey (or route preview) open it comes back as it was; otherwise the destination screen.
            if (MymavNav.state.value != null) reopenNav() else openAppViaAvatar("nav")
            return
        }
        Regex("^(open|launch|go to|show me)( up)?( the| my)? (shopping( list)?|recipes?( book)?|notepad|notes)$").find(cleaned)?.let { m ->
            val w = m.groupValues[4]
            openAppViaAvatar(if (w.startsWith("shop")) "shopping" else if (w.startsWith("recipe")) "recipes" else "notepad")
            return
        }
        if (handleTimerIntent(cleaned)) return
        if (handleShoppingIntent(cleaned)) return
        if (isBriefingQuery(cleaned)) { handleBriefing(); return }
        if (handleCookStart(cleaned)) return
        // Save to Recipe Book / Notepad, quick notes, and reading them back.
        if (isSaveIntent(cleaned)) { handleSaveIntent(cleaned); return }
        noteText(raw.trim())?.let { handleTakeNote(it); return }
        if (isSavedRecipeQuery(cleaned)) { handleSavedRecipes(cleaned); return }
        if (isSavedNotesQuery(cleaned)) { if (!routeAssistant(raw, cleaned, calendarOnly = true)) handleSavedNotes(cleaned); return }
        // Calendar events are not tasks: handled first, with its own confirmation conversation.
        if (routeAssistant(raw, cleaned, calendarOnly = true)) return
        // Tasks: "add a task ...", "what are my tasks today".
        if (MymavTaskIntent.isAdd(cleaned)) { handleAddTask(raw); return }
        if (MymavTaskIntent.isList(cleaned)) { showTasksToday(); return }
        // Recipes from the web.
        recipeQuery(cleaned)?.let { handleRecipe(it); return }
        // Live date / weather before the generic assistant path.
        if (MyMavWeather.isWeatherQuery(cleaned) ||
            (MyMavWeather.isDateQuery(cleaned) && MyMavWeather.isWeatherQuery(cleaned))
        ) {
            avatarSpeech = "Checking the weather…"
            avatarPose = MymavAvatarArt.Pose.Wave
            invalidateAvatarCache()
            showHomeBoardWithoutIdle()
            MyMavWeather.fetchWeatherSpeech { speech ->
                avatarSpeech = speech
                avatarPose = MymavAvatarArt.Pose.Wave
                invalidateAvatarCache()
                showHomeBoardWithoutIdle()
                assistantListening = false
            }
            return
        }
        if (MyMavWeather.isDateQuery(cleaned)) {
            avatarSpeech = MyMavWeather.todayDateSpeech()
            avatarPose = MymavAvatarArt.Pose.Wave
            invalidateAvatarCache()
            showHomeBoardWithoutIdle()
            assistantListening = false
            return
        }
        // Conversions, translation, headlines and current-information search: real providers, with sources.
        if (routeAssistant(raw, cleaned, calendarOnly = false)) return
        val reply = MyMavAssistant.handle(raw)
        // Pictures from the web.
        reply.showPictureQuery?.let { q -> handlePictures(q); return }
        // General questions go to the user's own ChatGPT / Gemini key when configured.
        if (reply.openAppId == null && cleaned.isNotBlank() && MymavAi.isConfigured()) {
            val ticket = ++aiTicket
            val question = raw.trim().replace(Regex("^(?i)(hey|hi),? ?my ?mav[,.! ]*"), "").ifBlank { raw.trim() }
            thinking("Thinking...")
            MymavAi.ask(question) { answer ->
                if (ticket != aiTicket) return@ask
                if (answer == null) {
                    val why = MymavAi.lastProblem.ifBlank { "Check your keys in MyMav Settings." }
                    AssistantLog.add(question, "I couldn't reach the AI. $why", AssistantLog.Kind.Error)
                    showAnswer("I couldn't reach the AI. $why")
                } else {
                    val note = buildString {
                        append("Model answer, not searched")
                        if (MymavAi.lastUsedLabel.isNotBlank()) append(" · ").append(MymavAi.lastUsedLabel)
                        if (MymavAi.lastCallNote.isNotBlank()) append(" · ").append(MymavAi.lastCallNote)
                    }
                    AssistantLog.add(question, answer, AssistantLog.Kind.Ai, note = note)
                    showAnswer(answer, Saveable.Info(question.replaceFirstChar { it.uppercase() }.take(60), answer, emptyList()))
                }
            }
            return
        }
        clearAskPages()
        avatarSpeech = reply.speech
        avatarPose = MymavAvatarArt.Pose.Wave
        invalidateAvatarCache()
        showHomeBoardWithoutIdle()
        reply.openAppId?.let { id -> openAppViaAvatar(id) }
        assistantListening = false
    }

    private fun thinking(msg: String) {
        clearAskPages()
        avatarX = AVATAR_REST_X
        avatarY = AVATAR_SUMMON_Y
        avatarSpeech = msg
        assistantListening = true  // keeps the CANCEL pill up so a pending request can be cancelled
        avatarPose = MymavAvatarArt.Pose.Wave
        invalidateAvatarCache()
        showHomeBoardWithoutIdle()
    }


    // ---- cooking mode ------------------------------------------------------------

    private fun cookPages(c: Cook): List<AskPage> {
        val head = AskPager.wrap(c.recipe.name, 22).take(3).joinToString("\n") +
            "\n${c.steps.size} steps\nSay NEXT STEP"
        val pages = arrayListOf(AskPage(head, c.recipe.image))
        c.steps.forEach { pages += AskPage(AskPager.wrap(it, 36).take(7).joinToString("\n")) }
        return pages
    }

    /** Recipe app -> cooking mode on the home board. */
    fun startCookingFromApp(r: MymavWeb.Recipe) {
        mainHandler.post {
            if (avatarMode == MymavAvatarPrefs.Mode.Off) return@post
            requestHomeFromApp()
            mainHandler.postDelayed({ avatarVisible = true; startCooking(r) }, 500L)
        }
    }

    private fun startCooking(r: MymavWeb.Recipe) {
        val steps = MymavKitchen.splitSteps(r.steps)
        if (steps.isEmpty()) { showAnswer("That recipe has no steps to cook."); return }
        val c = Cook(r, steps)
        showPages(cookPages(c), Saveable.RecipeItem(r))
        cook = c
        cookNotice = ""
        showHomeBoardWithoutIdle()
    }

    private fun cookMove(delta: Int) {
        val c = cook ?: return
        val t = askPageIndex + delta
        if (t < 0) return
        if (t >= askPages.size) {
            val n = c.recipe.name
            showAnswer("All done - enjoy your $n!")
            return
        }
        askPageIndex = t
        cookNotice = ""
        avatarSpeech = askPages[t].text.ifBlank { " " }
        showHomeBoardWithoutIdle()
    }

    private fun cookStepTimerMs(): Long? {
        val c = cook ?: return null
        return c.steps.getOrNull(askPageIndex - 1)?.let { MymavKitchen.stepTimerMs(it) }
    }

    private fun stepTimerLabel() = "Step $askPageIndex"

    private fun stepTimerRunning() = MymavTimers.active().any { it.label == stepTimerLabel() }

    private fun cookStartTimer() {
        val ms = cookStepTimerMs() ?: return
        if (!stepTimerRunning()) MymavTimers.start(stepTimerLabel(), ms)
        cookNotice = "timer ${MymavKitchen.shortDuration(ms)}"
        showHomeBoardWithoutIdle()
    }

    private fun handleCookCommand(c: String): Boolean {
        val t = c.trim('.', '!', ' ', ',')
        when {
            Regex("^(ok(ay)?,? )?(next|next step|continue|go on|forward|done|finished|ready|go next)$").matches(t) -> cookMove(1)
            Regex("^(previous|previous step|back|go back|last step|step back)$").matches(t) -> cookMove(-1)
            Regex("^(repeat|say that again|again|repeat that|what was that)$").matches(t) -> showHomeBoardWithoutIdle()
            Regex("\\b(stop|exit|end|finish|cancel|quit) cooking\\b|^i'?m done( cooking)?$").containsMatchIn(t) -> showAnswer("Cooking stopped.")
            Regex("^(start|set|run)( the| a)?( step)? timer( for (this|that)( step)?)?$|^time (it|this|that)$").matches(t) -> {
                if (cookStepTimerMs() != null) cookStartTimer() else say("No time in this step", "This step has no time to set. Say: set a timer for 5 minutes.")
            }
            else -> return false
        }
        return true
    }

    /** "start cooking pasta", "cooking mode", "cook this" */
    private fun handleCookStart(c: String): Boolean {
        if (!Regex("\\b(start cooking|cooking mode|cook this|let'?s cook|begin cooking|step by step)\\b").containsMatchIn(c)) return false
        val name = c.replace(Regex("\\b(start|begin) cooking\\b|\\bcooking mode\\b|\\bcook this\\b|\\blet'?s cook\\b|\\bstep by step\\b|\\b(my|the|a|please|for|recipe|with|in)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (name.isBlank()) {
            val r = lastRecipe
            if (r == null) showAnswer("Ask me for a recipe first, then say: start cooking.") else startCooking(r)
            return true
        }
        val saved = runCatching { MymavActions.recipes?.find(name) }.getOrNull()
        if (saved != null) { startCooking(saved); return true }
        val ticket = ++aiTicket
        thinking("Finding a recipe for $name...")
        webIo.execute {
            val r = runCatching { MymavWeb.findRecipe(name) }.getOrNull()
            mainHandler.post {
                if (ticket != aiTicket) return@post
                if (r != null) startCooking(r) else showAnswer("I couldn't find a recipe for $name.")
            }
        }
        return true
    }

    /** Short confirmation: shown in the cooking card's footer while cooking, else as a normal bubble. */
    private fun say(short: String, long: String) {
        if (cook != null) { cookNotice = short; showHomeBoardWithoutIdle() } else showAnswer(long)
    }

    // ---- timers -------------------------------------------------------------------

    /** Called when a timer elapses (from [MymavTimers]). */
    fun onTimerFired(label: String) {
        mainHandler.post {
            if (cook != null) {
                cookNotice = "TIMER DONE"
                if (surface == Surface.Home) showHomeBoardWithoutIdle()
                return@post
            }
            if (avatarMode == MymavAvatarPrefs.Mode.Off) return@post
            if (surface != Surface.Home) showHome()
            avatarVisible = true
            showAnswer("Time's up!\n$label")
        }
    }

    private val TIMER_NOISE = setOf(
        "set", "a", "an", "the", "timer", "for", "of", "me", "start", "please", "minute", "minutes", "hour",
        "hours", "second", "seconds", "min", "mins", "hr", "hrs", "sec", "secs", "and", "half", "can", "you",
        "could", "new", "another", "my", "to", "in", "on", "remind", "up", "wake", "tell", "alert",
    )

    private fun handleTimerIntent(c: String): Boolean {
        val hasTimerWord = Regex("\\btimers?\\b").containsMatchIn(c)
        if (hasTimerWord && Regex("\\b(cancel|stop|clear|delete|remove|kill)\\b").containsMatchIn(c)) {
            val q = c.replace(Regex("\\b(cancel|stop|clear|delete|remove|kill|all|my|the|timers?|please)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim().ifBlank { null }
            val n = MymavTimers.cancel(q)
            say(if (n > 0) "timer cancelled" else "no timer", if (n > 0) "Cancelled $n timer${if (n > 1) "s" else ""}." else "You have no matching timers.")
            return true
        }
        val rm = Regex("^(?:please )?remind me in (.+?) (?:to|that|about) (.+)$").find(c)
        if (rm != null) {
            val ms = MymavTimers.parseDuration(rm.groupValues[1])
            if (ms != null) {
                val label = rm.groupValues[2].trim().replaceFirstChar { it.uppercase() }
                MymavTimers.start(label, ms)
                say("timer ${MymavKitchen.shortDuration(ms)}", "OK - I'll remind you in ${MymavTimers.spoken(ms)}:\n$label")
                return true
            }
        }
        if (!hasTimerWord) return false
        val ms = MymavTimers.parseDuration(c)
        if (ms == null) {
            if (Regex("\\b(show|list|what|how long|any|check|which|running|left|remaining)\\b").containsMatchIn(c)) {
                val act = MymavTimers.active()
                if (act.isEmpty()) say("no timers", "No timers running.")
                else showAnswer("Timers:\n" + act.joinToString("\n") { "${it.label}  ${MymavTimers.format(it.endAt - System.currentTimeMillis())}" })
            } else {
                showAnswer("How long? Say: set a timer for 10 minutes.")
            }
            return true
        }
        val words = c.replace(Regex("\\d+(\\.\\d+)?\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b|\\b(an|one) hour\\b|\\bhalf an hour\\b|\\b\\d+\\b"), " ")
            .split(Regex("[^a-z']+")).filter { it.isNotBlank() && it !in TIMER_NOISE }
        val label = words.joinToString(" ").ifBlank { "Timer" }.replaceFirstChar { it.uppercase() }
        MymavTimers.start(label, ms)
        say("timer ${MymavKitchen.shortDuration(ms)}", "Timer set: $label\n${MymavTimers.spoken(ms)}")
        return true
    }

    // ---- shopping list ---------------------------------------------------------------

    private val SHOP_WORD = Regex("\\b(shopping|grocery|groceries)\\b")

    private fun handleShoppingIntent(c: String): Boolean {
        if (!SHOP_WORD.containsMatchIn(c)) return false
        val b = MymavActions.shopping
        if (b == null) { showAnswer("Shopping list isn't available right now."); return true }
        // Clear
        if (Regex("\\b(clear|empty|reset|wipe)\\b").containsMatchIn(c)) {
            say("list cleared", runCatching { b.clearAll() }.getOrNull() ?: "Couldn't clear the list."); return true
        }
        // Ingredients from a recipe
        if (c.contains("ingredient")) { addRecipeIngredients(c); return true }
        // Remove / tick off
        val rem = Regex("^(?:please )?(?:remove|delete|cross off|tick off|check off|take off|got the|got)\\s+(.+?)(?:\\s+(?:from|off)\\s+(?:my |the |our )?(?:shopping|grocery|groceries)(?: list)?)?$").find(c)
        if (rem != null && Regex("\\b(remove|delete|cross|tick|check|take|got)\\b").containsMatchIn(c)) {
            val q = rem.groupValues[1].replace(Regex("\\b(my|the|shopping|grocery|list)\\b"), " ").trim()
            if (q.isNotBlank()) {
                say("removed", runCatching { b.remove(q) }.getOrNull() ?: "Couldn't remove that."); return true
            }
        }
        // Add
        val add = Regex("^(?:please )?(?:can you |could you )?(?:add|put|get|buy|pick up|we need|i need|need)\\s+(.+?)\\s+(?:to|on|onto|in|into)\\s+(?:my |the |our )?(?:shopping|grocery|groceries)(?: list)?$").find(c)
        if (add != null) {
            val items = MymavKitchen.splitItems(add.groupValues[1])
            if (items.isNotEmpty()) {
                val msg = runCatching { b.add(items) }.getOrNull() ?: "Couldn't add that."
                say("added ${items.size}", msg); return true
            }
        }
        // Show
        val items = runCatching { b.items() }.getOrDefault(emptyList())
        if (items.isEmpty()) showAnswer("Your shopping list is empty. Say: add milk to my shopping list.")
        else showPages(AskPager.paginate("Shopping list (${items.size}):\n" + items.joinToString("\n") { "- $it" }))
        return true
    }

    private fun addRecipeIngredients(c: String) {
        val b = MymavActions.shopping ?: return
        val name = Regex("\\b(?:for|from|of)\\s+(?:my |the |a )?(.+?)(?:\\s+(?:to|on|onto|in|into)\\b.*)?$").find(c)
            ?.groupValues?.get(1)?.replace(Regex("\\b(recipe|the|my)\\b"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        fun put(r: MymavWeb.Recipe) {
            val items = r.ingredients.filter { it.isNotBlank() }
            if (items.isEmpty()) { say("no ingredients", "That recipe has no ingredients."); return }
            val msg = runCatching { b.add(items) }.getOrNull() ?: "Couldn't add those."
            say("added ${items.size}", "$msg\n(${r.name})")
        }
        if (name.isBlank() || name == "this" || name == "that") {
            val r = cook?.recipe ?: lastRecipe
            if (r == null) showAnswer("Ask me for a recipe first, then say: add all ingredients to my shopping list.") else put(r)
            return
        }
        val last = lastRecipe
        if (last != null && name.split(' ').all { last.name.contains(it, ignoreCase = true) }) { put(last); return }
        val saved = runCatching { MymavActions.recipes?.find(name) }.getOrNull()
        if (saved != null) { put(saved); return }
        val ticket = ++aiTicket
        thinking("Finding the ingredients for $name...")
        webIo.execute {
            val r = runCatching { MymavWeb.findRecipe(name) }.getOrNull()
            mainHandler.post {
                if (ticket != aiTicket) return@post
                if (r != null) put(r) else showAnswer("I couldn't find a recipe for $name.")
            }
        }
    }

    // ---- daily briefing ------------------------------------------------------------------

    private fun isBriefingQuery(c: String) =
        Regex("\\b(daily|morning|evening)? ?(briefing|brief me)\\b|^good (morning|day|afternoon)\\b|\\bwhat'?s (on )?(my day|today)\\b|\\bwhat do i have (on )?today\\b|\\bwhat'?s my day\\b|\\bmy day\\b.*\\blook\\b|^(my )?(schedule|agenda)( today)?$|\\b(rundown|overview) of my day\\b")
            .containsMatchIn(c)

    private fun handleBriefing() {
        val ticket = ++aiTicket
        thinking("Getting your briefing...")
        MyMavWeather.fetchWeatherSpeech { w ->
            if (ticket != aiTicket) return@fetchWeatherSpeech
            webIo.execute {
                val tasks = runCatching { MymavActions.tasks?.todayLines() }.getOrNull().orEmpty()
                val cal = MymavActions.calendar
                val events = if (cal == null) null else runCatching { cal.upcoming(4) }.getOrNull()
                mainHandler.post {
                    if (ticket != aiTicket) return@post
                    showPages(briefingPages(w, tasks, events))
                }
            }
        }
    }

    private fun briefingPages(weather: String, tasks: List<String>, events: List<MymavActions.CalEvent>?): List<AskPage> {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val greet = when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
        val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now.time)
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val lines = ArrayList<String>()
        lines += "$greet - $date"
        val w = weather.substringAfter(". ", weather).substringAfter(": ", weather.substringAfter(". ", weather)).trim().replace("Weather unavailable", "Weather offline")
        lines += AskPager.wrap(w, 40).take(2)
        lines += "TODAY'S TASKS"
        if (tasks.isEmpty()) lines += "Nothing due"
        else {
            tasks.take(2).forEach { l ->
                val t = l.substringBefore('|').trim()
                val title = l.substringAfter('|', l).trim()
                lines += (if (t.isBlank()) "Anytime  " else "$t  ") + title.take(30)
            }
            if (tasks.size > 2) lines += "+${tasks.size - 2} more"
        }
        lines += "NEXT EVENT"
        when {
            events == null -> lines += "Enable Calendar in Settings"
            events.isEmpty() -> lines += "Nothing coming up"
            else -> events.take(if (tasks.size >= 2) 1 else 2).forEach { e ->
                val start = java.util.Calendar.getInstance().apply { timeInMillis = e.startMs }
                val same = start.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) &&
                    start.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                val whenTxt = when {
                    e.allDay -> if (same) "All day" else java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(start.time)
                    same -> hm.format(start.time)
                    else -> java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(start.time)
                }
                lines += "$whenTxt  ${e.title.take(28)}"
            }
        }
        val timers = MymavTimers.active()
        if (timers.isNotEmpty() && lines.size < 8) {
            val t = timers.first()
            lines += "TIMER  ${t.label.take(18)} ${MymavTimers.format(t.endAt - System.currentTimeMillis())}"
        }
        val pages = ArrayList<AskPage>()
        AskPager.chunk(lines, pages)
        return pages
    }

    // ---- tasks -------------------------------------------------------------

    private fun handleAddTask(raw: String) {
        val bridge = MymavActions.tasks
        if (bridge == null) {
            showAnswer("Tasks isn't available right now.")
            return
        }
        val ticket = ++aiTicket
        thinking("Adding your task...")
        val now = java.util.Calendar.getInstance()
        val local = MymavTaskIntent.parseLocal(raw, now)
        fun finish(p: MymavTaskIntent.Parsed?) {
            if (ticket != aiTicket) return
            if (p == null || p.title.isBlank()) {
                showAnswer("What should the task be? Try: add task buy milk tomorrow at 5pm.")
                return
            }
            val msg = runCatching { bridge.add(p.title, p.dueEpochMs, p.hasTime) }.getOrNull()
            showAnswer(msg ?: ("Added \"${p.title}\"" + MymavTaskIntent.whenLabel(p, now) + "."))
        }
        if (MymavAi.isConfigured()) {
            MymavAi.generate(MymavTaskIntent.aiPrompt(now), raw.trim(), 120, 400, AiRole.Ask) { out ->
                finish(MymavTaskIntent.parseAiJson(out, now) ?: local)
            }
        } else {
            finish(local)
        }
    }

    private fun showTasksToday() {
        val bridge = MymavActions.tasks
        if (bridge == null) {
            showAnswer("Tasks isn't available right now.")
            return
        }
        val lines = runCatching { bridge.todayLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            showAnswer("Nothing due today. Enjoy!")
            return
        }
        val body = lines.joinToString("\n") { l ->
            val (t, title) = l.split('|', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (t.isBlank()) "Anytime  $title" else "$t  $title"
        }
        showAnswer("Today's tasks:\n$body")
    }

    // ---- Recipe Book & Notepad ---------------------------------------------------

    private fun isSaveIntent(c: String): Boolean {
        if (Regex("\\btasks?\\b").containsMatchIn(c)) return false
        val dest = Regex("\\b(notepad|notes?|recipe book|recipes?)\\b").containsMatchIn(c)
        val strong = Regex("^(please )?(can you |could you )?(save|store|bookmark|file)\\b").containsMatchIn(c)
        val soft = Regex("^(please )?(can you |could you )?(add|put|keep)\\b.*\\b(this|that|it)\\b").containsMatchIn(c)
        return (strong && (dest || Regex("\\b(this|that|it)\\b").containsMatchIn(c) || c.split(' ').size <= 2)) ||
            (soft && dest)
    }

    private fun handleSaveIntent(c: String) {
        val s = saveable
        if (s == null) {
            showAnswer("Nothing to save yet. Ask me for a recipe or a picture first.")
            return
        }
        val wantsRecipe = c.contains("recipe")
        val wantsNote = c.contains("note")
        if (wantsNote && s is Saveable.RecipeItem) {
            val r = s.recipe
            val body = (listOf(r.name, r.meta).filter { it.isNotBlank() } + listOf("") + r.ingredients.map { "- $it" } +
                listOf("", r.steps)).joinToString("\n")
            saveInfo(Saveable.Info(r.name, body, listOfNotNull(r.image)))
            return
        }
        if (wantsRecipe && s is Saveable.Info) {
            showAnswer("That isn't a recipe, so I'll put it in your Notepad instead.")
        }
        saveCurrent()
    }

    private fun saveInfo(info: Saveable.Info) {
        val b = MymavActions.notes
        if (b == null) { showAnswer("Notepad isn't available right now."); return }
        val msg = runCatching { b.save(info.title, info.text, info.images, emptyList()) }.getOrNull()
        showAnswer(msg ?: "Couldn't save that note.")
    }

    /** SAVE pill / "save this": recipes go to the Recipe Book, everything else to the Notepad. */
    private fun saveCurrent() {
        val s = saveable
        if (s == null) { showAnswer("Nothing to save yet."); return }
        when (s) {
            is Saveable.RecipeItem -> {
                val b = MymavActions.recipes
                if (b == null) { showAnswer("Recipe Book isn't available right now."); return }
                val msg = runCatching { b.save(s.recipe) }.getOrNull()
                showAnswer(msg ?: "Couldn't save that recipe.")
            }
            is Saveable.Info -> saveInfo(s)
        }
    }

    private fun parseAiRecipe(dish: String, text: String): MymavWeb.Recipe {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.firstOrNull { !it.endsWith(":") }?.take(60) ?: dish.replaceFirstChar { it.uppercase() }
        val iIdx = lines.indexOfFirst { it.startsWith("Ingredients", true) }
        val mIdx = lines.indexOfFirst { it.startsWith("Method", true) || it.startsWith("Steps", true) }
        val ing = if (iIdx >= 0) lines.subList(iIdx + 1, if (mIdx > iIdx) mIdx else lines.size)
            .map { it.trimStart('-', '*', ' ') } else emptyList()
        val steps = if (mIdx >= 0) lines.drop(mIdx + 1).joinToString("\n") else text
        return MymavWeb.Recipe(name, "AI recipe", null, ing, steps)
    }

    private val NOTE_RES = listOf(
        Regex("(?is)^(?:please )?(?:take|make|add|write|jot|create)(?: me)?(?: a| down)? ?(?:new )?notes?(?: that| saying| about)?[:,]?\\s*(.+)$"),
        Regex("(?is)^(?:note that|note down|write down|jot down|make a note that|remember that)\\s*(.+)$"),
    )

    private fun noteText(raw: String): String? {
        val t = raw.replace(Regex("(?i)^(hey|hi),? ?my ?mav[,.! ]*"), "").trim()
        for (re in NOTE_RES) re.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun handleTakeNote(text: String) {
        val b = MymavActions.notes
        if (b == null) { showAnswer("Notepad isn't available right now."); return }
        val title = text.take(40).trimEnd('.', ',', ' ')
        val msg = runCatching { b.save(title.replaceFirstChar { it.uppercase() }, text, emptyList(), emptyList()) }.getOrNull()
        showAnswer(msg ?: "Couldn't save that note.")
    }

    private fun isSavedRecipeQuery(c: String) =
        Regex("\\b(recipe book|my recipes|saved recipes?|my saved recipe)\\b").containsMatchIn(c) ||
            Regex("\\bmy recipe\\b").containsMatchIn(c)

    private fun handleSavedRecipes(c: String) {
        val b = MymavActions.recipes
        if (b == null) { showAnswer("Recipe Book isn't available right now."); return }
        val m = Regex("(?:recipes?)(?: book)? (?:for|of|called|named) (.+)$").find(c)
            ?: Regex("(?:open|read|show|find) (?:my )?(.+?) recipe$").find(c)
        if (m != null) {
            val q = m.groupValues[1].trim()
            val r = b.find(q)
            if (r != null) showPages(recipePages(r), Saveable.RecipeItem(r))
            else showAnswer("I couldn't find \"$q\" in your recipe book.")
            return
        }
        val names = b.names()
        if (names.isEmpty()) showAnswer("Your recipe book is empty. Ask me for a recipe, then press SAVE.")
        else showAnswer("Recipe book:\n" + names.take(12).joinToString("\n") { "- $it" })
    }

    private fun isSavedNotesQuery(c: String) =
        Regex("\\b(my notes|my notepad|saved notes|the notepad|show notes|read notes)\\b").containsMatchIn(c)

    private fun handleSavedNotes(c: String) {
        val b = MymavActions.notes
        if (b == null) { showAnswer("Notepad isn't available right now."); return }
        val m = Regex("notes? (?:about|on|called|named) (.+)$").find(c)
        if (m != null) {
            val q = m.groupValues[1].trim()
            val n = b.find(q)
            if (n != null) showAnswer(n.first + "\n" + n.second)
            else showAnswer("I couldn't find a note about \"$q\".")
            return
        }
        val titles = b.titles()
        if (titles.isEmpty()) showAnswer("Your notepad is empty. Say: take a note buy milk.")
        else showAnswer("Notepad:\n" + titles.take(12).joinToString("\n") { "- $it" })
    }

    // ---- recipes & pictures (online) --------------------------------------------

    private val RECIPE_NOISE = setOf(
        "give", "me", "a", "an", "the", "some", "recipe", "recipes", "for", "of", "to", "make", "cook", "bake",
        "how", "do", "i", "can", "you", "find", "get", "show", "please", "online", "from", "web", "internet",
        "search", "could", "want", "need", "tell", "about", "what", "is", "whats", "s", "id", "like", "prepare",
        "my", "us", "up", "look", "lookup", "pull", "using", "with", "on", "good", "best", "easy", "quick",
    )

    /** Dish name when the request is a recipe request, else null. */
    private fun recipeQuery(cleaned: String): String? {
        val isRecipe = cleaned.contains("recipe") ||
            Regex("^(how (do|can|to|would) (i |we |you )?(make|cook|bake|prepare)\\b)").containsMatchIn(cleaned)
        if (!isRecipe) return null
        val dish = cleaned.replace(Regex("[^a-z0-9 ]"), " ").split(' ')
            .filter { it.isNotBlank() && it !in RECIPE_NOISE }.joinToString(" ")
        return dish.ifBlank { "chicken" }
    }

    private fun handleRecipe(dish: String) {
        val ticket = ++aiTicket
        thinking("Finding a recipe for $dish...")
        webIo.execute {
            val r = runCatching { MymavWeb.findRecipe(dish) }.getOrNull()
            mainHandler.post {
                if (ticket != aiTicket) return@post
                when {
                    r != null -> showPages(recipePages(r), Saveable.RecipeItem(r))
                    MymavAi.isConfigured() -> {
                        MymavAi.generate(
                            "You are MyMav. Give a practical recipe as plain text (no markdown, no emoji): " +
                                "first line the dish name, then 'Ingredients:' with one ingredient and quantity per " +
                                "line, then 'Method:' with numbered short steps, one per line. Keep it under 200 words.",
                            "Recipe for $dish", 700, 1800, AiRole.Summary,
                        ) { out ->
                            if (ticket != aiTicket) return@generate
                            if (out == null) showAnswer("I couldn't find a recipe for $dish online right now.")
                            else showPages(AskPager.paginate(out), Saveable.RecipeItem(parseAiRecipe(dish, out)))
                        }
                    }
                    else -> showAnswer("I couldn't find a recipe for $dish online.")
                }
            }
        }
    }

    private fun recipePages(r: MymavWeb.Recipe): List<AskPage> {
        val pages = ArrayList<AskPage>()
        val head = buildString {
            append(r.name)
            if (r.meta.isNotBlank()) append("\n").append(r.meta)
            append("\n").append(r.ingredients.size).append(" ingredients")
            append("\nPress MORE for the full recipe")
        }
        pages += AskPage(AskPager.wrap(head, 22).take(8).joinToString("\n"), r.image)
        val ing = r.ingredients.flatMap { AskPager.wrap("- $it", 40) }
        AskPager.chunk(listOf("INGREDIENTS") + ing, pages)
        val steps = r.steps.take(2800).split('\n').filter { it.isNotBlank() }
            .flatMapIndexed { i, st -> AskPager.wrap(st.trim(), 40) + if (i >= 0) listOf("") else emptyList() }
            .let { l -> if (l.lastOrNull() == "") l.dropLast(1) else l }
        // Drop blank spacer lines at page starts.
        val stepPages = ArrayList<AskPage>()
        AskPager.chunk(listOf("METHOD") + steps, stepPages)
        stepPages.forEach { pg -> pages += AskPage(pg.text.trimStart('\n')) }
        return pages
    }

    private fun handlePictures(query: String) {
        val ticket = ++aiTicket
        thinking("Looking up pictures of $query...")
        webIo.execute {
            val search = runCatching { MymavWeb.searchImagesWithSources(query, 5) }
            val found = search.getOrNull()
            val imgs = found?.images.orEmpty()
            mainHandler.post {
                if (ticket != aiTicket) return@post
                if (imgs.isEmpty()) {
                    val why = when {
                        found == null -> "I couldn't reach the picture source (${search.exceptionOrNull()?.message?.take(60) ?: "network error"})."
                        found.candidates > 0 -> "I found ${found.candidates} pictures of $query but none could be downloaded."
                        else -> "I couldn't find pictures of $query on Wikipedia or Wikimedia Commons."
                    }
                    AssistantLog.add("Pictures of $query", why, AssistantLog.Kind.Error)
                    showAnswer(why)
                } else {
                    val srcs = imgs.flatMap { i ->
                        listOfNotNull(
                            i.pageUrl.takeIf { it.isNotBlank() }?.let { AssistantLog.Source("Wikipedia: ${i.title}", it) },
                            i.fileUrl.takeIf { it.isNotBlank() }?.let { AssistantLog.Source("Photo credit: ${listOf(i.credit.take(40), i.license).filter { x -> x.isNotBlank() }.joinToString(", ").ifBlank { "Wikimedia Commons" }}", it) },
                        )
                    }
                    val failed = found?.failedDownloads ?: 0
                    AssistantLog.add(
                        "Pictures of $query", "${imgs.size} pictures from Wikipedia / Wikimedia Commons.", AssistantLog.Kind.Web, srcs,
                        if (failed > 0) "$failed picture(s) could not be downloaded and were skipped." else "",
                    )
                    showPages(
                        imgs.map { AskPager.paginate(it.caption, it.bytes).first() },
                        Saveable.Info(
                            "Pictures: $query",
                            imgs.joinToString("\n\n") { it.caption },
                            imgs.map { it.bytes },
                        ),
                    )
                }
            }
        }
    }

    private fun onAvatarInteract() {
        if (assistantListening) {
            // Already in Ask mode — keep AUDIO / TEXT / CANCEL visible (don't stack keyboards).
            showHomeBoardWithoutIdle()
        } else {
            summonMyMav()
        }
    }

    /** Glasses mic / VTXT path — open Ask keyboard armed for voice-to-text. */
    private fun beginAskAudio() {
        if (!assistantListening) summonMyMav()
        openKeyboard(
            title = "Ask MyMav",
            searchSubmitsBuffer = true,
            doneLabel = "Ask",
            onDone = { handleAssistantUtterance(it) },
            onCancel = { if (cook != null) showHome() else cancelAskMyMav() },
        )
        val s = session ?: return
        runVoiceToText(s)
    }

    /** Text keyboard path for Ask MyMav. */
    private fun beginAskText() {
        if (!assistantListening) summonMyMav()
        openKeyboard(
            title = "Ask MyMav",
            searchSubmitsBuffer = true,
            doneLabel = "Ask",
            onDone = { handleAssistantUtterance(it) },
            onCancel = { if (cook != null) showHome() else cancelAskMyMav() },
        )
    }

    /** CANCEL — dismiss Ask without sending; return to quiet home dock. */
    /** Navigation updates replace the bubble without being spoken or logged as answers. */
    fun showNavigationUpdate(text: String) {
        mainHandler.post { suppressSpeech = true; try { showAnswer(text) } finally { suppressSpeech = false } }
    }
    @Volatile private var suppressSpeech = false

    /** True while the bubble still shows text starting with [prefix] (used to refresh the recording timer only if it is still up). */
    fun bubbleStartsWith(prefix: String): Boolean = lastAnswerText.startsWith(prefix)

    /** Cancels whatever the assistant is doing: a pending request, speech, navigation or captions. */
    fun cancelAssistant() { mainHandler.post { cancelAskMyMav() } }

    private fun cancelAskMyMav() {
        aiTicket++
        MymavSpeech.stop()
        MymavNav.stop()
        MymavCaptions.stop(false)
        cancelAskChoiceGaze()
        assistantListening = false
        avatarSpeech = ""
        clearAskPages()
        avatarPose = MymavAvatarArt.Pose.Idle
        applyAvatarDockPose(summoned = false)
        invalidateAvatarCache()
        if (DOCK_POSES && avatarMode == MymavAvatarPrefs.Mode.Active && cook == null && session != null) {
            // Dock at the summoned height, then glide back up to the top bar.
            cancelAvatarAnim()
            avatarBusy = true
            avatarY = AVATAR_SUMMON_Y
            showHome()
            easeDockedAvatarY(AVATAR_REST_Y, SUMMON_EASE_MS) {
                avatarBusy = false
                startAvatarIdleMotion()
            }
        } else {
            showHome()
        }
    }

    /** Scroll home to the widget's page, then jump into the app. */
    /** App → app hand-off (Food → Identify, Identify → Shop) without a trip through HOME. */
    fun switchToApp(appId: String) {
        val s = session ?: return
        val from = activeMiniAppId
        if (from != null && from != appId) MiniAppRegistry.get(from)?.let { app -> runCatching { app.onLeave(s) } }
        val opener = onOpenMiniApp
        if (opener != null) {
            opener.invoke(appId)
        } else {
            markMiniAppOpen(appId)
            MiniAppRegistry.get(appId)?.onEnter(s)
        }
    }

    fun openAppViaAvatar(appId: String) {
        val targetPage = HomeBoardCatalog.pageIndexOf(appId)
        if (targetPage < 0) {
            avatarSpeech = "I couldn't find that app."
            showHomeBoardWithoutIdle()
            return
        }
        val prev = pageIndex
        pageIndex = targetPage
        focusedWidgetId = appId
        if (prev != targetPage) {
            beginAvatarPageRun(sideImpliesRight(prev, targetPage))
            mainHandler.postDelayed({ beginAvatarJumpIntoApp(appId) }, 220L)
        } else {
            beginAvatarJumpIntoApp(appId)
        }
    }

    /** Docked = top-bar / sleep dock (resident pose set); summoned = Ask bubble (single 120px frame). */
    private fun avatarDocked(): Boolean = !(assistantListening || avatarSpeech.isNotBlank())

    private fun avatarImageCached(): MymavAvatarArt.Encoded? {
        if (!avatarVisible) return null
        if (avatarDocked()) {
            if (DOCK_POSES) return null
            // One mascot texture, moved by position patches only (switching
            // between several pose images left stale copies on the glasses).
            return MymavAvatarArt.dockPoses()[MymavAvatarArt.CELL_IDLE]
        }
        // Summoned / listening / answer mascot: production sprite of the SELECTED character (cache keyed per cell,
        // preset and size, so frame ticks and preset changes never reuse a stale or procedural image).
        val cacheKey = MymavAvatarArt.summonKey(AVATAR_SUMMON_SIZE, avatarPose, avatarFrame)
        val hit = cachedAvatar
        if (hit != null && cachedAvatarKey == cacheKey) return hit
        val encoded = MymavAvatarArt.summonImage(AVATAR_SUMMON_SIZE, avatarPose, avatarFrame)
        cachedAvatarKey = cacheKey
        cachedAvatar = encoded
        if (avatarTraceOn) android.util.Log.i("DBGART", "controller avatar image key=$cacheKey docked=false pose=${avatarPose.name} frame=$avatarFrame")
        return encoded
    }

    private fun avatarCellSize(): Int = if (DOCK_POSES && avatarVisible && avatarDocked()) MymavAvatarArt.DOCK_CELL else 0

    private fun avatarCell(): Int = MymavAvatarArt.dockCell(avatarPose, avatarFrame)

    private var posesSource: List<MymavAvatarArt.Encoded>? = null
    private var posesBytes: List<ByteArray>? = null

    /** Same list instance while the preset is unchanged (the board keys on it). */
    private fun avatarPosesBytes(): List<ByteArray>? {
        if (avatarCellSize() <= 0) return null
        val src = MymavAvatarArt.dockPoses()
        if (src !== posesSource) {
            posesSource = src
            posesBytes = src.map { it.bytes }
        }
        return posesBytes
    }

    /** Gentle 0..3px float for the docked mascot (smooth idle without extra frames). */
    private fun idleFloat(tick: Int): Float {
        val t = (tick % IDLE_FLOAT_TICKS).toFloat() / IDLE_FLOAT_TICKS
        return Math.round(1.5f - 1.5f * kotlin.math.cos(t * 2f * Math.PI.toFloat())).toFloat()
    }

    fun invalidateAvatarCache() {
        cachedAvatarKey = null
        cachedAvatar = null
    }

    /** Patch avatar sprite/position only — avoids full home replaceHud flicker. */
    /** Patch mascot pose/position only — never rebuilds the home board. */
    private fun pushAvatarFrame() {
        val s = session ?: return
        if (surface != Surface.Home) return
        traceAvatar("patch")
        if (DOCK_POSES && avatarDocked()) {
            s.patchHomeAvatarCell(avatarVisible, avatarX, avatarY, avatarCell())
        } else {
            s.patchHomeAvatar(
                visible = avatarVisible,
                x = avatarX,
                y = avatarY,
                imageBytes = null,
                imageWidth = 0,
                imageHeight = 0,
            )
        }
    }

    private fun startAvatarIdleMotion() {
        stopAvatarIdleMotion()
        if (surface != Surface.Home || avatarBusy) return
        if (avatarMode != MymavAvatarPrefs.Mode.Active) return
        // Keep Ask MyMav summon pose + bubble/pills stable.
        if (assistantListening) return
        MymavAvatarArt.prewarm(AVATAR_DOCK_SIZE)
        var tick = 0
        val runnable = object : Runnable {
            override fun run() {
                if (surface != Surface.Home || avatarBusy || assistantListening) {
                    avatarIdleRunnable = null
                    return
                }
                tick++
                // Gentle float (position only), a blink every ~6s and a short
                // wave every ~14s. Pose changes just swap which resident image
                // is visible — nothing is uploaded.
                val cycle = tick % IDLE_CYCLE_TICKS
                val blinkStep = BLINK_STARTS.firstOrNull { cycle >= it && cycle < it + BLINK_SEQ.size }
                when {
                    blinkStep != null -> {
                        avatarPose = BLINK_SEQ[cycle - blinkStep]
                        avatarFrame = 0
                    }
                    cycle >= WAVE_AT && cycle < WAVE_AT + WAVE_SEQ.size -> {
                        avatarPose = MymavAvatarArt.Pose.ArmWave
                        avatarFrame = WAVE_SEQ[cycle - WAVE_AT]
                    }
                    else -> {
                        avatarPose = MymavAvatarArt.Pose.Idle
                        avatarFrame = 0
                    }
                }
                avatarX = AVATAR_REST_X
                avatarY = AVATAR_REST_Y + idleFloat(tick)
                pushAvatarFrame()
                avatarIdleRunnable = this
                mainHandler.postDelayed(this, IDLE_FRAME_MS)
            }
        }
        avatarIdleRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_FRAME_MS)
    }

    /**
     * Glide the docked mascot to [toY] (ease-in-out) by moving the resident texture only —
     * no uploads, no rebuild. The caller owns [avatarBusy]; [done] runs after the last step.
     */
    private fun easeDockedAvatarY(toY: Float, durationMs: Long, done: () -> Unit) {
        val fromY = avatarY
        val startAt = android.os.SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - startAt).toFloat() / durationMs).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                avatarY = fromY + (toY - fromY) * eased
                pushAvatarFrame()
                if (t < 1f) {
                    avatarAnimRunnable = this
                    mainHandler.postDelayed(this, 16L)
                } else {
                    avatarAnimRunnable = null
                    done()
                }
            }
        }
        avatarAnimRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopAvatarIdleMotion() {
        avatarIdleRunnable?.let { mainHandler.removeCallbacks(it) }
        avatarIdleRunnable = null
    }

    /**
     * Gaze/manual page change. The mascot stays at its top-centre rest position the
     * whole time (no sideways slide): the board is rebuilt once with the mascot
     * already docked, then the idle/blink motion carries on.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun beginAvatarPageRun(dirRight: Boolean) {
        cancelAvatarAnim()
        stopAvatarIdleMotion()
        cancelAvatarGaze()
        cancelAskChoiceGaze()
        resetDwell()
        invalidateHomeOpenGaze()
        pageRunGeneration++
        avatarSpeech = ""
        assistantListening = false
        avatarBusy = false
        avatarPose = MymavAvatarArt.Pose.Idle
        avatarFrame = 0
        if (avatarMode == MymavAvatarPrefs.Mode.Active) {
            avatarVisible = true
            avatarX = AVATAR_REST_X
            avatarY = AVATAR_REST_Y
            invalidateAvatarCache()
            showHomeBoardWithoutIdle()
            startAvatarIdleMotion()
        } else {
            applyAvatarDockPose(summoned = false)
            invalidateAvatarCache()
            showHomeBoardWithoutIdle()
        }
    }

    /** Test-only motion trace (DBGAVATAR lines: time, cell, position, page); off unless a debug command enables it. */
    @Volatile private var avatarTraceOn = false
    fun setAvatarTrace(on: Boolean) { avatarTraceOn = on; MymavAvatarArt.traceArt = on }
    private fun traceAvatar(tag: String) {
        if (!avatarTraceOn) return
        android.util.Log.i("DBGAVATAR", "t=${android.os.SystemClock.uptimeMillis()} $tag cell=${avatarCell()} x=$avatarX y=$avatarY page=$pageIndex vis=$avatarVisible")
    }

    /** Like showHome but does not restart idle motion (used mid-animation). */
    private fun showHomeBoardWithoutIdle() {
        val s = session ?: return
        traceAvatar("board")
        surface = Surface.Home
        val pageCount = HomeBoardCatalog.pageCount()
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageWidgets = HomeBoardCatalog.page(pageIndex)
        if (pageWidgets.none { it.id == focusedWidgetId }) {
            focusedWidgetId = pageWidgets.firstOrNull()?.id ?: focusedWidgetId
        }
        val minuteKey = lastStatusMinute.ifBlank {
            SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date())
        }
        val avatar = avatarImageCached()
        s.replaceHud(
            GlassesHud.HomeBoard(
                pageIndex = pageIndex,
                pageCount = pageCount,
                focusedWidgetId = focusedWidgetId,
                widgets = pageWidgets,
                batteryPercent = MymavDeviceStatus.glassesPercent,
                phoneBatteryPercent = MymavDeviceStatus.phonePercent(),
                glassesCharging = MymavDeviceStatus.glassesCharging,
                phoneCharging = MymavDeviceStatus.phoneCharging(),
                statusLabel = homeStatusLabel(minuteKey),
                dwellSide = when (dwellSide) {
                    Side.Left -> "left"
                    Side.Right -> "right"
                    null -> null
                },
                dwellProgress = dwellProgress,
                openDwellProgress = openDwellProgress,
                avatarVisible = avatarVisible,
                avatarPose = avatarPose,
                avatarFrame = avatarFrame,
                avatarX = avatarX,
                avatarY = avatarY,
                avatarImageBytes = avatar?.bytes,
                avatarImageWidth = avatar?.width ?: 0,
                avatarImageHeight = avatar?.height ?: 0,
                avatarCellSize = avatarCellSize(),
                avatarCell = avatarCell(),
                avatarPoses = avatarPosesBytes(),
                avatarSpeech = avatarSpeech,
                avatarGazeProgress = avatarGazeProgress,
                askChoiceVisible = assistantListening && avatarSpeech.isNotBlank(),
                askChoiceFocus = askChoiceFocus,
                askChoiceGazeProgress = askChoiceGazeProgress,
                askExpanded = askExpanded && avatarSpeech.isNotBlank(),
                askPageLabel = askPageLabelText(),
                askHasMore = askPages.size > 1,
                askCanSave = saveable != null && cook == null,
                askCanCook = canCook(),
                askCooking = cook != null,
                askTimerLabel = askTimerLabelText(),
                askImageBytes = askPages.getOrNull(askPageIndex)?.image,
                actions = homeBoardActions(),
            ),
        )
    }

    /**
     * Jump into an app from the docked top-middle rest pose (only on Tap / open gaze).
     */
    private fun beginAvatarJumpIntoApp(appId: String) {
        cancelAvatarAnim()
        stopAvatarIdleMotion()
        pendingOpenAppId = appId
        avatarBusy = true
        avatarVisible = true
        val tile = HomeBoardCatalog.tileRect(pageIndex, appId)
        val endX = tile?.cx ?: AVATAR_REST_X
        val endY = tile?.cy ?: AVATAR_REST_Y
        val startX = AVATAR_REST_X
        val startY = AVATAR_REST_Y
        // Both arms up for the whole jump (one resident pose image, never hidden/cropped).
        avatarPose = MymavAvatarArt.Pose.Jump
        avatarFrame = 0
        avatarX = startX
        avatarY = startY
        avatarSpeech = ""
        // Jump is patch-only on the live home board — no mid-motion showHome
        // (full rebuild mid-jump flickered / stacked a second MyMav).
        invalidateAvatarCache()
        pushAvatarFrame()
        // 10 eased steps @ ~24ms; pose texture swaps every 2nd step.
        val steps = 10
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                val t = (step.toFloat() / steps).coerceIn(0f, 1f)
                val e = t * t * (3f - 2f * t)
                avatarFrame = 0
                avatarPose = MymavAvatarArt.Pose.Jump
                avatarX = startX + (endX - startX) * e
                val arc = if (step >= steps - 3) -28f * (1f - t) else 0f
                avatarY = startY + (endY - startY) * e + arc
                if (step < steps) {
                    pushAvatarFrame()
                    mainHandler.postDelayed(this, 24L)
                } else {
                    avatarVisible = false
                    avatarPose = MymavAvatarArt.Pose.Idle
                    avatarBusy = false
                    avatarAnimRunnable = null
                    // Park the live sprite before opening so it cannot linger under the app HUD.
                    session?.patchHomeAvatar(
                        visible = false,
                        x = -400f,
                        y = -400f,
                        imageBytes = null,
                        imageWidth = 0,
                        imageHeight = 0,
                    )
                    val id = pendingOpenAppId
                    pendingOpenAppId = null
                    if (id != null) {
                        markMiniAppOpen(id)
                        val opener = onOpenMiniApp
                        if (opener != null) {
                            opener.invoke(id)
                        } else {
                            // No phone UI attached (app in background / debug hook):
                            // still open the mini-app on the glasses.
                            session?.let { s -> MiniAppRegistry.get(id)?.onEnter(s) }
                        }
                    }
                }
            }
        }
        avatarAnimRunnable = runnable
        mainHandler.postDelayed(runnable, 16L)
    }

    private fun cancelAvatarAnim() {
        avatarAnimRunnable?.let { mainHandler.removeCallbacks(it) }
        avatarAnimRunnable = null
        // Invalidate pending clear→spawn so a cancelled page-run cannot add a second mascot.
        pageRunGeneration++
        avatarBusy = false
    }

    /**
     * Feed a normalized gaze sample (0..1 on canvas). Left/right edge dwell for 1s pages.
     * Center gaze resets dwell. Safe no-op when not showing home.
     */
    fun feedHomeGaze(normalizedX: Float, normalizedY: Float = 0.5f) {
        if (surface != Surface.Home) return
        if (avatarBusy || pendingOpenAppId != null) return
        val now = System.currentTimeMillis()
        val px = normalizedX * 640f
        val py = normalizedY * 400f
        // Ask MyMav pills — 1s gaze on AUDIO / TEXT / CANCEL (plus Forward/Tap/Backward).
        if (assistantListening && avatarSpeech.isNotBlank()) {
            val pill = askChoiceHit(px, py)
            if (pill != null) {
                resetOpenDwell()
                resetDwell()
                cancelAvatarGaze()
                feedAskChoiceGaze(pill, now)
                return
            }
            cancelAskChoiceGaze()
            // Ask is open: looking elsewhere must not flip pages, open tiles behind it or re-summon.
            resetOpenDwell()
            resetDwell()
            cancelAvatarGaze()
            return
        } else {
            cancelAskChoiceGaze()
        }
        // 1s gaze on docked avatar → summon MyMav (hand up + speech bubble).
        if (avatarVisible && avatarHit(px, py)) {
            resetOpenDwell()
            resetDwell()
            feedAvatarSummonGaze(now)
            return
        }
        cancelAvatarGaze()
        val side = when {
            normalizedX <= HomeBoardCatalog.GAZE_LEFT_MAX -> Side.Left
            normalizedX >= HomeBoardCatalog.GAZE_RIGHT_MIN -> Side.Right
            else -> null
        }
        if (side != null) {
            resetOpenDwell()
            if (pageDwell.target == null) {
                android.util.Log.i("MyMavGaze", "side=$side needsCenter=$pageFlipNeedsCenter busy=$avatarBusy page=$pageIndex")
            }
            // After a flip, look back toward the middle before the next one.
            if (pageFlipNeedsCenter) return
            pageDwell.feed(
                side.name,
                HomeBoardCatalog.GAZE_PAGE_DWELL_MS,
                onTick = { p ->
                    dwellSide = side
                    dwellProgress = p.coerceAtLeast(0.05f)
                    showHomeBoardWithoutIdle()
                },
                onFire = {
                    android.util.Log.i("MyMavGaze", "page flip $side from $pageIndex")
                    val count = HomeBoardCatalog.pageCount().coerceAtLeast(1)
                    pageIndex = when (side) {
                        Side.Left -> (pageIndex - 1 + count) % count
                        Side.Right -> (pageIndex + 1) % count
                    }
                    focusedWidgetId = HomeBoardCatalog.page(pageIndex).firstOrNull()?.id ?: focusedWidgetId
                    pageFlipNeedsCenter = true
                    beginAvatarPageRun(dirRight = side == Side.Right)
                },
            )
            return
        }
        pageFlipNeedsCenter = false
        if (pageDwell.target != null || dwellSide != null) {
            resetDwell()
            showHomeBoardWithoutIdle()
        }
        // Glance up → wave (does not open apps).
        if (normalizedY <= GAZE_UP_MAX) {
            resetOpenDwell()
            if (avatarPose == MymavAvatarArt.Pose.Idle) {
                revealAvatar(MymavAvatarArt.Pose.Wave)
                pushAvatarFrame()
            }
            return
        }

        // Centre: the tile under your gaze takes focus; 1s on it opens it.
        // Gaining focus never opens. A page/home inherit requires leave + re-enter.
        val tile = homeTileAt(px, py)
        if (homeGazeHoldPending) {
            homeGazeHoldPending = false
            homeGazeHoldTile = tile
            resetOpenDwell()
            if (tile != null && tile != focusedWidgetId) {
                focusedWidgetId = tile
                showHomeBoardWithoutIdle()
            }
            return
        }
        if (homeGazeHoldTile != null) {
            if (tile == homeGazeHoldTile) {
                resetOpenDwell()
                return
            }
            homeGazeHoldTile = null
            homeGazeArmTile = ARM_UNSET
        }
        if (tile == null) {
            resetOpenDwell()
            homeGazeArmTile = ARM_UNSET
            return
        }
        if (homeGazeArmTile == ARM_UNSET) {
            homeGazeArmTile = ARM_ARMED
        } else if (homeGazeArmTile != ARM_ARMED) {
            if (tile == homeGazeArmTile) return
            homeGazeArmTile = ARM_ARMED
        }
        if (tile != focusedWidgetId) {
            focusedWidgetId = tile
            showHomeBoardWithoutIdle()
        }
        feedOpenGaze(tile)
    }

    /** Widget id under a canvas point on the current home page. */
    private fun homeTileAt(px: Float, py: Float): String? =
        HomeBoardCatalog.layoutPage(pageIndex).firstOrNull { p ->
            val r = HomeBoardCatalog.cellRect(p.col, p.row, p.colSpan, p.rowSpan)
            px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h
        }?.widget?.id

    private fun avatarHit(px: Float, py: Float): Boolean {
        val dx = px - avatarX
        val dy = py - avatarY
        return dx * dx + dy * dy <= AVATAR_HIT_R * AVATAR_HIT_R
    }

    /**
     * Hit-test Ask MyMav AUDIO / TEXT / CANCEL under the summon bubble.
     * Layout mirrors [HomeWidgetBoardScreen.drawAskChoicePills].
     */
    private fun askChoiceHit(px: Float, py: Float): String? {
        if (!assistantListening || avatarSpeech.isBlank()) return null
        val ids = askPillIds()
        val geo = HomeBoardCatalog.AskLayout.geometry(avatarX, avatarY, askExpanded, ids.size)
        val pillH = HomeBoardCatalog.AskLayout.PILL_H
        if (py < geo.pillY || py > geo.pillY + pillH) return null
        ids.forEachIndexed { index, id ->
            val x = geo.pillX(index)
            if (px >= x && px <= x + geo.pillW) return id
        }
        return null
    }

    private fun feedAskChoiceGaze(pill: String, now: Long) {
        if (askChoiceFocus != pill) {
            askChoiceRunnable?.let { mainHandler.removeCallbacks(it) }
            askChoiceFocus = pill
            askChoiceStartedAt = now
            askChoiceGazeProgress = 0f
            lastAskChoiceUiTick = -1
            val fire = Runnable {
                if (surface == Surface.Home && assistantListening && askChoiceFocus == pill) {
                    askChoiceGazeProgress = 1f
                    when (pill) {
                        "more" -> nextAskPage()
                        "save" -> saveCurrent()
                        "prev" -> cookMove(-1)
                        "timer" -> cookStartTimer()
                        "cook" -> (saveable as? Saveable.RecipeItem)?.let { startCooking(it.recipe) }
                        "audio" -> beginAskAudio()
                        "text" -> beginAskText()
                        "cancel" -> cancelAskMyMav()
                    }
                }
                askChoiceRunnable = null
                askChoiceFocus = null
                askChoiceGazeProgress = 0f
                lastAskChoiceUiTick = -1
            }
            askChoiceRunnable = fire
            mainHandler.postDelayed(fire, ASK_CHOICE_DWELL_MS)
            showHomeBoardWithoutIdle()
            return
        }
        val elapsed = now - askChoiceStartedAt
        askChoiceGazeProgress = (elapsed / ASK_CHOICE_DWELL_MS.toFloat()).coerceIn(0f, 1f)
        val tick = (elapsed / 200L).toInt()
        if (tick != lastAskChoiceUiTick) {
            lastAskChoiceUiTick = tick
            showHomeBoardWithoutIdle()
        }
    }

    private fun cancelAskChoiceGaze() {
        askChoiceRunnable?.let { mainHandler.removeCallbacks(it) }
        askChoiceRunnable = null
        if (askChoiceFocus != null || askChoiceGazeProgress > 0f) {
            askChoiceFocus = null
            askChoiceStartedAt = 0L
            askChoiceGazeProgress = 0f
            lastAskChoiceUiTick = -1
        }
    }

    /**
     * Sim / no-LOS: inject 1s gaze samples on an Ask pill ("audio"|"text"|"cancel").
     */
    fun simulateAskChoiceGazeDwell(pill: String) {
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 1_800L
        if (surface != Surface.Home) showHome()
        if (!assistantListening) summonMyMav()
        cancelSimDwell()
        val target = when (pill.lowercase()) {
            "audio" -> 0.22f to 0.62f
            "text" -> 0.50f to 0.62f
            else -> 0.78f to 0.62f
        }
        var n = 0
        val runnable = object : Runnable {
            override fun run() {
                feedHomeGaze(target.first, target.second)
                n++
                if (n < 14) {
                    mainHandler.postDelayed(this, 100L)
                } else {
                    simDwellRunnable = null
                }
            }
        }
        simDwellRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun feedAvatarSummonGaze(now: Long) {
        if (avatarGazeStartedAt == 0L) {
            avatarGazeStartedAt = now
            avatarGazeProgress = 0f
            avatarGazeRunnable?.let { mainHandler.removeCallbacks(it) }
            val fire = Runnable {
                if (surface == Surface.Home && !avatarBusy) {
                    avatarGazeProgress = 1f
                    summonMyMav()
                }
                avatarGazeRunnable = null
            }
            avatarGazeRunnable = fire
            mainHandler.postDelayed(fire, AVATAR_SUMMON_DWELL_MS)
            showHomeBoardWithoutIdle()
            return
        }
        val elapsed = now - avatarGazeStartedAt
        avatarGazeProgress = (elapsed / AVATAR_SUMMON_DWELL_MS.toFloat()).coerceIn(0f, 1f)
    }

    private fun cancelAvatarGaze() {
        avatarGazeRunnable?.let { mainHandler.removeCallbacks(it) }
        avatarGazeRunnable = null
        avatarGazeStartedAt = 0L
        avatarGazeProgress = 0f
    }

    private fun feedOpenGaze(tile: String) {
        if (homeGazeArmTile != ARM_ARMED) return
        openDwelling = true
        val gen = homeGazeGen
        openDwell.feed(
            tile,
            HomeBoardCatalog.GAZE_OPEN_DWELL_MS,
            onTick = { p ->
                if (gen != homeGazeGen) return@feed
                openDwellProgress = p
                showHomeBoardWithoutIdle()
            },
            onFire = {
                if (gen != homeGazeGen || surface != Surface.Home) return@feed
                invalidateHomeOpenGaze()
                beginAvatarJumpIntoApp(tile)
            },
        )
    }

    private fun resetOpenDwell() {
        openDwell.cancel()
        openDwelling = false
        openDwellStartedAt = 0L
        openDwellProgress = 0f
        lastOpenDwellUiTick = -1
    }

    /** Cancel pending open dwell and require a fresh enter after page/home/activation. */
    private fun invalidateHomeOpenGaze() {
        homeGazeGen++
        resetOpenDwell()
        homeGazeArmTile = ARM_UNSET
        homeGazeHoldPending = true
        homeGazeHoldTile = null
    }

    /** Sim: gaze held on the docked mascot (top centre) through the real dwell path, as a wearer looking at it would. */
    fun simulateAvatarGazeDwell() {
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 2_000L
        var n = 0
        val r = object : Runnable {
            override fun run() {
                feedHomeGaze(AVATAR_REST_X / 640f, AVATAR_REST_Y / 400f)
                n++
                if (n < 16 && surface == Surface.Home && !assistantListening) mainHandler.postDelayed(this, 100L)
            }
        }
        mainHandler.post(r)
    }

    /** Sim: 1s center gaze to open the focused home widget. */
    fun simulateOpenGazeDwell() {
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 1_800L
        if (surface != Surface.Home) showHome()
        cancelSimOpenDwell()
        resetOpenDwell()
        var n = 0
        val runnable = object : Runnable {
            override fun run() {
                // Leave the inherit tile (below the grid) then re-enter the centre widget.
                if (n < 3) feedHomeGaze(0.50f, 0.96f) else feedHomeGaze(0.50f, 0.55f)
                n++
                if (n < 17 && surface == Surface.Home && pendingOpenAppId == null) {
                    mainHandler.postDelayed(this, 100L)
                } else {
                    simOpenDwellRunnable = null
                }
            }
        }
        simOpenDwellRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelSimOpenDwell() {
        simOpenDwellRunnable?.let { mainHandler.removeCallbacks(it) }
        simOpenDwellRunnable = null
    }

    /**
     * Feed gaze while a mini-app HUD is up. Dwelling on the HOME pill for 1s returns home.
     */
    fun feedAppHomeGaze(normalizedX: Float, normalizedY: Float = 0.5f) {
        if (surface != Surface.App) return
        val now = System.currentTimeMillis()
        val onPill = HomeBoardCatalog.homePillContainsNormalized(normalizedX, normalizedY)
        if (!onPill) {
            if (homePillDwelling) {
                resetHomePillDwell()
                rePushCachedAppHud()
            }
            return
        }
        if (!homePillDwelling) {
            homePillDwelling = true
            homePillDwellStartedAt = now
            homePillDwellProgress = 0f
            lastHomePillUiTick = 0
            rePushCachedAppHud()
            return
        }
        val elapsed = now - homePillDwellStartedAt
        homePillDwellProgress =
            (elapsed.toFloat() / HomeBoardCatalog.HOME_GAZE_DWELL_MS).coerceIn(0f, 1f)
        if (elapsed >= HomeBoardCatalog.HOME_GAZE_DWELL_MS) {
            requestHomeFromApp()
            return
        }
        val tick = (elapsed / 80L).toInt()
        if (tick != lastHomePillUiTick) {
            lastHomePillUiTick = tick
            rePushCachedAppHud()
        }
    }

    /**
     * Sim / yaw fallback: inject continuous side-gaze samples for ~1.2s so the page turns.
     * @param next true = right (next page), false = left (prev page)
     */
    fun simulateSideGazeDwell(next: Boolean) {
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 1_800L
        android.util.Log.i("MyMavGaze", "simulateSideGazeDwell next=$next surface=$surface busy=$avatarBusy pending=$pendingOpenAppId")
        if (surface != Surface.Home) showHome()
        pageFlipNeedsCenter = false
        cancelSimDwell()
        resetDwell()
        val targetX = if (next) 0.92f else 0.08f
        var n = 0
        val runnable = object : Runnable {
            override fun run() {
                feedHomeGaze(targetX, 0.5f)
                n++
                if (n < 14) {
                    mainHandler.postDelayed(this, 100L)
                } else {
                    simDwellRunnable = null
                }
            }
        }
        simDwellRunnable = runnable
        mainHandler.post(runnable)
    }

    /**
     * Sim fallback: inject gaze on the HOME pill for ~1.2s so the mini-app returns home.
     */
    fun simulateHomePillGazeDwell() {
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 1_800L
        if (surface != Surface.App) return
        cancelSimHomePill()
        resetHomePillDwell()
        val cx = (HomeBoardCatalog.HOME_PILL_X + HomeBoardCatalog.HOME_PILL_W / 2f) / 640f
        val cy = (HomeBoardCatalog.HOME_PILL_Y + HomeBoardCatalog.HOME_PILL_H / 2f) / 400f
        var n = 0
        val runnable = object : Runnable {
            override fun run() {
                feedAppHomeGaze(cx, cy)
                n++
                if (n < 14 && surface == Surface.App) {
                    mainHandler.postDelayed(this, 100L)
                } else {
                    simHomePillRunnable = null
                }
            }
        }
        simHomePillRunnable = runnable
        mainHandler.post(runnable)
    }

    fun refreshHomeIfShowing() {
        if (surface == Surface.Home) showHome()
    }

    /** Redraw whatever is showing (Home or the current app screen) once, e.g. after a glass style/opacity change. */
    fun refreshSurfaceIfShowing() {
        when (surface) {
            Surface.Home -> showHome()
            Surface.App -> rePushCachedAppHud()
            else -> Unit
        }
    }

    /**
     * Open shared gaze keyboard. [onDone] receives the typed buffer.
     */
    fun openKeyboard(
        title: String = "Type reply",
        initial: String = "",
        initialMode: KeyboardMode = KeyboardMode.Letters,
        /** When true, bottom Search submits the buffer (song search) instead of emoji filter. */
        searchSubmitsBuffer: Boolean = false,
        /** Done key label — use "Send" for WhatsApp / Instagram replies. */
        doneLabel: String = "OK",
        onDone: (String) -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val s = session ?: return
        surface = Surface.Keyboard
        cancelSimDwell()
        cancelSimHomePill()
        cancelKeyGaze()
        stopAvatarIdleMotion()
        resetDwell()
        resetHomePillDwell()
        keyboardTitle = title
        keyboardBuffer = initial
        keyboardMode = initialMode
        keyboardSearchSubmitsBuffer = searchSubmitsBuffer
        keyboardDoneLabel = doneLabel.ifBlank { "OK" }
        keyboardCapsLocked = false
        keyboardSymbols = false
        keyGazeArmKey = ARM_UNSET
        keyDwell.cancel()
        keyboardPageIndex = 0
        emojiCategoryId = "smileys"
        searchQuery = ""
        focusedKeyId = when (initialMode) {
            KeyboardMode.Letters -> "a"
            KeyboardMode.Categories -> "cat_smileys"
            KeyboardMode.Emoji -> "emoji_3"
            KeyboardMode.Search -> "search_field"
        }
        voiceArmed = false
        voiceMode = VoiceInputMode.None
        voiceStatus = ""
        onKeyboardDone = onDone
        onKeyboardCancel = onCancel
        pushKeyboard(s)
        armKeyGaze(focusedKeyId)
    }

    fun isShowingHome(): Boolean = surface == Surface.Home
    fun isShowingKeyboard(): Boolean = surface == Surface.Keyboard
    fun isShowingApp(): Boolean = surface == Surface.App
    fun activeMiniAppId(): String? = activeMiniAppId
    fun homePillDwellProgress(): Float = homePillDwellProgress

    /** Snapshot for phone HUD preview (home board page). */
    fun previewHomeBoard(): GlassesHud.HomeBoard {
        val pageCount = HomeBoardCatalog.pageCount()
        val idx = pageIndex.coerceIn(0, pageCount - 1)
        val pageWidgets = HomeBoardCatalog.page(idx)
        val time = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date())
        return GlassesHud.HomeBoard(
            pageIndex = idx,
            pageCount = pageCount,
            focusedWidgetId = focusedWidgetId,
            widgets = pageWidgets,
            batteryPercent = MymavDeviceStatus.glassesPercent,
                phoneBatteryPercent = MymavDeviceStatus.phonePercent(),
                glassesCharging = MymavDeviceStatus.glassesCharging,
                phoneCharging = MymavDeviceStatus.phoneCharging(),
            statusLabel = "$time  ·  MyMav",
        )
    }

    /** Snapshot for phone HUD preview of the shared keyboard. */
    fun previewKeyboard(): GlassesHud.Keyboard {
        val (_, pageCount) = GazeKeyboardCatalog.visibleKeys(
            keyboardMode, emojiCategoryId, keyboardPageIndex, searchQuery,
        )
        return GlassesHud.Keyboard(
            title = keyboardTitle,
            buffer = keyboardBuffer.ifBlank { "Hello, 😎" },
            focusedKeyId = focusedKeyId,
            suggestions = GazeKeyboardSuggestions.suggest(keyboardBuffer),
            voiceArmed = voiceArmed,
            voiceMode = voiceMode,
            voiceStatus = voiceStatus,
            mode = keyboardMode,
            pageIndex = keyboardPageIndex,
            pageCount = pageCount,
            categoryId = emojiCategoryId,
            searchQuery = searchQuery,
            capsLocked = keyboardCapsLocked,
            doneLabel = keyboardDoneLabel,
        )
    }

    private fun resetDwell() {
        pageDwell.cancel()
        dwellSide = null
        dwellStartedAt = 0L
        dwellProgress = 0f
        lastDwellUiTick = -1
    }

    private fun resetHomePillDwell() {
        homePillDwelling = false
        homePillDwellStartedAt = 0L
        homePillDwellProgress = 0f
        lastHomePillUiTick = -1
    }

    private fun cancelSimDwell() {
        simDwellRunnable?.let { mainHandler.removeCallbacks(it) }
        simDwellRunnable = null
    }

    private fun cancelSimHomePill() {
        simHomePillRunnable?.let { mainHandler.removeCallbacks(it) }
        simHomePillRunnable = null
    }

    private fun rePushCachedAppHud() {
        val s = session ?: return
        val hud = cachedAppHud ?: return
        val next = when (hud) {
            is GlassesHud.AppEnter -> withActionGaze(hud).copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.Notification -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.Lyrics -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.IdentifyResult -> withActionGaze(hud).copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.SongList -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.FitnessSummary -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.TasksBoard -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.ListApp -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.WorkoutSelect -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.RunningPace -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.WeightSession -> hud.copy(homeDwellProgress = homePillDwellProgress)
            is GlassesHud.NavMap -> hud.copy(homeDwellProgress = homePillDwellProgress)
            else -> return
        }
        cachedAppHud = next
        s.replaceHud(next)
    }

    private fun pushKeyboard(s: GlassesSession) {
        val (keys, pageCount) = GazeKeyboardCatalog.visibleKeys(
            keyboardMode, emojiCategoryId, keyboardPageIndex, searchQuery, keyboardSymbols,
        )
        val suggestions = if (keyboardMode == KeyboardMode.Letters) {
            GazeKeyboardSuggestions.suggest(keyboardBuffer)
        } else {
            emptyList()
        }
        val voiceKeys = listOf("back", "vtxt", "vnote")
        val sugKeys = GazeKeyboardLayout.suggestionKeys(suggestions.size)
        if (
            focusedKeyId !in keys &&
            focusedKeyId !in GazeKeyboardCatalog.FUNCTION_KEYS &&
            focusedKeyId !in voiceKeys &&
            focusedKeyId !in sugKeys
        ) {
            focusedKeyId = if (focusedKeyId.startsWith("sug_")) "spc" else keys.firstOrNull() ?: "fn_abc"
        }
        keyboardPageIndex = keyboardPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        // Phone-style auto-capital at the start of a sentence (letters page only).
        val shift = keyboardCapsLocked ||
            (keyboardMode == KeyboardMode.Letters && !keyboardSymbols && GazeKeyboardSuggestions.autoCapNext(keyboardBuffer))
        val hud = GlassesHud.Keyboard(
                title = keyboardTitle,
                buffer = keyboardBuffer,
                focusedKeyId = focusedKeyId,
                suggestions = suggestions,
                voiceArmed = voiceArmed,
                voiceMode = voiceMode,
                voiceStatus = voiceStatus,
                mode = keyboardMode,
                pageIndex = keyboardPageIndex,
                pageCount = pageCount,
                categoryId = emojiCategoryId,
                searchQuery = searchQuery,
                keyGazeProgress = keyGazeProgress,
                capsLocked = shift,
                doneLabel = keyboardDoneLabel,
                symbols = keyboardSymbols,
                actions = KeyboardActions(
                    onFocusChanged = { key ->
                        focusedKeyId = key
                        holdGazeForManualInput()
                        pushKeyboard(s)
                        armKeyGaze(key)
                    },
                    onKeyCommitted = { ch ->
                        keyboardBuffer = KeyboardPress.appendCommit(keyboardBuffer, ch)
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onSuggestionSelected = { word ->
                        keyboardBuffer = GazeKeyboardSuggestions.applySuggestion(keyboardBuffer, word)
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onVoiceRequested = { runVoiceToText(s) },
                    onVoiceToText = { runVoiceToText(s) },
                    onVoiceNote = { runVoiceNote(s) },
                    onBackspace = {
                        if (keyboardMode == KeyboardMode.Search && searchQuery.isNotEmpty()) {
                            searchQuery = searchQuery.dropLast(1)
                            keyboardPageIndex = 0
                        } else if (keyboardBuffer.isNotEmpty()) {
                            keyboardBuffer = KeyboardPress.dropLastGrapheme(keyboardBuffer)
                        }
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onClear = {
                        if (keyboardMode == KeyboardMode.Search) {
                            searchQuery = ""
                            keyboardPageIndex = 0
                        }
                        keyboardBuffer = ""
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onDone = { typed ->
                        GazeKeyboardSuggestions.learn(typed)
                        finishKeyboardThen {
                            onKeyboardDone?.invoke(typed)
                        }
                    },
                    onCancel = {
                        finishKeyboardThen {
                            onKeyboardCancel?.invoke()
                        }
                    },
                    onCapsToggled = {
                        keyboardCapsLocked = !keyboardCapsLocked
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onSymbolsToggled = {
                        keyboardSymbols = !keyboardSymbols
                        focusedKeyId = "sym"
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onSearchSubmit = if (keyboardSearchSubmitsBuffer) {
                        {
                            finishKeyboardThen {
                                onKeyboardDone?.invoke(keyboardBuffer)
                            }
                        }
                    } else {
                        null
                    },
                    onModeChanged = { mode ->
                        // Song-search keyboards never enter emoji Search mode via Search key.
                        if (keyboardSearchSubmitsBuffer && mode == KeyboardMode.Search) {
                            finishKeyboardThen {
                                onKeyboardDone?.invoke(keyboardBuffer)
                            }
                        } else {
                            keyboardMode = mode
                            keyboardPageIndex = 0
                            if (mode == KeyboardMode.Letters) keyboardSymbols = false
                            focusedKeyId = when (mode) {
                                KeyboardMode.Letters -> "a"
                                KeyboardMode.Categories -> "cat_smileys"
                                KeyboardMode.Emoji -> "emoji_0"
                                KeyboardMode.Search -> "search_field"
                            }
                            pushKeyboard(s)
                            armKeyGaze(focusedKeyId)
                        }
                    },
                    onPageChanged = { next ->
                        val count = pageCount.coerceAtLeast(1)
                        keyboardPageIndex = ((next % count) + count) % count
                        focusedKeyId = keys.firstOrNull { it.startsWith("emoji_") || it.startsWith("semoji_") }
                            ?: focusedKeyId
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onCategorySelected = { id ->
                        emojiCategoryId = id
                        keyboardMode = KeyboardMode.Emoji
                        keyboardPageIndex = 0
                        focusedKeyId = "emoji_0"
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                    onSearchQueryChanged = { q ->
                        searchQuery = q
                        keyboardPageIndex = 0
                        pushKeyboard(s)
                        armKeyGaze(focusedKeyId)
                    },
                ),
            )
        lastKeyboardHud = hud
        s.replaceHud(hud)
    }

    /** Demo voice-to-text stub — fills buffer when OS speech is unavailable. */
    private fun runVoiceToText(s: GlassesSession) {
        voiceArmed = true
        voiceMode = VoiceInputMode.ToText
        voiceStatus = "Voice to text · listening…"
        pushKeyboard(s)
        keyboardBuffer = if (keyboardBuffer.isBlank()) {
            "Sounds good — see you at 3!"
        } else {
            keyboardBuffer.trimEnd() + " (voice)"
        }
        voiceArmed = false
        voiceMode = VoiceInputMode.None
        voiceStatus = "Voice to text · inserted demo phrase"
        pushKeyboard(s)
    }

    /** Demo voice-note stub — status only (clip capture needs mic permission later). */
    private fun runVoiceNote(s: GlassesSession) {
        voiceArmed = true
        voiceMode = VoiceInputMode.Note
        voiceStatus = "Voice note · recording…"
        pushKeyboard(s)
        voiceArmed = false
        voiceMode = VoiceInputMode.None
        voiceStatus = "Voice note · saved demo clip (0:04)"
        pushKeyboard(s)
    }


    /** Restore App surface before keyboard callbacks so lyrics/notify HUD can redraw. */
    private fun finishKeyboardThen(block: () -> Unit) {
        cancelKeyGaze()
        surface = if (activeMiniAppId != null) Surface.App else Surface.None
        block()
    }

    /**
     * Do not auto-fire on focus alone — that felt like the keyboard "tapping itself".
     * Real LOS can call [feedKeyboardKeyGaze] to arm a 1s dwell; Tap always works.
     */
    private fun armKeyGaze(keyId: String) {
        cancelKeyGaze()
        // A key that was just pressed (tap or gaze) needs a fresh look to repeat.
        if (keyDwell.target != null) {
            keyDwell.cancel()
        }
        if (keyGazeArmKey != ARM_UNSET) keyGazeArmKey = focusedKeyId
        keyGazeKeyId = keyId
        keyGazeStartedAt = 0L
        keyGazeProgress = 0f
    }

    /** Call while the user gazes the focused key (normalized sample optional). */
    fun feedKeyboardKeyGaze() {
        if (surface != Surface.Keyboard) return
        val keyId = focusedKeyId
        if (keyGazeKeyId != keyId || keyGazeStartedAt == 0L) {
            keyGazeKeyId = keyId
            keyGazeStartedAt = System.currentTimeMillis()
            keyGazeProgress = 0f
            keyGazeRunnable?.let { mainHandler.removeCallbacks(it) }
            val fire = Runnable {
                if (surface == Surface.Keyboard && focusedKeyId == keyId) {
                    keyGazeProgress = 1f
                    activateFocusedKeyboardKey()
                }
            }
            keyGazeRunnable = fire
            mainHandler.postDelayed(fire, 1_000L)
            return
        }
        val elapsed = System.currentTimeMillis() - keyGazeStartedAt
        keyGazeProgress = (elapsed / 1_000f).coerceIn(0f, 1f)
    }

    private fun cancelKeyGaze() {
        keyGazeRunnable?.let { mainHandler.removeCallbacks(it) }
        keyGazeUiRunnable?.let { mainHandler.removeCallbacks(it) }
        keyGazeRunnable = null
        keyGazeUiRunnable = null
        keyGazeKeyId = null
        keyGazeProgress = 0f
        keyGazeStartedAt = 0L
    }

    private fun holdGazeForManualInput() {
        if (lastGazePx < 0f) return
        gazeHeld = true
        gazeHoldX = lastGazePx
        gazeHoldY = lastGazePy
        keyDwell.cancel()
        openDwell.cancel()
    }

    /** Activate focused key after 1s gaze — exactly the Tap path. */
    private fun activateFocusedKeyboardKey() {
        if (surface != Surface.Keyboard) return
        val hud = lastKeyboardHud ?: return
        val key = focusedKeyId
        cancelKeyGaze()
        KeyboardPress.press(hud.copy(focusedKeyId = key), key)
    }

    /**
     * Gaze on the keyboard: the key under your gaze takes focus; holding it for
     * 1s presses it. The key focused when the keyboard opened never fires until
     * the gaze has moved, and a pressed key needs a fresh look to repeat.
     */
    private fun feedKeyboardGaze(px: Float, py: Float) {
        val hud = lastKeyboardHud ?: return
        val (keys, _) = GazeKeyboardCatalog.visibleKeys(
            hud.mode, hud.categoryId, hud.pageIndex, hud.searchQuery, hud.symbols,
        )
        val sugCount = if (hud.mode == KeyboardMode.Letters) hud.suggestions.size else 0
        val rects = GazeKeyboardLayout.rects(hud.mode, keys, sugCount)
        val key = GazeKeyboardLayout.hit(rects, px, py)
        if (key == null) {
            keyDwell.cancel()
            keyGazeArmKey = ARM_ARMED
            if (keyGazeProgress > 0f) {
                keyGazeProgress = 0f
                session?.let { pushKeyboard(it) }
            }
            return
        }
        if (keyGazeArmKey == ARM_UNSET) {
            keyGazeArmKey = key
            return
        }
        if (keyGazeArmKey != ARM_ARMED) {
            if (key == keyGazeArmKey) return
            keyGazeArmKey = ARM_ARMED
        }
        val s = session ?: return
        if (key != focusedKeyId) {
            focusedKeyId = key
            keyGazeProgress = 0f
            pushKeyboard(s)
        }
        keyDwell.feed(
            key,
            1_000L,
            onTick = { p ->
                keyGazeProgress = p
                pushKeyboard(s)
            },
            onFire = {
                keyGazeProgress = 0f
                // Same key again needs the gaze to leave and come back.
                keyGazeArmKey = key
                activateFocusedKeyboardKey()
            },
        )
    }

    /**
     * Head / eye gaze sample from the glasses (normalised 0..1 on the 640×400
     * canvas). Routed to whatever is on screen.
     */
    fun feedGaze(normalizedX: Float, normalizedY: Float) {
        // A simulated dwell (phone buttons / debug) owns the gaze while it runs;
        // live head samples would otherwise cancel it every 100ms.
        if (android.os.SystemClock.uptimeMillis() < simGazeUntil) return
        val px = normalizedX * 640f
        val py = normalizedY * 400f
        lastGazePx = px
        lastGazePy = py
        // After a D-pad / touch focus move, gaze only takes over again once the
        // head actually moves — otherwise a resting gaze snaps focus straight back.
        if (gazeHeld) {
            val dx = px - gazeHoldX
            val dy = py - gazeHoldY
            if (dx * dx + dy * dy < GAZE_RESUME_PX * GAZE_RESUME_PX) return
            gazeHeld = false
        }
        when (surface) {
            Surface.Home -> { appGazeListener = null; feedHomeGaze(normalizedX, normalizedY) }
            Surface.Keyboard -> { appGazeListener = null; feedKeyboardGaze(normalizedX * 640f, normalizedY * 400f) }
            Surface.App -> {
                feedAppHomeGaze(normalizedX, normalizedY)
                feedAppActionGaze(normalizedX, normalizedY)
                // Apps with gaze controls (e.g. Spotify Prev/Play/Next) receive the same samples.
                appGazeListener?.invoke(normalizedX, normalizedY)
            }
            Surface.None -> Unit
        }
    }

    // ── Readable action row: 1s gaze = tap (Conversate / Food / Shop / Identify) ──

    private val actionDwell by lazy { GazeDwell(mainHandler, 100L) }
    private var actionScreenKey = ""
    /** ARM_UNSET right after a transition; then the target that must be left before it can dwell again. */
    private var actionBlocked: String? = ARM_UNSET
    private var actionTarget: String? = null
    private var actionProgress = 0f
    private val actionLastRun = HashMap<String, Long>()
    private var lastAnyActionAt = 0L
    private val actionLost = Runnable {
        if (actionDwell.target != null || actionProgress > 0f) {
            actionDwell.cancel()
            actionTarget = null
            actionProgress = 0f
            rePushCachedAppHud()
        }
    }

    private fun actionScreenKey(hud: GlassesHud): String = when (hud) {
        is GlassesHud.AppEnter -> "${hud.appId}|${hud.readableMode}|${hud.footerLabels.joinToString(",")}|${hud.offers.size}"
        is GlassesHud.IdentifyResult -> "identify-result|${hud.showingDetails}|${hud.footerLabels.joinToString(",")}"
        else -> hud.javaClass.simpleName
    }

    private fun resetActionGaze(requireLeave: Boolean) {
        mainHandler.removeCallbacks(actionLost)
        actionDwell.cancel()
        actionTarget = null
        actionProgress = 0f
        if (requireLeave) actionBlocked = ARM_UNSET
    }

    /** Debug screenshots only: draw [target] ("f:1" pill / "o:0" offer) at [progress] without dwelling or firing. */
    fun debugFreezeActionProgress(target: String, progress: Float) {
        if (surface != Surface.App) return
        resetActionGaze(requireLeave = false)
        simGazeUntil = android.os.SystemClock.uptimeMillis() + 8_000L
        actionTarget = target
        actionProgress = progress.coerceIn(0f, 1f)
        rePushCachedAppHud()
    }

    private fun withActionGaze(hud: GlassesHud.AppEnter): GlassesHud.AppEnter {
        val t = actionTarget
        return when {
            t != null && t.startsWith("f:") -> hud.copy(
                footerFocus = t.removePrefix("f:").toIntOrNull() ?: hud.footerFocus,
                footerGazeProgress = actionProgress,
                offerGazeIndex = -1,
                offerGazeProgress = 0f,
            )
            t != null && t.startsWith("o:") -> hud.copy(
                footerGazeProgress = 0f,
                offerGazeIndex = t.removePrefix("o:").toIntOrNull() ?: -1,
                offerGazeProgress = actionProgress,
            )
            else -> hud.copy(footerGazeProgress = 0f, offerGazeIndex = -1, offerGazeProgress = 0f)
        }
    }

    private fun withActionGaze(hud: GlassesHud.IdentifyResult): GlassesHud.IdentifyResult {
        val t = actionTarget
        return if (t != null && t.startsWith("f:")) {
            hud.copy(footerFocus = t.removePrefix("f:").toIntOrNull() ?: hud.footerFocus, footerGazeProgress = actionProgress)
        } else {
            hud.copy(footerGazeProgress = 0f)
        }
    }

    private fun actionHit(hud: GlassesHud, px: Float, py: Float): String? {
        val (labels, disabled) = when (hud) {
            is GlassesHud.AppEnter -> {
                if (hud.readableMode == HudReadableMode.Classic) return null
                if (hud.readableMode == HudReadableMode.Shop && hud.product != null) {
                    HudActionLayout.hitOffer(hud.offers.size, px, py)?.let { return "o:$it" }
                }
                hud.footerLabels to hud.footerDisabled
            }
            is GlassesHud.IdentifyResult -> hud.footerLabels to hud.footerDisabled
            else -> return null
        }
        val idx = HudActionLayout.hitPill(labels.size, px, py) ?: return null
        return if (idx in disabled) null else "f:$idx"
    }

    /**
     * Gaze sample on the app surface. The pill under gaze takes focus; holding it 1s fires it once.
     * After a screen change (or a fire) the gaze must leave that pill before it can dwell again.
     */
    fun feedAppActionGaze(normalizedX: Float, normalizedY: Float) {
        if (surface != Surface.App) return
        val hud = cachedAppHud ?: return
        mainHandler.removeCallbacks(actionLost)
        mainHandler.postDelayed(actionLost, ACTION_LOST_MS)
        val hit = actionHit(hud, normalizedX * 640f, normalizedY * 400f)
        val blocked = actionBlocked
        if (blocked == ARM_UNSET) {
            actionBlocked = hit
            return
        }
        if (blocked != null) {
            if (hit == blocked) return
            actionBlocked = null
        }
        if (hit == null) {
            if (actionTarget != null) {
                actionDwell.cancel()
                actionTarget = null
                actionProgress = 0f
                rePushCachedAppHud()
            }
            return
        }
        if (hit == actionDwell.target) return
        actionTarget = hit
        actionProgress = 0f
        if (hit.startsWith("f:")) {
            val idx = hit.removePrefix("f:").toInt()
            val focus = when (hud) {
                is GlassesHud.AppEnter -> hud.footerFocus to hud.actions.onFooterFocusChanged
                is GlassesHud.IdentifyResult -> hud.footerFocus to hud.actions.onFooterFocusChanged
                else -> null
            }
            if (focus != null && focus.first != idx) focus.second?.invoke(idx)
        }
        val key = actionScreenKey
        actionDwell.feed(
            hit,
            HomeBoardCatalog.HOME_GAZE_DWELL_MS,
            onTick = { p ->
                actionProgress = p
                rePushCachedAppHud()
            },
            onFire = {
                actionProgress = 0f
                actionTarget = null
                actionBlocked = hit
                if (key == actionScreenKey) fireAppAction(hit)
                rePushCachedAppHud()
            },
        )
    }

    private fun fireAppAction(target: String) {
        val hud = cachedAppHud ?: return
        val idx = target.drop(2).toIntOrNull() ?: return
        android.util.Log.i("MyMavGaze", "action gaze fire $target screen=$actionScreenKey")
        if (target.startsWith("o:")) {
            (hud as? GlassesHud.AppEnter)?.actions?.onOfferSelected?.let { cb -> runGated("o:$idx") { cb(idx) } }
        } else {
            activateAppFooter(hud, idx)
        }
    }

    /**
     * Tap and gaze both land here: a footer action runs at most once per [ACTION_GATE_MS]
     * and never while that pill is disabled (processing).
     */
    fun activateAppFooter(hud: GlassesHud, idx: Int) {
        val live = (cachedAppHud ?: hud).takeIf { it.javaClass == hud.javaClass } ?: hud
        when (live) {
            is GlassesHud.AppEnter -> {
                if (idx in live.footerDisabled || idx !in live.footerLabels.indices) return
                val cb = live.actions.onFooterActivated ?: return
                runGated("f:$idx:${live.footerLabels[idx]}") { cb(idx) }
            }
            is GlassesHud.IdentifyResult -> {
                if (idx in live.footerDisabled || idx !in live.footerLabels.indices) return
                val cb = live.actions.onFooterActivated ?: return
                runGated("f:$idx:${live.footerLabels[idx]}") { cb(idx) }
            }
            else -> Unit
        }
    }

    private fun runGated(key: String, block: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        val gateKey = "${activeMiniAppId}|$key"
        if (now - (actionLastRun[gateKey] ?: 0L) < ACTION_GATE_MS || now - lastAnyActionAt < ANY_ACTION_GATE_MS) {
            android.util.Log.i("MyMavGaze", "action gated $gateKey")
            return
        }
        actionLastRun[gateKey] = now
        lastAnyActionAt = now
        android.util.Log.i("MyMavGaze", "action run $gateKey")
        // A fired pill also blocks its own gaze dwell until the eyes leave it.
        if (key.startsWith("f:")) actionBlocked = "f:" + key.removePrefix("f:").substringBefore(':')
        block()
    }

    /**
     * Test helper (simulated, not a real eye/head sample): hold gaze at ([nx], [ny]) on the app surface for [ms]
     * by feeding the per-app listener every 50 ms while live head samples are suppressed.
     */
    fun simulateAppGaze(nx: Float, ny: Float, ms: Long) {
        val end = android.os.SystemClock.uptimeMillis() + ms
        simGazeUntil = end + 150L
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var tick: Runnable
        tick = Runnable {
            if (android.os.SystemClock.uptimeMillis() < end) {
                if (surface == Surface.App) {
                    feedAppActionGaze(nx, ny)
                    feedAppHomeGaze(nx, ny)
                    appGazeListener?.invoke(nx, ny)
                }
                h.postDelayed(tick, 50L)
            }
        }
        h.post(tick)
    }

    /**
     * Per-app gaze sink for the app surface (set by an app that draws its own gaze controls, cleared here as soon
     * as the surface leaves the app). The HOME pill keeps its own handling in [feedAppHomeGaze].
     */
    @Volatile
    var appGazeListener: ((Float, Float) -> Unit)? = null

    private enum class Surface { None, Home, Keyboard, App }
    private enum class Side { Left, Right }
}

/**
 * Shared [AppEnterActions] so every mini-app enter glasslet can return home via
 * 1s HOME-pill gaze or long-tap fallback.
 */
fun miniAppHomeActions(): AppEnterActions = AppEnterActions(
    onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
    onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
)
