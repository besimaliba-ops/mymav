package com.mymav.glasses.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Phone + glasses battery levels for the home status bar. -1 = unknown. */
object MymavDeviceStatus {
    @Volatile var glassesPercent: Int = -1
    @Volatile var glassesCharging: Boolean = false
    @Volatile private var phone: Int = -1
    @Volatile private var phoneCharge: Boolean = false
    @Volatile private var installed = false

    fun phonePercent(): Int = phone
    fun phoneCharging(): Boolean = phoneCharge

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { read(i) }
        }
        // Sticky broadcast gives the current value immediately.
        read(runCatching { app.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull())
    }

    private fun read(i: Intent?) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) phone = (level * 100 / scale).coerceIn(0, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        phoneCharge = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }
}
