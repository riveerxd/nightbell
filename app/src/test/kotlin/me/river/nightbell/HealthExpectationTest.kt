package me.river.nightbell

import java.time.Instant
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HealthExpectation
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parts marked as expected: a staging service that is red on purpose stops
 * paging, and the first change to that part pages again.
 */
class HealthExpectationTest {

    private val now = Instant.parse("2026-10-22T10:00:00Z")

    private fun doc(status: String, vararg parts: Pair<String, String>, message: String = "", checkedAt: String = "2026-10-22T10:00:00Z"): String {
        val components = parts.joinToString(",") { (id, s) ->
            """{"id":"$id","status":"$s","message":"$id said $s at ${System.nanoTime()}"}"""
        }
        val said = if (message.isBlank()) "" else ""","message":"$message""""
        return """{"healthReport":1,"status":"$status","checkedAt":"$checkedAt"$said,"components":[$components]}"""
    }

    private fun check(body: String, staleAfterMinutes: Int = 0): CheckResult {
        val reading = HealthFormat.parse(503, body, null, now, staleAfterMinutes)
        return HealthFormat.result(reading, 503, 42, now.toEpochMilli())
    }

    private val dbDown = mapOf("db" to HealthStatus.DOWN)

    @Test
    fun anExpectedDownPartNoLongerTakesTheReportDown() {
        val applied = HealthExpectation.apply(check(doc("down", "db" to "down", "api" to "up")), dbDown)
        assertTrue(applied.result.ok)
        assertEquals(FailureKind.NONE, applied.result.failureKind)
        assertFalse(applied.result.degraded)
        assertEquals("Reported up in 42ms", applied.result.message)
        assertEquals("", applied.result.detail)
        assertEquals(dbDown, applied.expected)
    }

    @Test
    fun withNothingExpectedTheResultIsUntouched() {
        val raw = check(doc("down", "db" to "down"))
        val applied = HealthExpectation.apply(raw, emptyMap())
        assertEquals(raw, applied.result)
        assertFalse(applied.result.ok)
    }

    @Test
    fun aNewMessageOnTheSameStatusIsNotAChange() {
        // Every message here carries a fresh number, the way a real staging
        // report carries a latency. Two checks, both quiet.
        val first = HealthExpectation.apply(check(doc("down", "db" to "down")), dbDown)
        val second = HealthExpectation.apply(check(doc("down", "db" to "down")), first.expected)
        assertTrue(first.result.ok)
        assertTrue(second.result.ok)
        assertEquals(dbDown, second.expected)
    }

    @Test
    fun downToDegradedDropsTheExpectationAndShowsDegraded() {
        val applied = HealthExpectation.apply(check(doc("degraded", "db" to "degraded")), dbDown)
        assertTrue(applied.result.ok)
        assertTrue(applied.result.degraded)
        assertEquals(emptyMap<String, HealthStatus>(), applied.expected)
    }

    @Test
    fun recoveryDropsTheExpectationSoTheNextOutagePages() {
        val recovered = HealthExpectation.apply(check(doc("up", "db" to "up")), dbDown)
        assertTrue(recovered.result.ok)
        assertEquals(emptyMap<String, HealthStatus>(), recovered.expected)

        val again = HealthExpectation.apply(check(doc("down", "db" to "down")), recovered.expected)
        assertFalse(again.result.ok)
        assertEquals(FailureKind.REPORTED_DOWN, again.result.failureKind)
    }

    @Test
    fun anotherPartGoingDownStillPagesAndIsTheOneNamed() {
        val applied = HealthExpectation.apply(check(doc("down", "db" to "down", "cache" to "down")), dbDown)
        assertFalse(applied.result.ok)
        assertTrue(applied.result.message, applied.result.message.startsWith("cache: "))
        assertFalse(applied.result.detail, applied.result.detail.contains("db"))
        assertTrue(applied.result.detail.contains("cache down"))
        assertEquals(dbDown, applied.expected)
    }

    @Test
    fun theReportsOwnMessageIsKeptWhenSomethingElseIsStillDown() {
        val body = doc("down", "db" to "down", "cache" to "down", message = "Checkout is broken")
        val applied = HealthExpectation.apply(check(body), dbDown)
        assertFalse(applied.result.ok)
        assertEquals("Checkout is broken", applied.result.message)
    }

    @Test
    fun anExpectedDownPartBesideADegradedOneLeavesItDegraded() {
        val applied = HealthExpectation.apply(check(doc("down", "db" to "down", "queue" to "degraded")), dbDown)
        assertTrue(applied.result.ok)
        assertTrue(applied.result.degraded)
        assertEquals("Reported degraded in 42ms", applied.result.message)
    }

    @Test
    fun aDownReportThatNoPartExplainsStaysDown() {
        // The service says down and its worst part says degraded: the reason is
        // somewhere the parts do not show, so expecting the part lifts nothing.
        val expected = mapOf("db" to HealthStatus.DEGRADED)
        val applied = HealthExpectation.apply(check(doc("down", "db" to "degraded")), expected)
        assertFalse(applied.result.ok)
    }

    @Test
    fun aStaleReportIsNotLiftedByAnExpectedPart() {
        val body = doc("down", "db" to "down", checkedAt = "2026-10-22T09:00:00Z")
        val applied = HealthExpectation.apply(check(body, staleAfterMinutes = 10), dbDown)
        assertFalse(applied.result.ok)
        assertTrue(applied.result.message, applied.result.message.startsWith("Report is 60 minutes old"))
    }

    @Test
    fun anUnknownStatusWordIsNotLiftedByAnExpectedPart() {
        val applied = HealthExpectation.apply(check(doc("on fire", "db" to "down")), dbDown)
        assertFalse(applied.result.ok)
    }

    @Test
    fun aPartMissingFromTheReportKeepsItsExpectation() {
        val applied = HealthExpectation.apply(check(doc("up", "api" to "up")), dbDown)
        assertEquals(dbDown, applied.expected)
    }

    @Test
    fun aCheckThatReadNoReportKeepsEveryExpectation() {
        val timeout = CheckResult(ok = false, latencyMs = 0, failureKind = FailureKind.TIMEOUT, message = "Timed out", health = HealthState(), at = 0L)
        val applied = HealthExpectation.apply(timeout, dbDown)
        assertEquals(timeout, applied.result)
        assertEquals(dbDown, applied.expected)
    }

    @Test
    fun theStoredVerdictIsWorkedOutAgainWithoutACheck() {
        val state = check(doc("down", "db" to "down", "api" to "up")).health!!
        assertEquals(HealthStatus.DOWN, HealthExpectation.verdict(state, emptyMap()))
        assertEquals(HealthStatus.UP, HealthExpectation.verdict(state, dbDown))
        assertNull(HealthExpectation.verdict(HealthState(), dbDown))
    }

    @Test
    fun theCardLineCountsExpectedParts() {
        val state = check(doc("down", "db" to "down", "api" to "up", "web" to "up")).health!!
        assertEquals("db down · 2 of 3 OK", HealthFormat.summary(state))
        assertEquals("2 of 3 OK · 1 expected", HealthFormat.summary(state, dbDown))

        val mixed = check(doc("down", "db" to "down", "queue" to "degraded", "api" to "up")).health!!
        assertEquals("queue degraded · 1 of 3 OK · 1 expected", HealthFormat.summary(mixed, dbDown))
    }
}
