package com.mymav.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * Test-only phone UI driver for simulator verification (reached only from DebugHudReceiver's TEST_ONLY commands,
 * which are ignored in release builds). Tracks the resumed activity, injects taps/swipes into that activity's own
 * window, and dumps a downscaled capture of it to logcat as base64 chunks (reassembled on the PC).
 */
internal object DebugPhoneDriver {
    private var top: WeakReference<Activity>? = null
    @Volatile var openMiniHandler: ((String) -> Unit)? = null

    fun openMini(id: String) {
        openMiniHandler?.invoke(id) ?: Log.w("DebugPhoneDriver", "openMini not attached")
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun act(): Activity? = top?.get()

    /** Touch in window coordinates (px). Swipe when x2/y2 differ. */
    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val a = act() ?: run { Log.w("DebugPhoneDriver", "no resumed activity"); return }
        val h = Handler(Looper.getMainLooper())
        val down = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(down, t, action, x, y, 0)
        a.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x1, y1, down))
        val steps = if (x1 == x2 && y1 == y2) 0 else 12
        for (i in 1..steps) {
            h.postDelayed({
                val f = i / steps.toFloat()
                a.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, SystemClock.uptimeMillis()))
            }, ms * i / (steps + 1))
        }
        h.postDelayed({
            a.window.decorView.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x2, y2, SystemClock.uptimeMillis()))
        }, ms.coerceAtLeast(40L))
    }

    /** Types [text] into whatever has focus, as hardware key events (so Compose text fields see real key input). */
    fun type(text: String) {
        val a = act() ?: return
        val map = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        a.runOnUiThread {
            val evs = map.getEvents(text.toCharArray()) ?: return@runOnUiThread
            evs.forEach { a.window.decorView.dispatchKeyEvent(it) }
        }
    }

    fun key(code: Int) {
        val a = act() ?: return
        a.runOnUiThread {
            val t = SystemClock.uptimeMillis()
            a.window.decorView.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            a.window.decorView.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
        }
    }

    /** Same as the system Back gesture for the resumed activity (stays inside the app). */
    fun back() { act()?.runOnUiThread { @Suppress("DEPRECATION") act()?.onBackPressed() } }

    fun windowSize(): String {
        val v = act()?.window?.decorView ?: return "none"
        return "${v.width}x${v.height}"
    }

    /** Capture the activity window (PixelCopy, so it matches what is on screen), scale to [targetW] wide, JPEG, base64 in log chunks. */
    fun shot(tag: String, targetW: Int = 540) {
        val a = act() ?: run { Log.w("DebugPhoneDriver", "no resumed activity"); return }
        val v = a.window.decorView
        if (!v.isAttachedToWindow || v.width <= 0 || !a.window.decorView.viewRootImpl_isDrawable()) {
            Log.w("DebugPhoneDriver", "window has no drawable surface (screen off / not visible); not capturing"); return
        }
        val full = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        runCatching { android.view.PixelCopy.request(a.window, full, { result ->
            if (result != android.view.PixelCopy.SUCCESS) { Log.w("DebugPhoneDriver", "PixelCopy failed $result"); return@request }
            val bmp = Bitmap.createScaledBitmap(full, targetW, (targetW * v.height.toFloat() / v.width).toInt(), true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val chunks = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).chunked(3000)
            Log.i("PHONESHOT", "BEGIN $tag n=${chunks.size} size=${v.width}x${v.height}")
            chunks.forEachIndexed { i, s -> Log.i("PHONESHOT", "$tag $i $s") }
            Log.i("PHONESHOT", "END $tag")
        }, Handler(Looper.getMainLooper())) }.onFailure { Log.w("DebugPhoneDriver", "PixelCopy unavailable: ${it.message}") }
    }

    /** Ask the OS to turn the screen on / keep it on for the resumed activity (test device only). */
    fun wake() {
        val a = act() ?: return
        a.runOnUiThread {
            @Suppress("DEPRECATION")
            a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            runCatching { a.setTurnScreenOn(true); a.setShowWhenLocked(true) }
        }
    }

    private fun android.view.View.viewRootImpl_isDrawable(): Boolean = visibility == android.view.View.VISIBLE && windowVisibility == android.view.View.VISIBLE
}
