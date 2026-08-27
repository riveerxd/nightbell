package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.TlsTrust
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three certificate trust modes, against a real handshake.
 *
 * Every test here talks to a local server holding a genuinely self-signed
 * certificate, so the trust decision is made by the platform's own TLS stack
 * rather than by a mock agreeing with the test. That matters more than usual: the
 * failure mode being guarded against is a mode that *looks* enforced and quietly
 * accepts anything, and nothing but a real rejection can rule it out.
 */
class TlsTrustTest {

    private val checker = HttpChecker()

    private fun monitor(url: String, trust: TlsTrust) = Monitor(
        id = "tls",
        name = "TLS",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 10,
        tlsTrust = trust,
    )

    @Test
    fun `SYSTEM refuses a self-signed certificate`() {
        TinyHttpServer(TinyTls.selfSigned().serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.SYSTEM))
            }
            assertFalse(result.message, result.ok)
            assertEquals(FailureKind.TLS, result.failureKind)
            assertEquals("Certificate not trusted", result.message)
        }
    }

    @Test
    fun `ANY accepts a self-signed certificate and still reads its expiry`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.ANY))
            }
            assertTrue(result.message, result.ok)
            assertEquals(200, result.statusCode)
            assertEquals("ok", result.bodyPreview)
            // Validation is off; observation is not. The certificate track keeps
            // working, and only the alert is suppressed, which the engine does.
            assertEquals(identity.leaf.notAfter.time, result.certExpiresAt)
            assertEquals("Nightbell test", result.certIssuer)
        }
    }

    @Test
    fun `PINNED with nothing pinned yet accepts and reports the key`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.PINNED), certPin = "")
            }
            assertTrue(result.message, result.ok)
            assertTrue(result.certSpki, result.certSpki.startsWith("sha256/"))
        }
    }

    @Test
    fun `PINNED accepts the key it recorded`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val first = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.PINNED))
            }
            assertTrue(first.message, first.ok)

            val second = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.PINNED), certPin = first.certSpki)
            }
            assertTrue(second.message, second.ok)
            assertEquals(first.certSpki, second.certSpki)
        }
    }

    @Test
    fun `PINNED refuses a different key on the same hostname`() {
        val first = TinyTls.selfSigned()
        val second = TinyTls.selfSigned()
        assertNotEquals(
            "the fixture must produce two distinct keys or this proves nothing",
            first.leaf.publicKey,
            second.leaf.publicKey,
        )

        val recorded = TinyHttpServer(first.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking { checker.check(monitor(server.url("/"), TlsTrust.PINNED)) }.certSpki
        }
        assertTrue(recorded.startsWith("sha256/"))

        // Same name, same self-signed shape, different key. This is a certificate
        // swap, and it is the thing pinning exists to notice.
        TinyHttpServer(second.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.PINNED), certPin = recorded)
            }
            assertFalse(result.message, result.ok)
            assertEquals(FailureKind.TLS, result.failureKind)
            assertEquals("The certificate key changed", result.message)
            // Both keys in the detail, so someone can tell a renewal apart from
            // something they should worry about.
            assertTrue(result.detail, result.detail.contains(recorded))
            assertTrue(result.detail, result.detail.contains("\"Trust the new key\""))
        }
    }

    @Test
    fun `a pinned monitor is not fooled by a valid certificate for another key`() {
        // The subtler half of the same rule. A pin must beat *any* other
        // certificate, not merely an untrusted one, or the mode degrades to
        // "system trust, plus a pin that only matters when system trust fails".
        val pinned = TinyTls.selfSigned()
        val impostor = TinyTls.selfSigned()
        val recorded = TinyHttpServer(pinned.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking { checker.check(monitor(server.url("/"), TlsTrust.PINNED)) }.certSpki
        }

        TinyHttpServer(impostor.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.PINNED), certPin = recorded)
            }
            assertFalse(result.message, result.ok)
            assertEquals("The certificate key changed", result.message)
        }
    }

    @Test
    fun `the pin is ignored unless the monitor asked to be pinned`() {
        val identity = TinyTls.selfSigned()
        val other = TinyTls.selfSigned()
        val stalePin = TinyHttpServer(other.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            runBlocking { checker.check(monitor(server.url("/"), TlsTrust.PINNED)) }.certSpki
        }

        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            // ANY means no checks, and a leftover pin from some earlier mode must
            // not quietly become one of them.
            val any = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.ANY), certPin = stalePin)
            }
            assertTrue(any.message, any.ok)

            // SYSTEM still refuses on the anchor, and reports that rather than a
            // pin mismatch it was never asked to check.
            val system = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.SYSTEM), certPin = stalePin)
            }
            assertFalse(system.ok)
            assertEquals("Certificate not trusted", system.message)
        }
    }

    @Test
    fun `plain http is untouched by any of the modes`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok") }.use { server ->
            TlsTrust.entries.forEach { trust ->
                val result = runBlocking { checker.check(monitor(server.url("/"), trust)) }
                assertTrue("$trust: ${result.message}", result.ok)
                // No handshake, so nothing to record, and a monitor set to PINNED
                // on an http URL must never arm itself against nothing.
                assertEquals("$trust", "", result.certSpki)
                assertEquals("$trust", 0L, result.certExpiresAt)
            }
        }
    }

    @Test
    fun `an untrusted chain is recognised by type, not by message text`() {
        // The reason TlsFailureCopyTest can exist at all. The checker has to sort
        // a handshake failure into a cause without reading the exception text,
        // because Conscrypt and the JVM word the same rejection differently. This
        // asserts the sorting works on the stack the suite runs on; the wording it
        // produces is covered on its own, and the hidden-service branch with it.
        TinyHttpServer(TinyTls.selfSigned().serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/"), TlsTrust.SYSTEM))
            }
            assertEquals(FailureKind.TLS, result.failureKind)
            // Sorted as an untrusted chain, which is what picks the self-signed
            // explanation over the generic handshake one.
            assertEquals("Certificate not trusted", result.message)
            assertTrue(result.detail, result.detail.contains("self-signed certificate or a private CA"))
            // The raw exception is still there, last, for anyone who wants it.
            assertTrue(result.detail, result.detail.contains("SSLHandshakeException"))
        }
    }
}
