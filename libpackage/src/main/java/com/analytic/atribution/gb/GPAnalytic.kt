package com.analytic.atribution.gb

import android.os.Bundle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Caller friendly API of the analytic library. All methods can be called from any context,
 * not blocking and never throws.
 *
 * Initialization is automatic, but you must call [setHost]
 * with the ip address or domain name of the server for it to be possible.
 *
 * Other methods are optional.
 */
object GPAnalytic {
    /**
     * Sets the hostname of the analytic server. Note that you have
     * 5 seconds to call this function, otherwise initialization process will be aborted.
     *
     * @param host Hostname of the server. For example: `analytic.com`, `127.0.0.1`.
     */
    fun setHost(host: String) {
        scope.launch {
            Locator.resolve<StatisticService>().setHost(host)
        }
    }

    /**
     * Adds custom event to the queue. They'll be sent to the server once there's more than 10
     * events in the queue or more than 10 seconds passed from oldest event in the queue. Currently
     * there's no way to trigger flush externally.
     *
     * Note that all not custom events are managed by SDK
     * automatically, only use this for custom events like `screen_open`, `button_pressed` etc.
     *
     * @param name Name of the event.
     * @param parameters Parameters of the event. Provided bundle must contain only json
     * compatible primitives types (e.g. one of [Int], [Float], [String], [Boolean], null).
     * Defaults to empty bundle
     * @param timestamp Timestamp of when event occur. Defaults to [System.currentTimeMillis]
     */
    fun sendEvent(
        name: String,
        parameters: Bundle? = null,
        timestamp: Long? = null
    ) {
        val jsonObject = JSONObject()
        parameters?.keySet()?.forEach { key ->
            jsonObject.put(key, parameters.get(key))
        }

        val event = Event(
            UUID.randomUUID().toString(),
            timestamp ?: System.currentTimeMillis(),
            name,
            jsonObject
        )

        scope.launch {
            Locator.resolve<StatisticService>().enqueueEvent(event)
        }
    }
}