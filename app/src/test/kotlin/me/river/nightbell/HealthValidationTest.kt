package me.river.nightbell

import me.river.nightbell.domain.AssertionMode
import me.river.nightbell.domain.BodyAssertion
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What setup refuses to save for a health monitor, and what it lets through. */
class HealthValidationTest {

    private fun monitor(watch: HealthWatch = HealthWatch(), headers: List<HeaderPair> = emptyList()) = Monitor(
        id = "h",
        kind = MonitorKind.HEALTH_ENDPOINT,
        url = "https://api.example.com/health",
        health = watch,
        headers = headers,
    )

    private fun errorOn(monitor: Monitor, field: Validation.Field): String? =
        Validation.report(monitor).notes.firstOrNull { it.field == field && it.severity == Validation.Severity.ERROR }?.message

    @Test
    fun anOpenEndpointNeedsNothingButItsAddress() {
        assertTrue(Validation.report(monitor()).isValid)
    }

    @Test
    fun aBodyAssertionLeftOverFromAnotherKindDoesNotBlockSaving() {
        val leftover = monitor().copy(assertion = BodyAssertion(mode = AssertionMode.REGEX, value = "("))

        assertTrue(Validation.report(leftover).isValid)
    }

    @Test
    fun eachSignInAsksForWhatItNeeds() {
        assertTrue(errorOn(monitor(HealthWatch(signIn = HealthSignIn.HEADERS)), Validation.Field.SIGN_IN)!!.contains("header"))
        assertNull(errorOn(monitor(HealthWatch(signIn = HealthSignIn.HEADERS), listOf(HeaderPair("X-Api-Key", "k"))), Validation.Field.SIGN_IN))
        assertEquals("Enter the username", errorOn(monitor(HealthWatch(signIn = HealthSignIn.BASIC)), Validation.Field.SIGN_IN))
        assertTrue(errorOn(monitor(HealthWatch(signIn = HealthSignIn.SSO)), Validation.Field.SIGN_IN) != null)
        assertNull(
            errorOn(
                monitor(HealthWatch(signIn = HealthSignIn.SSO, clientId = "c", tokenEndpoint = "https://sso.example.com/token")),
                Validation.Field.SIGN_IN,
            ),
        )
    }

    @Test
    fun aNegativeAgeLimitIsRefused() {
        assertTrue(errorOn(monitor(HealthWatch(staleAfterMinutes = -1)), Validation.Field.STALE) != null)
    }
}
