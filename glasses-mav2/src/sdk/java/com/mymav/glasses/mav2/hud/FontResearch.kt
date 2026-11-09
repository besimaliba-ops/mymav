package com.mymav.glasses.mav2.hud

import android.util.Base64
import android.util.Log
import com.everysight.mav2.sdk.resources.M2FontUrlBuilder

/** TEMP: dumps a few SDK font files to logcat so the glasses font format can be studied. */
object FontResearch {
    @Volatile private var started = false

    fun dump() {
        if (started) return
        started = true
        Thread {
            runCatching {
                val roboto = M2FontUrlBuilder.english.roboto
                val picks = listOf<Pair<String, Any?>>("m20" to roboto.medium.size20, "b16" to roboto.bold.size16)
                for ((tag, fu) in picks) {
                    for (kind in listOf("sif2", "json", "png")) {
                        val url = fu!!.javaClass.getMethod(kind + "Url\$sdk_debug").invoke(fu) as String
                        val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        Log.w("FONTDUMP", "BEGIN $tag $kind ${bytes.size} $url")
                        b64.chunked(3000).forEachIndexed { i, c ->
                            Log.w("FONTDUMP", "D $tag $kind $i $c")
                            Thread.sleep(4)
                        }
                        Log.w("FONTDUMP", "END $tag $kind")
                    }
                }
            }.onFailure { Log.w("FONTDUMP", "failed: $it") }
        }.start()
    }
}
