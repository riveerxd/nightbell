package me.river.pulse

import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Summary
import me.river.pulse.domain.UrgentAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worst-first roll-up shared by the dashboard, the widget and the
 * foreground-service notification. If these three ever disagree about what the
 * "worst" monitor is, this is the test that should have caught it.
 */
class SummaryTest {

    private fun monitor(id: String, name: String, enabled: Boolean = true, urgent: Boolean = false) =
        Monitor(id = id, name = name, url = "https://$id.example.com", enabled = enabled, urgent = urgent)

    private fun runtime(
        health: Health,
        latency: Long = 100,
        checkedAt: Long = 1_000,
        urgentState: UrgentAlerts.State = UrgentAlerts.State.Idle,
    ) = MonitorRuntime(
        health = health,
        lastLatencyMs = latency,
        lastCheckedAt = checkedAt,
    ).withUrgentState(urgentState)

    @Test
    fun `severity orders down before degraded before healthy`() {
        val order = listOf(Health.DOWN, Health.DEGRADED, Health.UNKNOWN, Health.UP, Health.PAUSED)
        val sorted = order.shuffled().sortedBy { Summary.severity(it) }
        assertEquals(order, sorted)
    }

    @Test
    fun `the worst monitor is the one that is down`() {
        val fleet = Summary.of(
            listOf(monitor("a", "Alpha"), monitor("b", "Bravo"), monitor("c", "Charlie")),
            mapOf(
                "a" to runtime(Health.UP),
                "b" to runtime(Health.DOWN),
                "c" to runtime(Health.DEGRADED),
            ),
        )
        assertEquals("Bravo", fleet.worst?.name)
        assertEquals(Health.DOWN, fleet.worstHealth)
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), fleet.ranked.map { it.name })
    }

    @Test
    fun `an unacknowledged urgent outage outranks an ordinary one`() {
        val fleet = Summary.of(
            listOf(monitor("a", "Alpha"), monitor("b", "Bravo", urgent = true)),
            mapOf(
                "a" to runtime(Health.DOWN),
                "b" to runtime(Health.DOWN, urgentState = UrgentAlerts.State(active = true)),
            ),
        )
        assertEquals("Bravo", fleet.worst?.name)
        assertTrue(fleet.worst!!.urgentNagging)
        assertEquals(1, fleet.urgentPending)
    }

    @Test
    fun `an acknowledged urgent outage stops counting as pending`() {
        val fleet = Summary.of(
            listOf(monitor("b", "Bravo", urgent = true)),
            mapOf(
                "b" to runtime(
                    Health.DOWN,
                    urgentState = UrgentAlerts.State(active = false, acknowledged = true),
                ),
            ),
        )
        assertEquals(0, fleet.urgentPending)
        // …but it is still down, and still the worst thing on the list.
        assertEquals(Health.DOWN, fleet.worstHealth)
    }

    @Test
    fun `a disabled monitor reports as paused whatever its last check said`() {
        val fleet = Summary.of(
            listOf(monitor("a", "Alpha", enabled = false)),
            mapOf("a" to runtime(Health.DOWN)),
        )
        assertEquals(Health.PAUSED, fleet.entries.single().health)
        assertEquals(1, fleet.paused)
        assertEquals(0, fleet.down)
    }

    @Test
    fun `headlines read the way a person would say them`() {
        assertEquals("No monitors yet", Summary.Fleet().headline)

        fun headline(vararg healths: Health): String = Summary.of(
            healths.mapIndexed { index, _ -> monitor("m$index", "M$index") },
            healths.mapIndexed { index, health -> "m$index" to runtime(health) }.toMap(),
        ).headline

        assertEquals("All 2 operational", headline(Health.UP, Health.UP))
        assertEquals("1 of 2 is down", headline(Health.DOWN, Health.UP))
        assertEquals("2 of 3 are down", headline(Health.DOWN, Health.DOWN, Health.UP))
        assertEquals("1 of 2 is slow", headline(Health.DEGRADED, Health.UP))
        assertEquals("2 of 2 are slow", headline(Health.DEGRADED, Health.DEGRADED))
        // Down wins over slow in the headline: you can only read one line.
        assertEquals("1 of 2 is down", headline(Health.DOWN, Health.DEGRADED))
    }

    @Test
    fun `an all-paused fleet says so rather than claiming everything is fine`() {
        val fleet = Summary.of(
            listOf(monitor("a", "Alpha", enabled = false), monitor("b", "Bravo", enabled = false)),
            emptyMap(),
        )
        assertEquals("All 2 paused", fleet.headline)
    }

    @Test
    fun `a monitor with no runtime yet is unknown, not healthy`() {
        val fleet = Summary.of(listOf(monitor("a", "Alpha")), emptyMap())
        assertEquals(Health.UNKNOWN, fleet.entries.single().health)
        assertEquals(0L, fleet.entries.single().lastCheckedAt)
    }

    @Test
    fun `an empty fleet has no worst monitor`() {
        assertNull(Summary.Fleet().worst)
        assertEquals(Health.UNKNOWN, Summary.Fleet().worstHealth)
    }

    @Test
    fun `ties break by name so the order never flickers`() {
        val fleet = Summary.of(
            listOf(monitor("c", "charlie"), monitor("a", "Alpha"), monitor("b", "bravo")),
            mapOf(
                "a" to runtime(Health.UP),
                "b" to runtime(Health.UP),
                "c" to runtime(Health.UP),
            ),
        )
        assertEquals(listOf("Alpha", "bravo", "charlie"), fleet.ranked.map { it.name })
    }
}
