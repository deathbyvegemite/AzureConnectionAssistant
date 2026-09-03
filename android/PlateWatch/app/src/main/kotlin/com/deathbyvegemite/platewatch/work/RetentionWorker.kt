package com.deathbyvegemite.platewatch.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deathbyvegemite.platewatch.PlateWatchApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Deletes sightings past their retention age, once a day.
 *
 * Retention is a real feature rather than housekeeping: a log that grows forever is
 * both a liability and, eventually, a phone full of JPEGs.
 */
class RetentionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val container = (applicationContext as PlateWatchApp).container
        val days = container.settingsStore.settings.first().retentionDays
        val removed = container.repository.purgeExpired(days)
        if (removed > 0) Log.i(TAG, "Purged $removed sighting(s) older than $days days")
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Retention sweep failed", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "RetentionWorker"
        private const val WORK_NAME = "platewatch-retention"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
