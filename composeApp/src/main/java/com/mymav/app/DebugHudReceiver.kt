package com.mymav.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.ShellSessionHolder
import com.mymav.glasses.api.SimulatorPrefs
import com.mymav.glasses.mav2.hud.BrandHudIcons

/**
 * Lock-screen-safe debug hooks for cloud-agent verify:
 * `adb shell am broadcast -a com.mymav.app.DEBUG_HUD --es cmd home|summon|dock|page|keyboard|dump [--es host <pc-ip>]`
 */
class DebugHudReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd")?.lowercase().orEmpty().ifBlank { "home" }
        Log.i(TAG, "DEBUG_HUD cmd=$cmd")
        // Test hooks that change settings or the link are for debuggable builds only.
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable && cmd in TEST_ONLY_CMDS) {
            Log.w(TAG, "cmd=$cmd ignored: test-only command is disabled in release builds")
            return
        }
        val app = context.applicationContext as? MyMavApp
        val session = ShellSessionHolder.get() ?: app?.glassesSession
        if (session == null) {
            Log.e(TAG, "no glasses session")
            return
        }
        // Optional: `--es host 192.168.x.y` points the SDK at this PC's simulator first.
        intent?.getStringExtra("host")?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
            session.setSimulatorHost(host)
            SimulatorPrefs.saveHost(context.applicationContext, host)
            Log.i(TAG, "DEBUG_HUD host=$host")
        }
        val arg = intent?.getStringExtra("arg").orEmpty()
        val main = Handler(Looper.getMainLooper())
        main.post {
            BrandHudIcons.install(context.applicationContext)
            BrandHudIcons.invalidate()
            // Keep the phone UI's open/return handlers — passing a no-op here broke Tap-to-open.
            GlassesHomeController.attach(session)
            when (cmd) {
                "dump" -> {
                    BrandHudIcons.whatsApp(128)
                    BrandHudIcons.instagram(128)
                    BrandHudIcons.spotify(128)
                }
                "summon" -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.showHome()
                        GlassesHomeController.summonMyMav()
                    }, 1200L)
                }
                "dock" -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.showHome()
                        GlassesHomeController.dockAvatarQuiet()
                    }, 800L)
                }
                // Test helper: each call asks MyMav the next canned question (recipe, pictures, tasks).
                "keyboard" -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.showHome()
                        GlassesHomeController.summonMyMav(DEMO_ASKS[demoIndex++ % DEMO_ASKS.size])
                    }, 1200L)
                }
                "kbd" -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.openKeyboard(
                            title = "Demo keyboard",
                            initial = "",
                            onDone = { GlassesHomeController.showHome() },
                            onCancel = { GlassesHomeController.showHome() },
                        )
                    }, 1200L)
                }
                // Verification helpers (use with the local claude-ext helper): `--es arg <value>`.
                "cache" -> com.mymav.glasses.mav2.hud.HudFonts.logCache("dbg")
                "texcache" -> Log.i(TAG, "DBGTEX ${com.mymav.glasses.mav2.hud.HudPerf.texcache(arg)}")
                "perf" -> when (arg) {
                    "reset" -> com.mymav.glasses.mav2.hud.HudPerf.reset()
                    "stall" -> com.mymav.glasses.mav2.hud.HudPerf.monitorStalls(true)
                    "nostall" -> com.mymav.glasses.mav2.hud.HudPerf.monitorStalls(false)
                    else -> com.mymav.glasses.mav2.hud.HudPerf.dump(arg)
                }
                "prefs" -> Log.i(TAG, "DBGPREFS font=${com.mymav.glasses.api.MymavHudPrefs.fontStyle()} size=${com.mymav.glasses.api.MymavHudPrefs.textSize()} glass=${com.mymav.glasses.api.MymavHudPrefs.glassOpacityPercent()} panel=${com.mymav.glasses.api.MymavHudPrefs.panelStyle()} tint=${com.mymav.glasses.api.MymavHudPrefs.defaultTintKey()}@${com.mymav.glasses.api.MymavHudPrefs.tintStrength()} ver=${com.mymav.glasses.api.MymavHudPrefs.glassVersion} hshadow=${com.mymav.glasses.mav2.hud.HeadingShadowTrial.enabled}")
                "gs" -> {
                    val want = com.mymav.glasses.api.MymavHudPrefs.PanelStyle.entries.firstOrNull { it.name.equals(arg, true) }
                    if (want != null) com.mymav.glasses.api.MymavHudPrefs.savePanelStyle(context.applicationContext, want)
                    Log.i(TAG, "DBGPREFS panelStyle=${com.mymav.glasses.api.MymavHudPrefs.panelStyle()}")
                }
                // Read-only: persisted (disk) values vs the cached/applied ones. Changes nothing.
                "rawprefs" -> {
                    val ctx = context.applicationContext
                    val sp = ctx.getSharedPreferences("mymav_hud", Context.MODE_PRIVATE)
                    val file = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/mymav_hud.xml")
                    val h = com.mymav.glasses.api.MymavHudPrefs
                    Log.i(
                        TAG,
                        "DBGPREFS RAW pid=${android.os.Process.myPid()} file.mtime=${java.util.Date(file.lastModified())} " +
                            "persisted=${sp.all.toSortedMap()} | cached font=${h.fontStyle()} size=${h.textSize()} " +
                            "hshadow=${com.mymav.glasses.mav2.hud.HeadingShadowTrial.enabled} " +
                            "| applied fontsReady=${com.mymav.glasses.mav2.hud.HudFonts.ready()} fontEpoch=${com.mymav.glasses.mav2.hud.HudFonts.epoch}",
                    )
                }
                // Test-only: jump straight into an app by its id (notify, fitness, tasks, shopping, ...).
                "open" -> main.post { GlassesHomeController.openAppViaAvatar(arg) }
                // Real Food / Shop / Identify capture path (phone camera, then vision). Does not inject samples.
                "appscan" -> main.post {
                    val id = when (arg.trim().lowercase()) {
                        "shopping", "shop" -> "shop"
                        "objects", "identify" -> "identify"
                        else -> "food"
                    }
                    GlassesHomeController.openAppViaAvatar(id)
                    main.postDelayed({
                        when (id) {
                            "food" -> com.mymav.miniapps.food.FoodScanEngine.scanFromGlasses(session)
                            "shop" -> com.mymav.miniapps.shop.ShopScanEngine.scanFromGlasses(session)
                            else -> com.mymav.miniapps.identify.IdentifyEngine.scanFromGlasses(session)
                        }
                        Log.i(TAG, "DBGLINK appscan $id")
                    }, 1800L)
                }
                "shopfail" -> com.mymav.glasses.api.MymavShopOffers.debugFailNext = true
                "sample" -> {
                    val f = java.io.File(context.getExternalFilesDir(null), arg)
                    com.mymav.glasses.api.MymavCapture.debugSample =
                        f.takeIf { it.isFile }?.readBytes()?.let { com.mymav.glasses.api.MymavVision.Frame(it, "Test input · sample image") }
                    Log.i(TAG, "DBGSAMPLE ${f.name} loaded=${com.mymav.glasses.api.MymavCapture.debugSample != null}")
                }
                "gt" -> {
                    com.mymav.glasses.api.MymavHudPrefs.saveDefaultTint(context.applicationContext, arg.ifBlank { "neutral" })
                    Log.i(TAG, "DBGPREFS tintDefault=${com.mymav.glasses.api.MymavHudPrefs.defaultTintKey()} strength=${com.mymav.glasses.api.MymavHudPrefs.tintStrength()}")
                }
                "gts" -> {
                    arg.toIntOrNull()?.let { com.mymav.glasses.api.MymavHudPrefs.saveTintStrength(context.applicationContext, it) }
                    Log.i(TAG, "DBGPREFS tintStrength=${com.mymav.glasses.api.MymavHudPrefs.tintStrength()}")
                }
                // gto <scope>=<key>   e.g. gto widget:fitness=cyan   (key "default" clears the override)
                "gto" -> {
                    val scope = arg.substringBefore('=')
                    val key = arg.substringAfter('=', "default")
                    com.mymav.glasses.api.MymavHudPrefs.saveTintOverride(context.applicationContext, scope, key.takeUnless { it == "default" })
                    Log.i(TAG, "DBGPREFS override $scope=${com.mymav.glasses.api.MymavHudPrefs.tintOverride(scope)} effective=${com.mymav.glasses.api.MymavHudPrefs.effectiveTintKey(scope)}")
                }
                "gtr" -> {
                    com.mymav.glasses.api.MymavHudPrefs.resetColours(context.applicationContext)
                    Log.i(TAG, "DBGPREFS tint reset default=${com.mymav.glasses.api.MymavHudPrefs.defaultTintKey()} overrides=${com.mymav.glasses.api.MymavHudPrefs.hasOverrides()}")
                }
                "gl" -> {
                    arg.toIntOrNull()?.let { com.mymav.glasses.api.MymavHudPrefs.saveGlassOpacity(context.applicationContext, it) }
                    Log.i(TAG, "DBGPREFS glass=${com.mymav.glasses.api.MymavHudPrefs.glassOpacityPercent()}")
                }
                "fstyle" -> {
                    val want = com.mymav.glasses.api.MymavHudPrefs.FontStyle.entries.firstOrNull { it.name.equals(arg, true) }
                    if (want != null) com.mymav.glasses.api.MymavHudPrefs.save(context.applicationContext, font = want)
                    Log.i(TAG, "DBGPREFS set font=$want -> now font=${com.mymav.glasses.api.MymavHudPrefs.fontStyle()}")
                }
                "fsize" -> {
                    val want = com.mymav.glasses.api.MymavHudPrefs.TextSize.entries.firstOrNull { it.name.equals(arg, true) }
                    if (want != null) com.mymav.glasses.api.MymavHudPrefs.save(context.applicationContext, size = want)
                    Log.i(TAG, "DBGPREFS set size=$want -> now size=${com.mymav.glasses.api.MymavHudPrefs.textSize()}")
                }
                // Genuine link loss: tear the glasses link down, then bring it back without restarting the app.
                "drop" -> { Log.i(TAG, "DBGLINK drop"); session.disconnect() }
                "rejoin" -> { Log.i(TAG, "DBGLINK rejoin"); session.connectSimulator() }
                // Test-only: push a text-style HUD (PremiumTextHudScreen). arg = memory | money | mymind | lines
                "texthud" -> main.post {
                    val hud: com.mymav.glasses.api.GlassesHud = when (arg) {
                        "money" -> com.mymav.glasses.api.GlassesHud.MoneyQuote("€12.50", "£10.78", "1 EUR = 0.862 GBP")
                        "mymind" -> com.mymav.glasses.api.GlassesHud.MymindBoard(
                            memorySummary = "Met Sam about the demo", peopleLines = listOf("Sam - design lead"), taskLines = listOf("Send build notes", "Book flights"))
                        "lines" -> com.mymav.glasses.api.GlassesHud.TextLines("test-lines", "Text lines", listOf("L40 aaaaaaaaaa bbbbbbbbbb cccccccccc ddd", "L52 aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeee", "L64 aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ff", "L80 aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd eeeeeeeeee ffffffffff gggg", "wide WWWWWWWWWWWWWWWWWWWWWWWWWWWWWW"))
                        else -> com.mymav.glasses.api.GlassesHud.MemoryTimeline(entries = listOf("09:12  Keys - hallway shelf", "12:40  Wallet - kitchen counter"), statusLine = "Live - 2 items today")
                    }
                    session.replaceHud(hud)
                }
                // Test-only: push a sample HUD model (see DebugHudFixtures), e.g. `fixture notif-media`.
                "fixture" -> main.post { DebugHudFixtures.build(arg)?.let { session.replaceHud(it) } ?: Log.w(TAG, "unknown fixture $arg") }
                // Test-only phone UI driver: `pshot <tag>`, `ptap x,y`, `pswipe x1,y1,x2,y2[,ms]`, `psize`.
                "pshot" -> main.post { DebugPhoneDriver.shot(arg.ifBlank { "shot" }) }
                "pwake" -> main.post { DebugPhoneDriver.wake() }
                "pback" -> DebugPhoneDriver.back()
                "psize" -> Log.i(TAG, "DBGPHONE window=${DebugPhoneDriver.windowSize()}")
                "ptap" -> arg.split(',').mapNotNull { it.toFloatOrNull() }.let { p -> if (p.size >= 2) DebugPhoneDriver.gesture(p[0], p[1], p[0], p[1], 80L) }
                "pswipe" -> arg.split(',').mapNotNull { it.toFloatOrNull() }.let { p ->
                    if (p.size >= 4) DebugPhoneDriver.gesture(p[0], p[1], p[2], p[3], p.getOrNull(4)?.toLong() ?: 400L)
                }
                // Test-only, in-memory only (never saved): `order id1,id2,...` reorders the Home catalog to prove colours
                // follow the widget id; `order` alone logs the current order; the persisted order is untouched.
                "order" -> main.post {
                    val cat = com.mymav.glasses.api.HomeBoardCatalog
                    if (arg.isNotBlank()) { cat.setOrder(arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }); GlassesHomeController.showHome() }
                    Log.i(TAG, "DBGPREFS order=" + cat.currentOrderIds().joinToString(","))
                }
                // Test-only, in-memory only: `wsize fitness=wide` — never writes MiniAppOrderStore.
                "wsize" -> main.post {
                    val id = arg.substringBefore('=').trim()
                    val sz = com.mymav.glasses.api.HomeWidgetSize.fromKey(arg.substringAfter('=', "small"))
                    if (id.isNotBlank()) {
                        com.mymav.glasses.api.HomeBoardCatalog.setSize(id, sz)
                        GlassesHomeController.showHome()
                    }
                    Log.i(TAG, "DBGPREFS wsize $id=${com.mymav.glasses.api.HomeBoardCatalog.sizeOf(id)}")
                }
                "popen" -> main.post {
                    DebugPhoneDriver.openMini(arg.ifBlank { "fitness" })
                    Log.i(TAG, "DBGPHONE popen ${arg.ifBlank { "fitness" }}")
                }
                // Mascot test hooks: "avatar" (log prefs) / "avatar Orbit" (switch preset), "atrace on|off" (motion trace).
                "avatar" -> main.post {
                    val ctx = context.applicationContext
                    val cur = com.mymav.glasses.api.MymavAvatarPrefs.preset(ctx)
                    val accent = com.mymav.glasses.api.MymavAvatarPrefs.accentArgb(ctx)
                    val want = com.mymav.glasses.api.MymavAvatarPrefs.Preset.entries.firstOrNull { it.name.equals(arg, true) }
                    if (want != null) {
                        com.mymav.glasses.api.MymavAvatarPrefs.save(ctx, want, accent)
                        GlassesHomeController.invalidateAvatarCache()
                        GlassesHomeController.revealAvatar()
                        GlassesHomeController.refreshHomeIfShowing()
                    }
                    Log.i(TAG, "DBGAVATAR prefs before=${cur.name} now=${com.mymav.glasses.api.MymavAvatarPrefs.preset(ctx).name} accent=${Integer.toHexString(accent)} mode=${com.mymav.glasses.api.MymavAvatarPrefs.mode(ctx)} production=${com.mymav.glasses.api.MymavAvatarPrefs.Preset.entries.associate { it.name to com.mymav.glasses.api.MymavAvatarArt.hasProductionSprites(it) }}")
                }
                // Gaze on the docked mascot for ~1.4 s through the real dwell path (feedHomeGaze), and a typed question
                // submitted exactly as the keyboard's Done does (handleAssistantUtterance).
                "gavatar" -> main.post { GlassesHomeController.simulateAvatarGazeDwell() }
                "ask" -> main.post { GlassesHomeController.handleAssistantUtterance(arg.replace('_', ' ')) }
                "nudge" -> main.post { MyMavProactive.deliver(arg, true) }
                "acancel" -> GlassesHomeController.cancelAssistant()
                "navsim" -> arg.split(',').mapNotNull { it.toDoubleOrNull() }.let { p -> if (p.size >= 2) com.mymav.glasses.api.MymavNav.simulateFix(p[0], p[1], p.getOrNull(2)?.toFloat() ?: Float.NaN) }
                "navview" -> com.mymav.glasses.api.MymavNav.setView(com.mymav.glasses.api.MymavNav.View.values().firstOrNull { it.name.equals(arg, true) } ?: com.mymav.glasses.api.MymavNav.View.Map)
                "navtravel" -> com.mymav.glasses.api.MymavNav.setTravel(com.mymav.glasses.api.MymavNav.Travel.values().firstOrNull { it.name.equals(arg, true) } ?: com.mymav.glasses.api.MymavNav.Travel.Walk)
                "navdim" -> com.mymav.glasses.api.MymavNav.setDim(if (arg.equals("2d", true) || arg.equals("d2", true)) com.mymav.glasses.api.MymavNav.Dim.D2 else com.mymav.glasses.api.MymavNav.Dim.D3)
                "navnorth" -> com.mymav.glasses.api.MymavNav.setNorthUp(arg != "off")
                "navmute" -> com.mymav.glasses.api.MymavNav.setMuted(arg != "off")
                "navrepeat" -> com.mymav.glasses.api.MymavNav.repeatInstruction()
                "navalt" -> com.mymav.glasses.api.MymavNav.nextAlternative()
                "navunits" -> com.mymav.glasses.api.MymavNav.toggleUnits()
                "navopen" -> main.post { com.mymav.glasses.api.GlassesHomeController.handleAssistantUtterance("open navigation") }
                "navsize" -> {
                    val sz = com.mymav.glasses.api.HomeWidgetSize.fromKey(arg)
                    com.mymav.glasses.api.MiniAppOrderStore(com.mymav.glasses.api.MymavNav.contextOrNull()!!).saveSize("nav", sz)
                    com.mymav.glasses.api.GlassesHomeController.refreshHomeIfShowing()
                }
                "navwidget" -> {
                    val c = com.mymav.glasses.api.MymavNav.contextOrNull()!!
                    val ord = com.mymav.glasses.api.MiniAppOrderStore(c)
                    val p = com.mymav.glasses.api.NavWidget.preview()
                    Log.i("DBGWIDGET", "order=${ord.loadOrder().joinToString(",")} catalog=${com.mymav.glasses.api.HomeBoardCatalog.currentOrderIds().joinToString(",")} navSize=${com.mymav.glasses.api.HomeBoardCatalog.sizeOf("nav")} savedSizes=${ord.loadSizes()["nav"]} state=${p.state} title=${p.title} sub=${p.subtitle} metric=${p.metric} detail=${p.metricDetail} lines=${p.lines} scope=${com.mymav.glasses.api.MymavHudPrefs.widgetScope("nav")} page=${com.mymav.glasses.api.HomeBoardCatalog.pageIndexOf("nav")}")
                    val s = com.mymav.glasses.api.MymavNav.state.value
                    Log.i("DBGWIDGET", "navstate=${s?.mode} dest=${s?.destName} toNext=${s?.toNextM?.toInt()} remaining=${s?.remainingM?.toInt()} speedMps=${s?.speedMps} speedText=${s?.let { com.mymav.glasses.api.MymavNav.speedText(it.speedMps, it.mph) }} travel=${s?.travel} gps=${s?.gps} eta=${s?.etaMs}")
                }
                "navlost" -> com.mymav.glasses.api.MymavNav.debugLost(arg != "off")
                "navreroute" -> com.mymav.glasses.api.MymavNav.debugReroute(arg != "off")
                "navhalt" -> com.mymav.glasses.api.MymavNav.haltWalker()
                "navplan" -> com.mymav.glasses.api.MymavNav.setPlanning(if (arg == "off") null else "Covent Garden, London")
                "navhome" -> com.mymav.glasses.api.GlassesHomeController.requestHomeFromApp()
                "navshow" -> com.mymav.glasses.api.GlassesHomeController.reopenNav()
                "navstop" -> com.mymav.glasses.api.MymavNav.stop(quiet = true)
                "navacc" -> arg.split(',').mapNotNull { it.toDoubleOrNull() }.let { p -> if (p.size >= 3) com.mymav.glasses.api.MymavNav.simulateFix(p[0], p[1], Float.NaN, p[2].toFloat()) }
                "navwalk" -> com.mymav.glasses.api.MymavNav.simulateWalk(arg.toDoubleOrNull() ?: 12.0)
                "navclear" -> com.mymav.glasses.api.MymavNav.clearSimulation()
                "cleandump" -> DebugCleanup.dump(context.applicationContext)
                "cleandel" -> DebugCleanup.delete(context.applicationContext, arg)
                "camprobe" -> com.mymav.glasses.mav2.Mav2CameraProbe.run()
                "ptype" -> DebugPhoneDriver.type(arg.replace('_', ' '))
                "pkey" -> DebugPhoneDriver.key(arg.toIntOrNull() ?: 66)
                "gemdiag" -> Thread { Log.i(TAG, "DBGLINK gemdiag " + com.mymav.glasses.api.MymavAi.diagnoseGemini()) }.start()
                "aimodels" -> Thread {
                    val listed = com.mymav.glasses.api.MymavAi.listAccountModels()
                    Log.i(TAG, "DBGLINK aimodels $listed")
                    com.mymav.glasses.api.AssistantLog.add(
                        "Account model list",
                        listed,
                        com.mymav.glasses.api.AssistantLog.Kind.Local,
                        note = "Names only. Keys never logged.",
                    )
                }.start()
                "aibench" -> Thread {
                    val out = com.mymav.glasses.api.AiBench.run(arg.ifBlank { "current" })
                    Log.i(TAG, "DBGLINK aibench $out")
                    com.mymav.glasses.api.AssistantLog.add(
                        "AI bench ${arg.ifBlank { "current" }}",
                        out,
                        com.mymav.glasses.api.AssistantLog.Kind.Local,
                        note = "Names and timings only.",
                    )
                }.start()
                // Persist provider / preset only. Never writes keys or Current saved model IDs.
                "aiprovider" -> {
                    val raw = arg.trim()
                    val want = com.mymav.glasses.api.MymavAi.Provider.entries.firstOrNull { it.name.equals(raw, true) }
                        ?: when (raw.lowercase()) {
                            "auto" -> com.mymav.glasses.api.MymavAi.Provider.Auto
                            "chatgpt", "openai" -> com.mymav.glasses.api.MymavAi.Provider.ChatGpt
                            "gemini" -> com.mymav.glasses.api.MymavAi.Provider.Gemini
                            "off" -> com.mymav.glasses.api.MymavAi.Provider.Off
                            else -> null
                        }
                    if (want != null) com.mymav.glasses.api.MymavAi.save(provider = want)
                    Log.i(TAG, "DBGLINK aiprovider now=${com.mymav.glasses.api.MymavAi.provider().name} arg=$arg")
                }
                "aipreset" -> {
                    val want = com.mymav.glasses.api.AiPreset.fromId(arg.ifBlank { "fast" })
                    com.mymav.glasses.api.MymavAi.save(preset = want)
                    Log.i(
                        TAG,
                        "DBGLINK aipreset now=${com.mymav.glasses.api.MymavAi.preset().id} " +
                            "savedOpenAi=${com.mymav.glasses.api.MymavAi.savedOpenAiModel()} " +
                            "savedGemini=${com.mymav.glasses.api.MymavAi.savedGeminiModel()}",
                    )
                }
                "bright" -> Log.i(TAG, "DBGLINK brightness before=" + com.mymav.glasses.mav2.Mav2Brightness.get() + " set(" + arg + ")=" + com.mymav.glasses.mav2.Mav2Brightness.set(arg.toIntOrNull() ?: 50) + " after=" + com.mymav.glasses.mav2.Mav2Brightness.get())
                "atrace" -> GlassesHomeController.setAvatarTrace(arg != "off")
                "dispoff" -> { Log.i(TAG, "DBGLINK display standby requested"); com.mymav.glasses.mav2.Mav2DebugHooks.displayOff() }
                "dispstate" -> Log.i(TAG, "DBGLINK isDisplayOn=${com.mymav.glasses.mav2.Mav2DebugHooks.displayOn()}")
                "gnext" -> main.post { GlassesHomeController.simulateSideGazeDwell(next = true) }
                "gprev" -> main.post { // TEMPFONT
                    val all = com.mymav.glasses.api.MymavHudPrefs.FontStyle.entries
                    val nxt = all[(all.indexOf(com.mymav.glasses.api.MymavHudPrefs.fontStyle()) + 1) % all.size]
                    com.mymav.glasses.api.MymavHudPrefs.save(context.applicationContext, font = nxt)
                    Log.i(TAG, "TEMPFONT now $nxt")
                }
                "gopen" -> main.post { GlassesHomeController.simulateOpenGazeDwell() }
                // Feeds a final STT result into a live Conversate session (`--es arg "sentence"`).
                // Freeze an action-row gaze bar for a screenshot: `--es arg f:1,0.6`.
                "gfreeze" -> main.post {
                    val p = arg.split(',')
                    GlassesHomeController.debugFreezeActionProgress(p[0], p.getOrNull(1)?.toFloatOrNull() ?: 0.6f)
                }
                "hshadow" -> {
                    com.mymav.glasses.mav2.hud.HeadingShadowTrial.setEnabled(!arg.equals("off", true))
                    Log.i(TAG, "headingShadow=${com.mymav.glasses.mav2.hud.HeadingShadowTrial.enabled}")
                    GlassesHomeController.refreshSurfaceIfShowing()
                }
                "cvsay" -> com.mymav.miniapps.conversate.ConversateSessionHolder.debugTranscript(arg)
                // Simulated gaze hold on the app surface: `--es arg x,y,ms` (normalised x,y).
                "gaze" -> main.post {
                    val p = arg.split(',')
                    val nx = p.getOrNull(0)?.toFloatOrNull()
                    val ny = p.getOrNull(1)?.toFloatOrNull()
                    val ms = p.getOrNull(2)?.toLongOrNull() ?: 1_500L
                    if (nx != null && ny != null) GlassesHomeController.simulateAppGaze(nx, ny, ms)
                }
                "gcancel" -> main.post { GlassesHomeController.simulateAskChoiceGazeDwell("cancel") }
                "gaudio" -> main.post { GlassesHomeController.simulateAskChoiceGazeDwell("audio") }
                "gtext" -> main.post { GlassesHomeController.simulateAskChoiceGazeDwell("text") }
                "page" -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.showHome()
                        GlassesHomeController.dockAvatarQuiet()
                        // Gaze-equivalent page turn (tests single-mascot page run).
                        main.postDelayed({
                            GlassesHomeController.simulateSideGazeDwell(next = true)
                        }, 600L)
                    }, 800L)
                }
                else -> {
                    session.connectSimulator()
                    main.postDelayed({
                        GlassesHomeController.dockAvatarQuiet()
                        GlassesHomeController.showHome()
                        // Second push clears stale “waiting for draw”.
                        main.postDelayed({
                            GlassesHomeController.dockAvatarQuiet()
                            GlassesHomeController.showHome()
                        }, 800L)
                    }, 1200L)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DebugHudReceiver"
        private val TEST_ONLY_CMDS = setOf("gfreeze", "cvsay", "hshadow", "cache", "prefs", "fstyle", "fsize", "drop", "rejoin", "gprev", "gaze", "gl", "gs", "rawprefs", "open", "gt", "gts", "gto", "gtr", "avatar", "atrace", "gavatar", "ask", "nudge", "acancel", "navsim", "navwalk", "navview", "navtravel", "navmute", "navreroute", "navdim", "navnorth", "navrepeat", "navalt", "navunits", "navhome", "navhalt", "navplan", "navlost", "navopen", "navsize", "navwidget", "navshow", "navstop", "navacc", "navclear", "cleandump", "cleandel", "camprobe", "ptype", "pkey", "gemdiag", "aimodels", "aibench", "aipreset", "aiprovider", "appscan", "bright", "dispoff", "dispstate", "texthud", "fixture", "order", "wsize", "popen", "pshot", "pback", "pwake", "psize", "ptap", "pswipe")
        @Volatile private var demoIndex = 0
        private val DEMO_ASKS = listOf(
            "Open shopping list",
            "Open recipe book",
            "Open notepad",
        )
    }
}
