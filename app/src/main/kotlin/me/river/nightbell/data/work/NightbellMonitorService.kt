package me.river.nightbell.data.work

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.data.alerts.LiveCard
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.LiveNotice
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var watcher: Job? = null

    /** What the loop last decided the service is running for, for [watchLiveCard]. */
    @Volatile
    private var runningStrict = true
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
        }.onFailure { Diag.log(LogEvent.SCHED_SERVICE_REFUSED, LogField.tag("at", "screen_receiver"), LogField.error("why", it)) }
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
        //
        // Only the first time. `sync` re-delivers this to a service that is already
        // in the foreground, and `notifyStateChanged` sends it right after waking
        // the loop, so the placeholder used to land *on top of* the redraw that
        // wake had just produced. The loop then slept for up to a minute with the
        // card reading "Starting…", which a tap in the live card settings did
        // every time. Later starts wake the loop instead, so it draws the real thing.
        if (!started) promoteImmediately()

        if (loop == null) {
            loop = scope.launch { runLoop() }
            watcher = scope.launch { watchLiveCard() }
        } else {
            wake()
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
            runningStrict = strict
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
            Diag.log(
                LogEvent.SCHED_SERVICE_TICK,
                LogField.of("strict", strict),
                LogField.of("paging", isPaging()),
            )
            if (strict) {
                runCatchingCancellable { graph.engine.runAllDue() }
                    .onFailure { Diag.logError(LogEvent.CHECK_FAILED, it, LogField.tag("at", "service_pass")) }
            }
            runCatchingCancellable { graph.engine.tickUrgent() }
                .onFailure { Diag.logError(LogEvent.ALERT_URGENT_FAILED, it, LogField.tag("at", "tick")) }

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
                    val page = graph.alerts.urgentPage(
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
                    )
                    // Under the notice lock, and marked as paging before the lock
                    // is let go: a live card redraw already waiting in it would
                    // otherwise land on top of the red page and hide it until the
                    // next pass.
                    noticeLock.withLock {
                        promoteWith(page)
                        paging_ = true
                    }
                    // Looping, because a channel's sound fires once per post and a
                    // five-minute gap between chimes is a reminder, not a pager.
                    // `start` re-reads the ringer every tick, so flipping the phone
                    // to vibrate quietens a page already in progress.
                    alarm.start(
                        style = policy.vibrationStyle,
                        vibrate = true,
                        respectRinger = respectRinger,
                    )
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

            renderNotice(strict)

            val delayMs = runCatchingCancellable { graph.engine.nextWakeDelayMs() }
                .getOrDefault(CheckEngine_MIN_TICK)
            sleepUnlessWoken(delayMs)
        }
    }

    /**
     * Posts the strict monitoring notice from the store as it is now.
     *
     * Words and line together, from the same function the Settings preview uses,
     * so what the preview promises is what gets posted. Serialised, because the
     * loop and [watchLiveCard] both call it and an older snapshot must not land
     * on top of a newer one.
     */
    private suspend fun renderNotice(strict: Boolean, onlyOverTheNotice: Boolean = false) {
        val graph = Nightbell.install(applicationContext)
        noticeLock.withLock {
            // Spaced, because NotificationManagerService drops an app's updates past
            // about five a second ("Package enqueue rate is 5.6. Shedding" in the
            // emulator's log) and the one dropped can be the last: three quick taps
            // in Settings, each drawn by the watcher and again by the woken loop,
            // left the card on the previous choice until the next tick. The
            // snapshot is read after the wait, so the post that lands is the newest.
            val wait = MIN_NOTICE_GAP_MS - (SystemClock.elapsedRealtime() - lastNoticeAt)
            if (wait > 0) delay(wait)
            // Asked again after the wait, for the watcher. In that time a page may
            // have gone up, which this must not cover, or the service may have
            // stopped, and a post now would put it back in the foreground.
            if (onlyOverTheNotice && (!started || isPaging())) return@withLock
            val latest = runCatchingCancellable { graph.store.currentSnapshot() }.getOrNull()
            val offline = !graph.network.isOnline()
            val notice = latest?.let {
                LiveNotice.of(
                    monitors = it.monitors,
                    runtimes = it.runtimes,
                    groups = it.groups,
                    prefs = it.settings.liveCard,
                    nowMs = System.currentTimeMillis(),
                    offline = offline,
                    lineSupported = LiveCard.supported,
                    strict = strict,
                )
            }
            promote(
                alerts = graph.alerts,
                title = notice?.title ?: "Strict monitoring",
                body = notice?.body ?: "Checking on schedule instead of waiting for Android to batch work.",
                timeline = notice?.line,
            )
            lastNoticeAt = SystemClock.elapsedRealtime()
        }
    }

    private val noticeLock = Mutex()
    private var lastNoticeAt = 0L

    /**
     * Redraws the notice the moment a live card choice changes, whatever the loop
     * is doing.
     *
     * The loop only draws between check passes, and a pass that finds a failure
     * stays to confirm it: retries 30 seconds apart for up to seven minutes. Waking
     * it does nothing until that is over, so on a fleet with something failing a
     * choice made in Settings could take minutes to appear. Seen on an emulator: a
     * choice made right after a pass had found failures was still not drawn 15
     * seconds later. This draws from the store
     * directly, and leaves the urgent page alone: while one is up, it is the
     * notification and the strict notice waits for it to end.
     */
    private suspend fun watchLiveCard() {
        val graph = Nightbell.install(applicationContext)
        graph.store.snapshot
            // Only what the notice reads off a group. Opening or shutting one on
            // the dashboard writes it too, and each of those used to repost.
            .map { snap -> snap.settings.liveCard to snap.groups.map { Triple(it.id, it.displayTitle, it.memberIds) } }
            .distinctUntilChanged()
            // No drop(1) for "it is only the current value": a choice saved in the
            // moment between the service starting and this subscribing arrives as
            // that first value, and dropping it left the card on the old choice
            // while the loop was busy confirming a failure. Drawing the current
            // state once on start is harmless, and replaces "Starting…" sooner.
            .collect {
                if (started && !isPaging()) {
                    runCatchingCancellable { renderNotice(strict = runningStrict, onlyOverTheNotice = true) }
                }
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
        if (!spoken) Diag.log(LogEvent.ALERT_SPEAK, LogField.monitor(monitor.id), LogField.of("spoken", false))
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
                Diag.log(
                    LogEvent.SCHED_SERVICE_START,
                    LogField.of("api", Build.VERSION.SDK_INT),
                )
            }.onFailure { Diag.logError(LogEvent.SCHED_SERVICE_REFUSED, it, LogField.tag("at", "foreground")) }
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
        // With the loop, or a quick restart before onDestroy launches a second
        // watcher next to this one and every choice is drawn twice.
        watcher?.cancel()
        watcher = null
        // Out of the foreground from here on, so the next start has to promote
        // again. A start that lands before onDestroy keeps this instance, and with
        // `started` still true it skipped startForeground, which Android punishes
        // by killing the process.
        started = false
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

        /** Four posts a second at most, under the platform's five. */
        private const val MIN_NOTICE_GAP_MS = 250L
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
                    }.onFailure { Diag.log(LogEvent.SCHED_SERVICE_REFUSED, LogField.tag("at", "start"), LogField.error("why", it)) }
                } else {
                    // Deliberately *not* `stopService`. Android does not cancel the
                    // "you must call startForeground" promise when a service is
                    // stopped, so a start and a stop landing in the same few
                    // milliseconds — two state changes in quick succession, which
                    // is routine — killed the process with
                    // ForegroundServiceDidNotStartInTimeException. The loop already
                    // shuts itself down on the first tick where neither strict mode
                    // nor a page needs it, so there is nothing to command here.
                    Diag.log(LogEvent.SCHED_SERVICE_STOP, LogField.tag("why", "nothing_needs_it"))
                }
            }
        }
    }
}
