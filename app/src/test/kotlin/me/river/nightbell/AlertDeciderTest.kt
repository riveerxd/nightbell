package me.river.nightbell

import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MonitorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDeciderTest {

    private val now = 1_700_000_000_000L
    private val minute = fun(hour: Int, min: Int) = hour * 60 + min

    private fun decide(
        wasAlerting: Boolean = false,
        ok: Boolean = false,
        failures: Int = 1,
        lastAlertAt: Long = 0L,
        policy: AlertPolicy = AlertPolicy(),
        master: Boolean = true,
        at: Long = now,
        minuteOfDay: Int = 12 * 60,
    ) = AlertDecider.decide(wasAlerting, ok, failures, lastAlertAt, policy, master, at, minuteOfDay)

    @Test
    fun `first failure raises a down alert`() {
        val decision = decide()
        assertEquals(AlertDecider.Kind.DOWN, decision.kind)
        assertTrue(decision.shouldNotify)
        assertFalse(decision.forceSilent)
    }

    @Test
    fun `master mute silences everything`() {
        val decision = decide(master = false)
        assertEquals(AlertDecider.Kind.NONE, decision.kind)
        assertEquals(AlertDecider.Suppression.MASTER_MUTED, decision.suppression)
    }

    @Test
    fun `per monitor disable silences everything`() {
        val decision = decide(policy = AlertPolicy(enabled = false))
        assertEquals(AlertDecider.Suppression.POLICY_DISABLED, decision.suppression)
    }

    @Test
    fun `threshold suppresses until the streak is long enough`() {
        val policy = AlertPolicy(failureThreshold = 3)
        assertEquals(AlertDecider.Suppression.BELOW_THRESHOLD, decide(failures = 1, policy = policy).suppression)
        assertEquals(AlertDecider.Suppression.BELOW_THRESHOLD, decide(failures = 2, policy = policy).suppression)
        assertEquals(AlertDecider.Kind.DOWN, decide(failures = 3, policy = policy).kind)
    }

    @Test
    fun `cooldown blocks a second down alert inside the window`() {
        val policy = AlertPolicy(cooldownMinutes = 10)
        val recent = now - 4 * 60_000
        assertEquals(AlertDecider.Suppression.COOLDOWN, decide(lastAlertAt = recent, policy = policy).suppression)

        val old = now - 30 * 60_000
        assertEquals(AlertDecider.Kind.DOWN, decide(lastAlertAt = old, policy = policy).kind)
    }

    @Test
    fun `already alerting stays quiet unless repeats are on`() {
        val quiet = decide(wasAlerting = true, lastAlertAt = now - 60 * 60_000)
        assertEquals(AlertDecider.Kind.NONE, quiet.kind)

        val repeating = AlertPolicy(repeatEnabled = true, repeatEveryMinutes = 30)
        val tooSoon = decide(wasAlerting = true, lastAlertAt = now - 10 * 60_000, policy = repeating)
        assertEquals(AlertDecider.Suppression.COOLDOWN, tooSoon.suppression)

        val due = decide(wasAlerting = true, lastAlertAt = now - 31 * 60_000, policy = repeating)
        assertEquals(AlertDecider.Kind.REPEAT, due.kind)
    }

    @Test
    fun `recovery only fires when we were previously alerting`() {
        assertEquals(AlertDecider.Kind.NONE, decide(ok = true, wasAlerting = false).kind)
        assertEquals(AlertDecider.Kind.RECOVERY, decide(ok = true, wasAlerting = true).kind)
    }

    @Test
    fun `recovery ignores cooldown but respects the recovery toggle`() {
        val justAlerted = decide(ok = true, wasAlerting = true, lastAlertAt = now - 1_000)
        assertEquals(AlertDecider.Kind.RECOVERY, justAlerted.kind)

        val disabled = decide(
            ok = true,
            wasAlerting = true,
            policy = AlertPolicy(alertOnRecovery = false),
        )
        assertEquals(AlertDecider.Suppression.RECOVERY_ALERTS_OFF, disabled.suppression)
    }

    @Test
    fun `down alerts can be turned off independently`() {
        val decision = decide(policy = AlertPolicy(alertOnDown = false))
        assertEquals(AlertDecider.Suppression.DOWN_ALERTS_OFF, decision.suppression)
    }

    @Test
    fun `quiet hours suppress by default and go silent when bypass is on`() {
        val policy = AlertPolicy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute = 7 * 60,
        )
        val atNight = decide(policy = policy, minuteOfDay = minute(23, 30))
        assertEquals(AlertDecider.Suppression.QUIET_HOURS, atNight.suppression)

        val bypass = policy.copy(criticalBypassesQuiet = true)
        val silent = decide(policy = bypass, minuteOfDay = minute(23, 30))
        assertEquals(AlertDecider.Kind.DOWN, silent.kind)
        assertTrue(silent.forceSilent)

        val daytime = decide(policy = policy, minuteOfDay = minute(12, 0))
        assertEquals(AlertDecider.Kind.DOWN, daytime.kind)
    }

    @Test
    fun `quiet hour window wraps past midnight`() {
        assertTrue(AlertDecider.inQuietHours(minute(23, 0), 22 * 60, 7 * 60))
        assertTrue(AlertDecider.inQuietHours(minute(3, 0), 22 * 60, 7 * 60))
        assertFalse(AlertDecider.inQuietHours(minute(8, 0), 22 * 60, 7 * 60))
        assertTrue(AlertDecider.inQuietHours(minute(13, 0), 9 * 60, 17 * 60))
        assertFalse(AlertDecider.inQuietHours(minute(18, 0), 9 * 60, 17 * 60))
        assertFalse(AlertDecider.inQuietHours(minute(12, 0), 60, 60))
    }

    // ---- runtime folding ----------------------------------------------------

    @Test
    fun `advance tracks streaks and caps history`() {
        var runtime = MonitorRuntime()
        repeat(5) { index ->
            runtime = AlertDecider.advance(
                runtime,
                CheckResult(ok = false, latencyMs = 10, statusCode = 500, at = now + index),
                historyDepth = 3,
            )
        }
        assertEquals(5, runtime.consecutiveFailures)
        assertEquals(0, runtime.consecutiveSuccesses)
        assertEquals(Health.DOWN, runtime.health)
        assertEquals(3, runtime.samples.size)

        runtime = AlertDecider.advance(
            runtime,
            CheckResult(ok = true, latencyMs = 120, statusCode = 200, at = now + 10),
            historyDepth = 3,
        )
        assertEquals(0, runtime.consecutiveFailures)
        assertEquals(1, runtime.consecutiveSuccesses)
        assertEquals(Health.UP, runtime.health)
    }

    @Test
    fun `slow but successful checks are degraded not down`() {
        val runtime = AlertDecider.advance(
            MonitorRuntime(),
            CheckResult(ok = true, latencyMs = 9_000, statusCode = 200, at = now),
            historyDepth = 10,
            degradedAboveMs = 2_500,
        )
        assertEquals(Health.DEGRADED, runtime.health)
    }

    @Test
    fun `uptime and latency stats are derived from history`() {
        var runtime = MonitorRuntime()
        val outcomes = listOf(true, true, false, true, true, true, true, true, true, true)
        outcomes.forEachIndexed { index, ok ->
            runtime = AlertDecider.advance(
                runtime,
                CheckResult(
                    ok = ok,
                    latencyMs = (index + 1) * 10L,
                    statusCode = if (ok) 200 else 500,
                    failureKind = if (ok) FailureKind.NONE else FailureKind.STATUS,
                    at = now + index,
                ),
                historyDepth = 60,
            )
        }
        assertEquals(90f, runtime.uptimePercent, 0.01f)
        assertTrue(runtime.averageLatencyMs > 0)
        assertTrue(runtime.p95LatencyMs >= runtime.averageLatencyMs)
    }
}
