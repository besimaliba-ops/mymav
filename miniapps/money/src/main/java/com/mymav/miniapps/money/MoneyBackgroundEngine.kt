package com.mymav.miniapps.money

import android.content.Context

/** Seeds a demo EUR → home currency quote when MyMav starts. */
object MoneyBackgroundEngine {
    @Volatile
    private var seeded = false

    fun seedIfNeeded(context: Context) {
        if (seeded) return
        seeded = true
        val store = MoneyStore(context)
        if (store.lastQuote() == null) {
            store.seedDemoQuote()
        }
    }

    fun resetSeedFlag() {
        seeded = false
    }
}
