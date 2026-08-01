package me.river.pulse.data.check

import android.util.Log
import me.river.pulse.data.PulseStore
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.domain.AlertDecider
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.UrgentAlerts
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The heart of the app: runs a check, folds the outcome into persisted state,
 * and decides whether the human deserves to be interrupted.
 *
 * Three independent alert tracks run off one check:
 *  - **down** — the original outage/recovery escalation ([AlertDecider.decide])
 *  - **degraded** — latency-SLO breaches ([AlertDecider.decideDegraded])
 *  - **urgent** — the nag-until-acknowledged loop ([UrgentAlerts])
 *
 * They deliberately don't share cooldowns or notification ids, so silencing one
 * never silences another.
 */
class CheckEngine(
    private val store: PulseStore,
    private val http: HttpChecker,
    private val element: ElementChecker,
    private val alerts: AlertCenter,
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
     * Full pipeline for one monitor: check → persist → maybe alert.
     *
     * Returns null when the monitor no longer exists, or when the device is
     * offline and the check was skipped — in both cases nothing was recorded.
     */
    suspend fun run(monitorId: String): CheckResult? =
        locks.getOrPut(monitorId) { Mutex() }.withLock { runLocked(monitorId) }

    private suspend fun runLocked(monitorId: String): CheckResult? {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return null
        val before = snapshot.runtimes[monitorId] ?: MonitorRuntime()
        val settings = snapshot.settings

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
        } catch (error: Throwable) {
            Log.e(TAG, "Check crashed for ${monitor.displayName}", error)
            CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.UNKNOWN,
                message = "Checker crashed",
                detail = error.message ?: error::class.java.simpleName,
                at = nowMs(),
            )
        } finally {
            store.markChecking(monitorId, false)
        }

        val slo = monitor.sloMs(settings)
        val after = AlertDecider.advance(
            previous = before,
            result = result,
            historyDepth = settings.historyDepth,
            degradedAboveMs = if (slo > 0) slo.toLong() else Long.MAX_VALUE,
        )
        val degraded = AlertDecider.isDegraded(result.ok, result.latencyMs, slo.toLong())
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
        applyUrgent(monitor, urgentOutcome, result, policy)

        store.updateRuntime(monitorId) {
            after
                .copy(
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
        }
        onStateChanged?.invoke()
        return result
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

    private fun applyUrgent(
        monitor: Monitor,
        outcome: UrgentAlerts.Outcome,
        result: CheckResult?,
        policy: AlertPolicy,
    ) {
        when (outcome.action) {
            UrgentAlerts.Action.START, UrgentAlerts.Action.REPEAT -> alerts.notifyUrgent(
                monitor = monitor,
                result = result ?: CheckResult(
                    ok = false,
                    latencyMs = 0,
                    failureKind = FailureKind.UNKNOWN,
                    message = "Still down",
                    at = nowMs(),
                ),
                policy = policy,
                repeatCount = if (outcome.action == UrgentAlerts.Action.REPEAT) 1 else 0,
            )

            UrgentAlerts.Action.CLEAR -> alerts.cancelUrgent(monitor.id)
            UrgentAlerts.Action.NONE -> Unit
        }
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
            applyUrgent(monitor, outcome, lastResultFor(runtime), policy)
            store.updateRuntime(monitor.id) { it.withUrgentState(outcome.state) }
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
            store.updateRuntime(monitorId) { it.withUrgentState(outcome.state) }
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
        val snapshot = store.currentSnapshot()
        val now = nowMs()
        var ran = 0
        for (monitor in snapshot.monitors) {
            if (!monitor.enabled) continue
            val runtime = snapshot.runtimes[monitor.id]
            val due = force || runtime == null || runtime.lastCheckedAt <= 0L ||
                now - runtime.lastCheckedAt >= monitor.intervalMinutes * 60_000L - DUE_SLACK_MS
            if (!due) continue
            run(monitor.id)
            ran++
        }
        return ran
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
            store.updateRuntime(monitorId) {
                // Muting is "stop shouting", not "I've triaged this": the urgent
                // loop is suspended by the eligibility gate and resumes on unmute.
                it.copy(mutedUntil = nowMs() + durationMs, urgentActive = false)
            }
            alerts.cancel(monitorId)
            alerts.cancelDegraded(monitorId)
            alerts.cancelUrgent(monitorId)
        }
        onStateChanged?.invoke()
    }

    suspend fun unmute(monitorId: String) {
        store.updateRuntime(monitorId) { it.copy(mutedUntil = 0L) }
        onStateChanged?.invoke()
    }

    companion object {
        private const val TAG = "CheckEngine"
        private const val DUE_SLACK_MS = 30_000L

        /** Never wake more often than this, however tight the configured cadence. */
        const val MIN_TICK_MS = 15_000L

        /** Wake at least this often so a store edit made elsewhere is picked up. */
        const val MAX_IDLE_MS = 60_000L
    }
}
