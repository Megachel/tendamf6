package com.megachel.tendamf6.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.megachel.tendamf6.data.Prefs
import java.util.concurrent.TimeUnit

object Scheduler {
    const val TAG_PERIODIC = "tenda_poll_periodic"
    private const val ONE_SHOT = "tenda_poll_now"

    /**
     * 15 minutes is the shortest period WorkManager allows.
     * The constraint is plain CONNECTED rather than UNMETERED: Android often
     * flags a mobile modem's Wi-Fi as metered, which would block the work.
     */
    fun schedulePeriodic(context: Context) {
        val minutes = Prefs.intervalMinutes.coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<PollWorker>(minutes, TimeUnit.MINUTES)
            .addTag(TAG_PERIODIC)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TAG_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun pollNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PollWorker>()
            // Default backoff starts at 30s and doubles; paired with the attempt
            // cap in PollWorker this keeps the retries inside a minute.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(TAG_PERIODIC)
    }
}
