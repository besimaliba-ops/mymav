package com.mymav.glasses.mav2.hud

import android.util.Log
import com.everysight.mav2.sdk.uikit.base.M2Drawable
import com.everysight.mav2.sdk.uikit.data.M2LineStyle
import com.everysight.mav2.sdk.uikit.data.M2PenShape
import com.everysight.mav2.sdk.uikit.data.M2Touch
import com.everysight.mav2.sdk.uikit.drawables.M2EllipseFilled
import com.everysight.mav2.sdk.uikit.drawables.M2Path
import com.everysight.mav2.sdk.uikit.drawables.M2RectFilled
import com.everysight.mav2.sdk.uikit.drawables.M2RectOutline
import com.everysight.mav2.sdk.uikit.drawables.M2Triangles
import com.everysight.mav2.sdk.uikit.drawables.ext.setDimensions
import com.everysight.mav2.sdk.utils.M2Color
import com.mymav.glasses.api.GlassesHud
import com.mymav.glasses.api.MymavNav
import com.mymav.glasses.api.MymavNav.Dim
import com.mymav.glasses.api.MymavNav.Gps
import com.mymav.glasses.api.MymavNav.Mode
import com.mymav.glasses.api.MymavNav.View
import com.mymav.glasses.api.NavArrow
import com.mymav.glasses.api.NavMapData
import com.mymav.glasses.api.NavScene
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Navigation on the 640x400 HUD, in the app's glass style: a perspective map (roads, light building blocks, a cyan route
 * with direction chevrons), a glass turn card with a dimensional arrow, a live metrics row, and a minimal view that is just
 * the next turn. Text and numbers are separate drawables rebuilt from [MymavNav.NavState] about twice a second; the glass
 * panels, header and pills are built once and the whole screen is rebuilt only when something structural changes (view,
 * travel mode, voice on/off, mode).
 */
class NavMapScreen(private var hud: GlassesHud.NavMap) : HudScreen(tag = "mymav-nav-map") {
    override val enterFade: Boolean = false
    private val dyn = ArrayList<M2Drawable>()
    private val builtKey = keyOf(hud.nav)
    private val VL = 30f; private val VT = 66f; private val VR = 610f; private val VB = 262f
    private val params = NavScene.Params(w = VR - VL, cx = 320f, cy = 212f, focal = 330f, camHeight = 62f, camBack = 38f, pitchDeg = 52f, range = 240f,
        maxBuildings = 18, wallsPerBuilding = 6, clipL = VL, clipT = VT, clipR = VR, clipB = VB)
    private val overviewParams = NavScene.Params(w = VR - VL, cx = 320f, cy = (VT + VB) / 2f, focal = 330f, range = 5000f, clipL = VL, clipT = VT, clipR = VR, clipB = VB)
    // 2D = the same scene looked at straight down: ~3 px per metre, walker in the lower part of the map, footprints only.
    private val params2d = NavScene.Params(w = VR - VL, cx = 320f, cy = 226f, focal = 330f, camHeight = 110f, camBack = 0f, range = 240f,
        maxBuildings = 30, wallsPerBuilding = 0, clipL = VL, clipT = VT, clipR = VR, clipB = VB, topDown = true)
    private val overviewParams2d = NavScene.Params(w = VR - VL, cx = 320f, cy = (VT + VB) / 2f, focal = 330f, range = 5000f, clipL = VL, clipT = VT, clipR = VR, clipB = VB, topDown = true)

    private fun keyOf(n: MymavNav.NavState) = "${n.mode}|${n.view}|${n.travel}|${n.muted}|${n.altCount}|${n.altIndex}|${n.gps == Gps.Lost}|${n.mph}|${n.dim}|${n.northUp}"

    override fun build() {
        val n = hud.nav
        add(HudPrimitives.fullBleedBackground())
        GlassKit.appPanel(this)
        add(HudPrimitives.label("Navigation", 32f, 24f, 1.4f, maxWidth = 170f))
        // travel-mode pill, then the HOME pill at its fixed gaze position
        GlassKit.button(this, n.travel.label, 204f, 24f, 104f, 30f, 0.68f)
        GlassKit.button(this, if (n.dim == Dim.D3) "3D" else if (n.northUp) "2D N" else "2D H", 316f, 24f, 64f, 30f, 0.68f)
        HudPrimitives.drawHomePill(this, hud.homeDwellProgress)
        val minimal = n.view == View.Minimal || n.mode == Mode.Arrived
        if (!minimal) {
            add(M2RectOutline(M2Color.BlueGrey).apply { setDimensions(VL - 1f, VT - 1f, VR - VL + 2f, VB - VT + 2f); setStyle(1f); softCorners(12f) })
            metricsCard()
            pills(n)
        } else {
            GlassKit.card(this, 36f, 70f, 250f, 200f, radius = 22f)
            GlassKit.card(this, 36f, 282f, 568f, 52f, radius = 18f)
            if (n.mode == Mode.Arrived) GlassKit.button(this, "Done", 244f, 338f, 152f, 32f, 0.8f, selected = true)
        }
        draw()
    }

    private fun metricsCard() {
        GlassKit.card(this, VL, 270f, VR - VL, 48f, radius = 16f)
        for (i in 1..3) add(M2RectFilled(HudTheme.InkEdgeBright.withAlpha(0.3f)).apply { setDimensions(VL + (VR - VL) / 4f * i, 278f, 1f, 32f) })
    }

    private fun pills(n: MymavNav.NavState) {
        val y = 326f; val h = 32f
        val xs = floatArrayOf(VL, VL + 196f, VL + 392f); val w = 188f
        when (n.mode) {
            Mode.Preview -> {
                GlassKit.button(this, "Start", xs[0], y, w, h, 0.8f, selected = true)
                GlassKit.button(this, n.travel.label, xs[1], y, w, h, 0.8f)
                GlassKit.button(this, if (n.altCount > 1) "Route ${n.altIndex + 1}/${n.altCount}" else "Route 1", xs[2], y, w, h, 0.8f)
            }
            else -> {
                GlassKit.button(this, if (n.muted) "Voice off" else "Voice", xs[0], y, w, h, 0.8f, selected = !n.muted)
                GlassKit.button(this, if (n.view == View.Overview) "Recenter" else "Overview", xs[1], y, w, h, 0.8f)
                GlassKit.button(this, "Stop", xs[2], y, w, h, 0.8f)
            }
        }
    }

    override fun tryPatch(next: GlassesHud): Boolean {
        if (next !is GlassesHud.NavMap) return false
        if (keyOf(next.nav) != builtKey) return false
        hud = next
        patchHomeDwell(next.homeDwellProgress)
        clearDyn()
        draw()
        return true
    }

    private fun clearDyn() {
        dyn.forEach { d -> runCatching { remove(d) } }
        dyn.clear()
    }

    private fun <T : M2Drawable> put(d: T): T { add(d); dyn.add(d); return d }

    /** Add something built with [GlassKit] on top of the map and remember its drawables so the next update can replace them. */
    private fun glass(block: () -> Unit) {
        val before = getDrawables().toHashSet()
        block()
        getDrawables().forEach { if (it !in before) dyn.add(it) }
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(ms))

    private fun draw() {
        val nav = hud.nav
        val minimal = nav.view == View.Minimal || nav.mode == Mode.Arrived
        val lost = nav.gps == Gps.Lost
        if (!minimal) drawMap(nav)
        if (minimal) drawMinimal(nav, lost) else { drawTurnCard(nav, lost); drawMetrics(nav, lost) }
        drawStatus(nav)
    }

    // ---- map ---------------------------------------------------------------------------------------------------

    private fun drawMap(nav: MymavNav.NavState) {
        val overview = nav.mode == Mode.Preview || nav.view == View.Overview
        val flat = nav.dim == Dim.D2
        val p = if (flat) (if (overview) overviewParams2d else params2d) else (if (overview) overviewParams else params)
        val mid = nav.route.let { r -> r[r.size / 2] }
        // overview: the route runs left to right (the viewport is wide)
        val camHeading = if (overview) (NavScene.bearing(nav.lat, nav.lon, nav.destLat, nav.destLon) + 270.0) % 360.0 else if (flat && nav.northUp) 0.0 else nav.heading
        val (roads, bld) = NavMapData.around(if (overview) mid[0] else nav.lat, if (overview) mid[1] else nav.lon, roadRing = if (overview) 6 else 2, bldRing = 1)
        val f = NavScene.frame(nav.lat, nav.lon, camHeading, nav.route, nav.fromIdx, roads, bld, p, overview, nav.destLat, nav.destLon)
        Log.i("DBGNAV", "frame mode=${nav.mode} view=${nav.view} dim=${nav.dim} northUp=${nav.northUp} travel=${nav.travel} gps=${nav.gps} speed=${nav.speedMps} pos=${"%.5f".format(nav.lat)},${"%.5f".format(nav.lon)} hdg=${nav.heading.toInt()} roads=${f.roads.size} bld=${f.buildings.size} route=${f.route.size} arrows=${f.arrows.size} tiles=${NavMapData.loadedTiles()}/${NavMapData.loadedBuildingTiles()} err=${NavMapData.lastError}")

        if (f.buildings.any { it.walls.isNotEmpty() }) {
            val walls = M2Triangles(M2Color.Indigo).apply { setColor(M2Color.Indigo) }
            f.buildings.forEach { b -> b.walls.forEach { w -> walls.addTriangle(w[0], w[1], w[2], w[3], w[4], w[5]) } }
            put(walls)
        }
        f.buildings.forEach { b -> if (b.roof.size >= 4) path(b.roof, M2Color.BlueGrey, 1f, pairs = true) }
        f.roads.sortedBy { -it.kind }.forEach { r ->
            when (r.kind) {
                0 -> path(r.pts, M2Color.White, 2.2f)
                1 -> path(r.pts, M2Color.Grey, 1.4f)
                else -> path(r.pts, M2Color.BlueGrey, 1f)
            }
        }
        // route guidance: cyan line on a deeper blue underlay, white chevrons showing the direction of travel
        val stale = nav.gps == Gps.Lost
        f.route.forEach { pts -> path(pts, M2Color.Blue, 11f); path(pts, if (stale) M2Color.BlueGrey else M2Color.Cyan, 6f) }
        if (f.arrows.isNotEmpty()) {
            val ar = M2Triangles(M2Color.White).apply { setColor(M2Color.White) }
            f.arrows.forEach { a -> ar.addTriangle(a[0], a[1], a[2], a[3], a[4], a[5]) }
            put(ar)
        }
        f.dest?.let { d ->
            if (d[0] in VL..VR && d[1] in VT..VB) {
                put(M2EllipseFilled(M2Color.White).apply { setDimensions(d[0] - 8f, d[1] - 8f, 16f, 16f) })
                put(M2EllipseFilled(M2Color.LightGreen).apply { setDimensions(d[0] - 5f, d[1] - 5f, 10f, 10f) })
            }
        }
        if (nav.mode != Mode.Preview) {
            val u = f.user
            val rel = Math.toRadians(nav.heading - camHeading)
            fun pt(x: Float, y: Float): FloatArray { val c = cos(rel).toFloat(); val s = sin(rel).toFloat(); return floatArrayOf(u[0] + x * c - y * s, u[1] + x * s + y * c) }
            val outer = M2Triangles(M2Color.White).apply { setColor(M2Color.White) }
            val a = pt(0f, -15f); val b = pt(-10.5f, 10f); val c = pt(10.5f, 10f); val d = pt(0f, 4f)
            outer.addTriangle(a[0], a[1], b[0], b[1], d[0], d[1]); outer.addTriangle(a[0], a[1], d[0], d[1], c[0], c[1])
            put(outer)
            val inner = M2Triangles(M2Color.PureCyan).apply { setColor(if (stale) M2Color.Grey else M2Color.PureCyan) }
            val a2 = pt(0f, -10f); val b2 = pt(-6.5f, 6f); val c2 = pt(6.5f, 6f); val d2 = pt(0f, 1.5f)
            inner.addTriangle(a2[0], a2[1], b2[0], b2[1], d2[0], d2[1]); inner.addTriangle(a2[0], a2[1], d2[0], d2[1], c2[0], c2[1])
            put(inner)
        }
        // compass: red tip = north
        glass { GlassKit.card(this, VR - 46f, VT + 8f, 38f, 38f, radius = 19f) }
        val ccx = VR - 27f; val ccy = VT + 27f
        val na = Math.toRadians(-camHeading); val nx = sin(na).toFloat(); val ny = -cos(na).toFloat()
        fun tri(col: M2Color, sgn: Float) = M2Triangles(col).apply {
            setColor(col)
            val tx = ccx + sgn * nx * 13f; val ty = ccy + sgn * ny * 13f
            val bx = ccx - sgn * nx * 2f; val by = ccy - sgn * ny * 2f
            addTriangle(tx, ty, bx - ny * 5f * sgn, by + nx * 5f * sgn, bx + ny * 5f * sgn, by - nx * 5f * sgn)
        }
        put(tri(M2Color.Red, 1f)); put(tri(M2Color.White, -1f))
        put(HudPrimitives.label("N", ccx - 3.5f + nx * 0f, VT + 49f, 0.5f, M2Color.White, bold = true))
    }

    // ---- turn card, metrics, status -------------------------------------------------------------------------

    private fun maneuverWord(kind: String) = when (kind) {
        "left" -> "Turn left"; "right" -> "Turn right"; "slight left" -> "Bear left"; "slight right" -> "Bear right"
        "sharp left" -> "Sharp left"; "sharp right" -> "Sharp right"; "uturn" -> "U-turn"; "arrive" -> "Arrive"
        "depart" -> "Head off"; "roundabout" -> "Roundabout"; else -> "Continue"
    }

    private fun distText(n: MymavNav.NavState, lost: Boolean) = if (lost) "--" else MymavNav.fmtDistance(n.toNextM)

    private fun drawTurnCard(nav: MymavNav.NavState, lost: Boolean) {
        val x = VL + 8f; val y = VT + 8f; val w = 236f; val h = 86f
        glass { GlassKit.card(this, x, y, w, h, radius = 20f) }
        val arrowCol = if (lost) M2Color.Grey else M2Color.PureCyan
        arrow3d(if (nav.mode == Mode.Preview) "straight" else nav.maneuver, x + 44f, y + 40f, 24f, arrowCol, lost)
        val tx = x + 86f
        if (nav.mode == Mode.Preview) {
            put(HudPrimitives.label(MymavNav.fmtDistance(nav.remainingM), tx, y + 8f, 1.2f, M2Color.White, bold = true, maxWidth = w - 92f))
            put(HudPrimitives.label(MymavNav.fmtDuration(nav.remainingS), tx, y + 38f, 0.8f, M2Color.PureCyan, bold = true, maxWidth = w - 92f))
            put(HudPrimitives.labelInk("${nav.travel.label} route", tx, y + 60f, 0.58f, HudTheme.InkMuted, maxWidth = w - 92f))
        } else {
            put(HudPrimitives.label(distText(nav, lost), tx, y + 8f, 1.25f, if (lost) M2Color.Grey else M2Color.White, bold = true, maxWidth = w - 92f))
            put(HudPrimitives.label(maneuverWord(nav.maneuver), tx, y + 40f, 0.72f, M2Color.PureCyan, bold = true, maxWidth = w - 92f))
            put(HudPrimitives.labelInk(nav.street.ifBlank { nav.instruction }.takeForHud(24), tx, y + 62f, 0.58f, HudTheme.InkMuted, maxWidth = w - 92f))
        }
    }

    private fun cell(i: Int, label: String, value: String, dim: Boolean) {
        val cw = (VR - VL) / 4f
        val cx = VL + cw * i + cw / 2f
        val lw = HudText.width(label, 0.5f, false)
        put(HudPrimitives.labelInk(label, cx - lw / 2f, 274f, 0.5f, HudTheme.InkMuted))
        val vw = HudText.width(value, 0.95f, true).coerceAtMost(cw - 14f)
        put(HudPrimitives.label(value, cx - vw / 2f, 289f, 0.95f, if (dim) M2Color.Grey else M2Color.White, bold = true, maxWidth = cw - 14f))
    }

    private fun drawMetrics(nav: MymavNav.NavState, lost: Boolean) {
        val pre = nav.mode == Mode.Preview
        cell(0, if (pre) "Arrive" else "Arrival", if (lost) "--" else clock(nav.etaMs), lost)
        cell(1, "Time left", if (lost) "--" else MymavNav.fmtDuration(nav.remainingS), lost)
        cell(2, "Distance", if (lost) "--" else MymavNav.fmtDistance(nav.remainingM), lost)
        cell(3, "Speed", if (pre) "--" else MymavNav.speedText(nav.speedMps, nav.mph), nav.speedMps.isNaN())
    }

    private fun drawMinimal(nav: MymavNav.NavState, lost: Boolean) {
        val arrived = nav.mode == Mode.Arrived
        arrow3d(if (arrived) "arrive" else nav.maneuver, 36f + 125f, 70f + 100f, 62f, if (lost) M2Color.Grey else M2Color.PureCyan, lost)
        val tx = 308f
        if (arrived) {
            put(HudPrimitives.label("Arrived", tx, 96f, 2.0f, M2Color.White, bold = true, maxWidth = 290f))
            put(HudPrimitives.label(nav.destName.substringBefore(',').takeForHud(26), tx, 168f, 0.9f, M2Color.PureCyan, bold = true, maxWidth = 290f))
            put(HudPrimitives.labelInk("Tap to close", tx, 200f, 0.65f, HudTheme.InkMuted, maxWidth = 290f))
        } else {
            put(HudPrimitives.label(distText(nav, lost), tx, 86f, 2.3f, if (lost) M2Color.Grey else M2Color.White, bold = true, maxWidth = 290f))
            put(HudPrimitives.label(maneuverWord(nav.maneuver), tx, 156f, 0.95f, M2Color.PureCyan, bold = true, maxWidth = 290f))
            put(HudPrimitives.label(nav.street.ifBlank { nav.instruction }.takeForHud(26), tx, 188f, 0.8f, M2Color.White, maxWidth = 290f))
        }
        if (!arrived) {
            val t = if (lost) "--" else MymavNav.fmtDuration(nav.remainingS)
            val d = if (lost) "--" else MymavNav.fmtDistance(nav.remainingM)
            val e = if (lost) "--" else clock(nav.etaMs)
            val cw = 568f / 3f
            listOf("Time left" to t, "Distance" to d, "Arrival" to e).forEachIndexed { i, (l, v) ->
                val cx = 36f + cw * i + cw / 2f
                put(HudPrimitives.labelInk(l, cx - HudText.width(l, 0.5f, false) / 2f, 286f, 0.5f, HudTheme.InkMuted))
                val vw = HudText.width(v, 1.0f, true).coerceAtMost(cw - 12f)
                put(HudPrimitives.label(v, cx - vw / 2f, 303f, 1.0f, if (lost) M2Color.Grey else M2Color.White, bold = true, maxWidth = cw - 12f))
            }
        }
    }

    /** Header chips, recovery banner, destination and gesture hints. */
    private fun drawStatus(nav: MymavNav.NavState) {
        val chip = when {
            nav.simulated -> "SIMULATED"
            NavMapData.loadedTiles() == 0 && NavMapData.lastError.isNotBlank() -> "NO MAP DATA"
            nav.offline -> "OFFLINE"
            else -> ""
        }
        if (chip.isNotBlank()) put(HudPrimitives.label(chip, 388f, 33f, 0.55f, M2Color.Amber, bold = true))
        val warn = when {
            nav.gps == Gps.Lost -> "GPS signal lost - directions paused"
            nav.rerouting -> "Finding a new route..."
            nav.notice.isNotBlank() -> nav.notice
            nav.gps == Gps.Poor -> "Weak GPS - position may be off"
            else -> ""
        }
        if (warn.isNotBlank()) {
            val minimal = nav.view == View.Minimal || nav.mode == Mode.Arrived
            val by = if (minimal) 244f else VB - 28f
            val bx = if (minimal) 44f else VL + 8f
            val bw = if (minimal) 234f else VR - VL - 16f
            if (!minimal || nav.mode != Mode.Arrived) {
                put(M2RectFilled(M2Color.Black.withAlpha(0.72f)).apply { setDimensions(bx, by, bw, 22f); softCorners(11f) })
                put(HudPrimitives.label(warn.takeForHud(if (minimal) 26 else 56), bx + 10f, by + 3f, 0.62f, M2Color.Amber, bold = true, maxWidth = bw - 18f))
            }
        }
        val hint = when (nav.mode) {
            Mode.Preview -> "Tap: start   Swipe up: mode   Swipe fwd: route"
            Mode.Arrived -> ""
            else -> "Tap: view   Swipe: voice / repeat / mode   Long-tap: stop"
        }
        put(HudPrimitives.labelInk("To ${nav.destName.substringBefore(',').takeForHud(18)}", 32f, 366f, 0.58f, HudTheme.InkMuted, maxWidth = 190f))
        if (hint.isNotBlank()) { val hw = HudText.width(hint, 0.55f, false).coerceAtMost(370f); put(HudPrimitives.labelInk(hint, 608f - hw, 366f, 0.55f, HudTheme.InkMuted, maxWidth = 370f)) }
    }

    // ---- drawing helpers ----------------------------------------------------------------------------------------

    private fun path(pts: FloatArray, color: M2Color, width: Float, pairs: Boolean = false) {
        if (pts.size < 4) return
        if (pairs) {
            var i = 0
            while (i + 3 < pts.size) { put(M2Path(color).apply { setColor(color); setStyle(width, M2LineStyle.Solid, M2PenShape.Round); add(pts[i], pts[i + 1]); add(pts[i + 2], pts[i + 3]) }); i += 4 }
            return
        }
        put(M2Path(color).apply {
            setColor(color)
            setStyle(width, M2LineStyle.Solid, M2PenShape.Round)
            var i = 0
            while (i + 1 < pts.size) { add(pts[i], pts[i + 1]); i += 2 }
        })
    }

    /** A dimensional arrow: darker extrusion layers below the bright face, plus a thin rim light on the head. */
    private fun arrow3d(kind: String, cx: Float, cy: Float, s: Float, face: M2Color, flat: Boolean) {
        val layers = if (flat) listOf(0f to face) else listOf(0.20f to M2Color.Indigo, 0.10f to M2Color.Blue, 0f to face)
        for ((off, col) in layers) {
            val t = NavArrow.triangles(kind, cx, cy + off * s, s)
            val tr = M2Triangles(col).apply { setColor(col) }
            var i = 0
            while (i + 5 < t.size) { tr.addTriangle(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5]); i += 6 }
            put(tr)
        }
        if (!flat) NavArrow.rim(kind, cx, cy, s)?.let { r ->
            put(M2Path(M2Color.White).apply { setColor(M2Color.White); setStyle(1.2f, M2LineStyle.Solid, M2PenShape.Round); add(r[0], r[1]); add(r[2], r[3]); add(r[4], r[5]) })
        }
    }

    // ---- touch --------------------------------------------------------------------------------------------------

    override fun onTouch(touch: M2Touch) {
        super.onTouch(touch)
        val n = hud.nav
        when (touch) {
            M2Touch.Tap -> when (n.mode) {
                Mode.Preview -> hud.actions.onStart?.invoke()
                Mode.Arrived -> hud.actions.onStop?.invoke()
                else -> MymavNav.setView(if (n.view == View.Map) View.Overview else View.Map)
            }
            M2Touch.LongTap -> hud.actions.onStop?.invoke()
            M2Touch.Forward -> if (n.mode == Mode.Preview) MymavNav.nextAlternative() else MymavNav.toggleMute()
            M2Touch.Backward -> if (n.mode != Mode.Arrived) MymavNav.toggleDim()   // repeat instruction: say "repeat" or use the phone
            M2Touch.Up -> if (n.mode != Mode.Arrived) MymavNav.cycleTravel()
            M2Touch.Down -> if (n.mode == Mode.Follow) MymavNav.setView(if (n.view == View.Minimal) View.Map else View.Minimal)
            else -> Log.d("NavMapScreen", "touch $touch")
        }
    }
}
