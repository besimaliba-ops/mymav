package com.mymav.glasses.api

object MiniAppRegistry {
    private val apps = linkedMapOf<String, MiniApp>()

    fun register(app: MiniApp) {
        apps[app.descriptor.id] = app
    }

    /** Registration order (ignore user reorder). */
    fun allRegistered(): List<MiniApp> = apps.values.toList()

    /** User-ordered list for phone home + deep links. */
    fun all(): List<MiniApp> {
        val order = HomeBoardCatalog.currentOrderIds()
        val ordered = order.mapNotNull { apps[it] }
        val extras = apps.values.filter { it.descriptor.id !in order }
        return ordered + extras
    }

    fun get(id: String): MiniApp? = apps[id]

    fun reorder(fromIndex: Int, toIndex: Int): List<String> {
        val ids = all().map { it.descriptor.id }.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices) return ids
        val item = ids.removeAt(fromIndex)
        ids.add(toIndex, item)
        HomeBoardCatalog.setOrder(ids)
        return ids
    }

    fun moveUp(id: String): List<String> {
        val ids = all().map { it.descriptor.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i > 0) {
            ids.removeAt(i)
            ids.add(i - 1, id)
            HomeBoardCatalog.setOrder(ids)
        }
        return ids
    }

    fun moveDown(id: String): List<String> {
        val ids = all().map { it.descriptor.id }.toMutableList()
        val i = ids.indexOf(id)
        if (i >= 0 && i < ids.lastIndex) {
            ids.removeAt(i)
            ids.add(i + 1, id)
            HomeBoardCatalog.setOrder(ids)
        }
        return ids
    }
}
