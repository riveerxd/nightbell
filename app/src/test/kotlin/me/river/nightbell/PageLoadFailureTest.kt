package me.river.nightbell

import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.PageLoadFailure
import me.river.nightbell.domain.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car park case, for page monitors.
 *
 * Every one of these failed before the mapping existed: a page monitor reported
 * RENDER for every way of not loading, RENDER says the network worked, and so
 * the reachability probe was never spent on the one failure it was written for.
 */
class PageLoadFailureTest {

    @Test
    fun `a dead radio classifies as a connection failure`() {
        // Chromium folds ERR_INTERNET_DISCONNECTED, ERR_CONNECTION_RESET and
        // ERR_ADDRESS_UNREACHABLE into this one code before the client sees it.
        assertEquals(FailureKind.CONNECT, PageLoadFailure.kindOf(PageLoadFailure.CONNECT))
        assertEquals(FailureKind.CONNECT, PageLoadFailure.kindOf(PageLoadFailure.IO))
        assertEquals(FailureKind.DNS, PageLoadFailure.kindOf(PageLoadFailure.HOST_LOOKUP))
        assertEquals(FailureKind.TIMEOUT, PageLoadFailure.kindOf(PageLoadFailure.TIMEOUT))
    }

    @Test
    fun `the connection failures are the ones worth confirming`() {
        val confirmable = listOf(
            PageLoadFailure.CONNECT,
            PageLoadFailure.IO,
            PageLoadFailure.HOST_LOOKUP,
            PageLoadFailure.TIMEOUT,
        )
        confirmable.forEach { code ->
            assertTrue(
                "code $code should be worth a reachability probe",
                Reachability.neverReachedAnything(PageLoadFailure.kindOf(code)),
            )
        }
    }

    @Test
    fun `a refused certificate is still a certificate problem`() {
        assertEquals(FailureKind.TLS, PageLoadFailure.kindOf(PageLoadFailure.FAILED_SSL_HANDSHAKE))
        assertFalse(Reachability.neverReachedAnything(FailureKind.TLS))
    }

    @Test
    fun `an address the browser will not load is a configuration problem`() {
        assertEquals(FailureKind.BAD_CONFIG, PageLoadFailure.kindOf(PageLoadFailure.BAD_URL))
        assertEquals(FailureKind.BAD_CONFIG, PageLoadFailure.kindOf(PageLoadFailure.UNSUPPORTED_SCHEME))
    }

    @Test
    fun `anything unrecognised still pages`() {
        // The direction to fail in: an unclassified load failure keeps the old
        // behaviour and reaches the user.
        val unrecognised = listOf(
            PageLoadFailure.NONE,
            PageLoadFailure.UNKNOWN,
            PageLoadFailure.AUTHENTICATION,
            PageLoadFailure.PROXY_AUTHENTICATION,
            PageLoadFailure.REDIRECT_LOOP,
            PageLoadFailure.TOO_MANY_REQUESTS,
            PageLoadFailure.UNSAFE_RESOURCE,
            PageLoadFailure.FILE_NOT_FOUND,
            -999,
        )
        unrecognised.forEach { code ->
            assertEquals("code $code", FailureKind.RENDER, PageLoadFailure.kindOf(code))
            assertFalse(Reachability.neverReachedAnything(PageLoadFailure.kindOf(code)))
        }
    }

    @Test
    fun `the headline says which half broke`() {
        assertEquals("Couldn't connect to the page", PageLoadFailure.headline(PageLoadFailure.CONNECT))
        assertEquals("Can't resolve the host", PageLoadFailure.headline(PageLoadFailure.HOST_LOOKUP))
        assertEquals("Page failed to load", PageLoadFailure.headline(PageLoadFailure.UNKNOWN))
    }
}
