package me.river.pulse.data.alerts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import me.river.pulse.data.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the inline notification actions ("Re-check now", "Mute 1h"). */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val monitorId = intent.getStringExtra(EXTRA_MONITOR_ID) ?: return
        val graph = Pulse.install(context)
        // goAsync() is only valid inside a real broadcast dispatch; it is null
        // when the receiver is invoked directly (tests, internal fan-out).
        val pending = runCatching { goAsync() }.getOrNull()

        graph.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_RECHECK -> {
                        NotificationManagerCompat.from(context).cancel(notificationIdOf(monitorId))
                        graph.engine.run(monitorId)
                    }

                    ACTION_MUTE_1H -> {
                        graph.engine.mute(monitorId, MUTE_DURATION_MS)
                        withContext(Dispatchers.Main) {
                            NotificationManagerCompat.from(context).cancel(notificationIdOf(monitorId))
                        }
                    }

                    else -> Unit
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Alert action failed", error)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlertActionReceiver"
        const val ACTION_RECHECK = "me.river.pulse.action.RECHECK"
        const val ACTION_MUTE_1H = "me.river.pulse.action.MUTE_1H"
        const val EXTRA_MONITOR_ID = "monitor_id"
        private const val MUTE_DURATION_MS = 60 * 60 * 1000L

        fun pendingIntent(context: Context, action: String, monitorId: String): PendingIntent {
            val intent = Intent(context, AlertActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_MONITOR_ID, monitorId)
            }
            return PendingIntent.getBroadcast(
                context,
                (action + monitorId).hashCode() and 0x7FFFFFF,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun notificationIdOf(monitorId: String): Int = 100_000 + (monitorId.hashCode() and 0x7FFF)
    }
}
