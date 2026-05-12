package com.analytic.atribution.gb.paywall

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Window
import com.analytic.atribution.gb.Constants
import com.analytic.atribution.gb.sharedPreferences

/**
 * Auto-collects two paywall-related metrics that don't require host-app instrumentation:
 *
 * - `session_length_first_ms`: length of the very first foreground session after install.
 *   A session ends when the last resumed Activity is paused (no debounce — internal Activity
 *   transitions don't drop the resumed counter to 0 because Android resumes the next
 *   Activity before pausing the previous one on API 21+).
 *
 * - `taps_count_first_30s`: number of [MotionEvent.ACTION_DOWN] events on any Activity's
 *   Window during the first 30 seconds after the first Activity is created. The window is
 *   anchored once and locked unconditionally 30 seconds later, even if no taps came in.
 *
 * Both values are first-only (locked once and never overwritten) and persist across
 * process death via SharedPreferences. After both lock, the lifecycle callbacks are
 * unregistered so the tracker stops paying any cost.
 */
internal class SessionTracker(
    context: Context,
    private val onMetricLocked: () -> Unit,
) {
    private companion object {
        const val SESSION_LENGTH_FIRST_MS = "paywall.session_length_first_ms"
        const val FIRST_SESSION_LOCKED = "paywall.first_session_locked"
        const val TAPS_COUNT_FIRST_30S = "paywall.taps_count_first_30s"
        const val TAPS_LOCKED = "paywall.taps_locked"
        const val FIRST_30S_MS = 30_000L
    }

    private val appContext: Context = context.applicationContext

    @Volatile private var resumedCount = 0
    @Volatile private var sessionStartMs: Long = -1L
    @Volatile private var sessionLocked = false

    @Volatile private var tapsCount = 0
    @Volatile private var tapsLocked = false
    @Volatile private var tapsWindowAnchored = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false
    private var application: Application? = null

    fun start() {
        val prefs = sharedPreferences(appContext)
        sessionLocked = prefs.getBoolean(FIRST_SESSION_LOCKED, false)
        tapsLocked = prefs.getBoolean(TAPS_LOCKED, false)
        if (sessionLocked && tapsLocked) return

        val app = appContext as? Application
        if (app == null) {
            Log.w(Constants.LOG_TAG, "SessionTracker: applicationContext is not an Application")
            return
        }
        application = app
        app.registerActivityLifecycleCallbacks(callbacks)
        registered = true
    }

    fun getFields(): Map<String, Long?> {
        val prefs = sharedPreferences(appContext)
        fun read(key: String): Long? = prefs.getLong(key, -1L).takeIf { it >= 0L }
        return mapOf(
            "session_length_first" to read(SESSION_LENGTH_FIRST_MS),
            "taps_count_first_30s" to read(TAPS_COUNT_FIRST_30S),
        )
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (tapsLocked) return
            if (!tapsWindowAnchored) {
                tapsWindowAnchored = true
                mainHandler.postDelayed({ lockTaps() }, FIRST_30S_MS)
            }
            wrapWindowCallback(activity)
        }

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            if (sessionLocked) return
            if (resumedCount == 0 && sessionStartMs < 0L) {
                sessionStartMs = System.currentTimeMillis()
            }
            resumedCount++
        }

        override fun onActivityPaused(activity: Activity) {
            if (sessionLocked) return
            resumedCount = (resumedCount - 1).coerceAtLeast(0)
            if (resumedCount == 0 && sessionStartMs >= 0L) {
                val length = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)
                sharedPreferences(appContext).edit()
                    .putLong(SESSION_LENGTH_FIRST_MS, length)
                    .putBoolean(FIRST_SESSION_LOCKED, true)
                    .apply()
                sessionLocked = true
                Log.i(Constants.LOG_TAG, "session_length_first locked: ${length}ms")
                maybeUnregister()
                onMetricLocked()
            }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun wrapWindowCallback(activity: Activity) {
        try {
            val window = activity.window ?: return
            val original = window.callback ?: return
            window.callback = TapCountingCallback(original) {
                if (!tapsLocked) tapsCount++
            }
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "Failed to wrap Window.Callback", e)
        }
    }

    private fun lockTaps() {
        if (tapsLocked) return
        tapsLocked = true
        sharedPreferences(appContext).edit()
            .putLong(TAPS_COUNT_FIRST_30S, tapsCount.toLong())
            .putBoolean(TAPS_LOCKED, true)
            .apply()
        Log.i(Constants.LOG_TAG, "taps_count_first_30s locked: $tapsCount")
        maybeUnregister()
        onMetricLocked()
    }

    private fun maybeUnregister() {
        if (registered && sessionLocked && tapsLocked) {
            application?.unregisterActivityLifecycleCallbacks(callbacks)
            registered = false
        }
    }

    private class TapCountingCallback(
        private val original: Window.Callback,
        private val onTap: () -> Unit,
    ) : Window.Callback by original {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) onTap()
            return original.dispatchTouchEvent(event)
        }
    }
}
