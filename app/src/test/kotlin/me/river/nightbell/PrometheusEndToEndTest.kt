package me.river.nightbell

import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.AlertSeverity
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.MetricComparison
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PromQueryRule
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole Prometheus path over a real socket.
 *
 * [PrometheusCheckTest] pins what a body means. This pins the parts only a real
 * request can show: that the URL the checker builds is the one that arrives,
 * that the credentials reach the wire, and that the verdict survives being
 * turned into a [me.river.nightbell.domain.CheckResult].
 */
class PrometheusEndToEndTest {

    private val checker = HttpChecker()

    private fun monitor(url: String, watch: PrometheusWatch, headers: List<HeaderPair> = emptyList()) =
        Monitor(
            id = "prom",
            kind = MonitorKind.PROMETHEUS,
            url = url,
            prometheus = watch,
            headers = headers,
            timeoutSeconds = 5,
        )

    @Test
    fun `a node exporter scrape reports the value on the dashboard line`() {
        TinyHttpServer {
            TinyHttpServer.Response(body = OpenMetricsTest.NODE_EXPORTER)
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(
                            source = PrometheusSource.METRICS,
                            metricName = "node_load1",
                            comparison = MetricComparison.BELOW,
                            threshold = 2.0,
                        ),
                    ),
                )
            }
            assertTrue(result.message, result.ok)
            // Not "HTTP 200 in 3ms". The number is the thing somebody opened the
            // monitor to find out.
            assertEquals("node_load1 = 0.29", result.message)
            assertEquals(200, result.statusCode)
        }
    }

    @Test
    fun `a breach comes back as a metric failure with advice`() {
        val hot = OpenMetricsTest.NODE_EXPORTER.replace("node_load1 0.29", "node_load1 9.5")
        TinyHttpServer { TinyHttpServer.Response(body = hot) }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(
                            source = PrometheusSource.METRICS,
                            metricName = "node_load1",
                            comparison = MetricComparison.BELOW,
                            threshold = 2.0,
                        ),
                    ),
                )
            }
            assertFalse(result.ok)
            assertEquals(FailureKind.METRIC, result.failureKind)
            assertTrue(result.message, result.message.contains("9.5"))
            // The specific advice has to survive into the result, not fall back
            // to the category's generic hint.
            assertEquals(result.hint, result.advice)
            assertTrue(result.advice, result.advice.contains("threshold"))
        }
    }

    @Test
    fun `a promql check sends the query to the query API and reads the envelope`() {
        TinyHttpServer {
            TinyHttpServer.Response(
                body = PrometheusCheckTest.EMPTY_VECTOR,
                contentType = "application/json",
            )
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.baseUrl,
                        PrometheusWatch(
                            source = PrometheusSource.QUERY,
                            query = """up{job="api"} == 0""",
                            queryRule = PromQueryRule.RETURNS_NOTHING,
                        ),
                    ),
                )
            }
            assertTrue(result.message, result.ok)
            assertEquals("Query matched nothing", result.message)

            val request = server.received.single()
            assertEquals("GET", request.method)
            assertTrue(request.path, request.path.startsWith("/api/v1/query?query="))
            // What arrived has to decode back to what was typed.
            val sent = URLDecoder.decode(request.path.substringAfter("query="), "UTF-8")
            assertEquals("""up{job="api"} == 0""", sent)
        }
    }

    @Test
    fun `a returned series takes the monitor down`() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusCheckTest.DOWN_VECTOR, contentType = "application/json")
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.baseUrl,
                        PrometheusWatch(source = PrometheusSource.QUERY, query = "up == 0"),
                    ),
                )
            }
            assertFalse(result.ok)
            assertEquals(FailureKind.METRIC, result.failureKind)
            assertTrue(result.message, result.message.contains("api:9090"))
        }
    }

    @Test
    fun `a pasted API path is not doubled on the wire`() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusCheckTest.EMPTY_VECTOR, contentType = "application/json")
        }.use { server ->
            runBlocking {
                checker.check(
                    monitor(
                        server.url("/api/v1/query"),
                        PrometheusWatch(source = PrometheusSource.QUERY, query = "up"),
                    ),
                )
            }
            val path = server.received.single().path
            assertEquals(path, 1, Regex("/api/v1/query").findAll(path).count())
        }
    }

    @Test
    fun `alertmanager alerts page, and the request asks the server to filter`() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusCheckTest.ALERTS, contentType = "application/json")
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.baseUrl,
                        PrometheusWatch(
                            source = PrometheusSource.ALERTMANAGER,
                            minimumSeverity = AlertSeverity.WARNING,
                        ),
                    ),
                )
            }
            assertFalse(result.ok)
            assertEquals(FailureKind.METRIC, result.failureKind)
            assertTrue(result.message, result.message.contains("DiskFillingUp (critical)"))

            val path = server.received.single().path
            assertTrue(path, path.startsWith("/api/v2/alerts?"))
            assertTrue(path, path.contains("silenced=false"))
        }
    }

    @Test
    fun `basic auth reaches the wire`() {
        TinyHttpServer { TinyHttpServer.Response(body = "node_load1 0.1") }.use { server ->
            runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(
                            source = PrometheusSource.METRICS,
                            metricName = "node_load1",
                            username = "aladdin",
                            password = "opensesame",
                        ),
                    ),
                )
            }
            val auth = server.received.single().headers.entries
                .firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
            assertEquals("Basic YWxhZGRpbjpvcGVuc2VzYW1l", auth)
        }
    }

    @Test
    fun `a header the user typed wins over the convenience fields`() {
        // Somebody who has pasted an Authorization header has said what they want
        // more specifically than two text fields can.
        TinyHttpServer { TinyHttpServer.Response(body = "node_load1 0.1") }.use { server ->
            runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(
                            source = PrometheusSource.METRICS,
                            metricName = "node_load1",
                            username = "ignored",
                            password = "ignored",
                        ),
                        headers = listOf(HeaderPair("Authorization", "Bearer abc123")),
                    ),
                )
            }
            val auth = server.received.single().headers.entries
                .firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
            assertEquals("Bearer abc123", auth)
        }
    }

    @Test
    fun `no credentials means no Authorization header at all`() {
        TinyHttpServer { TinyHttpServer.Response(body = "node_load1 0.1") }.use { server ->
            runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(source = PrometheusSource.METRICS, metricName = "node_load1"),
                    ),
                )
            }
            assertNull(
                server.received.single().headers.entries
                    .firstOrNull { it.key.equals("authorization", ignoreCase = true) },
            )
        }
    }

    @Test
    fun `a 401 from the endpoint explains itself`() {
        TinyHttpServer {
            TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "denied")
        }.use { server ->
            val result = runBlocking {
                checker.check(
                    monitor(
                        server.url("/metrics"),
                        PrometheusWatch(source = PrometheusSource.METRICS, metricName = "node_load1"),
                    ),
                )
            }
            assertFalse(result.ok)
            assertEquals(FailureKind.STATUS, result.failureKind)
            assertTrue(result.message, result.message.contains("metrics endpoint answered 401"))
            assertTrue(result.advice, result.advice.contains("username and password"))
        }
    }

    @Test
    fun `a Prometheus monitor never sends the body a request draft left behind`() {
        // Switching kind clears these, but a monitor restored from a backup
        // written by an older build can still be carrying them.
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusCheckTest.EMPTY_VECTOR, contentType = "application/json")
        }.use { server ->
            runBlocking {
                checker.check(
                    monitor(
                        server.baseUrl,
                        PrometheusWatch(source = PrometheusSource.QUERY, query = "up"),
                    ).copy(
                        method = me.river.nightbell.domain.HttpMethod.POST,
                        body = """{"stale":true}""",
                    ),
                )
            }
            val request = server.received.single()
            assertEquals("GET", request.method)
            assertEquals("", request.body)
        }
    }
}
