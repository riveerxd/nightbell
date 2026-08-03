package me.river.pulse.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.river.pulse.data.Pulse
import me.river.pulse.widget.PulseWidgetProvider
import kotlinx.coroutines.launch

/** Re-arms every monitor chain after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val graph = Pulse.install(context)
        val pending = runCatching { goAsync() }.getOrNull()
        graph.appScope.launch {
            try {
                // A reboot or an app update rebuilds every schedule, so nothing a
                // previous process concluded about failing checks still applies.
                graph.engine.clearCheckerHealth(intent.action ?: "boot")
                val snapshot = graph.store.currentSnapshot()
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
                // Strict mode and any unacknowledged urgent outage have to
                // survive a reboot; sync() decides whether that means starting
                // the service or leaving it alone.
                PulseMonitorService.sync(context)
                PulseWidgetProvider.refresh(context)
            } finally {
                pending?.finish()
            }
        }
    }
}
