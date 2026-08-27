package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #6, reproduced before it was designed for.
 *
 * The report is titled "self-signed https certs?" and the URL in it is plain
 * `http://` on a `.onion`. Both halves are true, and the thing that joins them is
 * the redirect: `Monitor.followRedirects` defaults on, `HttpChecker` wires it to
 * `followSslRedirects` as well, so an endpoint answering plain HTTP with a 3xx to
 * `https://` turns a monitor the user set up as http into a TLS check they never
 * asked for. When the certificate at the other end has no CA behind it, which is
 * every Tor hidden service and most homelab boxes, the check fails on a handshake
 * the user has no reason to expect.
 *
 * [reproducesTheReportedFailureExactly] is the one to read. It builds that shape
 * out of two local servers and gets the same failure the reporter got, so the
 * diagnosis rests on something that runs rather than on reasoning about it.
 */
class SelfSignedCertificateTest {

    private val checker = HttpChecker()

    /**
     * Whether a failure detail describes an untrusted chain, on either stack.
     *
     * The reporter's device said "Trust anchor for certification path not found",
     * which is Conscrypt. This suite runs on the JVM, where the same rejection
     * reads "PKIX path building failed ... unable to find valid certification path
     * to requested target". One condition, two providers, two strings.
     *
     * Worth stating loudly because it rules something out: no user-facing message
     * in this app may be chosen by matching on that text. The kind comes from the
     * exception class, and the reason comes from what the app already knows about
     * the monitor.
     */
    private fun describesAnUntrustedChain(detail: String): Boolean =
        detail.contains("Trust anchor for certification path not found") ||
            detail.contains("unable to find valid certification path")

    private fun monitor(url: String, block: Monitor.() -> Monitor = { this }) = Monitor(
        id = "tls",
        name = "TLS",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 10,
    ).block()

    @Test
    fun `a self-signed endpoint fails on the trust anchor`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "ok")
        }.use { server ->
            val result = runBlocking { checker.check(monitor(server.url("/health"))) }

            assertFalse(result.message, result.ok)
            assertEquals(FailureKind.TLS, result.failureKind)
            // The raw exception is kept, after the explanation the app adds.
            assertTrue(result.detail, result.detail.contains("SSLHandshakeException"))
            assertTrue(result.detail, describesAnUntrustedChain(result.detail))
        }
    }

    @Test
    fun `reproducesTheReportedFailureExactly`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "the real page")
        }.use { secure ->
            TinyHttpServer { request ->
                TinyHttpServer.Response(
                    code = 301,
                    reason = "Moved Permanently",
                    extraHeaders = mapOf("Location" to secure.url(request.path)),
                )
            }.use { plain ->
                // Set up exactly as the reporter set theirs up: an http:// URL.
                val target = monitor(plain.url("/"))
                assertTrue(target.url.startsWith("http://"))

                val result = runBlocking { checker.check(target) }

                // And it fails on a certificate, which is the part that made no
                // sense from the outside.
                assertFalse(result.message, result.ok)
                assertEquals(FailureKind.TLS, result.failureKind)
                assertTrue(result.detail, describesAnUntrustedChain(result.detail))
                assertEquals(1, plain.received.size)
            }
        }
    }

    @Test
    fun `turning redirects off leaves the monitor on the scheme it was given`() {
        val identity = TinyTls.selfSigned()
        TinyHttpServer(identity.serverSocketFactory()) {
            TinyHttpServer.Response(body = "the real page")
        }.use { secure ->
            TinyHttpServer {
                TinyHttpServer.Response(
                    code = 301,
                    reason = "Moved Permanently",
                    extraHeaders = mapOf("Location" to secure.url("/")),
                )
            }.use { plain ->
                val result = runBlocking {
                    checker.check(
                        monitor(plain.url("/")) {
                            copy(followRedirects = false)
                        },
                    )
                }
                // No handshake happens at all, so the certificate never comes into
                // it. Worth holding: it is the workaround a user has today, and it
                // has to keep working after the trust modes land.
                assertEquals(301, result.statusCode)
                assertEquals(FailureKind.STATUS, result.failureKind)
                assertEquals(0L, result.certExpiresAt)
            }
        }
    }
}
