package com.mymav.glasses.api

/**
 * Descriptor + lifecycle hooks for a MyMav mini-app.
 */
data class MiniAppDescriptor(
    val id: String,
    val title: String,
    val subtitle: String,
    val route: String,
    val requiredCapabilities: List<String> = emptyList(),
)

interface MiniApp {
    val descriptor: MiniAppDescriptor
    fun onEnter(session: GlassesSession)
    fun onLeave(session: GlassesSession)
}
