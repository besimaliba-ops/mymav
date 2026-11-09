package com.mymav.glasses.mav2

import android.content.Context
import com.mymav.glasses.api.GlassesSession

object GlassesSessionFactory {
    /**
     * When BuildConfig.HAS_MAV2_SDK is true, callers should use the SDK-backed
     * implementation from the `sdk` source set (wired in composeApp).
     */
    fun create(context: Context, fallback: GlassesSession): GlassesSession {
        return if (BuildConfig.HAS_MAV2_SDK) {
            // Prefer reflective load of SDK-backed class if present
            try {
                val clazz = Class.forName("com.mymav.glasses.mav2.Mav2SdkSession")
                val ctor = clazz.getConstructor(Context::class.java)
                ctor.newInstance(context.applicationContext) as GlassesSession
            } catch (_: Throwable) {
                Mav2GlassesSession(context.applicationContext, fallback)
            }
        } else {
            Mav2GlassesSession(context.applicationContext, fallback)
        }
    }
}
