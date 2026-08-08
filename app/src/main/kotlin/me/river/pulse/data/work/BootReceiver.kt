package me.river.pulse.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.river.pulse.data.Nightbell
import me.river.pulse.widget.NightbellWidgetProvider
import kotlinx.coroutines.launch

/** Re-arms every monitor chain after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val graph = Nightbell.install(context)
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
                NightbellMonitorService.sync(context)
                NightbellWidgetProvider.refresh(context)
            } finally {
                pending?.finish()
            }
        }
    }
}
