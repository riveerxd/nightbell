package me.river.pulse

import me.river.pulse.domain.DueCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The due-ness rule, which 1.6.0 made load-bearing.
 *
 * Nothing checks unconditionally in the background any more: the periodic worker,
 * the repair sweep and the strict service all ask this first. So the rule failing
 * *closed* is silent non-monitoring, which is worse than the duplicate checks it
 * was introduced to stop.
 */
class DueCheckTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a monitor that has never been checked is due`() {
        assertTrue(DueCheck.isDue(intervalMinutes = 15, lastCheckedAt = 0L, nowMs = now))
        assertTrue(DueCheck.isDue(intervalMinutes = 15, lastCheckedAt = -1L, nowMs = now))
    }

    @Test
    fun `a monitor checked just now is not due`() {
        assertFalse(DueCheck.isDue(15, now, now))
        assertFalse(DueCheck.isDue(15, now - minute, now))
    }

    @Test
    fun `the interval elapsing makes it due`() {
        assertTrue(DueCheck.isDue(15, now - 15 * minute, now))
        assertTrue(DueCheck.isDue(60, now - 60 * minute, now))
    }

    @Test
    fun `slack lets a check that lands slightly early through`() {
        // WorkManager's own jitter is comfortably inside this, and without it a
        // check firing two seconds early would be deferred a whole interval.
        assertTrue(DueCheck.isDue(15, now - (15 * minute - DueCheck.SLACK_MS), now))
        assertFalse(DueCheck.isDue(15, now - (15 * minute - DueCheck.SLACK_MS - 1), now))
    }

    @Test
    fun `a clock that jumps backwards does not silence the monitor`() {
        // The regression this guard exists for. `lastCheckedAt` is wall-clock, so a
        // manual date change — or time sync correcting a fast RTC — makes the age
        // negative. Without the guard every monitor waits for the clock to catch
        // up: no checks, and an outage inside that window never alerts.
        assertTrue("a stamp one hour in the future must still be due", DueCheck.isDue(15, now + 60 * minute, now))
        assertTrue("…and a year in the future", DueCheck.isDue(15, now + 365L * 24 * 60 * minute, now))
        assertTrue(DueCheck.isDue(1440, now + minute, now))
    }

    @Test
    fun `a zero or negative interval is floored rather than dividing by nothing`() {
        assertTrue(DueCheck.isDue(0, now - 2 * minute, now))
        assertTrue(DueCheck.isDue(-5, now - 2 * minute, now))
    }

    @Test
    fun `a long interval is respected rather than rounded down`() {
        val daily = 1_440
        assertFalse(DueCheck.isDue(daily, now - 12 * 60 * minute, now))
        assertTrue(DueCheck.isDue(daily, now - 24 * 60 * minute, now))
    }
}
