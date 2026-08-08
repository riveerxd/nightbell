package me.river.nightbell

import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LegacyCrashRepair
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.UrgentAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clearing the fake crash state that 1.5.0 and earlier wrote to disk.
 *
 * The bug did not only produce notifications, it produced *data*: a DOWN health,
 * an alerting flag, a failure streak, `urgentActive`, and a persisted sample
 * recording a failure that never happened — all derived from a cancelled
 * coroutine. Fixing the engine without scrubbing that would leave the six red
 * cards, the six standing notifications and the un-dismissable urgent nags exactly
 * where they were after the upgrade.
 */
class LegacyCrashRepairTest {

    private val now = 1_700_000_000_000L
    private val fake = CheckerHealth.LEGACY_CRASH_MESSAGE

    /**
     * The runtime a 1.5.0 device actually persists after a cancelled check.
     *
     * `lastAlertAt` is set because `CheckEngine` writes it in the same
     * `updateRuntime` block as `alerting` whenever the fabricated verdict notified;
     * an `alerting = true` with no `lastAlertAt` is not a state that occurs on disk.
     */
    private fun fabricated() = MonitorRuntime(
        health = Health.DOWN,
        lastCheckedAt = now,
        lastMessage = fake,
        lastDetail = "StandaloneCoroutine was cancelled",
        consecutiveFailures = 4,
        alerting = true,
        lastAlertAt = now,
        urgentActive = true,
        lastUrgentAlertAt = now,
        samples = listOf(
            Sample(at = now - 3_000, ok = true, latencyMs = 820),
            Sample(at = now - 2_000, ok = false, latencyMs = 0, note = fake),
            Sample(at = now - 1_000, ok = false, latencyMs = 0, note = fake),
        ),
    )

    @Test
    fun `fabricated state is recognised`() {
        assertTrue(LegacyCrashRepair.isFabricated(fabricated()))
        assertTrue(LegacyCrashRepair.needsRepair(mapOf("a" to fabricated())))
    }

    @Test
    fun `a healthy runtime is returned untouched and unallocated`() {
        val clean = MonitorRuntime(
            health = Health.UP,
            lastMessage = "",
            samples = listOf(Sample(at = now, ok = true, latencyMs = 400)),
        )
        assertFalse(LegacyCrashRepair.isFabricated(clean))
        assertSame(clean, LegacyCrashRepair.scrub(clean))

        val map = mapOf("a" to clean)
        assertSame(map, LegacyCrashRepair.scrub(map))
    }

    @Test
    fun `a real outage is never mistaken for the fake one`() {
        // The one thing this repair must not do is erase a genuine outage.
        val real = MonitorRuntime(
            health = Health.DOWN,
            lastMessage = "HTTP 503",
            consecutiveFailures = 3,
            alerting = true,
            urgentActive = true,
            samples = listOf(Sample(at = now, ok = false, latencyMs = 90, note = "HTTP 503")),
        )
        assertFalse(LegacyCrashRepair.isFabricated(real))
        assertSame(real, LegacyCrashRepair.scrub(real))
    }

    @Test
    fun `the fabricated verdict and everything derived from it is erased`() {
        val repaired = LegacyCrashRepair.scrub(fabricated())
        assertEquals("nothing is actually known about this monitor", Health.UNKNOWN, repaired.health)
        assertEquals("", repaired.lastMessage)
        assertEquals("", repaired.lastDetail)
        assertEquals(0, repaired.consecutiveFailures)
        assertFalse(repaired.alerting)
        assertFalse("an ongoing, DND-bypassing orphan is the worst thing to leave", repaired.urgentActive)
    }

    @Test
    fun `the fake alert leaves no cooldown clock behind`() {
        // Clearing `alerting` without clearing `lastAlertAt` just moves the silence
        // from AlertDecider's already-alerting branch to its cooldown branch: a
        // genuine outage arriving inside `cooldownMinutes` of the fabricated alert
        // would be suppressed, for up to four hours at the maximum the UI offers.
        val repaired = LegacyCrashRepair.scrub(fabricated())
        assertEquals(0L, repaired.lastAlertAt)
        assertEquals(0L, repaired.lastDegradedAlertAt)
        assertFalse(repaired.degradedAlerting)

        // Proof it actually unblocks: a real failure one minute later now alerts.
        val decision = AlertDecider.decide(
            wasAlerting = repaired.alerting,
            ok = false,
            consecutiveFailures = repaired.consecutiveFailures + 1,
            lastAlertAt = repaired.lastAlertAt,
            policy = AlertPolicy(),
            masterEnabled = true,
            nowMs = now + 60_000,
            minuteOfDay = 12 * 60,
        )
        assertEquals(AlertDecider.Kind.DOWN, decision.kind)
    }

    @Test
    fun `acknowledging a fake urgent nag does not silence the next real one`() {
        // The nag is `ongoing`, so tapping "I've got it" is the only way to clear it
        // — which means an affected device very likely has urgentAcknowledged=true
        // sitting next to the sentinel. UrgentAlerts.evaluate returns NONE for an
        // acknowledged monitor and only clears the acknowledgement on a *successful*
        // check, which never arrives while a site is genuinely down. Left in place,
        // urgent would be off for the entire next outage.
        val acknowledged = fabricated().copy(urgentActive = false, urgentAcknowledged = true)
        val repaired = LegacyCrashRepair.scrub(acknowledged)
        assertFalse(repaired.urgentAcknowledged)
        assertFalse(repaired.urgentActive)
        assertEquals(0L, repaired.lastUrgentAlertAt)

        val outcome = UrgentAlerts.evaluate(
            previous = repaired.urgentState,
            eligible = true,
            down = true,
            nowMs = now + 60_000,
            repeatMinutes = 1,
        )
        assertEquals(UrgentAlerts.Action.START, outcome.action)
    }

    @Test
    fun `a scrubbed monitor is immediately due so the truth arrives at once`() {
        // The fabricated verdict set lastCheckedAt, which is the due-clock. Leaving
        // it would make the monitor wait out a whole interval before anybody found
        // out what is actually true.
        assertEquals(0L, LegacyCrashRepair.scrub(fabricated()).lastCheckedAt)
    }

    @Test
    fun `a genuine outage keeps its alert clocks`() {
        // The counter-guard: none of the above may touch a monitor that is really
        // down, or the repair would reset real cooldowns and re-alert a fleet.
        val real = MonitorRuntime(
            health = Health.DOWN,
            lastMessage = "HTTP 503",
            lastCheckedAt = now,
            lastAlertAt = now,
            alerting = true,
            urgentActive = true,
            urgentAcknowledged = true,
            samples = listOf(Sample(at = now, ok = false, latencyMs = 90, note = "HTTP 503")),
        )
        assertSame(real, LegacyCrashRepair.scrub(real))
    }

    @Test
    fun `invented failures are dropped from the history and real samples are kept`() {
        val repaired = LegacyCrashRepair.scrub(fabricated())
        assertEquals(1, repaired.samples.size)
        assertTrue(repaired.samples.single().ok)
        // Uptime was 33% from two failures that did not happen.
        assertEquals(100f, repaired.uptimePercent, 0.01f)
    }

    @Test
    fun `a monitor that has since had a real check keeps its current state`() {
        // Only the fake history goes: the latest verdict is real, so the card is
        // telling the truth and must not be reset to UNKNOWN.
        val recovered = fabricated().copy(
            health = Health.UP,
            lastMessage = "",
            consecutiveFailures = 0,
            alerting = false,
            urgentActive = false,
            samples = fabricated().samples + Sample(at = now, ok = true, latencyMs = 700),
        )
        val repaired = LegacyCrashRepair.scrub(recovered)
        assertEquals(Health.UP, repaired.health)
        assertEquals(2, repaired.samples.size)
        assertTrue(repaired.samples.none { it.note == fake })
    }

    @Test
    fun `a paused monitor stays paused rather than becoming unknown`() {
        val paused = fabricated().copy(health = Health.PAUSED)
        assertEquals(Health.PAUSED, LegacyCrashRepair.scrub(paused).health)
    }

    @Test
    fun `scrubbing is idempotent`() {
        val once = LegacyCrashRepair.scrub(fabricated())
        val twice = LegacyCrashRepair.scrub(once)
        assertEquals(once, twice)
        assertFalse(LegacyCrashRepair.isFabricated(once))
    }

    @Test
    fun `a whole fleet is repaired and untouched monitors are preserved by identity`() {
        val clean = MonitorRuntime(health = Health.UP, samples = listOf(Sample(now, true, 300)))
        val runtimes = mapOf("bad" to fabricated(), "good" to clean)
        val repaired = LegacyCrashRepair.scrub(runtimes)

        assertEquals(setOf("bad", "good"), repaired.keys)
        assertSame(clean, repaired.getValue("good"))
        assertEquals(Health.UNKNOWN, repaired.getValue("bad").health)
    }

    @Test
    fun `mute windows and latency history unrelated to the bug survive`() {
        val muted = fabricated().copy(mutedUntil = now + 3_600_000, lastLatencyMs = 1_234)
        val repaired = LegacyCrashRepair.scrub(muted)
        assertEquals(now + 3_600_000, repaired.mutedUntil)
        assertEquals(1_234, repaired.lastLatencyMs)
    }
}
