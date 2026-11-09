package com.mymav.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mymav.glasses.api.GlassesHomeController
import com.mymav.glasses.api.MymavNav
import com.mymav.glasses.api.MymavNav.Gps
import com.mymav.glasses.api.MymavNav.Mode
import com.mymav.glasses.api.MymavNav.View
import com.mymav.glasses.api.NavArrow
import com.mymav.glasses.api.NavMapData
import com.mymav.glasses.api.NavScene
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val Navy = Color(0xFF07122B)
private val Cyan = Color(0xFF22D3EE)
private val DeepBlue = Color(0xFF1D6FE0)
private val Ink = Color(0xFFB7C4DE)

/**
 * Full-screen navigation on the phone, in the same glass look as the glasses HUD and drawn from the same [NavScene]
 * frame. Appears when a route preview or journey starts (asked by voice or typed); Home hides it without stopping the
 * journey, and a slim bar brings it back.
 */
@Composable
fun NavPhoneOverlay(bottomPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val st by MymavNav.state.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val n = st
    LaunchedEffect(n == null, n?.mode == Mode.Preview) { if (n == null || n.mode == Mode.Preview) hidden = false }
    val openReq by MymavNav.phoneOpen.collectAsState()
    LaunchedEffect(openReq) { hidden = false }
    LaunchedEffect(n != null && !hidden) { while (n != null && !hidden) { delay(800); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    if (n == null) return
    if (hidden) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).zIndex(20f)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navy).border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable { hidden = false }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Navigation running", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(if (n.gps == Gps.Lost) "GPS lost" else "${MymavNav.fmtDistance(n.remainingM)} - ${MymavNav.fmtDuration(n.remainingS)}  >", color = Cyan, fontSize = 14.sp)
            }
        }
        return
    }
    val lost = n.gps == Gps.Lost
    val minimal = n.view == View.Minimal || n.mode == Mode.Arrived
    Column(
        Modifier.fillMaxSize().zIndex(20f).background(Navy).statusBarsPadding().padding(bottom = bottomPadding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Navigation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            MymavNav.Travel.values().forEach { t -> Pill(t.name, n.travel == t) { MymavNav.setTravel(t) } }
            Spacer(Modifier.weight(1f))
            Pill("Home", false) { hidden = true }
        }
        val chips = listOfNotNull(
            if (n.simulated) "SIMULATED LOCATION" else null,
            if (n.offline) "OFFLINE" else null,
        )
        if (chips.isNotEmpty()) Text(chips.joinToString("  "), color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Bold)

        // turn card
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF12254D)).border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(if (minimal) 120.dp else 76.dp)) {
                drawArrow(if (n.mode == Mode.Preview) "straight" else n.maneuver, size.width / 2f, size.height * 0.46f, size.minDimension * 0.4f, lost)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                if (n.mode == Mode.Preview) {
                    Text(MymavNav.fmtDistance(n.remainingM), color = Color.White, fontSize = if (minimal) 44.sp else 32.sp, fontWeight = FontWeight.Bold)
                    Text(MymavNav.fmtDuration(n.remainingS), color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${n.travel.label} route to ${n.destName.substringBefore(',')}", color = Ink, fontSize = 13.sp)
                } else if (n.mode == Mode.Arrived) {
                    Text("Arrived", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(n.destName.substringBefore(','), color = Cyan, fontSize = 16.sp)
                } else {
                    Text(if (lost) "--" else MymavNav.fmtDistance(n.toNextM), color = if (lost) Ink else Color.White, fontSize = if (minimal) 48.sp else 32.sp, fontWeight = FontWeight.Bold)
                    Text(maneuverWord(n.maneuver), color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(n.street.ifBlank { n.instruction }, color = Ink, fontSize = 14.sp)
                }
            }
        }

        if (!minimal) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(22.dp))) {
                val tk = tick
                Canvas(Modifier.fillMaxSize()) { drawMap(n, tk) }
            }
        } else Spacer(Modifier.weight(1f))

        val warn = when {
            lost -> "GPS signal lost - directions paused"
            n.rerouting -> "Finding a new route..."
            n.notice.isNotBlank() -> n.notice
            n.gps == Gps.Poor -> "Weak GPS - position may be off"
            else -> ""
        }
        if (warn.isNotBlank()) Text(warn, color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, fontSize = 14.sp)

        // metrics
        val pre = n.mode == Mode.Preview
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF12254D)).padding(vertical = 10.dp)) {
            Metric(if (pre) "Arrive" else "Arrival", if (lost) "--" else SimpleDateFormat("HH:mm", Locale.UK).format(Date(n.etaMs)), Modifier.weight(1f))
            Metric("Time left", if (lost) "--" else MymavNav.fmtDuration(n.remainingS), Modifier.weight(1f))
            Metric("Distance", if (lost) "--" else MymavNav.fmtDistance(n.remainingM), Modifier.weight(1f))
            Metric("Speed", if (pre) "--" else MymavNav.speedText(n.speedMps, n.mph), Modifier.weight(1f))
        }

        // controls
        if (search) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Where to?") })
                Pill("Go", true) { if (query.isNotBlank()) { GlassesHomeController.handleAssistantUtterance("navigate to ${query.trim()}"); search = false; query = "" } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pre) {
                Pill("Start", true, Modifier.weight(1f)) { GlassesHomeController.handleAssistantUtterance("yes") }
                Pill(if (n.altCount > 1) "Route ${n.altIndex + 1}/${n.altCount}" else "Route 1", false, Modifier.weight(1f)) { MymavNav.nextAlternative() }
            } else if (n.mode == Mode.Follow) {
                Pill(if (n.muted) "Voice off" else "Voice", !n.muted, Modifier.weight(1f)) { MymavNav.toggleMute() }
                Pill("Repeat", false, Modifier.weight(1f)) { MymavNav.repeatInstruction() }
                Pill(if (n.view == View.Overview) "Recenter" else "Overview", false, Modifier.weight(1f)) { MymavNav.setView(if (n.view == View.Overview) View.Map else View.Overview) }
                Pill(if (n.view == View.Minimal) "Map" else "Minimal", false, Modifier.weight(1f)) { MymavNav.setView(if (n.view == View.Minimal) View.Map else View.Minimal) }
            }
            if (pre) {
                Pill("Search", search, Modifier.weight(1f)) { search = !search }
                Pill("Close", false, Modifier.weight(1f)) { MymavNav.closePreview() }
            }
        }
        // map presentation (independent of Follow / Overview): 3D perspective or 2D top-down, and the 2D orientation
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("3D", n.dim == MymavNav.Dim.D3, Modifier.weight(1f)) { MymavNav.setDim(MymavNav.Dim.D3) }
            Pill("2D", n.dim == MymavNav.Dim.D2, Modifier.weight(1f)) { MymavNav.setDim(MymavNav.Dim.D2) }
            if (n.dim == MymavNav.Dim.D2) Pill(if (n.northUp) "North-up" else "Heading-up", false, Modifier.weight(1.6f)) { MymavNav.toggleNorthUp() }
        }
        if (!pre) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Search", search, Modifier.weight(1f)) { search = !search }
                Pill(if (n.mph) "mph" else "km/h", false, Modifier.weight(1f)) { MymavNav.toggleUnits() }
                Pill(if (n.mode == Mode.Arrived) "Done" else "Stop", false, Modifier.weight(1f), danger = true) { MymavNav.stop(quiet = true) }
            }
        }
    }
}

private fun maneuverWord(kind: String) = when (kind) {
    "left" -> "Turn left"; "right" -> "Turn right"; "slight left" -> "Bear left"; "slight right" -> "Bear right"
    "sharp left" -> "Sharp left"; "sharp right" -> "Sharp right"; "uturn" -> "U-turn"; "arrive" -> "Arrive"
    "depart" -> "Head off"; "roundabout" -> "Roundabout"; else -> "Continue"
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Ink, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when { selected -> DeepBlue; danger -> Color(0xFF5B1A24); else -> Color(0xFF12254D) }
    Box(
        modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, if (selected) Cyan else Color(0x44FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

private fun DrawScope.drawTris(t: FloatArray, color: Color) {
    val p = Path()
    var i = 0
    while (i + 5 < t.size) {
        p.moveTo(t[i], t[i + 1]); p.lineTo(t[i + 2], t[i + 3]); p.lineTo(t[i + 4], t[i + 5]); p.close(); i += 6
    }
    drawPath(p, color)
}

private fun DrawScope.drawArrow(kind: String, cx: Float, cy: Float, s: Float, flat: Boolean) {
    if (flat) { drawTris(NavArrow.triangles(kind, cx, cy, s), Color(0xFF7A869E)); return }
    drawTris(NavArrow.triangles(kind, cx, cy + 0.20f * s, s), Color(0xFF3949AB))
    drawTris(NavArrow.triangles(kind, cx, cy + 0.10f * s, s), Color(0xFF2196F3))
    drawTris(NavArrow.triangles(kind, cx, cy, s), Color(0xFF00E5FF))
    NavArrow.rim(kind, cx, cy, s)?.let { r ->
        val p = Path().apply { moveTo(r[0], r[1]); lineTo(r[2], r[3]); lineTo(r[4], r[5]) }
        drawPath(p, Color.White, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.runPath(pts: FloatArray, color: Color, width: Float) {
    if (pts.size < 4) return
    val p = Path().apply { moveTo(pts[0], pts[1]); var i = 2; while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 } }
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawMap(n: MymavNav.NavState, tk: Int) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1B3F), Color(0xFF050B1E))))
    val overview = n.mode == Mode.Preview || n.view == View.Overview
    val flat = n.dim == MymavNav.Dim.D2
    val p = if (flat && overview) NavScene.Params(w = w, cx = w / 2f, cy = h / 2f, focal = w * 0.57f, range = 5000f, clipL = 0f, clipT = 0f, clipR = w, clipB = h, topDown = true)
    else if (flat) NavScene.Params(w = w, cx = w / 2f, cy = h * 0.70f, focal = w * 0.57f, camHeight = 120f, camBack = 0f, range = 260f,
        maxRoads = 110, maxBuildings = 60, wallsPerBuilding = 0, clipL = 0f, clipT = 0f, clipR = w, clipB = h, topDown = true)
    else if (overview) NavScene.Params(w = w, cx = w / 2f, cy = h / 2f, focal = w * 0.57f, range = 5000f, clipL = 0f, clipT = 0f, clipR = w, clipB = h)
    else NavScene.Params(w = w, cx = w / 2f, cy = h * 0.74f, focal = w * 0.57f, camHeight = 62f, camBack = 38f, pitchDeg = 52f, range = 260f,
        maxRoads = 110, maxBuildings = 45, wallsPerBuilding = 8, clipL = 0f, clipT = 0f, clipR = w, clipB = h)
    val mid = n.route[n.route.size / 2]
    val camHeading = if (overview) NavScene.bearing(n.lat, n.lon, n.destLat, n.destLon) else if (flat && n.northUp) 0.0 else n.heading
    val (roads, bld) = NavMapData.around(if (overview) mid[0] else n.lat, if (overview) mid[1] else n.lon, roadRing = if (overview) 6 else 2, bldRing = 1)
    val f = NavScene.frame(n.lat, n.lon, camHeading, n.route, n.fromIdx, roads, bld, p, overview, n.destLat, n.destLon)
    android.util.Log.i("DBGNAV", "phone frame tk=$tk view=${n.view} dim=${n.dim} northUp=${n.northUp} roads=${f.roads.size} bld=${f.buildings.size} route=${f.route.size} tiles=${NavMapData.loadedTiles()} err=${NavMapData.lastError}")
    val stale = n.gps == Gps.Lost
    f.buildings.forEach { b ->
        b.walls.forEach { t -> drawTris(t, Color(0xFF1F2F66)) }
        var i = 0
        while (i + 3 < b.roof.size) { drawLine(Color(0xFF7C8FB5), Offset(b.roof[i], b.roof[i + 1]), Offset(b.roof[i + 2], b.roof[i + 3]), 1.5f); i += 4 }
    }
    f.roads.sortedBy { -it.kind }.forEach { r ->
        when (r.kind) { 0 -> runPath(r.pts, Color.White, 4f); 1 -> runPath(r.pts, Color(0xFFB0B8C8), 2.6f); else -> runPath(r.pts, Color(0xFF5A6A8C), 1.8f) }
    }
    f.route.forEach { pts -> runPath(pts, DeepBlue, 20f); runPath(pts, if (stale) Color(0xFF607D8B) else Color(0xFF00E5FF), 11f) }
    f.arrows.forEach { a -> drawTris(a, Color.White) }
    f.dest?.let { d -> drawCircle(Color.White, 11f, Offset(d[0], d[1])); drawCircle(Color(0xFF7CE06B), 7f, Offset(d[0], d[1])) }
    if (n.mode != Mode.Preview) {
        val u = f.user
        val rel = Math.toRadians(n.heading - camHeading)
        fun pt(x: Float, y: Float) = Offset(u[0] + x * cos(rel).toFloat() - y * sin(rel).toFloat(), u[1] + x * sin(rel).toFloat() + y * cos(rel).toFloat())
        val outer = Path().apply { val a = pt(0f, -26f); moveTo(a.x, a.y); val b = pt(-18f, 17f); lineTo(b.x, b.y); val d = pt(0f, 7f); lineTo(d.x, d.y); val c = pt(18f, 17f); lineTo(c.x, c.y); close() }
        drawPath(outer, Color.White)
        val inner = Path().apply { val a = pt(0f, -18f); moveTo(a.x, a.y); val b = pt(-11f, 11f); lineTo(b.x, b.y); val d = pt(0f, 3f); lineTo(d.x, d.y); val c = pt(11f, 11f); lineTo(c.x, c.y); close() }
        drawPath(inner, if (stale) Color(0xFF9AA3B2) else Color(0xFF00E5FF))
    }
    // compass: red = north
    val cx = w - 34f; val cy = 34f
    drawCircle(Color(0x66FFFFFF), 24f, Offset(cx, cy), style = Stroke(1.5f))
    val na = Math.toRadians(-camHeading); val nx = sin(na).toFloat(); val ny = -cos(na).toFloat()
    val north = Path().apply { moveTo(cx + nx * 19f, cy + ny * 19f); lineTo(cx - ny * 7f, cy + nx * 7f); lineTo(cx + ny * 7f, cy - nx * 7f); close() }
    drawPath(north, Color(0xFFFF5252))
    val south = Path().apply { moveTo(cx - nx * 19f, cy - ny * 19f); lineTo(cx - ny * 7f, cy + nx * 7f); lineTo(cx + ny * 7f, cy - nx * 7f); close() }
    drawPath(south, Color.White)
    if (NavMapData.loadedTiles() == 0 && NavMapData.lastError.isNotBlank()) { /* no tiles: the route line and arrow are still drawn */ }
}
