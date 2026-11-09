package com.mymav.glasses.api

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure projection maths for the navigation map: turns map data, the route and the walker's position into 2D screen
 * shapes (heading-up, perspective). The glasses HUD and the phone both draw the same [Frame], so what is on the phone is
 * what is on the glasses. No Android classes here.
 */
object NavScene {
    class Params(
        val w: Float = 640f, val h: Float = 400f,
        val focal: Float = 430f,
        val camHeight: Float = 42f, val camBack: Float = 26f, val pitchDeg: Float = 50f,
        val cx: Float = 320f, val cy: Float = 150f,
        val range: Float = 260f,
        val maxRoads: Int = 70, val maxBuildings: Int = 26, val wallsPerBuilding: Int = 8,
        val arrowEveryM: Float = 16f,
        /** 2D presentation: camera looks straight down, buildings are flat footprints. */
        val topDown: Boolean = false,
        /** Everything is clipped to this screen rectangle (left, top, right, bottom). */
        val clipL: Float = -30f, val clipT: Float = -30f, val clipR: Float = 670f, val clipB: Float = 430f,
    )

    /** A visible run of a polyline in screen space (x,y pairs). */
    class Run(val kind: Int, val pts: FloatArray)
    /** roof = independent edge pairs (x0,y0,x1,y1); walls = triangles (6 floats each), already clipped to the viewport. */
    class Bldg(val roof: FloatArray, val walls: List<FloatArray>, val depth: Float)
    class Frame(
        val roads: List<Run>, val buildings: List<Bldg>, val route: List<FloatArray>, val arrows: List<FloatArray>,
        val user: FloatArray, val dest: FloatArray?, val northArrowDeg: Float, val visibleRouteM: Float,
    )

    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat); val dl = Math.toRadians(bLon - aLon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest signed difference b - a in degrees (-180..180). */
    fun angleDiff(a: Double, b: Double): Double { var d = (b - a) % 360.0; if (d > 180) d -= 360.0; if (d < -180) d += 360.0; return d }

    private class Cam(val lat0: Double, val lon0: Double, headingDeg: Double, val p: Params, val overview: Boolean, val ovH: Float) {
        val kx = cos(Math.toRadians(lat0)) * 111320.0
        val ky = 110540.0
        val hr = Math.toRadians(headingDeg)
        val fx = sin(hr).toFloat(); val fy = cos(hr).toFloat()          // forward on the ground
        val rx = cos(hr).toFloat(); val ry = (-sin(hr)).toFloat()       // right on the ground
        val pitch = Math.toRadians(if (p.topDown) 90.0 else if (overview) 82.0 else p.pitchDeg.toDouble())
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()
        val height = if (overview) ovH else p.camHeight
        val back = if (overview) 0f else p.camBack
        fun ex(lon: Double) = ((lon - lon0) * kx).toFloat()
        fun ny(lat: Double) = ((lat - lat0) * ky).toFloat()
        /** world (east, north, up) -> camera (xc, yc, zc depth) */
        fun cam(x: Float, y: Float, z: Float, out: FloatArray) {
            val gx = x + fx * back; val gy = y + fy * back
            val df = gx * fx + gy * fy
            val xc = gx * rx + gy * ry
            val dz = z - height
            out[0] = xc; out[2] = df * cp - dz * sp; out[1] = df * sp + dz * cp
        }
    }

    private const val NEAR = 2.5f

    /** Clip the camera-space segment to the near plane then project to the screen; null when nothing is in front. */
    private fun seg(a: FloatArray, b: FloatArray, p: Params, out: FloatArray): Boolean {
        var ax = a[0]; var ay = a[1]; var az = a[2]; var bx = b[0]; var by = b[1]; var bz = b[2]
        if (az < NEAR && bz < NEAR) return false
        if (az < NEAR) { val t = (NEAR - az) / (bz - az); ax += (bx - ax) * t; ay += (by - ay) * t; az = NEAR }
        if (bz < NEAR) { val t = (NEAR - bz) / (az - bz); bx += (ax - bx) * t; by += (ay - by) * t; bz = NEAR }
        var x0 = p.cx + p.focal * ax / az; var y0 = p.cy - p.focal * ay / az
        var x1 = p.cx + p.focal * bx / bz; var y1 = p.cy - p.focal * by / bz
        // Liang-Barsky against a slightly enlarged screen so line ends do not show.
        val xmin = p.clipL; val xmax = p.clipR; val ymin = p.clipT; val ymax = p.clipB
        var t0 = 0f; var t1 = 1f
        val dx = x1 - x0; val dy = y1 - y0
        fun clip(pp: Float, q: Float): Boolean {
            if (pp == 0f) return q >= 0f
            val r = q / pp
            if (pp < 0f) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
            return true
        }
        if (!(clip(-dx, x0 - xmin) && clip(dx, xmax - x0) && clip(-dy, y0 - ymin) && clip(dy, ymax - y0))) return false
        out[0] = x0 + t0 * dx; out[1] = y0 + t0 * dy; out[2] = x0 + t1 * dx; out[3] = y0 + t1 * dy
        return true
    }

    /** Clip a convex-ish polygon (x,y pairs) to the viewport (Sutherland-Hodgman) and fan it into triangles. */
    private fun clipTriangles(poly: FloatArray, p: Params): List<FloatArray> {
        var pts = poly.toList()
        fun clipEdge(inside: (Float, Float) -> Boolean, cut: (Float, Float, Float, Float) -> FloatArray) {
            if (pts.size < 6) return
            val out = ArrayList<Float>()
            val n = pts.size / 2
            for (i in 0 until n) {
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[((i + 1) % n) * 2]; val by = pts[((i + 1) % n) * 2 + 1]
                val ia = inside(ax, ay); val ib = inside(bx, by)
                if (ia && ib) { out.add(bx); out.add(by) }
                else if (ia && !ib) { val c = cut(ax, ay, bx, by); out.add(c[0]); out.add(c[1]) }
                else if (!ia && ib) { val c = cut(ax, ay, bx, by); out.add(c[0]); out.add(c[1]); out.add(bx); out.add(by) }
            }
            pts = out
        }
        clipEdge({ x, _ -> x >= p.clipL }) { ax, ay, bx, by -> val t = (p.clipL - ax) / (bx - ax); floatArrayOf(p.clipL, ay + (by - ay) * t) }
        clipEdge({ x, _ -> x <= p.clipR }) { ax, ay, bx, by -> val t = (p.clipR - ax) / (bx - ax); floatArrayOf(p.clipR, ay + (by - ay) * t) }
        clipEdge({ _, y -> y >= p.clipT }) { ax, ay, bx, by -> val t = (p.clipT - ay) / (by - ay); floatArrayOf(ax + (bx - ax) * t, p.clipT) }
        clipEdge({ _, y -> y <= p.clipB }) { ax, ay, bx, by -> val t = (p.clipB - ay) / (by - ay); floatArrayOf(ax + (bx - ax) * t, p.clipB) }
        val tris = ArrayList<FloatArray>()
        val n = pts.size / 2
        for (i in 1 until n - 1) tris += floatArrayOf(pts[0], pts[1], pts[i * 2], pts[i * 2 + 1], pts[(i + 1) * 2], pts[(i + 1) * 2 + 1])
        return tris
    }

    private class RunBuilder(val kind: Int, val sink: MutableList<Run>) {
        private var pts = ArrayList<Float>()
        fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
            val n = pts.size
            if (n >= 2 && abs(pts[n - 2] - x0) < 0.6f && abs(pts[n - 1] - y0) < 0.6f) { pts.add(x1); pts.add(y1) }
            else { flush(); pts.add(x0); pts.add(y0); pts.add(x1); pts.add(y1) }
            if (pts.size >= 48) { val lx = pts[pts.size - 2]; val ly = pts[pts.size - 1]; flush(); pts.add(lx); pts.add(ly) }
        }
        fun flush() { if (pts.size >= 4) sink += Run(kind, pts.toFloatArray()); pts = ArrayList() }
    }

    /**
     * @param heading heading-up camera direction in degrees
     * @param route polyline lat/lon; [routeFrom] is the index of the first vertex still ahead of the walker
     * @param overview true = look down on the whole route (preview before walking)
     */
    fun frame(
        lat: Double, lon: Double, heading: Double, route: List<DoubleArray>, routeFrom: Int,
        roads: List<NavMapData.Road>, buildings: List<NavMapData.Building>, p: Params = Params(),
        overview: Boolean = false, destLat: Double = Double.NaN, destLon: Double = Double.NaN,
    ): Frame {
        var ovH = 0f
        var cLat = lat; var cLon = lon
        if (overview && route.size >= 2) {
            // Fit the whole route in the viewport along the camera's own axes: width along "right", height along "forward".
            var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
            for (q in route) { minLa = min(minLa, q[0]); maxLa = max(maxLa, q[0]); minLo = min(minLo, q[1]); maxLo = max(maxLo, q[1]) }
            cLat = (minLa + maxLa) / 2; cLon = (minLo + maxLo) / 2
            val kx = cos(Math.toRadians(cLat)) * 111320.0; val ky = 110540.0
            val hr = Math.toRadians(heading)
            val fx = sin(hr); val fy = cos(hr); val rx = cos(hr); val ry = -sin(hr)
            var x0 = 1e9; var x1 = -1e9; var y0 = 1e9; var y1 = -1e9
            val pts = ArrayList<DoubleArray>(route); if (!destLat.isNaN()) pts += doubleArrayOf(destLat, destLon); pts += doubleArrayOf(lat, lon)
            for (q in pts) { val e = (q[1] - cLon) * kx; val nn = (q[0] - cLat) * ky; val x = e * rx + nn * ry; val y = e * fx + nn * fy; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            cLon += (mx * rx + my * fx) / kx; cLat += (mx * ry + my * fy) / ky
            val viewH = (p.clipB - p.clipT).coerceAtLeast(60f)
            val needW = (x1 - x0) * p.focal / (0.86 * p.w); val needH = (y1 - y0) * p.focal / (0.86 * viewH)
            ovH = max(max(needW, needH), 150.0).toFloat() + 10f
            // the camera is tilted 82 degrees, so the middle of the screen looks about 0.14 x height ahead of it: stand back
            val lookAhead = if (p.topDown) 0.0 else ovH * 0.14
            cLon -= fx * lookAhead / kx; cLat -= fy * lookAhead / ky
        }
        val cam = Cam(cLat, cLon, heading, p, overview, ovH)
        val tmpA = FloatArray(3); val tmpB = FloatArray(3); val o = FloatArray(4)
        val range = if (overview) 5000f else p.range
        val centerX = 0f; val centerY = 0f

        // ---- roads (nearest first, capped) ----
        data class RD(val r: NavMapData.Road, val d: Float)
        val rd = ArrayList<RD>()
        for (r in roads) {
            var best = Float.MAX_VALUE
            val step = max(1, r.lat.size / 6)
            var i = 0
            while (i < r.lat.size) { val x = cam.ex(r.lon[i]); val y = cam.ny(r.lat[i]); best = min(best, hypot(x - centerX, y - centerY)); i += step }
            if (best < range) rd += RD(r, best)
        }
        rd.sortBy { it.d }
        val roadRuns = ArrayList<Run>()
        for (e in rd.take(if (overview) p.maxRoads * 2 else p.maxRoads)) {
            val rb = RunBuilder(e.r.kind, roadRuns)
            for (i in 0 until e.r.lat.size - 1) {
                cam.cam(cam.ex(e.r.lon[i]), cam.ny(e.r.lat[i]), 0f, tmpA)
                cam.cam(cam.ex(e.r.lon[i + 1]), cam.ny(e.r.lat[i + 1]), 0f, tmpB)
                if (seg(tmpA, tmpB, p, o)) rb.add(o[0], o[1], o[2], o[3])
            }
            rb.flush()
        }

        // ---- buildings: extruded footprints (walls + roof), far to near ----
        val blds = ArrayList<Bldg>()
        if (!overview) {   // (2D draws footprints in follow view; both overviews stay roads + route)
            data class BD(val b: NavMapData.Building, val d: Float)
            val bd = ArrayList<BD>()
            for (b in buildings) {
                var sx = 0f; var sy = 0f
                for (i in b.lat.indices) { sx += cam.ex(b.lon[i]); sy += cam.ny(b.lat[i]) }
                val d = hypot(sx / b.lat.size - centerX, sy / b.lat.size - centerY)
                if (d < range && d > 7f) bd += BD(b, d)
            }
            bd.sortBy { it.d }
            for (e in bd.take(p.maxBuildings).reversed()) {
                val b = e.b
                val n = b.lat.size - (if (b.lat.first() == b.lat.last() && b.lon.first() == b.lon.last()) 1 else 0)
                if (n < 3) continue
                val roofPts = ArrayList<Float>(); val walls = ArrayList<FloatArray>()
                var anyVisible = false
                val gA = FloatArray(3); val gB = FloatArray(3); val rA = FloatArray(3); val rB = FloatArray(3)
                val oo = FloatArray(4)
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val x0 = cam.ex(b.lon[i]); val y0 = cam.ny(b.lat[i]); val x1 = cam.ex(b.lon[j]); val y1 = cam.ny(b.lat[j])
                    val bh = if (p.topDown) 0f else b.height
                    cam.cam(x0, y0, 0f, gA); cam.cam(x1, y1, 0f, gB); cam.cam(x0, y0, bh, rA); cam.cam(x1, y1, bh, rB)
                    if (gA[2] < NEAR || gB[2] < NEAR || rA[2] < NEAR || rB[2] < NEAR) continue   // no partial walls across the near plane
                    val pa = project(gA, p); val pb = project(gB, p); val pc = project(rB, p); val pd = project(rA, p)
                    // Only walls that face the camera (screen winding), so the box reads as solid.
                    val area = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0])
                    if (area < 0f && !p.topDown) continue
                    if (!p.topDown && walls.size < p.wallsPerBuilding * 2) clipTriangles(floatArrayOf(pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pd[0], pd[1]), p).forEach { walls += it }
                    if (seg(rA, rB, p, oo)) { roofPts.add(oo[0]); roofPts.add(oo[1]); roofPts.add(oo[2]); roofPts.add(oo[3]) }
                    anyVisible = true
                }
                if (anyVisible) blds += Bldg(roofPts.toFloatArray(), walls, e.d)
            }
        }

        // ---- route (ahead of the walker only) with direction arrows ----
        val routeRuns = ArrayList<FloatArray>(); val arrows = ArrayList<FloatArray>()
        var visibleM = 0f
        if (route.size >= 2) {
            val rb = ArrayList<Run>(); val builder = RunBuilder(9, rb)
            val from = if (overview) 0 else routeFrom.coerceIn(0, route.size - 2)
            var carry = 0f
            // A long journey must not turn into thousands of drawables: thin the polyline and space the chevrons by what is shown.
            val stride = if (overview) max(1, (route.size - 1) / 300) else 1
            var total = 0f
            if (overview) for (k in 0 until route.size - 1) total += hypot(cam.ex(route[k + 1][1]) - cam.ex(route[k][1]), cam.ny(route[k + 1][0]) - cam.ny(route[k][0]))
            val spacing = if (overview) max(p.arrowEveryM, total / 25f) else p.arrowEveryM
            var i = from
            while (i < route.size - 1) {
                val i1 = min(i + stride, route.size - 1)
                var aLat = route[i][0]; var aLon = route[i][1]
                if (i == from && !overview) { aLat = lat; aLon = lon }   // the path starts under the walker
                val bLat = route[i1][0]; val bLon = route[i1][1]
                val x0 = cam.ex(aLon); val y0 = cam.ny(aLat); val x1 = cam.ex(bLon); val y1 = cam.ny(bLat)
                cam.cam(x0, y0, 0.4f, tmpA); cam.cam(x1, y1, 0.4f, tmpB)
                if (seg(tmpA, tmpB, p, o)) { builder.add(o[0], o[1], o[2], o[3]); visibleM += hypot(x1 - x0, y1 - y0) }
                // chevrons every arrowEveryM metres along the path, drawn flat on the ground
                val len = hypot(x1 - x0, y1 - y0)
                if (len > 0.5f) {
                    val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
                    var s = spacing - carry
                    while (s < len) {
                        val cx0 = x0 + ux * s; val cy0 = y0 + uy * s
                        val half = 2.2f; val tip = 3.8f
                        val tipX = cx0 + ux * tip; val tipY = cy0 + uy * tip
                        val lx = cx0 - ux * 1.2f - uy * half; val ly = cy0 - uy * 1.2f + ux * half
                        val rx2 = cx0 - ux * 1.2f + uy * half; val ry2 = cy0 - uy * 1.2f - ux * half
                        val c1 = FloatArray(3); val c2 = FloatArray(3); val c3 = FloatArray(3)
                        cam.cam(tipX, tipY, 0.6f, c1); cam.cam(lx, ly, 0.6f, c2); cam.cam(rx2, ry2, 0.6f, c3)
                        if (c1[2] > NEAR && c2[2] > NEAR && c3[2] > NEAR) {
                            val a = project(c1, p); val b = project(c2, p); val c = project(c3, p)
                            if (a[0] in p.clipL..p.clipR && a[1] in p.clipT..p.clipB && b[0] in p.clipL..p.clipR && b[1] in p.clipT..p.clipB && c[0] in p.clipL..p.clipR && c[1] in p.clipT..p.clipB) arrows += floatArrayOf(a[0], a[1], b[0], b[1], c[0], c[1])
                        }
                        s += spacing
                    }
                    carry = (carry + len) % spacing
                }
                if (visibleM > p.range * 1.6f && !overview) break
                i = i1
            }
            builder.flush()
            rb.forEach { routeRuns += it.pts }
        }

        // ---- walker marker and destination pin ----
        val u = FloatArray(3)
        cam.cam(cam.ex(lon), cam.ny(lat), 0.5f, u)
        val up = if (u[2] > NEAR) project(u, p) else floatArrayOf(p.cx, p.h - 70f)
        val dst = if (!destLat.isNaN()) FloatArray(3).also { cam.cam(cam.ex(destLon), cam.ny(destLat), 0f, it) }.let { if (it[2] > NEAR) project(it, p) else null } else null
        return Frame(roadRuns, blds, routeRuns, arrows, up, dst, heading.toFloat(), visibleM)
    }

    private fun project(c: FloatArray, p: Params) = floatArrayOf(p.cx + p.focal * c[0] / c[2], p.cy - p.focal * c[1] / c[2])
}
