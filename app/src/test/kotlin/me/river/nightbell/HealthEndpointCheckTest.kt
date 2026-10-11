package me.river.nightbell

import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthStatus
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A health endpoint read over a real socket, with no sign-in in the way.
 *
 * [HealthFormatTest] pins what a body means. This pins what only a request can
 * show: the headers that go out, the status code not being the verdict, the
 * size limit applied to bytes on the wire, and the server's `Date` being the
 * clock staleness is judged by.
 */
class HealthEndpointCheckTest {

    private val checker = HttpChecker()

    private fun monitor(url: String, watch: HealthWatch = HealthWatch(), headers: List<HeaderPair> = emptyList()) =
        Monitor(id = "health", kind = MonitorKind.HEALTH_ENDPOINT, url = url, health = watch, headers = headers, timeoutSeconds = 5)

    private fun check(monitor: Monitor, authorization: String? = null) =
        runBlocking { checker.check(monitor, authorization = authorization) }

    @Test
    fun `a report is asked for as json or text and read`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.MINIMAL, contentType = "application/json") }.use { server ->
            val result = check(monitor(server.url("/health")))

            assertTrue(result.message, result.ok)
            assertEquals("application/json, text/plain", server.received.single().headers["accept"])
            assertEquals("GET", server.received.single().method)
        }
    }

    @Test
    fun `a 503 carrying a report is that report`() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 503, reason = "Service Unavailable", body = HealthPayloads.FULL)
        }.use { server ->
            val result = check(monitor(server.url("/health")))

            assertFalse(result.ok)
            assertEquals(FailureKind.REPORTED_DOWN, result.failureKind)
            assertEquals(503, result.statusCode)
            assertEquals("Card payments are failing", result.message)
            assertEquals(3, result.health!!.components.size)
            assertEquals(2, result.health!!.components[0].log.size)
        }
    }

    @Test
    fun `the web app's fallback page is not a health endpoint`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.SPA_FALLBACK, contentType = "text/html") }.use { server ->
            val result = check(monitor(server.url("/health")))

            assertEquals(FailureKind.NOT_HEALTH_FORMAT, result.failureKind)
            assertTrue(result.detail.startsWith("<!doctype html>"))
        }
    }

    @Test
    fun `a body over the limit is refused without being parsed`() {
        val huge = HealthPayloads.MINIMAL + " ".repeat(HealthFormat.MAX_BODY_BYTES)
        TinyHttpServer { TinyHttpServer.Response(body = huge) }.use { server ->
            val result = check(monitor(server.url("/health")))

            assertEquals(FailureKind.NOT_HEALTH_FORMAT, result.failureKind)
            assertEquals("Response too large to be a health document", result.message)
        }
    }

    @Test
    fun `staleness is judged by the server's own clock`() {
        val doc = """{"healthReport":1,"status":"up","checkedAt":"2026-10-09T12:00:00Z"}"""
        TinyHttpServer {
            TinyHttpServer.Response(body = doc, extraHeaders = mapOf("Date" to "Fri, 09 Oct 2026 12:30:00 GMT"))
        }.use { server ->
            val result = check(monitor(server.url("/health"), HealthWatch(staleAfterMinutes = 10)))

            assertFalse(result.ok)
            assertEquals("Report is 30 minutes old", result.message)
        }
    }

    @Test
    fun `a sign-in from the vault replaces a typed authorization header`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.MINIMAL) }.use { server ->
            check(
                monitor(server.url("/health"), headers = listOf(HeaderPair("Authorization", "Bearer typed"))),
                authorization = "Bearer from-vault",
            )

            assertEquals("Bearer from-vault", server.received.single().headers["authorization"])
        }
    }

    @Test
    fun `a 401 with no report is a refusal the sign-in layer can act on`() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Missing or invalid Authorization header")
        }.use { server ->
            val result = check(monitor(server.url("/api/health")))

            assertTrue(HealthFormat.isRefusal(result))
            assertEquals("This endpoint needs a sign-in (401)", result.message)
        }
    }

    @Test
    fun `degraded answers and is flagged`() {
        TinyHttpServer { TinyHttpServer.Response(body = HealthPayloads.ASPNET_DEGRADED) }.use { server ->
            val result = check(monitor(server.url("/health")))

            assertTrue(result.ok)
            assertTrue(result.degraded)
            assertEquals(HealthStatus.DEGRADED, result.health!!.components[0].status)
        }
    }

    @Test
    fun `plain Healthy from an unconfigured ASP NET check is up`() {
        TinyHttpServer { TinyHttpServer.Response(body = "Healthy") }.use { server ->
            assertTrue(check(monitor(server.url("/api/health"))).ok)
        }
    }
}
