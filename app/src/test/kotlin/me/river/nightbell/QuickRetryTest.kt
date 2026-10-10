package me.river.nightbell

import kotlinx.serialization.json.Json
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.QuickRetry
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.components.quickRetryLead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 20: a failure threshold above one used to be counted in scheduled checks,
 * so in the background every extra failure cost another fifteen minutes.
 */
class QuickRetryTest {

    private val now = 1_700_000_000_000L
    private val monitor = Monitor(id = "m", url = "https://example.test")
    private val policy = AlertPolicy(failureThreshold = 3)

    /** A runtime whose last attempt was a failing check, [failures] long. */
    private fun failing(failures: Int, at: Long = now) = MonitorRuntime(
        consecutiveFailures = failures,
        lastCheckedAt = at,
        samples = listOf(Sample(at = at, ok = false, latencyMs = 0L)),
    )

    @Test
    fun `a first failure under a threshold of three owes a retry`() {
        assertTrue(QuickRetry.pending(monitor, policy, failing(1), silenced = false))
        assertTrue(QuickRetry.pending(monitor, policy, failing(2), silenced = false))
    }

    @Test
    fun `the retry is due one gap after the failure, not before`() {
        val runtime = failing(1)
        assertFalse(QuickRetry.due(monitor, policy, runtime, silenced = false, nowMs = now + QuickRetry.GAP_MS - 1))
        assertTrue(QuickRetry.due(monitor, policy, runtime, silenced = false, nowMs = now + QuickRetry.GAP_MS))
    }

    @Test
    fun `reaching the threshold ends it, because the alert has been decided`() {
        assertFalse(QuickRetry.pending(monitor, policy, failing(3), silenced = false))
        assertFalse(QuickRetry.pending(monitor, policy, failing(4), silenced = false))
    }

    @Test
    fun `a monitor already alerting is left to the schedule`() {
        assertFalse(QuickRetry.pending(monitor, policy, failing(1).copy(alerting = true), silenced = false))
    }

    @Test
    fun `a threshold of one has nothing to confirm`() {
        assertFalse(QuickRetry.pending(monitor, AlertPolicy(failureThreshold = 1), failing(1), silenced = false))
        assertNull(QuickRetry.streak(monitor, AlertPolicy(failureThreshold = 1), failing(1), silenced = false))
    }

    @Test
    fun `a passing monitor owes nothing`() {
        val ok = MonitorRuntime(
            lastCheckedAt = now,
            samples = listOf(Sample(at = now, ok = true, latencyMs = 40L)),
        )
        assertFalse(QuickRetry.pending(monitor, policy, ok, silenced = false))
        assertNull(QuickRetry.streak(monitor, policy, ok, silenced = false))
    }

    @Test
    fun `switched off, it waits for the schedule`() {
        assertFalse(QuickRetry.pending(monitor, policy.copy(quickRetry = false), failing(1), silenced = false))
    }

    /**
     * The car park. A dead local network stamps `lastCheckedAt` with no sample,
     * and that stamp must not start a loop that re-checks twice a minute.
     */
    @Test
    fun `an attempt that produced no verdict does not keep retrying`() {
        val noVerdict = failing(1).copy(lastCheckedAt = now + 5_000L)
        assertFalse(QuickRetry.pending(monitor, policy, noVerdict, silenced = false))
    }

    @Test
    fun `github repositories stay on their schedule`() {
        val repo = monitor.copy(kind = MonitorKind.GITHUB_REPO)
        assertFalse(QuickRetry.pending(repo, policy, failing(1), silenced = false))
    }

    @Test
    fun `alerts that will never be posted are not chased`() {
        assertFalse(QuickRetry.pending(monitor, policy.copy(enabled = false), failing(1), silenced = false))
        assertFalse(QuickRetry.pending(monitor, policy.copy(alertOnDown = false), failing(1), silenced = false))
        assertFalse(QuickRetry.pending(monitor.copy(enabled = false), policy, failing(1), silenced = false))
    }

    @Test
    fun `the card line says how far along it is and nothing it cannot know`() {
        assertEquals("1 of 3 failures before it alerts", QuickRetry.streak(monitor, policy, failing(1), silenced = false)?.line)
        assertEquals(
            "2 of 3 failures before it alerts",
            QuickRetry.streak(monitor, policy.copy(quickRetry = false), failing(2), silenced = false)?.line,
        )
    }

    /** Code review: retrying towards an alert that will never be posted. */
    @Test
    fun `a muted monitor or a switched off master is neither retried nor counted`() {
        val runtime = failing(1)
        assertFalse(QuickRetry.pending(monitor, policy, runtime, silenced = true))
        assertNull(QuickRetry.streak(monitor, policy, runtime, silenced = true))

        val settings = me.river.nightbell.domain.GlobalSettings()
        assertFalse(QuickRetry.silenced(settings, paused = false, runtime, now))
        assertTrue(QuickRetry.silenced(settings.copy(masterAlertsEnabled = false), paused = false, runtime, now))
        assertTrue(QuickRetry.silenced(settings, paused = false, runtime.copy(mutedUntil = now + 60_000L), now))
        assertFalse(
            "an expired mute is not a mute",
            QuickRetry.silenced(settings, paused = false, runtime.copy(mutedUntil = now - 1), now),
        )
    }

    /**
     * Code review: a pause silences every alert the way the master switch does,
     * and the card still counted towards one with monitoring paused.
     */
    @Test
    fun `a pause silences the streak like the master switch does`() {
        val runtime = failing(1)
        val settings = me.river.nightbell.domain.GlobalSettings()
        assertTrue(QuickRetry.silenced(settings, paused = true, runtime, now))
        assertNull(
            QuickRetry.streak(monitor, policy, runtime, QuickRetry.silenced(settings, paused = true, runtime, now)),
        )
    }

    /**
     * Code review: the stepper goes to ten, and nine retries thirty seconds apart
     * did not fit the four minutes the budget used to allow, so the editor's
     * "about 4 min 30s" was a promise the loop broke.
     */
    @Test
    fun `the budget fits every threshold the stepper allows`() {
        val lastRetryDue = (10 - 1) * QuickRetry.GAP_MS
        assertTrue(lastRetryDue < QuickRetry.BUDGET_MS)
        assertTrue("must stay inside WorkManager's ten minutes", QuickRetry.BUDGET_MS < 10 * 60_000L)
    }

    /**
     * Every stored policy predates the field. It decodes as on, which is the
     * decision: the half hour was the bug, not a setting anybody chose.
     */
    @Test
    fun `a policy stored before the switch existed decodes with it on`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AlertPolicy.serializer(), """{"failureThreshold":2}""")
        assertTrue(old.quickRetry)
    }

    @Test
    fun `the editor states the lead time in plain units`() {
        assertEquals("30s", quickRetryLead(1))
        assertEquals("1 min", quickRetryLead(2))
        assertEquals("1 min 30s", quickRetryLead(3))
        assertEquals("4 min 30s", quickRetryLead(9))
    }
}
