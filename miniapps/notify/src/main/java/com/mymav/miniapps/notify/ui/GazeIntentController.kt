package com.mymav.miniapps.notify.ui

/**
 * Gaze intent routing for Notify glasslets (ported from android-native companion).
 * Reply target = upper-right (mic pill). Music controls = upper band.
 */
class GazeIntentController(
    private val onDownwardGlance: () -> Unit,
    private val onUpwardGlance: () -> Unit,
    private val onReplyTargetGaze: () -> Unit,
    private val onMusicControlGaze: (MusicGazeControl) -> Unit,
    private val downwardThreshold: Float = 0.68f,
    private val upwardThreshold: Float = 0.32f,
    private val replyTargetMinX: Float = 0.68f,
    private val replyTargetMaxY: Float = 0.36f,
    private val musicControlMinY: Float = 0.16f,
    private val musicControlMaxY: Float = 0.36f,
    private val cooldownMs: Long = 900L,
) {
    private var lastTriggerAtMillis = 0L

    fun onLineOfSightSample(verticalNormalized: Float) {
        val now = System.currentTimeMillis()
        if (verticalNormalized >= downwardThreshold && now - lastTriggerAtMillis >= cooldownMs) {
            lastTriggerAtMillis = now
            onDownwardGlance()
        } else if (verticalNormalized <= upwardThreshold && now - lastTriggerAtMillis >= cooldownMs) {
            lastTriggerAtMillis = now
            onUpwardGlance()
        }
    }

    fun onLineOfSightSample(horizontalNormalized: Float, verticalNormalized: Float) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMillis < cooldownMs) return

        if (horizontalNormalized >= replyTargetMinX && verticalNormalized <= replyTargetMaxY) {
            lastTriggerAtMillis = now
            onReplyTargetGaze()
        } else if (verticalNormalized in musicControlMinY..musicControlMaxY) {
            lastTriggerAtMillis = now
            onMusicControlGaze(
                when {
                    horizontalNormalized < 0.38f -> MusicGazeControl.Previous
                    horizontalNormalized > 0.62f -> MusicGazeControl.Next
                    else -> MusicGazeControl.PlayPause
                },
            )
        } else if (verticalNormalized >= downwardThreshold) {
            lastTriggerAtMillis = now
            onDownwardGlance()
        } else if (verticalNormalized <= upwardThreshold) {
            lastTriggerAtMillis = now
            onUpwardGlance()
        }
    }
}

enum class MusicGazeControl {
    Previous,
    PlayPause,
    Next,
}

interface MaverickLineOfSightSource {
    fun start(onGazeSample: (horizontalNormalized: Float, verticalNormalized: Float) -> Unit)
    fun stop()
}

class NoOpLineOfSightSource : MaverickLineOfSightSource {
    override fun start(onGazeSample: (horizontalNormalized: Float, verticalNormalized: Float) -> Unit) = Unit
    override fun stop() = Unit
}
