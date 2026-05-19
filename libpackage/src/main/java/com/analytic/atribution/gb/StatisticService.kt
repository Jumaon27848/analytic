package com.analytic.atribution.gb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.analytic.atribution.gb.appsflyer.ActivityPackageWatcher
import com.analytic.atribution.gb.appsflyer.AppsFlyerInfo
import com.analytic.atribution.gb.appsflyer.AppsFlyerManager
import com.analytic.atribution.gb.clarity.ClarityInfo
import com.analytic.atribution.gb.clarity.ClarityManager
import com.analytic.atribution.gb.paywall.PaywallTracker
import com.analytic.atribution.gb.paywall.SessionTracker
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal class StatisticService(private val context: Context) {
    private companion object {
        const val SAVED_DATA_KEY = "data.saved"
        const val CURRENT_DATA_KEY = "data.current"
        const val HOSTNAME_KEY = "hostname"
        const val SECURE_KEY = "secure"
        const val AFFISE_CLICK_ID_KEY = "affise_click_id"
        const val AFFISE_PROMO_CODE_KEY = "affise_promo_code"
        const val WEB_CUSTOMER_ID = "web_customer_id"
        val semaphore = Semaphore(1)
    }

    private val initializationStarted = AtomicBoolean(false)
    private val initialized = CompletableDeferred<Unit>()
    private val appsFlyerInfoDeferred = CompletableDeferred<AppsFlyerInfo>()
    private val clarityInfoDeferred = CompletableDeferred<ClarityInfo>()
    private val activityPackageWatcher = ActivityPackageWatcher(context)
    private val appsFlyerManager = AppsFlyerManager(context, activityPackageWatcher)
    private val clarityManager = ClarityManager(context, activityPackageWatcher)
    private val paywallTracker = PaywallTracker(context)
    private val sessionTracker = SessionTracker(context) { triggerUserUpdate() }
    private val workerKey = "worker_enqueued"
    private val eventLightQueue = mutableListOf<Event>()
    private var api: StatisticServerAPI? = null
    private var eventQueue: EventQueue? = null

    fun setHost(host: String, secure: Boolean) {
        sharedPreferences(context)
            .edit()
            .putBoolean(SECURE_KEY, secure)
            .putString(HOSTNAME_KEY, host)
            .apply()
    }

    suspend fun setAdditionalUserData(
        affiseClickId: String? = null,
        affisePromoCode: String? = null,
        webCustomerId: String? = null,
    ) {
        val editor = sharedPreferences(context).edit()
        affiseClickId?.let { editor.putString(AFFISE_CLICK_ID_KEY, it) }
        affisePromoCode?.let { editor.putString(AFFISE_PROMO_CODE_KEY, it) }
        webCustomerId?.let { editor.putString(WEB_CUSTOMER_ID, it) }
        editor.apply()
        api?.let { updateAppInstance(it) }
    }

    fun enqueueEvent(event: Event) {
        val queue: EventQueue = eventQueue ?: run {
            eventLightQueue.add(event)
            Log.d(Constants.LOG_TAG, "Event \"${event.name2}\" added to the light queue")
            return
        }
        scope.launch {
            queue.enqueue2(event)
            Log.d(Constants.LOG_TAG, "Event \"${event.name2}\" added to the queue")
        }
    }

    fun init() {
        if (!initializationStarted.compareAndSet(false, true)) {
            Log.w(Constants.LOG_TAG, "Statistic service's init called multiple times")
            return
        }

        scope.launch { Locator.register(this@StatisticService) }

        sessionTracker.start()

        Handler(Looper.getMainLooper()).post {
            val prefs: SharedPreferences = sharedPreferences(context)

            scope.launch {
                // Maybe will be enough for lib, tenjin (and anything else we can decide to add)
                // to initialize. Even if 1 second is not enough for something, worker will
                // launch update task periodically and collect it later.
                // So this exists only to decrease amount of requests from device if 1 seconds is enough.
                delay(1000)

                var host = prefs.getString(HOSTNAME_KEY, null)
                if (host == null) {
                    Log.w(Constants.LOG_TAG, "No hostname provided in the first second, initialization delayed")
                    delay(5000)
                }
                host = prefs.getString(HOSTNAME_KEY, null)
                if (host == null) {
                    Log.e(Constants.LOG_TAG, "No hostname provided, initialization impossible")
                    if (!appsFlyerInfoDeferred.isCompleted) {
                        appsFlyerInfoDeferred.complete(AppsFlyerInfo.EMPTY)
                    }
                    if (!clarityInfoDeferred.isCompleted) {
                        clarityInfoDeferred.complete(ClarityInfo.EMPTY)
                    }
                    initialized.complete(Unit)
                    return@launch
                }

                val api = StatisticServerAPI(host, prefs.getBoolean(SECURE_KEY, false))
                this@StatisticService.api = api

                eventQueue = EventQueue(
                    context,
                    api,
                    SQLiteEventRepository(context),
                    50,
                    10,
                    10.seconds.inWholeMilliseconds,
                    10.seconds.inWholeMilliseconds,
                )
                if (eventLightQueue.isNotEmpty()) {
                    scope.launch {
                        eventLightQueue.forEach{ event ->
                            eventQueue!!.enqueue2(event)
                            Log.d(Constants.LOG_TAG, "Event \"" + event.name2 + "\" moved to the queue")
                        }
                        eventLightQueue.clear()
                    }
                }
                scope.launch {
                    while (true) {
                        delay(3000L)
                        try {
                            eventQueue!!.checkFlush()
                        } catch (e: Exception) {
                            Log.e(Constants.LOG_TAG, "Failed to call checkFlush", e)
                        }
                    }
                }

                scope.launch { fetchAndStartAppsFlyer(api) }
                scope.launch { fetchAndStartClarity(api) }

                try {
                    updateAppInstance(api)
                } finally {
                    Log.i(Constants.LOG_TAG, "Analytic initialized")
                    initialized.complete(Unit)
                }
            }

            if (!prefs.getBoolean(workerKey, false)) {
                try {
                    StatisticWorker.enqueue(context)
                    prefs.edit().putBoolean(workerKey, true).apply()
                } catch (e: Exception) {
                    Log.e(Constants.LOG_TAG, "Failed to enqueue worker", e)
                }
            }
        }
    }

    suspend fun await() = initialized.await()

    suspend fun awaitAppsFlyerInfo(): AppsFlyerInfo = appsFlyerInfoDeferred.await()

    fun appsFlyerConversionFlow(): SharedFlow<Bundle> = appsFlyerManager.conversionFlow

    fun lastAppsFlyerConversionBundle(): Bundle? = appsFlyerManager.lastConversionBundle()

    fun logAppsFlyerEvent(eventName: String, params: Map<String, Any>?) =
        appsFlyerManager.logEvent(eventName, params)

    fun setAppsFlyerGdprConsent(hasGdpr: Boolean?) =
        appsFlyerManager.setGdprConsent(hasGdpr)

    suspend fun awaitClarityInfo(): ClarityInfo = clarityInfoDeferred.await()

    fun setClarityConsent(hasConsent: Boolean) = clarityManager.setConsent(hasConsent)

    fun maskClarityView(view: View) = clarityManager.maskView(view)

    fun unmaskClarityView(view: View) = clarityManager.unmaskView(view)

    fun notifyPaywallOpened() {
        paywallTracker.notifyPaywallOpened()
        triggerUserUpdate()
    }

    fun notifyPurchaseStarted() {
        paywallTracker.notifyPurchaseStarted()
        triggerUserUpdate()
    }

    fun notifyPurchaseCompleted() {
        paywallTracker.notifyPurchaseCompleted()
        triggerUserUpdate()
    }

    fun notifyInterstitialShown() = paywallTracker.notifyInterstitialShown()

    fun notifyAoaShown() = paywallTracker.notifyAoaShown()

    fun notifyUserAction() = paywallTracker.notifyUserAction()

    fun setGdprConsent(status: String) {
        paywallTracker.setGdprConsent(status)
        triggerUserUpdate()
    }

    private fun triggerUserUpdate() {
        val current = api ?: return
        scope.launch { updateAppInstance(current) }
    }

    private suspend fun fetchAndStartAppsFlyer(api: StatisticServerAPI) {
        val info: AppsFlyerInfo = try {
            val raw = api.fetchAppsFlyerConfig(context.packageName)
            AppsFlyerInfo.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "Failed to fetch AppsFlyer info, defaulting to disabled", e)
            AppsFlyerInfo.EMPTY
        }
        if (!appsFlyerInfoDeferred.isCompleted) {
            appsFlyerInfoDeferred.complete(info)
        }
        try {
            appsFlyerManager.start(info)
            triggerUserUpdate()
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "AppsFlyer start failed", e)
        }
    }

    private suspend fun fetchAndStartClarity(api: StatisticServerAPI) {
        val raw: String? = try {
            api.fetchClarityConfig(context.packageName)
        } catch (e: Exception) {
            Log.w(
                Constants.LOG_TAG,
                "Failed to fetch Clarity info for package=${context.packageName} " +
                    "(${e.javaClass.simpleName}: ${e.message}), defaulting to disabled",
                e
            )
            null
        }
        val info: ClarityInfo = if (raw == null) {
            ClarityInfo.EMPTY
        } else {
            try {
                ClarityInfo.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                Log.w(
                    Constants.LOG_TAG,
                    "Failed to parse Clarity info (${e.javaClass.simpleName}: ${e.message}), " +
                        "raw response length=${raw.length}, defaulting to disabled",
                    e
                )
                ClarityInfo.EMPTY
            }
        }
        if (!clarityInfoDeferred.isCompleted) {
            clarityInfoDeferred.complete(info)
        }
        try {
            clarityManager.start(info)
        } catch (e: Throwable) {
            Log.e(Constants.LOG_TAG, "Clarity start failed", e)
        }
    }

    private fun getData(key: String): AppInstanceData {
        val data: String = sharedPreferences(context).getString(key, null) ?: return AppInstanceData()
        return AppInstanceData.fromJson(JSONObject(data))
    }

    private fun saveData(key: String, data: AppInstanceData) {
        sharedPreferences(context).edit().putString(key, data.toJson().toString()).apply()
    }

    suspend fun updateAppInstance(api: StatisticServerAPI) {
        // Allows to run only one user update at the same time, 'cause there's may be no reason
        // for second request if first one succeed
        semaphore.withPermit {
            try {
                updateAppInstanceUnsafe(api)
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "updateUserUnsafe throws unknown exception", e)
                reportError(api, context, ErrorData(e.toString()))
            }
        }
    }

    private suspend fun updateAppInstanceUnsafe(api: StatisticServerAPI) {
        val preferences = sharedPreferences(context)
        val currentData: AppInstanceData = getData(CURRENT_DATA_KEY)
        val savedData: AppInstanceData = getData(SAVED_DATA_KEY)

        // This may fail, but there's no way to recover. analyticAppID is required
        val analyticAppID: String = FirebaseAnalytics.getInstance(context).appInstanceId.await()
        currentData.firebaseToken = FirebaseMessaging.getInstance().token.await()
        currentData.libID = getLibId(context).let { id -> if (!id.isNullOrEmpty()) id else null }
        currentData.appPackage = context.packageName
        currentData.appFirstOpenTimestamp = getFirstOpenTimestamp(context)
        currentData.appLastUpdateTimestamp = getLastUpdateTimestamp(context)
        currentData.geo = getCountryCode(context)
        currentData.osVersion = Build.VERSION.RELEASE
        currentData.device = Build.DEVICE
        currentData.deviceModel = Build.MODEL
        val (appVersionCode, appVersion) = getAppVersion(context)
        currentData.appVersionCode = appVersionCode
        currentData.appVersion = appVersion
        currentData.tenjinAnalyticsInstallationId = getTenjinAnalyticsInstallationId(context)
        val (isLimitedAdTracking, advertisingId) = getIsLimitedAdAndAdvertisingId(context)
        currentData.isLimitedAdTracking = isLimitedAdTracking
        currentData.advertisingId = advertisingId
        currentData.osVersionInt = Build.VERSION.SDK_INT
        currentData.buildId = Build.ID
        currentData.locale1 = Locale.getDefault().toString()
        currentData.affiseClickId = preferences.getString(AFFISE_CLICK_ID_KEY, null)
        currentData.affisePromoCode = preferences.getString(AFFISE_PROMO_CODE_KEY, null)
        currentData.webCustomerId = preferences.getString(WEB_CUSTOMER_ID, null)
        if (currentData.referrer == null) currentData.referrer = getInstallReferrer(context)

        currentData.connectionType = getConnectionType(context)
        currentData.screenResolution = getScreenResolution()
        currentData.ramTotalBytes = getRamTotalBytes(context)
        currentData.manufacturer = Build.MANUFACTURER
        currentData.brand = Build.BRAND
        val (storageTotal, storageFree) = getStorageTotalAndFreeBytes()
        currentData.storageTotal = storageTotal
        currentData.storageFree = storageFree
        currentData.carrier = getCarrierName(context)
        currentData.gdprConsentStatus = paywallTracker.getGdprStatus() ?: "unknown"
        currentData.hamonVersion = Constants.LIB_VERSION
        val pw = paywallTracker.getPaywallFields()
        currentData.timeToPaywall = pw["time_to_paywall"]
        currentData.actionsBeforePaywall = pw["actions_before_paywall"]?.toInt()
        currentData.intersShownBeforePaywall = pw["inters_shown_before_paywall"]?.toInt()
        currentData.aoaShownBeforePaywall = pw["aoa_shown_before_paywall"]?.toInt()
        currentData.paywallConversionTime = pw["paywall_conversion_time"]
        currentData.clickToPayTime = pw["click_to_pay_time"]
        val st = sessionTracker.getFields()
        currentData.sessionLengthFirst = st["session_length_first"]
        currentData.tapsCountFirst30s = st["taps_count_first_30s"]?.toInt()
        appsFlyerManager.getAppsFlyerUID()?.let { currentData.appsflyerId = it }

        if (currentData.toJson().toString() == savedData.toJson().toString()) {
            Log.i(Constants.LOG_TAG, "User data already updated")
        } else {
            saveData(CURRENT_DATA_KEY, currentData)

            val firstRequest: Boolean = savedData.firebaseToken == null
            if (!firstRequest) {
                Log.i(Constants.LOG_TAG, "User data changed")
            }

            currentData.hints = AppInstanceHints(
                firstRequest,
                if (firstRequest) null else savedData.toJson(),
                System.currentTimeMillis(),
            )

            try {
                api.makeRequest(
                    "/users/" + analyticAppID,
                    HTTP.Method.POST,
                    currentData.toJson().toString()
                ) {
                    saveData(SAVED_DATA_KEY, currentData)
                    Log.i(Constants.LOG_TAG, "User data updated. User ID: $analyticAppID")
                }
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "First http request failed, retry...", e)
                api.makeRequest(
                    "/users/" + analyticAppID,
                    HTTP.Method.POST,
                    currentData.toJson().toString()
                ) {
                    saveData(SAVED_DATA_KEY, currentData)
                    Log.i(Constants.LOG_TAG, "User data updated. User ID: $analyticAppID")
                }
            }
        }
    }

    suspend fun reportError(api: StatisticServerAPI, context: Context, errorData: ErrorData) {
        try {
            val analyticAppID: String = FirebaseAnalytics.getInstance(context).appInstanceId.await()
            api.makeRequest(
                "/users/" + analyticAppID + "/errors/",
                HTTP.Method.POST,
                errorData.toJson().toString()
            ) {
                Log.i(Constants.LOG_TAG, "Error logged")
            }
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "reportError throws unknown exception", e)
        }
    }
}
