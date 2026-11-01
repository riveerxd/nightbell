package me.river.nightbell

import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.QuickRetry
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFacts
import me.river.nightbell.domain.WebhookRouting
import me.river.nightbell.domain.WebhookState
import me.river.nightbell.domain.WebhookTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 21: "number of successful responses before marking it as UP". A flapping
 * host used to post "back up" on its first good answer and page again on the
 * next bad one.
 */
class RecoveryThresholdTest {

    private val minute = 60_000L
    private val policy = AlertPolicy(recoveryThreshold = 3, cooldownMinutes = 0)

    private fun decide(ok: Boolean, successes: Int, alerting: Boolean = true, policy: AlertPolicy = this.policy) =
        AlertDecider.decide(
            wasAlerting = alerting,
            ok = ok,
            consecutiveFailures = if (ok) 0 else 1,
            lastAlertAt = 0L,
            policy = policy,
            masterEnabled = true,
            nowMs = 10 * minute,
            minuteOfDay = 12 * 60,
            consecutiveSuccesses = successes,
        )

    @Test
    fun `the outage stands until the threshold of passes`() {
        assertEquals(AlertDecider.Suppression.RECOVERY_PENDING, decide(ok = true, successes = 1).suppression)
        assertEquals(AlertDecider.Suppression.RECOVERY_PENDING, decide(ok = true, successes = 2).suppression)
        assertEquals(AlertDecider.Kind.RECOVERY, decide(ok = true, successes = 3).kind)
    }

    @Test
    fun `one is the old behaviour exactly`() {
        assertEquals(AlertDecider.Kind.RECOVERY, decide(ok = true, successes = 1, policy = AlertPolicy()).kind)
    }

    @Test
    fun `callers that predate the threshold are not held`() {
        val decision = AlertDecider.decide(
            wasAlerting = true, ok = true, consecutiveFailures = 0, lastAlertAt = 0L,
            policy = policy, masterEnabled = true, nowMs = 0L, minuteOfDay = 0,
        )
        assertEquals(AlertDecider.Kind.RECOVERY, decision.kind)
    }

    @Test
    fun `with all-clears off the outage still has to be over first`() {
        val quietRecovery = policy.copy(alertOnRecovery = false)
        assertEquals(AlertDecider.Suppression.RECOVERY_PENDING, decide(ok = true, successes = 1, policy = quietRecovery).suppression)
        assertEquals(AlertDecider.Suppression.RECOVERY_ALERTS_OFF, decide(ok = true, successes = 3, policy = quietRecovery).suppression)
    }

    @Test
    fun `nothing to recover from is no transition`() {
        assertEquals(AlertDecider.Suppression.NO_TRANSITION, decide(ok = true, successes = 1, alerting = false).suppression)
    }

    @Test
    fun `alerts off for the monitor end the outage rather than hold it`() {
        assertEquals(
            AlertDecider.Suppression.POLICY_DISABLED,
            decide(ok = true, successes = 1, policy = policy.copy(enabled = false)).suppression,
        )
    }

    @Test
    fun `the stored runtime says when it is recovering`() {
        val alerting = MonitorRuntime(alerting = true, health = Health.UP, consecutiveSuccesses = 1)
        assertTrue(AlertDecider.recovering(alerting, policy))
        assertFalse(AlertDecider.recovering(alerting.copy(consecutiveSuccesses = 3), policy))
        assertFalse(AlertDecider.recovering(alerting.copy(alerting = false), policy))
        assertFalse(AlertDecider.recovering(alerting.copy(health = Health.DOWN, consecutiveSuccesses = 0), policy))
        assertFalse(AlertDecider.recovering(alerting, AlertPolicy()))
    }

    @Test
    fun `advance counts the passes the decider reads`() {
        var runtime = MonitorRuntime(alerting = true, consecutiveFailures = 2)
        runtime = AlertDecider.advance(runtime, CheckResult(ok = true, latencyMs = 5, at = 1), historyDepth = 10)
        assertEquals(1, runtime.consecutiveSuccesses)
        runtime = AlertDecider.advance(runtime, CheckResult(ok = true, latencyMs = 5, at = 2), historyDepth = 10)
        assertEquals(2, runtime.consecutiveSuccesses)
        runtime = AlertDecider.advance(runtime, CheckResult(ok = false, latencyMs = 0, at = 3), historyDepth = 10)
        assertEquals(0, runtime.consecutiveSuccesses)
    }

    // ---- quick retry confirms a recovery as it confirms an outage -------------

    private val monitor = Monitor(id = "m", url = "https://example.test")

    private fun recovering(successes: Int, at: Long = 5 * minute) = MonitorRuntime(
        alerting = true,
        health = Health.UP,
        consecutiveSuccesses = successes,
        lastCheckedAt = at,
        samples = listOf(Sample(at = at - minute, ok = false, latencyMs = 0), Sample(at = at, ok = true, latencyMs = 5)),
    )

    @Test
    fun `a recovering monitor is re-checked thirty seconds on`() {
        assertTrue(QuickRetry.pending(monitor, policy, recovering(1), silenced = false))
        assertTrue(QuickRetry.pending(monitor, policy, recovering(2), silenced = false))
        assertFalse("the count is met", QuickRetry.pending(monitor, policy, recovering(3), silenced = false))
        assertFalse("silenced", QuickRetry.pending(monitor, policy, recovering(1), silenced = true))
        assertFalse("switched off", QuickRetry.pending(monitor, policy.copy(quickRetry = false), recovering(1), silenced = false))
        assertTrue(QuickRetry.due(monitor, policy, recovering(1, at = 0), silenced = false, nowMs = QuickRetry.GAP_MS))
    }

    @Test
    fun `an attempt with no verdict does not start the loop`() {
        val stamped = recovering(1).copy(lastCheckedAt = 6 * minute)
        assertFalse(QuickRetry.pending(monitor, policy, stamped, silenced = false))
    }

    @Test
    fun `the card line counts good checks`() {
        val streak = QuickRetry.streak(monitor, policy, recovering(1), silenced = false)!!
        assertTrue(streak.recovering)
        assertEquals("1 of 3 good checks before the all-clear", streak.line)
        assertNull(QuickRetry.streak(monitor, AlertPolicy(), recovering(1), silenced = false))
    }

    // ---- a channel hears the all-clear when the phone does ---------------------

    @Test
    fun `webhooks hold the outage and report its whole length`() {
        val target = WebhookTarget(id = "t", url = "https://hooks.example.com/t")
        var state = WebhookState()
        var failures = 0
        var successes = 0
        val sent = mutableListOf<WebhookFacts>()
        fun check(ok: Boolean, at: Long): List<WebhookEvent> {
            if (ok) { successes++; failures = 0 } else { failures++; successes = 0 }
            val outcome = WebhookRouting.decide(
                targets = listOf(target),
                state = state,
                monitorId = "m",
                groups = emptyList(),
                check = WebhookRouting.Check(ok = ok, consecutiveFailures = failures, degraded = false, at = at, consecutiveSuccesses = successes),
                phone = WebhookRouting.Phone(policy, masterEnabled = true, muted = false, minuteOfDay = 12 * 60),
                base = WebhookFacts(event = WebhookEvent.DOWN, monitorId = "m", at = at),
            )
            state = state.copy(tracks = state.tracks + outcome.tracks)
            sent += outcome.sends.map { it.second }
            return outcome.sends.map { it.second.event }
        }
        assertEquals(listOf(WebhookEvent.DOWN), check(ok = false, at = 10 * minute))
        assertEquals(emptyList<WebhookEvent>(), check(ok = true, at = 11 * minute))
        assertEquals("a flap inside the count is still the same outage", emptyList<WebhookEvent>(), check(ok = false, at = 12 * minute))
        assertEquals(emptyList<WebhookEvent>(), check(ok = true, at = 13 * minute))
        assertEquals(emptyList<WebhookEvent>(), check(ok = true, at = 14 * minute))
        assertEquals(listOf(WebhookEvent.RECOVERED), check(ok = true, at = 15 * minute))
        assertEquals(5 * minute, sent.last().downForMs)
        assertEquals(10 * minute, sent.last().downSinceAt)
    }
}
