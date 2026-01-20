package com.analytic.atribution.gb

import org.json.JSONArray
import org.json.JSONObject

/**
 * Additional data to have some insights about the client
 */
internal class AppInstanceHints(
    val firstAppInstanceUpdate: Boolean,
    val oldData: JSONObject? = null,
    val deviceTimestamp: Long,
) {
    fun toJson(): JSONObject {
        val jsonObject: JSONObject = JSONObject()
        jsonObject.put("first_app_instance_update", firstAppInstanceUpdate)
        jsonObject.put("old_data", oldData)
        jsonObject.put("device_timestamp_millis", deviceTimestamp)
        return jsonObject
    }
}

internal class AppInstanceData(
    var libID: String? = null,
    var appPackage: String? = null,
    var appFirstOpenTimestamp: Long? = null,
    var appLastUpdateTimestamp: Long? = null,
    var appDeleteTimestamp: Long? = null,
    var firebaseToken: String? = null,
    var gclid: String? = null,
    var geo: String? = null,
    var osVersion: String? = null,
    var device: String? = null,
    var deviceModel: String? = null,
    var appVersion: String? = null,
    var referrer: String? = null,
    var tenjinAnalyticsInstallationId: String? = null,
    var isLimitedAdTracking: Boolean? = null,
    var advertisingId: String? = null,
    var osVersionInt: Int? = null,
    var appVersionCode: Int? = null,
    var buildId: String? = null,
    var locale1: String? = null,
    var affiseClickId: String? = null,
    var affisePromoCode: String? = null,
    var hints: AppInstanceHints? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): AppInstanceData {
            return AppInstanceData(
                libID = json.getOrNull("lib_id"),
                appPackage = json.getOrNull("package"),
                appFirstOpenTimestamp = json.getOrNull("app_first_open_timestamp"),
                appLastUpdateTimestamp = json.getOrNull("app_last_update_timestamp"),
                appDeleteTimestamp = json.getOrNull("app_delete_timestamp"),
                firebaseToken = json.getOrNull("firebase_token"),
                gclid = json.getOrNull("gclid"),
                geo = json.getOrNull("geo"),
                osVersion = json.getOrNull("os_version"),
                device = json.getOrNull("device"),
                deviceModel = json.getOrNull("device_model"),
                appVersion = json.getOrNull("app_version"),
                referrer = json.getOrNull("referrer"),
                tenjinAnalyticsInstallationId = json.getOrNull("tenjin_analytics_installation_id"),
                isLimitedAdTracking = json.getOrNull("is_limited_ad_tracking"),
                advertisingId = json.getOrNull("advertising_id"),
                osVersionInt = json.getOrNull("os_version_int"),
                appVersionCode = json.getOrNull("app_version_code"),
                buildId = json.getOrNull("build_id"),
                locale1 = json.getOrNull("locale"),
                affiseClickId = json.getOrNull("affise_clickid"),
                affisePromoCode = json.getOrNull("affise_promo_code"),
                hints = null,
            )
        }
    }

    fun toJson(): JSONObject {
        val jsonObject: JSONObject = JSONObject()
        jsonObject.put("lib_id", libID)
        jsonObject.put("package", appPackage)
        jsonObject.put("app_first_open_timestamp", appFirstOpenTimestamp)
        jsonObject.put("app_last_update_timestamp", appLastUpdateTimestamp)
        jsonObject.put("app_delete_timestamp", appDeleteTimestamp)
        jsonObject.put("firebase_token", firebaseToken)
        jsonObject.put("gclid", gclid)
        jsonObject.put("geo", geo)
        jsonObject.put("os_version", osVersion)
        jsonObject.put("device", device)
        jsonObject.put("device_model", deviceModel)
        jsonObject.put("app_version", appVersion)
        jsonObject.put("referrer", referrer)
        jsonObject.put("tenjin_analytics_installation_id", tenjinAnalyticsInstallationId)
        jsonObject.put("is_limited_ad_tracking", isLimitedAdTracking)
        jsonObject.put("advertising_id", advertisingId)
        jsonObject.put("os_version_int", osVersionInt)
        jsonObject.put("app_version_code", appVersionCode)
        jsonObject.put("build_id", buildId)
        jsonObject.put("locale", locale1)
        jsonObject.put("affise_clickid", affiseClickId)
        jsonObject.put("affise_promo_code", affisePromoCode)
        jsonObject.put("hints", hints?.toJson())
        return jsonObject
    }
}

internal class AppImpressionData(
    val appPackage: String,
    val timestamp: Long,
    val revenue: Float,
) {
    fun toJson(): JSONObject {
        val jsonObject: JSONObject = JSONObject()
        jsonObject.put("package", appPackage)
        jsonObject.put("timestamp", timestamp)
        jsonObject.put("revenue", revenue)
        return jsonObject
    }
}

internal class ErrorData(val error: String) {
    fun toJson(): JSONObject {
        val jsonObject: JSONObject = JSONObject()
        jsonObject.put("error", error)
        return jsonObject
    }
}

internal class Event(
    val uuid: String,
    val timestamp2: Long,
    val name2: String,
    val parameters2: JSONObject
) {
    fun toJson(): JSONObject {
        val jsonObject: JSONObject = JSONObject()
        jsonObject.put("uuid", uuid)
        jsonObject.put("timestamp", timestamp2)
        jsonObject.put("name", name2)
        jsonObject.put("parameters", parameters2)
        return jsonObject
    }
}

internal inline fun <reified T> JSONObject.getOrNull(key: String): T? {
    if (!has(key) || isNull(key)) return null

    return when (T::class) {
        String::class -> getString(key) as T
        Int::class -> getInt(key) as T
        Long::class -> getLong(key) as T
        Double::class -> getDouble(key) as T
        Boolean::class -> getBoolean(key) as T
        JSONObject::class -> getJSONObject(key) as T
        JSONArray::class -> getJSONArray(key) as T
        else -> null
    }
}
