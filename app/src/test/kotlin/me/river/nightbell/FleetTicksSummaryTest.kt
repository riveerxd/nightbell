package me.river.nightbell

import me.river.nightbell.domain.Health
import me.river.nightbell.ui.dashboard.ticksSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tick row's spoken form.
 *
 * The strip's whole content is a proportion, how much of the fleet is red, and it
 * used to announce itself as "Per-monitor health" and nothing else: the name of a
 * thing rather than anything it says. Sighted users read the proportion for free.
 */
class FleetTicksSummaryTest {

    @Test
    fun anEmptyFleetSaysSo() {
        assertEquals("Per-monitor health, nothing watched", ticksSummary(emptyList()))
    }

    @Test
    fun oneMonitorIsSingular() {
        assertEquals(
            "Per-monitor health, 1 monitor: 1 operational",
            ticksSummary(listOf(Health.UP)),
        )
    }

    @Test
    fun aMixedFleetCountsEveryState() {
        val healths = listOf(
            Health.DOWN,
            Health.DEGRADED,
            Health.UP,
            Health.UP,
            Health.PAUSED,
        )
        assertEquals(
            "Per-monitor health, 5 monitors: 2 operational, 1 down, 1 degraded, 1 paused",
            ticksSummary(healths),
        )
    }

    @Test
    fun statesNobodyIsInAreLeftOut() {
        val summary = ticksSummary(listOf(Health.UP, Health.UP))
        assertEquals("Per-monitor health, 2 monitors: 2 operational", summary)
    }
}
