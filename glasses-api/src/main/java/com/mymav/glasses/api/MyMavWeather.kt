package com.mymav.glasses.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Free weather + local date for Ask MyMav.
 * Weather: Open-Meteo (no API key). Location: last-known GPS → IP geolocation fallback.
 */
object MyMavWeather {
    private const val TAG = "MyMavWeather"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun todayDateSpeech(): String {
        val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        return "Today is ${fmt.format(Date())}."
    }

    fun isDateQuery(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.contains("today") && (t.contains("date") || t.contains("day")) ||
            t.contains("what's the date") || t.contains("whats the date") ||
            t.contains("what is the date") || t.contains("what day is") ||
            t.contains("what is today's date") || t.contains("todays date") ||
            t.contains("today's date")
    }

    fun isWeatherQuery(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.contains("weather") || t.contains("temperature") ||
            t.contains("forecast") || t.contains("how hot") || t.contains("how cold") ||
            (t.contains("outside") && (t.contains("temp") || t.contains("rain")))
    }

    /**
     * Async weather line for the speech bubble. Always includes today's date.
     * Calls [onResult] on the main thread.
     */
    fun fetchWeatherSpeech(onResult: (String) -> Unit) {
        val date = todayDateSpeech().removeSuffix(".")
        io.execute {
            val speech = runCatching { buildWeatherSpeech(date) }
                .getOrElse { e ->
                    Log.w(TAG, "weather failed", e)
                    "$date. Weather unavailable offline — check connection and try again."
                }
            main.post { onResult(speech) }
        }
    }

    private fun buildWeatherSpeech(dateLine: String): String {
        val loc = resolveLocation()
            ?: return "$dateLine. Weather unavailable — couldn't resolve location (offline or no network)."
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
                "&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto"
        val json = httpGet(url) ?: return "$dateLine. Weather unavailable offline — try again when online."
        val current = JSONObject(json).optJSONObject("current")
            ?: return "$dateLine. Weather data missing — try again shortly."
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        val cond = weatherCodeLabel(code)
        val place = loc.label
        val tempStr = if (temp.isNaN()) "?" else "${temp.roundToInt()}°C"
        val windStr = if (wind.isNaN()) "" else ", wind ${wind.roundToInt()} km/h"
        return "$dateLine. $place: $cond, $tempStr$windStr."
    }

    private data class Loc(val lat: Double, val lon: Double, val label: String)

    private fun resolveLocation(): Loc? {
        deviceLocation()?.let { return it }
        return ipLocation()
    }

    private fun deviceLocation(): Loc? {
        val ctx = appContext ?: return null
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val best = providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        return Loc(best.latitude, best.longitude, "Near you")
    }

    private fun ipLocation(): Loc? {
        // ip-api.com — free, no key, HTTP (cleartext allowed on app).
        val body = httpGet("http://ip-api.com/json/?fields=status,lat,lon,city,country")
            ?: return null
        val json = JSONObject(body)
        if (json.optString("status") != "success") return null
        val lat = json.optDouble("lat", Double.NaN)
        val lon = json.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val city = json.optString("city").ifBlank { "Your area" }
        val country = json.optString("country")
        val label = if (country.isNotBlank()) "$city, $country" else city
        return Loc(lat, lon, label)
    }

    private fun httpGet(url: String): String? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MyMav/1.0")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Mixed conditions"
    }
}
