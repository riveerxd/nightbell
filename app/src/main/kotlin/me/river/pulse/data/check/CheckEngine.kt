package me.river.pulse.data.check

import android.util.Log
import me.river.pulse.data.PulseStore
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.domain.AlertDecider
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import java.util.Calendar

/**
 * The heart of the app: runs a check, folds the outcome into persisted state,
 * and decides whether the human deserves to be interrupted.
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

    /** Runs the check without touching persisted state — used by "Test now". */
    suspend fun dryRun(monitor: Monitor): CheckResult = when (monitor.kind) {
        MonitorKind.WEBSITE_ELEMENT -> element.check(monitor)
        else -> http.check(monitor)
    }

    /**
     * Full pipeline for one monitor: check → persist → maybe alert.
     * Returns null when the monitor no longer exists.
     */
    suspend fun run(monitorId: String): CheckResult? {
        val snapshot = store.currentSnapshot()
        val monitor = snapshot.monitors.firstOrNull { it.id == monitorId } ?: return null
        val before = snapshot.runtimes[monitorId] ?: me.river.pulse.domain.MonitorRuntime()
        val settings = snapshot.settings

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

        val after = AlertDecider.advance(before, result, settings.historyDepth)
        val policy: AlertPolicy = if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert
        val muted = before.mutedUntil > nowMs()

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
                minuteOfDay = minuteOfDay(),
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

            AlertDecider.Kind.NONE -> Unit
        }

        val alertingNow = when (decision.kind) {
            AlertDecider.Kind.DOWN, AlertDecider.Kind.REPEAT -> true
            AlertDecider.Kind.RECOVERY -> false
            AlertDecider.Kind.NONE -> if (result.ok) false else before.alerting
        }
        // Clear the stale "down" notification — unless we just replaced it with a
        // recovery notification, which reuses the same id and would be wiped out.
        if (result.ok && before.alerting && decision.kind != AlertDecider.Kind.RECOVERY) {
            alerts.cancel(monitor.id)
        }

        store.updateRuntime(monitorId) {
            after.copy(
                alerting = alertingNow,
                lastAlertAt = if (decision.shouldNotify) result.at else before.lastAlertAt,
                mutedUntil = before.mutedUntil,
                health = if (!monitor.enabled) Health.PAUSED else after.health,
            )
        }
        return result
    }

    /** Runs every enabled monitor whose interval has elapsed. Returns how many ran. */
    suspend fun runAllDue(force: Boolean = false): Int {
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

    suspend fun mute(monitorId: String, durationMs: Long) {
        store.updateRuntime(monitorId) { it.copy(mutedUntil = nowMs() + durationMs) }
        alerts.cancel(monitorId)
    }

    suspend fun unmute(monitorId: String) {
        store.updateRuntime(monitorId) { it.copy(mutedUntil = 0L) }
    }

    companion object {
        private const val TAG = "CheckEngine"
        private const val DUE_SLACK_MS = 30_000L
    }
}
