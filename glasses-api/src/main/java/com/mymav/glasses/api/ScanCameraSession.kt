package com.mymav.glasses.api

/**
 * Ensures Food and Shop do not run competing CameraX sessions.
 * [holder] is the mini-app id currently owning the phone camera.
 */
object ScanCameraSession {
    @Volatile
    var holder: String? = null
        private set

    /** Null = acquired; otherwise who already holds the camera. */
    fun tryAcquire(appId: String): String? {
        synchronized(this) {
            val current = holder
            if (current == null || current == appId) {
                holder = appId
                return null
            }
            return "The $current camera is open. Leave that app before scanning here."
        }
    }

    fun release(appId: String) {
        synchronized(this) {
            if (holder == appId) holder = null
        }
    }
}
