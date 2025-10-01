package com.analytic.atribution.gb

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class StatisticWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        private val TRY_KEY = "worker.try"
        val jobName = "statistic_job"

        fun enqueue(context: Context) {
            val tried: Int = sharedPreferences(context).getInt(TRY_KEY, 0)
            // First one is mostly for "run when there's internet connection" behaviour
            // Second one because why not
            val delay: Duration = if (tried < 2) {
                1.minutes
            // Maybe the server is down. This gives 2 hours of "acceptable" downtime.
            // Also there can be new data. In any case, 2 additional tries won't hurt even if useless
            } else if (tried < 4) {
                1.hours
            // There can be new data
            } else {
                7.days
            }

            val workRequest: OneTimeWorkRequest = OneTimeWorkRequestBuilder<StatisticWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                jobName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            Log.d(Constants.LOG_TAG, "Statistic collection enqueued in $delay")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(Constants.LOG_TAG, "Worker started")
        return withContext(Dispatchers.IO) {
            try {
                sharedPreferences(applicationContext).apply {
                    edit().putInt(TRY_KEY, getInt(TRY_KEY, 0) + 1).apply()
                }

                // StatisticProvider will initialize StatisticService, so we just need to give it enough time
                withTimeout(10.seconds) {
                    Locator.resolve(StatisticService::class.java).await()
                }

                Log.i(Constants.LOG_TAG, "Worker end")
                Result.success()
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Worker failed unexpectedly", e)
                Result.failure()
            } finally {
                enqueue(applicationContext)
            }
        }
    }
}