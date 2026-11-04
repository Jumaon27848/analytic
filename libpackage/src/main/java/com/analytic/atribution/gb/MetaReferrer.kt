package com.analytic.atribution.gb

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Meta Install Referrer: https://developers.facebook.com/docs/app-ads/meta-install-referrer
 *
 * Facebook, Instagram and Facebook Lite each expose their own provider, and several of them can
 * be installed on the device at once. All of them are queried and every non-empty answer is
 * sent to the server, which picks the winning touch (click over view, then the latest one).
 */
private val META_REFERRER_PROVIDERS: List<Pair<String, String>> = listOf(
    "facebook" to "com.facebook.katana.provider.InstallReferrerProvider",
    "instagram" to "com.instagram.contentprovider.InstallReferrerProvider",
    "facebook_lite" to "com.facebook.lite.provider.InstallReferrerProvider",
)

private const val COLUMN_INSTALL_REFERRER = "install_referrer"
private const val COLUMN_ACTUAL_TIMESTAMP = "actual_timestamp"
private const val COLUMN_IS_CT = "is_ct"

/** Meta-data key used by the Facebook SDK for the app id. */
private const val FACEBOOK_APP_ID_META_DATA = "com.facebook.sdk.ApplicationId"

/**
 * Facebook App ID from the manifest (`com.facebook.sdk.ApplicationId`) or, if the manifest
 * has none, the one passed to [GPAnalytic.setHost]. When both are present and differ, the
 * manifest wins and a warning is logged.
 */
internal fun resolveFacebookAppId(context: Context, passedAppId: String?): String? {
    val passed: String? = normalizeFacebookAppId(passedAppId)
    val fromManifest: String? = getFacebookAppIdFromManifest(context)

    if (fromManifest != null) {
        if (passed != null && passed != fromManifest) {
            Log.w(
                Constants.LOG_TAG,
                "Facebook App ID passed to setHost ($passed) differs from the manifest " +
                    "$FACEBOOK_APP_ID_META_DATA ($fromManifest), using the manifest value"
            )
        }
        return fromManifest
    }
    return passed
}

private fun getFacebookAppIdFromManifest(context: Context): String? {
    return try {
        val metaData = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData ?: return null
        when (val value: Any? = metaData.get(FACEBOOK_APP_ID_META_DATA)) {
            null -> null
            is String -> normalizeFacebookAppId(value)
            else -> {
                // A raw number in android:value gets parsed as int/float and loses digits.
                // The Facebook SDK rejects it as well, so it can't be the real id.
                Log.w(
                    Constants.LOG_TAG,
                    "$FACEBOOK_APP_ID_META_DATA in the manifest is not a string " +
                        "(${value.javaClass.simpleName}), ignoring it. Use @string/... or the \"fb\" prefix"
                )
                null
            }
        }
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to read Facebook App ID from the manifest", e)
        null
    }
}

/** Trims and drops the optional "fb" prefix, the same way the Facebook SDK does. */
private fun normalizeFacebookAppId(raw: String?): String? {
    var id: String = raw?.trim() ?: return null
    if (id.startsWith("fb", ignoreCase = true)) id = id.substring(2)
    return id.ifEmpty { null }
}

/**
 * Queries every installed Meta app for the install referrer. Returns an array of
 * `{source, install_referrer, actual_timestamp, is_ct}`, or null if nothing was found.
 */
internal fun getMetaInstallReferrers(context: Context, facebookAppId: String): JSONArray? {
    val result = JSONArray()
    for ((source, authority) in META_REFERRER_PROVIDERS) {
        queryMetaReferrer(context, source, authority, facebookAppId)?.let { result.put(it) }
    }
    return if (result.length() > 0) result else null
}

private fun queryMetaReferrer(
    context: Context,
    source: String,
    authority: String,
    facebookAppId: String,
): JSONObject? {
    try {
        if (context.packageManager.resolveContentProvider(authority, 0) == null) return null

        val uri: Uri = Uri.parse("content://$authority/$facebookAppId")
        val projection = arrayOf(COLUMN_INSTALL_REFERRER, COLUMN_IS_CT, COLUMN_ACTUAL_TIMESTAMP)
        val cursor: Cursor = context.contentResolver.query(uri, projection, null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null

            val referrerIndex: Int = it.getColumnIndex(COLUMN_INSTALL_REFERRER)
            if (referrerIndex < 0 || it.isNull(referrerIndex)) return null
            val referrer: String = it.getString(referrerIndex)
            if (referrer.isEmpty()) return null

            val timestampIndex: Int = it.getColumnIndex(COLUMN_ACTUAL_TIMESTAMP)
            val isCtIndex: Int = it.getColumnIndex(COLUMN_IS_CT)

            val entry = JSONObject()
            entry.put("source", source)
            entry.put("install_referrer", referrer)
            entry.put(
                "actual_timestamp",
                if (timestampIndex >= 0 && !it.isNull(timestampIndex)) it.getLong(timestampIndex) else null
            )
            entry.put(
                "is_ct",
                if (isCtIndex >= 0 && !it.isNull(isCtIndex)) it.getInt(isCtIndex) == 1 else null
            )
            Log.i(Constants.LOG_TAG, "Meta install referrer found in $source")
            return entry
        }
    } catch (e: Exception) {
        Log.w(Constants.LOG_TAG, "Failed to read Meta install referrer from $source", e)
        return null
    }
}
