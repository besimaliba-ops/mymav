package com.mymav.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.GlassesSession
import com.mymav.glasses.api.MiniAppOrderStore
import com.mymav.glasses.api.MiniAppRegistry
import com.mymav.glasses.api.MymavAvatarArt
import com.mymav.glasses.api.MymavAvatarPrefs
import com.mymav.glasses.api.SimulatorPrefs
import com.mymav.miniapps.conversate.ConversateMiniScreen
import com.mymav.miniapps.fitness.FitnessMiniApp
import com.mymav.miniapps.fitness.FitnessMiniScreen
import com.mymav.miniapps.food.FoodMiniScreen
import com.mymav.miniapps.football.FootballMiniScreen
import com.mymav.miniapps.gallery.GalleryMiniScreen
import com.mymav.miniapps.identify.IdentifyMiniScreen
import com.mymav.miniapps.memorysight.MemorySightMiniScreen
import com.mymav.miniapps.mirror.MirrorMiniScreen
import com.mymav.miniapps.money.MoneyMiniScreen
import com.mymav.miniapps.mymind.MymindMiniScreen
import com.mymav.miniapps.notify.NotifyScreen
import com.mymav.miniapps.shop.ShopMiniScreen
import com.mymav.miniapps.spotify.SpotifyScreen
import com.mymav.miniapps.tasks.TasksMiniScreen
import com.mymav.miniapps.saved.NotepadMiniScreen
import com.mymav.miniapps.saved.RecipeBookMiniScreen
import com.mymav.miniapps.saved.ShoppingMiniScreen
import com.mymav.miniapps.workouts.WorkoutsMiniScreen

private enum class MainTab(val route: String, val label: String) {
    Glasses("glasses", "Glasses"),
    Apps("apps", "Apps"),
    Settings("settings", "Settings"),
}

@Composable
fun MyMavRoot(session: GlassesSession) {
    val nav = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val orderStore = remember { MiniAppOrderStore(context).also { it.applyToCatalog() } }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val inMini = currentRoute?.startsWith("mini/") == true

    // Authoritative tab content source — never rely on NavHost restore for main tabs
    // (that was sticky on Glasses while the pill highlight flipped to Apps/Settings).
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Glasses.route) }

    fun openMini(id: String) {
        if (id == "fitness") {
            FitnessMiniApp.attachContext(context)
        }
        GlassesHomeController.markMiniAppOpen(id)
        MiniAppRegistry.get(id)?.onEnter(session)
        nav.navigate("mini/$id") {
            launchSingleTop = true
        }
    }
    androidx.compose.runtime.DisposableEffect(session) {
        com.mymav.app.DebugPhoneDriver.openMiniHandler = { id -> openMini(id) }
        onDispose { com.mymav.app.DebugPhoneDriver.openMiniHandler = null }
    }

    fun popToShell() {
        runCatching { nav.popBackStack(route = "shell", inclusive = false) }
        // Belt and braces: if anything is still stacked above the anchor, wipe it.
        if (nav.currentBackStackEntry?.destination?.route != "shell") {
            runCatching {
                nav.navigate("shell") { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true }
            }
        }
    }

    fun leaveMini(id: String) {
        // Phone-only return to Glasses tab — do NOT call requestHomeFromApp().
        // Glasses mini-app HUD stays open until HOME pill / long-tap on glasses.
        //
        // Pop everything back down to the "shell" anchor in one call rather than
        // popping one-at-a-time while the top route's name starts with "mini/":
        // that loop's starting condition was read off the current route *before*
        // any pop happened, so a mini-app that had pushed its own sub-screen
        // (a differently-named route, or more than one level deep) could leave
        // the loop seeing a route that didn't match "mini/" and never pop at
        // all — "Back to home" stayed on the mini app. popBackStack(route, ...)
        // clears down to the anchor regardless of what's stacked above it.
        popToShell()
        selectedTab = MainTab.Apps.route
    }

    DisposableEffect(session) {
        GlassesHomeController.attach(
            session = session,
            onOpenMiniApp = { id -> openMini(id) },
            onReturnHome = { popToShell() },
        )
        onDispose { /* keep controller for Notify / Spotify services */ }
    }

    // System back from any mini-app returns to the Apps page too.
    androidx.activity.compose.BackHandler(enabled = inMini) { popToShell(); selectedTab = MainTab.Apps.route }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!inMini) {
                MyMavBottomBar(
                    selectedRoute = selectedTab,
                    onSelect = { tab ->
                        selectedTab = tab.route
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!inMini) {
                when (selectedTab) {
                    MainTab.Apps.route -> AppsScreen(
                        orderStore = orderStore,
                        contentPadding = padding,
                        onOpenMini = { id -> openMini(id) },
                        onOrderChanged = {
                            GlassesHomeController.refreshHomeIfShowing()
                        },
                    )
                    MainTab.Settings.route -> SettingsScreen(
                        session = session,
                        contentPadding = padding,
                    )
                    else -> GlassesScreen(
                        session = session,
                        contentPadding = padding,
                        onOpenMini = { id -> openMini(id) },
                    )
                }
            }
            NavPhoneOverlay(padding.calculateBottomPadding())
            // Mini-app routes only — main tabs are selectedTab-driven above.
            // Mini-app screens are light-designed: pin their theme so a dark-mode
            // phone (or the dark shell theme) cannot turn their text unreadable.
            MyMavMiniTheme {
            NavHost(
                navController = nav,
                startDestination = "shell",
                modifier = if (inMini) {
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(0.dp)
                },
            ) {
                composable("shell") { /* anchor for popping mini routes */ }
                composable("mini/notify") {
                    NotifyScreen(session) { leaveMini("notify") }
                }
                composable("mini/spotify") {
                    SpotifyScreen(session) { leaveMini("spotify") }
                }
                composable("mini/conversate") {
                    ConversateMiniScreen(session) { leaveMini("conversate") }
                }
                composable("mini/food") {
                    FoodMiniScreen(session) { leaveMini("food") }
                }
                composable("mini/shop") {
                    ShopMiniScreen(session) { leaveMini("shop") }
                }
                composable("mini/identify") {
                    IdentifyMiniScreen(session) { leaveMini("identify") }
                }
                composable("mini/football") {
                    FootballMiniScreen(session) { leaveMini("football") }
                }
                composable("mini/fitness") {
                    FitnessMiniScreen(session) { leaveMini("fitness") }
                }
                composable("mini/workouts") {
                    WorkoutsMiniScreen(session) { leaveMini("workouts") }
                }
                composable("mini/mirror") {
                    MirrorMiniScreen(session) { leaveMini("mirror") }
                }
                composable("mini/gallery") {
                    GalleryMiniScreen(session) { leaveMini("gallery") }
                }
                composable("mini/tasks") {
                    TasksMiniScreen(session) { leaveMini("tasks") }
                }
                composable("mini/recipes") {
                    RecipeBookMiniScreen(session) { leaveMini("recipes") }
                }
                composable("mini/shopping") {
                    ShoppingMiniScreen(session) { leaveMini("shopping") }
                }
                composable("mini/notepad") {
                    NotepadMiniScreen(session) { leaveMini("notepad") }
                }
                composable("mini/memorysight") {
                    MemorySightMiniScreen(session) { leaveMini("memorysight") }
                }
                composable("mini/money") {
                    MoneyMiniScreen(session) { leaveMini("money") }
                }
                composable("mini/nav") {
                    NavMiniScreen { leaveMini("nav") }
                }
                composable("mini/mymind") {
                    MymindMiniScreen(session) { leaveMini("mymind") }
                }
            }
            }
        }
    }
}

@Composable
private fun MyMavBottomBar(
    selectedRoute: String,
    onSelect: (MainTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("mymav_bottom_nav"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .shadow(18.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(50))
                .background(Color(0xF0111821))
                .border(1.dp, MyMavColors.Hairline, RoundedCornerShape(50))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                val selected = selectedRoute == tab.route
                val bg by androidx.compose.animation.animateColorAsState(
                    if (selected) MyMavColors.Accent else Color.Transparent,
                    animationSpec = androidx.compose.animation.core.tween(200),
                    label = "tabBg",
                )
                val fg by androidx.compose.animation.animateColorAsState(
                    if (selected) MyMavColors.OnAccent else MyMavColors.TextMuted,
                    animationSpec = androidx.compose.animation.core.tween(200),
                    label = "tabFg",
                )
                Box(
                    modifier = Modifier
                        .testTag("tab_${tab.route}")
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Tab ${tab.label}"
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = fg,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    session: GlassesSession,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val host by session.simulatorHost.collectAsState()
    val hasKey by session.hasSdkKey.collectAsState()
    val status by session.statusMessage.collectAsState()
    var hostEdit by remember(host) { mutableStateOf(host) }
    var avatarPreset by remember { mutableStateOf(MymavAvatarPrefs.preset(context)) }
    var accentArgb by remember { mutableIntStateOf(MymavAvatarPrefs.accentArgb(context)) }
    var avatarMode by remember { mutableStateOf(MymavAvatarPrefs.mode(context)) }
    val accentChoices = remember {
        listOf(
            0xFF2EE6A6.toInt(),
            0xFF4DD0E1.toInt(),
            0xFFFFB74D.toInt(),
            0xFFFF8A80.toInt(),
            0xFFB39DDB.toInt(),
            0xFFFFF176.toInt(),
        )
    }
    // encodeLauncher → PNG (BitmapFactory); raw RGBA encode() cannot decode on phone UI.
    val avatarPreview = remember(avatarPreset, accentArgb) {
        val enc = MymavAvatarArt.encodeLauncher(avatarPreset, accentArgb, sizePx = 192)
        BitmapFactory.decodeByteArray(enc.bytes, 0, enc.bytes.size)?.asImageBitmap()
    }
    val presetPreviews = remember(accentArgb) {
        MymavAvatarPrefs.Preset.entries.associateWith { preset ->
            val enc = MymavAvatarArt.encodeLauncher(preset, accentArgb, sizePx = 144)
            BitmapFactory.decodeByteArray(enc.bytes, 0, enc.bytes.size)?.asImageBitmap()
        }
    }

    fun refreshAvatar() {
        GlassesHomeController.invalidateAvatarCache()
        GlassesHomeController.revealAvatar()
        GlassesHomeController.refreshHomeIfShowing()
    }

    MyMavShellBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_settings")
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            ScreenHeader("Settings", "MyMav companion")

            var openGroup by rememberSaveable { mutableStateOf("") }
            fun toggle(k: String) { openGroup = if (openGroup == k) "" else k }

            SectionLabel("Glasses", modifier = Modifier.padding(start = 4.dp))
            SettingsGroup("Display", "Brightness", openGroup == "bright", { toggle("bright") }) {
                BrightnessCard()
            }
            SettingsGroup("Avatar", "Character, accent, motion", openGroup == "avatar", { toggle("avatar") }) {
            // ---- avatar ----
            GlassCard {
                CardTitle(
                    "MyMav avatar",
                    "Active: full motion. Sleep: bottom-left, still summons with a 1s look. Off: hidden.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MymavAvatarPrefs.Mode.entries.forEach { mode ->
                        KitChip(
                            mode.label,
                            avatarMode == mode,
                            {
                                avatarMode = mode
                                MymavAvatarPrefs.saveMode(context, mode)
                                GlassesHomeController.setAvatarMode(mode)
                            },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (avatarPreview != null) {
                        Image(
                            bitmap = avatarPreview,
                            contentDescription = "Avatar preview",
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(MyMavColors.Night)
                                .border(2.dp, MyMavColors.Accent, CircleShape),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(avatarPreset.label, color = MyMavColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pick a character and accent", color = MyMavColors.TextMuted, fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MymavAvatarPrefs.Preset.entries.forEach { preset ->
                        val selected = avatarPreset == preset
                        val thumb = presetPreviews[preset]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .width(78.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) Color(0x2245D6F5) else Color(0x0DFFFFFF))
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) MyMavColors.Accent else MyMavColors.Hairline,
                                    shape = RoundedCornerShape(18.dp),
                                )
                                .clickable {
                                    avatarPreset = preset
                                    MymavAvatarPrefs.save(context, preset, accentArgb)
                                    refreshAvatar()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = preset.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(MyMavColors.Night),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color(accentArgb)),
                                )
                            }
                            Text(
                                preset.label,
                                color = if (selected) MyMavColors.Accent else MyMavColors.Text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                SectionLabel("Accent")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    accentChoices.forEach { color ->
                        val selected = accentArgb == color
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = if (selected) Color.White else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    accentArgb = color
                                    MymavAvatarPrefs.save(context, avatarPreset, color)
                                    refreshAvatar()
                                },
                        )
                    }
                }
            }

            }
            SettingsGroup("Text & panels", "Font, size, panel style, colours", openGroup == "look", { toggle("look") }) {
                GlassesTextCard()
                GlassesPanelsCard()
                GlassColoursCard()
            }
            SettingsGroup("Connection", "Simulator host $host", openGroup == "conn", { toggle("conn") }) {
            // ---- simulator / device ----
            GlassCard {
                CardTitle("Glasses connection", "Everysight Glasses Simulator or real Maverick glasses")
                KitTextField(
                    value = hostEdit,
                    onValueChange = { hostEdit = it },
                    label = "Simulator host",
                    placeholder = "10.0.2.2 (emulator) or your PC's IP",
                )
                Text("ws://$hostEdit:8765/ws", color = MyMavColors.TextFaint, fontSize = 12.sp)
                KitButton(
                    "Save & connect simulator",
                    onClick = {
                        session.setSimulatorHost(hostEdit)
                        SimulatorPrefs.saveHost(context, hostEdit)
                        SimulatorPrefs.setAutoConnect(context, true)
                        session.connectSimulator()
                        com.mymav.app.GlassesLinkService.start(context, retryNow = true)
                    },
                    kind = KitButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                var batteryOk by remember { mutableStateOf(com.mymav.app.GlassesLinkService.isIgnoringBatteryOptimizations(context)) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        batteryOk = com.mymav.app.GlassesLinkService.isIgnoringBatteryOptimizations(context)
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                Text(
                    if (batteryOk) "Battery: unrestricted, the link stays up when the phone is locked."
                    else "Battery optimisation is on, so Android may cut the link when the phone is locked.",
                    color = MyMavColors.TextFaint,
                    fontSize = 12.sp,
                )
                if (!batteryOk) {
                    KitButton(
                        "Keep running when locked",
                        onClick = { context.startActivity(com.mymav.app.GlassesLinkService.batteryExemptionIntent(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                KitButtonRow {
                    KitButton("Device / BLE", onClick = { session.connectDevice() }, modifier = Modifier.weight(1f))
                    KitButton("SDK setup", onClick = { session.showConfigureUi() }, modifier = Modifier.weight(1f))
                }
                KitButton(
                    "Disconnect",
                    onClick = {
                        SimulatorPrefs.setAutoConnect(context, false)
                        session.disconnect()
                    },
                    kind = KitButtonKind.Quiet,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            }
            SectionLabel("Assistant", modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            SettingsGroup("Assistant & AI", "Keys, voice, learning, memory, activity", openGroup == "ai", { toggle("ai") }) {
                MyMavAiCard()
                ReadAloudCard()
                LearningCard()
                ProactiveCard()
                MeetingCard()
                MemoryCard()
                AssistantActivityCard()
            }
            SectionLabel("App", modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            SettingsGroup("Navigation", "Map style 2D / 3D, units, travel mode", openGroup == "nav", { toggle("nav") }) {
                NavSettingsCard()
            }
            SettingsGroup("Permissions", "Calendar, notifications, messaging, location", openGroup == "perm", { toggle("perm") }) {
                MyMavPermissionsCard()
            }
            SettingsGroup("Help & diagnostics", status.ifBlank { "Backend " + session.backendName }, openGroup == "help", { toggle("help") }) {
            // ---- tips ----
            GlassCard {
                CardTitle("On the glasses")
                Text(
                    "Look at HOME for 1s (or long-tap) to return to the home board. " +
                        "Look at the left or right edge for 1s to turn pages. Tap opens the focused app. " +
                        "App order and widget sizes come from the Apps tab.",
                    color = MyMavColors.TextMuted,
                    fontSize = 13.sp,
                )
            }

            GlassCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                SectionLabel("Diagnostics")
                MetaLine("Backend", session.backendName)
                MetaLine("SDK key", if (hasKey) "Present" else "Missing (assets/sdk.key)")
                MetaLine("Status", status)
            }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


/** Settings → Calendar (daily briefing) and notifications (timers). */
@Composable
private fun MyMavPermissionsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cal by remember { mutableStateOf(com.mymav.app.MyMavCalendar.granted(context) && com.mymav.app.MyMavCalendar.canWrite(context)) }
    var notif by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    fun msgOk() = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var loc by remember { mutableStateOf(com.mymav.glasses.api.MymavNav.hasPermission()) }
    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { loc = com.mymav.glasses.api.MymavNav.hasPermission() }
    var msg by remember { mutableStateOf(msgOk()) }
    val msgLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { msg = msgOk() }
    val calLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { cal = com.mymav.app.MyMavCalendar.granted(context) && com.mymav.app.MyMavCalendar.canWrite(context) }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notif = it }
    GlassCard {
        CardTitle("Briefing & timers", "Calendar events appear in your daily briefing; notifications let timers ring on this phone.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !cal,
                onClick = { calLauncher.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) },
            ) { Text(if (cal) "Calendar allowed" else "Allow calendar") }
            OutlinedButton(
                enabled = !notif,
                onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            ) { Text(if (notif) "Notifications allowed" else "Allow notifications") }
        }
        OutlinedButton(
            enabled = !msg,
            onClick = { msgLauncher.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.SEND_SMS)) },
        ) { Text(if (msg) "Messaging allowed" else "Allow messaging (contacts + SMS)") }
        OutlinedButton(
            enabled = !loc,
            onClick = { locLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) },
        ) { Text(if (loc) "Location allowed" else "Allow location (navigation)") }
    }
}

/** Settings → Glasses text: font style + size for everything drawn on the glasses. */
@Composable
private fun MyMavAiCard() {
    val ai = com.mymav.glasses.api.MymavAi
    var provider by remember { mutableStateOf(ai.provider()) }
    var preset by remember { mutableStateOf(ai.preset()) }
    var openAiKey by remember { mutableStateOf(ai.openAiKey()) }
    var geminiKey by remember { mutableStateOf(ai.geminiKey()) }
    var openAiModel by remember { mutableStateOf(ai.savedOpenAiModel()) }
    var geminiModel by remember { mutableStateOf(ai.savedGeminiModel()) }
    var serpApiKey by remember { mutableStateOf(com.mymav.glasses.api.MymavShopOffers.serpApiKey()) }
    var status by remember { mutableStateOf("") }
    fun persist() = ai.save(provider, openAiKey, geminiKey, openAiModel, geminiModel, preset)
    val savedOpenAi = openAiModel.ifBlank { com.mymav.glasses.api.MymavAi.DEFAULT_OPENAI_MODEL }
    val savedGemini = geminiModel.ifBlank { com.mymav.glasses.api.MymavAi.DEFAULT_GEMINI_MODEL }
    GlassCard {
        CardTitle(
            "MyMav AI",
            "Use your own ChatGPT and/or Gemini API key for the glasses avatar's answers. Keys stay on this phone.",
        )
        SectionLabel("Provider")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.mymav.glasses.api.MymavAi.Provider.entries.forEach { option ->
                KitChip(option.label, provider == option, { provider = option; persist() })
            }
        }
        SectionLabel("Model preset")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.mymav.glasses.api.AiPreset.entries.forEach { option ->
                KitChip(
                    option.label,
                    preset == option,
                    {
                        preset = option
                        persist()
                    },
                    modifier = Modifier.testTag("ai-preset-${option.id}"),
                )
            }
        }
        Text(
            "${preset.summary} Active IDs: ${preset.idsLine(savedOpenAi, savedGemini)}.",
            color = MyMavColors.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.testTag("ai-preset-active-ids"),
        )
        if (preset != com.mymav.glasses.api.AiPreset.Current) {
            OutlinedButton(
                onClick = {
                    preset = com.mymav.glasses.api.AiPreset.Current
                    persist()
                },
                modifier = Modifier.testTag("ai-preset-back-to-current"),
            ) { Text("Back to Current") }
            Text(
                "Returns to the saved fallback ChatGPT $savedOpenAi · Gemini $savedGemini. Trying Fast does not overwrite those fields.",
                color = MyMavColors.TextMuted,
                fontSize = 12.sp,
            )
        }
        Text(
            "Fast is the everyday trial for Ask and Conversate cues. Current stays the saved fallback " +
                "(ChatGPT $savedOpenAi · Gemini $savedGemini) — use Back to Current any time. " +
                "Balanced and Advanced stay available for harder questions and summaries " +
                "(GPT-6 Luna/Sol/Astra, Gemini 3.5-lite / 3.8 Flash / 4 Argon). " +
                "On glasses, say \"use advanced:\" before a hard question to use Advanced IDs for that turn only. " +
                "A missing model or quota is shown — Ask will not silently swap to a different product class. " +
                "ChatGPT-only / Gemini-only / Auto is respected on Ask, cues, vision, and search.",
            color = MyMavColors.TextMuted,
            fontSize = 12.sp,
        )
        SectionLabel("ChatGPT (OpenAI) API key")
        OutlinedTextField(
            value = openAiKey,
            onValueChange = { openAiKey = it; persist() },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = openAiModel,
            onValueChange = {
                openAiModel = it
                if (preset != com.mymav.glasses.api.AiPreset.Current) preset = com.mymav.glasses.api.AiPreset.Current
                persist()
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ChatGPT model (Current preset)") },
        )
        SectionLabel("Gemini API key")
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it; persist() },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AIza...") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = geminiModel,
            onValueChange = {
                geminiModel = it
                if (preset != com.mymav.glasses.api.AiPreset.Current) preset = com.mymav.glasses.api.AiPreset.Current
                persist()
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini model (Current preset)") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                persist(); status = "Testing ChatGPT…"
                ai.test(com.mymav.glasses.api.MymavAi.Provider.ChatGpt) { status = it?.let { e -> "ChatGPT: $e" } ?: "ChatGPT works" }
            }) { Text("Test ChatGPT") }
            OutlinedButton(onClick = {
                persist(); status = "Testing Gemini…"
                ai.test(com.mymav.glasses.api.MymavAi.Provider.Gemini) { status = it?.let { e -> "Gemini: $e" } ?: "Gemini works" }
            }) { Text("Test Gemini") }
        }
        if (status.isNotEmpty()) Text(status, color = MyMavColors.TextMuted, fontSize = 13.sp)
        SectionLabel("Shopping prices (SerpAPI)")
        Text(
            "Required for live Shop / Identify → Find prices offers. Create a key at serpapi.com. " +
                "Without it, product ID still works but MyMav will say SerpAPI setup is needed — " +
                "never invents store prices from AI memory.",
            color = MyMavColors.TextMuted, fontSize = 12.sp,
        )
        OutlinedTextField(
            value = serpApiKey,
            onValueChange = {
                serpApiKey = it
                com.mymav.glasses.api.MymavShopOffers.saveSerpApiKey(it)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("SerpAPI key") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        Text(
            "Ask the avatar anything: gaze at it, or say \"hey MyMav\". Short answers show in the speech bubble.",
            color = MyMavColors.TextMuted, fontSize = 12.sp,
        )
    }
}

/** Settings -> Glasses panels: how visible the glass panels are; text and icons always stay full brightness in front. */
@Composable
private fun GlassesPanelsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.mymav.glasses.api.MymavHudPrefs
    var percent by remember { mutableStateOf(prefs.glassOpacityPercent().toFloat()) }
    var panelStyle by remember { mutableStateOf(prefs.panelStyle()) }
    GlassCard {
        CardTitle(
            "Glasses panels",
            "Choose the glass style and how visible the panels are on the glasses. Text and icons are drawn in front of the panels at full brightness whatever you choose.",
        )
        SectionLabel("Panel style")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.mymav.glasses.api.MymavHudPrefs.PanelStyle.entries.forEach { option ->
                KitChip(option.label, panelStyle == option, { panelStyle = option; prefs.savePanelStyle(context, option) })
                Text(option.detail, color = MyMavColors.TextMuted, fontSize = 12.sp)
            }
        }
        SectionLabel("Panel opacity: ${percent.toInt()}%")
        androidx.compose.material3.Slider(
            value = percent,
            onValueChange = { percent = (Math.round(it / 10f) * 10f) },
            onValueChangeFinished = { prefs.saveGlassOpacity(context, percent.toInt()) },
            valueRange = prefs.GLASS_MIN.toFloat()..prefs.GLASS_MAX.toFloat(),
        )
        Text("100% is the designed look. Lower lets more scenery through; higher makes panels stand out more.", color = MyMavColors.TextMuted, fontSize = 12.sp)
        OutlinedButton(onClick = { percent = 100f; prefs.saveGlassOpacity(context, 100) }) { Text("Reset to 100%") }
    }
}

@Composable
private fun GlassesTextCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var style by remember { mutableStateOf(com.mymav.glasses.api.MymavHudPrefs.fontStyle()) }
    var size by remember { mutableStateOf(com.mymav.glasses.api.MymavHudPrefs.textSize()) }
    GlassCard {
        CardTitle(
            "Glasses text",
            "Font used on the glasses display. Applies instantly when connected.",
        )
        com.mymav.glasses.api.MymavHudPrefs.FontStyle.entries.forEach { option ->
            val selected = option == style
            val weight = when (option) {
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.Modern -> FontWeight.Medium
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.Bold -> FontWeight.Bold
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.Heavy -> FontWeight.ExtraBold
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.Sans -> FontWeight.Medium
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.SansItalic -> FontWeight.Medium
                com.mymav.glasses.api.MymavHudPrefs.FontStyle.Classic -> FontWeight.Normal
            }
            val family = if (option == com.mymav.glasses.api.MymavHudPrefs.FontStyle.Classic) {
                androidx.compose.ui.text.font.FontFamily.Monospace
            } else {
                androidx.compose.ui.text.font.FontFamily.SansSerif
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Color(0x2245D6F5) else Color(0x0DFFFFFF))
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MyMavColors.Accent else MyMavColors.Hairline,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable {
                        style = option
                        com.mymav.glasses.api.MymavHudPrefs.save(context, font = option, size = size)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "${option.label}  ·  Aa 12:45 Next page",
                    color = if (selected) MyMavColors.Accent else MyMavColors.Text,
                    fontSize = 16.sp,
                    fontWeight = weight,
                    fontFamily = family,
                )
                Text(option.detail, color = MyMavColors.TextMuted, fontSize = 12.sp)
            }
        }
        SectionLabel("Text size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.mymav.glasses.api.MymavHudPrefs.TextSize.entries.forEach { option ->
                KitChip(
                    option.label,
                    size == option,
                    {
                        size = option
                        com.mymav.glasses.api.MymavHudPrefs.save(context, font = style, size = option)
                    },
                )
            }
        }
    }
}
