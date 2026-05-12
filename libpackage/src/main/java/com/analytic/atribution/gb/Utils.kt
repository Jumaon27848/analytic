package com.analytic.atribution.gb

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

private val job = SupervisorJob()
internal val scope = CoroutineScope(Dispatchers.IO + job)

internal fun sharedPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(
        Constants.SHARED_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
}


internal fun getCountryCode(context: Context): String? {
    var countryCode: String?
    val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    countryCode = tm.simCountryIso
    if (countryCode == null || countryCode.length != 2) {
        if (tm.phoneType != TelephonyManager.PHONE_TYPE_CDMA){
            countryCode = tm.networkCountryIso
        }
    }
    if (countryCode == null || countryCode.length != 2) {
        countryCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0).country
        } else {
            context.resources.configuration.locale.country
        }
    }

    return if (countryCode != null && countryCode.length == 2){
        countryCode.uppercase(Locale.getDefault())
    } else {
        Log.w(Constants.LOG_TAG, "Failed to get country code")
        null
    }
}

internal fun getLibId(context: Context): String? {
    val appPackage: String = context.packageName
    return context.getSharedPreferences(
        "tst$appPackage", Context.MODE_PRIVATE
    ).getString("uid$appPackage", "")
}

internal fun getFirstOpenTimestamp(context: Context): Long? {
    try {
        return context.packageManager.getPackageInfo(
            context.packageName,
            0
        ).firstInstallTime
    } catch (e: Exception) {
        Log.e(Constants.LOG_TAG, "Failed to get app first open timestamp")
        return null
    }
}

internal fun getLastUpdateTimestamp(context: Context): Long? {
    try {
        return context.packageManager.getPackageInfo(
            context.packageName,
            0
        ).lastUpdateTime
    } catch (e: Exception) {
        Log.e(Constants.LOG_TAG, "Failed to get app last update timestamp")
        return null
    }
}

internal fun getAppVersion(context: Context): Pair<Int?, String?> {
    try {
        val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionCode to packageInfo.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(Constants.LOG_TAG, "Failed to get app version")
        return null to null
    }
}

internal suspend fun getInstallReferrer(context: Context): String? {
    return try {
        withTimeout(5.seconds) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val referrerClient = InstallReferrerClient.newBuilder(context).build()

                    continuation.invokeOnCancellation { referrerClient.endConnection() }

                    referrerClient.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            when (responseCode) {
                                InstallReferrerClient.InstallReferrerResponse.OK -> {
                                    Log.i(Constants.LOG_TAG, "Referrer client connected")
                                    try {
                                        val referrer: String = referrerClient.installReferrer.installReferrer
                                        continuation.resume(referrer) { _, _, _ -> }
                                    } catch (e: Exception) {
                                        continuation.resumeWithException(e)
                                    } finally {
                                        referrerClient.endConnection()
                                    }
                                }
                                InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                                    continuation.resume(null) { _, _, _ -> }
                                    Log.i(Constants.LOG_TAG, "Referrer not updated: API not available on the current Play Store app")
                                }
                                InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                                    continuation.resume(null) { _, _, _ -> }
                                    Log.i(Constants.LOG_TAG, "Referrer not updated: Connection couldn't be established")
                                }
                            }
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            Log.i(Constants.LOG_TAG, "Referrer client disconnected")
                        }
                    })
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(Constants.LOG_TAG, "Unexpected error $e", e)
        null
    }
}

internal fun getTenjinAnalyticsInstallationId(context: Context): String? {
    val tenjinPreferences: SharedPreferences = context.getSharedPreferences(
        "tenjinInstallPreferences",
        Context.MODE_PRIVATE
    )
    return tenjinPreferences.getString("analyticsInstallationId", null)
}

internal fun getIsLimitedAdAndAdvertisingId(context: Context): Pair<Boolean?, String?> {
    return try {
        val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
        if (info.isLimitAdTrackingEnabled) {
            true to null
        } else {
            false to info.id
        }
    } catch (e: Exception) {
        null to null
    }
}

internal fun getConnectionType(context: Context): String {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net: Network = cm.activeNetwork ?: return "none"
            val caps: NetworkCapabilities = cm.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "none"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return "none"
            @Suppress("DEPRECATION")
            when (info.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                else -> "none"
            }
        }
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to get connection type", e)
        "none"
    }
}

internal fun getScreenResolution(): String? {
    return try {
        val dm = Resources.getSystem().displayMetrics
        "${dm.widthPixels}x${dm.heightPixels}"
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to get screen resolution", e)
        null
    }
}

internal fun getRamTotalBytes(context: Context): Long? {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        mem.totalMem
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to get RAM total", e)
        null
    }
}

internal fun getStorageTotalAndFreeBytes(): Pair<Long?, Long?> {
    return try {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.totalBytes to stat.availableBytes
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to get storage info", e)
        null to null
    }
}

internal fun getCarrierName(context: Context): String? {
    return try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.networkOperatorName?.takeUnless { it.isBlank() }
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to get carrier name", e)
        null
    }
}

internal fun Context.isNetworkAvailable(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
        val cm: ConnectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net: Network = cm.activeNetwork ?: return false
        val capabilities: NetworkCapabilities = cm.getNetworkCapabilities(net) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        return true
    }
}