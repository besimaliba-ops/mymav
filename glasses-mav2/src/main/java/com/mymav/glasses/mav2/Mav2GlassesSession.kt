package com.mymav.glasses.mav2

import android.content.Context
import com.mymav.glasses.api.ConnectionState
import com.mymav.glasses.api.GlassesSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real SDK backend when built with -Pmaverick.useSdk=true (see Mav2GlassesSessionSdk).
 * This default type is a compile-safe placeholder that mirrors stub messaging when the
 * SDK artifact is not on the classpath; the Sdk implementation is selected by factory.
 */
class Mav2GlassesSession(
    private val appContext: Context,
    private val delegate: GlassesSession,
) : GlassesSession by delegate {
    override val backendName: String = "mav2"
}
