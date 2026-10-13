package com.mymoney.app.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mymoney.app.data.FinanceSnapshotProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** Runs [DailyMaintenance] once a day. No network, no wakelocks, no exact alarms (docs/02 §Battery). */
@HiltWorker
class DailyMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val provider: FinanceSnapshotProvider,
    private val maintenance: DailyMaintenance,
    private val notifier: AndroidNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val snapshot = provider.snapshot.first()
        notifier.settings = snapshot.settings
        notifier.currencies = snapshot.currencies
        maintenance.run(snapshot, notifier)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val NAME = "daily-maintenance"

        /**
         * Schedules the daily run near [hour] local time. KEEP when nothing changed, so app starts do not push
         * the next run forward; UPDATE when the user picks a new hour.
         */
        fun schedule(context: Context, hour: Int, hourChanged: Boolean = false) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                if (hourChanged) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
