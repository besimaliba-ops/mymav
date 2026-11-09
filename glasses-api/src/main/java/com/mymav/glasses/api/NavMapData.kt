package com.mymav.glasses.api

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Map geometry for the navigation view: roads and building footprints (with heights) from OpenStreetMap data, served as
 * free vector tiles by OpenFreeMap (no key, CDN-hosted). One zoom-14 tile (about 2.4 km across) covers a whole walk, so a
 * trip needs one to four downloads, cached on disk. Nothing is invented: if a tile cannot be fetched the map has no roads
 * or buildings there and [lastError] says why.
 */
object NavMapData {
    /** kind: 0 = main road, 1 = street / service, 2 = footpath / track / steps. */
    class Road(val kind: Int, val lat: DoubleArray, val lon: DoubleArray)
    class Building(val lat: DoubleArray, val lon: DoubleArray, val height: Float)
    private class Tile(val roads: List<Road>, val buildings: List<Building>)

    const val Z = 14
    const val ATTRIBUTION = "Map: OpenFreeMap, OpenMapTiles, data from OpenStreetMap contributors"
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"

    private val tiles = ConcurrentHashMap<Long, Tile>()
    private val inflight = ConcurrentHashMap.newKeySet<Long>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var template: String? = null

    @Volatile var lastError: String = ""
    @Volatile var onLoaded: (() -> Unit)? = null

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    private fun tx(lon: Double) = floor((lon + 180.0) / 360.0 * (1 shl Z)).toInt()
    private fun ty(lat: Double): Int { val r = Math.toRadians(lat); return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl Z)).toInt() }

    fun loadedTiles() = tiles.size
    fun loadedBuildingTiles() = tiles.values.count { it.buildings.isNotEmpty() }
    fun pending() = inflight.size

    /** Make sure the tile under this point is loaded or loading, and the neighbour tiles when the point is near an edge. */
    fun ensure(lat: Double, lon: Double, ring: Int = 0, buildings: Boolean = true) {
        val n = (1 shl Z).toDouble()
        val fx = (lon + 180.0) / 360.0 * n; val r = Math.toRadians(lat)
        val fy = (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n
        val cx = floor(fx).toInt(); val cy = floor(fy).toInt()
        request(cx, cy)
        val ex = fx - cx; val ey = fy - cy
        val dxs = ArrayList<Int>().apply { if (ex < 0.12) add(-1); if (ex > 0.88) add(1) }
        val dys = ArrayList<Int>().apply { if (ey < 0.12) add(-1); if (ey > 0.88) add(1) }
        dxs.forEach { request(cx + it, cy) }; dys.forEach { request(cx, cy + it) }
        for (dx in dxs) for (dy in dys) request(cx + dx, cy + dy)
    }

    /** Tiles under a route, sampled. */
    fun ensureRoute(poly: List<DoubleArray>, cap: Int = 6) {
        val seen = LinkedHashSet<Pair<Int, Int>>()
        for (p in poly) seen += tx(p[1]) to ty(p[0])
        seen.take(cap).forEach { request(it.first, it.second) }
    }

    private fun request(x: Int, y: Int) {
        val k = key(x, y)
        if (tiles.containsKey(k)) return
        failedAt[k]?.let { if (System.currentTimeMillis() - it < 30_000L) return }
        if (!inflight.add(k)) return
        pool.execute {
            try {
                val bytes = readDisk(x, y) ?: fetch(x, y)
                if (bytes != null) {
                    tiles[k] = decode(bytes, x, y)
                    failedAt.remove(k)
                    main.post { onLoaded?.invoke() }
                } else failedAt[k] = System.currentTimeMillis()
            } catch (t: Throwable) {
                lastError = "Map data unavailable (${t.message ?: t.javaClass.simpleName})"
                failedAt[k] = System.currentTimeMillis()
            } finally { inflight.remove(k) }
        }
    }

    /** Everything in the loaded tiles around this point (the scene culls by distance). */
    fun around(lat: Double, lon: Double, roadRing: Int = 1, bldRing: Int = 1): Pair<List<Road>, List<Building>> {
        val cx = tx(lon); val cy = ty(lat)
        val r = ArrayList<Road>(); val b = ArrayList<Building>()
        for (dx in -1..1) for (dy in -1..1) tiles[key(cx + dx, cy + dy)]?.let { r += it.roads; b += it.buildings }
        return r to b
    }

    // ---- download + cache --------------------------------------------------------------------------------------

    private fun dir(): File? = MymavNav.contextOrNull()?.let { File(it.cacheDir, "navtiles14").also { d -> d.mkdirs() } }
    private fun fileOf(x: Int, y: Int) = dir()?.let { File(it, "${x}_$y.pbf") }

    private fun readDisk(x: Int, y: Int): ByteArray? {
        val f = fileOf(x, y) ?: return null
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 14L * 24 * 3600 * 1000) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    private fun tileTemplate(): String {
        template?.let { return it }
        val j = JSONObject(MymavHttp.request("GET", TILEJSON, timeoutMs = 15_000))
        val t = j.getJSONArray("tiles").getString(0)
        template = t
        return t
    }

    private fun fetch(x: Int, y: Int): ByteArray? {
        return try {
            val url = tileTemplate().replace("{z}", "$Z").replace("{x}", "$x").replace("{y}", "$y")
            val b = MymavHttp.getBytes(url)
            fileOf(x, y)?.writeBytes(b)
            b
        } catch (f: MymavHttp.Failure) {
            lastError = "Map data unavailable (${f.message})"
            template = null
            null
        }
    }

    // ---- Mapbox Vector Tile decoding (just the layers and fields we draw) ---------------------------------------

    private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[p++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); if (c and 0x80 == 0) return r; s += 7 } }
        fun skip(wt: Int) { when (wt) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = varint().toInt(); p += n }; 5 -> p += 4; else -> p = end } }
        fun bytesField(): Pb { val n = varint().toInt(); val sub = Pb(b, p, p + n); p += n; return sub }
        fun string(): String { val n = varint().toInt(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun packed(): IntArray { val n = varint().toInt(); val stop = p + n; val out = ArrayList<Int>(); while (p < stop) out.add(varint().toInt()); return out.toIntArray() }
        fun double8(): Double { var v = 0L; for (i in 0 until 8) v = v or ((b[p + i].toLong() and 0xff) shl (8 * i)); p += 8; return java.lang.Double.longBitsToDouble(v) }
        fun float4(): Float { var v = 0; for (i in 0 until 4) v = v or ((b[p + i].toInt() and 0xff) shl (8 * i)); p += 4; return java.lang.Float.intBitsToFloat(v) }
    }

    private fun zz(n: Int) = (n ushr 1) xor -(n and 1)

    private fun decode(data: ByteArray, tileX: Int, tileY: Int): Tile {
        val roads = ArrayList<Road>(); val buildings = ArrayList<Building>()
        val top = Pb(data)
        while (top.more()) {
            val tag = top.varint().toInt(); val field = tag shr 3; val wt = tag and 7
            if (field != 3 || wt != 2) { top.skip(wt); continue }
            val layer = top.bytesField()
            var name = ""; var extent = 4096
            val keys = ArrayList<String>(); val values = ArrayList<Any>(); val feats = ArrayList<Pb>()
            while (layer.more()) {
                val t = layer.varint().toInt(); val f = t shr 3; val w = t and 7
                when {
                    f == 1 && w == 2 -> name = layer.string()
                    f == 2 && w == 2 -> feats += layer.bytesField()
                    f == 3 && w == 2 -> keys += layer.string()
                    f == 4 && w == 2 -> {
                        val v = layer.bytesField(); var any: Any = ""
                        while (v.more()) {
                            val vt = v.varint().toInt(); val vf = vt shr 3; val vw = vt and 7
                            when {
                                vf == 1 && vw == 2 -> any = v.string()
                                vf == 2 && vw == 5 -> any = v.float4().toDouble()
                                vf == 3 && vw == 1 -> any = v.double8()
                                vf == 4 && vw == 0 -> any = v.varint().toDouble()
                                vf == 5 && vw == 0 -> any = v.varint().toDouble()
                                vf == 6 && vw == 0 -> any = zz(v.varint().toInt()).toDouble()
                                vf == 7 && vw == 0 -> any = v.varint() != 0L
                                else -> v.skip(vw)
                            }
                        }
                        values += any
                    }
                    f == 5 && w == 0 -> extent = layer.varint().toInt()
                    else -> layer.skip(w)
                }
            }
            if (name != "transportation" && name != "building") continue
            for (fb in feats) {
                var type = 0; var geom = IntArray(0); var tags = IntArray(0)
                while (fb.more()) {
                    val t = fb.varint().toInt(); val f = t shr 3; val w = t and 7
                    when {
                        f == 2 && w == 2 -> tags = fb.packed()
                        f == 3 && w == 0 -> type = fb.varint().toInt()
                        f == 4 && w == 2 -> geom = fb.packed()
                        else -> fb.skip(w)
                    }
                }
                val props = HashMap<String, Any>()
                var i = 0; while (i + 1 < tags.size) { val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1]); if (k != null && v != null) props[k] = v; i += 2 }
                val rings = geometry(geom, extent)
                if (name == "transportation" && type == 2) {
                    val kind = when (props["class"] as? String) {
                        "motorway", "trunk", "primary", "secondary", "tertiary" -> 0
                        "minor", "service" -> 1
                        "path", "track" -> 2
                        else -> -1
                    }
                    if (kind < 0) continue
                    for (r in rings) if (r.size >= 4) roads += toRoad(kind, r, extent, tileX, tileY)
                } else if (name == "building" && type == 3) {
                    val h = ((props["render_height"] as? Double) ?: 10.0).toFloat().coerceIn(2.5f, 150f)
                    for (r in rings) {
                        if (r.size < 8 || area(r) <= 0.0) continue   // exterior rings only
                        val n = r.size / 2
                        val la = DoubleArray(n); val lo = DoubleArray(n)
                        for (k in 0 until n) { val ll = lonLat(r[k * 2], r[k * 2 + 1], extent, tileX, tileY); lo[k] = ll[0]; la[k] = ll[1] }
                        buildings += Building(la, lo, h)
                    }
                }
            }
        }
        return Tile(roads, buildings)
    }

    private fun area(r: DoubleArray): Double { var a = 0.0; val n = r.size / 2; for (i in 0 until n) { val j = (i + 1) % n; a += r[i * 2] * r[j * 2 + 1] - r[j * 2] * r[i * 2 + 1] }; return a / 2.0 }

    /** Decode geometry commands into a list of (x,y) pair arrays (one per line / ring). */
    private fun geometry(cmds: IntArray, extent: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(); var cur = ArrayList<Double>()
        var x = 0; var y = 0; var i = 0
        while (i < cmds.size) {
            val c = cmds[i++]; val id = c and 7; val count = c ushr 3
            when (id) {
                1, 2 -> repeat(count) {
                    x += zz(cmds[i++]); y += zz(cmds[i++])
                    if (id == 1) { if (cur.size >= 4) out += cur.toDoubleArray(); cur = ArrayList() }
                    cur.add(x.toDouble()); cur.add(y.toDouble())
                }
                7 -> { if (cur.size >= 4) out += cur.toDoubleArray(); cur = ArrayList() }
            }
        }
        if (cur.size >= 4) out += cur.toDoubleArray()
        return out
    }

    private fun lonLat(px: Double, py: Double, extent: Int, tileX: Int, tileY: Int): DoubleArray {
        val n = (1 shl Z).toDouble()
        val wx = (tileX + px / extent) / n; val wy = (tileY + py / extent) / n
        return doubleArrayOf(wx * 360.0 - 180.0, Math.toDegrees(atan(sinh(PI * (1 - 2 * wy)))))
    }

    private fun toRoad(kind: Int, r: DoubleArray, extent: Int, tileX: Int, tileY: Int): Road {
        val n = r.size / 2; val la = DoubleArray(n); val lo = DoubleArray(n)
        for (k in 0 until n) { val ll = lonLat(r[k * 2], r[k * 2 + 1], extent, tileX, tileY); lo[k] = ll[0]; la[k] = ll[1] }
        return Road(kind, la, lo)
    }
}
