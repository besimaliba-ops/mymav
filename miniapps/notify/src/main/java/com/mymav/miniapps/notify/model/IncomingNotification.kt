package com.mymav.miniapps.notify.model

enum class CompanionSourceApp(
    val packageName: String,
    val displayName: String,
    val shortLabel: String,
) {
    WhatsApp("com.whatsapp", "WhatsApp", "WA"),
    Instagram("com.instagram.android", "Instagram", "IG");

    companion object {
        /** Canonical packages + common aliases (e.g. WhatsApp Business). */
        private val PACKAGE_ALIASES = mapOf(
            "com.whatsapp" to WhatsApp,
            "com.whatsapp.w4b" to WhatsApp,
            "com.instagram.android" to Instagram,
        )

        fun fromPackageName(packageName: String): CompanionSourceApp? {
            return PACKAGE_ALIASES[packageName]
        }

        fun isSupportedPackage(packageName: String): Boolean {
            return fromPackageName(packageName) != null
        }
    }
}

enum class NotificationMediaKind(val label: String) {
    None(""),
    Image("Photo"),
    Video("Video");
}

data class IncomingNotification(
    val sourceApp: CompanionSourceApp,
    val sender: String,
    val body: String,
    /** StatusBarNotification.key — reply target (may repeat across messages). */
    val notificationKey: String = "",
    /**
     * Unique inbox row id so multiple messages from the same person can be flicked
     * through even when Android reuses [notificationKey].
     */
    val inboxId: String = "",
    val mediaKind: NotificationMediaKind = NotificationMediaKind.None,
    val mediaKey: String? = null,
    val postedAtMillis: Long = System.currentTimeMillis(),
) {
    val hasMedia: Boolean get() = mediaKind != NotificationMediaKind.None
    /** Prefer [inboxId] for list focus; fall back to notification key. */
    val focusId: String get() = inboxId.ifBlank { notificationKey }
}
