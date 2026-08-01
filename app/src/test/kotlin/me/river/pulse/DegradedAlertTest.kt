package me.river.pulse

import me.river.pulse.domain.AlertDecider
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The latency-SLO track: when a successful-but-slow response becomes DEGRADED,
 * and when that is worth an alert of its own.
 */
class DegradedAlertTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private fun minuteOf(hour: Int, min: Int) = hour * 60 + min

    private val alerting = AlertPolicy(alertOnDegraded = true, degradedCooldownMinutes = 30)

    private fun decide(
        wasDegradedAlerting: Boolean = false,
        ok: Boolean = true,
        degraded: Boolean = true,
        lastDegradedAlertAt: Long = 0L,
        policy: AlertPolicy = alerting,
        master: Boolean = true,
        at: Long = now,
        minuteOfDay: Int = 12 * 60,
    ) = AlertDecider.decideDegraded(
        wasDegradedAlerting, ok, degraded, lastDegradedAlertAt, policy, master, at, minuteOfDay,
    )

    // ---- the threshold itself -----------------------------------------------

    @Test
    fun `isDegraded needs a successful check over the budget`() {
        assertTrue(AlertDecider.isDegraded(ok = true, latencyMs = 3_000, sloMs = 2_500))
        assertFalse(AlertDecider.isDegraded(ok = true, latencyMs = 2_500, sloMs = 2_500))
        assertFalse(AlertDecider.isDegraded(ok = true, latencyMs = 100, sloMs = 2_500))
        // A failure is DOWN, never DEGRADED, however slow it was.
        assertFalse(AlertDecider.isDegraded(ok = false, latencyMs = 90_000, sloMs = 2_500))
        // Budget of zero switches the whole idea off.
        assertFalse(AlertDecider.isDegraded(ok = true, latencyMs = 90_000, sloMs = 0))
    }

    @Test
    fun `advance folds the budget into health`() {
        val slow = AlertDecider.advance(
            MonitorRuntime(),
            CheckResult(ok = true, latencyMs = 4_000, statusCode = 200, at = now),
            historyDepth = 10,
            degradedAboveMs = 2_500,
        )
        assertEquals(Health.DEGRADED, slow.health)

        val fast = AlertDecider.advance(
            MonitorRuntime(),
            CheckResult(ok = true, latencyMs = 400, statusCode = 200, at = now),
            historyDepth = 10,
            degradedAboveMs = 2_500,
        )
        assertEquals(Health.UP, fast.health)

        val off = AlertDecider.advance(
            MonitorRuntime(),
            CheckResult(ok = true, latencyMs = 40_000, statusCode = 200, at = now),
            historyDepth = 10,
            degradedAboveMs = Long.MAX_VALUE,
        )
        assertEquals("no budget means never degraded", Health.UP, off.health)
    }

    @Test
    fun `the per-monitor budget overrides the global one`() {
        val settings = GlobalSettings(defaultLatencySloMs = 2_500)
        assertEquals(2_500, Monitor(id = "a").sloMs(settings))
        assertEquals(800, Monitor(id = "a", latencySloMs = 800).sloMs(settings))
        assertEquals(0, Monitor(id = "a").sloMs(settings.copy(defaultLatencySloMs = 0)))
    }

    // ---- the alert decision -------------------------------------------------

    @Test
    fun `a first breach alerts when latency alerts are on`() {
        val decision = decide()
        assertEquals(AlertDecider.Kind.DEGRADED, decision.kind)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun `latency alerts are off by default`() {
        val decision = decide(policy = AlertPolicy())
        assertEquals(AlertDecider.Kind.NONE, decision.kind)
        assertEquals(AlertDecider.Suppression.DEGRADED_ALERTS_OFF, decision.suppression)
    }

    @Test
    fun `an outage suppresses the latency track entirely`() {
        // Down owns that transition; two notifications for one event is noise.
        val decision = decide(ok = false, degraded = false)
        assertEquals(AlertDecider.Kind.NONE, decision.kind)
        assertEquals(AlertDecider.Suppression.NO_TRANSITION, decision.suppression)
    }

    @Test
    fun `the latency cooldown is independent of the down cooldown`() {
        // A 10-minute down cooldown must not let a degraded alert through early.
        val policy = alerting.copy(cooldownMinutes = 1, degradedCooldownMinutes = 30)
        val tooSoon = decide(policy = policy, lastDegradedAlertAt = now - 5 * minute)
        assertEquals(AlertDecider.Suppression.COOLDOWN, tooSoon.suppression)

        val due = decide(policy = policy, lastDegradedAlertAt = now - 31 * minute)
        assertEquals(AlertDecider.Kind.DEGRADED, due.kind)
    }

    @Test
    fun `a standing degraded alert stays quiet unless repeats are on`() {
        val quiet = decide(wasDegradedAlerting = true, lastDegradedAlertAt = now - 90 * minute)
        assertEquals(AlertDecider.Kind.NONE, quiet.kind)

        val repeating = alerting.copy(degradedRepeatEnabled = true, degradedRepeatEveryMinutes = 60)
        val tooSoon = decide(
            wasDegradedAlerting = true,
            policy = repeating,
            lastDegradedAlertAt = now - 20 * minute,
        )
        assertEquals(AlertDecider.Suppression.COOLDOWN, tooSoon.suppression)

        val due = decide(
            wasDegradedAlerting = true,
            policy = repeating,
            lastDegradedAlertAt = now - 61 * minute,
        )
        assertEquals(AlertDecider.Kind.DEGRADED_REPEAT, due.kind)
    }

    @Test
    fun `dropping back under the budget is a recovery`() {
        val recovered = decide(wasDegradedAlerting = true, degraded = false)
        assertEquals(AlertDecider.Kind.DEGRADED_RECOVERY, recovered.kind)

        val never = decide(wasDegradedAlerting = false, degraded = false)
        assertEquals(AlertDecider.Suppression.NO_TRANSITION, never.suppression)

        val disabled = decide(
            wasDegradedAlerting = true,
            degraded = false,
            policy = alerting.copy(alertOnDegradedRecovery = false),
        )
        assertEquals(AlertDecider.Suppression.DEGRADED_RECOVERY_ALERTS_OFF, disabled.suppression)
    }

    @Test
    fun `master mute and per-monitor disable both win`() {
        assertEquals(AlertDecider.Suppression.MASTER_MUTED, decide(master = false).suppression)
        assertEquals(
            AlertDecider.Suppression.POLICY_DISABLED,
            decide(policy = alerting.copy(enabled = false)).suppression,
        )
    }

    @Test
    fun `quiet hours silence the latency track the same way`() {
        val policy = alerting.copy(
            quietHoursEnabled = true,
            quietStartMinute = 22 * 60,
            quietEndMinute = 7 * 60,
        )
        val night = decide(policy = policy, minuteOfDay = minuteOf(23, 30))
        assertEquals(AlertDecider.Suppression.QUIET_HOURS, night.suppression)

        val bypass = decide(
            policy = policy.copy(criticalBypassesQuiet = true),
            minuteOfDay = minuteOf(23, 30),
        )
        assertEquals(AlertDecider.Kind.DEGRADED, bypass.kind)
        assertTrue(bypass.forceSilent)

        val day = decide(policy = policy, minuteOfDay = minuteOf(12, 0))
        assertEquals(AlertDecider.Kind.DEGRADED, day.kind)
        assertFalse(day.forceSilent)
    }

    @Test
    fun `the down track is untouched by latency settings`() {
        // Regression guard: adding the degraded fields must not change how an
        // outage is decided for a policy that leaves them at their defaults.
        val decision = AlertDecider.decide(
            wasAlerting = false,
            ok = false,
            consecutiveFailures = 1,
            lastAlertAt = 0L,
            policy = AlertPolicy(),
            masterEnabled = true,
            nowMs = now,
            minuteOfDay = 12 * 60,
        )
        assertEquals(AlertDecider.Kind.DOWN, decision.kind)
    }
}
