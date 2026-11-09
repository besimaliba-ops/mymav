package com.mymav.glasses.api

/**
 * Live preview overlays for home widgets (e.g. Spotify recent cover + title).
 * Companions write here; [HomeBoardCatalog] merges into tiles. Never starts playback.
 */
object HomeWidgetPreviewStore {
    data class Preview(
        val title: String,
        val subtitle: String,
        val metric: String = "",
        val metricDetail: String = "",
        val imageBytes: ByteArray? = null,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
        val isVideo: Boolean = false,
        val lines: List<String> = emptyList(),
        /** Companion-reported state (see [HomeWidget.state]); empty when unknown. */
        val state: String = "",
    )

    private val byId = LinkedHashMap<String, Preview>()

    @Synchronized
    fun put(widgetId: String, preview: Preview) {
        byId[widgetId] = preview
    }

    private val providers = HashMap<String, () -> Preview?>()
    private val providerCache = HashMap<String, Pair<Long, Preview?>>()

    /** Live preview computed on demand (cached ~1.5s) — e.g. today's tasks. */
    @Synchronized
    fun provide(widgetId: String, fn: () -> Preview?) {
        providers[widgetId] = fn
        providerCache.remove(widgetId)
    }

    @Synchronized
    fun invalidate(widgetId: String) {
        providerCache.remove(widgetId)
    }

    @Synchronized
    fun get(widgetId: String): Preview? {
        val fn = providers[widgetId] ?: return byId[widgetId]
        val now = System.currentTimeMillis()
        val hit = providerCache[widgetId]
        if (hit != null && now - hit.first < 1_500L) return hit.second ?: byId[widgetId]
        val fresh = runCatching { fn() }.getOrNull()
        providerCache[widgetId] = now to fresh
        return fresh ?: byId[widgetId]
    }

    @Synchronized
    fun clear(widgetId: String) {
        byId.remove(widgetId)
    }
}
