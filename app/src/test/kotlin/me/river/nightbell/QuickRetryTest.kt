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
        assertTrue(QuickRetry.pending(monitor, policy, failing(1)))
        assertTrue(QuickRetry.pending(monitor, policy, failing(2)))
    }

    @Test
    fun `the retry is due one gap after the failure, not before`() {
        val runtime = failing(1)
        assertFalse(QuickRetry.due(monitor, policy, runtime, now + QuickRetry.GAP_MS - 1))
        assertTrue(QuickRetry.due(monitor, policy, runtime, now + QuickRetry.GAP_MS))
    }

    @Test
    fun `reaching the threshold ends it, because the alert has been decided`() {
        assertFalse(QuickRetry.pending(monitor, policy, failing(3)))
        assertFalse(QuickRetry.pending(monitor, policy, failing(4)))
    }

    @Test
    fun `a monitor already alerting is left to the schedule`() {
        assertFalse(QuickRetry.pending(monitor, policy, failing(1).copy(alerting = true)))
    }

    @Test
    fun `a threshold of one has nothing to confirm`() {
        assertFalse(QuickRetry.pending(monitor, AlertPolicy(failureThreshold = 1), failing(1)))
        assertNull(QuickRetry.streak(monitor, AlertPolicy(failureThreshold = 1), failing(1)))
    }

    @Test
    fun `a passing monitor owes nothing`() {
        val ok = MonitorRuntime(
            lastCheckedAt = now,
            samples = listOf(Sample(at = now, ok = true, latencyMs = 40L)),
        )
        assertFalse(QuickRetry.pending(monitor, policy, ok))
        assertNull(QuickRetry.streak(monitor, policy, ok))
    }

    @Test
    fun `switched off, it waits for the schedule`() {
        assertFalse(QuickRetry.pending(monitor, policy.copy(quickRetry = false), failing(1)))
    }

    /**
     * The car park. A dead local network stamps `lastCheckedAt` with no sample,
     * and that stamp must not start a loop that re-checks twice a minute.
     */
    @Test
    fun `an attempt that produced no verdict does not keep retrying`() {
        val noVerdict = failing(1).copy(lastCheckedAt = now + 5_000L)
        assertFalse(QuickRetry.pending(monitor, policy, noVerdict))
    }

    @Test
    fun `github repositories stay on their schedule`() {
        val repo = monitor.copy(kind = MonitorKind.GITHUB_REPO)
        assertFalse(QuickRetry.pending(repo, policy, failing(1)))
    }

    @Test
    fun `alerts that will never be posted are not chased`() {
        assertFalse(QuickRetry.pending(monitor, policy.copy(enabled = false), failing(1)))
        assertFalse(QuickRetry.pending(monitor, policy.copy(alertOnDown = false), failing(1)))
        assertFalse(QuickRetry.pending(monitor.copy(enabled = false), policy, failing(1)))
    }

    @Test
    fun `the card line says how far along it is and what happens next`() {
        val quick = QuickRetry.streak(monitor, policy, failing(1))
        assertEquals("1 of 3 failures, re-checking every 30s", quick?.line)
        assertEquals(now + QuickRetry.GAP_MS, quick?.retryAt)

        val slow = QuickRetry.streak(monitor, policy.copy(quickRetry = false), failing(2))
        assertEquals("2 of 3 failures before it alerts", slow?.line)
        assertNull(slow?.retryAt)
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
