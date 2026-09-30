package com.analytic.atribution.gb

import android.content.Context
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Buffers events locally and then sends them to the server in batches. Have internal auto flush
 * mechanism that trigger flush based on EITHER [flushThreshold] OR [flushIntervalMs] whenever new event
 * arrives. Or you can use [flush] to force a flush manually.
 *
 * Since auto flush can only be triggered on new event, it may be a good idea for another class to
 * set up a timer and call [checkFlush] periodically.
 *
 * @param context Context for HTTP requests.
 * @param api Instance of the Analytic server API wrapper to send events.
 * @param repository Repository used to store events locally.
 * @param flushBatchSize Maximal amount of events in HTTP request to the server.
 * @param flushThreshold Amount of events that will trigger auto flush.
 * @param flushIntervalMs Amount of time passed after oldest event in the queue that will trigger
 * auto flush.
 * @param flushRetryIntervalMs Amount of time to wait after the first auto flush fail before next
 * attempt. Doubles after each consecutive fail (exponential backoff) and resets after a successful
 * flush. The queue does not have internal retry mechanism, either [checkFlush] or [enqueue2] must be
 * called for the next attempt to happen.
 * @param flushMaxRetryIntervalMs Upper bound for the backoff interval.
 */
internal class EventQueue(
    private val context: Context,
    private val api: StatisticServerAPI,
    private val repository: SQLiteEventRepository,
    private val flushBatchSize: Int,
    private val flushThreshold: Int,
    private val flushIntervalMs: Long,
    private val flushRetryIntervalMs: Long,
    private val flushMaxRetryIntervalMs: Long,
) {
    private val flushLock = Mutex()
    private var lastFailedFlushTime: Long = 0
    private var consecutiveFailures: Int = 0

    suspend fun enqueue2(event: Event) {
        if (repository.stats().first >= Constants.LOCAL_EVENT_LIMIT) {
            Log.w(Constants.LOG_TAG, "Local events limit (${Constants.LOCAL_EVENT_LIMIT}) reached")
        } else {
            repository.save(event)
        }
        checkFlush()
    }

    suspend fun flush(flushBatchMinSize: Int) {
        if (!context.isNetworkAvailable()) {
            lastFailedFlushTime = System.currentTimeMillis()
            return
        }
        if (!flushLock.tryLock()) return
        try {
            while (repository.stats().first >= flushBatchMinSize) {
                val events: List<Event> = repository.get(limit = flushBatchSize)
                if (events.isEmpty()) break
                try {
                    sendEvents(events)
                    repository.delete(events)
                } catch (e: Exception) {
                    consecutiveFailures++
                    lastFailedFlushTime = System.currentTimeMillis()
                    Log.e(Constants.LOG_TAG, "Failed to flush events, next attempt in ${retryIntervalMs()} ms", e)
                    break
                }
                consecutiveFailures = 0
                Log.i(Constants.LOG_TAG, events.size.toString() + " events flushed")
            }
        } finally {
            flushLock.unlock()
        }
    }

    suspend fun checkFlush() {
        if (flushLock.isLocked) return
        if (System.currentTimeMillis() - lastFailedFlushTime < retryIntervalMs()) return
        val (eventCount: Int, oldestTimestamp: Long?) = repository.stats()
        if (oldestTimestamp == null) return
        if (eventCount < flushThreshold && System.currentTimeMillis() - oldestTimestamp < flushIntervalMs) return

        flush(eventCount)
    }

    private fun retryIntervalMs(): Long {
        if (consecutiveFailures <= 1) return flushRetryIntervalMs
        // Cap the shift to avoid Long overflow, the result is capped anyway
        val shift: Int = (consecutiveFailures - 1).coerceAtMost(30)
        return (flushRetryIntervalMs shl shift).coerceAtMost(flushMaxRetryIntervalMs)
    }

    private suspend fun sendEvents(events: List<Event>) {
        val analyticAppID: String = FirebaseAnalytics.getInstance(context).appInstanceId.await()
        api.makeRequest(
            "/users/$analyticAppID/event",
            HTTP.Method.POST,
            JSONObject(mapOf(
                "events" to JSONArray(events.map { it.toJson() })
            )).toString()
        )
    }
}