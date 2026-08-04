package me.river.pulse.data.work

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import me.river.pulse.data.Pulse
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.alerts.UrgentAlarm
import me.river.pulse.domain.Summary
import me.river.pulse.domain.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Strict monitoring: a foreground service that keeps its own cadence instead of
 * asking WorkManager nicely.
 *
 * ### Why this exists
 * WorkManager is the right default — it is battery-friendly and survives
 * reboots — but Doze can defer a one-shot chain for a long time, and its
 * periodic minimum is 15 minutes. A monitor set to "check every minute" simply
 * does not check every minute in the background. A foreground service is the
 * only supported way to get a real cadence, and the persistent notification is
 * the price Android charges for it.
 *
 * ### What runs it
 * The service is alive when *either* is true:
 *  - [me.river.pulse.domain.GlobalSettings.strictForegroundMonitoring] is on
 *    and at least one monitor is enabled, or
 *  - some monitor has an unacknowledged URGENT outage.
 *
 * The second condition means the urgent nag keeps its interval even with strict
 * mode off — which is the whole point of urgent — and the service shuts itself
 * down the moment the outage is acknowledged or recovers.
 *
 * ### Tradeoffs
 *  - Battery: a wake every [CheckEngine.MIN_TICK_MS]–[CheckEngine.MAX_IDLE_MS]
 *    plus the checks themselves. It sleeps until the next monitor is actually
 *    due rather than polling on a fixed tick, so an all-hourly fleet costs
 *    roughly one wake a minute doing nothing.
 *  - A permanent notification the user cannot dismiss (Android's rule, not ours).
 *  - It does not replace WorkManager: [MonitorScheduler] stays armed as the
 *    repair sweep, so a service killed by the OS still gets checks eventually.
 */
class PulseMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var started = false

    /**
     * The looping page sound. Taken from the graph rather than constructed here,
     * so acknowledging can silence *this* player directly instead of waiting for
     * the loop to come round and notice.
     */
    private val alarm: UrgentAlarm get() = Pulse.install(applicationContext).alarm

    override fun onCreate() {
        super.onCreate()
        // As early as Android allows. `startForegroundService` opens a ~5s window
        // in which this process is killed unless `startForeground` has been
        // called, and that window is not cancelled by the service being stopped
        // again in the meantime — see [sync].
        promoteImmediately()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FIRST, before anything that could be slow. Android gives a service
        // started with `startForegroundService` about five seconds to call
        // `startForeground`, and kills the *process* with
        // ForegroundServiceDidNotStartInTimeException if it misses. Building the
        // object graph — DataStore, OkHttp, the network monitor — used to happen
        // ahead of this, which is fine in a warm process and a crash in a cold
        // one. `AlertCenter` needs nothing but a Context, so the placeholder
        // notification is always cheap; the real content lands on the next tick.
        promoteImmediately()

        val graph = Pulse.install(applicationContext)

        if (intent?.action == ACTION_STOP) {
            // Explicit "stop strict mode" from the notification: flip the
            // setting too, otherwise the next sync would start us straight back.
            scope.launch {
                graph.store.updateSettings { it.copy(strictForegroundMonitoring = false) }
                stopSelfSafely()
            }
            return START_NOT_STICKY
        }

        if (loop == null) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val graph = Pulse.install(applicationContext)
        while (scope.isActive) {
            val snapshot = runCatchingCancellable { graph.store.currentSnapshot() }.getOrNull()
            if (snapshot == null) {
                delay(CheckEngine_MIN_TICK)
                continue
            }
            val strict = snapshot.settings.strictForegroundMonitoring &&
                snapshot.monitors.any { it.enabled }
            val nagging = snapshot.monitors.any { monitor ->
                monitor.urgent && snapshot.runtimes[monitor.id]?.urgentState?.nagging == true
            }
            if (!strict && !nagging) {
                stopSelfSafely()
                return
            }

            // Separate catches on purpose. These used to share one block, so a
            // checker throwing anywhere in the pass — a WebView blowing up on
            // one page, say — skipped the urgent tick *and* its reconciliation
            // sweep for that tick, and the sweep is what removes notifications
            // that should no longer be on screen.
            //
            // `runCatchingCancellable`, not `runCatching`: when this service is
            // stopping, every one of these throws CancellationException, and
            // swallowing that made the loop grind through a whole tick's worth of
            // work on a dead scope.
            if (strict) {
                runCatchingCancellable { graph.engine.runAllDue() }
                    .onFailure { Log.e(TAG, "Check pass failed", it) }
            }
            runCatchingCancellable { graph.engine.tickUrgent() }
                .onFailure { Log.e(TAG, "Urgent tick failed", it) }

            // ---- the page ----------------------------------------------------
            //
            // While anything is unacknowledged, *this* service's own notification
            // is the page. That is not a stylistic choice: `setColorized(true)` is
            // honoured only for a foreground-service notification, so the red card
            // exists here and nowhere else — the identical builder sent through
            // `NotificationManager.notify` renders as a white card with a red block
            // in it. One card at a time, oldest outage first, with the others
            // counted on it.
            val paging = runCatchingCancellable { graph.store.currentSnapshot() }
                .getOrNull()
                ?.let { snap ->
                    snap.monitors
                        .filter { it.urgent && snap.runtimes[it.id]?.urgentState?.nagging == true }
                        .sortedBy { snap.runtimes[it.id]?.urgentSinceAt ?: Long.MAX_VALUE }
                        .let { pending ->
                            pending.firstOrNull()?.let { first -> first to pending.size - 1 }
                        }
                }

            if (paging != null) {
                val (monitor, others) = paging
                val snapshot2 = runCatchingCancellable { graph.store.currentSnapshot() }.getOrNull()
                val runtime = snapshot2?.runtimes?.get(monitor.id)
                val policy = if (monitor.useGlobalAlerts) {
                    snapshot2?.settings?.defaultAlert
                } else {
                    monitor.alert
                }
                if (runtime != null && policy != null) {
                    val since = runtime.urgentSinceAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    val respectRinger = snapshot2.settings.urgentRespectsRingerMode
                    promoteWith(
                        graph.alerts.urgentPage(
                            monitor = monitor,
                            result = graph.engine.pageEvidence(runtime),
                            policy = policy,
                            downForMs = System.currentTimeMillis() - since,
                            pageCount = runtime.urgentPageCount.coerceAtLeast(1),
                            otherPending = others,
                            respectRinger = respectRinger,
                            // The loop below is making the noise; the channel must
                            // not chime over it.
                            silent = true,
                        ),
                    )
                    // Looping, because a channel's sound fires once per post and a
                    // five-minute gap between chimes is a reminder, not a pager.
                    // `start` re-reads the ringer every tick, so flipping the phone
                    // to vibrate quietens a page already in progress.
                    alarm.start(
                        style = policy.vibrationStyle,
                        vibrate = true,
                        respectRinger = respectRinger,
                    )
                    paging_ = true
                    // The per-monitor fallback copy, if an earlier pass posted one
                    // before this service could start, would now be a duplicate.
                    graph.alerts.cancelUrgent(monitor.id)
                    sleepUnlessWoken(
                        runCatchingCancellable { graph.engine.nextWakeDelayMs() }
                            .getOrDefault(CheckEngine_MIN_TICK),
                    )
                    continue
                }
            }
            // Nothing to page about: silence, and go back to the strict-mode notice.
            alarm.stop()
            paging_ = false

            val fleet = runCatchingCancellable {
                graph.store.currentSnapshot().let { Summary.of(it.monitors, it.runtimes) }
            }.getOrNull()
            val offline = !graph.network.isOnline()
            promote(
                alerts = graph.alerts,
                title = when {
                    // Ahead of the fleet verdict: while offline that verdict is a
                    // record of the past, and this notification is permanently on
                    // screen claiming to describe the present.
                    offline -> "Monitoring paused · offline"
                    fleet == null -> "Strict monitoring"
                    fleet.urgentPending > 0 -> "URGENT · ${fleet.urgentPending} unacknowledged"
                    else -> "Strict monitoring · ${fleet.headline}"
                },
                body = buildString {
                    if (offline) {
                        append("No connection — checks resume automatically.")
                    } else if (strict) {
                        append("Checking on schedule instead of waiting for Android to batch work.")
                    } else {
                        append("Keeping the urgent alert alive until it's acknowledged.")
                    }
                    if (fleet != null && fleet.total > 0) {
                        append("\n${fleet.total} monitor(s) · ${fleet.down} down · ${fleet.degraded} slow")
                    }
                },
            )

            val delayMs = runCatchingCancellable { graph.engine.nextWakeDelayMs() }
                .getOrDefault(CheckEngine_MIN_TICK)
            sleepUnlessWoken(delayMs)
        }
    }

    /**
     * Sleeps, unless something asks for attention first.
     *
     * A plain `delay` here is what made acknowledging feel broken. The loop is the
     * only thing that stops the alarm and re-renders the page, and after a page it
     * sleeps for `nextWakeDelayMs()` — floored at 15s and capped at 60s. So an ack
     * cancelled the notification and persisted the state instantly, and then the
     * phone carried on vibrating for up to a minute until the loop woke on its own
     * schedule. `sync()` could not help: it re-delivers `onStartCommand`, which
     * sees the loop already running and returns.
     */
    private suspend fun sleepUnlessWoken(ms: Long) {
        withTimeoutOrNull(ms) { wakeSignal.receive() }
    }

    /**
     * Enters the foreground with a placeholder, using nothing that can block.
     *
     * Idempotent: after the first call [started] is true and this is a plain
     * notification update, so calling it at the top of every `onStartCommand`
     * costs nothing.
     */
    private fun promoteImmediately() {
        val alerts = AlertCenter(applicationContext)
        promote(alerts, "Starting…", "Working out what needs checking.")
    }

    private fun promote(alerts: AlertCenter, title: String, body: String) =
        promoteWith(alerts.serviceNotification(title, body, stopPendingIntent()))

    /**
     * Posts [notification] as this service's foreground notification.
     *
     * Shared by the strict-mode notice and the URGENT page so both go through one
     * `startForeground`/`notify` decision — the service must never have two
     * different ideas of what its foreground notification is.
     */
    private fun promoteWith(notification: android.app.Notification) {
        if (!started) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        AlertCenter.SERVICE_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(AlertCenter.SERVICE_NOTIFICATION_ID, notification)
                }
                started = true
            }.onFailure { Log.e(TAG, "Could not enter the foreground", it) }
        } else {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(AlertCenter.SERVICE_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopSelfSafely() {
        // Before anything else: the noise must not outlive the page.
        alarm.stop()
        paging_ = false
        // Cancelling the loop cancels whatever check it had in flight. That is
        // correct and unavoidable — and up to 1.5.0 it was reported to the user
        // as "Checker crashed". `CheckEngine` now records nothing for a cancelled
        // check; this call additionally drops any crash claim, because a claim
        // about how checks were failing does not survive the thing that was
        // running them going away.
        runCatchingCancellable { Pulse.install(applicationContext).engine.resetCheckerHealth("service stopping") }
        loop?.cancel()
        loop = null
        runCatchingCancellable { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, PulseMonitorService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        alarm.stop()
        paging_ = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PulseMonitorService"

        /**
         * Whether this service is currently the thing showing the URGENT page.
         *
         * Process-wide rather than per-instance because the engine has to know
         * before it decides whether to post its own fallback copy, and it has no
         * handle on the service. False whenever the service is not running, which
         * is the safe default: it makes the engine post, and a duplicate is
         * cancelled on the next tick whereas a missed page is missed.
         */
        @Volatile
        private var paging_: Boolean = false

        fun isPaging(): Boolean = paging_

        /**
         * Conflated: several state changes in quick succession are one reason to
         * wake up, and a signal sent while the loop is working is kept so the next
         * sleep returns at once rather than missing it.
         */
        private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        /**
         * Cuts the loop's sleep short. Called from
         * [me.river.pulse.data.Pulse.Graph.notifyStateChanged], so anything that
         * changes paging state — an acknowledgement above all — is acted on in
         * milliseconds instead of on the next tick.
         */
        fun wake() {
            wakeSignal.trySend(Unit)
        }
        private const val CheckEngine_MIN_TICK = 15_000L
        const val ACTION_SYNC = "me.river.pulse.action.SERVICE_SYNC"
        const val ACTION_STOP = "me.river.pulse.action.SERVICE_STOP"

        /**
         * Starts or stops the service to match the current store.
         *
         * Safe to call from anywhere and as often as you like — it is a
         * reconciliation, not a command. Swallows the
         * `ForegroundServiceStartNotAllowedException` Android 12+ throws when
         * the app is in the background and has no exemption: strict mode then
         * simply doesn't engage until the app is next opened, and the
         * WorkManager sweep covers the gap.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val graph = Pulse.install(app)
            graph.appScope.launch {
                val snapshot = runCatchingCancellable { graph.store.currentSnapshot() }
                    .getOrNull() ?: return@launch
                val strict = snapshot.settings.strictForegroundMonitoring &&
                    snapshot.monitors.any { it.enabled }
                val nagging = snapshot.monitors.any { monitor ->
                    monitor.urgent && snapshot.runtimes[monitor.id]?.urgentState?.nagging == true
                }
                val intent = Intent(app, PulseMonitorService::class.java).setAction(ACTION_SYNC)
                if (strict || nagging) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            app.startForegroundService(intent)
                        } else {
                            app.startService(intent)
                        }
                    }.onFailure { Log.w(TAG, "Foreground start refused: ${it.message}") }
                } else {
                    // Deliberately *not* `stopService`. Android does not cancel the
                    // "you must call startForeground" promise when a service is
                    // stopped, so a start and a stop landing in the same few
                    // milliseconds — two state changes in quick succession, which
                    // is routine — killed the process with
                    // ForegroundServiceDidNotStartInTimeException. The loop already
                    // shuts itself down on the first tick where neither strict mode
                    // nor a page needs it, so there is nothing to command here.
                    Log.i(TAG, "Nothing needs the service; it will stand down on its next tick")
                }
            }
        }
    }
}
