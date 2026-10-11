package me.river.nightbell

import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.SignInReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Once when sign-in trouble starts, then once a day, and never as a page. */
class SignInReminderTest {

    private val t0 = 1_800_000_000_000L

    @Test
    fun theFirstCheckThatCannotSignInSaysSo() {
        assertTrue(SignInReminder.shouldNotify(HealthAuth(), AuthProblem.SESSION_ENDED, t0))
    }

    @Test
    fun theNextChecksTheSameDayStayQuiet() {
        val after = SignInReminder.failed(HealthAuth(), AuthProblem.SESSION_ENDED, "", t0, notified = true)

        assertFalse(SignInReminder.shouldNotify(after, AuthProblem.SESSION_ENDED, t0 + 23 * 3_600_000L))
        assertTrue(SignInReminder.shouldNotify(after, AuthProblem.SESSION_ENDED, t0 + SignInReminder.EVERY_MS))
        assertEquals(t0, after.since)
    }

    @Test
    fun aDifferentProblemIsNews() {
        val after = SignInReminder.failed(HealthAuth(), AuthProblem.SESSION_ENDED, "", t0, notified = true)

        assertTrue(SignInReminder.shouldNotify(after, AuthProblem.CREDENTIALS_REFUSED, t0 + 60_000))
    }

    @Test
    fun signingInClearsTheTroubleAndKeepsTheAccount() {
        val trouble = HealthAuth(problem = AuthProblem.SIGNED_OUT, since = t0, lastNotifiedAt = t0, account = "alice")

        val after = SignInReminder.signedIn(trouble, HealthAuth(lastRefreshAt = t0))

        assertEquals(AuthProblem.NONE, after.problem)
        assertEquals("alice", after.account)
        assertEquals(0L, after.lastNotifiedAt)
    }
}
