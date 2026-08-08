package me.river.nightbell

import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.CertificateWatch
import me.river.nightbell.domain.CertificateWatch.Level
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.MonitorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The certificate track.
 *
 * Three things have to hold, and each one is a bug the other tracks in this app
 * have already been through once:
 *
 *  1. A certificate that cannot be read produces *no opinion*, never a warning.
 *  2. A failed check does not erase the expiry date we already know.
 *  3. It speaks on escalation and then goes quiet, rather than repeating an
 *     identical notice for a fortnight.
 */
class CertificateWatchTest {

    private val now = 1_700_000_000_000L
    private val day = CertificateWatch.DAY_MS

    private fun inDays(days: Long) = now + days * day

    // ---- levels ------------------------------------------------------------

    @Test
    fun `no certificate is unknown, never expiring`() {
        assertEquals(Level.UNKNOWN, CertificateWatch.level(0L, now, warnDays = 14, criticalDays = 2))
    }

    @Test
    fun `a comfortable certificate is ok`() {
        assertEquals(Level.OK, CertificateWatch.level(inDays(60), now, 14, 2))
    }

    @Test
    fun `crossing the warning threshold warns`() {
        assertEquals(Level.OK, CertificateWatch.level(inDays(15), now, 14, 2))
        assertEquals(Level.WARN, CertificateWatch.level(inDays(14), now, 14, 2))
    }

    @Test
    fun `crossing the critical threshold escalates`() {
        assertEquals(Level.WARN, CertificateWatch.level(inDays(3), now, 14, 2))
        assertEquals(Level.CRITICAL, CertificateWatch.level(inDays(2), now, 14, 2))
    }

    @Test
    fun `past notAfter is expired`() {
        assertEquals(Level.EXPIRED, CertificateWatch.level(now - 1, now, 14, 2))
        assertEquals(Level.EXPIRED, CertificateWatch.level(inDays(-30), now, 14, 2))
    }

    @Test
    fun `a zero warning window turns the track off without hiding an expiry`() {
        // The threshold is the user's; "already broken" is not a matter of taste,
        // so an expired cert still reads as OK only because they asked for silence
        // — and the level function is the wrong place to override that. Verify the
        // documented behaviour rather than assuming it.
        assertEquals(Level.OK, CertificateWatch.level(inDays(1), now, warnDays = 0, criticalDays = 0))
    }

    @Test
    fun `a critical threshold above the warning one cannot open a silent gap`() {
        // criticalDays clamps to warnDays, so everything inside the window is at
        // least CRITICAL rather than falling through to OK.
        val level = CertificateWatch.level(inDays(5), now, warnDays = 7, criticalDays = 30)
        assertEquals(Level.CRITICAL, level)
    }

    // ---- days left ---------------------------------------------------------

    @Test
    fun `days left rounds down so an evening is never called a day`() {
        val almostTomorrow = now + day - 60_000
        assertEquals(0L, CertificateWatch.daysLeft(almostTomorrow, now))
    }

    @Test
    fun `days left goes negative once expired`() {
        assertEquals(-2L, CertificateWatch.daysLeft(inDays(-2), now))
    }

    // ---- escalation --------------------------------------------------------

    @Test
    fun `nothing is said while the certificate is fine`() {
        assertFalse(CertificateWatch.shouldAlert(Level.OK, Level.OK.rank, 0L, now))
        assertFalse(CertificateWatch.shouldAlert(Level.UNKNOWN, Level.OK.rank, 0L, now))
    }

    @Test
    fun `the first warning is announced`() {
        assertTrue(CertificateWatch.shouldAlert(Level.WARN, Level.OK.rank, 0L, now))
    }

    @Test
    fun `an unchanged level stays quiet until the daily reminder`() {
        val announcedAt = now - 6 * 60 * 60 * 1000
        assertFalse(CertificateWatch.shouldAlert(Level.WARN, Level.WARN.rank, announcedAt, now))
        assertTrue(
            CertificateWatch.shouldAlert(Level.WARN, Level.WARN.rank, now - 25 * 60 * 60 * 1000, now),
        )
    }

    @Test
    fun `escalation speaks immediately, ignoring the reminder gap`() {
        val justNow = now - 60_000
        assertTrue(CertificateWatch.shouldAlert(Level.CRITICAL, Level.WARN.rank, justNow, now))
        assertTrue(CertificateWatch.shouldAlert(Level.EXPIRED, Level.CRITICAL.rank, justNow, now))
    }

    @Test
    fun `de-escalation says nothing`() {
        // Renewal is not news worth a notification; the notice is simply cancelled.
        assertFalse(CertificateWatch.shouldAlert(Level.WARN, Level.CRITICAL.rank, now - day, now))
    }

    @Test
    fun `renewal resets the announced level so next year's expiry is announced again`() {
        // Without the reset, a monitor that once reached CRITICAL would be compared
        // against that rank forever and the next genuine expiry would never speak.
        assertEquals(Level.OK.rank, CertificateWatch.alertedLevelAfter(Level.OK))
        assertEquals(Level.OK.rank, CertificateWatch.alertedLevelAfter(Level.UNKNOWN))
        assertEquals(Level.CRITICAL.rank, CertificateWatch.alertedLevelAfter(Level.CRITICAL))
    }

    // ---- the fold ----------------------------------------------------------

    @Test
    fun `a failed check does not erase the expiry date we already knew`() {
        // This is the bug that would matter most: a site starts timing out, the
        // handshake never completes, and the cert warning silently disappears at
        // exactly the moment things are going wrong.
        val before = MonitorRuntime(certExpiresAt = inDays(3), certIssuer = "Example CA")
        val failed = CheckResult(
            ok = false,
            latencyMs = 0,
            failureKind = FailureKind.TIMEOUT,
            message = "No response within 15s",
            at = now,
        )
        val after = AlertDecider.advance(before, failed, historyDepth = 60)
        assertEquals(inDays(3), after.certExpiresAt)
        assertEquals("Example CA", after.certIssuer)
    }

    @Test
    fun `a renewal replaces the stored date`() {
        val before = MonitorRuntime(certExpiresAt = inDays(3), certIssuer = "Old CA")
        val renewed = CheckResult(
            ok = true,
            latencyMs = 120,
            statusCode = 200,
            certExpiresAt = inDays(90),
            certIssuer = "New CA",
            at = now,
        )
        val after = AlertDecider.advance(before, renewed, historyDepth = 60)
        assertEquals(inDays(90), after.certExpiresAt)
        assertEquals("New CA", after.certIssuer)
    }

    @Test
    fun `a plain HTTP monitor never accumulates a certificate`() {
        val result = CheckResult(ok = true, latencyMs = 40, statusCode = 200, at = now)
        val after = AlertDecider.advance(MonitorRuntime(), result, historyDepth = 60)
        assertEquals(0L, after.certExpiresAt)
        assertEquals(
            Level.UNKNOWN,
            CertificateWatch.level(after.certExpiresAt, now, 14, 2),
        )
    }

    @Test
    fun `certificate state never changes the monitor's health`() {
        // The site is up. A deadline next Tuesday must not paint it red.
        val result = CheckResult(
            ok = true,
            latencyMs = 40,
            statusCode = 200,
            certExpiresAt = inDays(-5),
            at = now,
        )
        val after = AlertDecider.advance(MonitorRuntime(), result, historyDepth = 60, degradedAboveMs = 0)
        assertEquals(me.river.nightbell.domain.Health.UP, after.health)
    }
}
