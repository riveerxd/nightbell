package me.river.nightbell

import me.river.nightbell.domain.HealthComponent
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.HealthStatus
import me.river.nightbell.domain.HealthTriage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail screen's three groups: what pages, what is expected, what is fine. */
class HealthTriageTest {

    private fun part(id: String, status: HealthStatus) = HealthComponent(id = id, status = status)

    private val state = HealthState(
        components = listOf(
            part("web", HealthStatus.UP),
            part("mail", HealthStatus.DEGRADED),
            part("db", HealthStatus.DOWN),
            part("cache", HealthStatus.DOWN),
            part("api", HealthStatus.UP),
        ),
    )

    @Test
    fun failingIsWorstFirstThenTheServicesOrder() {
        val triage = HealthTriage.of(state, emptyMap())
        assertEquals(listOf("db", "cache", "mail"), triage.failing.map { it.id })
        assertEquals(listOf("web", "api"), triage.healthy.map { it.id })
        assertTrue(triage.expected.isEmpty())
        assertEquals("2 down · 1 degraded · 2 up", triage.tally)
    }

    @Test
    fun anExpectedPartLeavesNeedsYouForItsOwnGroup() {
        val triage = HealthTriage.of(state, mapOf("db" to HealthStatus.DOWN))
        assertEquals(listOf("cache", "mail"), triage.failing.map { it.id })
        assertEquals(listOf("db"), triage.expected.map { it.id })
        assertEquals("1 down · 1 degraded · 1 expected · 2 up", triage.tally)
    }

    @Test
    fun anExpectationForAnotherStatusDoesNotHoldThePart() {
        // Marked while degraded, now down: that is a change, and it pages.
        val triage = HealthTriage.of(state, mapOf("db" to HealthStatus.DEGRADED))
        assertEquals(listOf("db", "cache", "mail"), triage.failing.map { it.id })
        assertTrue(triage.expected.isEmpty())
    }

    @Test
    fun theLineUnderAGreenVerdictNamesWhatIsExpected() {
        val one = HealthTriage.of(
            HealthState(components = listOf(HealthComponent("db", name = "Database", status = HealthStatus.DOWN), part("api", HealthStatus.UP))),
            mapOf("db" to HealthStatus.DOWN),
        )
        assertEquals("Database is down, and you marked that expected. Nothing else needs you.", one.expectedLine)

        val two = HealthTriage.of(state, mapOf("db" to HealthStatus.DOWN, "cache" to HealthStatus.DOWN, "mail" to HealthStatus.DEGRADED))
        assertEquals("3 parts are failing as expected. Nothing else needs you.", two.expectedLine)
        assertEquals("", HealthTriage.of(state, emptyMap()).expectedLine)
    }

    @Test
    fun aReportWithoutPartsIsEmpty() {
        val triage = HealthTriage.of(HealthState(), emptyMap())
        assertTrue(triage.isEmpty)
        assertEquals("", triage.tally)
    }
}
