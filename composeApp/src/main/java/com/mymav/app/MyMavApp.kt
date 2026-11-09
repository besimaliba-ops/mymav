package com.mymav.app

import android.app.Application
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniAppOrderStore
import com.mymav.glasses.api.MiniAppRegistry
import com.mymav.glasses.api.MyMavWeather
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.MymavAvatarArt
import com.mymav.glasses.api.MymavAvatarPrefs
import com.mymav.glasses.api.ShellSessionHolder
import com.mymav.glasses.api.SimulatorPrefs
import com.mymav.glasses.mav2.GlassesSessionFactory
import com.mymav.glasses.mav2.hud.BrandHudIcons
import com.mymav.glasses.stub.StubGlassesSession
import com.mymav.miniapps.memorysight.MemorySightBackgroundEngine
import com.mymav.miniapps.money.MoneyBackgroundEngine
import com.mymav.miniapps.mymind.MymindBackgroundEngine
import com.mymav.miniapps.conversate.ConversateMiniApp
import com.mymav.miniapps.fitness.FitnessMiniApp
import com.mymav.miniapps.food.FoodMiniApp
import com.mymav.miniapps.football.FootballMiniApp
import com.mymav.miniapps.gallery.GalleryMiniApp
import com.mymav.miniapps.identify.IdentifyMiniApp
import com.mymav.miniapps.memorysight.MemorySightMiniApp
import com.mymav.miniapps.mirror.MirrorMiniApp
import com.mymav.miniapps.money.MoneyMiniApp
import com.mymav.miniapps.mymind.MymindMiniApp
import com.mymav.miniapps.notify.NotifyMiniApp
import com.mymav.miniapps.notify.NotifySessionBridge
import com.mymav.miniapps.notify.service.NotifyCompanionPrefs
import com.mymav.miniapps.notify.service.NotifyCompanionService
import com.mymav.miniapps.shop.ShopMiniApp
import com.mymav.miniapps.spotify.SpotifyMiniApp
import com.mymav.miniapps.spotify.SpotifySessionBridge
import com.mymav.miniapps.tasks.TasksMiniApp
import com.mymav.miniapps.saved.NotepadMiniApp
import com.mymav.miniapps.saved.RecipeBookMiniApp
import com.mymav.miniapps.saved.SavedApps
import com.mymav.miniapps.saved.ShoppingMiniApp
import com.mymav.miniapps.workouts.WorkoutsMiniApp

class MyMavApp : Application() {
    lateinit var glassesSession: GlassesSession
        private set

    override fun onCreate() {
        super.onCreate()
        MymavAvatarArt.install(this)
        DebugPhoneDriver.install(this)
        com.mymav.glasses.api.MymavHudPrefs.install(this)
        com.mymav.glasses.mav2.hud.HeadingShadowTrial.load()
        com.mymav.glasses.api.HudLineLayout.widthPx = { text, scale, bold ->
            com.mymav.glasses.mav2.hud.HudText.width(text, scale, bold)
        }
        com.mymav.glasses.api.MymavAi.install(this)
        com.mymav.glasses.api.MymavShopOffers.install(this)
        com.mymav.glasses.api.AssistantLog.install(this)
        com.mymav.glasses.api.MymavConvert.install(this)
        com.mymav.glasses.api.MymavMemory.install(this)
        com.mymav.glasses.api.MymavSpeech.install(this)
        com.mymav.glasses.api.MymavNav.install(this)
        MyMavMeeting.install(this)
        MyMavProactive.install(this)
        com.mymav.glasses.api.MymavCaptions.install(this)
        ConversateMiniApp.install(this)
        com.mymav.miniapps.food.FoodScanEngine.install(this)
        com.mymav.miniapps.shop.ShopScanEngine.bindPhone(this)
        FootballMiniApp.install(this)
        com.mymav.glasses.api.MymavVision.glassesFrame = { com.mymav.glasses.mav2.Mav2Camera.grab() }
        com.mymav.miniapps.shop.PhoneCameraGrabber.install(this) {
            listOf(
                com.mymav.miniapps.food.FoodCameraCaptureHolder.imageCapture,
                com.mymav.miniapps.identify.IdentifyCameraCaptureHolder.imageCapture,
            )
        }
        com.mymav.glasses.api.MymavLearning.install(this)
        com.mymav.glasses.api.MymavActions.meeting = MyMavMeeting
        com.mymav.glasses.api.MymavNav.onText = { com.mymav.glasses.api.GlassesHomeController.showNavigationUpdate(it) }
        com.mymav.glasses.api.MymavNav.onState = { com.mymav.glasses.api.GlassesHomeController.onNavState(it) }
        com.mymav.glasses.api.GazeKeyboardSuggestions.install(this)
        BrandHudIcons.install(this)
        // Glass opacity / panel style: coalesce rapid changes into one redraw of the current glasses screen.
        val glassHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var glassLastRun = 0L
        var glassPending = false
        val glassRefresh = Runnable {
            glassPending = false
            glassLastRun = android.os.SystemClock.uptimeMillis()
            com.mymav.glasses.api.GlassesHomeController.refreshSurfaceIfShowing()
        }
        // Throttle, not debounce: dragging a slider or colour picker redraws the glasses at most every ~1.8 s (a rebuild
        // needs its textures acknowledged first), always with the latest value, never one rebuild per movement.
        val glassChanged: () -> Unit = {
            if (!glassPending) {
                glassPending = true
                val since = android.os.SystemClock.uptimeMillis() - glassLastRun
                glassHandler.postDelayed(glassRefresh, (1800L - since).coerceIn(500L, 1800L))
            }
        }
        com.mymav.glasses.api.MymavHudPrefs.onGlassChanged = glassChanged
        com.mymav.glasses.api.MymavHudPrefs.onPanelStyleChanged = glassChanged
        com.mymav.miniapps.fitness.FitnessMiniApp.attachContext(this) // creates the store; cached live Health data reaches Home
        MyMavWeather.install(this)
        com.mymav.glasses.api.MymavDeviceStatus.install(this)
        listOf(
            NotifyMiniApp,
            SpotifyMiniApp,
            ConversateMiniApp,
            FoodMiniApp,
            ShopMiniApp,
            IdentifyMiniApp,
            FootballMiniApp,
            FitnessMiniApp,
            WorkoutsMiniApp,
            MirrorMiniApp,
            GalleryMiniApp,
            TasksMiniApp,
            RecipeBookMiniApp,
            NotepadMiniApp,
            ShoppingMiniApp,
            MemorySightMiniApp,
            MoneyMiniApp,
            MymindMiniApp,
            com.mymav.glasses.api.NavigationMiniApp,
        ).forEach(MiniAppRegistry::register)
        com.mymav.glasses.api.NavWidget.install()
        com.mymav.glasses.api.MymavNav.onWidget = { com.mymav.glasses.api.GlassesHomeController.refreshNavWidget() }
        MiniAppOrderStore(this).applyToCatalog()
        TasksMiniApp.ensureStore(applicationContext)
        SavedApps.install(applicationContext)
        com.mymav.glasses.api.MymavTimers.install(applicationContext)
        com.mymav.glasses.api.MymavTimers.onFire = { label -> com.mymav.glasses.api.GlassesHomeController.onTimerFired(label) }
        com.mymav.glasses.api.MymavActions.calendar = MyMavCalendar(applicationContext)
        com.mymav.glasses.api.MymavActions.spotify = com.mymav.miniapps.spotify.service.SpotifyVoice(applicationContext)
        com.mymav.glasses.api.MymavActions.messages = MyMavMessages(applicationContext)
        WorkoutsMiniApp.ensureStore(applicationContext)
        MemorySightMiniApp.ensureStore(applicationContext)
        MoneyMiniApp.ensureStore(applicationContext)
        MymindMiniApp.ensureStore(applicationContext)
        SpotifyMiniApp.ensureContext(applicationContext)
        GlassesHomeController.setAvatarMode(MymavAvatarPrefs.mode(this))

        // Process-lifetime session so Notify FGS / listener survive Activity death.
        val stub = StubGlassesSession(applicationContext)
        glassesSession = GlassesSessionFactory.create(applicationContext, stub).also { session ->
            session.init()
            session.setSimulatorHost(SimulatorPrefs.host(this))
            ShellSessionHolder.install(session)
            NotifySessionBridge.attach(session)
            SpotifySessionBridge.attach(session)
        }
        GlassesLinkService.start(this)

        // Background mock pipelines while companion / FGS is wanted (emulator-safe).
        runCatching { MemorySightBackgroundEngine.seedIfNeeded(applicationContext) }
        runCatching { MoneyBackgroundEngine.seedIfNeeded(applicationContext) }
        runCatching { MymindBackgroundEngine.seedIfNeeded(applicationContext) }

        if (NotifyCompanionPrefs.isWanted(this)) {
            // Never let companion restore crash the whole process on cold start.
            runCatching { NotifyCompanionService.start(this) }
                .onFailure {
                    android.util.Log.e("MyMavApp", "Notify companion restore failed", it)
                    NotifyCompanionPrefs.setWanted(this, false)
                }
        }
    }
}
