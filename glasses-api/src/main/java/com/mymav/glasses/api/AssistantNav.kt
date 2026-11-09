package com.mymav.glasses.api

/**
 * Voice front-end for [MymavNav]: nearby places, walking directions and stopping. Always asks before starting a walk
 * so a wrong place match never sends the user off in the wrong direction. Provider failures are reported as they are.
 */
internal object AssistantNav {
    private val STOP = Regex("^(?:please )?(?:stop|cancel|end|quit) (?:the )?(?:navigation|directions|navigating|route|walking directions)$")
    private val GO = Regex("^(?:please )?(?:navigate|walk|drive|cycle|bike|ride|take me|directions|give me directions|route|how do i get|get me|show me the way)(?: me)?(?: (?:from here )?(?:to|towards))? (.+)$")
    private val NEARBY = Regex("^(?:please )?(?:find|show|where(?:'?s| is| are)|is there|are there|any|what)(?: me)?(?: the| a| an| any)? ?(?:nearest|closest|nearby|near me|local)? ?(.+?)(?: nearby| near me| around here| close by| near here| open)?$")
    private val NEAR_HINT = Regex("\\b(nearest|closest|nearby|near me|around here|close by|near here)\\b")

    private val CTRL = Regex("^(?:please )?(?:mute|unmute|mute (?:the )?(?:voice|directions|navigation)|unmute (?:the )?(?:voice|directions|navigation)|repeat|repeat (?:that|the instruction|the directions|instruction|directions)|say that again|overview|show (?:the )?overview|minimal|minimal view|minimal mode|recenter|re-?centre|map view|show (?:the )?(?:navigation|map|route)|open (?:the )?(?:navigation|map)|(?:switch|change) to (?:walking|cycling|driving|biking|walk|cycle|drive|bike)|(?:use|go) (?:walking|cycling|driving|biking)|(?:switch|change|set|use|show) (?:to )?(?:the )?(?:2 ?d|two ?d|3 ?d|three ?d|north[- ]up|heading[- ]up)(?: map| view| mode)?|(?:2 ?d|two ?d|3 ?d|three ?d|north[- ]up|heading[- ]up)(?: map| view| mode)?|(?:next|another|alternative|other) route|use (?:km|kilometres|kilometers|miles|mph))$")
    private fun ctrlActive() = MymavNav.mapActive() || MymavNav.previewing()

    private fun control(t: String): String {
        val dimT = t.replace(" ", "").replace("-", "")
        when {
            dimT.contains("northup") -> { MymavNav.setDim(MymavNav.Dim.D2); MymavNav.setNorthUp(true); return "2D map, north-up." }
            dimT.contains("headingup") -> { MymavNav.setDim(MymavNav.Dim.D2); MymavNav.setNorthUp(false); return "2D map, heading-up." }
            dimT.contains("2d") || dimT.contains("twod") -> { MymavNav.setDim(MymavNav.Dim.D2); return "2D map." }
            dimT.contains("3d") || dimT.contains("threed") -> { MymavNav.setDim(MymavNav.Dim.D3); return "3D map." }
            t.startsWith("unmute") -> { MymavNav.setMuted(false); return "Voice directions on." }
            t.startsWith("mute") -> { MymavNav.setMuted(true); return "Voice directions muted." }
            t.startsWith("repeat") || t == "say that again" -> { if (MymavNav.active()) { MymavNav.repeatInstruction(); return "Repeating the instruction." } else return "No journey is running." }
            t.contains("overview") -> { MymavNav.setView(MymavNav.View.Overview); return "Overview." }
            t.contains("minimal") -> { MymavNav.setView(MymavNav.View.Minimal); return "Minimal view." }
            t.contains("recent") || t == "map view" -> { MymavNav.setView(MymavNav.View.Map); return "Map view." }
            t.contains("route") && !t.contains("show") -> { MymavNav.nextAlternative(); return "Next route." }
            t.contains("walk") -> { MymavNav.setTravel(MymavNav.Travel.Walk); return "Switching to walking." }
            t.contains("cycl") || t.contains("bik") -> { MymavNav.setTravel(MymavNav.Travel.Cycle); return "Switching to cycling." }
            t.contains("driv") -> { MymavNav.setTravel(MymavNav.Travel.Drive); return "Switching to driving." }
            t.contains("mile") || t.contains("mph") -> { if (!MymavNav.mph()) MymavNav.toggleUnits(); return "Speed in mph." }
            t.contains("km") || t.contains("kilomet") -> { if (MymavNav.mph()) MymavNav.toggleUnits(); return "Speed in km/h." }
            else -> { GlassesHomeController.reopenNav(); return "Showing navigation." }
        }
    }

    private enum class Stage { Choose, Confirm }
    private class Pending(val places: List<MymavNav.Place>, var picked: MymavNav.Place? = null, var stage: Stage = Stage.Choose, val listOnly: Boolean = false) {
        var route: MymavNav.Route? = null; var startLat = Double.NaN; var startLon = Double.NaN; var simulated = false
        val expires = System.currentTimeMillis() + 3 * 60_000L
    }
    @Volatile private var pending: Pending? = null

    private fun out(t: String, kind: AssistantLog.Kind = AssistantLog.Kind.Local, src: List<AssistantLog.Source> = emptyList(), note: String = "ask") =
        AssistantRouter.Outcome(t, kind, src, note)
    private fun err(t: String) = AssistantRouter.Outcome(t, AssistantLog.Kind.Error, emptyList(), "")
    private val SOURCES = listOf(
        AssistantLog.Source("Places: OpenStreetMap Nominatim", "https://www.openstreetmap.org/copyright"),
        AssistantLog.Source("Route: FOSSGIS OSRM (OpenStreetMap data)", "https://routing.openstreetmap.de/"),
    )

    fun hasPending() = pending?.let { it.expires > System.currentTimeMillis() } == true

    fun isRequest(c: String): Boolean =
        STOP.matches(c) || (ctrlActive() && CTRL.matches(c)) || (GO.matches(c) && !Regex("\\b(work out|get to know|get started)\\b").containsMatchIn(c)) ||
            (NEAR_HINT.containsMatchIn(c) && NEARBY.matches(c))

    fun continuePending(c: String, deliver: (AssistantRouter.Outcome) -> Unit): Boolean {
        val p = pending ?: return false
        if (p.expires < System.currentTimeMillis()) { pending = null; return false }
        val t = c.trim().removeSuffix(".")
        if (t in setOf("no", "cancel", "never mind", "nevermind", "stop")) { pending = null; if (!MymavNav.active()) MymavNav.closePreview(); deliver(out("Okay, I won't start navigation.")); return true }
        when (p.stage) {
            Stage.Choose -> {
                val n = t.removePrefix("number ").removePrefix("take me to ").removePrefix("navigate to ").removePrefix("the ").let { w ->
                    w.toIntOrNull() ?: mapOf("first" to 1, "first one" to 1, "second" to 2, "second one" to 2, "third" to 3, "third one" to 3)[w]
                }
                if (n != null && n in 1..p.places.size) { p.picked = p.places[n - 1]; p.stage = Stage.Confirm; ask(p, deliver); return true }
                pending = null; return false
            }
            Stage.Confirm -> {
                if (t in setOf("yes", "yeah", "yep", "go", "start", "yes start", "go ahead", "yes please", "do it", "confirm")) {
                    pending = null; begin(p.picked!!, deliver, p); return true
                }
                pending = null; return false
            }
        }
    }

    /** Plan a route to [pl] (from the Navigation app or a recent destination): shows the preview map and waits for "yes"/Start. */
    internal fun plan(pl: MymavNav.Place, deliver: (AssistantRouter.Outcome) -> Unit) {
        val p = Pending(listOf(pl), pl, Stage.Confirm)
        pending = p
        ask(p, deliver)
    }

    private fun ask(p: Pending, deliver: (AssistantRouter.Outcome) -> Unit) {
        val pl = p.picked!!
        MymavNav.setPlanning(pl.name)
        // Fetch the real walking route first and show it on the map, so "Walk to X?" is asked over the route itself.
        MymavNav.location { loc, why ->
            if (loc == null) { MymavNav.setPlanning(null); deliver(err(why.ifBlank { "I couldn't get your location." })); return@location }
            Thread {
                val r = runCatching { MymavNav.routes(loc.latitude, loc.longitude, pl.lat, pl.lon, MymavNav.travel()) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    MymavNav.setPlanning(null)
                    r.onSuccess { list ->
                        val route = list[0]
                        p.route = route; p.startLat = loc.latitude; p.startLon = loc.longitude; p.simulated = loc.provider == "simulated"
                        MymavNav.preview(pl.name, pl.lat, pl.lon, list, loc.latitude, loc.longitude, p.simulated)
                        deliver(out("${verb()} to ${pl.name.substringBefore(',')}? ${MymavNav.fmtDistance(route.meters)}, about ${MymavNav.fmtDuration(route.seconds)}.\nSay yes to start, or no.", AssistantLog.Kind.Data, SOURCES, if (MymavNav.onState != null) "map" else "Route from OpenStreetMap data"))
                    }.onFailure { pending = null; deliver(err("I couldn't get a route: ${it.message?.take(80)}.")) }
                }
            }.start()
        }
    }

    private fun verb() = when (MymavNav.travel()) { MymavNav.Travel.Walk -> "Walk"; MymavNav.Travel.Cycle -> "Cycle"; MymavNav.Travel.Drive -> "Drive" }

    private fun begin(pl: MymavNav.Place, deliver: (AssistantRouter.Outcome) -> Unit, p: Pending? = null) {
        val ready = MymavNav.previewSelected() ?: p?.route
        if (ready != null) {
            MymavNav.start(pl.name, pl.lat, pl.lon, ready, p?.startLat ?: Double.NaN, p?.startLon ?: Double.NaN)
            deliver(out("Navigating to ${pl.name.substringBefore(',')}: ${MymavNav.fmtDistance(ready.meters)}, about ${MymavNav.fmtDuration(ready.seconds)}. Say stop navigation to end.", AssistantLog.Kind.Data, SOURCES, if (MymavNav.onState != null) "map" else "Route from OpenStreetMap data"))
            return
        }
        MymavNav.location { loc, why ->
            if (loc == null) { deliver(err(why)); return@location }
            Thread {
                val r = runCatching { MymavNav.route(loc.latitude, loc.longitude, pl.lat, pl.lon, MymavNav.travel()) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    r.onSuccess {
                        deliver(out("Walking to ${pl.name.substringBefore(',')}: ${MymavNav.fmtDistance(it.meters)}, about ${MymavNav.fmtDuration(it.seconds)}. Say stop navigation to end.", AssistantLog.Kind.Data, SOURCES, "Route from OpenStreetMap data"))
                        MymavNav.start(pl.name, pl.lat, pl.lon, it)
                    }.onFailure { deliver(err("I couldn't get a route: ${it.message?.take(80)}.")) }
                }
            }.start()
        }
    }

    fun run(raw: String, c: String, status: (String) -> Unit, deliver: (AssistantRouter.Outcome) -> Unit) {
        val t = c.trim().removeSuffix(".").removePrefix("please ")
        if (STOP.matches(t)) {
            if (MymavNav.active() || MymavNav.previewing()) { pending = null; MymavNav.stop(quiet = true); deliver(out("Navigation stopped.")) } else deliver(out("No navigation is running."))
            return
        }
        if (ctrlActive() && CTRL.matches(t)) { deliver(out(control(t))); return }
        when (t.substringBefore(' ')) { "drive" -> MymavNav.setTravel(MymavNav.Travel.Drive); "cycle", "bike", "ride" -> MymavNav.setTravel(MymavNav.Travel.Cycle); "walk" -> MymavNav.setTravel(MymavNav.Travel.Walk) }
        val go = GO.find(t)?.groupValues?.get(1)?.trim()
        val near = if (go == null) NEARBY.find(t)?.groupValues?.get(1)?.trim() else null
        val query = (go ?: near ?: return).removePrefix("the ").removePrefix("a ")
        val nearby = go == null || NEAR_HINT.containsMatchIn(t)
        status("Finding your location...")
        MymavNav.location { loc, why ->
            if (loc == null || why == "stale" && false) { deliver(err(why.ifBlank { "I couldn't get your location." })); return@location }
            status("Searching places...")
            Thread {
                val found = runCatching { MymavNav.places(query, loc.latitude, loc.longitude, nearby = go == null) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    found.onFailure { deliver(err("I couldn't search places just now (${it.message?.take(70)}).")) }
                    found.onSuccess { list ->
                        val staleNote = if (why == "stale") " (using an older location fix)" else ""
                        if (list.isEmpty()) { deliver(err("I found no \"$query\" ${if (go == null) "within about 4 km" else "near you"}$staleNote.")); return@onSuccess }
                        val top = list.take(4)
                        if (go != null && top.size == 1) {
                            pending = Pending(top, top[0], Stage.Confirm); ask(pending!!, deliver); return@onSuccess
                        }
                        pending = Pending(top)
                        if (go != null && MymavNav.onState != null && GlassesHomeController.showNavResults(query, top)) {
                            deliver(out("Pick a destination in Navigation (tap, or say a number).", AssistantLog.Kind.Data, SOURCES, "map")); return@onSuccess
                        }
                        val lines = top.mapIndexed { i, p -> "${i + 1}. ${p.name.take(48)} - ${MymavNav.fmtDistance(p.meters)}" }
                        deliver(out((if (go == null) "Nearby $query$staleNote:\n" else "Which one?\n") + lines.joinToString("\n") + "\nSay a number to walk there.", AssistantLog.Kind.Data, SOURCES, "Places from OpenStreetMap"))
                    }
                }
            }.start()
        }
    }
}
