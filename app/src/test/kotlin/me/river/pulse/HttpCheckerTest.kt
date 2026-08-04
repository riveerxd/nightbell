package me.river.pulse

import me.river.pulse.data.check.HttpChecker
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.BodyAssertion
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.HeaderPair
import me.river.pulse.domain.HttpMethod
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.StatusExpectation
import me.river.pulse.domain.StatusMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCheckerTest {

    private val checker = HttpChecker()

    private fun monitor(
        url: String,
        block: Monitor.() -> Monitor = { this },
    ) = Monitor(
        id = "test",
        name = "Test",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 5,
    ).block()

    @Test
    fun `200 response passes the default expectation`() {
        TinyHttpServer { TinyHttpServer.Response(body = "pong") }.use { server ->
            val result = runBlocking { checker.check(monitor(server.url("/health"))) }
            assertTrue(result.message, result.ok)
            assertEquals(200, result.statusCode)
            assertEquals(FailureKind.NONE, result.failureKind)
            assertEquals("pong", result.bodyPreview)
        }
    }

    @Test
    fun `a plain HTTP response carries no certificate, over a real socket`() {
        // The guard that matters most in the certificate track: no handshake means
        // no opinion. If this ever returned something non-zero, every http:// monitor
        // would start reporting an expiry date derived from nothing.
        TinyHttpServer { TinyHttpServer.Response(body = "ok") }.use { server ->
            val result = runBlocking { checker.check(monitor(server.url("/plain"))) }
            assertTrue(result.message, result.ok)
            assertEquals(0L, result.certExpiresAt)
            assertEquals("", result.certIssuer)
        }
    }

    @Test
    fun `unexpected status is reported as a status failure`() {
        TinyHttpServer { TinyHttpServer.Response(code = 503, reason = "Service Unavailable", body = "nope") }
            .use { server ->
                val result = runBlocking { checker.check(monitor(server.url("/down"))) }
                assertFalse(result.ok)
                assertEquals(503, result.statusCode)
                assertEquals(FailureKind.STATUS, result.failureKind)
                assertTrue(result.message.contains("503"))
            }
    }

    @Test
    fun `a configured non-200 expectation passes on that code`() {
        TinyHttpServer { TinyHttpServer.Response(code = 418, reason = "I'm a teapot", body = "short and stout") }
            .use { server ->
                val result = runBlocking {
                    checker.check(
                        monitor(server.url("/teapot")) {
                            copy(status = StatusExpectation(StatusMode.EXACT, code = 418))
                        },
                    )
                }
                assertTrue(result.message, result.ok)
                assertEquals(418, result.statusCode)
            }
    }

    @Test
    fun `body contains assertion drives pass and fail`() {
        TinyHttpServer { TinyHttpServer.Response(body = """{"status":"ok","version":3}""") }.use { server ->
            val passing = runBlocking {
                checker.check(
                    monitor(server.url("/status")) {
                        copy(assertion = BodyAssertion(AssertionMode.CONTAINS, value = "\"status\":\"ok\""))
                    },
                )
            }
            assertTrue(passing.message, passing.ok)

            val failing = runBlocking {
                checker.check(
                    monitor(server.url("/status")) {
                        copy(assertion = BodyAssertion(AssertionMode.CONTAINS, value = "degraded"))
                    },
                )
            }
            assertFalse(failing.ok)
            assertEquals(FailureKind.BODY, failing.failureKind)
        }
    }

    @Test
    fun `json field assertion reads the parsed response`() {
        TinyHttpServer {
            TinyHttpServer.Response(
                body = """{"data":{"health":"green","nodes":[{"id":"a"},{"id":"b"}]}}""",
                contentType = "application/json",
            )
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(server.url("/api")) {
                        copy(
                            assertion = BodyAssertion(
                                AssertionMode.JSON_FIELD_EQUALS,
                                value = "green",
                                jsonPath = "data.health",
                            ),
                        )
                    },
                )
            }
            assertTrue(result.message, result.ok)

            val nodeCheck = runBlocking {
                checker.check(
                    monitor(server.url("/api")) {
                        copy(
                            assertion = BodyAssertion(
                                AssertionMode.JSON_FIELD_EQUALS,
                                value = "b",
                                jsonPath = "data.nodes[1].id",
                            ),
                        )
                    },
                )
            }
            assertTrue(nodeCheck.message, nodeCheck.ok)
        }
    }

    @Test
    fun `post sends the configured body content type and headers`() {
        val server = TinyHttpServer { request ->
            TinyHttpServer.Response(body = "received:${request.body}")
        }
        server.use {
            val result = runBlocking {
                checker.check(
                    monitor(server.url("/echo")) {
                        copy(
                            kind = MonitorKind.ADVANCED_REQUEST,
                            method = HttpMethod.POST,
                            body = """{"ping":true}""",
                            contentType = "application/json",
                            headers = listOf(HeaderPair("X-Pulse-Test", "yes")),
                            assertion = BodyAssertion(AssertionMode.CONTAINS, value = "received:{\"ping\":true}"),
                        )
                    },
                )
            }
            assertTrue(result.message, result.ok)
            val request = server.received.last()
            assertEquals("POST", request.method)
            assertEquals("yes", request.headers["x-pulse-test"])
            assertTrue(request.headers["content-type"]!!.contains("application/json"))
            assertEquals("""{"ping":true}""", request.body)
        }
    }

    @Test
    fun `user agent is always present`() {
        val server = TinyHttpServer { TinyHttpServer.Response(body = "ok") }
        server.use {
            runBlocking { checker.check(monitor(server.url("/ua"))) }
            assertEquals(HttpChecker.USER_AGENT, server.received.last().headers["user-agent"])
        }
    }

    @Test
    fun `timeout is classified as a timeout not a generic failure`() {
        TinyHttpServer { TinyHttpServer.Response(body = "slow", delayMs = 2_500) }.use { server ->
            val result = runBlocking {
                checker.check(monitor(server.url("/slow")) { copy(timeoutSeconds = 1) })
            }
            assertFalse(result.ok)
            assertEquals(FailureKind.TIMEOUT, result.failureKind)
            assertTrue(result.message.contains("1s"))
        }
    }

    @Test
    fun `unresolvable host is classified as DNS`() {
        val result = runBlocking {
            checker.check(monitor("https://this-host-should-never-resolve.pulse.invalid/") { copy(timeoutSeconds = 3) })
        }
        assertFalse(result.ok)
        assertEquals(FailureKind.DNS, result.failureKind)
    }

    @Test
    fun `refused connection is classified as connect`() {
        // Bind then immediately close so the port is almost certainly free.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val result = runBlocking {
            checker.check(monitor("http://127.0.0.1:$port/") { copy(timeoutSeconds = 3) })
        }
        assertFalse(result.ok)
        assertEquals(FailureKind.CONNECT, result.failureKind)
    }

    @Test
    fun `invalid url fails before any request is attempted`() {
        val result = runBlocking { checker.check(monitor("not-a-url")) }
        assertFalse(result.ok)
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertEquals(0L, result.latencyMs)
    }

    @Test
    fun `head request skips body assertions gracefully`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ignored") }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(server.url("/head")) {
                        copy(kind = MonitorKind.ADVANCED_REQUEST, method = HttpMethod.HEAD)
                    },
                )
            }
            assertTrue(result.message, result.ok)
            assertEquals("", result.bodyPreview)
        }
    }

    @Test
    fun `redirects are followed when enabled and surfaced when not`() {
        val server = TinyHttpServer { request ->
            if (request.path == "/redirect") {
                TinyHttpServer.Response(
                    code = 302,
                    reason = "Found",
                    body = "",
                    extraHeaders = mapOf("Location" to "/final"),
                )
            } else {
                TinyHttpServer.Response(body = "final destination")
            }
        }
        server.use {
            val followed = runBlocking {
                checker.check(
                    monitor(server.url("/redirect")) {
                        copy(assertion = BodyAssertion(AssertionMode.CONTAINS, value = "final destination"))
                    },
                )
            }
            assertTrue(followed.message, followed.ok)

            val notFollowed = runBlocking {
                checker.check(
                    monitor(server.url("/redirect")) {
                        copy(
                            followRedirects = false,
                            status = StatusExpectation(StatusMode.EXACT, code = 302),
                        )
                    },
                )
            }
            assertTrue(notFollowed.message, notFollowed.ok)
            assertEquals(302, notFollowed.statusCode)
        }
    }

    @Test
    fun `latency is measured and non-negative`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 120) }.use { server ->
            val result = runBlocking { checker.check(monitor(server.url("/latency"))) }
            assertTrue(result.ok)
            assertTrue("latency was ${result.latencyMs}", result.latencyMs >= 100)
        }
    }
}
