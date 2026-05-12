package com.analytic.atribution.gb.appsflyer

import org.json.JSONArray
import org.json.JSONObject

/**
 * AppsFlyer configuration delivered by the analytics admin console.
 *
 * The library uses [devKey], [isDebugMode] and [isLoggingEnabled] to drive AppsFlyer SDK
 * initialization. [web2AppSubscriptionIds] is forwarded to the host app untouched — the
 * library never interprets individual ids or values.
 */
data class AppsFlyerInfo(
    val devKey: String?,
    val isDebugMode: Boolean,
    val isLoggingEnabled: Boolean,
    val web2AppSubscriptionIds: List<String>,
) {
    companion object {
        val EMPTY = AppsFlyerInfo(
            devKey = null,
            isDebugMode = false,
            isLoggingEnabled = false,
            web2AppSubscriptionIds = emptyList(),
        )

        fun fromJson(json: JSONObject): AppsFlyerInfo {
            val ids = mutableListOf<String>()
            json.optJSONArray("web_2_app_subscription_ids")?.let { array: JSONArray ->
                for (i in 0 until array.length()) {
                    val item = array.optString(i, "")
                    if (item.isNotEmpty()) ids.add(item)
                }
            }
            val devKey = if (json.has("dev_key") && !json.isNull("dev_key")) {
                json.optString("dev_key", "").takeIf { it.isNotEmpty() }
            } else null
            return AppsFlyerInfo(
                devKey = devKey,
                isDebugMode = json.optBoolean("is_debug_mode", false),
                isLoggingEnabled = json.optBoolean("is_logging_enabled", false),
                web2AppSubscriptionIds = ids,
            )
        }
    }
}
