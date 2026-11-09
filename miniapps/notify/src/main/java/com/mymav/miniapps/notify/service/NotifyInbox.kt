package com.mymav.miniapps.notify.service

import com.mymav.glasses.api.NotifyChannelFilter
import com.mymav.miniapps.notify.model.CompanionSourceApp
import com.mymav.miniapps.notify.model.IncomingNotification
import java.util.LinkedHashMap
import java.util.UUID

/**
 * In-memory inbox of individual WhatsApp / Instagram **messages**.
 * Each arrival gets a unique [IncomingNotification.inboxId] so two texts from the
 * same person stay as separate rows you can flick through.
 */
object NotifyInbox {
    private const val MAX_ITEMS = 64
    private val byId = LinkedHashMap<String, IncomingNotification>()
    private var focusedId: String? = null
    @Volatile
    var channelFilter: NotifyChannelFilter = NotifyChannelFilter.All
        private set

    @Synchronized
    fun setChannelFilter(filter: NotifyChannelFilter) {
        channelFilter = filter
        val visible = filteredIds()
        if (focusedId !in visible) {
            focusedId = visible.lastOrNull()
        }
    }

    @Synchronized
    fun cycleChannelFilter(): NotifyChannelFilter {
        channelFilter = when (channelFilter) {
            NotifyChannelFilter.All -> NotifyChannelFilter.WhatsApp
            NotifyChannelFilter.WhatsApp -> NotifyChannelFilter.Instagram
            NotifyChannelFilter.Instagram -> NotifyChannelFilter.All
        }
        val visible = filteredIds()
        if (focusedId !in visible) {
            focusedId = visible.lastOrNull()
        }
        return channelFilter
    }

    @Synchronized
    fun upsert(message: IncomingNotification) {
        val replyKey = message.notificationKey.ifBlank { return }
        // Dedup only exact same body within 1.5s (Android double-delivery).
        val dup = byId.values.lastOrNull {
            it.notificationKey == replyKey &&
                it.body == message.body &&
                message.postedAtMillis - it.postedAtMillis < 1_500L
        }
        if (dup != null) {
            focusedId = dup.focusId
            return
        }
        val id = message.inboxId.ifBlank {
            "$replyKey#${message.postedAtMillis}#${UUID.randomUUID().toString().take(8)}"
        }
        val stored = message.copy(inboxId = id)
        byId.remove(id)
        byId[id] = stored
        while (byId.size > MAX_ITEMS) {
            val oldest = byId.keys.firstOrNull() ?: break
            byId.remove(oldest)
        }
        if (matchesFilter(stored)) {
            focusedId = id
        }
    }

    @Synchronized
    fun remove(keyOrId: String) {
        // Remove by inboxId, or all rows sharing a notificationKey.
        val toRemove = byId.filter { (id, msg) ->
            id == keyOrId || msg.notificationKey == keyOrId || msg.inboxId == keyOrId
        }.keys.toList()
        toRemove.forEach { byId.remove(it) }
        if (focusedId in toRemove || focusedId == keyOrId) {
            focusedId = filteredIds().lastOrNull()
        }
    }

    @Synchronized
    fun clear() {
        byId.clear()
        focusedId = null
    }

    @Synchronized
    fun items(): List<IncomingNotification> =
        byId.values.filter { matchesFilter(it) }

    @Synchronized
    fun allItems(): List<IncomingNotification> = byId.values.toList()

    @Synchronized
    fun size(): Int = items().size

    @Synchronized
    fun isEmpty(): Boolean = items().isEmpty()

    @Synchronized
    fun whatsAppCount(): Int =
        byId.values.count { it.sourceApp == CompanionSourceApp.WhatsApp }

    @Synchronized
    fun instagramCount(): Int =
        byId.values.count { it.sourceApp == CompanionSourceApp.Instagram }

    @Synchronized
    fun focused(): IncomingNotification? {
        val visible = items()
        if (visible.isEmpty()) return null
        val id = focusedId
        return visible.firstOrNull { it.focusId == id } ?: visible.lastOrNull()
    }

    @Synchronized
    fun focusedIndex(): Int {
        val visible = items()
        if (visible.isEmpty()) return 0
        val id = focusedId
        val idx = visible.indexOfFirst { it.focusId == id }
        return if (idx >= 0) idx else visible.lastIndex.coerceAtLeast(0)
    }

    @Synchronized
    fun focusNext(): IncomingNotification? {
        val visible = items()
        if (visible.isEmpty()) return null
        val idx = focusedIndex()
        focusedId = visible[(idx + 1) % visible.size].focusId
        return byId[focusedId]
    }

    @Synchronized
    fun focusPrev(): IncomingNotification? {
        val visible = items()
        if (visible.isEmpty()) return null
        val idx = focusedIndex()
        focusedId = visible[(idx - 1 + visible.size) % visible.size].focusId
        return byId[focusedId]
    }

    @Synchronized
    fun focusKey(keyOrId: String): IncomingNotification? {
        val item = byId[keyOrId]
            ?: byId.values.firstOrNull { it.notificationKey == keyOrId || it.inboxId == keyOrId }
            ?: return null
        if (!matchesFilter(item)) return null
        focusedId = item.focusId
        return item
    }

    private fun filteredIds(): List<String> =
        byId.values.filter { matchesFilter(it) }.map { it.focusId }

    private fun matchesFilter(message: IncomingNotification): Boolean =
        when (channelFilter) {
            NotifyChannelFilter.All -> true
            NotifyChannelFilter.WhatsApp -> message.sourceApp == CompanionSourceApp.WhatsApp
            NotifyChannelFilter.Instagram -> message.sourceApp == CompanionSourceApp.Instagram
        }
}
