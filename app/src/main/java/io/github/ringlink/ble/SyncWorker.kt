package io.github.ringlink.ble

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ringlink.L
import io.github.ringlink.data.Settings
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync.
 *
 * The heavy lifting stays in [RingService], which owns the Bluetooth link; this worker only nudges
 * it. That split matters on Android 12+, where a background component may not start a foreground
 * service — so if the service is not running the worker asks to be retried rather than forcing it.
 *
 * The sync is requested un-forced, so the service's own guards still apply: it skips quiet hours
 * (draining mid-night would shred the backlog the ring is still accumulating) and skips syncing
 * again too soon.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (settings.ringAddress == null) return Result.success()

        val service = RingService.instance
        if (service == null) {
            L.d("periodic sync: service not running, retrying later")
            return Result.retry()
        }
        return try {
            service.syncNow(force = false)
            Result.success()
        } catch (t: Throwable) {
            L.e("periodic sync failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "ringlink-periodic-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
