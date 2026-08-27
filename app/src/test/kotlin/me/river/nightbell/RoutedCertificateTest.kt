package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.TlsTrust
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A certificate on the far side of a SOCKS5 tunnel.
 *
 * This is issue #6's actual configuration, and until this file existed it was the
 * one combination nothing covered. [TlsTrustTest] proves the trust modes over a
 * direct connection. [SocksProxyTest] proves the routing, with a proxy that answers
 * plain HTTP itself and never dials anything. Neither says whether a custom trust
 * manager and a SOCKS route survive each other, and "both halves pass separately"
 * is exactly the reasoning that ships a broken feature.
 *
 * The address used here ends in `.onion`, so it also exercises the pieces that key
 * off that: the routing rule that refuses to send it out directly, and the failure
 * copy that explains why no CA can ever vouch for it. Those could only be tested
 * as a pure function before, because a real handshake needs a real server and a
 * real server cannot be called `.onion` without a proxy in front of it.
 *
 * The proxy maps the name onto a local TLS server, which is a Tor hidden service
 * with the six relays taken out: a name no resolver can answer, an origin this
 * device has no other route to, and the origin's own certificate arriving inside
 * the tunnel.
 */
class RoutedCertificateTest {

    private val onion =
        "nightbelltestaddressabcdefghijklmnopqrstuvwxyz234567abcd.onion"

    private fun monitor(trust: TlsTrust) = Monitor(
        id = "routed-tls",
        name = "Hidden service",
        kind = MonitorKind.HTTP_STATUS,
        url = "https://$onion/status",
        timeoutSeconds = 20,
        useProxy = true,
        tlsTrust = trust,
    )

    private fun checkerVia(proxy: TinySocks5) = HttpChecker(
        settingsFor = {
            GlobalSettings(
                socksProxyEnabled = true,
                socksProxyHost = "127.0.0.1",
                socksProxyPort = proxy.port,
            )
        },
    )

    /** Runs [body] with a TLS origin and a proxy that maps the onion name to it. */
    private fun withHiddenService(
        identity: TinyTls.Identity = TinyTls.selfSigned(),
        body: (HttpChecker, TinySocks5) -> Unit,
    ) {
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "hidden")
        }.use { origin ->
            TinySocks5 { host, _ ->
                // Only the name the test is about is reachable, so a check that
                // somehow bypassed the proxy cannot accidentally pass.
                require(host == onion) { "unexpected target $host" }
                InetSocketAddress("127.0.0.1", origin.port)
            }.use { proxy ->
                body(checkerVia(proxy), proxy)
            }
        }
    }

    @Test
    fun `a pinned monitor completes a handshake through the tunnel`() {
        withHiddenService { checker, proxy ->
            val result = runBlocking { checker.check(monitor(TlsTrust.PINNED)) }

            assertTrue(result.message, result.ok)
            assertEquals(200, result.statusCode)
            assertEquals("hidden", result.bodyPreview)
            // Recorded through the tunnel, which is the half that had never been
            // exercised: the certificate is read from the trust manager, and the
            // trust manager runs inside a socket the proxy opened.
            assertTrue(result.certSpki, result.certSpki.startsWith("sha256/"))

            // And the name never left this machine as a lookup. 3 is SOCKS5's
            // "domain name", so the proxy did the resolving.
            assertEquals(3, proxy.requestedAddressType)
            assertEquals(listOf("$onion:443"), proxy.requested)
        }
    }

    @Test
    fun `the pin is enforced on the next routed check`() {
        withHiddenService { checker, _ ->
            val first = runBlocking { checker.check(monitor(TlsTrust.PINNED)) }
            assertTrue(first.message, first.ok)

            val second = runBlocking {
                checker.check(monitor(TlsTrust.PINNED), certPin = first.certSpki)
            }
            assertTrue(second.message, second.ok)
        }
    }

    @Test
    fun `a different key behind the same onion address is refused`() {
        val recorded = TinyTls.selfSigned().let { first ->
            var pin = ""
            withHiddenService(first) { checker, _ ->
                pin = runBlocking { checker.check(monitor(TlsTrust.PINNED)) }.certSpki
            }
            pin
        }
        assertTrue(recorded.startsWith("sha256/"))

        // Same address, same tunnel, different key. For a hidden service the
        // address is the identity, so this is the case where pinning is the only
        // thing that can notice.
        withHiddenService(TinyTls.selfSigned()) { checker, _ ->
            val result = runBlocking {
                checker.check(monitor(TlsTrust.PINNED), certPin = recorded)
            }
            assertFalse(result.message, result.ok)
            assertEquals(FailureKind.TLS, result.failureKind)
            assertEquals("The certificate key changed", result.message)
        }
    }

    @Test
    fun `system trust through the tunnel explains the hidden service case`() {
        withHiddenService { checker, _ ->
            val result = runBlocking { checker.check(monitor(TlsTrust.SYSTEM)) }

            assertFalse(result.message, result.ok)
            assertEquals(FailureKind.TLS, result.failureKind)
            // The branch that could only be checked as a pure function before this
            // file existed: a real refusal, on a real handshake, on an address that
            // really does end in .onion.
            assertEquals("No CA vouches for this certificate", result.message)
            assertTrue(
                result.detail,
                result.detail.contains("No certificate authority issues for .onion"),
            )
            assertTrue(result.detail, result.detail.contains("Pinned key"))
        }
    }

    @Test
    fun `accepting any certificate also works through the tunnel`() {
        withHiddenService { checker, _ ->
            val result = runBlocking { checker.check(monitor(TlsTrust.ANY)) }
            assertTrue(result.message, result.ok)
            assertEquals("hidden", result.bodyPreview)
        }
    }

    @Test
    fun `a routed monitor with no proxy configured still refuses to leak the name`() {
        // The rule that predates this work, re-asserted because the certificate
        // modes must not have opened a path around it. A hidden service name
        // reaching the device's own resolver is the leak the whole feature exists
        // to prevent, and "the handshake would have failed anyway" is not a defence:
        // the lookup happens first.
        val checker = HttpChecker(settingsFor = { GlobalSettings(socksProxyEnabled = false) })
        val result = runBlocking { checker.check(monitor(TlsTrust.ANY)) }

        assertFalse(result.ok)
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertEquals("No proxy to route through", result.message)
    }
}
