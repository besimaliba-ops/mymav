package com.mymav.glasses.mav2.hud

import android.util.Log
import com.everysight.mav2.sdk.resources.M2CacheScope
import com.everysight.mav2.sdk.resources.M2ImageBytes
import com.everysight.mav2.sdk.uikit.data.M2ImageType

/**
 * Phone-side cache of the SDK's packed-PNG payload for a given raw RGBA image.
 *
 * Encoding each panel/icon costs 20–70 ms on this phone and every HUD rebuild re-encoded
 * every image. The payload is copied out of the resource the SDK just encoded, then later
 * reconstructions use encode=false (the SDK only appends a CRC). Bounded LRU; reconnect
 * does not need a wipe because this is phone memory, not a glasses texture slot.
 *
 * Used from [newImageResource] in HudPrimitives (protected). First use self-checks that a
 * cached payload round-trips to the same bytes; a mismatch disables the cache.
 */
internal object HudEncodedCache {
    private const val TAG = "HudImg"
    private const val MAX_BYTES = 3 * 1024 * 1024
    private val map = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var bytes = 0
    @Volatile private var verified = false
    @Volatile private var disabled = false

    private val readers: Pair<java.lang.reflect.Method, java.lang.reflect.Method>? by lazy {
        runCatching {
            val methods = com.everysight.mav2.sdk.resources.M2BinaryResource::class.java.methods
            val data = methods.first { it.name.startsWith("getResource$") && it.returnType == ByteArray::class.java }
            val size = methods.first { it.name.startsWith("getSize$") && it.returnType == Int::class.javaPrimitiveType }
            data to size
        }.onFailure { Log.w(TAG, "encoded-image cache disabled: ${it.message}") }.getOrNull()
    }

    fun key(raw: ByteArray, w: Int, h: Int): String = "${w}x$h:${raw.contentHashCode()}"

    @Synchronized
    fun get(key: String): ByteArray? {
        if (disabled) return null
        return map[key]
    }

    fun remember(key: String, res: M2ImageBytes) {
        val (dataM, sizeM) = readers ?: return
        if (disabled) return
        val payload = runCatching {
            val data = dataM.invoke(res) as? ByteArray ?: return
            val size = sizeM.invoke(res) as Int
            if (size <= 4 || size > data.size) return
            val body = data.copyOf(size - 4)
            if (!verified) {
                val again = M2ImageBytes(
                    body.copyOf(), M2ImageType.Png, res.imageWidth, res.imageHeight,
                    M2CacheScope.NotCached, false, true, false,
                )
                val same = (dataM.invoke(again) as? ByteArray)?.let { b ->
                    sizeM.invoke(again) == size && b.copyOf(size).contentEquals(data.copyOf(size))
                } == true
                Log.i(TAG, "encoded-image cache self-check ${if (same) "OK" else "MISMATCH, disabled"}")
                verified = true
                if (!same) {
                    disabled = true
                    return
                }
            }
            body
        }.getOrNull() ?: return
        synchronized(this) {
            map.put(key, payload)?.let { bytes -= it.size }
            bytes += payload.size
            val it = map.entries.iterator()
            while (bytes > MAX_BYTES && it.hasNext()) {
                bytes -= it.next().value.size
                it.remove()
            }
        }
    }

    fun reuse(payload: ByteArray, width: Int, height: Int, scope: M2CacheScope): M2ImageBytes {
        HudPerf.count("img.enc.hit")
        return M2ImageBytes(payload.copyOf(), M2ImageType.Png, width, height, scope, false, true, false)
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }
}
