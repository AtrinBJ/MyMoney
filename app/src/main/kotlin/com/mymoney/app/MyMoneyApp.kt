package com.mymoney.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mymoney.app.reminders.DailyMaintenanceWorker
import com.mymoney.core.domain.settings.AppSettings
import androidx.lifecycle.ProcessLifecycleOwner
import com.mymoney.app.lock.AppLockManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyMoneyApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var lockManager: AppLockManager

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lockManager)
        // Daily maintenance always runs (trash purge, AUTO_POST rules); it notifies only if reminders are on.
        // KEEP: an existing schedule (possibly at the user's chosen hour) is left untouched.
        DailyMaintenanceWorker.schedule(this, hour = AppSettings().reminderHour)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()
}
