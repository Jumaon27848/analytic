package com.analytic.atribution.gb.clarity

import android.content.Context
import android.util.Log
import android.view.View
import com.analytic.atribution.gb.Constants
import com.analytic.atribution.gb.appsflyer.ActivityPackageWatcher
import com.microsoft.clarity.Clarity
import com.microsoft.clarity.ClarityConfig
import com.microsoft.clarity.models.LogLevel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns Microsoft Clarity SDK initialization and exposes mask/unmask + consent helpers.
 *
 * Mirrors [com.analytic.atribution.gb.appsflyer.AppsFlyerManager]: init only when the
 * console-supplied projectId is non-empty AND the first activity created in the process
 * is one of the host's launcher activities. Initializes at most once per process.
 */
internal class ClarityManager(
    private val context: Context,
    private val activityPackageWatcher: ActivityPackageWatcher,
) {

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var pendingConsent: Boolean? = null

    fun start(info: ClarityInfo) {
        val projectId = info.projectId
        if (projectId.isNullOrEmpty()) {
            Log.i(Constants.LOG_TAG, "Clarity disabled: empty projectId")
            return
        }

        activityPackageWatcher.awaitFirstActivity { isLauncher ->
            if (!isLauncher) {
                Log.i(
                    Constants.LOG_TAG,
                    "Clarity init skipped: first activity is not a launcher activity"
                )
                return@awaitFirstActivity
            }
            initializeClarity(projectId, info)
        }
    }

    private fun initializeClarity(projectId: String, info: ClarityInfo) {
        if (!initialized.compareAndSet(false, true)) {
            Log.w(Constants.LOG_TAG, "Clarity init called multiple times, ignoring")
            return
        }

        val config = ClarityConfig(
            projectId = projectId,
            logLevel = if (info.isLoggingEnabled) LogLevel.Verbose else LogLevel.None,
        )
        try {
            Clarity.initialize(context.applicationContext, config)
            Log.i(Constants.LOG_TAG, "Clarity initialized")
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "Clarity initialize failed", e)
            initialized.set(false)
            return
        }

        pendingConsent?.also {
            applyConsent(it)
            pendingConsent = null
        }
    }

    fun setConsent(hasConsent: Boolean) {
        if (!initialized.get()) {
            pendingConsent = hasConsent
            Log.d(Constants.LOG_TAG, "Clarity consent stored, will apply after init: $hasConsent")
            return
        }
        applyConsent(hasConsent)
    }

    private fun applyConsent(hasConsent: Boolean) {
        try {
            // Clarity.consent(activityConsent, metadataConsent) — apply identical state
            // to both flags so a granted consent unlocks both session recording and
            // metadata logging together (and a denied consent locks both).
            Clarity.consent(hasConsent, hasConsent)
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "Clarity consent failed", e)
        }
    }

    fun maskView(view: View) {
        if (!initialized.get()) {
            Log.d(Constants.LOG_TAG, "Clarity maskView ignored, SDK not initialized")
            return
        }
        try {
            Clarity.maskView(view)
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "Clarity mask failed", e)
        }
    }

    fun unmaskView(view: View) {
        if (!initialized.get()) {
            Log.d(Constants.LOG_TAG, "Clarity unmaskView ignored, SDK not initialized")
            return
        }
        try {
            Clarity.unmaskView(view)
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "Clarity unmask failed", e)
        }
    }

    fun isInitialized(): Boolean = initialized.get()
}
