package com.mymav.glasses.api

import android.content.Context

/**
 * Persists mini-app display order + widget sizes (phone Apps grid + glasses home share this).
 */
class MiniAppOrderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null)?.trim().orEmpty()
        if (raw.isBlank()) return HomeBoardCatalog.defaultWidgets.map { it.id }
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun loadSizes(): Map<String, HomeWidgetSize> {
        val raw = prefs.getString(KEY_SIZES, null)?.trim().orEmpty()
        if (raw.isBlank()) {
            return HomeBoardCatalog.defaultWidgets.associate { it.id to it.size }
        }
        return raw.split(',')
            .mapNotNull { token ->
                val parts = token.split('=')
                if (parts.size != 2) return@mapNotNull null
                val id = parts[0].trim()
                if (id.isEmpty()) return@mapNotNull null
                id to HomeWidgetSize.fromKey(parts[1].trim())
            }
            .toMap()
    }

    fun saveOrder(ids: List<String>) {
        prefs.edit().putString(KEY_ORDER, ids.joinToString(",")).apply()
        HomeBoardCatalog.setOrder(ids)
    }

    fun saveSize(id: String, size: HomeWidgetSize) {
        HomeBoardCatalog.setSize(id, size)
        persistSizes(HomeBoardCatalog.currentSizes())
    }

    fun saveSizes(sizes: Map<String, HomeWidgetSize>) {
        HomeBoardCatalog.setSizes(sizes)
        persistSizes(HomeBoardCatalog.currentSizes())
    }

    fun applyToCatalog() {
        HomeBoardCatalog.setOrder(loadOrder())
        HomeBoardCatalog.setSizes(loadSizes())
    }

    private fun persistSizes(sizes: Map<String, HomeWidgetSize>) {
        val encoded = sizes.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        prefs.edit().putString(KEY_SIZES, encoded).apply()
    }

    companion object {
        private const val PREFS = "mymav_miniapp_order"
        private const val KEY_ORDER = "order_ids"
        private const val KEY_SIZES = "widget_sizes"
    }
}
