package com.mymav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.mymav.glasses.api.ShellSessionHolder
import com.mymav.glasses.api.SimulatorPrefs
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Keeps the glasses/simulator link up while MyMav is in the background or the phone is locked.
 *
 * Without a foreground service Android freezes the cached process a few seconds after the screen
 * locks, which silently kills the SDK's WebSocket. The watchdog also re-dials after the PC side
 * goes away (USB replug, `adb reverse` lost, simulator restart), probing the port first so it
 * doesn't churn the SDK while nothing is listening.
 */
class GlassesLinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var backoffMs = MIN_BACKOFF_MS
    private var nextAttemptAt = 0L
    private var lastAttemptAt = 0L
    private var status = ""

    private val tick = object : Runnable {
        override fun run() {
            check()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground("Starting glasses link…")
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NOW) {
            backoffMs = MIN_BACKOFF_MS
            nextAttemptAt = 0L
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun check() {
        if (!SimulatorPrefs.autoConnect(this)) {
            stopSelf()
            return
        }
        val session = ShellSessionHolder.get() ?: return
        if (session.isLinkReady) {
            backoffMs = MIN_BACKOFF_MS
            nextAttemptAt = 0L
            update("Glasses connected · ${session.simulatorHost.value}")
            return
        }
        val now = System.currentTimeMillis()
        // A connect() is in flight: give the SDK time to reach onReady before dialling again.
        if (now - lastAttemptAt < CONNECT_GRACE_MS || now < nextAttemptAt) return
        val host = SimulatorPrefs.host(this)
        nextAttemptAt = now + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        probeExecutor.execute {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, PORT), PROBE_TIMEOUT_MS) }
            }.isSuccess
            main.post {
                if (!open) {
                    update(
                        if (host == "127.0.0.1") "Waiting for the PC (USB + adb reverse) · retry in ${backoffMs / 1000}s"
                        else "Simulator $host:$PORT not reachable · retry in ${backoffMs / 1000}s",
                    )
                    return@post
                }
                Log.i(TAG, "simulator reachable at $host — reconnecting")
                lastAttemptAt = System.currentTimeMillis()
                update("Reconnecting to $host…")
                session.setSimulatorHost(host)
                runCatching { session.connectSimulator() }.onFailure { Log.w(TAG, "connect failed", it) }
            }
        }
    }

    private fun update(text: String) {
        if (text == status) return
        status = text
        Log.i(TAG, text)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(text))
    }

    private fun startInForeground(text: String) {
        status = text
        val n = build(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MyMav glasses link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        if (!isIgnoringBatteryOptimizations(this)) {
            val allow = PendingIntent.getActivity(
                this, 1, batteryExemptionIntent(this), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "Keep running when locked", allow).build())
        }
        return b.build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses link", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val TAG = "GlassesLink"
        private const val CHANNEL = "mymav_glasses_link"
        private const val NOTIFICATION_ID = 4410
        private const val ACTION_NOW = "com.mymav.app.GLASSES_LINK_NOW"
        private const val PORT = 8765
        private const val TICK_MS = 3_000L
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val CONNECT_GRACE_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 1_500

        /** Start (or nudge to retry immediately). Safe from the background: failures are logged, not thrown. */
        fun start(context: Context, retryNow: Boolean = false) {
            if (!SimulatorPrefs.autoConnect(context)) return
            val i = Intent(context, GlassesLinkService::class.java)
            if (retryNow) i.action = ACTION_NOW
            runCatching { context.startForegroundService(i) }
                .onFailure { Log.w(TAG, "could not start link service: ${it.javaClass.simpleName}") }
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

        fun batteryExemptionIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
