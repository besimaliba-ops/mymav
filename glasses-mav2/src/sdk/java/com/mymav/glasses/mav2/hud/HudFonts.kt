package com.mymav.glasses.mav2.hud

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everysight.mav2.sdk.Evs
import com.everysight.mav2.sdk.resources.M2FontNode16To50
import com.everysight.mav2.sdk.resources.M2FontResource
import com.everysight.mav2.sdk.resources.M2FontResourceUrl
import com.everysight.mav2.sdk.resources.M2FontUrl
import com.everysight.mav2.sdk.resources.M2FontUrlBuilder
import com.everysight.mav2.sdk.resources.M2UploadableResource
import com.mymav.glasses.api.MymavHudPrefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Everysight Roboto fonts (2-bit anti-aliased atlases, drawn at native pixel size),
 * styled by the phone's Settings → Glasses text choice ([MymavHudPrefs]).
 *
 * Callers keep thinking in the old "stock scale" units: [pick] maps a scale to
 * the nearest Roboto size (stock line height is 22px × scale) and returns
 * scale 1.
 *
 * Resource lifecycle (all state is mutated on the main thread):
 *  - Every font is tracked per **connection generation** ([connGen]). A disconnect, reconnect or
 *    cache wipe starts a new generation, which instantly makes every earlier confirmation invalid.
 *  - A font is *confirmed* only when the SDK's own upload-completion callback reports an
 *    acknowledgement that is newer than the upload request issued in the current generation
 *    (`wasUploadedToGlasses()` alone proved stale across reconnects). Callbacks that belong to an
 *    older generation, a replaced font object or an obsolete style are ignored.
 *  - One coordinator ([pump]) issues uploads, de-duplicates requests, retries a bounded number of
 *    times and gives up on a size instead of looping.
 *  - Until the whole set is confirmed every label uses the stock built-in font (never blank,
 *    never half-uploaded); then the current screen is refreshed once.
 */
object HudFonts {
    private const val TAG = "HudFonts"
    private const val STOCK_LINE_PX = 22f
    private const val MAX_ATTEMPTS = 3
    private const val STALL_MS = 10_000L
    private const val WATCHDOG_MS = 1500L
    private const val WIPE_SETTLE_MS = 2500L
    private const val REFRESH_DEBOUNCE_MS = 150L
    private const val ACCEPT_CHECK_MS = 1500L
    private const val RELEASE_DELAY_MS = 1500L
    private const val REQUEST_TIMEOUT_MS = 4000L
    private const val QUIET_WAIT_MAX_MS = 5000L

    enum class Weight { Medium, Bold, ExtraBold, SansRegular, SansBold, SansItalic, SansBoldItalic }

    /**
     * Type ramp per weight: every even pixel size the SDK ships for Roboto (16-50) that the HUD
     * actually uses. Text is always drawn at scale 1.0 in the exact pixel-size font the SDK
     * rasterised, so edges stay crisp (no bitmap stretching) and the nearest size is never far off.
     */
    private val fullRamp = intArrayOf(16, 20, 24, 32)
    /**
     * Bundled Inter sizes. BUDGET: the glasses accept only ~11 fonts at once ('Failed: no more slots' beyond that),
     * so every style is capped at 2 faces x 4 sizes = 8 (10 failed intermittently).
     */
    private val sansRamp = intArrayOf(16, 20, 24, 32)
    private val ramp: Map<Weight, IntArray> = mapOf(
        Weight.Medium to fullRamp,
        Weight.Bold to fullRamp,
        Weight.ExtraBold to fullRamp,
        Weight.SansRegular to sansRamp,
        Weight.SansBold to sansRamp,
        Weight.SansItalic to sansRamp,
        Weight.SansBoldItalic to sansRamp,
    )

    /** One font size of one weight, created for exactly one connection generation. */
    private class Entry(val key: String, val weight: Weight, val size: Int, val font: M2FontResource, val gen: Int, val loadStartedAt: Long) {
        @Volatile var confirmedGen = -1
        /** The SDK handed back an object that already claimed to be uploaded before we created it. */
        @Volatile var stale = false
        @Volatile var requestedExplicitly = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    /** Keys whose load is in flight for the current generation. */
    private val loading = ConcurrentHashMap<String, Long>()
    private val genAttempts = ConcurrentHashMap<String, Int>()
    private val gaveUpKeys = ConcurrentHashMap.newKeySet<String>()
    private val usage = ConcurrentHashMap<String, AtomicInteger>()
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread when the current style's fonts are confirmed (once per readiness). */
    @Volatile var onReady: (() -> Unit)? = null

    /** Connection generation: bumps on disconnect / reconnect / cache wipe / style change. */
    @Volatile var connGen = 0
        private set
    /** True once something was loaded/uploaded in this generation (avoids double bumps). */
    private var genUsed = false
    /** Glasses link is up, so uploads can be delivered. */
    @Volatile private var sessionUp = false
    @Volatile private var readyGen = -1
    @Volatile private var readyStyle: MymavHudPrefs.FontStyle? = null
    /** A confirmed font was lost/re-confirmed after readiness: the screen needs one more rebind. */
    private var dirty = false
    @Volatile private var lastProgressAt = 0L
    @Volatile private var lastStartAt = 0L
    @Volatile private var lastWipeAt = 0L
    @Volatile private var activeStyle: MymavHudPrefs.FontStyle? = null
    /** One extra load pass after a 0-confirmed give-up (style change left old font slots held). */
    private var zeroConfirmedRetry = false
    private val listened = java.util.Collections.newSetFromMap(java.util.WeakHashMap<M2FontResource, Boolean>())

    /** Bumps whenever the usable font set changes so screens know to rebuild their texts. */
    @Volatile var epoch = 0
        private set

    private fun weightsFor(style: MymavHudPrefs.FontStyle): Set<Weight> = when (style) {
        MymavHudPrefs.FontStyle.Modern -> setOf(Weight.Medium, Weight.Bold)
        MymavHudPrefs.FontStyle.Bold -> setOf(Weight.Bold)
        MymavHudPrefs.FontStyle.Heavy -> setOf(Weight.Bold, Weight.ExtraBold)
        MymavHudPrefs.FontStyle.Sans -> setOf(Weight.SansRegular, Weight.SansBold)
        MymavHudPrefs.FontStyle.SansItalic -> setOf(Weight.SansItalic, Weight.SansBoldItalic)
        MymavHudPrefs.FontStyle.Classic -> emptySet()
    }

    private fun assetName(w: Weight): String? = when (w) {
        Weight.SansRegular -> "regular"
        Weight.SansBold -> "bold"
        Weight.SansItalic -> "italic"
        Weight.SansBoldItalic -> "bolditalic"
        else -> null
    }

    private fun key(weight: Weight, size: Int) = "${weight.name}-$size"

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }

    // ---- diagnostics ---------------------------------------------------------------------------

    /** Glasses cache slot (SDK-internal getter, read via reflection for diagnostics only). */
    private fun slotOf(r: Any): Int? = runCatching {
        r.javaClass.methods.firstOrNull { it.name.startsWith("getSlot") && it.parameterCount == 0 }?.invoke(r) as? Int
    }.getOrNull()

    private fun describe(e: Entry): String = runCatching {
        val f = e.font
        "${e.key}@${Integer.toHexString(System.identityHashCode(f))} up=${if (e.requestedExplicitly) f.wasUploadedToGlasses().toString() else "-"} busy=${f.isUploading} " +
            "err=${f.hasError} slot=${slotOf(f)} t=${f.uploadStartTime}/${f.uploadAckTime} " +
            "gen=g${e.gen} conf=g${e.confirmedGen} loadAt=${e.loadStartedAt}${if (e.stale) " STALE" else ""}"
    }.getOrElse { "${e.key} (describe failed: ${it.message})" }

    private fun up(e: Entry): Boolean = e.confirmedGen == connGen && runCatching { e.font.wasUploadedToGlasses() }.getOrDefault(false)

    /**
     * The SDK keeps ONE 48-entry cache-storage table shared by every resource type (GlassesState.cache, keyed by
     * type+crc, random free slot, 127 when full). Per-type *active* slots (onCacheActive) are separate tables.
     * Logs how that shared table is occupied so font/image pressure is visible.
     */
    /**
     * The SDK identifies cached resources by the CRC of their bytes. Every generation of the same face has the
     * same CRC, so a new object can "alias" an old, already-released object (up, but without a slot of its own) and
     * end up drawn with whatever font later reused that slot: wrong metrics, garbled labels. A few unique trailing
     * bytes give every uploaded copy its own identity; the SIF2 reader ignores bytes past the declared data.
     */
    private fun withUniqueTail(sif: ByteArray): ByteArray {
        val salt = (System.nanoTime() xor (connGen.toLong() shl 40)).toInt()
        return sif + byteArrayOf((salt ushr 24).toByte(), (salt ushr 16).toByte(), (salt ushr 8).toByte(), salt.toByte())
    }

    // ---- targeted cache clearing ---------------------------------------------------------------
    //
    // clearAllCache() (mask 63) also wipes the built-in font on the glasses side: afterwards every stock-font
    // label (Classic style, loading fallback, status bar) stays blank for the rest of the session while the SDK
    // still believes the font is fine. The shared cache table does not mark which rows are built-in, but the
    // rows that matter are exactly the Roboto/Inter fonts we upload (an old row with the same CRC makes the SDK
    // treat a new font object as an "unplaced alias", and old sets would fill the 48-row table). Those fonts
    // have fixed CRCs, so we remember every CRC we ever created (also across app restarts) and clear only rows
    // that carry one of them, one row at a time. Anything else (built-in fonts, images) is left alone.

    private val knownCrcs = LinkedHashSet<Int>()
    private var crcsLoaded = false

    private fun crcOf(font: Any): Int? = runCatching {
        var c: Class<*>? = font.javaClass
        while (c != null) { try { return@runCatching c.getDeclaredField("crc").apply { isAccessible = true }.getInt(font) } catch (_: NoSuchFieldException) { c = c.superclass } }
        null
    }.getOrNull()

    private fun loadKnownCrcs() {
        if (crcsLoaded) return
        crcsLoaded = true
        val ctx = MymavHudPrefs.context() ?: return
        runCatching {
            ctx.getSharedPreferences("mymav_font_crcs", android.content.Context.MODE_PRIVATE)
                .getStringSet("crcs", emptySet())?.forEach { knownCrcs.add(it.toLong(16).toInt()) }
        }
    }

    private fun rememberCrc(font: Any) {
        loadKnownCrcs()
        val crc = crcOf(font) ?: return
        if (crc == 0 || !knownCrcs.add(crc)) return
        while (knownCrcs.size > 300) knownCrcs.remove(knownCrcs.first())
        val ctx = MymavHudPrefs.context() ?: return
        runCatching {
            ctx.getSharedPreferences("mymav_font_crcs", android.content.Context.MODE_PRIVATE).edit()
                .putStringSet("crcs", knownCrcs.map { it.toUInt().toString(16) }.toSet()).apply()
        }
    }

    /** Clear every cache row whose CRC is in [crcs] (null = all CRCs we have ever created). Returns rows cleared, or -1 if unavailable. */
    private fun clearRows(crcs: Set<Int>?, reason: String): Int = runCatching {
        loadKnownCrcs()
        val target = crcs ?: knownCrcs
        val wrapper = Evs.javaClass.getDeclaredMethod("getWrapper\$sdk_debug").apply { isAccessible = true }.invoke(Evs)!!
        val core = wrapper.javaClass.getMethod("getCore").invoke(wrapper)!!
        val maker = core.javaClass.getMethod("getOutPktMaker").invoke(core)!!
        val clear = maker.javaClass.getMethod("clearCache", Int::class.javaPrimitiveType)
        val gs = core.javaClass.getMethod("getGlassesState").invoke(core)!!
        val arr = gs.javaClass.getDeclaredMethod("getCache\$core_debug").apply { isAccessible = true }.invoke(gs) as Array<*>
        var n = 0
        for (row in 0 until minOf(arr.size, 48)) {
            val item = arr[row] ?: continue
            val crc = item.javaClass.methods.first { it.name.startsWith("getCrc") && it.parameterCount == 0 }.invoke(item) as Int
            if (crc != 0 && crc in target) { clear.invoke(maker, row); n++ }
        }
        if (n > 0 || crcs == null) Log.i(TAG, "FD cache rows cleared ($reason): $n of ${target.size} known crcs")
        n
    }.getOrElse { Log.w(TAG, "FD targeted cache clear failed ($reason): ${it.javaClass.simpleName} ${it.message}"); -1 }

    /** Clear our fonts' rows (all known CRCs); keeps built-in fonts and images intact. */
    fun wipeGlassesCache(reason: String) {
        val n = clearRows(null, reason)
        if (n < 0) {
            Log.w(TAG, "FD targeted clear unavailable ($reason): falling back to clearAllCache")
            runCatching { Evs.resourcesService.clearAllCache() }
        }
    }

    /** ms until the glasses have finished their asynchronous cache wipe (0 = settled). */
    fun wipeRemainingMs(): Long {
        val since = System.currentTimeMillis() - lastWipeAt
        return if (since in 0 until WIPE_SETTLE_MS) WIPE_SETTLE_MS - since + 100L else 0L
    }

    fun logCache(tag: String) {
        runCatching {
            val wrapper = Evs.javaClass.getDeclaredMethod("getWrapper\$sdk_debug").apply { isAccessible = true }.invoke(Evs)!!
            val core = wrapper.javaClass.getMethod("getCore").invoke(wrapper)!!
            val gs = core.javaClass.getMethod("getGlassesState").invoke(core)!!
            val arr = gs.javaClass.getDeclaredMethod("getCache\$core_debug").apply { isAccessible = true }.invoke(gs) as Array<*>
            val byType = java.util.TreeMap<Int, IntArray>() // type -> [occupied, uploaded, uploading]
            var occupied = 0
            val rows = StringBuilder()
            for ((idx, item) in arr.withIndex()) {
                item ?: continue
                val c = item.javaClass
                val lru = c.getMethod("getLru").invoke(item) as Int
                val crc = c.methods.first { it.name.startsWith("getCrc") && it.parameterCount == 0 }.invoke(item) as Int
                val t = c.getMethod("getType").invoke(item) as Int
                val up = c.getMethod("getUploaded").invoke(item) as Boolean
                val ing = c.getMethod("getUploading").invoke(item) as Boolean
                if (lru == 0 && crc == 0 && !up && !ing) continue
                occupied++
                rows.append(" r").append(idx).append(":t").append(t).append(":c").append(Integer.toHexString(crc)).append(if (up) "U" else "-")
                val a = byType.getOrPut(t) { IntArray(3) }
                a[0]++; if (up) a[1]++; if (ing) a[2]++
            }
            Log.i(TAG, "FD CACHE[$tag] gen=$connGen occupied=$occupied/${arr.size} " +
                byType.entries.joinToString(" ") { "type${it.key}:occ=${it.value[0]},up=${it.value[1]},busy=${it.value[2]}" } + " rows:$rows")
        }.onFailure { Log.w(TAG, "FD CACHE[$tag] unavailable: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun snapshot(tag: String) {
        val style = MymavHudPrefs.fontStyle()
        val list = neededEntries(style) ?: entries.values.toList()
        val conf = list.count { it.confirmedGen == connGen }
        val upN = list.count { up(it) }
        val busy = list.count { runCatching { it.font.isUploading }.getOrDefault(false) }
        val err = list.count { runCatching { it.font.hasError }.getOrDefault(false) }
        val pending = runCatching { Evs.resourcesService.pendingUploadCount() }.getOrDefault(-1)
        logCache(tag)
        Log.i(TAG, "FD gen=$connGen $tag style=$style fonts=${list.size} confirmed=$conf up=$upN busy=$busy err=$err gaveUp=${gaveUpKeys.size} queue=$pending ready=${ready()} sessionUp=$sessionUp")
        // Slot 127 = "no slot assigned"; only real shared slots are collisions.
        list.groupBy { slotOf(it.font) }.filter { it.key != null && it.key != 127 && it.value.size > 1 }.forEach { (slot, l) ->
            Log.w(TAG, "FD gen=$connGen SLOT COLLISION slot=$slot: ${l.joinToString { it.key }}")
        }
        Log.i(TAG, "FD gen=$connGen slots " + list.joinToString(" ") { "${it.size}:${slotOf(it.font)}${if (it.confirmedGen == connGen) "✓" else "?"}" })
        if (usage.isNotEmpty()) Log.i(TAG, "FD usage ${usage.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}")
        list.filter { it.confirmedGen != connGen }.forEach { Log.i(TAG, "FD gen=$connGen   unconfirmed ${describe(it)}") }
    }

    // ---- readiness -----------------------------------------------------------------------------

    /** True when every font of the current style is confirmed usable on the *current* connection. */
    fun ready(): Boolean {
        val style = MymavHudPrefs.fontStyle()
        return style == MymavHudPrefs.FontStyle.Classic || (readyGen == connGen && readyStyle == style)
    }

    private fun neededKeys(style: MymavHudPrefs.FontStyle): List<Triple<Weight, Int, String>> {
        val out = ArrayList<Triple<Weight, Int, String>>()
        for (w in weightsFor(style)) for (sz in ramp.getValue(w)) out.add(Triple(w, sz, key(w, sz)))
        return out
    }

    /** All entries of the style, or null while any is still missing (loading / not created yet). */
    private fun neededEntries(style: MymavHudPrefs.FontStyle): List<Entry>? {
        val out = ArrayList<Entry>()
        for ((_, _, k) in neededKeys(style)) out.add(entries[k] ?: return null)
        return out
    }

    /**
     * Start a new connection generation: everything created or confirmed before is unproven (the SDK's
     * own "uploaded" flags survive reconnects and the glasses' asynchronous cache wipe), so the old font
     * objects are dropped and fresh ones are created for the new generation.
     */
    private fun invalidate(reason: String) {
        if (!genUsed) {
            Log.i(TAG, "FD gen=$connGen invalidate($reason): generation still unused, keeping it")
            return
        }
        connGen++
        genUsed = false
        readyGen = -1
        dirty = false
        epoch++
        // NOTE: never call freeResource() on dropped fonts: it is deferred ("still in use") and later frees the *cache slot
        // index* of the old object, evicting whichever new font was randomly given that index (the "lost" thrash).
        requestQueue.clear()
        inflight = null
        entries.clear()
        loading.clear()
        genAttempts.clear()
        gaveUpKeys.clear()
        zeroConfirmedRetry = false
        main.removeCallbacks(refreshRunnable)
        Log.i(TAG, "FD gen=$connGen invalidate($reason): new generation, readiness reset, font objects dropped")
    }

    /**
     * Session hook for every lifecycle event: onReady / onUnReady / status=… / connect().
     * Only real link loss or (re)establishment changes readiness.
     */
    fun connectionEvent(what: String) {
        // Always deferred: SDK callbacks must return immediately (a slow callback drops the link).
        main.post {
            Log.i(TAG, "FD gen=$connGen connection event: $what")
            when {
                what == "onReady" -> {
                    sessionUp = true
                    schedulePump(0L)
                }
                what == "onUnReady" || what == "status=Disconnected" || what == "status=Failed" ||
                    what == "status=AuthFailed" || what == "status=BluetoothOff" -> {
                    sessionUp = false
                    // Uploads still queued for the dead link would otherwise be replayed on the next connection and
                    // alias the new generation's identical-content fonts (new objects were then born "uploaded", no slot).
                    runCatching { Evs.resourcesService.javaClass.getDeclaredMethod("clearUploads").apply { isAccessible = true }.invoke(Evs.resourcesService) }
                        .onFailure { Log.w(TAG, "clearUploads unavailable: ${it.message}") }
                    invalidate(what)
                }
                else -> Unit
            }
            if (what == "onReady" || what == "onUnReady") snapshot("after-$what")
        }
    }

    /**
     * A new glasses-side session started on an already-running app (connect() while ready): whatever
     * was uploaded before is gone, so nothing confirmed earlier counts any more.
     */
    fun glassesReconnected() {
        onMain {
            // Do NOT invalidate here: a genuine link loss already invalidated (onUnReady/disconnect) and the cache wipe
            // invalidates too. A plain connect() on a live session keeps the fonts resident on the glasses; throwing
            // the objects away only produces fresh objects the SDK instantly re-acks with no slot (slot 127), which
            // then render as garbage.
            sessionUp = true
            preload()
        }
    }

    /** Called by the session right after it clears the glasses cache (which finishes asynchronously). */
    fun cacheWiped() {
        onMain {
            firstSetPending = true
            lastWipeAt = System.currentTimeMillis()
            invalidate("cache wiped")
        }
    }

    /**
     * The glasses only have 127 cache slots and CachedManualDelete fonts are never evicted, so
     * switching style without a wipe leaks 13-26 slots per switch. Start each style from a clean cache.
     */
    private fun wipeIfStyleChanged(style: MymavHudPrefs.FontStyle) {
        val prev = activeStyle
        activeStyle = style
        if (prev == null || prev == style) return
        genUsed = true // force a new generation: callbacks of the old style's fonts must be ignored
        // The SDK allocates glasses font slots from its own bitset and only gives them back through
        // release/free; the old style's slots would otherwise leak until "Failed: no more slots".
        retired.addAll(entries.values.map { it.font })
        invalidate("style $prev -> $style")
        wipeGlassesCache("style change")
        runCatching { HudImageCache.reset() }
        lastWipeAt = System.currentTimeMillis()
        Log.i(TAG, "style $prev -> $style: glasses cache wiped")
    }

    /** Load (phone cache first) and upload what the current style needs. Idempotent. */
    fun preload() {
        onMain {
            val style = MymavHudPrefs.fontStyle()
            wipeIfStyleChanged(style)
            if (weightsFor(style).isEmpty()) {
                declareReady()
                return@onMain
            }
            if (ready()) {
                // Text-size (or no-op) change: nothing to upload, just rebind the screen once.
                epoch++
                requestRefresh()
                return@onMain
            }
            schedulePump(0L)
        }
    }

    /** Inter faces are bundled as SIF2 assets (tools/make_sif2.py) — no download needed. */
    private fun loadAsset(weight: Weight, size: Int, startGen: Int, startedAt: Long): Boolean {
        val name = assetName(weight) ?: return false
        val ctx = MymavHudPrefs.context() ?: return false
        return runCatching {
            val sif = withUniqueTail(ctx.assets.open("fonts/$name$size.sif2").use { it.readBytes() })
            val json = ctx.assets.open("fonts/$name$size.json").use { it.readBytes() }
            // Consecutive single-char suffixes -> consecutive name hashes, so the glasses' slot picker
            // can't collide two of our fonts (regular-16 kept being overwritten by a sibling).
            val idx = Weight.entries.indexOf(weight).minus(Weight.SansRegular.ordinal) * sansRamp.size + sansRamp.indexOf(size)
            val font = M2FontResource(sif, json, true, "mavinter${startGen}x" + ('a' + idx))
            onLoaded(weight, size, font, startGen, startedAt)
            true
        }.onFailure { Log.w(TAG, "asset font $name$size failed: ${it.message}") }.getOrDefault(false)
    }

    private fun startLoad(weight: Weight, size: Int, k: String) {
        val gen = connGen
        val startedAt = System.currentTimeMillis()
        genAttempts.merge(k, 1) { a, b -> a + b }
        loading[k] = startedAt
        genUsed = true
        lastStartAt = startedAt
        if (assetName(weight) != null) {
            if (!loadAsset(weight, size, gen, startedAt)) { loading.remove(k); Log.w(TAG, "FD gen=$gen asset load failed $k") }
            return
        }
        val url = urlFor(weight, size)
        if (url == null) { loading.remove(k); gaveUpKeys.add(k); return }
        // The generation is part of the resource name: the SDK keeps per-name upload history that survives a
        // reconnect (instant re-acks, slot 127), so every connection generation gets fresh identities.
        // Download the SIF2 + JSON ourselves (cached on the phone) and build the resource with a
        // consecutive, unique name: the SDK's URL loader names fonts by URL, whose hashes collide in the
        // glasses' tiny font slot table (slots 4-23) and silently overwrite each other.
        val idx = Weight.entries.indexOf(weight) * fullRamp.size + fullRamp.indexOf(size)
        Thread({
            val result = runCatching {
                val dir = java.io.File(MymavHudPrefs.context()!!.cacheDir, "robofonts").apply { mkdirs() }
                val sifUrl = fontUrlString(url, "sif2Url\$sdk_debug", "sif2")
                val jsonUrl = fontUrlString(url, "jsonUrl\$sdk_debug", "json")
                val sif = withUniqueTail(fetchCached(dir, sifUrl))
                val json = fetchCached(dir, jsonUrl)
                M2FontResource(sif, json, true, "mavrobo${gen}x" + ('a' + idx))
            }.onFailure { Log.w(TAG, "FD gen=$gen font download failed $k: ${it.message}") }.getOrNull()
            onMain { onLoaded(weight, size, result, gen, startedAt) }
        }, "HudFontFetch").start()
    }

    private fun fontUrlString(url: M2FontUrl, method: String, ext: String): String =
        runCatching { url.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(url) as String }
            .getOrElse { "https://storage.googleapis.com/evs-pub/maverickai/fonts/" + url.toString() + "." + ext }

    private fun fetchCached(dir: java.io.File, urlStr: String): ByteArray {
        val f = java.io.File(dir, urlStr.substringAfter("/fonts/").replace('/', '_'))
        if (f.isFile && f.length() > 0) return f.readBytes()
        val c = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        val bytes = c.inputStream.use { it.readBytes() }
        require(bytes.isNotEmpty()) { "empty $urlStr" }
        f.writeBytes(bytes)
        return bytes
    }

    private fun onLoaded(weight: Weight, size: Int, font: M2FontResource?, startGen: Int, startedAt: Long) {
        onMain {
            val k = key(weight, size)
            if (startGen != connGen) {
                Log.i(TAG, "FD gen=$connGen ignoring late load of $k from g$startGen")
                return@onMain
            }
            loading.remove(k)
            if (font == null) {
                Log.w(TAG, "FD gen=$connGen load of $k returned nothing")
                schedulePump(500L)
                return@onMain
            }
            rememberCrc(font)
            crcOf(font)?.let { if (it != 0) clearRows(setOf(it), "before upload of $k") }
            val e = Entry(k, weight, size, font, startGen, startedAt)
            // A brand-new object must have no upload history. If it does (SDK registry returned an old one),
            // an acknowledgement proves nothing about this connection.
            val ack0 = runCatching { font.uploadAckTime }.getOrDefault(0L)
            e.stale = ack0 in 1 until startedAt
            entries[k] = e
            watchUploads(e)
            Log.i(TAG, "FD gen=$connGen loaded ${describe(e)} (${entries.size})")
            // The SDK normally starts the upload by itself when the font finishes loading; the first events may
            // already have fired, so evaluate the current state once.
            onUploadEvent(e)
            // Fonts built from bytes are not uploaded by the SDK on their own: request once, the SDK queue serialises.
            if (!e.stale && !e.requestedExplicitly) {
                requestQueue.add(e)
                pumpRequests()
            }
            schedulePump(0L)
        }
    }

    /** If nothing started uploading by itself shortly after loading, ask for the upload explicitly (once). */
    private fun ensureStarted(e: Entry) {
        if (entries[e.key] !== e || e.gen != connGen || e.confirmedGen == connGen || e.requestedExplicitly) return
        val busy = runCatching { e.font.isUploading }.getOrDefault(false)
        val ack = runCatching { e.font.uploadAckTime }.getOrDefault(0L)
        val queue = runCatching { Evs.resourcesService.pendingUploadCount() }.getOrDefault(1)
        if (!busy && ack == 0L && queue == 0) {
            e.requestedExplicitly = true
            Log.i(TAG, "FD gen=$connGen ${e.key}: no upload started by itself — requesting explicitly")
            runCatching { Evs.resourcesService.requestResourceUpload(e.font) }
        }
    }

    /** SDK upload-state callback → main thread, tied to the Entry (and so to its connection generation). */
    private fun watchUploads(e: Entry) {
        if (!listened.add(e.font)) return
        runCatching {
            e.font.registerListener(object : M2UploadableResource.IOnResourceUploadedEvents {
                override fun onUploadChanged(resource: M2UploadableResource) {
                    onMain { onUploadEvent(e) }
                }
            })
        }
    }

    // ---- coordinator ---------------------------------------------------------------------------

    private val pumpRunnable = Runnable { pump() }
    private fun schedulePump(delayMs: Long) {
        main.removeCallbacks(pumpRunnable)
        main.postDelayed(pumpRunnable, delayMs)
    }

    /**
     * The single place fonts are loaded for a generation. Idempotent: it only starts what is missing,
     * waits for what is in flight, and gives up on a size after [MAX_ATTEMPTS] instead of looping.
     */
    private fun pump() {
        val style = MymavHudPrefs.fontStyle()
        if (style == MymavHudPrefs.FontStyle.Classic || !sessionUp) return
        val now = System.currentTimeMillis()
        val sinceWipe = now - lastWipeAt
        if (sinceWipe < WIPE_SETTLE_MS) {
            // clearAllCache() is asynchronous on the glasses: anything uploaded meanwhile is dropped there
            // although the SDK believes it arrived. Don't load (= auto-upload) anything until it has settled.
            schedulePump(WIPE_SETTLE_MS - sinceWipe + 50L)
            return
        }
        pumpRequests()
        val gen = connGen
        val stalled = now - maxOf(lastProgressAt, lastStartAt) > STALL_MS
        if (retired.isNotEmpty()) releaseRetiredNow("before load")
        var unresolved = 0
        var startedThisTick = loading.isNotEmpty() || inflight != null
        for ((w, sz, k) in neededKeys(style)) {
            if (gaveUpKeys.contains(k)) continue
            val e = entries[k]
            if (e == null) {
                if (loading.containsKey(k)) {
                    if (stalled && now - (loading[k] ?: now) > STALL_MS) { loading.remove(k) } else { unresolved++; continue }
                }
                if ((genAttempts[k] ?: 0) >= MAX_ATTEMPTS) {
                    gaveUpKeys.add(k)
                    Log.w(TAG, "FD gen=$gen giving up on $k after $MAX_ATTEMPTS attempts — it will use the nearest confirmed size")
                    continue
                }
                unresolved++
                // One new font object at a time. Creating all 8 at once after a style wipe made the SDK
                // report hasError/slot 127 immediately (font-slot budget ~11), burn 3 attempts in a few ms,
                // then declare Sans ready with 0 confirmed faces — pick() fell through to Classic stock.
                if (!startedThisTick) {
                    startLoad(w, sz, k)
                    startedThisTick = true
                }
                continue
            }
            if (e.confirmedGen == gen) {
                if (up(e)) continue
                markLost(e, "pump")
                unresolved++
                continue
            }
            // Created this generation, not confirmed yet: in flight.
            // Born "uploaded" with no slot and nothing in flight: an SDK registry alias, no transfer will ever happen.
            val alias = e.requestedExplicitly && now - e.loadStartedAt > ACCEPT_CHECK_MS &&
                runCatching { e.font.wasUploadedToGlasses() && !e.font.isUploading }.getOrDefault(false) && slotOf(e.font) == 127
            if (!stalled && !alias) { unresolved++; continue }
            Log.w(TAG, "FD gen=$gen ${e.key} " + (if (alias) "is an unplaced SDK alias (up, no slot)" else "made no progress for ${STALL_MS}ms") + " — recreating")
            entries.remove(k, e)
            unresolved++
        }
        if (unresolved == 0) declareReady() else schedulePump(400L)
    }

    /** Diagnostics: the shared cache-table row (GlassesState.cache[slotCache]) behind a font, and the font's own crc. */
    private fun cacheItemOf(font: Any): String = runCatching {
        fun field(o: Any, n: String): Any? {
            var c: Class<*>? = o.javaClass
            while (c != null) { try { return c.getDeclaredField(n).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
            return null
        }
        val sc = field(font, "slotCache") as? Int
        val crc = field(font, "crc") as? Int
        val wrapper = Evs.javaClass.getDeclaredMethod("getWrapper\$sdk_debug").apply { isAccessible = true }.invoke(Evs)!!
        val core = wrapper.javaClass.getMethod("getCore").invoke(wrapper)!!
        val gs = core.javaClass.getMethod("getGlassesState").invoke(core)!!
        val arr = gs.javaClass.getDeclaredMethod("getCache\$core_debug").apply { isAccessible = true }.invoke(gs) as Array<*>
        val row = sc?.let { arr.getOrNull(it) }
        "fontCrc=${crc?.toUInt()?.toString(16)} slotCache=$sc row=${row}"
    }.getOrElse { "n/a ${it.javaClass.simpleName}" }

    /**
     * Serial upload pipeline. Requesting all fonts at once made the SDK reserve every shared cache-table row up
     * front; the glasses' periodic cache sync cleared rows of fonts still waiting for their turn, so rows got
     * handed to a second font ("lost"). One font is requested at a time, the next when it is confirmed.
     */
    private val requestQueue = ArrayDeque<Entry>()
    private var inflight: Entry? = null
    private var inflightSince = 0L

    private fun pumpRequests() {
        val cur = inflight
        if (cur != null) {
            val unresolved = entries[cur.key] === cur && cur.gen == connGen && cur.confirmedGen != connGen &&
                !runCatching { cur.font.hasError }.getOrDefault(false) && System.currentTimeMillis() - inflightSince < REQUEST_TIMEOUT_MS
            if (unresolved) return
            inflight = null
        }
        while (requestQueue.isNotEmpty()) {
            val e = requestQueue.removeFirst()
            if (entries[e.key] !== e || e.gen != connGen || e.confirmedGen == connGen || e.requestedExplicitly) continue
            e.requestedExplicitly = true
            inflight = e
            inflightSince = System.currentTimeMillis()
            runCatching { Evs.resourcesService.requestResourceUpload(e.font) }
                .onFailure { Log.w(TAG, "FD gen=$connGen upload request failed ${e.key}: ${it.message}") }
            return
        }
    }

    private fun markLost(e: Entry, why: String) {
        if (e.confirmedGen != connGen) return
        Log.w(TAG, "FD gen=$connGen lost ${describe(e)} ($why)")
        Log.w(TAG, "FD gen=$connGen lost-detail ${cacheItemOf(e.font)}")
        e.confirmedGen = -1
        entries.remove(e.key, e) // recreate: a new object gets a fresh slot and a real transfer
        dirty = true
        epoch++
    }

    private fun onUploadEvent(e: Entry) {
        if (entries[e.key] !== e) return // replaced / dropped (new generation, recreate, style change)
        lastProgressAt = System.currentTimeMillis()
        val f = e.font
        // Not requested yet: polling wasUploadedToGlasses() would reserve a shared cache-table row early (see pumpRequests).
        if (!e.requestedExplicitly && !e.stale) { pumpRequests(); return }
        val upNow = runCatching { f.wasUploadedToGlasses() }.getOrDefault(false)
        val busy = runCatching { f.isUploading }.getOrDefault(false)
        val err = runCatching { f.hasError }.getOrDefault(false)
        val ack = runCatching { f.uploadAckTime }.getOrDefault(0L)
        Log.i(TAG, "FD gen=$connGen upload event ${describe(e)}")
        if (e.gen != connGen) {
            Log.i(TAG, "FD gen=$connGen ignoring event for ${e.key}: belongs to g${e.gen}")
            return
        }
        when {
            err -> {
                if (!e.requestedExplicitly) {
                    Log.w(TAG, "FD gen=$connGen ${e.key}: constructor upload error slot=${slotOf(f)} — requesting explicitly once")
                    requestQueue.add(e)
                    pumpRequests()
                    return
                }
                Log.w(TAG, "FD gen=$connGen ${e.key}: SDK reported an upload error — recreating after backoff")
                entries.remove(e.key, e)
                schedulePump(400L)
                return
            }
            // A fresh object cannot have an ack older than its creation: an ack after loadStartedAt is a real transfer.
            upNow && !busy && !e.stale && ack >= e.loadStartedAt && slotOf(f) != 127 -> if (e.confirmedGen != connGen) e.confirmedGen = connGen
            upNow && !busy && e.stale -> {
                Log.w(TAG, "FD gen=$connGen ${e.key}: stale SDK object (acked before it was created) — not evidence, recreating")
                entries.remove(e.key, e)
            }
            !upNow && !busy && e.confirmedGen == connGen -> markLost(e, "slot reassigned")
        }
        pumpRequests()
        schedulePump(0L)
    }

    /**
     * After a (re)connect the glasses may still hold font slot bindings from the previous session/process. The first
     * set uploaded into that state can end up drawn with another font's data (scrambled labels, reproduced after every
     * app restart and drop/rejoin) while a later set, uploaded after the first was released, is always clean. So the first
     * set after a connect is a throwaway: when it is complete it is released and loaded again, and only that second set
     * is declared ready (screens stay on the stock font until then).
     */
    @Volatile private var firstSetPending = false

    private fun declareReady() {
        val style = MymavHudPrefs.fontStyle()
        if (firstSetPending && weightsFor(style).isNotEmpty() && entries.isNotEmpty()) {
            firstSetPending = false
            Log.i(TAG, "FD gen=$connGen first set after connect complete: releasing and loading it again before declaring ready")
            genUsed = true
            retired.addAll(entries.values.map { it.font })
            invalidate("first set after connect")
            wipeGlassesCache("first set after connect")
            runCatching { HudImageCache.reset() }
            lastWipeAt = System.currentTimeMillis()
            main.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
            schedulePump(300L)
            return
        }
        val need = neededKeys(style)
        val confirmed = need.count { (_, _, k) ->
            val e = entries[k] ?: return@count false
            e.confirmedGen == connGen
        }
        if (need.isNotEmpty() && confirmed == 0) {
            Log.w(TAG, "FD gen=$connGen not declaring ready: 0 confirmed of ${need.size} gaveUp=${gaveUpKeys.size}")
            releaseRetiredNow("zero-confirmed")
            gaveUpKeys.clear()
            genAttempts.clear()
            val delay = if (!zeroConfirmedRetry) { zeroConfirmedRetry = true; 500L } else 2000L
            schedulePump(delay)
            return
        }
        val already = readyGen == connGen && readyStyle == style
        if (already && !dirty) return
        readyGen = connGen
        readyStyle = style
        genUsed = true
        dirty = false
        epoch++
        val excluded = need.map { it.third }.filter { gaveUpKeys.contains(it) }
        Log.i(TAG, "FD gen=$connGen READY style=$style confirmed=$confirmed/${need.size}" +
            if (excluded.isEmpty()) "" else " excluded=$excluded")
        snapshot("declared-ready")
        requestRefresh()
        main.removeCallbacks(watchdogRunnable)
        main.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private val refreshRunnable = Runnable {
        if (!ready()) return@Runnable
        Log.i(TAG, "FD gen=$connGen refreshing current screen once (epoch=$epoch)")
        logCache("refresh-pre")
        runCatching { onReady?.invoke() }
        main.postDelayed({ logCache("refresh+2s") }, 2000L)
        if (retired.isNotEmpty()) main.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
    }

    /** Fonts of replaced style sets; released once no screen references them any more (bounded retries). */
    private val retired = ArrayList<M2FontResource>()
    private var releaseTries = 0

    /** Free glasses font slots held by a replaced style so the new set can allocate (~11 live fonts). */
    private fun releaseRetiredNow(why: String) {
        if (retired.isEmpty()) return
        val iter = retired.iterator()
        var freed = 0
        var kept = 0
        while (iter.hasNext()) {
            val f = iter.next()
            // releaseResourceKeepCache frees the glasses font slot but NOT the shared cache-table row
            // (freeResource would evict whichever new font got that row: the "lost" thrash).
            val ok = runCatching { Evs.resourcesService.releaseResourceKeepCache(f) }.getOrDefault(true)
            if (ok) { iter.remove(); freed++ } else kept++
        }
        Log.i(TAG, "FD gen=$connGen released $freed retired fonts ($why), still referenced=$kept")
    }

    private val releaseRunnable: Runnable = Runnable {
        releaseRetiredNow("delayed")
        if (retired.isNotEmpty() && ++releaseTries < 5) main.postDelayed(releaseRunnable, 2000L) else releaseTries = 0
    }

    /** One debounced rebind of whatever screen is current now (never a stale one). */
    private fun requestRefresh() {
        main.removeCallbacks(refreshRunnable)
        // The glasses clear their caches asynchronously after clearAllCache(). Anything (stock-font glyphs
        // included) drawn inside that window can be wiped afterwards, leaving labels blank. Styles that need
        // no uploads (Classic) are "ready" immediately, so hold the redraw until the wipe has settled.
        val sinceWipe = System.currentTimeMillis() - lastWipeAt
        val delay = if (sinceWipe in 0 until WIPE_SETTLE_MS) maxOf(REFRESH_DEBOUNCE_MS, WIPE_SETTLE_MS - sinceWipe + 100L) else REFRESH_DEBOUNCE_MS
        main.postDelayed(refreshRunnable, delay)
    }

    /** Detects fonts whose cache slot was taken by another resource (images, avatar) after readiness. */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!ready()) return
            val list = neededEntries(MymavHudPrefs.fontStyle())
            if (list == null) { schedulePump(0L); main.postDelayed(this, WATCHDOG_MS); return }
            var lost = 0
            for (e in list) if (e.confirmedGen == connGen && !up(e)) { markLost(e, "watchdog"); lost++ }
            if (lost > 0) schedulePump(0L)
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---- font selection ------------------------------------------------------------------------

    data class Pick(val font: M2FontResource, val scale: Float, val roboto: Boolean)

    /**
     * Font + scale for a label that was designed at stock [stockScale].
     * [bold] = headings, caps labels and big numbers.
     * Until the set is confirmed (and for any size that is not confirmed right now) this returns the
     * stock built-in font, which needs no upload, so labels such as HOME are always readable.
     */
    fun pick(stockScale: Float, bold: Boolean): Pick {
        val style = MymavHudPrefs.fontStyle()
        // Stock font: size setting applies as a plain scale (also used while a style is still loading).
        val stockStep = MymavHudPrefs.textSize().step
        val stock = Pick(M2FontResource.fontSmall, stockScale * (1f + SIZE_STEP_FRACTION * stockStep), false)
        if (style == MymavHudPrefs.FontStyle.Classic || !ready()) return stock
        val weight = when (style) {
            MymavHudPrefs.FontStyle.Modern -> if (bold) Weight.Bold else Weight.Medium
            MymavHudPrefs.FontStyle.Bold -> Weight.Bold
            MymavHudPrefs.FontStyle.Heavy -> if (bold) Weight.ExtraBold else Weight.Bold
            MymavHudPrefs.FontStyle.Sans -> if (bold) Weight.SansBold else Weight.SansRegular
            MymavHudPrefs.FontStyle.SansItalic -> if (bold) Weight.SansBoldItalic else Weight.SansItalic
            MymavHudPrefs.FontStyle.Classic -> return stock
        }
        // Standard keeps the long-standing mapping (nearest uploaded size, drawn at scale 1).
        // Compact / Large then move the *effective* pixel size by SIZE_STEP_FRACTION per step. Most
        // labels are designed below the smallest Roboto size (16 px), so picking a different uploaded
        // size alone cannot make them smaller: the remainder is applied as a text scale on the
        // chosen font. Callers measure with this same Pick, so fitting/ellipsis stays consistent.
        val step = MymavHudPrefs.textSize().step
        val baseWanted = STOCK_LINE_PX * stockScale
        val standard = nearest(weight, baseWanted) ?: return stock
        val target = standard.size * (1f + SIZE_STEP_FRACTION * step)
        val chosen = if (step == 0) standard else (nearest(weight, target) ?: standard)
        val scale = if (step == 0) 1f else (target / chosen.size).coerceIn(0.7f, 1.4f).let { if (abs(it - 1f) < 0.06f) 1f else it }
        usage.getOrPut(chosen.key) { AtomicInteger() }.incrementAndGet()
        logPick(style, step, stockScale, bold, chosen.size, scale)
        return Pick(chosen.font, scale, true)
    }

    private const val SIZE_STEP_FRACTION = 0.16f
    private val pickLogged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun nearer(candidate: Entry, best: Entry?, wanted: Float): Boolean {
        val b = best ?: return true
        val dc = abs(candidate.size - wanted)
        val db = abs(b.size - wanted)
        return dc < db || (dc == db && candidate.size > b.size)
    }

    private fun nearest(weight: Weight, wanted: Float): Entry? {
        var bestUp: Entry? = null
        var bestHeld: Entry? = null
        for (s in ramp.getValue(weight)) {
            val e = entries[key(weight, s)] ?: continue
            if (up(e)) {
                if (nearer(e, bestUp, wanted)) bestUp = e
            } else if (e.confirmedGen == connGen || e.gen == connGen) {
                // Upload flag flickers when images fight the shared table — keep the
                // same face/size instead of falling through to stock (looks like a resize).
                if (nearer(e, bestHeld, wanted)) bestHeld = e
            }
        }
        return bestUp ?: bestHeld
    }

    private fun logPick(style: MymavHudPrefs.FontStyle, step: Int, designed: Float, bold: Boolean, size: Int, scale: Float) {
        val k = "$style/$step/$designed/$bold"
        if (pickLogged.putIfAbsent(k, true) == null) {
            Log.i(TAG, "PICK style=$style step=$step designedScale=$designed bold=$bold -> font=${size}px scale=${"%.2f".format(scale)} effective=${"%.1f".format(size * scale)}px")
        }
    }

    private fun urlFor(weight: Weight, size: Int): M2FontUrl? {
        val roboto = M2FontUrlBuilder.english.roboto
        return when (weight) {
            Weight.Medium -> sizeOf(roboto.medium, size)
            Weight.Bold -> sizeOf(roboto.bold, size)
            Weight.ExtraBold -> sizeOf(roboto.extraBold, size)
            else -> null
        }
    }

    private fun sizeOf(n: M2FontNode16To50, size: Int): M2FontUrl? = when (size) {
        16 -> n.size16
        18 -> n.size18
        20 -> n.size20
        22 -> n.size22
        24 -> n.size24
        26 -> n.size26
        28 -> n.size28
        30 -> n.size30
        32 -> n.size32
        34 -> n.size34
        36 -> n.size36
        38 -> n.size38
        40 -> n.size40
        42 -> n.size42
        44 -> n.size44
        46 -> n.size46
        48 -> n.size48
        50 -> n.size50
        else -> null
    }
}
