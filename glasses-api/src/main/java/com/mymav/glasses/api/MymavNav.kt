package com.mymav.glasses.api

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Real navigation: the phone's actual location, places from OpenStreetMap's Nominatim search and walking routes from
 * the OpenStreetMap-based FOSSGIS "routed-foot" service (OSRM). Directions are never produced by a language model.
 * All of these are public services with no key; they can be slow, rate-limited or unavailable, and then we say so.
 */
object MymavNav {
    class Place(val name: String, val lat: Double, val lon: Double, val meters: Double)
    class Step(val text: String, val atLat: Double, val atLon: Double, val meters: Double, val geom: List<DoubleArray>, val kind: String = "", val street: String = "")
    class Route(val steps: List<Step>, val meters: Double, val seconds: Double) {
        /** The whole walk as one lat/lon polyline, and the polyline index where each step begins. */
        val polyline: List<DoubleArray>
        val stepStart: IntArray
        init {
            val pts = ArrayList<DoubleArray>(); val starts = IntArray(steps.size)
            steps.forEachIndexed { i, st ->
                starts[i] = pts.size
                st.geom.forEach { g -> val l = pts.lastOrNull(); if (l == null || l[0] != g[0] || l[1] != g[1]) pts += g }
            }
            polyline = pts; stepStart = starts
        }
    }

    enum class Mode { Preview, Follow, Arrived }
    enum class Travel(val label: String, val urlProfile: String, val host: String) {
        Walk("Walking", "foot", "routed-foot"), Cycle("Cycling", "bike", "routed-bike"), Drive("Driving", "driving", "routed-car");
        fun next() = values()[(ordinal + 1) % values().size]
    }
    enum class Gps { Ok, Poor, Lost }
    /** Map = follow camera, Overview = whole route from above, Minimal = just the next turn. */
    enum class View { Map, Overview, Minimal }
    /** Map presentation, independent of [View]: D3 = perspective, D2 = flat top-down. */
    enum class Dim { D3, D2 }
    /** Everything a map needs to draw one moment of a journey. Public so the glasses HUD and the phone share it. */
    data class NavState(
        val mode: Mode, val destName: String, val destLat: Double, val destLon: Double, val route: List<DoubleArray>,
        val lat: Double, val lon: Double, val heading: Double, val fromIdx: Int,
        val maneuver: String, val instruction: String, val toNextM: Double, val remainingM: Double, val remainingS: Double,
        val simulated: Boolean, val notice: String, val at: Long = System.currentTimeMillis(),
        val street: String = "", val travel: Travel = Travel.Walk, val speedMps: Double = Double.NaN, val gps: Gps = Gps.Ok,
        val rerouting: Boolean = false, val offline: Boolean = false, val muted: Boolean = false, val mph: Boolean = false,
        val view: View = View.Map, val altIndex: Int = 0, val altCount: Int = 1, val etaMs: Long = 0L,
        val dim: Dim = Dim.D3, val northUp: Boolean = false,
    )

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state
    /** The glasses map: set by the controller. When set, turn updates go to the map instead of the speech bubble. */
    @Volatile var onState: ((NavState?) -> Unit)? = null
    /** Debug/test only: a fixed pretend location (clearly labelled "simulated" wherever it is shown). */
    @Volatile var simLoc: Location? = null
    private fun deco(st: NavState): NavState = st.copy(
        travel = travel(), muted = muted(), mph = mph(), view = view, dim = dim(), northUp = northUp(),
        altIndex = pv?.idx ?: 0, altCount = pv?.routes?.size ?: 1,
        etaMs = System.currentTimeMillis() + (st.remainingS * 1000).toLong(),
    )
    private fun publish(st: NavState?) { val d = st?.let { deco(it) }; _state.value = d; onState?.invoke(d); onWidget?.invoke() }
    private fun showTurn(text: String, st: NavState) { val d = deco(st); _state.value = d; val f = onState; if (f != null) f(d) else onText?.invoke(text); onWidget?.invoke() }

    /** Told whenever the journey changes, so the Home widget (and anything else showing it) can redraw from the same state. */
    @Volatile var onWidget: (() -> Unit)? = null
    /** Destination whose route is being fetched right now (the Home widget shows "Finding route..."). */
    @Volatile var planningTo: String? = null
        private set
    fun setPlanning(name: String?) { planningTo = name; onWidget?.invoke() }
    /** Bumped to ask the phone to show the full-screen navigation (it was hidden with Home, or the app was opened). */
    val phoneOpen = MutableStateFlow(0)
    fun requestPhoneOpen() { phoneOpen.value = phoneOpen.value + 1 }

    // ---- recent destinations ---------------------------------------------------------------------------------
    fun recents(): List<Place> = runCatching {
        val arr = JSONArray(prefs()?.getString("recents", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Place(o.getString("n"), o.getDouble("a"), o.getDouble("o"), 0.0) } }
    }.getOrDefault(emptyList())
    fun addRecent(name: String, lat: Double, lon: Double) {
        val keep = recents().filter { it.name != name }.take(5)
        val arr = JSONArray()
        (listOf(Place(name, lat, lon, 0.0)) + keep).forEach { arr.put(JSONObject().put("n", it.name).put("a", it.lat).put("o", it.lon)) }
        prefs()?.edit()?.putString("recents", arr.toString())?.apply()
    }
    fun clearRecents() { prefs()?.edit()?.remove("recents")?.apply() }

    // ---- user preferences and controls -------------------------------------------------------------------------
    private fun prefs() = appContext?.getSharedPreferences("mymav_nav", Context.MODE_PRIVATE)
    fun travel(): Travel = runCatching { Travel.valueOf(prefs()?.getString("travel", "Walk") ?: "Walk") }.getOrDefault(Travel.Walk)
    fun muted(): Boolean = prefs()?.getBoolean("muted", false) ?: false
    fun mph(): Boolean = prefs()?.getBoolean("mph", false) ?: false
    @Volatile private var viewMode: View = View.Map
    val view: View get() = viewMode
    private fun refresh() { main.post { val s = session; val p = pv; if (s != null && !s.lat.isNaN()) publish(stateOf(s, s.lat, s.lon)) else if (p != null) publish(previewState(p)) } }
    fun setView(v: View) { viewMode = v; refresh() }
    fun cycleView() { setView(View.values()[(view.ordinal + 1) % View.values().size]) }
    fun setMuted(m: Boolean) { prefs()?.edit()?.putBoolean("muted", m)?.apply(); if (m) MymavSpeech.stop(); refresh() }
    fun toggleMute() = setMuted(!muted())
    /** 2D / 3D map presentation and the 2D orientation; both survive restarts and never touch the journey. */
    fun dim(): Dim = runCatching { Dim.valueOf(prefs()?.getString("dim", "D3") ?: "D3") }.getOrDefault(Dim.D3)
    fun northUp(): Boolean = prefs()?.getBoolean("northUp", false) ?: false
    fun setDim(d: Dim) { prefs()?.edit()?.putString("dim", d.name)?.apply(); refresh() }
    fun toggleDim() = setDim(if (dim() == Dim.D3) Dim.D2 else Dim.D3)
    fun setNorthUp(n: Boolean) { prefs()?.edit()?.putBoolean("northUp", n)?.apply(); refresh() }
    fun toggleNorthUp() = setNorthUp(!northUp())
    fun toggleUnits() { prefs()?.edit()?.putBoolean("mph", !mph())?.apply(); refresh() }
    fun speedText(mps: Double, mph: Boolean = mph()): String =
        if (mps.isNaN()) "--" else if (mph) "${Math.round(mps * 2.23694)} mph" else "${Math.round(mps * 3.6)} km/h"
    /** Say the current instruction again (works even when voice is muted, because it was asked for). */
    fun repeatInstruction() {
        val s = session ?: return
        if (s.lat.isNaN() || System.currentTimeMillis() - s.lastFixAt > 15_000L && !s.simulated) { MymavSpeech.speak("I don't have a GPS fix right now."); return }
        val st = stateOf(s, s.lat, s.lon)
        MymavSpeech.speak("${st.instruction}, in ${spokenDist(st.toNextM)}.")
    }
    private fun spokenDist(m: Double): String = if (m >= 950) String.format(java.util.Locale.UK, "%.1f kilometres", m / 1000) else "${((m / 10).toInt() * 10).coerceAtLeast(10)} metres"
    private fun say(t: String) { if (!muted()) MymavSpeech.speak(t) }

    @Volatile private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun install(context: Context) { appContext = context.applicationContext }

    fun contextOrNull(): Context? = appContext
    fun hasPermission(): Boolean = appContext?.let {
        it.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            it.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } ?: false

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    fun fmtDistance(m: Double): String = if (m >= 1000) String.format(java.util.Locale.UK, "%.1f km", m / 1000) else "${(m / 10).toInt() * 10} m"
    fun fmtDuration(s: Double): String { val min = (s / 60).toInt().coerceAtLeast(1); return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min" }

    // ---- location ----------------------------------------------------------------------------------------------

    /** Calls back on the main thread with a fix no older than [maxAgeMs], or null and a reason. */
    @SuppressLint("MissingPermission")
    fun location(maxAgeMs: Long = 120_000L, done: (Location?, String) -> Unit) {
        simLoc?.let { sl -> main.post { done(sl, "") }; return }
        val c = appContext
        if (c == null || !hasPermission()) { main.post { done(null, "Location permission is off. Open MyMav Settings and tap Allow location.") }; return }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { main.post { done(null, "Location services are switched off on this phone.") }; return }
        val best = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (best != null && System.currentTimeMillis() - best.time <= maxAgeMs) { main.post { done(best, "") }; return }
        var finished = false
        val listeners = ArrayList<LocationListener>()
        fun finish(l: Location?, why: String) {
            if (finished) return
            finished = true
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            done(l, why)
        }
        providers.filter { it != LocationManager.PASSIVE_PROVIDER }.forEach { p ->
            val li = LocationListener { loc -> main.post { finish(loc, "") } }
            listeners += li
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, li, Looper.getMainLooper()) }
        }
        main.postDelayed({
            val stale = best
            if (stale != null) finish(stale, "stale") else finish(null, "I couldn't get a location fix. Try again outdoors or with location on.")
        }, 12_000L)
    }

    // ---- places and routes ---------------------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Places matching [query] near ([lat],[lon]). [nearby] limits results to about 4 km. */
    fun places(query: String, lat: Double, lon: Double, nearby: Boolean): List<Place> {
        val d = if (nearby) 0.04 else 0.25
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=${enc(query)}" +
            "&viewbox=${lon - d},${lat + d},${lon + d},${lat - d}" + if (nearby) "&bounded=1" else ""
        val arr = JSONArray(MymavHttp.request("GET", url, timeoutMs = 15_000))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val la = o.optString("lat").toDoubleOrNull() ?: continue
            val lo = o.optString("lon").toDoubleOrNull() ?: continue
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(',') }
            val area = full.split(',').drop(1).take(2).joinToString(",").trim()
            out += Place(if (area.isBlank()) name else "$name, $area", la, lo, distance(lat, lon, la, lo))
        }
        return out.sortedBy { it.meters }.distinctBy { it.name }
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, travel: Travel = Travel.Walk): Route =
        routes(fromLat, fromLon, toLat, toLon, travel, alternatives = false).first()

    /** The best route first, then alternatives the routing service offers (usually 0-2). */
    fun routes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, travel: Travel = Travel.Walk, alternatives: Boolean = true): List<Route> {
        val url = "https://routing.openstreetmap.de/${travel.host}/route/v1/${travel.urlProfile}/$fromLon,$fromLat;$toLon,$toLat?steps=true&geometries=geojson&overview=false" + if (alternatives) "&alternatives=true" else ""
        val j = JSONObject(MymavHttp.request("GET", url, timeoutMs = 20_000))
        if (j.optString("code") != "Ok") throw MymavHttp.Failure(0, "no ${travel.label.lowercase()} route found (${j.optString("code")})")
        val arr = j.getJSONArray("routes")
        val all = (0 until arr.length()).map { parseRoute(arr.getJSONObject(it)) }
        val limitKm = when (travel) { Travel.Walk -> 40.0; Travel.Cycle -> 250.0; Travel.Drive -> 1000.0 }
        val km = all.first().meters / 1000.0
        if (km > limitKm) throw MymavHttp.Failure(0, "${"%,.0f".format(km)} km by ${travel.label.lowercase()} is too far" + if (travel != Travel.Drive) ", try driving" else "")
        return all
    }

    private fun parseRoute(r: JSONObject): Route {
        val stepsJ = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val steps = ArrayList<Step>()
        for (i in 0 until stepsJ.length()) {
            val s = stepsJ.getJSONObject(i)
            val m = s.getJSONObject("maneuver")
            val loc = m.getJSONArray("location")
            val coords = s.getJSONObject("geometry").getJSONArray("coordinates")
            val g = (0 until coords.length()).map { k -> coords.getJSONArray(k).let { doubleArrayOf(it.getDouble(1), it.getDouble(0)) } }
            steps += Step(instruction(m, s.optString("name")), loc.getDouble(1), loc.getDouble(0), s.optDouble("distance"), g, kindOf(m), s.optString("name"))
        }
        return Route(steps, r.optDouble("distance"), r.optDouble("duration"))
    }

    private fun kindOf(m: JSONObject): String {
        val type = m.optString("type"); val mod = m.optString("modifier")
        return when {
            type == "arrive" -> "arrive"
            type == "depart" -> "depart"
            type == "roundabout" || type == "rotary" -> "roundabout"
            mod.isBlank() -> "straight"
            else -> mod   // left, right, slight left, sharp right, straight, uturn
        }
    }

    private fun instruction(m: JSONObject, name: String): String {
        val type = m.optString("type"); val mod = m.optString("modifier")
        val road = if (name.isBlank()) "" else " onto $name"
        return when (type) {
            "depart" -> "Head ${if (mod.isBlank()) "off" else mod}" + if (name.isBlank()) "" else " on $name"
            "arrive" -> "Arrive at your destination"
            "turn", "end of road", "fork", "off ramp", "on ramp" -> (if (mod == "straight") "Go straight" else "Turn ${mod.ifBlank { "ahead" }}") + road
            "roundabout", "rotary" -> "At the roundabout take exit ${m.optInt("exit", 1)}$road"
            "new name", "continue" -> "Continue" + if (name.isBlank()) "" else " on $name"
            else -> "Continue" + if (name.isBlank()) "" else " on $name"
        }
    }

    // ---- active navigation ------------------------------------------------------------------------------------

    @Volatile var onText: ((String) -> Unit)? = null
    @Volatile private var session: Session? = null
    fun active() = session != null
    /** True while a preview or a walk is on screen. */
    fun mapActive() = _state.value != null

    private class Session(val destName: String, val destLat: Double, val destLon: Double, var route: Route, var travel: Travel) {
        var idx = 0
        var pIdx = 0                       // nearest polyline vertex still ahead
        var heading = Double.NaN
        var lat = Double.NaN; var lon = Double.NaN
        var simulated = false
        var lastFixAt = System.currentTimeMillis()
        var offCount = 0
        var lastReroute = 0L
        var notice = ""
        var noticeAt = 0L
        var listener: LocationListener? = null
        var watchdog: Runnable? = null
        var rerouting = false
        var offline = false
        var acc = 0f
        var speed = Double.NaN
        var prevLat = Double.NaN; var prevLon = Double.NaN; var prevAt = 0L
        var lostSpoken = false
        val spoken = HashSet<Int>()
    }

    /** A route shown on the map before the journey starts. */
    private class Pv(val name: String, val dLat: Double, val dLon: Double, val sLat: Double, val sLon: Double, val sim: Boolean, var routes: List<Route>, var idx: Int = 0, var busy: Boolean = false, var notice: String = "Say yes to start")
    @Volatile private var pv: Pv? = null
    fun previewing() = pv != null
    fun previewSelected(): Route? = pv?.let { it.routes.getOrNull(it.idx) }

    private fun previewState(p: Pv): NavState {
        val route = p.routes[p.idx]
        val poly = route.polyline
        val first = route.steps.getOrNull(1) ?: route.steps.firstOrNull()
        val hdg = if (poly.size >= 2) NavScene.bearing(poly.first()[0], poly.first()[1], poly[minOf(3, poly.size - 1)][0], poly[minOf(3, poly.size - 1)][1]) else 0.0
        return NavState(Mode.Preview, p.name, p.dLat, p.dLon, poly, p.sLat, p.sLon, hdg, 0,
            first?.kind.orEmpty(), first?.text.orEmpty(), first?.let { distance(p.sLat, p.sLon, it.atLat, it.atLon) } ?: 0.0, route.meters, route.seconds, p.sim,
            if (p.busy) "Finding a ${travel().label.lowercase()} route..." else p.notice, street = first?.street.orEmpty(), rerouting = p.busy)
    }

    /** Show the route on the map before the journey (the user is still being asked to confirm). */
    fun preview(destName: String, destLat: Double, destLon: Double, routes: List<Route>, startLat: Double, startLon: Double, simulated: Boolean) {
        val p = Pv(destName, destLat, destLon, startLat, startLon, simulated, routes)
        pv = p
        viewMode = View.Map
        NavMapData.ensureRoute(p.routes[0].polyline)
        publish(previewState(p))
    }

    fun closePreview() { if (pv != null) { pv = null; if (session == null) publish(null) } }

    /** Switch to the next alternative route (preview only: the routing service offers alternatives before you set off). */
    fun nextAlternative() {
        val p = pv ?: return
        if (p.routes.size < 2) { p.notice = "No alternative route available"; refresh(); return }
        p.idx = (p.idx + 1) % p.routes.size
        p.notice = "Route ${p.idx + 1} of ${p.routes.size} - tap to start"
        NavMapData.ensureRoute(p.routes[p.idx].polyline)
        refresh()
    }

    /** Change travel mode and fetch a new route for it. If that fails the mode and route stay as they were. */
    fun setTravel(t: Travel) {
        val s = session; val p = pv
        if (t == travel() && (s != null || p != null)) return
        val prev = travel()
        if (s == null && p == null) { prefs()?.edit()?.putString("travel", t.name)?.apply(); return }
        val fromLat = s?.lat?.takeUnless { it.isNaN() } ?: p?.sLat ?: return
        val fromLon = s?.lon?.takeUnless { it.isNaN() } ?: p?.sLon ?: return
        val dLat = s?.destLat ?: p!!.dLat; val dLon = s?.destLon ?: p!!.dLon
        prefs()?.edit()?.putString("travel", t.name)?.apply()
        if (s != null) { s.rerouting = true; s.travel = t } else p!!.busy = true
        refresh()
        Thread {
            val r = runCatching { routes(fromLat, fromLon, dLat, dLon, t, alternatives = s == null) }
            main.post {
                r.onSuccess { list ->
                    if (s != null) {
                        if (session !== s) return@post
                        s.route = list[0]; s.idx = 0; s.pIdx = 0; s.heading = Double.NaN; s.rerouting = false; s.offline = false; s.spoken.clear()
                        NavMapData.ensureRoute(s.route.polyline)
                        s.notice = "Now ${t.label.lowercase()}"; s.noticeAt = System.currentTimeMillis()
                        say("Now ${t.label.lowercase()}. ${list[0].steps.getOrNull(1)?.text.orEmpty()}")
                        publish(stateOf(s, s.lat, s.lon))
                    } else if (pv === p) {
                        p!!.routes = list; p.idx = 0; p.busy = false; p.notice = "${t.label} route - tap to start"
                        NavMapData.ensureRoute(list[0].polyline)
                        publish(previewState(p))
                    }
                }.onFailure {
                    // keep the old mode and the old route; say why
                    prefs()?.edit()?.putString("travel", prev.name)?.apply()
                    if (s != null) {
                        if (session !== s) return@post
                        s.travel = prev; s.rerouting = false; s.offline = true
                        s.notice = "Can't switch to ${t.label.lowercase()}: ${it.message?.take(40)}"; s.noticeAt = System.currentTimeMillis()
                        publish(stateOf(s, s.lat, s.lon))
                    } else if (pv === p) {
                        p!!.busy = false; p.notice = "Can't switch to ${t.label.lowercase()}: ${it.message?.take(40)}"
                        publish(previewState(p))
                    }
                }
            }
        }.start()
    }
    fun cycleTravel() = setTravel(travel().next())

    @SuppressLint("MissingPermission")
    fun start(destName: String, destLat: Double, destLon: Double, route: Route, startLat: Double = Double.NaN, startLon: Double = Double.NaN) {
        stop(quiet = true, keepMap = true)
        pv = null
        viewMode = View.Map
        val c = appContext ?: return
        val s = Session(destName, destLat, destLon, route, travel())
        session = s
        planningTo = null
        addRecent(destName, destLat, destLon)
        NavMapData.ensureRoute(route.polyline)
        val sim = simLoc
        if (!startLat.isNaN()) { s.lat = startLat; s.lon = startLon }
        s.simulated = sim != null
        if (!s.lat.isNaN()) { NavMapData.ensure(s.lat, s.lon); showTurn("Starting ${s.travel.label.lowercase()} to ${destName.substringBefore(',')}.", stateOf(s, s.lat, s.lon).copy(notice = "Waiting for location")) }
        say("Starting navigation to ${destName.substringBefore(',')}. ${route.steps.getOrNull(1)?.text.orEmpty()}")
        if (sim == null) {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val li = LocationListener { loc -> main.post { onFix(s, loc) } }
            s.listener = li
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                .forEach { runCatching { lm.requestLocationUpdates(it, 1000L, 2f, li, Looper.getMainLooper()) } }
        }
        val dog = object : Runnable {
            override fun run() {
                if (session !== s) return
                if (!s.simulated && System.currentTimeMillis() - s.lastFixAt > 15_000L) {
                    s.notice = "GPS signal lost"; s.noticeAt = System.currentTimeMillis()
                    if (!s.lostSpoken) { s.lostSpoken = true; say("GPS signal lost.") }
                    if (!s.lat.isNaN()) showTurn("Waiting for GPS...\nNo location update for 15 s, so the directions are paused.", stateOf(s, s.lat, s.lon))
                    else onText?.invoke("Waiting for GPS...")
                }
                main.postDelayed(this, 5_000L)
            }
        }
        s.watchdog = dog
        main.postDelayed(dog, 15_000L)
        if (s.lat.isNaN()) onText?.invoke("Starting navigation to ${destName.substringBefore(',')}.\n${fmtDistance(route.meters)}, about ${fmtDuration(route.seconds)}.\nWaiting for your location...")
        else if (onState == null) onText?.invoke("Starting navigation to ${destName.substringBefore(',')}.\n${fmtDistance(route.meters)}, about ${fmtDuration(route.seconds)}.")
    }

    fun stop(quiet: Boolean = false, keepMap: Boolean = false) {
        val s = session
        session = null
        walker?.let { main.removeCallbacks(it) }; walker = null
        if (s != null) {
            val c = appContext
            if (c != null) runCatching { s.listener?.let { (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
            s.watchdog?.let { main.removeCallbacks(it) }
            MymavSpeech.stop()
        }
        if (!keepMap) pv = null
        if (!keepMap && _state.value != null) publish(null)
        if (s != null && !quiet) onText?.invoke("Navigation stopped.")
    }

    /** Debug/test only: feed one pretend fix into the running walk (or just set the pretend location). */
    fun simulateFix(lat: Double, lon: Double, bearing: Float = Float.NaN, accuracy: Float = 5f) {
        val l = Location("simulated").apply { latitude = lat; longitude = lon; time = System.currentTimeMillis(); this.accuracy = accuracy; if (!bearing.isNaN()) this.bearing = bearing }
        simLoc = l
        val s = session ?: return
        s.simulated = true
        main.post { onFix(s, l) }
    }

    fun clearSimulation() { simLoc = null }
    fun haltWalker() { walker?.let { main.removeCallbacks(it) }; walker = null }
    /** Test only: behave as if GPS has been lost (simulated journeys otherwise never lose their pretend fix). */
    @Volatile private var forceLost = false
    @Volatile private var forceReroute = false
    /** Debug/test only: show the Rerouting state (the real reroute finishes too quickly to observe). */
    fun debugReroute(on: Boolean) { forceReroute = on; refresh() }
    fun debugLost(on: Boolean) { forceLost = on; refresh() }

    /** Pretend to walk the current route at about [mps] metres per second (test only). */
    @Volatile private var walker: Runnable? = null
    fun simulateWalk(mps: Double = 12.0, tickMs: Long = 1000L) {
        val s = session ?: return
        val poly = s.route.polyline
        if (poly.size < 2) return
        walker?.let { main.removeCallbacks(it) }
        var seg = 0; var along = 0.0
        val r = object : Runnable {
            override fun run() {
                if (session !== s) return
                var left = mps * tickMs / 1000.0
                while (seg < poly.size - 1) {
                    val len = distance(poly[seg][0], poly[seg][1], poly[seg + 1][0], poly[seg + 1][1])
                    if (along + left < len) { along += left; break }
                    left -= (len - along); along = 0.0; seg++
                }
                val a = poly[minOf(seg, poly.size - 1)]; val b = poly[minOf(seg + 1, poly.size - 1)]
                val len = distance(a[0], a[1], b[0], b[1]).coerceAtLeast(0.01)
                val t = (along / len).coerceIn(0.0, 1.0)
                simulateFix(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                if (seg < poly.size - 1) main.postDelayed(this, tickMs)
            }
        }
        walker = r
        main.post(r)
    }

    private fun segDist(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val k = cos(Math.toRadians(p[0])) * 111320.0
        val px = p[1] * k; val py = p[0] * 110540.0
        val ax = a[1] * k; val ay = a[0] * 110540.0
        val bx = b[1] * k; val by = b[0] * 110540.0
        val dx = bx - ax; val dy = by - ay
        val t = if (dx == 0.0 && dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val ex = ax + t * dx - px; val ey = ay + t * dy - py
        return sqrt(ex * ex + ey * ey)
    }

    /** Distance from the walker to the route near the current step. */
    private fun offRoute(s: Session, lat: Double, lon: Double): Double {
        val p = doubleArrayOf(lat, lon)
        val poly = s.route.polyline
        var best = Double.MAX_VALUE
        val a = maxOf(0, s.pIdx - 1); val b = minOf(poly.size - 1, s.pIdx + 40)
        for (k in a until b) best = minOf(best, segDist(p, poly[k], poly[k + 1]))
        if (poly.size == 1) best = distance(lat, lon, poly[0][0], poly[0][1])
        return best
    }

    /** Move the "nearest vertex ahead" marker along the polyline and refresh the smoothed heading. */
    private fun track(s: Session, lat: Double, lon: Double) {
        val poly = s.route.polyline
        if (poly.size < 2) return
        var best = Double.MAX_VALUE; var bi = s.pIdx
        for (k in s.pIdx..minOf(poly.size - 1, s.pIdx + 25)) { val d = distance(lat, lon, poly[k][0], poly[k][1]); if (d < best) { best = d; bi = k } }
        s.pIdx = bi
        val ahead = poly[minOf(poly.size - 1, bi + 2)]
        val to = if (distance(lat, lon, ahead[0], ahead[1]) > 4) NavScene.bearing(lat, lon, ahead[0], ahead[1]) else NavScene.bearing(poly[bi][0], poly[bi][1], poly[minOf(poly.size - 1, bi + 3)][0], poly[minOf(poly.size - 1, bi + 3)][1])
        s.heading = if (s.heading.isNaN()) to else (s.heading + NavScene.angleDiff(s.heading, to) * 0.45 + 360.0) % 360.0
    }

    private fun stateOf(s: Session, lat: Double, lon: Double): NavState {
        val steps = s.route.steps
        val next = steps[(s.idx + 1).coerceAtMost(steps.size - 1)]
        val toNext = distance(lat, lon, next.atLat, next.atLon)
        var remaining = toNext
        for (j in s.idx + 1 until steps.size - 1) remaining += steps[j].meters
        val secPerM = s.route.seconds / s.route.meters.coerceAtLeast(1.0)
        val now = System.currentTimeMillis()
        val lost = forceLost || (!s.simulated && now - s.lastFixAt > 15_000L)
        val gps = when { lost -> Gps.Lost; s.acc > 50f -> Gps.Poor; else -> Gps.Ok }
        val notice = when {
            lost -> "GPS signal lost - directions paused"
            s.rerouting -> "Finding a new route..."
            s.offline -> s.notice.ifBlank { "No connection - using the saved route" }
            now - s.noticeAt < 8000L -> s.notice
            gps == Gps.Poor -> "Weak GPS - position may be off"
            else -> ""
        }
        val speed = if (gps != Gps.Ok || s.speed.isNaN() || s.speed > 80.0) Double.NaN else s.speed
        return NavState(Mode.Follow, s.destName, s.destLat, s.destLon, s.route.polyline, lat, lon, if (s.heading.isNaN()) 0.0 else s.heading, s.pIdx,
            next.kind, next.text, toNext, remaining, remaining * secPerM, s.simulated, notice, street = next.street, travel = s.travel, speedMps = speed, gps = gps, rerouting = s.rerouting || forceReroute, offline = s.offline)
    }

    private fun thresholds(t: Travel): DoubleArray = when (t) { Travel.Walk -> doubleArrayOf(100.0, 30.0); Travel.Cycle -> doubleArrayOf(200.0, 50.0); Travel.Drive -> doubleArrayOf(500.0, 150.0, 40.0) }

    /** Spoken guidance: once per distance band per turn. */
    private fun announce(s: Session, st: NavState) {
        if (s.rerouting || st.gps == Gps.Lost) return
        val th = thresholds(s.travel)
        var hit = -1
        for (i in th.indices.reversed()) if (st.toNextM <= th[i]) { hit = i; break }   // closest band reached
        if (hit < 0) return
        val key = s.idx * 10 + hit
        if (key in s.spoken) return
        for (i in 0..hit) s.spoken += s.idx * 10 + i
        val text = if (hit == th.size - 1) st.instruction else "In ${spokenDist(th[hit])}, ${st.instruction.replaceFirstChar { it.lowercase() }}"
        say(text)
    }

    private fun onFix(s: Session, loc: Location) {
        if (session !== s) return
        val now = System.currentTimeMillis()
        s.lastFixAt = now; s.lostSpoken = false
        s.acc = if (loc.hasAccuracy()) loc.accuracy else 10f
        // speed: from the fix when the phone provides one, else from the distance between fixes; stationary jitter reads as 0
        var v = Double.NaN
        val moved = if (s.prevLat.isNaN()) 99.0 else distance(s.prevLat, s.prevLon, loc.latitude, loc.longitude)
        if (loc.hasSpeed() && loc.provider != "simulated") v = loc.speed.toDouble()
        else if (!s.prevLat.isNaN() && now - s.prevAt >= 500L) {
            // a gap longer than 6 s (tunnel, lost fix, halted updates) has no honest instantaneous speed: unavailable, not an average over the gap
            if (now - s.prevAt <= 6000L) v = moved / ((now - s.prevAt) / 1000.0) else s.speed = Double.NaN
        }
        // Genuine sensor speeds are shown as reported (a walker who is really on a bus is going 40 km/h). What is rejected is an
        // unreliable fix: a derived speed from a position jump (implied > 70 m/s, or a leap far beyond the fix accuracy that the
        // previous speed does not explain) is "unavailable" for that sample, never clamped to a per-mode limit.
        val genuine = loc.hasSpeed() && loc.provider != "simulated"
        if (!v.isNaN() && !genuine) {
            val jump = moved > 4.0 * s.acc + 30.0 && (s.speed.isNaN() || Math.abs(v - s.speed) > 12.0)
            if (v > 70.0 || jump) { Log.w("MymavNav", "location jump rejected: moved=${moved.toInt()} m, implied ${"%.1f".format(v)} m/s, acc=${s.acc.toInt()} m"); v = Double.NaN; s.speed = Double.NaN }
        }
        if (!v.isNaN()) {
            val still = v < 0.6 || (loc.provider != "simulated" && moved < maxOf(3.0, s.acc * 0.5) && !loc.hasSpeed())
            s.speed = if (still) 0.0 else if (s.speed.isNaN() || s.speed == 0.0) v else s.speed * 0.6 + v * 0.4
        }
        if (s.prevLat.isNaN() || now - s.prevAt >= 500L) { s.prevLat = loc.latitude; s.prevLon = loc.longitude; s.prevAt = now }
        s.lat = loc.latitude; s.lon = loc.longitude
        NavMapData.ensure(loc.latitude, loc.longitude)
        val steps = s.route.steps
        // Arrived?
        if (distance(loc.latitude, loc.longitude, s.destLat, s.destLon) < (if (s.travel == Travel.Drive) 40 else 25)) {
            stop(quiet = true, keepMap = true)
            val st = NavState(Mode.Arrived, s.destName, s.destLat, s.destLon, s.route.polyline, loc.latitude, loc.longitude, if (s.heading.isNaN()) 0.0 else s.heading, s.route.polyline.size - 1,
                "arrive", "You have arrived at ${s.destName.substringBefore(',')}", 0.0, 0.0, 0.0, s.simulated, "", travel = s.travel)
            say("You have arrived at ${s.destName.substringBefore(',')}.")
            showTurn("You have arrived at ${s.destName.substringBefore(',')}.", st)
            main.postDelayed({ if (_state.value?.mode == Mode.Arrived) publish(null) }, 12000L)
            return
        }
        // Advance past maneuvers we have reached.
        val reach = if (s.travel == Travel.Drive) 35 else 25
        while (s.idx + 1 < steps.size - 1 && distance(loc.latitude, loc.longitude, steps[s.idx + 1].atLat, steps[s.idx + 1].atLon) < reach) s.idx++
        track(s, loc.latitude, loc.longitude)
        // Off route? Re-route (not more than once per 20 s, after two off-route fixes in a row).
        val off = offRoute(s, loc.latitude, loc.longitude)
        if (off > (if (s.travel == Travel.Drive) 80 else 60)) s.offCount++ else s.offCount = 0
        if (s.offCount >= 2 && !s.rerouting && System.currentTimeMillis() - s.lastReroute > 20_000L) {
            s.lastReroute = System.currentTimeMillis(); s.offCount = 0
            s.rerouting = true
            say("Rerouting.")
            showTurn("Off route, finding a new way...", stateOf(s, loc.latitude, loc.longitude))
            Thread {
                val r = runCatching { route(loc.latitude, loc.longitude, s.destLat, s.destLon, s.travel) }
                main.post {
                    if (session !== s) return@post
                    s.rerouting = false
                    r.onSuccess {
                        s.route = it; s.idx = 0; s.pIdx = 0; s.heading = Double.NaN; s.offline = false; s.spoken.clear()
                        NavMapData.ensureRoute(it.polyline)
                        s.notice = "New route found"; s.noticeAt = System.currentTimeMillis()
                        showTurn("New route found.", stateOf(s, s.lat, s.lon))
                    }.onFailure {
                        s.offline = true
                        s.notice = "No connection - can't find a new route"; s.noticeAt = System.currentTimeMillis()
                        showTurn("I couldn't re-route (${it.message?.take(60)}). The saved route may be wrong now.", stateOf(s, s.lat, s.lon))
                    }
                }
            }.start()
            return
        }
        val st = stateOf(s, loc.latitude, loc.longitude)
        announce(s, st)
        val total = s.route.meters.coerceAtLeast(1.0)
        val pct = ((1 - st.remainingM / total) * 100).toInt().coerceIn(0, 99)
        val ageNote = if (loc.time > 0 && System.currentTimeMillis() - loc.time > 60_000L) "\n(location is ${(System.currentTimeMillis() - loc.time) / 60000} min old)" else ""
        showTurn("${st.instruction}\nin ${fmtDistance(st.toNextM)}\nRoute $pct% - ${fmtDistance(st.remainingM)} left$ageNote", st)
    }
}
