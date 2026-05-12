package com.analytic.atribution.gb.appsflyer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Tracks whether the first activity created after the process starts is one of the host
 * app's launcher activities (declared with MAIN/LAUNCHER intent filters in the manifest).
 * Used to detect when the app was launched from a foreign process (for example, an ad
 * SDK's transparent activity) so AppsFlyer / Clarity initialization can be skipped.
 */
internal class ActivityPackageWatcher(context: Context) {

    private val launcherActivities: Set<ComponentName> = resolveLauncherActivities(context)

    @Volatile
    private var firstActivityIsLauncher: Boolean? = null

    init {
        val application = context.applicationContext as? Application
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (firstActivityIsLauncher == null) {
                    firstActivityIsLauncher = activity.componentName in launcherActivities
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Returns true if the first created activity is a launcher activity, false if it is
     * not, or null if no activity has been created yet (in which case callers should
     * proceed — we have no evidence of a foreign-process launch).
     */
    fun isFirstActivityLauncherOrNull(): Boolean? = firstActivityIsLauncher

    @SuppressLint("QueryPermissionsNeeded")
    private fun resolveLauncherActivities(context: Context): Set<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .toSet()
    }
}