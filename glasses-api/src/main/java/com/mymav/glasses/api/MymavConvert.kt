package com.mymav.glasses.api

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Unit conversion (calculated on the phone) and currency conversion (live ECB reference rates from frankfurter.dev,
 * cached; a cached rate is always labelled as cached with its age). Nothing here is produced by a language model.
 */
object MymavConvert {
    class Result(val text: String, val kind: AssistantLog.Kind, val sources: List<AssistantLog.Source> = emptyList(), val note: String = "")

    private class Unit(val dim: String, val toBase: Double, val label: String)

    // Base units: m, kg, l, m/s. Temperature is handled separately.
    private val units: Map<String, Unit> = buildMap {
        fun u(dim: String, f: Double, label: String, vararg names: String) = names.forEach { put(it, Unit(dim, f, label)) }
        u("len", 1.0, "m", "m", "meter", "meters", "metre", "metres")
        u("len", 1000.0, "km", "km", "kilometer", "kilometers", "kilometre", "kilometres")
        u("len", 0.01, "cm", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        u("len", 0.001, "mm", "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        u("len", 1609.344, "miles", "mi", "mile", "miles")
        u("len", 0.9144, "yards", "yd", "yds", "yard", "yards")
        u("len", 0.3048, "feet", "ft", "foot", "feet")
        u("len", 0.0254, "inches", "in", "inch", "inches")
        u("mass", 1.0, "kg", "kg", "kilo", "kilos", "kilogram", "kilograms")
        u("mass", 0.001, "g", "g", "gram", "grams")
        u("mass", 0.45359237, "lb", "lb", "lbs", "pound mass")
        u("mass", 0.0283495231, "oz", "oz", "ounce", "ounces")
        u("mass", 6.35029318, "stone", "st", "stone", "stones")
        u("vol", 1.0, "litres", "l", "liter", "liters", "litre", "litres")
        u("vol", 0.001, "ml", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        u("vol", 3.785411784, "US gallons", "gal", "gallon", "gallons", "us gallon", "us gallons")
        u("vol", 4.54609, "UK gallons", "uk gallon", "uk gallons", "imperial gallon", "imperial gallons")
        u("vol", 0.2365882365, "US cups", "cup", "cups")
        u("vol", 0.568261485, "UK pints", "pint", "pints")
        u("vol", 0.0147867648, "tbsp", "tbsp", "tablespoon", "tablespoons")
        u("vol", 0.00492892159, "tsp", "tsp", "teaspoon", "teaspoons")
        u("spd", 0.44704, "mph", "mph", "miles per hour")
        u("spd", 0.277777778, "km/h", "kph", "km/h", "kmh", "kilometers per hour", "kilometres per hour")
        u("spd", 1.0, "m/s", "m/s", "mps")
        u("spd", 0.514444444, "knots", "knot", "knots", "kn")
    }

    private val temps = mapOf(
        "c" to "C", "celsius" to "C", "centigrade" to "C", "°c" to "C",
        "f" to "F", "fahrenheit" to "F", "°f" to "F",
        "k" to "K", "kelvin" to "K",
    )

    private val currencies: Map<String, String> = buildMap {
        fun c(code: String, vararg names: String) { put(code.lowercase(), code); names.forEach { put(it, code) } }
        c("USD", "dollar", "dollars", "us dollar", "us dollars", "bucks")
        c("GBP", "pound sterling", "pounds sterling", "sterling", "quid", "british pound", "british pounds")
        c("EUR", "euro", "euros")
        c("JPY", "yen")
        c("TRY", "lira", "turkish lira", "liras")
        c("CAD", "canadian dollar", "canadian dollars")
        c("AUD", "australian dollar", "australian dollars")
        c("CHF", "swiss franc", "swiss francs", "franc", "francs")
        c("INR", "rupee", "rupees", "indian rupee", "indian rupees")
        c("CNY", "yuan", "renminbi", "rmb")
        c("SEK", "swedish krona", "krona")
        c("NOK", "norwegian krone")
        c("DKK", "danish krone")
        c("PLN", "zloty", "złoty")
        c("MXN", "mexican peso", "mexican pesos")
        c("BRL", "real", "reais", "brazilian real")
        c("ZAR", "rand")
        c("NZD", "new zealand dollar", "new zealand dollars", "kiwi dollar")
        c("SGD", "singapore dollar", "singapore dollars")
        c("HKD", "hong kong dollar", "hong kong dollars")
        c("KRW", "won", "korean won")
        c("CZK", "czech koruna", "koruna")
        c("HUF", "forint")
        c("RON", "leu", "romanian leu")
        c("ILS", "shekel", "shekels")
        c("THB", "baht")
    }

    private val CONV = Regex(
        "^(?:please )?(?:convert |what(?:'s| is) |how much is |how much are )?(-?\\d[\\d,]*(?:\\.\\d+)?)\\s*([a-z°/ ]+?)\\s+(?:to|in|into|equals?|as)\\s+([a-z°/ ]+?)\\s*\\??$",
    )
    private val HOWMANY = Regex(
        "^how many ([a-z°/ ]+?) (?:are |is )?(?:there )?in (?:a |an |one )?(-?\\d*[.]?\\d*)\\s*([a-z°/ ]+?)\\s*\\??$",
    )

    /** A parsed request, or null when [cleaned] is not a conversion. */
    class Request(val amount: Double, val from: String, val to: String)

    fun parse(cleaned: String): Request? {
        val t = cleaned.trim().removeSuffix("?").trim()
        CONV.find(t)?.let { m ->
            val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val from = m.groupValues[2].trim()
            val to = m.groupValues[3].trim()
            if (known(from) && known(to)) return Request(amt, from, to)
        }
        HOWMANY.find(t)?.let { m ->
            val to = m.groupValues[1].trim()
            val amt = m.groupValues[2].ifBlank { "1" }.toDoubleOrNull() ?: 1.0
            val from = m.groupValues[3].trim()
            if (known(from) && known(to)) return Request(amt, from, to)
        }
        return null
    }

    private fun known(name: String): Boolean {
        val n = name.lowercase().trim()
        return n in units || n in temps || n in currencies || n == "pound" || n == "pounds"
    }

    private fun fmt(v: Double): String {
        val a = abs(v)
        return when {
            a == 0.0 -> "0"
            a >= 1000 -> String.format(Locale.US, "%,.1f", v).removeSuffix(".0")
            a >= 10 -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            a >= 0.01 -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
            else -> String.format(Locale.US, "%.5f", v).trimEnd('0').trimEnd('.')
        }
    }

    /** Resolve "pound(s)": currency when the other side is a currency, mass otherwise. */
    private fun resolvePound(from: String, to: String): Pair<String, String> {
        fun isP(s: String) = s == "pound" || s == "pounds"
        val f = from.lowercase(); val t = to.lowercase()
        val fp = isP(f); val tp = isP(t)
        if (!fp && !tp) return f to t
        val other = if (fp) t else f
        val currency = other in currencies
        val rep = if (currency) "gbp" else "lb"
        return (if (fp) rep else f) to (if (tp) rep else t)
    }

    /** Local (non-network) result for unit conversions; null when this is a currency request or can't be converted. */
    fun convertUnits(r: Request): Result? {
        val (f, t) = resolvePound(r.from, r.to)
        if (f in currencies || t in currencies) return null
        val ft = temps[f]; val tt = temps[t]
        if (ft != null && tt != null) {
            val c = when (ft) { "C" -> r.amount; "F" -> (r.amount - 32) * 5 / 9; else -> r.amount - 273.15 }
            val out = when (tt) { "C" -> c; "F" -> c * 9 / 5 + 32; else -> c + 273.15 }
            return Result("${fmt(r.amount)} °$ft = ${fmt(out)} °$tt", AssistantLog.Kind.Local)
        }
        val fu = units[f]; val tu = units[t]
        if (fu == null || tu == null) return null
        if (fu.dim != tu.dim) return Result("I can't convert ${fu.label} to ${tu.label}: they measure different things.", AssistantLog.Kind.Local)
        val out = r.amount * fu.toBase / tu.toBase
        val assume = if (f.contains("gallon") || t.contains("gallon") || f.contains("cup") || t.contains("cup") || f.contains("pint") || t.contains("pint"))
            " (${listOf(fu, tu).filter { it.label.startsWith("US") || it.label.startsWith("UK") }.joinToString(", ") { it.label }} assumed)" else ""
        return Result("${fmt(r.amount)} ${fu.label} = ${fmt(out)} ${tu.label}$assume", AssistantLog.Kind.Local)
    }

    // ---- currency ----------------------------------------------------------------------------------------------

    private const val FX_PREFS = "mymav_fx"
    @Volatile private var ctx: Context? = null
    fun install(context: Context) { ctx = context.applicationContext }

    private fun hourMin(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(t))

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
        return when {
            m < 2 -> "just now"
            m < 90 -> "$m min ago"
            m < 36 * 60 -> "${m / 60} h ago"
            else -> "${m / 1440} days ago"
        }
    }

    /** Blocking: call off the main thread. */
    fun convertCurrency(r: Request): Result {
        val (f0, t0) = resolvePound(r.from, r.to)
        val from = currencies[f0] ?: return Result("I don't know the currency \"${r.from}\".", AssistantLog.Kind.Error)
        val to = currencies[t0] ?: return Result("I don't know the currency \"${r.to}\".", AssistantLog.Kind.Error)
        if (from == to) return Result("${fmt(r.amount)} $from = ${fmt(r.amount)} $to", AssistantLog.Kind.Local)
        val key = "${from}_$to"
        val prefs = ctx?.getSharedPreferences(FX_PREFS, Context.MODE_PRIVATE)
        val src = AssistantLog.Source("Frankfurter (European Central Bank rates)", "https://frankfurter.dev/")
        return try {
            val body = MymavHttp.request("GET", "https://api.frankfurter.dev/v1/latest?base=$from&symbols=$to", timeoutMs = 12_000)
            val o = JSONObject(body)
            val rate = o.getJSONObject("rates").getDouble(to)
            val date = o.optString("date")
            val now = System.currentTimeMillis()
            prefs?.edit()?.putString(key, "$rate|$date|$now")?.apply()
            Result(
                "${fmt(r.amount)} $from = ${fmt(r.amount * rate)} $to\nRate ${fmt(rate)} (ECB reference rate for $date, fetched ${hourMin(now)})",
                AssistantLog.Kind.Data, listOf(src),
            )
        } catch (e: Exception) {
            val cached = prefs?.getString(key, null)?.split('|')
            if (cached != null && cached.size == 3) {
                val rate = cached[0].toDouble(); val fetched = cached[2].toLong()
                Result(
                    "${fmt(r.amount)} $from = ${fmt(r.amount * rate)} $to\nCACHED rate ${fmt(rate)} (ECB reference for ${cached[1]}, fetched ${ago(fetched)}). Live rate unavailable: ${e.message?.take(80)}",
                    AssistantLog.Kind.Data, listOf(src), "cached",
                )
            } else {
                Result("I couldn't get a live exchange rate and have no cached one for $from to $to (${e.message?.take(80)}).", AssistantLog.Kind.Error)
            }
        }
    }
}
