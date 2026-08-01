package me.river.pulse

import android.app.Application
import android.util.Log
import me.river.pulse.data.Pulse
import kotlinx.coroutines.launch

class PulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val graph = Pulse.install(this)
        graph.appScope.launch {
            runCatching {
                val snapshot = graph.store.currentSnapshot()
                repairNotificationsIfNeeded(graph, snapshot.settings.notificationsRepairedForVersion)
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            }.onFailure { Log.e(TAG, "Failed to re-arm schedules", it) }
        }
    }

    /**
     * One-time cleanup after upgrading from a build that could strand alert
     * notifications (1.1.0 and earlier — see HANDOFF).
     *
     * Runs before the schedule sync so the first check of the new version
     * re-posts from a clean slate. Guarded by a persisted version so it happens
     * exactly once per upgrade, not on every launch.
     */
    private suspend fun repairNotificationsIfNeeded(graph: Pulse.Graph, repairedFor: Int) {
        if (repairedFor >= REPAIR_VERSION) return
        Log.i(TAG, "Clearing stale notifications once, upgrading repair $repairedFor → $REPAIR_VERSION")
        graph.alerts.cancelEverything()
        graph.store.updateSettings { it.copy(notificationsRepairedForVersion = REPAIR_VERSION) }
    }

    private companion object {
        const val TAG = "PulseApplication"

        /**
         * Bump alongside `versionCode` whenever a release needs to clear
         * notifications left behind by its predecessor.
         */
        const val REPAIR_VERSION = 4
    }
}
