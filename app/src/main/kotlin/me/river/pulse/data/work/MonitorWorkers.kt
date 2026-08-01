package me.river.pulse.data.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.river.pulse.data.Pulse
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Monitor
import java.util.concurrent.TimeUnit

/**
 * Runs one monitor, then re-arms itself for its own interval. This gives
 * per-monitor cadences (including sub-15-minute ones, best effort) that
 * WorkManager's periodic API can't express, while [SweepWorker] acts as the
 * safety net that repairs any chain the OS has dropped.
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = Pulse.install(applicationContext)
        val monitorId = inputData.getString(KEY_MONITOR_ID) ?: return Result.failure()
        return try {
            val snapshot = graph.store.currentSnapshot()
            val monitor = snapshot.monitors.firstOrNull { it.id == monitorId }
                ?: return Result.success() // deleted while queued
            if (!monitor.enabled || !snapshot.settings.backgroundChecksEnabled) return Result.success()

            graph.engine.run(monitorId)
            graph.scheduler.scheduleNext(monitor, snapshot.settings)
            Result.success()
        } catch (error: Throwable) {
            Log.e(TAG, "Monitor worker failed for $monitorId", error)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_MONITOR_ID = "monitor_id"
        private const val TAG = "MonitorWorker"
    }
}

/**
 * Periodic safety net: runs anything that is overdue and makes sure every
 * enabled monitor still has a live one-shot chain.
 */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = Pulse.install(applicationContext)
        return try {
            val snapshot = graph.store.currentSnapshot()
            if (!snapshot.settings.backgroundChecksEnabled) return Result.success()
            val ran = graph.engine.runAllDue()
            graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            Log.i(TAG, "Sweep ran $ran monitor(s)")
            Result.success()
        } catch (error: Throwable) {
            Log.e(TAG, "Sweep failed", error)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "SweepWorker"
    }
}

/** Thin façade over WorkManager so the rest of the app never touches it. */
class MonitorScheduler(private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun constraints(settings: GlobalSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (settings.onlyOnUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .build()

    /** Re-arms a monitor for one interval from now. */
    fun scheduleNext(monitor: Monitor, settings: GlobalSettings) {
        if (!monitor.enabled || !settings.backgroundChecksEnabled) {
            cancel(monitor.id)
            return
        }
        val delayMinutes = monitor.intervalMinutes.coerceAtLeast(1).toLong()
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(workDataOf(MonitorWorker.KEY_MONITOR_ID to monitor.id))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG_MONITOR)
            .build()
        workManager.enqueueUniqueWork(uniqueName(monitor.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Runs a monitor as soon as constraints allow — used by "check now" actions. */
    fun requestImmediate(monitorId: String, settings: GlobalSettings = GlobalSettings()) {
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(Data.Builder().putString(MonitorWorker.KEY_MONITOR_ID, monitorId).build())
            .setConstraints(constraints(settings))
            .addTag(TAG_MONITOR)
            .build()
        workManager.enqueueUniqueWork(uniqueName(monitorId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(monitorId: String) = workManager.cancelUniqueWork(uniqueName(monitorId)).let { }

    fun syncAll(monitors: List<Monitor>, settings: GlobalSettings) {
        monitors.forEach { monitor ->
            if (monitor.enabled && settings.backgroundChecksEnabled) {
                scheduleNext(monitor, settings)
            } else {
                cancel(monitor.id)
            }
        }
        ensureSweep(settings)
    }

    fun ensureSweep(settings: GlobalSettings) {
        if (!settings.backgroundChecksEnabled) {
            workManager.cancelUniqueWork(SWEEP_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SweepWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .addTag(TAG_SWEEP)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SWEEP_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelEverything() {
        workManager.cancelAllWorkByTag(TAG_MONITOR)
        workManager.cancelUniqueWork(SWEEP_NAME)
    }

    private fun uniqueName(monitorId: String) = "pulse.monitor.$monitorId"

    companion object {
        private const val SWEEP_NAME = "pulse.sweep"
        const val TAG_MONITOR = "pulse.monitor"
        const val TAG_SWEEP = "pulse.sweep.tag"
    }
}
