package me.river.pulse.data.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.river.pulse.data.Pulse
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Monitor
import java.util.concurrent.TimeUnit
import me.river.pulse.domain.runCatchingCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Runs one monitor.
 *
 * ### What changed in 1.6.0, and why
 * This used to re-arm itself at the end of `doWork` — `enqueueUniqueWork` with
 * [ExistingWorkPolicy.REPLACE], under **its own unique name**, while still
 * running. REPLACE cancels the work it replaces, so every scheduled check
 * cancelled itself on the way out. Worse, [MonitorScheduler.syncAll] did the same
 * REPLACE across every monitor, and it was called from the 15-minute
 * [SweepWorker], from `PulseApplication.onCreate`, from [BootReceiver] and from
 * every settings write — so any of those killed whatever checks were in flight,
 * all of them at once.
 *
 * `CheckEngine` then reported each of those cancellations as a failed check named
 * "Checker crashed", which escalated through the down track into the URGENT nag
 * loop. Reproduced from a real device: six monitors, six simultaneous
 * "URGENT · … is down / Checker crashed" notifications, timestamped the same
 * minute the foreground service was reporting "All 6 operational" — because the
 * checks had in fact all succeeded.
 *
 * Cadence is now expressed as [PeriodicWorkRequest] with
 * [ExistingPeriodicWorkPolicy.UPDATE], which never cancels a running worker, and
 * nothing re-arms itself from inside its own execution.
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val monitorId = inputData.getString(KEY_MONITOR_ID) ?: return Result.failure()
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            // Inside the try, deliberately. Building the graph touches
            // NotificationManager, creates channel groups and registers a
            // connectivity callback; a throw there used to escape `doWork`, and
            // WorkerWrapper turns an escaped throwable into a **terminal FAILED**
            // state — from which `ExistingPeriodicWorkPolicy.UPDATE` cannot recover
            // this work at all. See MonitorScheduler.schedule.
            val graph = Pulse.install(applicationContext)
            val snapshot = graph.store.currentSnapshot()
            val monitor = snapshot.monitors.firstOrNull { it.id == monitorId }
                ?: return Result.success() // deleted while queued
            if (!monitor.enabled || !snapshot.settings.backgroundChecksEnabled) return Result.success()

            // Periodic work fires on Android's schedule, not on the monitor's, and
            // the repair sweep may have just checked this monitor a second ago.
            // Without this gate a monitor was checked several times within the
            // same few seconds — visible on a real device as three samples one
            // second apart — and each extra run was another chance to be
            // cancelled and mis-reported.
            // Answered from the snapshot already in hand — the store is a JSON
            // document behind DataStore, so re-reading it here would decode the
            // whole thing a second time for one timestamp.
            if (!force && !graph.engine.isDue(monitor, snapshot.runtimes[monitorId])) {
                Log.i(TAG, "$monitorId is not due yet; skipping")
                return Result.success()
            }

            graph.engine.run(monitorId, force = force)
            Result.success()
        } catch (cancellation: CancellationException) {
            // WorkManager stopped us: constraints no longer met, execution window
            // over, work replaced, app force-stopped. Nothing failed, so nothing
            // is reported. Propagating lets WorkManager record this as cancelled
            // instead of retrying a run nobody is waiting for.
            Log.i(TAG, "Monitor worker for $monitorId was stopped by WorkManager")
            throw cancellation
        } catch (error: Throwable) {
            Log.e(TAG, "Monitor worker failed for $monitorId", error)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_MONITOR_ID = "monitor_id"

        /** Set by "check now": run regardless of whether the interval has elapsed. */
        const val KEY_FORCE = "force"
        private const val MAX_ATTEMPTS = 3
        private const val TAG = "MonitorWorker"
    }
}

/**
 * Periodic safety net: runs anything overdue and makes sure every enabled monitor
 * still has live periodic work.
 *
 * Also the only background path that can honour a sub-15-minute interval at all
 * — at 15-minute granularity, because that is Android's floor for periodic work.
 * Strict foreground mode is the only way to do better; see the README.
 */
class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Inside the try — see MonitorWorker.doWork for why.
            val graph = Pulse.install(applicationContext)
            val snapshot = graph.store.currentSnapshot()
            if (!snapshot.settings.backgroundChecksEnabled) return Result.success()
            val ran = graph.engine.runAllDue()
            // Repair after the pass. This used to be the single biggest source of
            // false crash alerts: `syncAll` REPLACEd the unique work of every
            // monitor, cancelling each check that was running in parallel with
            // this sweep. It now updates periodic work in place and cancels
            // nothing.
            graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            Log.i(TAG, "Sweep ran $ran monitor(s)")
            Result.success()
        } catch (cancellation: CancellationException) {
            Log.i(TAG, "Sweep was stopped by WorkManager")
            throw cancellation
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

    /**
     * Arms — or re-arms in place — a monitor's own periodic check.
     *
     * [ExistingPeriodicWorkPolicy.UPDATE] is the whole point: it applies the new
     * spec to the next period and **does not cancel a running worker**. Every
     * caller of this is a reconciliation ("make the schedule match the store"),
     * and a reconciliation that kills work in flight is how a healthy fleet
     * ended up reporting six simultaneous crashes.
     *
     * Intervals below [MIN_PERIODIC_MINUTES] are clamped, because that is
     * Android's floor for periodic work
     * ([PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS]). Nothing is silently
     * lost: [SweepWorker] still picks such a monitor up as overdue on every wake,
     * and strict foreground mode honours the real interval. The app says so in
     * Settings rather than pretending.
     */
    suspend fun schedule(monitor: Monitor, settings: GlobalSettings) {
        if (!monitor.enabled || !settings.backgroundChecksEnabled) {
            cancel(monitor.id)
            return
        }
        clearIfDead(periodicName(monitor.id))
        val minutes = monitor.intervalMinutes.toLong().coerceAtLeast(MIN_PERIODIC_MINUTES)
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(MonitorWorker.KEY_MONITOR_ID to monitor.id))
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag(TAG_MONITOR)
            .build()
        workManager.enqueueUniquePeriodicWork(
            periodicName(monitor.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Runs a monitor as soon as constraints allow — used by "check now" actions.
     *
     * Its own unique name, so it can never replace (and therefore never cancel)
     * the periodic worker. [ExistingWorkPolicy.KEEP] rather than REPLACE because
     * the honest response to "check now" when a check is already on its way is
     * "one is already on its way", not "kill that one and start again".
     */
    fun requestImmediate(monitorId: String, settings: GlobalSettings = GlobalSettings()) {
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(
                workDataOf(
                    MonitorWorker.KEY_MONITOR_ID to monitorId,
                    MonitorWorker.KEY_FORCE to true,
                ),
            )
            .setConstraints(constraints(settings))
            .addTag(TAG_MONITOR)
            .build()
        workManager.enqueueUniqueWork(immediateName(monitorId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(monitorId: String) {
        workManager.cancelUniqueWork(periodicName(monitorId))
        workManager.cancelUniqueWork(immediateName(monitorId))
        workManager.cancelUniqueWork(legacyName(monitorId))
    }

    suspend fun syncAll(monitors: List<Monitor>, settings: GlobalSettings) {
        monitors.forEach { monitor ->
            // Retire the 1.5.0-and-earlier self-re-arming chain. Its worker no
            // longer re-arms, so it would die out on its own after one more run —
            // but that run would be an ungated duplicate check, so it goes now.
            workManager.cancelUniqueWork(legacyName(monitor.id))
            if (monitor.enabled && settings.backgroundChecksEnabled) {
                schedule(monitor, settings)
            } else {
                cancel(monitor.id)
            }
        }
        ensureSweep(settings)
    }

    suspend fun ensureSweep(settings: GlobalSettings) {
        if (!settings.backgroundChecksEnabled) {
            workManager.cancelUniqueWork(SWEEP_NAME)
            return
        }
        clearIfDead(SWEEP_NAME)
        val request = PeriodicWorkRequestBuilder<SweepWorker>(MIN_PERIODIC_MINUTES, TimeUnit.MINUTES)
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

    /**
     * Clears unique work that has reached a terminal state, so it can be enqueued
     * again.
     *
     * `ExistingPeriodicWorkPolicy.UPDATE` deliberately does not touch a running
     * worker — that is the whole point of using it — but the same code path also
     * refuses to touch a **finished** one: `WorkerUpdater.updateWorkImpl` returns
     * `NOT_APPLIED` when `state.isFinished`. A periodic job only has to end FAILED
     * once (a single throwable escaping `doWork` is enough) and it is retired
     * forever, with every later `syncAll` a silent no-op. A monitor that quietly
     * stops being checked and cannot be repaired is the worst failure this app has.
     *
     * Only cancels when **nothing** for that name is enqueued or running, so it can
     * never kill a check in flight. `CANCELLED` is then handled by
     * `enqueueUniquelyNamedPeriodic`, which deletes the spec and inserts fresh.
     */
    private suspend fun clearIfDead(uniqueName: String) {
        val dead = runCatchingCancellable {
            val infos = workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
            infos.isNotEmpty() && infos.all { it.state.isFinished }
        }.getOrDefault(false)
        if (dead) {
            Log.w(TAG, "$uniqueName had reached a terminal state; clearing so it can re-arm")
            workManager.cancelUniqueWork(uniqueName)
        }
    }

    private fun periodicName(monitorId: String) = "pulse.monitor.periodic.$monitorId"

    private fun immediateName(monitorId: String) = "pulse.monitor.now.$monitorId"

    /** The unique name 1.5.0 and earlier used for its one-shot chain. */
    private fun legacyName(monitorId: String) = "pulse.monitor.$monitorId"

    companion object {
        private const val TAG = "MonitorScheduler"
        private const val SWEEP_NAME = "pulse.sweep"
        const val TAG_MONITOR = "pulse.monitor"
        const val TAG_SWEEP = "pulse.sweep.tag"

        /**
         * Android's minimum period for [PeriodicWorkRequest], in minutes. Not a
         * choice Pulse gets to make — `PeriodicWorkRequest` clamps anything
         * shorter, silently, so the app clamps it visibly instead.
         */
        const val MIN_PERIODIC_MINUTES =
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000L
    }
}
