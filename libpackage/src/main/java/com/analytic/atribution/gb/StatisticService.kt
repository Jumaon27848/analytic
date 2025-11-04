package com.analytic.atribution.gb

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal class StatisticService(private val context: Context) {
    private companion object {
        const val SAVED_DATA_KEY = "data.saved"
        const val CURRENT_DATA_KEY = "data.current"
        const val HOSTNAME_KEY = "hostname"
        val semaphore = Semaphore(1)
    }

    private val initializationStarted = AtomicBoolean(false)
    private val initialized = CompletableDeferred<Unit>()
    private val workerKey = "worker_enqueued"
    private val eventLightQueue = mutableListOf<Event>()
    private var eventQueue: EventQueue? = null
    private val eventReceiver : BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val eventBundle: Bundle = intent?.extras ?: run {
                Log.e(Constants.LOG_TAG, "Received event broadcast without extra")
                return
            }
            val timestamp: Long = eventBundle.getLong("timestamp").let {
                if (it != 0L) it else System.currentTimeMillis()
            }
            val name: String = eventBundle.getString("name") ?: run {
                Log.e(Constants.LOG_TAG, "Event without name received. Event must have a name")
                return
            }
            val parameters: JSONObject = eventBundle.getBundle("parameters").let { parametersBundle ->
                if (parametersBundle == null) {
                    Log.d(Constants.LOG_TAG,
                        "No parameters received for event \"$name\", will use empty JSON"
                    )
                    JSONObject()
                } else {
                    val jsonObject: JSONObject = JSONObject()
                    parametersBundle.keySet().forEach { key ->
                        jsonObject.put(key, parametersBundle.get(key))
                    }
                    jsonObject
                }
            }
            val event = Event(
                UUID.randomUUID().toString(),
                timestamp,
                name,
                parameters
            )

            enqueueEvent(event)
        }
    }

    fun setHost(host: String) {
        sharedPreferences(context).edit().putString(HOSTNAME_KEY, host).apply()
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

        LocalBroadcastManager.getInstance(context).registerReceiver(
            eventReceiver, IntentFilter("analytic.event")
        )

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
                    initialized.complete(Unit)
                    return@launch
                }

                val api = StatisticServerAPI(host)

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
        if (currentData.referrer == null) currentData.referrer = getInstallReferrer(context)

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
