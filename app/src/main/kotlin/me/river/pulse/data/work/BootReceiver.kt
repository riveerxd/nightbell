package me.river.pulse.data.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.river.pulse.data.Pulse
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
                val snapshot = graph.store.currentSnapshot()
                graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            } finally {
                pending?.finish()
            }
        }
    }
}
