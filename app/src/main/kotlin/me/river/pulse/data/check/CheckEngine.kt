package me.river.pulse.data.check

import android.util.Log
import me.river.pulse.data.PulseStore
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.domain.AlertDecider
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.CheckerHealth
import me.river.pulse.domain.CheckerStreak
import me.river.pulse.domain.DueCheck
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.NetworkBaseline
import me.river.pulse.domain.UrgentAlerts
import me.river.pulse.domain.runCatchingCancellable
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The heart of the app: runs a check, folds the outcome into persisted state,
 * and decides whether the human deserves to be interrupted.
 *
 * Four independent alert tracks run off one check:
 *  - **down** — the original outage/recovery escalation ([AlertDecider.decide])
 *  - **degraded** — latency-SLO breaches ([AlertDecider.decideDegraded])
 *  - **urgent** — the nag-until-acknowledged loop ([UrgentAlerts])
 *  - **checker health** — the checker itself being broken ([CheckerHealth])
 *
 * They deliberately don't share cooldowns or notification ids, so silencing one
 * never silences another. The fourth is the newest and the most important
 * separation: up to 1.5.0 a fault *inside Pulse* was reported through the down
 * track as though the monitored site had gone down, and a cancelled check
 * counted as a fault. See [CheckerHealth] for the whole story.
 */
class CheckEngine(
    private val store: PulseStore,
    private val http: HttpChecker,
    private val element: ElementChecker,
    private val alerts: AlertCenter,
    /**
     * Times a known-good endpoint so a slow *connection* is not reported as a
     * slow service. Null disables the compensation entirely, which is what the
     * unit-level tests want.
     */
    private val reference: LatencyReference? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val minuteOfDay: () -> Int = {
        Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    },
) {

    /**
     * Called after anything that could change what a widget or a foreground
     * service shows. Wired up by [me.river.pulse.data.Pulse]; a plain
     * lambda keeps this class free of Android plumbing.
     */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Whether the device can reach anything. Wired to
     * [me.river.pulse.data.net.NetworkMonitor] by the graph — a lambda
     * for the same reason as [onStateChanged], and defaulting to online so the
     * engine stays usable and testable with no Android around it.
     *
     * When this is false the engine does not check, does not record a sample and
     * does not alert. A check with no connectivity fails for a reason that says
     * nothing about the monitored thing, and it fails for *every* monitor at
     * once — so running them would turn a walk through a tunnel into a screenful
     * of false outages, and would poison the uptime history with them too.
     */
    var isOnline: () -> Boolean = { true }

    /** Runs the check without touching persisted state — used by "Test now". */
    suspend fun dryRun(monitor: Monitor): CheckResult = when (monitor.kind) {
        MonitorKind.WEBSITE_ELEMENT -> element.check(monitor)
        else -> http.check(monitor)
    }

    /**
     * One lock per monitor.
     *
     * `run()` reads the runtime, spends seconds doing network or WebView work,
     * then writes state derived from that read. Two overlapping runs of the
     * same monitor therefore clobber each other, and — worse — the losing run
     * can post a notification the winning run has already decided not to
     * cancel. Strict mode makes this easy to hit: the service loop and a manual
     * "Check now" can land on the same monitor seconds apart.
     *
     * Different monitors still run concurrently; only same-monitor overlap is
     * serialised.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The checker's own health. See [CheckerHealth] — process-scoped on purpose,
     * so a restart cannot inherit a stale crash claim.
     */
    private val _checkerHealth = MutableStateFlow(CheckerHealth.State.Healthy)
    val checkerHealth: StateFlow<CheckerHealth.State> = _checkerHealth.asStateFlow()

    /**
     * Full pipeline for one monitor: check → persist → maybe alert.
     *
     * Returns null when the monitor no longer exists, when the device is offline
     * and the check was skipped, or when an exception escaped checker code — in
     * all three cases nothing was recorded about the monitor, because nothing was
     * learned about it.
     *
     * **Throws [CancellationException]** rather than reporting anything if the
     * check is cancelled. That is the whole fix: a cancelled check has no verdict,
     * and inventing one is what made the phone buzz about crashes that never
     * happened.
     */
    suspend fun run(monitorId: String, force: Boolean = true): CheckResult? =
        locks.getOrPut(monitorId) { Mutex() }.withLock { runLocked(monitorId, force) }

    private suspend fun runLocked(monitorId: String, force: Boolean): CheckResult? {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return null
        val before = snapshot.runtimes[monitorId] ?: MonitorRuntime()
        val settings = snapshot.settings

        // Re-checked *inside* the lock, not only by the caller. Every scheduled
        // caller evaluates due-ness before waiting on this mutex, and what it waits
        // behind is another check of this same monitor — so by the time the lock is
        // free the interval has usually just been satisfied. Without this, the sweep
        // and a monitor's own worker sharing one wake-up produced two checks a
        // second apart, which is the duplicate-sample symptom the outer gate was
        // added to fix.
        if (!force && !isDue(monitor, before, nowMs())) {
            Log.i(TAG, "${monitor.displayName} became not-due while waiting; skipping")
            return null
        }

        // Before `markChecking`, deliberately: bailing after it would leave the
        // monitor spinning forever. Callers gate too — this is the backstop that
        // makes it impossible for any of them to record an offline failure.
        if (!isOnline()) {
            Log.i(TAG, "Offline — skipping ${monitor.displayName}")
            return null
        }

        store.markChecking(monitorId, true)
        val result = try {
            dryRun(monitor)
        } catch (cancellation: CancellationException) {
            // The single most important catch in this app.
            //
            // Cancellation here is Android working correctly, not a failure:
            // WorkManager replacing or stopping work, a foreground service
            // shutting down, a `viewModelScope` closing because the user left the
            // screen, an execution window ending, the process being reclaimed.
            // 1.5.0 and earlier caught this with the `Throwable` clause below,
            // fabricated a failed result labelled "Checker crashed", and ran it
            // through the down-alert track — notification, vibration and all. The
            // persist that would have applied a cooldown was the *next*
            // suspending call on the same cancelled coroutine, so it threw too,
            // and the next cancellation alerted at full volume all over again.
            //
            // A cancelled check has no verdict. Record nothing, say nothing, and
            // let cancellation finish travelling up the stack where it belongs.
            //
            // Folded through the machine rather than simply skipped, so the
            // "cancellation is not evidence" rule lives in one tested place
            // ([CheckerHealth.recordCancellation]) instead of being re-assumed
            // here. It is a no-op today; if that ever changes, it changes here too.
            _checkerHealth.value = CheckerHealth.recordCancellation(_checkerHealth.value, nowMs()).state
            Log.i(TAG, "Check for ${monitor.displayName} was cancelled; no verdict recorded")
            throw cancellation
        } catch (error: Throwable) {
            // A real escape from checker code. Both checkers classify their own
            // failures into FailureKind and do not throw, so anything arriving
            // here is a bug in ours — which is a statement about Pulse, not about
            // the monitored service. It goes to the checker-health track and
            // *nowhere near* this monitor's health: "our code broke" has never
            // been evidence that somebody's website is down.
            Log.e(TAG, "Checker threw while checking ${monitor.displayName}", error)
            noteInternalError(monitor, settings, snapshot.checkerStreak, error)
            null
        } finally {
            store.markChecking(monitorId, false)
        }
        if (result == null) {
            // Record *only* that an attempt happened at this time — no health, no
            // sample, no message. Without this the monitor stays permanently due,
            // and `nextWakeDelayMs` floors to MIN_TICK_MS, so a checker that
            // throws every time would be retried every 15 seconds forever. The old
            // code got interval back-off for free because it fabricated a full
            // verdict; not fabricating one means paying for the back-off honestly.
            store.updateRuntime(monitorId) { it.copy(lastCheckedAt = nowMs()) }
            return null
        }

        // The check reached a verdict, so the checker demonstrably works —
        // whatever that verdict was. A classified failure is proof of a working
        // checker just as much as a pass is.
        noteVerdict(snapshot.checkerStreak)

        val slo = monitor.sloMs(settings)

        // Discount whatever this phone's own connection is adding before calling
        // anything slow. Only consulted for the latency verdict: a bad connection
        // is a reason to doubt a *slow* reading, never a reason to stay quiet
        // about an outage.
        val verdict = NetworkBaseline.judge(
            observedMs = result.latencyMs,
            readings = if (settings.latencyBaselineEnabled) snapshot.reference else emptyList(),
            nowMs = result.at,
        )
        val judgedLatency = if (settings.latencyBaselineEnabled) verdict.adjustedMs else result.latencyMs
        val suspect = settings.latencyBaselineEnabled && verdict.unreliable

        val after = AlertDecider.advance(
            previous = before,
            result = result,
            historyDepth = settings.historyDepth,
            // The health tint follows the same judgement as the alert, or a card
            // would sit amber explaining nothing while no notification arrived.
            degradedAboveMs = if (slo > 0 && !suspect) slo.toLong() else Long.MAX_VALUE,
            judgedLatencyMs = judgedLatency,
        )
        val degraded = !suspect && AlertDecider.isDegraded(result.ok, judgedLatency, slo.toLong())
        val policy: AlertPolicy = if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert
        val muted = before.mutedUntil > nowMs()
        val minute = minuteOfDay()

        // ---- down track -----------------------------------------------------
        val decision = if (muted) {
            AlertDecider.Decision.none(AlertDecider.Suppression.POLICY_DISABLED)
        } else {
            AlertDecider.decide(
                wasAlerting = before.alerting,
                ok = result.ok,
                consecutiveFailures = after.consecutiveFailures,
                lastAlertAt = before.lastAlertAt,
                policy = policy,
                masterEnabled = settings.masterAlertsEnabled,
                nowMs = result.at,
                minuteOfDay = minute,
            )
        }

        when (decision.kind) {
            AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> alerts.notifyDown(
                monitor = monitor,
                result = result,
                policy = policy,
                silent = decision.forceSilent,
                repeat = decision.kind == AlertDecider.Kind.REPEAT,
            )

            AlertDecider.Kind.RECOVERY -> alerts.notifyRecovery(
                monitor = monitor,
                result = result,
                policy = policy,
                silent = decision.forceSilent,
            )

            else -> Unit
        }

        val alertingNow = when (decision.kind) {
            AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> true
            AlertDecider.Kind.RECOVERY -> false
            else -> if (result.ok) false else before.alerting
        }
        // Clear the stale "down" notification — unless we just replaced it with a
        // recovery notification, which reuses the same id and would be wiped out.
        //
        // Not gated on `before.alerting`: that is read before the check runs and
        // can be stale, which is exactly how a down notification outlives the
        // outage it describes. Healthy and no recovery posted ⇒ nothing should
        // be showing, so cancel and don't reason about how it got there.
        if (result.ok && decision.kind != AlertDecider.Kind.RECOVERY) {
            alerts.cancel(monitor.id)
        }

        // ---- degraded track --------------------------------------------------
        val degradedDecision = if (muted) {
            AlertDecider.Decision.none(AlertDecider.Suppression.POLICY_DISABLED)
        } else {
            AlertDecider.decideDegraded(
                wasDegradedAlerting = before.degradedAlerting,
                ok = result.ok,
                degraded = degraded,
                lastDegradedAlertAt = before.lastDegradedAlertAt,
                policy = policy,
                masterEnabled = settings.masterAlertsEnabled,
                nowMs = result.at,
                minuteOfDay = minute,
            )
        }

        when (degradedDecision.kind) {
            AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> alerts.notifyDegraded(
                monitor = monitor,
                result = result,
                policy = policy,
                sloMs = slo,
                silent = degradedDecision.forceSilent,
                repeat = degradedDecision.kind == AlertDecider.Kind.DEGRADED_REPEAT,
            )

            AlertDecider.Kind.DEGRADED_RECOVERY -> alerts.notifyDegradedRecovery(
                monitor = monitor,
                result = result,
                policy = policy,
                sloMs = slo,
                silent = degradedDecision.forceSilent,
            )

            else -> Unit
        }

        val degradedAlertingNow = when (degradedDecision.kind) {
            AlertDecider.Kind.DEGRADED, AlertDecider.Kind.DEGRADED_REPEAT -> true
            AlertDecider.Kind.DEGRADED_RECOVERY -> false
            // An outage supersedes slowness; the down notification is the story.
            else -> if (!result.ok) false else if (degraded) before.degradedAlerting else false
        }
        if (!degraded && before.degradedAlerting &&
            degradedDecision.kind != AlertDecider.Kind.DEGRADED_RECOVERY
        ) {
            alerts.cancelDegraded(monitor.id)
        }

        // ---- urgent track ----------------------------------------------------
        val urgentOutcome = UrgentAlerts.evaluate(
            previous = before.urgentState,
            eligible = urgentEligible(monitor, policy, settings, after, muted, minute),
            down = !result.ok,
            nowMs = result.at,
            repeatMinutes = monitor.urgentRepeatMinutes,
        )
        val urgentMutation = applyUrgent(monitor, urgentOutcome, result, policy, after)

        // NonCancellable, and this is load-bearing.
        //
        // Every alert side effect above — notifyDown, notifyRecovery, cancel,
        // cancelDegraded, cancelUrgent, notifyUrgent — is a plain function call, so
        // there is no suspension point between them and this write. Cancellation
        // can therefore only ever be observed *here*, after the shade has already
        // been changed. Letting it through leaves the store asserting the state the
        // notifications no longer show: a recovered monitor still recorded DOWN with
        // `urgentActive = true`, which `tickUrgent` then re-shouts about — ongoing,
        // DND-bypassing, "URGENT · X is down" for a monitor that is up. Which is
        // precisely the symptom this release was opened for.
        //
        // The write is a single small DataStore commit, so making it uninterruptible
        // is bounded and cheap.
        withContext(NonCancellable) {
            store.updateRuntime(monitorId) {
                after
                    .copy(
                        lastNetworkExcessMs = if (settings.latencyBaselineEnabled) verdict.excessMs else 0L,
                        lastLatencySuspect = suspect,
                        alerting = alertingNow,
                        lastAlertAt = if (decision.shouldNotify) result.at else before.lastAlertAt,
                        degradedAlerting = degradedAlertingNow,
                        lastDegradedAlertAt = if (degradedDecision.shouldNotify) {
                            result.at
                        } else {
                            before.lastDegradedAlertAt
                        },
                        mutedUntil = before.mutedUntil,
                        health = if (!monitor.enabled) Health.PAUSED else after.health,
                    )
                    .withUrgentState(urgentOutcome.state)
                    .let(urgentMutation)
            }
        }
        onStateChanged?.invoke()
        return result
    }

    /** Serialises probing so a burst of passes shares one round trip. */
    private val referenceLock = Mutex()

    /**
     * Consecutive failed probes, for backing off.
     *
     * In memory on purpose: a fresh process retrying sooner is harmless, and this
     * has no business in the persisted store.
     */
    private var referenceFailures = 0

    /**
     * Times the reference endpoint if the newest reading has gone stale.
     *
     * Called once per **pass**, never per check. It used to run inside
     * `runLocked`, which meant a reference the network blocks added its whole
     * timeout to a check — the measurement itself was unaffected, but the pass
     * got slower for no benefit. One timing per pass is all the maths wants
     * anyway, since the window is a rolling estimate and tolerates a reading a
     * little older than the check it informs.
     *
     * Backs off exponentially while probes fail, so a network that blocks the
     * endpoint costs one wasted request every half hour rather than one per pass.
     */
    private suspend fun refreshReference() {
        val settings = store.currentSnapshot().settings
        if (!settings.latencyBaselineEnabled) return
        val probe = reference ?: return
        if (!isOnline()) return

        referenceLock.withLock {
            val interval = REFERENCE_MIN_INTERVAL_MS shl referenceFailures.coerceAtMost(6)
            val readings = store.currentSnapshot().reference
            if (!NetworkBaseline.needsProbe(readings, nowMs(), interval)) return
            val rtt = probe.probe(settings.latencyReferenceUrl)
            referenceFailures = if (rtt == null) (referenceFailures + 1).coerceAtMost(6) else 0
            store.updateReference { NetworkBaseline.record(it, rtt, nowMs()) }
        }
    }

    /**
     * Whether the urgent loop is allowed to shout right now.
     *
     * Urgent overrides cooldown and the repeat toggle — that is the feature —
     * but it deliberately still honours the master switch, the per-monitor
     * alert switch, mute, the failure threshold and quiet hours. Urgent means
     * "don't let me miss it", not "ignore everything I configured".
     */
    private fun urgentEligible(
        monitor: Monitor,
        policy: AlertPolicy,
        settings: GlobalSettings,
        after: MonitorRuntime,
        muted: Boolean,
        minute: Int,
    ): Boolean {
        if (!monitor.urgent) return false
        if (muted) return false
        if (!settings.masterAlertsEnabled) return false
        if (!policy.enabled || !policy.alertOnDown) return false
        if (after.consecutiveFailures < policy.failureThreshold.coerceAtLeast(1)) return false
        val quiet = policy.quietHoursEnabled &&
            AlertDecider.inQuietHours(minute, policy.quietStartMinute, policy.quietEndMinute)
        return !quiet || policy.criticalBypassesQuiet
    }

    /**
     * Applies one urgent outcome.
     *
     * The page itself is *not* posted here any more. It is the foreground
     * service's own notification, because `setColorized` — the only way to have
     * the platform paint the whole card in the down colour — is honoured for
     * nothing else; verified on a device. So this records that a page is owed and
     * lets [me.river.pulse.data.work.PulseMonitorService] render it, falling back
     * to an ordinary post when that service could not be started.
     *
     * @return the runtime mutation this outcome implies.
     */
    private fun applyUrgent(
        monitor: Monitor,
        outcome: UrgentAlerts.Outcome,
        result: CheckResult?,
        policy: AlertPolicy,
        runtime: MonitorRuntime,
    ): (MonitorRuntime) -> MonitorRuntime {
        when (outcome.action) {
            UrgentAlerts.Action.START, UrgentAlerts.Action.REPEAT -> {
                val at = nowMs()
                val evidence = result ?: CheckResult(
                    ok = false,
                    latencyMs = runtime.lastLatencyMs,
                    failureKind = FailureKind.UNKNOWN,
                    message = runtime.lastMessage.ifBlank { "Still down" },
                    at = at,
                )
                pageWanted = true
                // The service owns the loud copy. If it is not running — Android 12+
                // refuses a background foreground-service start without an
                // exemption — this at least still interrupts the user.
                if (!serviceIsPaging()) {
                    val since = if (runtime.urgentSinceAt == 0L) at else runtime.urgentSinceAt
                    alerts.postUrgentPageFallback(
                        monitor = monitor,
                        result = evidence,
                        policy = policy,
                        downForMs = at - since,
                        pageCount = runtime.urgentPageCount + 1,
                    )
                }
                return { it.withUrgentPaged(at) }
            }

            UrgentAlerts.Action.CLEAR -> {
                alerts.cancelUrgent(monitor.id)
                return { it }
            }

            UrgentAlerts.Action.NONE -> return { it }
        }
    }

    /**
     * Whether the foreground service is currently the thing showing the page.
     *
     * Wired by the graph. Defaults to false so the engine stays testable and so
     * the fallback post — the safe direction — is what happens when nothing has
     * told us otherwise.
     */
    var serviceIsPaging: () -> Boolean = { false }

    /**
     * Set whenever a page is owed, so the service knows to re-render and start
     * making noise without re-deriving the decision. Read-and-cleared by
     * [consumePageWanted].
     */
    @Volatile
    private var pageWanted: Boolean = false

    fun consumePageWanted(): Boolean {
        val wanted = pageWanted
        pageWanted = false
        return wanted
    }

    // ---- checker health ------------------------------------------------------

    /**
     * Folds one escaped exception into [CheckerHealth] and acts on the verdict.
     *
     * Honours the master alert switch and quiet hours, because a broken checker
     * is still an interruption and the user's settings still mean what they say.
     * Vibration only on the first [CheckerHealth.Action.RAISE], never on a
     * repeat: repeats exist so the notification does not silently rot, not to
     * nag.
     */
    private suspend fun noteInternalError(
        monitor: Monitor,
        settings: GlobalSettings,
        persisted: CheckerStreak,
        error: Throwable,
    ) {
        val now = nowMs()
        // Evidence from disk, claim from this process. `Application.onCreate` runs
        // on every WorkManager-spawned process, so a purely in-memory streak could
        // never reach the raise threshold in background-only operation — see
        // [CheckerStreak].
        val previous = CheckerHealth.hydrate(persisted, _checkerHealth.value, now)
        val outcome = CheckerHealth.recordInternalError(
            previous = previous,
            monitorId = monitor.id,
            signature = error::class.java.simpleName,
            detail = error.message ?: error::class.java.simpleName,
            nowMs = now,
        )
        _checkerHealth.value = outcome.state
        store.updateCheckerStreak { CheckerHealth.toStreak(outcome.state) }
        when (outcome.action) {
            CheckerHealth.Action.RAISE, CheckerHealth.Action.REPEAT -> {
                // The monitor's *effective* policy, resolved the same way every
                // other track resolves it. Reading `settings.defaultAlert`
                // directly meant a user whose monitors all carry their own policy
                // was judged by a global default they had never opened: vibration
                // they had switched off, and quiet hours they had switched on,
                // both ignored.
                val policy = if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert
                val quiet = policy.quietHoursEnabled && AlertDecider.inQuietHours(
                    minuteOfDay(),
                    policy.quietStartMinute,
                    policy.quietEndMinute,
                )
                // `policy.enabled` as well as the master switch. Every other
                // track honours it (AlertDecider returns POLICY_DISABLED,
                // urgentEligible returns false); the editor renders it as "You
                // won't be notified at all", so buzzing through it would make that
                // sentence a lie.
                if (!settings.masterAlertsEnabled || !policy.enabled) {
                    Log.i(TAG, "Checker crash verified but alerts are switched off")
                } else {
                    alerts.notifyCheckerCrash(
                        state = outcome.state,
                        monitorName = monitor.displayName,
                        policy = policy,
                        silent = quiet,
                        repeat = outcome.action == CheckerHealth.Action.REPEAT,
                    )
                }
            }

            CheckerHealth.Action.CLEAR -> alerts.cancelCheckerHealth()
            CheckerHealth.Action.NONE -> Unit
        }
        onStateChanged?.invoke()
    }

    /** A check produced a verdict, so the checker works. Clears any crash claim. */
    private suspend fun noteVerdict(persisted: CheckerStreak) {
        val nothingToClear = persisted.isEmpty && _checkerHealth.value == CheckerHealth.State.Healthy
        // The overwhelmingly common case, and it must not cost a DataStore write on
        // every single successful check.
        if (nothingToClear) return
        val outcome = CheckerHealth.recordVerdict(_checkerHealth.value, nowMs())
        _checkerHealth.value = outcome.state
        if (!persisted.isEmpty) store.updateCheckerStreak { CheckerStreak.Empty }
        if (outcome.action == CheckerHealth.Action.CLEAR) alerts.cancelCheckerHealth()
    }

    /**
     * Drops any crash claim and takes its notification down.
     *
     * Called from everything that changes *how* checks run — process start,
     * background checks being switched off, the scheduler re-arming, the strict
     * service starting or stopping — because a claim about how checks were
     * failing does not survive the checks being rebuilt. Cheap and idempotent;
     * a genuinely broken checker re-earns the claim over the next three checks.
     */
    fun resetCheckerHealth(reason: String) {
        if (_checkerHealth.value != CheckerHealth.State.Healthy) {
            Log.i(TAG, "Dropping this process's checker-health claim ($reason)")
        }
        _checkerHealth.value = CheckerHealth.reset(_checkerHealth.value).state
        // Unconditional: the notification is the thing the user actually sees, and
        // a process that no longer holds the claim must not leave it on screen.
        alerts.cancelCheckerHealth()
    }

    /**
     * [resetCheckerHealth], and the persisted evidence with it.
     *
     * For the events that change *how* checks run — a reboot, a settings write that
     * re-arms the scheduler — where a streak accumulated under the old arrangement
     * says nothing about the new one. Deliberately **not** used at process start:
     * the evidence surviving a background wake is the entire reason it is persisted
     * (see [CheckerStreak]), and only the claim is process-scoped.
     */
    suspend fun clearCheckerHealth(reason: String) {
        resetCheckerHealth(reason)
        store.updateCheckerStreak { CheckerStreak.Empty }
    }

    /** A monitor was deleted or disabled — it can no longer support a crash claim. */
    suspend fun forgetMonitor(monitorId: String) {
        val outcome = CheckerHealth.forget(_checkerHealth.value, monitorId)
        _checkerHealth.value = outcome.state
        if (outcome.action == CheckerHealth.Action.CLEAR) alerts.cancelCheckerHealth()
        store.updateCheckerStreak { streak ->
            if (monitorId !in streak.affectedMonitorIds) {
                streak
            } else {
                val remaining = streak.affectedMonitorIds - monitorId
                if (remaining.isEmpty()) {
                    CheckerStreak.Empty
                } else {
                    streak.copy(affectedMonitorIds = remaining)
                }
            }
        }
    }

    /**
     * Retires a crash claim whose newest supporting error has aged out.
     *
     * Called from the reconciliation tick rather than from a check, because the
     * situation this covers is precisely "no check is completing" — waiting for a
     * successful one to clear the notification could mean waiting forever.
     */
    private fun expireCheckerHealth() {
        val outcome = CheckerHealth.expireIfStale(_checkerHealth.value, nowMs())
        if (outcome.action == CheckerHealth.Action.NONE) return
        Log.i(TAG, "Checker crash claim expired; no supporting error in the last hour")
        _checkerHealth.value = outcome.state
        alerts.cancelCheckerHealth()
    }

    /**
     * Re-alerts every urgent outage whose repeat gap has elapsed, *without*
     * running a network check. Driven by [me.river.pulse.data.work.PulseMonitorService]
     * so the nag keeps its cadence even when a monitor's own interval is hours.
     *
     * @return how many monitors were re-alerted
     */
    suspend fun tickUrgent(): Int {
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        val minute = minuteOfDay()
        // Reconciliation runs offline on purpose: it only ever *cancels*
        // notifications nothing can justify, which stays correct with no network
        // and is the one path that can clear an un-dismissable urgent orphan.
        reconcileNotifications(snapshot)
        expireCheckerHealth()

        // The re-nag, however, is an assertion that the outage is still
        // happening — and offline we have not checked, so we do not know. This is
        // the second spam source: with no checks running at all, a monitor that
        // was down when signal dropped would keep shouting every few minutes.
        // The standing notification is left alone; only the repeat pauses.
        if (!isOnline()) return 0

        var fired = 0
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id] ?: continue
            if (!monitor.urgent || !runtime.urgentState.nagging) continue
            val policy = if (monitor.useGlobalAlerts) snapshot.settings.defaultAlert else monitor.alert
            val muted = runtime.mutedUntil > now
            val outcome = UrgentAlerts.evaluate(
                previous = runtime.urgentState,
                eligible = urgentEligible(monitor, policy, snapshot.settings, runtime, muted, minute),
                down = runtime.health == Health.DOWN,
                nowMs = now,
                repeatMinutes = monitor.urgentRepeatMinutes,
            )
            if (outcome.action == UrgentAlerts.Action.NONE) continue

            // A repeat asserts "this is *still* down", and a page is a much
            // stronger claim than a row in the shade. Re-check first when the last
            // verdict is older than the repeat gap: with strict mode off no checks
            // are running between ticks, so the old code could page three times off
            // one observation a quarter of an hour stale — and page about a monitor
            // that had already come back.
            if (outcome.action == UrgentAlerts.Action.REPEAT && staleEvidence(monitor, runtime, now)) {
                Log.i(TAG, "Re-checking ${monitor.displayName} before paging again")
                val fresh = runCatchingCancellable { run(monitor.id, force = true) }.getOrNull()
                if (fresh == null || fresh.ok) continue
                // `run` has already folded the failure through this same machine,
                // paged if it was due, and persisted. Nothing left to do here.
                fired++
                continue
            }
            val mutation = applyUrgent(monitor, outcome, lastResultFor(runtime), policy, runtime)
            // `applyUrgent` has already posted and vibrated. Losing this write
            // would drop the repeat cooldown it just earned, so the next tick — or
            // the next process to start the service — would nag again immediately
            // instead of after `urgentRepeatMinutes`.
            withContext(NonCancellable) {
                store.updateRuntime(monitor.id) {
                    it.withUrgentState(outcome.state).let(mutation)
                }
            }
            if (outcome.action == UrgentAlerts.Action.REPEAT) fired++
        }
        if (fired > 0) onStateChanged?.invoke()
        return fired
    }

    /**
     * Cancels every alert notification that no monitor can justify.
     *
     * Reconciles against what is *on screen* rather than against what state
     * says should be on screen, because the two can diverge in ways state
     * cannot see:
     *
     *  - a monitor was deleted while it had notifications standing, so no
     *    per-monitor loop will ever visit it again;
     *  - an older build left something behind;
     *  - overlapping checks posted from a stale read (fixed separately by the
     *    per-monitor lock, but this is the backstop).
     *
     * That matters most for urgent, which is `ongoing` — an orphan there is not
     * merely wrong, it is un-dismissable.
     *
     * A monitor may legitimately hold: the urgent id while nagging, the
     * degraded id while a latency alert stands, and the down id while it is
     * down *or* has just recovered — recovery reuses the down id, and eating
     * someone's all-clear a minute after it arrives would be its own bug.
     */
    private fun reconcileNotifications(snapshot: me.river.pulse.data.PulseSnapshot) {
        val active = alerts.activeAlertIds()
        if (active.isEmpty()) return
        val legitimate = mutableSetOf<Int>()
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id] ?: continue
            if (monitor.urgent && runtime.urgentState.nagging) {
                legitimate += alerts.urgentIdOf(monitor.id)
            }
            if (runtime.degradedAlerting) {
                legitimate += alerts.degradedIdOf(monitor.id)
            }
            // `consecutiveSuccesses <= 1` keeps a just-posted recovery notice.
            if (runtime.alerting || runtime.health == Health.DOWN || runtime.consecutiveSuccesses <= 1) {
                legitimate += alerts.downIdOf(monitor.id)
            }
        }
        for (id in active) {
            if (id !in legitimate) alerts.cancelById(id)
        }
    }

    /**
     * Whether the newest verdict is too old to justify another page.
     *
     * "Too old" is one repeat gap: if the monitor is being checked at least as
     * often as it is paged, the evidence behind every page is fresh by
     * construction and this never fires.
     */
    private fun staleEvidence(monitor: Monitor, runtime: MonitorRuntime, nowMs: Long): Boolean {
        if (runtime.lastCheckedAt <= 0L) return true
        val gap = monitor.urgentRepeatMinutes.coerceAtLeast(1) * 60_000L
        return nowMs - runtime.lastCheckedAt >= gap
    }

    /** Re-hydrates enough of the last failure for the page to describe it. */
    fun pageEvidence(runtime: MonitorRuntime): CheckResult = lastResultFor(runtime)

    /** Re-hydrates enough of the last failure to re-post a useful notification. */
    private fun lastResultFor(runtime: MonitorRuntime): CheckResult = CheckResult(
        ok = false,
        latencyMs = runtime.lastLatencyMs,
        statusCode = runtime.lastCode,
        failureKind = FailureKind.UNKNOWN,
        message = runtime.lastMessage.ifBlank { "Still down" },
        detail = runtime.lastDetail,
        at = nowMs(),
    )

    /**
     * In-app acknowledgement. Same code path as the notification action, and
     * behind the same per-monitor lock as [run] so a check in flight cannot
     * resurrect the state this just cleared.
     */
    suspend fun acknowledgeUrgent(monitorId: String) {
        locks.getOrPut(monitorId) { Mutex() }.withLock {
            val snapshot = store.currentSnapshot()
            val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return
            val runtime = snapshot.runtimes[monitorId] ?: return
            // Cancel first and unconditionally: if the notification is already
            // gone this is a no-op, and if the state was somehow out of step
            // the user still gets the silence they just asked for.
            alerts.cancelUrgent(monitor.id)
            val outcome = UrgentAlerts.acknowledge(runtime.urgentState)
            if (outcome.action == UrgentAlerts.Action.NONE) return
            // The notification is already gone. If this write is lost — the user
            // presses Back and takes `viewModelScope` with it — the store still
            // says `active = true, acknowledged = false`, and the service re-posts
            // the alarm the user just dismissed.
            withContext(NonCancellable) {
                store.updateRuntime(monitorId) { it.withUrgentState(outcome.state) }
            }
        }
        onStateChanged?.invoke()
    }

    /**
     * Runs every enabled monitor whose interval has elapsed. Returns how many ran.
     *
     * Returns 0 immediately while offline. `force` does not override that: the
     * flag exists so a human can say "check now", not "check even though it
     * cannot possibly succeed".
     */
    suspend fun runAllDue(force: Boolean = false): Int {
        if (!isOnline()) {
            Log.i(TAG, "Offline — check pass skipped")
            return 0
        }
        // Once, before the loop: the whole pass shares one reading of the control.
        refreshReference()
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        var ran = 0
        for (monitor in snapshot.monitors) {
            if (!monitor.enabled) continue
            if (!force && !isDue(monitor, snapshot.runtimes[monitor.id], now)) continue
            run(monitor.id, force = force)
            ran++
        }
        return ran
    }

    /**
     * Whether a monitor's interval has elapsed.
     *
     * Public because the WorkManager path needs it: periodic work fires on
     * Android's schedule rather than on the monitor's, so the worker asks this
     * before checking. Without the gate, a monitor on a two-hour interval sharing
     * a wake-up with the repair sweep would be checked twice — visible on a real
     * device as three samples one second apart.
     *
     * Prefer the [Monitor]/[MonitorRuntime] overload when the caller already holds
     * a snapshot; this one re-reads the store, and decoding it is not free.
     */
    fun isDue(monitor: Monitor, runtime: MonitorRuntime?, nowMs: Long = nowMs()): Boolean {
        if (!monitor.enabled) return false
        return DueCheck.isDue(monitor.intervalMinutes, runtime?.lastCheckedAt ?: 0L, nowMs)
    }

    suspend fun isDue(monitorId: String, nowMs: Long = nowMs()): Boolean {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return false
        return isDue(monitor, snapshot.runtimes[monitorId], nowMs)
    }

    /**
     * Millis until the earliest of: the next monitor becoming due, or the next
     * urgent repeat. Used by the foreground service to sleep exactly as long as
     * it can rather than polling on a fixed tick.
     */
    suspend fun nextWakeDelayMs(): Long {
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        var soonest = Long.MAX_VALUE
        for (monitor in snapshot.monitors) {
            val runtime = snapshot.runtimes[monitor.id]
            if (monitor.enabled) {
                val due = if (runtime == null || runtime.lastCheckedAt <= 0L) {
                    0L
                } else {
                    runtime.lastCheckedAt + monitor.intervalMinutes * 60_000L - DUE_SLACK_MS - now
                }
                soonest = minOf(soonest, due)
            }
            if (monitor.urgent && runtime != null) {
                UrgentAlerts.nextRepeatDelayMs(runtime.urgentState, now, monitor.urgentRepeatMinutes)
                    ?.let { soonest = minOf(soonest, it) }
            }
        }
        if (soonest == Long.MAX_VALUE) return MAX_IDLE_MS
        return soonest.coerceIn(MIN_TICK_MS, MAX_IDLE_MS)
    }

    suspend fun mute(monitorId: String, durationMs: Long) {
        locks.getOrPut(monitorId) { Mutex() }.withLock {
            // One write, not two. Muting used to persist `mutedUntil`, cancel,
            // then persist `urgentActive = false` — and a check landing between
            // those two writes could re-post the notification after the cancel.
            // Both halves or neither. A lost write leaves the user's "Mute 1h"
            // silently undone with the urgent alarm still nagging every minute for
            // the hour they asked to be left alone; a lost *cancel* leaves
            // notifications up that state says should be gone.
            withContext(NonCancellable) {
                store.updateRuntime(monitorId) {
                    // Muting is "stop shouting", not "I've triaged this": the urgent
                    // loop is suspended by the eligibility gate and resumes on unmute.
                    it.copy(mutedUntil = nowMs() + durationMs, urgentActive = false)
                }
                alerts.cancel(monitorId)
                alerts.cancelDegraded(monitorId)
                alerts.cancelUrgent(monitorId)
            }
        }
        onStateChanged?.invoke()
    }

    suspend fun unmute(monitorId: String) {
        store.updateRuntime(monitorId) { it.copy(mutedUntil = 0L) }
        onStateChanged?.invoke()
    }

    companion object {
        private const val TAG = "CheckEngine"
        private const val DUE_SLACK_MS = DueCheck.SLACK_MS

        /** Never wake more often than this, however tight the configured cadence. */
        const val MIN_TICK_MS = 15_000L

        /** Wake at least this often so a store edit made elsewhere is picked up. */
        const val MAX_IDLE_MS = 60_000L

        /** Shortest gap between two timings of the latency reference. */
        const val REFERENCE_MIN_INTERVAL_MS = 45_000L
    }
}
