package me.river.pulse

import android.app.Application
import android.util.Log
import me.river.pulse.data.Pulse
import me.river.pulse.domain.runCatchingCancellable
import kotlinx.coroutines.launch

class PulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val graph = Pulse.install(this)

        // Synchronously, before any worker or receiver in this process can run: a
        // checker-crash claim is process-scoped by design (see CheckerHealth), so
        // one left on screen by a process that no longer exists is stale by
        // definition. This is "clear stale crash state after app restart",
        // and it costs one cancel() call.
        graph.engine.resetCheckerHealth("process start")

        graph.appScope.launch {
            runCatchingCancellable {
                val snapshot = graph.store.currentSnapshot()
                repairNotificationsIfNeeded(graph, snapshot.settings.notificationsRepairedForVersion)
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            }.onFailure { Log.e(TAG, "Failed to re-arm schedules", it) }
        }
    }

    /**
     * One-time cleanup after upgrading from a build that could strand alert
     * notifications.
     *
     * Runs before the schedule sync so the first check of the new version
     * re-posts from a clean slate. Guarded by a persisted version so it happens
     * exactly once per upgrade, not on every launch.
     *
     * Repair 4 covered 1.1.0's stranded alerts. Repair 5 covers 1.5.0 and
     * earlier, which could leave a whole fleet's worth of `ongoing`,
     * DND-bypassing "URGENT · … is down / Checker crashed" notifications behind —
     * one per monitor, none of them describing anything that happened. The
     * persisted state that fed them is scrubbed separately and on every read; see
     * `PulseStore.scrubFakeCrashState`.
     */
    private suspend fun repairNotificationsIfNeeded(graph: Pulse.Graph, repairedFor: Int) {
        if (repairedFor >= REPAIR_VERSION) return
        Log.i(TAG, "Clearing stale notifications once, upgrading repair $repairedFor → $REPAIR_VERSION")
        graph.alerts.cancelEverything()

        // `cancelEverything` is indiscriminate — it has to be, since the whole
        // point is recovering from a state we cannot enumerate. But it also takes
        // down notifications describing outages that are happening *right now*, and
        // the claim that "anything genuinely current re-posts on the next check" is
        // simply not true of the down and degraded tracks: they are
        // transition-driven, and `AlertDecider.decide` with `wasAlerting = true` and
        // `repeatEnabled = false` (the shipped default) returns NO_TRANSITION for
        // as long as the outage lasts. A live outage would have gone completely
        // silent — no notification on screen and none coming — until it recovered.
        //
        // Clearing the bookkeeping turns the next check back into a transition, so
        // whatever is still true is raised again within one interval. `lastAlertAt`
        // goes with it or the cooldown would swallow that re-raise instead.
        // `urgentActive` is deliberately left alone: a genuine nag re-posts itself
        // from `tickUrgent` within its repeat gap, and the fabricated ones have
        // already been cleared by `LegacyCrashRepair`.
        graph.store.updateAllRuntimes {
            it.copy(
                alerting = false,
                lastAlertAt = 0L,
                degradedAlerting = false,
                lastDegradedAlertAt = 0L,
            )
        }
        graph.store.updateSettings { it.copy(notificationsRepairedForVersion = REPAIR_VERSION) }
    }

    private companion object {
        const val TAG = "PulseApplication"

        /**
         * Bump alongside `versionCode` whenever a release needs to clear
         * notifications left behind by its predecessor.
         */
        const val REPAIR_VERSION = 5
    }
}
