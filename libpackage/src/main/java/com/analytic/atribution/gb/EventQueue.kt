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
 * @param flushRetryIntervalMs Amount of time to wait after auto flush fail before next attempt.
 * The queue does not have internal retry mechanism, either [checkFlush] or [enqueue2] must be called
 * for the next attempt to happen.
 */
internal class EventQueue(
    private val context: Context,
    private val api: StatisticServerAPI,
    private val repository: SQLiteEventRepository,
    private val flushBatchSize: Int,
    private val flushThreshold: Int,
    private val flushIntervalMs: Long,
    private val flushRetryIntervalMs: Long,
) {
    private val flushLock = Mutex()
    private var lastFailedFlushTime: Long = 0

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
                } catch (e: Exception) {
                    Log.e(Constants.LOG_TAG, "Failed to flush events", e)
                    lastFailedFlushTime = System.currentTimeMillis()
                    break
                }
                repository.delete(events)
                Log.i(Constants.LOG_TAG, events.size.toString() + " events flushed")
            }
        } finally {
            flushLock.unlock()
        }
    }

    suspend fun checkFlush() {
        if (flushLock.isLocked) return
        if (System.currentTimeMillis() - lastFailedFlushTime < flushRetryIntervalMs) return
        val (eventCount: Int, oldestTimestamp: Long?) = repository.stats()
        if (oldestTimestamp == null) return
        if (eventCount < flushThreshold && System.currentTimeMillis() - oldestTimestamp < flushIntervalMs) return

        flush(eventCount)
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