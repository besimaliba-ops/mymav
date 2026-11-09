package com.mymav.glasses.api

/**
 * Rich companion HUD payloads. SDK backends render these as full-bleed M2Screens;
 * stub backends surface a text summary in [GlassesSession.statusMessage].
 */
sealed class GlassesHud {
    data class TextLines(
        val screenId: String,
        val title: String,
        val lines: List<String>,
    ) : GlassesHud()

    /** Glasses home widget board — side-gaze pages; Tap opens a mini-app (not gaze/hover). */
    data class HomeBoard(
        val pageIndex: Int = 0,
        val pageCount: Int = 2,
        val focusedWidgetId: String,
        val widgets: List<HomeWidget>,
        /** Glasses battery (-1 unknown). */
        val batteryPercent: Int = -1,
        /** Phone battery (-1 unknown). */
        val phoneBatteryPercent: Int = -1,
        val glassesCharging: Boolean = false,
        val phoneCharging: Boolean = false,
        val statusLabel: String = "",
        /** "left" | "right" | null — side-gaze page dwell. */
        val dwellSide: String? = null,
        /** 0..1 progress toward [HomeBoardCatalog.GAZE_PAGE_DWELL_MS]. */
        val dwellProgress: Float = 0f,
        /** 0..1 progress toward opening the focused widget via center gaze. */
        val openDwellProgress: Float = 0f,
        /** MyMav mascot visible on home (glance-up / tap / page run / open jump). */
        val avatarVisible: Boolean = false,
        val avatarPose: MymavAvatarArt.Pose = MymavAvatarArt.Pose.Idle,
        val avatarFrame: Int = 0,
        /** Canvas X of avatar center (px) — rests bottom-middle when idle. */
        val avatarX: Float = 320f,
        /** Canvas Y of avatar center (px). */
        val avatarY: Float = 368f,
        val avatarImageBytes: ByteArray? = null,
        val avatarImageWidth: Int = 0,
        val avatarImageHeight: Int = 0,
        /**
         * Docked mascot pose set: [avatarPoses] are raw RGBA squares of this size,
         * all resident on the glasses; [avatarCell] picks the visible one.
         */
        val avatarCellSize: Int = 0,
        val avatarCell: Int = 0,
        val avatarPoses: List<ByteArray>? = null,
        /** Speech bubble above the avatar (assistant / wake). */
        val avatarSpeech: String = "",
        /** 0..1 progress while gazing the docked avatar to summon (1s). */
        val avatarGazeProgress: Float = 0f,
        /**
         * After Activate / summon: show AUDIO · TEXT · CANCEL under the Ask bubble
         * so the wearer can speak (VTXT) or type without auto-opening the keyboard.
         */
        val askChoiceVisible: Boolean = false,
        /** "audio" | "text" | "cancel" while 1s gaze dwells on an Ask pill. */
        val askChoiceFocus: String? = null,
        /** 0..1 progress toward 1s gaze-activate on [askChoiceFocus]. */
        val askChoiceGazeProgress: Float = 0f,
        /** Large answer card (long text / recipe / pictures): avatar moves left, bubble fills the board. */
        val askExpanded: Boolean = false,
        /** "2/4" style page marker for multi-page answers; blank when single page. */
        val askPageLabel: String = "",
        /** More pages follow — shows a MORE pill before AUDIO / TEXT / CANCEL. */
        val askHasMore: Boolean = false,
        /** The current answer can be saved to Recipe Book / Notepad — shows a SAVE pill. */
        val askCanSave: Boolean = false,
        /** Recipe on screen — shows a COOK pill (step-by-step cooking mode). */
        val askCanCook: Boolean = false,
        /** Cooking mode: BACK / NEXT pills, large step text. */
        val askCooking: Boolean = false,
        /** Cooking step has a time — label for the TIMER pill (e.g. "20m"), blank = no pill. */
        val askTimerLabel: String = "",
        /** JPEG/PNG picture for the current answer page (drawn left of the text). */
        val askImageBytes: ByteArray? = null,
        val actions: HomeBoardActions = HomeBoardActions(),
    ) : GlassesHud()

    /** Shared gaze keyboard for text entry across mini-apps. */
    data class Keyboard(
        val title: String = "Keyboard",
        val buffer: String = "",
        val focusedKeyId: String = "a",
        val suggestions: List<String> = emptyList(),
        val voiceArmed: Boolean = false,
        /** Which voice pill is active: none | text | note */
        val voiceMode: VoiceInputMode = VoiceInputMode.None,
        /** Status line under voice pills (demo STT / note stub). */
        val voiceStatus: String = "",
        /** letters | emoji | categories | search */
        val mode: KeyboardMode = KeyboardMode.Letters,
        /** Page index within emoji / category grids. */
        val pageIndex: Int = 0,
        val pageCount: Int = 1,
        /** Active emoji category id when browsing. */
        val categoryId: String = "smileys",
        /** Search query when [mode] is Search. */
        val searchQuery: String = "",
        /** 0..1 progress toward 1s gaze-activate on the focused key. */
        val keyGazeProgress: Float = 0f,
        /** Letter case toggle (CAPS / caps). */
        val capsLocked: Boolean = false,
        /** Done key label — "Send" for WA/IG reply, "OK" elsewhere. */
        val doneLabel: String = "OK",
        /** Letters mode shows the numbers / symbols page. */
        val symbols: Boolean = false,
        val actions: KeyboardActions = KeyboardActions(),
    ) : GlassesHud()

    /** Premium mini-app enter / idle glasslet. */
    data class AppEnter(
        val appId: String,
        val title: String,
        val subtitle: String,
        val accent: HudAccentTone = HudAccentTone.Teal,
        val bullets: List<String> = emptyList(),
        val metric: String = "",
        val metricLabel: String = "",
        /**
         * When not [HudReadableMode.Classic], [AppEnterScreen] draws the shared readable
         * layout (compact header · full-width cards · bottom action row) instead of the
         * metric+bullets enter glasslet. Other mini-apps keep Classic unchanged.
         */
        val readableMode: HudReadableMode = HudReadableMode.Classic,
        /** Compact status chip next to the title (e.g. Listening). */
        val statusChip: String = "",
        /** Full-width content cards (Conversate caption/cue, phase messages). */
        val cards: List<HudReadableCard> = emptyList(),
        /** Shop / Objects product identity strip. */
        val product: HudReadableProduct? = null,
        /** Shop offer rows for the current page (1–2). */
        val offers: List<HudReadableOffer> = emptyList(),
        val offerPageLabel: String = "",
        /** Bottom action labels (e.g. Summary · Save · Stop). */
        val footerLabels: List<String> = emptyList(),
        /** Focused footer index for Forward/Backward + Tap. */
        val footerFocus: Int = 0,
        /** Paging for overflow caption/cue/summary/details (0-based). */
        val contentPage: Int = 0,
        val contentPageCount: Int = 1,
        val contentOverflowHint: String = "",
        /** 0..1 progress toward [HomeBoardCatalog.HOME_GAZE_DWELL_MS] on HOME pill. */
        val homeDwellProgress: Float = 0f,
        /** 0..1 gaze dwell on the focused footer pill (bar under the pill). */
        val footerGazeProgress: Float = 0f,
        /** Footer indices that ignore tap/gaze (processing, nothing to act on). */
        val footerDisabled: Set<Int> = emptySet(),
        /** Index into [offers] under gaze dwell (-1 none) and its 0..1 progress. */
        val offerGazeIndex: Int = -1,
        val offerGazeProgress: Float = 0f,
        /** Food result tiles (kcal / protein / carbs …). */
        val metrics: List<HudReadableMetric> = emptyList(),
        /** Discreet input-source label (e.g. "Test input · phone camera"). */
        val sourceLabel: String = "",
        val actions: AppEnterActions = AppEnterActions(),
    ) : GlassesHud()

    data class Notification(
        val appLabel: String,
        val accent: HudAccent,
        val sender: String,
        val body: String,
        val messagePage: Int = 0,
        val replyState: HudReplyState = HudReplyState.Idle,
        val draftReply: String? = null,
        val hasMedia: Boolean = false,
        val mediaKindLabel: String = "",
        /** Inbox = pick a chat; Detail = read then reply. */
        val stage: NotifyStage = NotifyStage.Inbox,
        /** 1-based index into the pending inbox (for "2/4" chrome). */
        val inboxIndex: Int = 1,
        val inboxCount: Int = 1,
        /** Active channel filter shown on glass: ALL / WA / IG. */
        val channelFilter: NotifyChannelFilter = NotifyChannelFilter.All,
        /** RGBA8888 / JPEG pixel bytes for contact avatar (prefer square ≥72px). */
        val avatarImageBytes: ByteArray? = null,
        val avatarImageWidth: Int = 0,
        val avatarImageHeight: Int = 0,
        /** Optional media thumbnail (photo/video preview). */
        val mediaImageBytes: ByteArray? = null,
        val mediaImageWidth: Int = 0,
        val mediaImageHeight: Int = 0,
        /** 0..1 HOME pill gaze dwell. */
        val homeDwellProgress: Float = 0f,
        /** Real inbox state: a message, nothing pending, or notification access unavailable. */
        val inboxState: NotifyInboxState = NotifyInboxState.Messages,
        val actions: NotificationHudActions = NotificationHudActions(),
    ) : GlassesHud()

    data class Lyrics(
        val trackName: String,
        val artistName: String,
        val albumName: String? = null,
        val currentLyric: String,
        val nextLyric: String = "",
        val progressMs: Long = 0L,
        val isPlaying: Boolean = true,
        val albumArtBytes: ByteArray? = null,
        val albumArtWidth: Int = 0,
        val albumArtHeight: Int = 0,
        /** True when [albumArtBytes] is a still from Spotify Canvas / video preview. */
        val artIsVideoPreview: Boolean = false,
        /** 0..1 HOME pill gaze dwell. */
        val homeDwellProgress: Float = 0f,
        val actions: LyricsHudActions = LyricsHudActions(),
    ) : GlassesHud()

    /** Identify / Objects result — thumbnail identity + attribute details + footer actions. */
    data class IdentifyResult(
        val label: String,
        val confidence: Float = 0f,
        val detail: String = "",
        val images: List<IdentifyImage> = emptyList(),
        val accent: HudAccentTone = HudAccentTone.Teal,
        /** Header title on glass (mock: Objects). */
        val headerTitle: String = "Objects",
        val brand: String = "",
        val productName: String = "",
        val variant: String = "",
        val size: String = "",
        val barcode: String = "",
        val productType: String = "",
        val identityEyebrow: String = "IDENTIFIED FROM LABEL",
        val uncertainty: String = "",
        val nextInstruction: String = "",
        /** Confirmed attribute rows for the details card (label → value). */
        val attributes: List<HudReadableAttribute> = emptyList(),
        /** When true, content area shows paged full answer instead of the attribute card. */
        val showingDetails: Boolean = false,
        val detailsBody: String = "",
        val contentPage: Int = 0,
        val contentPageCount: Int = 1,
        val footerLabels: List<String> = listOf("Rescan", "Details", "Find prices"),
        val footerFocus: Int = 0,
        /** 0..1 HOME pill gaze dwell. */
        val homeDwellProgress: Float = 0f,
        val footerGazeProgress: Float = 0f,
        val footerDisabled: Set<Int> = emptySet(),
        val sourceLabel: String = "",
        val actions: IdentifyHudActions = IdentifyHudActions(),
    ) : GlassesHud()

    /** Spotify Liked Songs / search hit list — gaze or tap to play. */
    data class SongList(
        val title: String = "Liked Songs",
        val items: List<SongPickItem> = emptyList(),
        val focusedIndex: Int = 0,
        /** 0..1 progress toward selecting the focused row via 1s gaze. */
        val selectDwellProgress: Float = 0f,
        val homeDwellProgress: Float = 0f,
        val statusLine: String = "",
        val actions: SongListHudActions = SongListHudActions(),
    ) : GlassesHud()

    /**
     * Fitness glasslet — Daily / Weekly / Workouts / Sleep with icon+stat rows
     * (matches the phone weekly summary layout).
     */
    data class FitnessSummary(
        val page: FitnessHudPage = FitnessHudPage.Daily,
        val title: String = "Fitness",
        val subtitle: String = "Today",
        val heroValue: String = "",
        val heroLabel: String = "steps",
        val rows: List<FitnessHudRow> = emptyList(),
        val statusLine: String = "",
        val homeDwellProgress: Float = 0f,
        val actions: FitnessHudActions = FitnessHudActions(),
    ) : GlassesHud()

    /** Tasks mini-app — list, add via gaze keyboard, due time, toggle done. */
    data class TasksBoard(
        val items: List<TaskHudItem> = emptyList(),
        /** Row index, or [items].size + pill offset (ADD=0 … BACK=3). */
        val focusedIndex: Int = 0,
        val mode: TasksBoardMode = TasksBoardMode.List,
        val selectDwellProgress: Float = 0f,
        val homeDwellProgress: Float = 0f,
        val openCount: Int = 0,
        val statusLine: String = "",
        val actions: TasksBoardHudActions = TasksBoardHudActions(),
    ) : GlassesHud()

    /**
     * Generic list app (Shopping, Recipes, Notepad): scrollable coloured rows with optional
     * thumbnails, action pills, and a detail view. Focus indexes rows first, then pills.
     */
    data class ListApp(
        val appId: String,
        val title: String,
        val tone: HudAccentTone = HudAccentTone.Teal,
        val rows: List<ListRow> = emptyList(),
        val focusedIndex: Int = 0,
        val pills: List<ListPill> = emptyList(),
        val statusLine: String = "",
        val emptyTitle: String = "Nothing here yet",
        val emptyHint: String = "",
        val detail: ListDetail? = null,
        val homeDwellProgress: Float = 0f,
        val actions: ListAppActions = ListAppActions(),
    ) : GlassesHud()

    /** Workouts — pick Running or Weight training. */
    data class WorkoutSelect(
        val focusedIndex: Int = 0,
        val homeDwellProgress: Float = 0f,
        val statusLine: String = "↑↓ FOCUS · TAP SELECT",
        val actions: WorkoutSelectHudActions = WorkoutSelectHudActions(),
    ) : GlassesHud()

    /**
     * Visletic-style running “Stay on pace” glasslet (640×400).
     * [paceSliderNorm] 0 = slower / behind, 0.5 = on target, 1 = faster / ahead.
     */
    data class RunningPace(
        val statusLabel: String = "AHEAD",
        val deltaLabel: String = "-0:08",
        val paceLabel: String = "4:57",
        val paceUnit: String = "/KM",
        val targetLabel: String = "TGT 5:00",
        val paceSliderNorm: Float = 0.62f,
        val elapsedLabel: String = "16:57",
        val splitMeters: Int = 585,
        val distanceKmLabel: String = "3.41",
        val homeDwellProgress: Float = 0f,
        val actions: RunningPaceHudActions = RunningPaceHudActions(),
    ) : GlassesHud()

    /**
     * Weight training session — focused exercise name, body part, sets as □/✓, reps.
     * Tap or 1s gaze checks off the next incomplete set.
     */
    data class WeightSession(
        val exercises: List<WeightExerciseHudItem> = emptyList(),
        val focusedIndex: Int = 0,
        /** 0..1 progress toward selecting / completing a set via 1s gaze. */
        val selectDwellProgress: Float = 0f,
        val homeDwellProgress: Float = 0f,
        val statusLine: String = "TAP OR 1S · NEXT SET",
        val actions: WeightSessionHudActions = WeightSessionHudActions(),
    ) : GlassesHud()

    /** Memory Sight — belongings timeline (AI camera detections). */
    data class MemoryTimeline(
        val title: String = "Memory Sight",
        val entries: List<String> = emptyList(),
        val statusLine: String = "",
        val homeDwellProgress: Float = 0f,
        val actions: AppEnterActions = AppEnterActions(),
    ) : GlassesHud()

    /** Money — foreign price gaze-converted to home currency. */
    data class MoneyQuote(
        val foreignLabel: String = "",
        val homeLabel: String = "",
        val rateLabel: String = "",
        /** 0..1 progress toward 1s gaze on a price tag. */
        val dwellProgress: Float = 0f,
        val homeDwellProgress: Float = 0f,
        val actions: AppEnterActions = AppEnterActions(),
    ) : GlassesHud()

    /** Mymind — conversation memory + extracted tasks by cadence. */
    data class MymindBoard(
        val page: MymindTaskPage = MymindTaskPage.Daily,
        val memorySummary: String = "",
        val taskLines: List<String> = emptyList(),
        val peopleLines: List<String> = emptyList(),
        val homeDwellProgress: Float = 0f,
        val actions: AppEnterActions = AppEnterActions(),
    ) : GlassesHud()

    /** Live 3D navigation map (preview of a route, then following it). The map shapes are computed by [NavScene]. */
    data class NavMap(
        val nav: MymavNav.NavState,
        val actions: NavMapActions = NavMapActions(),
        val homeDwellProgress: Float = 0f,
    ) : GlassesHud()
}

/** Glasses-side controls for the map: tap = start (preview) / repeat guidance, long-tap = stop and go home. */
data class NavMapActions(
    val onStart: (() -> Unit)? = null,
    val onStop: (() -> Unit)? = null,
)

enum class MymindTaskPage {
    Daily,
    Weekly,
    Monthly,
}

/** Glasses Tasks row model. */
data class TaskHudItem(
    val id: String,
    val title: String,
    val dueLabel: String,
    val done: Boolean,
)

enum class TasksBoardMode {
    List,
    AddTitle,
    SetDue,
}

data class IdentifyImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val caption: String = "",
)

data class IdentifyHudActions(
    val onAgain: (() -> Unit)? = null,
    /** Open Shop with the identified product and look up live offers. */
    val onFindPrices: (() -> Unit)? = null,
    /** Toggle / page the full Details answer. */
    val onDetails: (() -> Unit)? = null,
    val onFooterFocusChanged: ((Int) -> Unit)? = null,
    val onFooterActivated: ((Int) -> Unit)? = null,
    val onContentPageChanged: ((Int) -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

/** Long-tap / 1s HOME-pill gaze from a mini-app enter glasslet. */
data class AppEnterActions(
    val onHomeRequested: (() -> Unit)? = null,
    /** Normalized gaze on 640×400 (0..1) for HOME pill dwell. */
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
    val onFooterFocusChanged: ((Int) -> Unit)? = null,
    val onFooterActivated: ((Int) -> Unit)? = null,
    val onContentPageChanged: ((Int) -> Unit)? = null,
    /** Shop: an offer row (index into the visible page) was selected by 1s gaze. */
    val onOfferSelected: ((Int) -> Unit)? = null,
)

/** Readable HUD layout mode for [GlassesHud.AppEnter]. */
enum class HudReadableMode {
    Classic,
    Conversate,
    Shop,
    Food,
}

/** One value tile on the Food result (value + unit label). */
data class HudReadableMetric(
    val value: String,
    val label: String,
)

/** Full-width content card (caption, cue, phase message). */
data class HudReadableCard(
    val eyebrow: String = "",
    val body: String = "",
    /** Full text when [body] is brief; Details / paging surfaces this. */
    val fullBody: String = "",
)

/** Shop / Objects product identity strip beside an optional thumbnail. */
data class HudReadableProduct(
    val title: String = "",
    val meta: String = "",
    val status: String = "",
    val thumbBytes: ByteArray? = null,
    val thumbWidth: Int = 0,
    val thumbHeight: Int = 0,
)

/** One retailer offer row on the Shop readable HUD. */
data class HudReadableOffer(
    val retailer: String,
    val priceLabel: String,
    val delivery: String = "",
    val selected: Boolean = false,
)

/** Confirmed attribute line on Objects / Identify details card. */
data class HudReadableAttribute(
    val label: String,
    val value: String,
)

data class HomeWidget(
    val id: String,
    val title: String,
    val subtitle: String,
    val accent: HudAccentTone = HudAccentTone.Teal,
    val metric: String = "",
    val metricDetail: String = "",
    /** Grid footprint on the glasses home board (cells in a 2×2 page). */
    val size: HomeWidgetSize = HomeWidgetSize.Small,
    /** Optional JPEG/PNG preview (e.g. Spotify album art) — not a launch trigger. */
    val previewImageBytes: ByteArray? = null,
    val previewImageWidth: Int = 0,
    val previewImageHeight: Int = 0,
    val previewIsVideo: Boolean = false,
    /** Optional body rows ("HH:MM|Title") — Tasks widget shows today's list here. */
    val bodyLines: List<String> = emptyList(),
    /**
     * True when a companion app has published real state for this widget. False means [metric] /
     * [metricDetail] are only the catalogue placeholders and must not be presented as live data.
     */
    val live: Boolean = false,
    /**
     * Companion-reported state for the tile (Spotify: playing / paused / loading / disconnected / unavailable;
     * Conversate: live / idle). Empty = the companion has not reported one, so no state is implied.
     */
    val state: String = "",
)

/**
 * Widget footprint on the 2-column × 2-row home cell grid.
 * Matches design-ref 12 mixed sizes (1×1 vs taller/wider).
 */
enum class HomeWidgetSize(val colSpan: Int, val rowSpan: Int, val label: String) {
    Small(1, 1, "1×1"),
    Tall(1, 2, "1×2"),
    Wide(2, 1, "2×1"),
    Large(2, 2, "2×2"),
    ;

    val cellCount: Int get() = colSpan * rowSpan

    fun next(): HomeWidgetSize = when (this) {
        Small -> Tall
        Tall -> Wide
        Wide -> Large
        Large -> Small
    }

    companion object {
        fun fromKey(raw: String?): HomeWidgetSize {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Small
        }
    }
}

enum class KeyboardMode {
    Letters,
    Emoji,
    Categories,
    Search,
}

enum class VoiceInputMode {
    None,
    /** Speech → typed buffer (voice-to-text). */
    ToText,
    /** Capture a voice note clip (demo stub OK). */
    Note,
}

enum class HudAccentTone {
    Teal,
    Green,
    Yellow,
    Red,
    Blue,
    Orange,
    Purple,
    Spotify,
}

data class HomeBoardActions(
    val onFocusChanged: ((String) -> Unit)? = null,
    val onOpenWidget: ((String) -> Unit)? = null,
    val onPageChanged: ((Int) -> Unit)? = null,
    /**
     * Normalized gaze sample on the 640×400 canvas (0..1).
     * Left ≤ [HomeBoardCatalog.GAZE_LEFT_MAX] / right ≥ [HomeBoardCatalog.GAZE_RIGHT_MIN]
     * for [HomeBoardCatalog.GAZE_PAGE_DWELL_MS] turns the page.
     */
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
    /** Tap the docked avatar — summon / open Ask MyMav. */
    val onAvatarTapped: (() -> Unit)? = null,
    /** Ask MyMav choice: glasses mic / VTXT path. */
    val onAskAudio: (() -> Unit)? = null,
    /** Ask MyMav choice: open text keyboard. */
    val onAskText: (() -> Unit)? = null,
    /** Dismiss Ask MyMav without sending. */
    val onAskCancel: (() -> Unit)? = null,
    /** Ask MyMav: next page of a multi-page answer. */
    val onAskMore: (() -> Unit)? = null,
    /** Ask MyMav: save the current answer (recipe → Recipe Book, anything else → Notepad). */
    val onAskSave: (() -> Unit)? = null,
    val onAskCook: (() -> Unit)? = null,
    val onAskPrev: (() -> Unit)? = null,
    val onAskTimer: (() -> Unit)? = null,
)

data class KeyboardActions(
    val onFocusChanged: ((String) -> Unit)? = null,
    val onKeyCommitted: ((String) -> Unit)? = null,
    val onSuggestionSelected: ((String) -> Unit)? = null,
    /** @deprecated Prefer [onVoiceToText] / [onVoiceNote]. */
    val onVoiceRequested: (() -> Unit)? = null,
    val onVoiceToText: (() -> Unit)? = null,
    val onVoiceNote: (() -> Unit)? = null,
    val onBackspace: (() -> Unit)? = null,
    /** Wipe the type buffer (and search query in Search mode). */
    val onClear: (() -> Unit)? = null,
    val onDone: ((String) -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
    val onModeChanged: ((KeyboardMode) -> Unit)? = null,
    val onPageChanged: ((Int) -> Unit)? = null,
    val onCategorySelected: ((String) -> Unit)? = null,
    val onSearchQueryChanged: ((String) -> Unit)? = null,
    /** Toggle letter caps / lowercase. */
    val onCapsToggled: (() -> Unit)? = null,
    /** Toggle the numbers / symbols page. */
    val onSymbolsToggled: (() -> Unit)? = null,
    /**
     * When set, bottom **Search** submits the typed buffer (song search) instead of
     * switching into emoji Search-filter mode.
     */
    val onSearchSubmit: (() -> Unit)? = null,
)

enum class HudAccent {
    WhatsAppGreen,
    InstagramRed,
    SpotifyGreen,
}

enum class HudReplyState {
    Idle,
    Listening,
    ConfirmSend,
    Sent,
    Cancelled,
}

/** Notify glasslet browse stage — select a message before opening the keyboard. */
enum class NotifyStage {
    Inbox,
    Detail,
}

/** Glasses + phone filter for WhatsApp vs Instagram inbox. */
enum class NotifyChannelFilter {
    All,
    WhatsApp,
    Instagram,
}

data class NotificationHudActions(
    val onReplyRequested: (() -> Unit)? = null,
    val onSendConfirmed: (() -> Unit)? = null,
    val onScrollUp: (() -> Unit)? = null,
    val onScrollDown: (() -> Unit)? = null,
    val onDismissRequested: (() -> Unit)? = null,
    /** Open shared gaze keyboard for typed reply (optional). */
    val onKeyboardRequested: (() -> Unit)? = null,
    /** Inbox → open Detail for focused message. */
    val onOpenSelected: (() -> Unit)? = null,
    /** Detail → back to Inbox list. */
    val onBackToInbox: (() -> Unit)? = null,
    /** Cycle ALL → WA → IG while in Inbox. */
    val onCycleChannelFilter: (() -> Unit)? = null,
    /** Long-tap / 1s HOME gaze → glasses home board. */
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

data class LyricsHudActions(
    /** Tap on the control that gaze is currently focused on (ids in [LyricsControlLayout]). */
    val onControl: ((String) -> Unit)? = null,
    val onPreviousTrack: (() -> Unit)? = null,
    val onTogglePlayback: (() -> Unit)? = null,
    val onNextTrack: (() -> Unit)? = null,
    /** Open shared gaze keyboard to search for a song. */
    val onSearchRequested: (() -> Unit)? = null,
    /** Open Liked Songs picker on glass. */
    val onLikedSongsRequested: (() -> Unit)? = null,
    /** Long-tap / 1s HOME gaze → glasses home board. */
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

/** Pick a track from Liked Songs (or search results) on glass. */
data class SongPickItem(
    val trackId: String,
    val title: String,
    val artist: String,
)

data class SongListHudActions(
    val onSelect: ((SongPickItem) -> Unit)? = null,
    val onFocusChanged: ((Int) -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

data class TasksBoardHudActions(
    val onFocusChanged: ((Int) -> Unit)? = null,
    val onToggleDone: ((String) -> Unit)? = null,
    val onAddRequested: (() -> Unit)? = null,
    /** Task id to edit due time (HHMM via shared keyboard). */
    val onSetDueRequested: ((String) -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
    /** Tap or 1s gaze on the focused row / bottom pill. */
    val onSelectFocused: (() -> Unit)? = null,
)

class ListRow(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val trailing: String = "",
    val tone: HudAccentTone = HudAccentTone.Teal,
    /** One or two characters drawn in the coloured badge when there is no picture. */
    val badge: String = "",
    /** Non-null = tickable row; true draws a tick and dims the row. */
    val checked: Boolean? = null,
    val imageBytes: ByteArray? = null,
)

class ListPill(val label: String, val tone: HudAccentTone? = null)

class ListDetail(
    val title: String,
    val body: String,
    val imageBytes: ByteArray? = null,
    val pageLabel: String = "",
)

data class ListAppActions(
    val onFocusChanged: ((Int) -> Unit)? = null,
    val onSelect: (() -> Unit)? = null,
    val onHomeRequested: (() -> Unit)? = null,
    val onGazeNormalized: ((Float, Float) -> Unit)? = null,
)

/** What the Socials screen should show: a real message, an empty inbox, or no notification access. */
enum class NotifyInboxState { Messages, Empty, AccessUnavailable }
