package com.mymav.miniapps.money

import android.content.Context

data class MoneyQuoteRecord(
    val foreignCurrency: String,
    val foreignAmount: Double,
    val homeCurrency: String,
    val homeAmount: Double,
    val label: String,
) {
    fun foreignLabel(): String = "$foreignCurrency ${formatAmount(foreignAmount, foreignCurrency)}"
    fun homeLabel(): String = "$homeCurrency ${formatAmount(homeAmount, homeCurrency)}"

    companion object {
        fun formatAmount(amount: Double, currency: String): String {
            val sym = when (currency) {
                "EUR" -> "€"
                "USD" -> "$"
                "GBP" -> "£"
                else -> ""
            }
            return if (sym.isNotEmpty()) {
                "$sym${"%.2f".format(amount)}"
            } else {
                "%.2f".format(amount)
            }
        }
    }
}

class MoneyStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var homeCurrency: String
        get() = prefs.getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) {
            prefs.edit().putString(KEY_HOME, value.uppercase()).apply()
        }

    fun lastQuote(): MoneyQuoteRecord? {
        val foreign = prefs.getString(KEY_LAST_FOREIGN, null) ?: return null
        val home = prefs.getString(KEY_LAST_HOME, homeCurrency) ?: homeCurrency
        val foreignAmt = prefs.getFloat(KEY_LAST_FOREIGN_AMT, 0f).toDouble()
        val homeAmt = prefs.getFloat(KEY_LAST_HOME_AMT, 0f).toDouble()
        val label = prefs.getString(KEY_LAST_LABEL, "") ?: ""
        if (foreignAmt <= 0.0) return null
        return MoneyQuoteRecord(foreign, foreignAmt, home, homeAmt, label)
    }

    fun saveQuote(quote: MoneyQuoteRecord) {
        prefs.edit()
            .putString(KEY_LAST_FOREIGN, quote.foreignCurrency)
            .putString(KEY_LAST_HOME, quote.homeCurrency)
            .putFloat(KEY_LAST_FOREIGN_AMT, quote.foreignAmount.toFloat())
            .putFloat(KEY_LAST_HOME_AMT, quote.homeAmount.toFloat())
            .putString(KEY_LAST_LABEL, quote.label)
            .apply()
    }

    fun convert(foreignCurrency: String, amount: Double, label: String = "Price tag"): MoneyQuoteRecord {
        val home = homeCurrency
        val rate = mockRateToHome(foreignCurrency, home)
        val converted = amount * rate
        val quote = MoneyQuoteRecord(foreignCurrency.uppercase(), amount, home, converted, label)
        saveQuote(quote)
        return quote
    }

    fun seedDemoQuote(): MoneyQuoteRecord = convert("EUR", 12.50, "Café menu")

    private fun mockRateToHome(foreign: String, home: String): Double {
        val f = foreign.uppercase()
        val h = home.uppercase()
        if (f == h) return 1.0
        // Mock ECB-ish crosses into GBP default home.
        val toGbp = when (f) {
            "GBP" -> 1.0
            "EUR" -> 0.86
            "USD" -> 0.79
            "JPY" -> 0.0051
            else -> 0.75
        }
        val fromGbp = when (h) {
            "GBP" -> 1.0
            "EUR" -> 1.16
            "USD" -> 1.27
            else -> 1.0
        }
        return toGbp * fromGbp
    }

    companion object {
        private const val PREFS = "mymav_money"
        private const val KEY_HOME = "home_currency"
        private const val DEFAULT_HOME = "GBP"
        private const val KEY_LAST_FOREIGN = "last_foreign"
        private const val KEY_LAST_HOME = "last_home_ccy"
        private const val KEY_LAST_FOREIGN_AMT = "last_foreign_amt"
        private const val KEY_LAST_HOME_AMT = "last_home_amt"
        private const val KEY_LAST_LABEL = "last_label"
    }
}
