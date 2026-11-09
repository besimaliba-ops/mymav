package com.mymav.glasses.api

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Navigation Home widget shows. It is derived on demand from the same [MymavNav] state the full-screen map and the
 * phone use, so the three can never disagree. Distances are rounded coarsely and times to the minute so the widget only
 * changes (and the Home board only redraws) when the reading would visibly change.
 */
object NavWidget {
    const val ID = "nav"
    const val IDLE = "idle"; const val CALC = "calculating"; const val READY = "ready"; const val NAV = "navigating"
    const val REROUTE = "rerouting"; const val GPS = "gps"; const val ARRIVED = "arrived"

    fun q(m: Double): String = when {
        m < 100 -> "${(Math.round(m / 10.0) * 10).coerceAtLeast(10)} m"
        m < 1000 -> "${Math.round(m / 50.0) * 50} m"
        else -> String.format(Locale.UK, "%.1f km", m / 1000)
    }
    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))
    private fun short(s: String) = s.substringBefore(',').trim()

    /** lines = [arrow kind, arrival time, remaining distance, travel mode] */
    fun preview(): HomeWidgetPreviewStore.Preview {
        val st = MymavNav.state.value
        val plan = MymavNav.planningTo
        fun p(title: String, sub: String, metric: String = "", detail: String = "", state: String, lines: List<String> = emptyList()) =
            HomeWidgetPreviewStore.Preview(title = title, subtitle = sub, metric = metric, metricDetail = detail, lines = lines, state = state)
        if (st == null) return if (plan != null) p(short(plan), "Finding route…", state = CALC) else p("Where to?", "Tap to plan a route", state = IDLE)
        val dest = short(st.destName)
        val arrive = clock(st.etaMs)
        return when {
            st.mode == MymavNav.Mode.Arrived -> p(dest, "You've arrived", state = ARRIVED)
            st.mode == MymavNav.Mode.Preview ->
                if (st.rerouting) p(dest, "Finding route…", state = CALC)
                else p(dest, "Tap to start", q(st.remainingM), MymavNav.fmtDuration(st.remainingS), READY, listOf("straight", arrive, q(st.remainingM), st.travel.label))
            st.gps == MymavNav.Gps.Lost -> p(dest, "Waiting for location", "Last: ${q(st.toNextM)}", st.street.ifBlank { st.instruction }.take(30), GPS, listOf(st.maneuver, arrive, q(st.remainingM), st.travel.label))
            st.rerouting -> p(dest, "Updating route…", state = REROUTE)
            else -> p(dest, "$arrive - ${q(st.remainingM)}", q(st.toNextM), st.street.ifBlank { st.instruction }.take(30), NAV, listOf(st.maneuver, arrive, q(st.remainingM), st.travel.label))
        }
    }

    fun install() { HomeWidgetPreviewStore.provide(ID) { preview() } }
}

/**
 * The Navigation app (glasses launcher + Home widget id "nav"). With no journey it opens a destination screen built on the
 * shared list app: say or type a destination (the gaze keyboard has dictation), pick a recent one, choose the travel mode.
 * With a journey (or a route preview) it reopens that same journey; it never starts a second one.
 */
object NavigationMiniApp : MiniApp {
    override val descriptor = MiniAppDescriptor(
        id = "nav", title = "Navigation", subtitle = "Turn-by-turn maps for walking, cycling and driving",
        route = "mini/nav", requiredCapabilities = listOf("location", "hud"),
    )
    private val main = Handler(Looper.getMainLooper())
    private var session: GlassesSession? = null
    private var focus = 0
    private var note = ""
    private var results: List<MymavNav.Place>? = null
    private var query = ""

    /** True while the journey (or its preview) is something to reopen rather than a search to start. */
    fun journeyOpen() = MymavNav.state.value != null

    override fun onEnter(session: GlassesSession) {
        this.session = session
        GlassesHomeController.markMiniAppOpen("nav")
        MymavNav.requestPhoneOpen()
        if (journeyOpen()) { GlassesHomeController.reopenNav(); return }
        focus = 0; note = ""; results = null
        refresh()
    }

    override fun onLeave(session: GlassesSession) = Unit

    fun refresh() { session?.replaceHud(hud()) }

    /** Show these places in the app, to be picked with the normal focus and tap controls (or by saying their number). */
    fun showResults(session: GlassesSession, query: String, places: List<MymavNav.Place>) {
        this.session = session
        GlassesHomeController.markMiniAppOpen("nav")
        this.query = query; results = places; focus = 0; note = ""
        refresh()
    }

    private fun hud(): GlassesHud.ListApp {
        val res = results
        val recents = MymavNav.recents()
        val rows: List<ListRow>; val pills: List<ListPill>
        if (res != null) {
            rows = res.map { ListRow(it.name, it.name.substringBefore(',').take(30), it.name.substringAfter(',', "").trim().take(34), if (it.meters > 0) MymavNav.fmtDistance(it.meters) else "", HudAccentTone.Teal, it.name.take(1).uppercase()) }
            pills = listOf(ListPill("BACK", HudAccentTone.Blue))
        } else {
            val mode = MymavNav.travel()
            rows = buildList {
                add(ListRow("where", "Where to?", "Say or type a destination", "", HudAccentTone.Teal, "?"))
                add(ListRow("mode", "Travel: ${mode.label}", "Tap to change", "", HudAccentTone.Blue, mode.label.take(1)))
                add(ListRow("dim", "Map: ${if (MymavNav.dim() == MymavNav.Dim.D3) "3D perspective" else "2D top-down"}", "Tap to switch 2D / 3D", "", HudAccentTone.Blue, if (MymavNav.dim() == MymavNav.Dim.D3) "3D" else "2D"))
                add(ListRow("north", "2D direction: ${if (MymavNav.northUp()) "North-up" else "Heading-up"}", "Tap to switch (2D only)", "", HudAccentTone.Blue, if (MymavNav.northUp()) "N" else "H"))
                recents.forEachIndexed { i, r -> add(ListRow("r$i", r.name.substringBefore(',').take(30), "Recent", "", HudAccentTone.Purple, r.name.take(1).uppercase())) }
            }
            pills = if (recents.isNotEmpty()) listOf(ListPill("CLEAR RECENTS", HudAccentTone.Red)) else emptyList()
        }
        val total = rows.size + pills.size
        focus = focus.coerceIn(0, (total - 1).coerceAtLeast(0))
        return GlassesHud.ListApp(
            appId = "nav", title = "Navigation", tone = HudAccentTone.Teal, rows = rows, focusedIndex = focus, pills = pills,
            statusLine = note.ifBlank { if (res != null) "Results for \"${query.take(18)}\"" else "" },
            emptyTitle = "Where to?", emptyHint = "Say: navigate to the station",
            actions = ListAppActions(
                onFocusChanged = { i -> focus = i.coerceIn(0, (total - 1).coerceAtLeast(0)); note = ""; refresh() },
                onSelect = { select(rows, recents) },
                onHomeRequested = { GlassesHomeController.requestHomeFromApp() },
                onGazeNormalized = { x, y -> GlassesHomeController.feedAppHomeGaze(x, y) },
            ),
        )
    }

    private fun select(rows: List<ListRow>, recents: List<MymavNav.Place>) {
        val res = results
        if (res != null) {
            if (focus < res.size) plan(res[focus]) else { results = null; focus = 0; note = ""; refresh() }
            return
        }
        when {
            focus == 0 -> GlassesHomeController.openKeyboard(
                title = "Where to?", doneLabel = "Go",
                onDone = { t -> GlassesHomeController.markMiniAppOpen("nav"); if (t.isNotBlank()) search(t.trim()) else refresh() },
                onCancel = { GlassesHomeController.markMiniAppOpen("nav"); refresh() },
            )
            focus == 1 -> { MymavNav.setTravel(MymavNav.travel().next()); note = "Now ${MymavNav.travel().label.lowercase()}"; refresh() }
            focus == 2 -> { MymavNav.toggleDim(); note = "Map is now ${if (MymavNav.dim() == MymavNav.Dim.D3) "3D" else "2D"}"; refresh() }
            focus == 3 -> { MymavNav.toggleNorthUp(); note = if (MymavNav.northUp()) "2D map: north-up" else "2D map: heading-up"; refresh() }
            focus - 4 in recents.indices -> plan(recents[focus - 4])
            else -> { MymavNav.clearRecents(); note = "Recents cleared"; focus = 0; refresh() }
        }
    }

    /** Search places for typed or dictated text; one match goes straight to the route, several are listed to pick from. */
    fun search(text: String) {
        note = "Searching…"; refresh()
        MymavNav.location { loc, why ->
            if (loc == null) { note = why.take(40).ifBlank { "No location" }; refresh(); return@location }
            Thread {
                val found = runCatching { MymavNav.places(text, loc.latitude, loc.longitude, nearby = false) }
                main.post {
                    found.onFailure { note = "Search failed"; refresh() }
                    found.onSuccess { list ->
                        val top = list.take(5)
                        when {
                            top.isEmpty() -> { note = "Nothing found for \"${text.take(16)}\""; refresh() }
                            top.size == 1 -> plan(top[0])
                            else -> { query = text; results = top; focus = 0; note = ""; refresh() }
                        }
                    }
                }
            }.start()
        }
    }

    /** Plan a route to [p] (phone or glasses); [onError] gets the reason if no route could be made. */
    fun planTo(p: MymavNav.Place, onError: (String) -> Unit) { AssistantNav.plan(p) { out -> if (out.kind == AssistantLog.Kind.Error) onError(out.text) } }

    private fun plan(p: MymavNav.Place) {
        results = null; note = "Finding route…"; refresh()
        AssistantNav.plan(p) { out -> if (out.kind == AssistantLog.Kind.Error) { note = out.text.take(44); refresh() } }
    }
}
