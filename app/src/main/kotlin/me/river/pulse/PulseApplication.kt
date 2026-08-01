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
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            }.onFailure { Log.e("PulseApplication", "Failed to re-arm schedules", it) }
        }
    }
}
