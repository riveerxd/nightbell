package me.river.nightbell.data.alerts

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the inline notification actions ("Re-check now", "Mute 1h", and friends). */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val graph = Nightbell.install(context)
        // goAsync() is only valid inside a real broadcast dispatch; it is null
        // when the receiver is invoked directly (tests, internal fan-out).
        val pending = runCatching { goAsync() }.getOrNull()

        graph.appScope.launch {
            try {
                when (action) {
                    ACTION_UPDATE_REMIND, ACTION_UPDATE_IGNORE ->
                        handleUpdate(context, graph, action, intent.getStringExtra(EXTRA_VERSION).orEmpty())

                    else -> {
                        val monitorId = intent.getStringExtra(EXTRA_MONITOR_ID) ?: return@launch
                        handleMonitor(context, graph, action, monitorId)
                    }
                }
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Diag.logError(LogEvent.ALERT_URGENT_FAILED, error, LogField.tag("at", "action"))
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun handleMonitor(
        context: Context,
        graph: Nightbell.Graph,
        action: String,
        monitorId: String,
    ) {
        when (action) {
            ACTION_RECHECK -> {
                NotificationManagerCompat.from(context).cancel(notificationIdOf(monitorId))
                graph.engine.run(monitorId)
            }

            ACTION_MUTE_1H -> {
                graph.engine.mute(monitorId, MUTE_1H_MS)
                withContext(Dispatchers.Main) {
                    NotificationManagerCompat.from(context).cancel(notificationIdOf(monitorId))
                }
            }

            // Repository news, so a full day rather than an hour: the thing being
            // muted is a stream of updates about somebody's project, and an hour
            // of quiet from that is not a meaningful amount of quiet.
            ACTION_MUTE_24H -> {
                graph.engine.mute(monitorId, MUTE_24H_MS)
                graph.alerts.cancelGitHub(monitorId)
            }

            // "I have read this." Takes down everything the repository has on
            // screen and records when, so the detail card can say so. It does not
            // touch the last-seen ids: those already advanced when the poll found
            // the news, and rolling them back would announce all of it again.
            ACTION_MARK_SEEN -> {
                graph.alerts.cancelGitHub(monitorId)
                graph.store.updateRuntime(monitorId) {
                    it.copy(github = it.github.copy(seenAt = System.currentTimeMillis()))
                }
            }

            // "I've seen it." Stops the urgent loop for this outage but
            // leaves the monitor down, its card red, and the ordinary
            // down notification exactly where it was.
            ACTION_ACK_URGENT -> graph.engine.acknowledgeUrgent(monitorId)

            else -> Unit
        }
    }

    /**
     * The two ways to say no to an update notice.
     *
     * Both take the notification down immediately, because a button that leaves
     * the thing it dismissed on screen reads as a button that did not work.
     */
    private suspend fun handleUpdate(
        context: Context,
        graph: Nightbell.Graph,
        action: String,
        version: String,
    ) {
        graph.alerts.cancelUpdate()
        val now = System.currentTimeMillis()
        graph.store.updateAppUpdate { state ->
            when (action) {
                ACTION_UPDATE_IGNORE -> AppUpdate.ignore(state, version)
                else -> AppUpdate.remindLater(state, now)
            }
        }
        Diag.log(LogEvent.UPDATE_NOTICE_DISMISSED, LogField.text("action", action))
    }

    companion object {
        private const val TAG = "AlertActionReceiver"
        const val ACTION_RECHECK = "me.river.nightbell.action.RECHECK"
        const val ACTION_MUTE_1H = "me.river.nightbell.action.MUTE_1H"
        const val ACTION_MUTE_24H = "me.river.nightbell.action.MUTE_24H"
        const val ACTION_ACK_URGENT = "me.river.nightbell.action.ACK_URGENT"
        const val ACTION_MARK_SEEN = "me.river.nightbell.action.MARK_SEEN"
        const val ACTION_UPDATE_REMIND = "me.river.nightbell.action.UPDATE_REMIND"
        const val ACTION_UPDATE_IGNORE = "me.river.nightbell.action.UPDATE_IGNORE"
        const val EXTRA_MONITOR_ID = "monitor_id"
        const val EXTRA_VERSION = "version"
        private const val MUTE_1H_MS = 60 * 60 * 1000L
        private const val MUTE_24H_MS = 24 * 60 * 60 * 1000L

        fun pendingIntent(context: Context, action: String, monitorId: String): PendingIntent {
            val intent = Intent(context, AlertActionReceiver::class.java).apply {
                this.action = action
                // The update actions are about the app rather than a monitor, so
                // they carry a version instead. Same receiver, different payload.
                if (action == ACTION_UPDATE_REMIND || action == ACTION_UPDATE_IGNORE) {
                    putExtra(EXTRA_VERSION, monitorId)
                } else {
                    putExtra(EXTRA_MONITOR_ID, monitorId)
                }
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
