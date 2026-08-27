package me.river.nightbell

import me.river.nightbell.domain.TlsFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app says when a certificate is refused.
 *
 * Worth a test class of its own, because on issue #6 the wording *was* the bug.
 * The handshake failed correctly, and the reporter could not tell from the screen
 * whether their setup was wrong, the service was broken, or the app was.
 */
class TlsFailureCopyTest {

    private val untrusted = TlsFailure.Cause.UntrustedChain
    private val mismatch = TlsFailure.Cause.PinMismatch(
        expected = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        actual = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
    )

    @Test
    fun `a hidden service is told the truth rather than warned`() {
        val headline = TlsFailure.headline(untrusted, hiddenService = true)
        val body = TlsFailure.explanation(untrusted, hiddenService = true)

        assertEquals("No CA vouches for this certificate", headline)
        assertTrue(body, body.contains("No certificate authority issues for .onion"))
        assertTrue(body, body.contains("Pinned key"))
        // The three-way guess this replaced. An onion service's certificate is not
        // expired and its hostname is not mismatched, and saying it might be sends
        // the reader looking for a problem that is not there.
        assertFalse(body, body.contains("expired"))
        assertFalse(body, body.contains("mismatch"))
    }

    @Test
    fun `an ordinary host gets the self-signed explanation`() {
        val headline = TlsFailure.headline(untrusted, hiddenService = false)
        val body = TlsFailure.explanation(untrusted, hiddenService = false)

        assertEquals("Certificate not trusted", headline)
        assertTrue(body, body.contains("self-signed certificate or a private CA"))
        // And it does not tell someone to trust something they may have no reason
        // to. The escape hatch is offered with a condition attached to it.
        assertTrue(body, body.contains("treat it as a real failure"))
    }

    @Test
    fun `a pin mismatch shows both keys and says how to re-pin`() {
        val headline = TlsFailure.headline(mismatch, hiddenService = false)
        val body = TlsFailure.explanation(mismatch, hiddenService = false)

        assertEquals("The certificate key changed", headline)
        assertTrue(body, body.contains(mismatch.expected))
        assertTrue(body, body.contains(mismatch.actual))
        // Names the button that actually exists on the detail screen. A message
        // telling someone to do something the app does not offer is worse than no
        // message, and the first draft of this did exactly that.
        assertTrue(body, body.contains("\"Trust the new key\""))
    }

    @Test
    fun `a pin mismatch on a hidden service is still a pin mismatch`() {
        // Order matters. The hidden-service explanation says a CA-less certificate
        // is normal here, which is true and completely wrong to show someone whose
        // pinned key just changed.
        assertEquals(
            "The certificate key changed",
            TlsFailure.headline(mismatch, hiddenService = true),
        )
        val body = TlsFailure.explanation(mismatch, hiddenService = true)
        assertTrue(body, body.contains("pinned to one public key"))
        assertFalse(body, body.contains("No certificate authority issues"))
    }

    @Test
    fun `the redirect that caused the handshake is named`() {
        val body = TlsFailure.explanation(
            untrusted,
            hiddenService = false,
            schemeUpgradedTo = "https://example.com",
        )
        assertTrue(body, body.contains("This monitor's URL is http"))
        assertTrue(body, body.contains("https://example.com"))
        assertTrue(body, body.contains("Follow redirects"))
    }

    @Test
    fun `no redirect means no sentence about one`() {
        val body = TlsFailure.explanation(untrusted, hiddenService = false)
        assertFalse(body, body.contains("redirected"))
        assertFalse(body, body.contains("Follow redirects"))
    }

    @Test
    fun `a handshake failure that is not about trust says so`() {
        val headline = TlsFailure.headline(TlsFailure.Cause.Other, hiddenService = false)
        val body = TlsFailure.explanation(TlsFailure.Cause.Other, hiddenService = false)

        assertEquals("TLS handshake failed", headline)
        assertTrue(body, body.contains("protocol or cipher"))
        // Sending someone to the certificate settings for a cipher problem wastes
        // their afternoon.
        assertFalse(body, body.contains("Pinned key"))
    }

    @Test
    fun `every message is one the app could show in a notification`() {
        val cases = listOf(untrusted, mismatch, TlsFailure.Cause.Other)
        cases.forEach { cause ->
            listOf(true, false).forEach { hidden ->
                val headline = TlsFailure.headline(cause, hidden)
                assertFalse(headline, headline.isBlank())
                // A notification title truncates, and these are the one line a
                // user may ever read about the failure.
                assertTrue("$headline is ${headline.length} chars", headline.length <= 44)
                assertFalse(headline, headline.contains("\n"))
            }
        }
    }
}
