package me.river.pulse

import me.river.pulse.domain.UrgentAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URGENT loop, exercised as a state machine.
 *
 * These are the transitions the feature actually promises: down starts a loop,
 * acknowledging stops it, recovery re-arms it, and a monitor without urgent
 * switched on never enters it at all.
 */
class UrgentAlertsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun evaluate(
        previous: UrgentAlerts.State = UrgentAlerts.State.Idle,
        eligible: Boolean = true,
        down: Boolean = true,
        at: Long = now,
        repeatMinutes: Int = 5,
    ) = UrgentAlerts.evaluate(previous, eligible, down, at, repeatMinutes)

    // ---- starting -----------------------------------------------------------

    @Test
    fun `going down starts the urgent loop`() {
        val outcome = evaluate()
        assertEquals(UrgentAlerts.Action.START, outcome.action)
        assertTrue(outcome.state.active)
        assertFalse(outcome.state.acknowledged)
        assertTrue(outcome.state.nagging)
        assertEquals(now, outcome.state.lastAlertAt)
    }

    @Test
    fun `a non-urgent monitor never enters the loop`() {
        // `eligible` is how the engine expresses "urgent is off for this one".
        val outcome = evaluate(eligible = false)
        assertEquals(UrgentAlerts.Action.NONE, outcome.action)
        assertFalse(outcome.state.active)
        assertFalse(outcome.state.nagging)
    }

    @Test
    fun `an already-started loop stays quiet inside the repeat gap`() {
        val started = evaluate().state
        val tooSoon = evaluate(previous = started, at = now + 3 * minute)
        assertEquals(UrgentAlerts.Action.NONE, tooSoon.action)
        assertEquals(now, tooSoon.state.lastAlertAt)
    }

    @Test
    fun `the loop re-alerts once the repeat gap elapses`() {
        val started = evaluate().state
        val due = evaluate(previous = started, at = now + 5 * minute)
        assertEquals(UrgentAlerts.Action.REPEAT, due.action)
        assertEquals(now + 5 * minute, due.state.lastAlertAt)
        assertTrue(due.state.nagging)
    }

    @Test
    fun `the repeat gap is floored at one minute`() {
        val started = evaluate(repeatMinutes = 0).state
        val tooSoon = evaluate(previous = started, at = now + 30_000L, repeatMinutes = 0)
        assertEquals(UrgentAlerts.Action.NONE, tooSoon.action)
        val due = evaluate(previous = started, at = now + minute, repeatMinutes = 0)
        assertEquals(UrgentAlerts.Action.REPEAT, due.action)
    }

    // ---- acknowledging ------------------------------------------------------

    @Test
    fun `acknowledging stops the loop but keeps the monitor down`() {
        val started = evaluate().state
        val acked = UrgentAlerts.acknowledge(started)
        assertEquals(UrgentAlerts.Action.CLEAR, acked.action)
        assertFalse(acked.state.active)
        assertTrue(acked.state.acknowledged)
        assertFalse(acked.state.nagging)

        // Still failing, well past the repeat gap — and still silent.
        val later = evaluate(previous = acked.state, at = now + 60 * minute)
        assertEquals(UrgentAlerts.Action.NONE, later.action)
        assertTrue(later.state.acknowledged)
    }

    @Test
    fun `acknowledging twice is a no-op`() {
        val acked = UrgentAlerts.acknowledge(evaluate().state).state
        val again = UrgentAlerts.acknowledge(acked)
        assertEquals(UrgentAlerts.Action.NONE, again.action)
        assertEquals(acked, again.state)
    }

    @Test
    fun `acknowledging an idle monitor does nothing`() {
        val outcome = UrgentAlerts.acknowledge(UrgentAlerts.State.Idle)
        assertEquals(UrgentAlerts.Action.NONE, outcome.action)
    }

    // ---- recovery -----------------------------------------------------------

    @Test
    fun `recovery clears the loop`() {
        val started = evaluate().state
        val recovered = evaluate(previous = started, down = false, at = now + minute)
        assertEquals(UrgentAlerts.Action.CLEAR, recovered.action)
        assertEquals(UrgentAlerts.State.Idle, recovered.state)
    }

    @Test
    fun `recovery resets the acknowledgement so the next outage shouts again`() {
        val acked = UrgentAlerts.acknowledge(evaluate().state).state
        val recovered = evaluate(previous = acked, down = false, at = now + 10 * minute)
        assertEquals(UrgentAlerts.Action.CLEAR, recovered.action)
        assertFalse(recovered.state.acknowledged)

        val nextOutage = evaluate(previous = recovered.state, at = now + 20 * minute)
        assertEquals(UrgentAlerts.Action.START, nextOutage.action)
        assertTrue(nextOutage.state.nagging)
    }

    @Test
    fun `a healthy monitor always reconciles to no notification`() {
        // Regression, 1.1.0: this used to return NONE when the previous state
        // was already idle, on the assumption that idle state implies no
        // notification on screen. It doesn't — `run()` reads that state before
        // a check that takes seconds, so two overlapping runs of one monitor
        // could post from the stale-losing run and persist idle from the other.
        // The urgent notification is `ongoing`, so the orphan could not be
        // dismissed by the user either. CLEAR is idempotent; NONE was a bet.
        val fromIdle = evaluate(down = false)
        assertEquals(UrgentAlerts.Action.CLEAR, fromIdle.action)
        assertEquals(UrgentAlerts.State.Idle, fromIdle.state)

        val fromActive = evaluate(previous = UrgentAlerts.State(active = true), down = false)
        assertEquals(UrgentAlerts.Action.CLEAR, fromActive.action)
        assertEquals(UrgentAlerts.State.Idle, fromActive.state)

        val fromAcknowledged = evaluate(
            previous = UrgentAlerts.State(acknowledged = true),
            down = false,
        )
        assertEquals(UrgentAlerts.Action.CLEAR, fromAcknowledged.action)
        assertEquals(UrgentAlerts.State.Idle, fromAcknowledged.state)
    }

    @Test
    fun `a healthy non-urgent monitor also reconciles`() {
        // Turning urgent off mid-outage must take the notification with it.
        val outcome = evaluate(
            previous = UrgentAlerts.State(active = true),
            eligible = false,
            down = false,
        )
        assertEquals(UrgentAlerts.Action.CLEAR, outcome.action)
        assertEquals(UrgentAlerts.State.Idle, outcome.state)
    }

    // ---- suppression --------------------------------------------------------

    @Test
    fun `losing eligibility mid-outage pulls the notification but keeps the ack`() {
        // e.g. the user hits "Mute 1h" while an urgent outage is running.
        val acked = UrgentAlerts.acknowledge(evaluate().state).state
        val muted = evaluate(previous = acked, eligible = false, at = now + minute)
        assertEquals(UrgentAlerts.Action.NONE, muted.action)
        assertTrue("un-muting must not re-nag a seen outage", muted.state.acknowledged)
    }

    @Test
    fun `losing eligibility while nagging clears the notification`() {
        val started = evaluate().state
        val muted = evaluate(previous = started, eligible = false, at = now + minute)
        assertEquals(UrgentAlerts.Action.CLEAR, muted.action)
        assertFalse(muted.state.active)
        // Regaining eligibility restarts it — the outage was never acknowledged.
        val unmuted = evaluate(previous = muted.state, at = now + 2 * minute)
        assertEquals(UrgentAlerts.Action.START, unmuted.action)
    }

    // ---- scheduling ---------------------------------------------------------

    @Test
    fun `next repeat delay counts down and is null when nothing is pending`() {
        val started = evaluate().state
        assertEquals(5 * minute, UrgentAlerts.nextRepeatDelayMs(started, now, 5))
        assertEquals(2 * minute, UrgentAlerts.nextRepeatDelayMs(started, now + 3 * minute, 5))
        assertEquals(0L, UrgentAlerts.nextRepeatDelayMs(started, now + 99 * minute, 5))

        assertNull(UrgentAlerts.nextRepeatDelayMs(UrgentAlerts.State.Idle, now, 5))
        val acked = UrgentAlerts.acknowledge(started).state
        assertNull(UrgentAlerts.nextRepeatDelayMs(acked, now, 5))
    }
}
