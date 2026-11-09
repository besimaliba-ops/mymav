package com.mymav.glasses.api

/**
 * Canonical glasses-home widget catalog for MyMav mini-apps.
 * Layout is packed into pages of a 2×2 cell grid; widgets may span cells via [HomeWidgetSize].
 * Order + sizes are user-configurable via [setOrder] / [setSize].
 */
object HomeBoardCatalog {
    val defaultWidgets: List<HomeWidget> = listOf(
        HomeWidget(
            id = "notify",
            title = "Socials",
            subtitle = "",
            accent = HudAccentTone.Green,
            metric = "WA:0",
            metricDetail = "IG:0",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "spotify",
            title = "Spotify",
            subtitle = "Lyrics",
            accent = HudAccentTone.Spotify,
            metric = "♪",
            metricDetail = "now",
            size = HomeWidgetSize.Tall,
        ),
        HomeWidget(
            id = "conversate",
            title = "Conversate",
            subtitle = "Cues",
            accent = HudAccentTone.Blue,
            metric = "LIVE",
            metricDetail = "meeting",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "food",
            title = "Food",
            subtitle = "Macros",
            accent = HudAccentTone.Orange,
            metric = "640",
            metricDetail = "kcal",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "shop",
            title = "Shop",
            subtitle = "Scan",
            accent = HudAccentTone.Purple,
            metric = "3",
            metricDetail = "wish",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "identify",
            title = "Identify",
            subtitle = "Look",
            accent = HudAccentTone.Teal,
            metric = "AI",
            metricDetail = "vision",
            size = HomeWidgetSize.Wide,
        ),
        HomeWidget(
            id = "football",
            title = "Football",
            subtitle = "Match",
            accent = HudAccentTone.Green,
            metric = "2-1",
            metricDetail = "live",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "fitness",
            title = "Health",
            subtitle = "",
            accent = HudAccentTone.Yellow,
            metric = "8.2k",
            metricDetail = "412kcal|69m",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "workouts",
            title = "Workouts",
            subtitle = "Run · Lift",
            accent = HudAccentTone.Teal,
            metric = "RUN",
            metricDetail = "lift",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "mirror",
            title = "Mirror",
            subtitle = "Cast",
            accent = HudAccentTone.Blue,
            metric = "CAST",
            metricDetail = "phone",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "gallery",
            title = "Gallery",
            subtitle = "Media",
            accent = HudAccentTone.Purple,
            metric = "12",
            metricDetail = "items",
            size = HomeWidgetSize.Wide,
        ),
        HomeWidget(
            id = "tasks",
            title = "Tasks",
            subtitle = "To-dos",
            accent = HudAccentTone.Orange,
            metric = "3",
            metricDetail = "open",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "shopping",
            title = "Shopping",
            subtitle = "List",
            accent = HudAccentTone.Green,
            metric = "0",
            metricDetail = "items",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "recipes",
            title = "Recipes",
            subtitle = "Recipe book",
            accent = HudAccentTone.Orange,
            metric = "0",
            metricDetail = "saved",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "notepad",
            title = "Notepad",
            subtitle = "Notes",
            accent = HudAccentTone.Yellow,
            metric = "0",
            metricDetail = "notes",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "memorysight",
            title = "Memory",
            subtitle = "Sight",
            accent = HudAccentTone.Purple,
            metric = "3",
            metricDetail = "items",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "money",
            title = "Money",
            subtitle = "FX",
            accent = HudAccentTone.Green,
            metric = "£",
            metricDetail = "gaze",
            size = HomeWidgetSize.Small,
        ),
        HomeWidget(
            id = "mymind",
            title = "Mymind",
            subtitle = "Memory",
            accent = HudAccentTone.Blue,
            metric = "2",
            metricDetail = "tasks",
            size = HomeWidgetSize.Small,
        ),
        // Navigation: appended last so existing saved order and sizes are untouched. No placeholder metric: the tile is
        // drawn from the live journey (NavWidget).
        HomeWidget(
            id = "nav",
            title = "Navigation",
            subtitle = "",
            accent = HudAccentTone.Teal,
            size = HomeWidgetSize.Small,
        ),
    )

    private val defaultsById: Map<String, HomeWidget> = defaultWidgets.associateBy { it.id }

    /** Current display order (mini-app ids). Defaults to [defaultWidgets] order. */
    @Volatile
    private var orderIds: List<String> = defaultWidgets.map { it.id }

    @Volatile
    private var sizeById: Map<String, HomeWidgetSize> =
        defaultWidgets.associate { it.id to it.size }

    val widgets: List<HomeWidget>
        get() = orderIds.mapNotNull { id ->
            val base = defaultsById[id] ?: return@mapNotNull null
            val sized = base.copy(size = sizeById[id] ?: base.size)
            val preview = HomeWidgetPreviewStore.get(id) ?: return@mapNotNull sized
            sized.copy(
                title = preview.title.ifBlank { sized.title },
                subtitle = preview.subtitle.ifBlank { sized.subtitle },
                metric = preview.metric.ifBlank { sized.metric },
                metricDetail = preview.metricDetail.ifBlank { sized.metricDetail },
                previewImageBytes = preview.imageBytes,
                previewImageWidth = preview.imageWidth,
                previewImageHeight = preview.imageHeight,
                previewIsVideo = preview.isVideo,
                bodyLines = preview.lines,
                live = true,
                state = preview.state,
            )
        }

    fun setOrder(ids: List<String>) {
        val known = defaultWidgets.map { it.id }.toSet()
        val cleaned = ids.filter { it in known }.distinct()
        val missing = defaultWidgets.map { it.id }.filter { it !in cleaned }
        orderIds = cleaned + missing
    }

    fun setSize(id: String, size: HomeWidgetSize) {
        if (id !in defaultsById) return
        sizeById = sizeById + (id to size)
    }

    fun setSizes(sizes: Map<String, HomeWidgetSize>) {
        val known = defaultsById.keys
        sizeById = defaultWidgets.associate { it.id to it.size } +
            sizes.filterKeys { it in known }
    }

    fun sizeOf(id: String): HomeWidgetSize =
        sizeById[id] ?: defaultsById[id]?.size ?: HomeWidgetSize.Small

    fun currentOrderIds(): List<String> = orderIds.toList()

    fun currentSizes(): Map<String, HomeWidgetSize> = sizeById.toMap()

    /** Short status line for phone Apps tiles. */
    fun statusLine(id: String): String {
        val w = widgets.firstOrNull { it.id == id } ?: defaultsById[id] ?: return "Ready"
        return listOf(w.metric, w.metricDetail)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { w.subtitle }
    }

    /** Max cells on one glasses home page (2×2). */
    const val PAGE_CELLS = 4
    /** Legacy alias — average widgets per page when all are 1×1. */
    const val PAGE_SIZE = 4
    /** Normalized X ≤ this = left dwell zone (prev page). */
    const val GAZE_LEFT_MAX = 0.18f
    /** Normalized X ≥ this = right dwell zone (next page). */
    const val GAZE_RIGHT_MIN = 0.82f
    /** Continuous side-gaze required to flip page. */
    const val GAZE_PAGE_DWELL_MS = 1_000L

    /** Gaze dwell on HOME pill (mini-app HUDs) before returning to home board. */
    const val HOME_GAZE_DWELL_MS = 1_000L

    /** HOME pill on 640×400 mini-app HUDs (normalized hit box). */
    const val HOME_PILL_X = 482f
    const val HOME_PILL_Y = 28f
    const val HOME_PILL_W = 130f
    const val HOME_PILL_H = 34f

    fun homePillContainsNormalized(nx: Float, ny: Float): Boolean {
        val x = nx * 640f
        val y = ny * 400f
        val slop = 16f // generous: the pill is slim, gaze is imprecise
        return x >= HOME_PILL_X - slop && x <= HOME_PILL_X + HOME_PILL_W + slop &&
            y >= HOME_PILL_Y - slop && y <= HOME_PILL_Y + HOME_PILL_H + slop
    }

    fun pageCount(): Int = packedPages().size.coerceAtLeast(1)

    fun page(pageIndex: Int): List<HomeWidget> {
        val pages = packedPages()
        if (pages.isEmpty()) return emptyList()
        return pages[pageIndex.coerceIn(0, pages.lastIndex)]
    }

    /** Page that contains [widgetId], or -1 if unknown. */
    fun pageIndexOf(widgetId: String): Int {
        val pages = packedPages()
        return pages.indexOfFirst { page -> page.any { it.id == widgetId } }
    }

    /**
     * Pack widgets into pages of a 2×2 cell grid, respecting each widget's span.
     * First-fit row-major placement; widgets that do not fit open a new page.
     */
    fun packedPages(): List<List<HomeWidget>> {
        val pages = mutableListOf<MutableList<HomeWidget>>()
        var occupied = Array(2) { BooleanArray(2) }
        var current = mutableListOf<HomeWidget>()

        fun newPage() {
            if (current.isNotEmpty()) pages.add(current)
            current = mutableListOf()
            occupied = Array(2) { BooleanArray(2) }
        }

        fun fits(col: Int, row: Int, size: HomeWidgetSize): Boolean {
            if (col + size.colSpan > 2 || row + size.rowSpan > 2) return false
            for (r in row until row + size.rowSpan) {
                for (c in col until col + size.colSpan) {
                    if (occupied[r][c]) return false
                }
            }
            return true
        }

        fun place(col: Int, row: Int, size: HomeWidgetSize) {
            for (r in row until row + size.rowSpan) {
                for (c in col until col + size.colSpan) {
                    occupied[r][c] = true
                }
            }
        }

        for (widget in widgets) {
            val size = if (widget.size.cellCount > PAGE_CELLS) HomeWidgetSize.Large else widget.size
            var placed = false
            outer@ for (row in 0 until 2) {
                for (col in 0 until 2) {
                    if (fits(col, row, size)) {
                        place(col, row, size)
                        current.add(widget.copy(size = size))
                        placed = true
                        break@outer
                    }
                }
            }
            if (!placed) {
                newPage()
                // Always place at 0,0 on a fresh page (Large fills it).
                place(0, 0, size)
                current.add(widget.copy(size = size))
            }
        }
        if (current.isNotEmpty()) pages.add(current)
        if (pages.isEmpty()) pages.add(mutableListOf())
        return pages
    }

    /** Continuous center-gaze required to open the focused widget. */
    const val GAZE_OPEN_DWELL_MS = 1_000L

    /** Pixel rect of a placed widget on the 640×400 home board. */
    data class TileRect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val cx: Float get() = x + w / 2f
        val cy: Float get() = y + h / 2f
    }

    /** Home grid bounds — single source for the glasses board and gaze hit-tests. */
    const val GRID_TOP = 80f
    const val GRID_BOTTOM = 362f
    const val GRID_LEFT = 16f
    const val GRID_RIGHT = 624f
    const val GRID_GAP = 12f

    /** Pixel rect for a cell span on the 2×2 home grid. */
    fun cellRect(col: Int, row: Int, colSpan: Int, rowSpan: Int): TileRect {
        val colW = (GRID_RIGHT - GRID_LEFT - GRID_GAP) / 2f
        val rowH = (GRID_BOTTOM - GRID_TOP - GRID_GAP) / 2f
        return TileRect(
            x = GRID_LEFT + col * (colW + GRID_GAP),
            y = GRID_TOP + row * (rowH + GRID_GAP),
            w = colW * colSpan + GRID_GAP * (colSpan - 1),
            h = rowH * rowSpan + GRID_GAP * (rowSpan - 1),
        )
    }

    fun tileRect(pageIndex: Int, widgetId: String): TileRect? {
        val p = layoutPage(pageIndex).firstOrNull { it.widget.id == widgetId } ?: return null
        return cellRect(p.col, p.row, p.colSpan, p.rowSpan)
    }

    /**
     * Ask MyMav bubble + AUDIO / TEXT / CANCEL pills. Fixed geometry shared by the
     * glasses board (drawing) and [GlassesHomeController] (gaze hit-tests) — the old
     * code drew a text-height bubble but hit-tested a fixed 56px one.
     */
    object AskLayout {
        const val SUMMON_SIZE = 120f
        const val BUBBLE_W = 440f
        /** Room for three lines of answer text. */
        const val BUBBLE_H = 96f
        const val PILL_H = 40f
        const val PILL_GAP = 10f

        /** Expanded answer card (recipes, long answers, pictures). */
        const val EXP_AVATAR_X = 72f
        const val EXP_BUBBLE_X = 136f
        const val EXP_BUBBLE_Y = 52f
        const val EXP_BUBBLE_W = 496f
        const val EXP_BUBBLE_H = 268f
        const val EXP_IMG_W = 200f
        const val EXP_IMG_H = 150f

        fun pillIds(
            hasMore: Boolean,
            canSave: Boolean = false,
            canCook: Boolean = false,
            cooking: Boolean = false,
            hasTimer: Boolean = false,
        ): List<String> = buildList {
            if (cooking) {
                add("prev"); add("more")
                if (hasTimer) add("timer")
                add("audio"); add("cancel")
                return@buildList
            }
            if (hasMore) add("more")
            if (canSave) add("save")
            if (canCook) add("cook")
            add("audio")
            if (!canCook) add("text")
            add("cancel")
        }

        data class Geometry(
            val bubbleX: Float,
            val bubbleY: Float,
            val bubbleW: Float,
            val bubbleH: Float,
            val pillY: Float,
            val pillW: Float,
        ) {
            fun pillX(index: Int): Float = bubbleX + index * (pillW + PILL_GAP)
        }

        fun geometry(
            avatarX: Float,
            avatarY: Float,
            expanded: Boolean = false,
            pillCount: Int = 3,
        ): Geometry {
            if (expanded) {
                val y = EXP_BUBBLE_Y
                return Geometry(
                    bubbleX = EXP_BUBBLE_X,
                    bubbleY = y,
                    bubbleW = EXP_BUBBLE_W,
                    bubbleH = EXP_BUBBLE_H,
                    pillY = y + EXP_BUBBLE_H + 8f,
                    pillW = (EXP_BUBBLE_W - PILL_GAP * (pillCount - 1)) / pillCount,
                )
            }
            val ay = (avatarY - SUMMON_SIZE / 2f).coerceIn(4f, 400f - SUMMON_SIZE - 8f)
            val bubbleX = (avatarX - BUBBLE_W / 2f).coerceIn(8f, 640f - BUBBLE_W - 8f)
            val bubbleY = (ay + SUMMON_SIZE + 4f).coerceAtMost(400f - BUBBLE_H - PILL_H - 22f)
            return Geometry(
                bubbleX = bubbleX,
                bubbleY = bubbleY,
                bubbleW = BUBBLE_W,
                bubbleH = BUBBLE_H,
                pillY = bubbleY + BUBBLE_H + 8f,
                pillW = (BUBBLE_W - PILL_GAP * (pillCount - 1)) / pillCount,
            )
        }
    }

    /**
     * Absolute cell layout for widgets on a page (col/row 0..1, with spans).
     */
    fun layoutPage(pageIndex: Int): List<PlacedWidget> {
        val pageWidgets = page(pageIndex)
        val occupied = Array(2) { BooleanArray(2) }
        val placed = mutableListOf<PlacedWidget>()

        fun fits(col: Int, row: Int, size: HomeWidgetSize): Boolean {
            if (col + size.colSpan > 2 || row + size.rowSpan > 2) return false
            for (r in row until row + size.rowSpan) {
                for (c in col until col + size.colSpan) {
                    if (occupied[r][c]) return false
                }
            }
            return true
        }

        fun mark(col: Int, row: Int, size: HomeWidgetSize) {
            for (r in row until row + size.rowSpan) {
                for (c in col until col + size.colSpan) {
                    occupied[r][c] = true
                }
            }
        }

        for (widget in pageWidgets) {
            var done = false
            outer@ for (row in 0 until 2) {
                for (col in 0 until 2) {
                    if (fits(col, row, widget.size)) {
                        mark(col, row, widget.size)
                        placed.add(PlacedWidget(widget, col, row, widget.size.colSpan, widget.size.rowSpan))
                        done = true
                        break@outer
                    }
                }
            }
            if (!done) {
                placed.add(PlacedWidget(widget, 0, 0, 1, 1))
            }
        }
        return placed
    }
}

data class PlacedWidget(
    val widget: HomeWidget,
    val col: Int,
    val row: Int,
    val colSpan: Int,
    val rowSpan: Int,
)
