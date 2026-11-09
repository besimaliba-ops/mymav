package com.mymav.glasses.mav2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everysight.mav2.sdk.Evs
import com.everysight.mav2.sdk.init
import com.everysight.mav2.sdk.services.IM2GlassesConnectionEvents
import com.everysight.mav2.sdk.uikit.data.M2AppUIOption
import com.everysight.mav2.sdk.uikit.data.M2ConnectionStatus
import com.everysight.mav2.sdk.uikit.screens.M2Screen
import com.mymav.glasses.api.ConnectionState
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.mav2.hud.AppEnterScreen
import com.mymav.glasses.mav2.hud.FitnessSummaryScreen
import com.mymav.glasses.mav2.hud.GazeKeyboardScreen
import com.mymav.glasses.mav2.hud.GlassKit
import com.mymav.glasses.mav2.hud.PremiumHudIcons
import com.mymav.glasses.mav2.hud.HomeWidgetBoardScreen
import com.mymav.glasses.mav2.hud.HudFonts
import com.mymav.glasses.mav2.hud.HudPrimitives
import com.mymav.glasses.mav2.hud.HudScreen
import com.mymav.glasses.mav2.hud.HudTheme
import com.mymav.glasses.mav2.hud.IdentifyResultScreen
import com.mymav.glasses.mav2.hud.NavMapScreen
import com.mymav.glasses.mav2.hud.NotifyLyricsScreen
import com.mymav.glasses.mav2.hud.NotifyNotificationScreen
import com.mymav.glasses.mav2.hud.RunningWorkoutScreen
import com.mymav.glasses.mav2.hud.SongListScreen
import com.mymav.glasses.mav2.hud.TasksBoardScreen
import com.mymav.glasses.mav2.hud.ListAppScreen
import com.mymav.glasses.mav2.hud.WeightSessionScreen
import com.mymav.glasses.mav2.hud.WorkoutSelectScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Everysight Maverick AI SDK 0.2.0 (`com.everysight.mav2.sdk`).
 */
class Mav2SdkSession(
    private val appContext: Context,
) : GlassesSession {
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    private val _statusMessage = MutableStateFlow("Maverick AI SDK backend")
    private val _simulatorHost = MutableStateFlow("10.0.2.2")
    private val _hasSdkKey = MutableStateFlow(false)
    private var hudScreen: M2Screen? = null
    /** Model of what [hudScreen] currently shows (for in-place patch diffing). */
    private var lastHud: GlassesHud? = null
    private var pendingHud: GlassesHud? = null
    private var glassesReady = false
    private var connectionListenerRegistered = false
    /** Bumps on every [pushHud] so deferred adds cannot stack ghost boards. */
    private var hudGeneration: Int = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Glasses-side cache is wiped once per app process (before any upload). */
    private var cacheWiped = false
    private var batteryListenerRegistered = false
    /**
     * True once this link session has sent turnDisplayOn() (onReady or a push). Cleared on every
     * (re)connect, unready and stop so a stale flag can never suppress a genuine wake-up. A push only
     * skips the call when this is set AND the SDK itself reports the display on (so Standby -> Power On
     * still re-sends it). Redundant calls were echoed back as a DISPLAY state change while the first
     * texture uploads were in flight, and the SDK dropped those uploads ("SKIPPING texture").
     */
    @Volatile private var displayOnSent = false
    /** When this link session last sent turnDisplayOn() (elapsedRealtime), for the in-flight window below. */
    @Volatile private var displayOnSentAt = 0L

    private fun markDisplayOnSent() {
        displayOnSent = true
        displayOnSentAt = android.os.SystemClock.elapsedRealtime()
    }

    /** @return true when a turnDisplayOn() was actually sent by this call (the caller then lets the echo settle). */
    private fun ensureDisplayOn(): Boolean {
        // Within a few seconds of our own request the SDK may not have processed the state change yet
        // (it reported "off" ~3 s after onReady's request in testing), and its echo is what clobbers the
        // first uploads. Treat the request as in flight; after that, the SDK's own state decides, so a
        // Standby -> Power On transition (state off) is re-sent.
        val inFlight = displayOnSent && android.os.SystemClock.elapsedRealtime() - displayOnSentAt < DISPLAY_ON_INFLIGHT_MS
        val alreadyOn = displayOnSent && runCatching { Evs.displayService.isDisplayOn() }.getOrDefault(false)
        if (inFlight || alreadyOn) { Log.i(TAG, "turnDisplayOn skipped (${if (inFlight) "request in flight" else "display already on"})"); return false }
        Log.i(TAG, "turnDisplayOn sent (push)")
        runCatching { Evs.displayService.turnDisplayOn() }
        markDisplayOnSent()
        return true
    }

    private val connectionListener = object : IM2GlassesConnectionEvents {
        override fun onReady() {
            Log.i(TAG, "glasses onReady")
            HudFonts.connectionEvent("onReady")
            glassesReady = true
            displayOnSent = false
            // The SDK runs this callback inside its packet handler and drops the link if it blocks
            // for ~0.5 s (the emulator's onReady took 470-600 ms, every one followed by
            // "connected session disconnected"). Only flag readiness here; do the work next turn.
            mainHandler.post { onReadyWork() }
        }

        private fun onReadyWork() {
            if (!glassesReady) return // link dropped again before we got here
            runCatching {
                com.mymav.glasses.api.MymavDeviceStatus.glassesPercent = Evs.glassesService.battery()
                com.mymav.glasses.api.MymavDeviceStatus.glassesCharging = Evs.glassesService.isCharging()
                if (!batteryListenerRegistered) {
                    batteryListenerRegistered = true
                    Evs.glassesService.registerBatteryListener(object : com.everysight.mav2.sdk.services.IM2GlassesBatteryEvents {
                        override fun onBatteryPercentageChanged(percent: Int) {
                            com.mymav.glasses.api.MymavDeviceStatus.glassesPercent = percent
                        }
                        override fun onChargingStateChanged(charging: Boolean, connected: Boolean) {
                            com.mymav.glasses.api.MymavDeviceStatus.glassesCharging = charging
                        }
                    })
                }
            }
            // Start from a clean glasses-side texture cache: stale blobs from an
            // earlier run were being re-activated into the wrong images.
            // Every genuine (re)connect, not just the first of the process: after onUnReady the SDK's cache
            // table is stale (new font objects were instantly "uploaded" with no slot, so nothing ever reached
            // the glasses). The font coordinator waits for the asynchronous wipe to settle before uploading.
            cacheWiped = true
            HudFonts.wipeGlassesCache("onReady")
            HudFonts.cacheWiped()
            com.mymav.glasses.mav2.hud.HudImageCache.reset()
            runCatching { Evs.displayService.turnDisplayOn() }
            markDisplayOnSent()
            // Roboto ramp: download (phone cache) + pre-upload, then redraw in it.
            HudFonts.onReady = {
                Log.i(TAG, "Roboto fonts ready — rebuilding current HUD")
                lastHud?.let { pushHud(it) }
            }
            HudFonts.glassesReconnected()
            HudFonts.preload()
            // Phone Settings → Glasses text: fetch the new weights, then redraw.
            com.mymav.glasses.api.MymavHudPrefs.onChanged = {
                mainHandler.post { HudFonts.preload() }
            }
            HeadGaze.start()
            setState(ConnectionState.Connected, "Glasses ready · ws://${_simulatorHost.value}:8765/ws")
            // A reconnect gives the glasses an empty scene: redraw what was showing, or Home on a cold link.
            val hud = pendingHud ?: lastHud
            pendingHud = null
            if (hud != null) pushHud(hud) else com.mymav.glasses.api.GlassesHomeController.showHome()
        }

        override fun onUnReady() {
            Log.i(TAG, "glasses onUnReady")
            HudFonts.connectionEvent("onUnReady")
            HeadGaze.stop()
            com.mymav.glasses.mav2.hud.HudImageCache.reset()
            glassesReady = false
            displayOnSent = false
            setState(ConnectionState.Ready, "Glasses unready / disconnected")
        }

        override fun onConnectionStatusChanged(status: M2ConnectionStatus) {
            Log.i(TAG, "glasses connectionStatus=$status")
            HudFonts.connectionEvent("status=$status")
            when (status) {
                M2ConnectionStatus.Connected, M2ConnectionStatus.Ready ->
                    setState(ConnectionState.Connected, "Simulator ws://${_simulatorHost.value}:8765/ws")
                M2ConnectionStatus.Connecting ->
                    setState(ConnectionState.Connecting, "Connecting…")
                M2ConnectionStatus.Disconnected, M2ConnectionStatus.BluetoothOff -> {
                    glassesReady = false
                    setState(ConnectionState.Ready, "Disconnected")
                }
                M2ConnectionStatus.Failed, M2ConnectionStatus.AuthFailed -> {
                    glassesReady = false
                    setState(ConnectionState.Failed, "Connection $status")
                }
            }
        }
    }

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    override val simulatorHost: StateFlow<String> = _simulatorHost.asStateFlow()
    override val hasSdkKey: StateFlow<Boolean> = _hasSdkKey.asStateFlow()
    override val backendName: String = "mav2-sdk"
    override val isLinkReady: Boolean
        get() = glassesReady && runCatching { Evs.wasInitialized() && Evs.glassesService.isReady() }.getOrDefault(false)

    private fun setState(state: ConnectionState, message: String) {
        Log.i(TAG, "state=$state msg=$message")
        _connectionState.value = state
        _statusMessage.value = message
    }

    private fun ensureConnectionListener() {
        if (connectionListenerRegistered || !Evs.wasInitialized()) return
        runCatching {
            Evs.glassesService.registerConnectionListener(connectionListener)
            connectionListenerRegistered = true
        }
    }

    override fun init() {
        setState(ConnectionState.Initializing, "Initializing Evs…")
        _hasSdkKey.value = try {
            appContext.assets.open("sdk.key").use { it.available() > 0 }
        } catch (_: Exception) {
            false
        }
        try {
            if (!Evs.wasInitialized()) {
                Log.i(TAG, "calling Evs.init")
                Evs.init(appContext)
            }
            if (_hasSdkKey.value) {
                val ok = runCatching {
                    appContext.assets.open("sdk.key").use { input ->
                        Evs.setDeveloperKey(input.readBytes())
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "setDeveloperKey failed", e)
                    false
                }
                Log.i(TAG, "setDeveloperKey result=$ok")
            }
            ensureConnectionListener()
            setState(ConnectionState.Ready, "Evs.init OK — set simulator host then Connect")
        } catch (t: Throwable) {
            Log.e(TAG, "Evs.init failed", t)
            setState(ConnectionState.Failed, "Evs.init failed: ${t.message}")
        }
    }

    override fun stop() {
        clearHud()
        try {
            if (Evs.wasInitialized()) {
                if (connectionListenerRegistered) {
                    runCatching { Evs.glassesService.unregisterConnectionListener(connectionListener) }
                    connectionListenerRegistered = false
                }
                Evs.glassesService.disconnect()
            }
        } catch (_: Throwable) {
        }
        glassesReady = false
        displayOnSent = false
        _connectionState.value = ConnectionState.Stopped
        _statusMessage.value = "SDK stopped"
    }

    override fun setSimulatorHost(host: String) {
        _simulatorHost.value = host.trim().ifEmpty { "10.0.2.2" }
    }

    override fun connectSimulator() {
        setState(ConnectionState.Connecting, "Connecting simulator ${_simulatorHost.value}…")
        try {
            if (!Evs.wasInitialized()) {
                Log.i(TAG, "Evs not initialized; calling init before connect")
                Evs.init(appContext)
            }
            Log.i(TAG, "setConfiguredSimulator host=${_simulatorHost.value}")
            Evs.glassesService.setConfiguredSimulator(_simulatorHost.value)
            Log.i(TAG, "glassesService.connect()")
            displayOnSent = false
            Evs.glassesService.connect()
            HudFonts.connectionEvent("connect() requested")
            // A fresh connect() starts a new glasses-side session without a new onReady: whatever
            // fonts were uploaded earlier are gone, so push them again.
            if (glassesReady) mainHandler.post { HudFonts.glassesReconnected() }
            setState(ConnectionState.Connected, "Simulator ws://${_simulatorHost.value}:8765/ws (waiting ready)")
        } catch (t: Throwable) {
            Log.e(TAG, "Simulator connect failed", t)
            setState(ConnectionState.Failed, "Simulator connect failed: ${t.message}")
        }
    }

    override fun connectDevice() {
        _connectionState.value = ConnectionState.Connecting
        try {
            if (!Evs.wasInitialized()) {
                Evs.init(appContext)
            }
            ensureConnectionListener()
            Evs.showAppUI(M2AppUIOption.DefaultConfigure)
            _statusMessage.value = "Device mode — configure UI opened"
            _connectionState.value = ConnectionState.Ready
        } catch (t: Throwable) {
            _connectionState.value = ConnectionState.Failed
            _statusMessage.value = "Device configure failed: ${t.message}"
        }
    }

    override fun disconnect() {
        try {
            Evs.glassesService.disconnect()
        } catch (_: Throwable) {
        }
        glassesReady = false
        displayOnSent = false
        _connectionState.value = ConnectionState.Ready
        _statusMessage.value = "Disconnected"
    }

    override fun showConfigureUi() {
        try {
            Evs.showAppUI(M2AppUIOption.DefaultConfigure)
            _statusMessage.value = "Configure UI opened"
        } catch (t: Throwable) {
            _statusMessage.value = "Configure UI: ${t.message}"
        }
    }

    override fun replaceHud(screenId: String, title: String, lines: List<String>) {
        replaceHud(GlassesHud.TextLines(screenId, title, lines))
    }

    override fun replaceHud(hud: GlassesHud) {
        if (!Evs.wasInitialized()) {
            _statusMessage.value = "HUD skipped — Evs not initialized"
            return
        }
        if (!glassesReady && !Evs.glassesService.isReady()) {
            pendingHud = hud
            _statusMessage.value = "HUD queued until glasses ready [${hudLabel(hud)}]"
            Log.i(TAG, "HUD queued ${hudLabel(hud)}")
            return
        }
        if (hud is GlassesHud.Lyrics && patchLyricsHud(hud)) {
            return
        }
        if (tryPatchLive(hud)) {
            return
        }
        pushHud(hud)
    }

    /**
     * Update the live screen in place when only gaze-rate state changed (focus,
     * dwell progress, buffer text, HOME-pill dwell). Rebuilding the whole scene
     * for these was the main source of flicker: every tick removed the screen,
     * waited 80ms and re-uploaded every drawable and texture.
     */
    private fun tryPatchLive(hud: GlassesHud): Boolean {
        val live = hudScreen as? HudScreen ?: return false
        val previous = lastHud
        val patched = runCatching {
            when {
                live.tryPatch(hud) -> true
                previous != null && onlyHomeDwellChanged(previous, hud) ->
                    live.patchHomeDwell(homeDwellOf(hud))
                else -> false
            }
        }.onFailure { Log.w(TAG, "live patch failed, rebuilding: ${it.message}") }
            .getOrDefault(false)
        if (patched) {
            GlassesHomeController.rememberAppHud(hud)
            lastHud = hud
        }
        return patched
    }

    private fun homeDwellOf(hud: GlassesHud): Float = when (hud) {
        is GlassesHud.AppEnter -> hud.homeDwellProgress
        is GlassesHud.Notification -> hud.homeDwellProgress
        is GlassesHud.Lyrics -> hud.homeDwellProgress
        is GlassesHud.IdentifyResult -> hud.homeDwellProgress
        is GlassesHud.SongList -> hud.homeDwellProgress
        is GlassesHud.FitnessSummary -> hud.homeDwellProgress
        is GlassesHud.TasksBoard -> hud.homeDwellProgress
        is GlassesHud.WorkoutSelect -> hud.homeDwellProgress
        is GlassesHud.RunningPace -> hud.homeDwellProgress
        is GlassesHud.WeightSession -> hud.homeDwellProgress
        else -> 0f
    }

    private fun withoutHomeDwell(hud: GlassesHud): GlassesHud? = when (hud) {
        is GlassesHud.AppEnter -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.Notification -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.Lyrics -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.IdentifyResult -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.SongList -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.FitnessSummary -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.TasksBoard -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.WorkoutSelect -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.RunningPace -> hud.copy(homeDwellProgress = 0f)
        is GlassesHud.WeightSession -> hud.copy(homeDwellProgress = 0f)
        else -> null
    }

    /** True when [next] is [previous] with only the HOME-pill dwell progress changed. */
    private fun onlyHomeDwellChanged(previous: GlassesHud, next: GlassesHud): Boolean {
        if (previous::class != next::class) return false
        val a = withoutHomeDwell(previous) ?: return false
        val b = withoutHomeDwell(next) ?: return false
        return a == b
    }

    override fun patchLyricsHud(hud: GlassesHud.Lyrics): Boolean {
        val existing = hudScreen as? NotifyLyricsScreen ?: return false
        val nextKey =
            "${hud.trackName}|${hud.artistName}|${hud.albumArtWidth}x${hud.albumArtHeight}|${hud.artIsVideoPreview}"
        if (existing.trackKey != nextKey) return false
        return runCatching {
            existing.patchLyrics(hud.currentLyric, hud.nextLyric, hud.progressMs, hud.isPlaying)
            existing.patchHomeDwell(hud.homeDwellProgress)
            GlassesHomeController.rememberAppHud(hud)
            lastHud = hud
            _statusMessage.value = "HUD [spotify-lyrics] patched"
            true
        }.getOrDefault(false)
    }

    override fun patchHomeAvatar(
        visible: Boolean,
        x: Float,
        y: Float,
        imageBytes: ByteArray?,
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        val board = hudScreen as? HomeWidgetBoardScreen ?: return false
        return runCatching {
            board.patchAvatar(visible, x, y, imageBytes, imageWidth, imageHeight)
        }.getOrDefault(false)
    }

    override fun patchHomeAvatarCell(visible: Boolean, x: Float, y: Float, cell: Int): Boolean {
        val board = hudScreen as? HomeWidgetBoardScreen ?: return false
        return runCatching { board.patchAvatarCell(visible, x, y, cell) }.getOrDefault(false)
    }

    private val deferredPush = Runnable { lastHud?.let { pushHud(it) } }

    private fun pushHud(hud: GlassesHud) {
        // Anything uploaded while the glasses are still processing clearAllCache() (stock-font glyphs, images)
        // is wiped afterwards while the SDK believes it is resident: labels/artwork stay blank. Hold the
        // draw until the wipe has settled; the latest HUD wins.
        val wipeWait = HudFonts.wipeRemainingMs()
        if (wipeWait > 0L) {
            lastHud = hud
            mainHandler.removeCallbacks(deferredPush)
            mainHandler.postDelayed(deferredPush, wipeWait)
            Log.i(TAG, "HUD push deferred ${wipeWait}ms until glasses cache wipe settles")
            return
        }
        try {
            if (ensureDisplayOn()) {
                // The glasses answer turnDisplayOn with a DISPLAY state change; if the first texture uploads are
                // already in flight the SDK drops them ("SKIPPING texture"). Let the echo land, then build.
                lastHud = hud
                mainHandler.removeCallbacks(deferredPush)
                mainHandler.postDelayed(deferredPush, DISPLAY_ON_SETTLE_MS)
                Log.i(TAG, "HUD push deferred ${DISPLAY_ON_SETTLE_MS}ms for the display-on echo")
                return
            }
            GlassesHomeController.rememberAppHud(hud)
            lastHud = hud
            // Drop every prior screen so page-turn / jump rebuilds cannot stack
            // home boards (ghost tiny avatars + double green MyMav clones).
            // Park old avatar off-canvas first (removeScreen is async under the hood).
            val previous = hudScreen
            hudScreen = null
            if (previous is HomeWidgetBoardScreen) {
                runCatching {
                    previous.patchAvatar(false, -400f, -400f, null, 0, 0)
                }
            }
            previous?.let { runCatching { Evs.screenService.removeScreen(it) } }
            var guard = 0
            while (Evs.screenService.stackSize() > 0 && guard++ < 12) {
                runCatching { Evs.screenService.removeTopmostScreen() }
            }
            val gen = ++hudGeneration
            val screen = buildScreen(hud)
            hudScreen = screen
            val label = hudLabel(hud)
            val addScreen = Runnable {
                if (hudGeneration != gen || hudScreen !== screen) return@Runnable
                // Final drain after async removes settle — never stack orphan boards.
                var g2 = 0
                while (Evs.screenService.stackSize() > 0 && g2++ < 12) {
                    val top = runCatching { Evs.screenService.topmostScreen }.getOrNull()
                    if (top == null || top === screen) break
                    runCatching { Evs.screenService.removeTopmostScreen() }
                }
                runCatching { Evs.screenService.addScreen(screen) }
                _statusMessage.value = "HUD [$label] (${Evs.screenService.stackSize()} screens)"
                Log.i(TAG, "HUD added $label stack=${Evs.screenService.stackSize()}")
            }
            // Only defer when replacing a live screen (avoids Ask blank race on first push).
            if (previous != null) {
                // 80ms lets async removeScreen finish so orphan home boards cannot
                // leave tiny ghost mascots / double green clones in the scene graph.
                mainHandler.postDelayed(addScreen, 80L)
            } else {
                addScreen.run()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "HUD failed", t)
            _statusMessage.value = "HUD failed: ${t.message}"
        }
    }

    private fun buildScreen(hud: GlassesHud): M2Screen = when (hud) {
        is GlassesHud.TextLines -> PremiumTextHudScreen(hud.screenId, hud.title, hud.lines)
        is GlassesHud.HomeBoard -> HomeWidgetBoardScreen(hud)
        is GlassesHud.Keyboard -> GazeKeyboardScreen(hud)
        is GlassesHud.AppEnter -> AppEnterScreen(hud)
        is GlassesHud.Notification -> NotifyNotificationScreen(hud)
        is GlassesHud.Lyrics -> NotifyLyricsScreen(hud)
        is GlassesHud.IdentifyResult -> IdentifyResultScreen(hud)
        is GlassesHud.NavMap -> NavMapScreen(hud)
        is GlassesHud.SongList -> SongListScreen(hud)
        is GlassesHud.FitnessSummary -> FitnessSummaryScreen(hud)
        is GlassesHud.TasksBoard -> TasksBoardScreen(hud)
        is GlassesHud.ListApp -> ListAppScreen(hud)
        is GlassesHud.WorkoutSelect -> WorkoutSelectScreen(hud)
        is GlassesHud.RunningPace -> RunningWorkoutScreen(hud)
        is GlassesHud.WeightSession -> WeightSessionScreen(hud)
        is GlassesHud.MemoryTimeline -> PremiumTextHudScreen(
            "memorysight",
            hud.title,
            buildList {
                if (hud.statusLine.isNotBlank()) add(hud.statusLine)
                addAll(hud.entries)
            },
        )
        is GlassesHud.MoneyQuote -> PremiumTextHudScreen(
            "money",
            "Money",
            listOfNotNull(
                hud.foreignLabel.takeIf { it.isNotBlank() },
                hud.homeLabel.takeIf { it.isNotBlank() },
                hud.rateLabel.takeIf { it.isNotBlank() },
            ),
        )
        is GlassesHud.MymindBoard -> PremiumTextHudScreen(
            "mymind-${hud.page.name.lowercase()}",
            "Mymind · ${hud.page.name}",
            buildList {
                if (hud.memorySummary.isNotBlank()) add(hud.memorySummary)
                hud.peopleLines.forEach { add("· $it") }
                hud.taskLines.forEach { add("□ $it") }
            },
        )
    }

    private fun hudLabel(hud: GlassesHud): String = when (hud) {
        is GlassesHud.TextLines -> hud.screenId
        is GlassesHud.HomeBoard -> "home-board/p${hud.pageIndex}"
        is GlassesHud.Keyboard -> "gaze-keyboard"
        is GlassesHud.AppEnter -> "enter-${hud.appId}"
        is GlassesHud.Notification -> "notify-message/${hud.sender}"
        is GlassesHud.Lyrics -> "spotify-lyrics"
        is GlassesHud.IdentifyResult -> "identify/${hud.label}"
        is GlassesHud.NavMap -> "nav-map"
        is GlassesHud.SongList -> "spotify-songs"
        is GlassesHud.FitnessSummary -> "fitness-${hud.page.name.lowercase()}"
        is GlassesHud.TasksBoard -> "tasks-board"
        is GlassesHud.ListApp -> "list-${hud.appId}"
        is GlassesHud.WorkoutSelect -> "workout-select"
        is GlassesHud.RunningPace -> "running-pace"
        is GlassesHud.WeightSession -> "weight-session"
        is GlassesHud.MemoryTimeline -> "memorysight"
        is GlassesHud.MoneyQuote -> "money-quote"
        is GlassesHud.MymindBoard -> "mymind-${hud.page.name.lowercase()}"
    }

    override fun clearHud() {
        // Never wipe a home board that was just pushed (long-tap HOME race).
        if (GlassesHomeController.isShowingHome()) {
            Log.i(TAG, "clearHud skipped — home board is showing")
            return
        }
        pendingHud = null
        try {
            hudScreen?.let { Evs.screenService.removeScreen(it) }
        } catch (_: Throwable) {
        }
        hudScreen = null
        lastHud = null
        // Soft idle glass so the simulator never sits on "waiting for draw commands".
        runCatching {
            if (Evs.wasInitialized() && (glassesReady || Evs.glassesService.isReady())) {
                val idle = PremiumTextHudScreen(
                    "idle",
                    "MyMav",
                    listOf("Returning home…", "Long-tap HOME anytime"),
                )
                hudScreen = idle
                Evs.screenService.addScreen(idle)
                _statusMessage.value = "HUD idle placeholder"
                return
            }
        }
        _statusMessage.value = "HUD cleared"
    }
}

private const val TAG = "MyMavSdkSession"
private const val DISPLAY_ON_INFLIGHT_MS = 10_000L
private const val DISPLAY_ON_SETTLE_MS = 1_000L

/**
 * Fallback text HUD (Memory, Money, Mymind, plain TextLines) on the shared glass system: one app panel, the shared
 * header, and one glass row per line. The panel colour follows the app's override (screen ids map to the widget
 * ids memorysight / money / mymind); unknown ids use the default tint.
 */
private class PremiumTextHudScreen(
    private val screenId: String,
    private val title: String,
    private val lines: List<String>,
) : HudScreen(tag = "mymav-$screenId") {
    override val glassScope: String? = screenId.substringBefore('-')
        .takeIf { it in setOf("memorysight", "money", "mymind") }
        ?.let { com.mymav.glasses.api.MymavHudPrefs.appScope(it) }

    override fun build() {
        add(HudPrimitives.fullBleedBackground())
        GlassKit.appPanel(this)
        GlassKit.header(this, title, screenId.substringBefore('-').takeIf { PremiumHudIcons.has(it) })
        // Labels longer than ~40 characters are not drawn by the glasses (seen in the simulator: the row stays
        // blank), so long lines are word-wrapped to short chunks: one or two text lines per glass row.
        var y = 84f
        for (line in lines) {
            val chunks = wrapChunks(line, MAX_LABEL_CHARS, 2)
            val rowH = if (chunks.size > 1) 62f else 46f
            if (y + rowH > 372f) break
            GlassKit.card(this, 32f, y, 576f, rowH, radius = 14f)
            chunks.forEachIndexed { i, c ->
                add(HudPrimitives.label(c, 48f, y + 12f + i * 22f - (if (chunks.size > 1) 4f else 0f), 0.66f, HudTheme.TextPrimary, maxWidth = 544f))
            }
            y += rowH + 6f
        }
    }
}

private const val MAX_LABEL_CHARS = 38

/** Word-wrap [text] into at most [maxLines] chunks of at most [max] chars; the last chunk ends in "..." if text was dropped. */
private fun wrapChunks(text: String, max: Int, maxLines: Int): List<String> {
    val words = text.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf(" ")
    val out = mutableListOf<String>()
    var cur = ""
    var dropped = false
    for (raw in words) {
        val w = if (raw.length > max) raw.take(max - 3) + "..." else raw
        if (cur.isEmpty()) { cur = w; continue }
        if (cur.length + 1 + w.length <= max) { cur += " $w"; continue }
        out += cur
        if (out.size == maxLines) { dropped = true; cur = ""; break }
        cur = w
    }
    if (cur.isNotEmpty()) out += cur
    if (dropped) out[out.lastIndex] = out.last().take(max - 3).trimEnd() + "..."
    return out
}

/** Test-only hook (reached only from the debug receiver's TEST_ONLY commands): put the display in standby. */
object Mav2DebugHooks {
    fun displayOff() { runCatching { Evs.displayService.turnDisplayOff() } }
    fun displayOn(): Boolean = runCatching { Evs.displayService.isDisplayOn() }.getOrDefault(false)
}
