package com.analytic.atribution.gb.appsflyer

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.analytic.atribution.gb.Constants
import com.analytic.atribution.gb.sharedPreferences
import com.appsflyer.AppsFlyerConsent
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns AppsFlyer SDK initialization, the conversion-data listener, and the flow that
 * forwards the conversion payload to the host app. The library never interprets individual
 * keys or values inside the conversion map.
 */
internal class AppsFlyerManager(
    private val context: Context,
    private val activityPackageWatcher: ActivityPackageWatcher,
) {
    companion object {
        private const val LAST_CONVERSION_PREF_KEY = "appsflyer.last_conversion"
    }

    private val initialized = AtomicBoolean(false)

    private val _conversionFlow = MutableSharedFlow<Bundle>(replay = 1, extraBufferCapacity = 8)
    val conversionFlow: SharedFlow<Bundle> = _conversionFlow.asSharedFlow()

    init {
        lastConversionBundle()?.let { _conversionFlow.tryEmit(it) }
    }

    fun start(info: AppsFlyerInfo) {
        val devKey = info.devKey
        if (devKey.isNullOrEmpty()) {
            Log.i(Constants.LOG_TAG, "AppsFlyer disabled: empty devKey")
            return
        }

        if (activityPackageWatcher.isFirstActivityLauncherOrNull() == false) {
            Log.i(
                Constants.LOG_TAG,
                "AppsFlyer init skipped: first activity is not a launcher activity"
            )
            return
        }

        if (!initialized.compareAndSet(false, true)) {
            Log.w(Constants.LOG_TAG, "AppsFlyer init called multiple times, ignoring")
            return
        }

        val af = AppsFlyerLib.getInstance()
        if (info.isDebugMode || info.isLoggingEnabled) {
            af.setDebugLog(true)
        }
        af.init(devKey, conversionListener, context.applicationContext)
        FirebaseAnalytics.getInstance(context.applicationContext).appInstanceId
            .addOnSuccessListener { id ->
                if (id != null) {
                    af.setCustomerUserId(id)
                    Log.i(Constants.LOG_TAG, "AppsFlyer customerUserId set from Firebase appInstanceId")
                }
            }
            .addOnFailureListener { e ->
                Log.w(Constants.LOG_TAG, "AppsFlyer customerUserId: failed to read Firebase appInstanceId", e)
            }
        af.start(context.applicationContext)
        Log.i(Constants.LOG_TAG, "AppsFlyer initialized")
    }

    fun getAppsFlyerUID(): String? {
        if (!initialized.get()) return null
        return try {
            AppsFlyerLib.getInstance().getAppsFlyerUID(context.applicationContext)
        } catch (e: Throwable) {
            Log.w(Constants.LOG_TAG, "Failed to read AppsFlyer UID", e)
            null
        }
    }

    fun logEvent(eventName: String, params: Map<String, Any>?) {
        if (!initialized.get()) {
            Log.d(Constants.LOG_TAG, "AppsFlyer logEvent ignored, SDK not initialized: $eventName")
            return
        }
        AppsFlyerLib.getInstance().logEvent(context.applicationContext, eventName, params)
    }

    fun setGdprConsent(hasGdpr: Boolean?) {
        if (!initialized.get()) {
            Log.d(Constants.LOG_TAG, "AppsFlyer setGdprConsent ignored, SDK not initialized")
            return
        }
        val consent = if (hasGdpr == null) {
            AppsFlyerConsent.forNonGDPRUser()
        } else {
            AppsFlyerConsent.forGDPRUser(hasGdpr, hasGdpr)
        }
        AppsFlyerLib.getInstance().setConsentData(consent)
    }

    fun lastConversionBundle(): Bundle? {
        val raw = sharedPreferences(context).getString(LAST_CONVERSION_PREF_KEY, null) ?: return null
        return try {
            jsonToBundle(JSONObject(raw))
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Failed to read cached conversion data", e)
            null
        }
    }

    private val conversionListener = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
            val map = data ?: emptyMap()
            Log.i(Constants.LOG_TAG, "AppsFlyer conversion data received: $map")
            val bundle = mapToBundle(map)
            persistConversion(map)
            _conversionFlow.tryEmit(bundle)
        }

        override fun onConversionDataFail(error: String?) {
            Log.w(Constants.LOG_TAG, "AppsFlyer conversion data fail: $error")
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data ?: return
            Log.i(Constants.LOG_TAG, "AppsFlyer app open attribution: ${data.keys}")
            val bundle = Bundle()
            data.forEach { (k, v) -> bundle.putString(k, v) }
            _conversionFlow.tryEmit(bundle)
        }

        override fun onAttributionFailure(error: String?) {
            Log.w(Constants.LOG_TAG, "AppsFlyer attribution failure: $error")
        }
    }

    private fun persistConversion(map: Map<String, Any>) {
        try {
            val json = JSONObject()
            map.forEach { (k, v) -> json.put(k, jsonValue(v)) }
            sharedPreferences(context).edit()
                .putString(LAST_CONVERSION_PREF_KEY, json.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "Failed to persist conversion data", e)
        }
    }

    private fun mapToBundle(map: Map<String, Any>): Bundle {
        val bundle = Bundle()
        map.forEach { (k, v) -> putAny(bundle, k, v) }
        return bundle
    }

    private fun putAny(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            null -> bundle.putString(key, null)
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }

    private fun jsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Int, is Long, is Float, is Double -> value
            else -> value.toString()
        }
    }

    private fun jsonToBundle(json: JSONObject): Bundle {
        val bundle = Bundle()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            if (value == null || value == JSONObject.NULL) {
                bundle.putString(key, null)
            } else {
                putAny(bundle, key, value)
            }
        }
        return bundle
    }
}
