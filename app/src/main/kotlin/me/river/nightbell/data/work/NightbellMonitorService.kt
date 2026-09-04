package me.river.nightbell.data.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.data.alerts.LiveCard
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.LiveTimeline
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.SpokenPage
import me.river.nightbell.domain.Summary
import me.river.nightbell.domain.runCatchingCancellable
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
 *  - [me.river.nightbell.domain.GlobalSettings.strictForegroundMonitoring] is on
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
class NightbellMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var started = false

    /**
     * The looping page sound. Taken from the graph rather than constructed here,
     * so acknowledging can silence *this* player directly instead of waiting for
     * the loop to come round and notice.
     */
    private val alarm: UrgentAlarm get() = Nightbell.install(applicationContext).alarm

    /**
     * Re-issues the page's haptics the instant the screen turns off.
     *
     * The system cancels an ongoing vibration on screen-off (the vibrator history logs it
     * as `cancelled_by_screen_off`), so pressing the power button silenced a page set to
     * vibrate — the one thing a pager must never let the user do by accident. A vibration
     * *re-issued* while the screen is already off runs normally, so this catches the
     * transition and starts it again. Only while actually paging; the alarm no-ops when
     * nothing should be buzzing. `ACTION_SCREEN_ON` is caught too, as a cheap belt-and-
     * braces revive if a device cancels on wake as well. These are runtime-only
     * broadcasts — they cannot be declared in the manifest.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isPaging()) alarm.reassertVibration()
        }
    }
    private var screenReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
            screenReceiverRegistered = true
        }.onFailure { Log.w(TAG, "Could not register screen receiver", it) }
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

        if (loop == null) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val graph = Nightbell.install(applicationContext)
        while (scope.isActive) {
            val snapshot = runCatchingCancellable { graph.store.currentSnapshot() }.getOrNull()
            if (snapshot == null) {
                delay(CheckEngine_MIN_TICK)
                continue
            }
            // A pause that stops the checks stops this too. Strict mode exists to
            // hold a cadence, and there is no cadence to hold while paused: left
            // running it sat there once a minute doing nothing behind a permanent
            // notification claiming the fleet was being watched, which is the one
            // lie a monitoring app cannot afford. Resuming starts it again, the
            // same way enabling a monitor does.
            val strict = snapshot.settings.strictForegroundMonitoring &&
                snapshot.monitors.any { it.enabled } &&
                !snapshot.pause.stopsChecks(System.currentTimeMillis())
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
                    // Said after the siren is already looping, on purpose. The
                    // noise is the part that wakes someone; the sentence is the
                    // part that tells them what for, and it mutes the siren for
                    // the few seconds it takes to say it.
                    announce(
                        monitor = monitor,
                        runtime = runtime,
                        policy = policy,
                        settings = snapshot2.settings,
                        otherPending = others,
                        respectRinger = respectRinger,
                    )
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
            // Forgotten with the outage. The page counter restarts at one for the
            // next one, so a remembered key would silence the announcement for
            // the second outage of the same monitor.
            lastSpokenKey = null
            // The page is over, so the engine binding goes with it: holding a TTS
            // service bound for the life of the app keeps another process alive
            // for something that speaks a sentence a minute at most. Deferred by
            // the speaker itself if it happens to be mid-sentence.
            Nightbell.install(applicationContext).speaker.release()

            val latest = runCatchingCancellable { graph.store.currentSnapshot() }.getOrNull()
            val fleet = latest?.let { Summary.of(it.monitors, it.runtimes) }
            val offline = !graph.network.isOnline()
            // The check history as a ride-card line, on the releases that can draw
            // one. Built from the same snapshot as the verdict above it, or the bar
            // and the headline could disagree by one check. Not built at all where
            // nothing can render it — this loop runs every 15 to 60 seconds forever.
            val timeline = latest?.takeIf { LiveCard.supported }?.let {
                LiveTimeline.of(
                    monitors = it.monitors,
                    runtimes = it.runtimes,
                    nowMs = System.currentTimeMillis(),
                    offline = offline,
                )
            }
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
                    } else if (timeline != null && fleet != null && fleet.total > 0) {
                        // With the line drawn, the paragraph explaining what strict
                        // mode is has nowhere to go: ProgressStyle draws no big text,
                        // so it was being clipped mid-sentence two lines in. What is
                        // worth those two lines instead is what the bar covers —
                        // there is no axis on it, so an outage a third of the way
                        // along could be ten bad minutes or eight bad hours.
                        append("${fleet.total} monitors · ${fleet.down} down · ${fleet.degraded} slow")
                        append(" · last ${timeline.spanLabel}")
                    } else if (strict) {
                        append("Checking on schedule instead of waiting for Android to batch work.")
                    } else {
                        append("Keeping the urgent alert alive until it's acknowledged.")
                    }
                    if (timeline == null && fleet != null && fleet.total > 0) {
                        append("\n${fleet.total} monitor(s) · ${fleet.down} down · ${fleet.degraded} slow")
                    }
                },
                timeline = timeline,
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
    /**
     * Reads the page out loud, if the user asked for that and the ringer allows it.
     *
     * Held to one announcement per page rather than one per tick by
     * [SpokenPage.isDue], keyed on the outage's page counter, so a fifteen-second
     * loop cannot turn into a fifteen-second stutter and what is spoken can never
     * disagree with what the notification says.
     *
     * Failures are swallowed deliberately. A missing engine, a language pack the
     * user removed, an engine that hangs: none of those may take down the loop
     * that is at that moment the only thing paging anybody. Settings reports the
     * same readiness for whoever wants to know why it went quiet.
     */
    private suspend fun announce(
        monitor: Monitor,
        runtime: MonitorRuntime,
        policy: AlertPolicy,
        settings: GlobalSettings,
        otherPending: Int,
        respectRinger: Boolean,
    ) {
        val pageCount = runtime.urgentPageCount.coerceAtLeast(1)
        val usage = alarm.speechUsage(respectRinger)
        val key = SpokenPage.keyOf(monitor.id, pageCount)
        val due = SpokenPage.isDue(
            enabled = policy.speak,
            audible = usage != null,
            onRepeats = settings.speakOnRepeats,
            pageCount = pageCount,
            key = key,
            lastSpokenKey = lastSpokenKey,
        )
        if (!due || usage == null) return
        val graph = Nightbell.install(applicationContext)
        val evidence = graph.engine.pageEvidence(runtime)
        val since = runtime.urgentSinceAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val text = SpokenPage.render(
            template = settings.speakTemplate,
            name = monitor.displayName,
            reason = evidence.message.ifBlank { evidence.failureKind.headline },
            downForMs = System.currentTimeMillis() - since,
            otherPending = otherPending,
        )
        // Marked spoken before the engine is asked, not after. An engine that
        // takes eight seconds would otherwise let the next tick conclude the same
        // announcement was still owed.
        lastSpokenKey = key
        val spoken = runCatchingCancellable {
            graph.speaker.say(text = text, usage = usage, voice = settings.speakVoice)
        }.getOrDefault(false)
        if (!spoken) Log.w(TAG, "Nothing was said for ${monitor.displayName}")
    }

    /** The last announcement this service made, so one page is read out once. */
    private var lastSpokenKey: String? = null

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

    private fun promote(
        alerts: AlertCenter,
        title: String,
        body: String,
        timeline: LiveTimeline.Timeline? = null,
    ) = promoteWith(alerts.serviceNotification(title, body, timeline))

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
        runCatchingCancellable { Nightbell.install(applicationContext).engine.resetCheckerHealth("service stopping") }
        loop?.cancel()
        loop = null
        runCatchingCancellable { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        alarm.stop()
        paging_ = false
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NightbellMonitorService"

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
         * [me.river.nightbell.data.Nightbell.Graph.notifyStateChanged], so anything that
         * changes paging state — an acknowledgement above all — is acted on in
         * milliseconds instead of on the next tick.
         */
        fun wake() {
            wakeSignal.trySend(Unit)
        }
        private const val CheckEngine_MIN_TICK = 15_000L
        const val ACTION_SYNC = "me.river.nightbell.action.SERVICE_SYNC"

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
            val graph = Nightbell.install(app)
            graph.appScope.launch {
                val snapshot = runCatchingCancellable { graph.store.currentSnapshot() }
                    .getOrNull() ?: return@launch
                // Same rule as `runLoop`, and it has to be the same rule. With only
                // the loop knowing about the pause, this went on starting the
                // service through one: promote to the foreground, notice there is
                // nothing to do, stop, and do it again on the next state change.
                val strict = snapshot.settings.strictForegroundMonitoring &&
                    snapshot.monitors.any { it.enabled } &&
                    !snapshot.pause.stopsChecks(System.currentTimeMillis())
                val nagging = snapshot.monitors.any { monitor ->
                    monitor.urgent && snapshot.runtimes[monitor.id]?.urgentState?.nagging == true
                }
                val intent = Intent(app, NightbellMonitorService::class.java).setAction(ACTION_SYNC)
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
