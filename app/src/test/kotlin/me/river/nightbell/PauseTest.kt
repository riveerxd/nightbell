package me.river.nightbell

import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PauseChoice
import me.river.nightbell.domain.Summary
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the pause button.
 *
 * Worth testing away from the UI because the decisions are the feature: a pause
 * that silently expired the wrong way, or that stopped alerts without stopping
 * checks when it said it would, is indistinguishable from a bug in the checker.
 */
class PauseTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `no pause is not a pause`() {
        val idle = PauseState()
        assertFalse(idle.isActive(now))
        assertFalse(idle.stopsChecks(now))
        assertNull(idle.remainingMs(now))
    }

    @Test
    fun `a timed pause lifts itself exactly when it said it would`() {
        val paused = PauseState.timed(now, minutes = 30, scope = PauseScope.STOP_CHECKS)

        assertTrue(paused.isActive(now))
        assertTrue(paused.isActive(now + 29 * 60_000L))
        // The boundary is exclusive: at the stroke of the deadline it is over.
        assertFalse(paused.isActive(now + 30 * 60_000L))
        assertFalse(paused.isActive(now + 31 * 60_000L))
    }

    @Test
    fun `an indefinite pause outlasts anything`() {
        val paused = PauseState.forever(now, PauseScope.STOP_CHECKS)

        assertTrue(paused.isActive(now))
        assertTrue(paused.isActive(now + 365L * 24 * 60 * 60_000L))
        // Nothing to count down to, and the banner has to know that rather than
        // rendering a countdown that never moves.
        assertNull(paused.remainingMs(now))
    }

    @Test
    fun `every pause silences, only one kind stops the checks`() {
        val silent = PauseState.timed(now, 60, PauseScope.ALERTS_ONLY)
        val stopped = PauseState.timed(now, 60, PauseScope.STOP_CHECKS)

        // The asymmetry is the design: a pause that could still page would not be
        // a pause, but only one of the two is allowed to leave gaps in history.
        assertTrue(silent.isActive(now))
        assertFalse(silent.stopsChecks(now))
        assertTrue(stopped.isActive(now))
        assertTrue(stopped.stopsChecks(now))
    }

    @Test
    fun `an expired pause stops the checks no longer`() {
        val expired = PauseState.timed(now, 30, PauseScope.STOP_CHECKS)
        assertFalse(expired.stopsChecks(now + 31 * 60_000L))
    }

    @Test
    fun `the countdown never runs negative`() {
        val paused = PauseState.timed(now, 30, PauseScope.STOP_CHECKS)
        assertEquals(30 * 60_000L, paused.remainingMs(now))
        assertEquals(60_000L, paused.remainingMs(now + 29 * 60_000L))
        // Past the deadline it is not active, so there is nothing left to report.
        assertNull(paused.remainingMs(now + 45 * 60_000L))
    }

    @Test
    fun `a fleet pause reads through to every widget entry`() {
        val monitors = listOf(
            Monitor(id = "a", name = "A", url = "https://a.example"),
            Monitor(id = "b", name = "B", url = "https://b.example"),
        )
        val runtimes = mapOf(
            "a" to MonitorRuntime(health = Health.UP),
            "b" to MonitorRuntime(health = Health.DOWN),
        )

        val live = Summary.of(monitors, runtimes, fleetPaused = false)
        val paused = Summary.of(monitors, runtimes, fleetPaused = true)

        assertEquals("1 of 2 is down", live.headline)
        // Not "1 of 2 is down" any more: nothing has been checked since the pause
        // began, so the last verdict is a claim the app can no longer stand behind.
        assertEquals("All 2 paused", paused.headline)
        assertTrue(paused.entries.all { it.health == Health.PAUSED })
    }

    @Test
    fun `the settings choice maps to a scope, or to asking`() {
        assertEquals(PauseScope.STOP_CHECKS, PauseChoice.STOP_CHECKS.scope)
        assertEquals(PauseScope.ALERTS_ONLY, PauseChoice.ALERTS_ONLY.scope)
        assertNull("ASK has no scope of its own, that is the point", PauseChoice.ASK.scope)
    }

    @Test
    fun `the offered durations end with the indefinite entry`() {
        val offered = PauseState.OFFERED_MINUTES

        assertEquals(listOf(30, 60, 240, 480, null), offered)
        // Exactly one open-ended option, and it is last: the timed ones are the
        // ones a user should land on by default.
        assertEquals(1, offered.count { it == null })
        assertNull(offered.last())
    }
}
