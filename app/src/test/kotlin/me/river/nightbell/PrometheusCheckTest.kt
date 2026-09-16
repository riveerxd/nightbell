package me.river.nightbell

import me.river.nightbell.domain.AlertSeverity
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.MetricComparison
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PromQueryRule
import me.river.nightbell.domain.PrometheusCheck
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Prometheus monitor decides, given a body.
 *
 * Every payload here is the real shape: a node_exporter scrape, a Prometheus
 * `/api/v1/query` envelope including its 200-with-status-error case, and an
 * Alertmanager v2 alert with the `status` block it actually carries.
 */
class PrometheusCheckTest {

    private fun monitor(watch: PrometheusWatch, url: String = "https://prom.example.com") = Monitor(
        id = "p",
        kind = MonitorKind.PROMETHEUS,
        url = url,
        prometheus = watch,
    )

    // ---------------------------------------------------------------- metrics

    private val metricsWatch = PrometheusWatch(
        source = PrometheusSource.METRICS,
        metricName = "node_load1",
        comparison = MetricComparison.BELOW,
        threshold = 2.0,
    )

    @Test
    fun `a load below the threshold is up, and says the number`() {
        val verdict = PrometheusCheck.evaluate(monitor(metricsWatch), 200, OpenMetricsTest.NODE_EXPORTER)
        assertTrue(verdict.message, verdict.passed)
        // The point of the whole feature: opening the monitor tells you the
        // current value, not that an HTTP request succeeded.
        assertEquals("node_load1 = 0.29", verdict.message)
    }

    @Test
    fun `a load above the threshold is down and names the value`() {
        val hot = OpenMetricsTest.NODE_EXPORTER.replace("node_load1 0.29", "node_load1 4.21")
        val verdict = PrometheusCheck.evaluate(monitor(metricsWatch), 200, hot)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.METRIC, verdict.kind)
        assertTrue(verdict.message, verdict.message.contains("node_load1 = 4.21"))
        assertTrue(verdict.message, verdict.message.contains("< 2"))
    }

    @Test
    fun `every matching series has to pass`() {
        // The multi-series case from issue 12: one CPU out of range fails the
        // monitor even though the others are fine.
        val watch = metricsWatch.copy(
            metricName = "node_cpu_seconds_total",
            labelFilter = """mode="idle"""",
            comparison = MetricComparison.ABOVE,
            threshold = 137_400.0,
        )
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, OpenMetricsTest.NODE_EXPORTER)
        assertFalse(verdict.passed)
        // cpu 1 sits at 137301.09, under the floor; cpu 0 is above it.
        assertTrue(verdict.message, verdict.message.contains("cpu=\"1\""))
    }

    @Test
    fun `a filter that matches nothing is a failure, not a pass`() {
        // The quiet disaster this guards: a collector switched off, or a metric
        // renamed, would otherwise leave a monitor permanently green.
        val watch = metricsWatch.copy(metricName = "node_load15")
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, OpenMetricsTest.NODE_EXPORTER)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.METRIC, verdict.kind)
        assertTrue(verdict.message, verdict.message.contains("node_load15"))
    }

    @Test
    fun `a label filter excluding everything says so differently`() {
        val watch = metricsWatch.copy(
            metricName = "node_cpu_seconds_total",
            labelFilter = """mode="nonsense"""",
        )
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, OpenMetricsTest.NODE_EXPORTER)
        assertFalse(verdict.passed)
        assertTrue(verdict.detail, verdict.detail.contains("label filter excluded"))
    }

    @Test
    fun `the _total suggestion reaches the user`() {
        val watch = metricsWatch.copy(metricName = "node_cpu_seconds")
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, OpenMetricsTest.NODE_EXPORTER)
        // In the detail, not the hint. The names come off the monitored server,
        // and `hint` is classified as text this app ships. See LogSentinelTest.
        assertTrue(verdict.detail, verdict.detail.contains("node_cpu_seconds_total"))
        assertTrue(verdict.hint, verdict.hint.contains("_total suffix"))
    }

    @Test
    fun `NaN never counts as healthy`() {
        // NaN means the exporter had no reading. Reading that as "fine" is how a
        // monitor goes quiet exactly when it had something to say.
        val body = "node_load1 NaN"
        val below = PrometheusCheck.evaluate(monitor(metricsWatch), 200, body)
        assertFalse(below.passed)
        assertTrue(below.hint, below.hint.contains("NaN"))

        val notEqual = metricsWatch.copy(comparison = MetricComparison.NOT_EQUAL_TO, threshold = 0.0)
        assertFalse(PrometheusCheck.evaluate(monitor(notEqual), 200, body).passed)
    }

    @Test
    fun `an HTML page is not a metrics endpoint and is not a threshold breach`() {
        val verdict = PrometheusCheck.evaluate(
            monitor(metricsWatch),
            200,
            "<html><body>Sign in</body></html>",
        )
        assertFalse(verdict.passed)
        assertEquals(FailureKind.BODY, verdict.kind)
        assertTrue(verdict.message, verdict.message.contains("isn't a metrics endpoint"))
    }

    @Test
    fun `a 401 talks about credentials rather than about a status code`() {
        val verdict = PrometheusCheck.evaluate(monitor(metricsWatch), 401, "")
        assertFalse(verdict.passed)
        assertEquals(FailureKind.STATUS, verdict.kind)
        assertTrue(verdict.hint, verdict.hint.contains("username and password"))
    }

    // ----------------------------------------------------------------- promql

    private val queryWatch = PrometheusWatch(
        source = PrometheusSource.QUERY,
        query = """up{job="api"} == 0""",
        queryRule = PromQueryRule.RETURNS_NOTHING,
    )

    @Test
    fun `an empty result is healthy for an alerting-shaped query`() {
        // Issue 13's ask, exactly: write the query as the alert condition and
        // anything it returns is the alert.
        val verdict = PrometheusCheck.evaluate(monitor(queryWatch), 200, EMPTY_VECTOR)
        assertTrue(verdict.message, verdict.passed)
        assertEquals("Query matched nothing", verdict.message)
    }

    @Test
    fun `a returned series fires and names it`() {
        val verdict = PrometheusCheck.evaluate(monitor(queryWatch), 200, DOWN_VECTOR)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.METRIC, verdict.kind)
        assertTrue(verdict.message, verdict.message.contains("""instance="api:9090""""))
    }

    @Test
    fun `a query Prometheus refuses is a config failure, not an outage`() {
        // Prometheus answers a bad query with HTTP 200 and status "error", so
        // the status code alone never sees the most common user mistake.
        val verdict = PrometheusCheck.evaluate(monitor(queryWatch), 200, QUERY_ERROR)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.BAD_CONFIG, verdict.kind)
        assertTrue(verdict.detail, verdict.detail.contains("bad_data"))
        assertTrue(verdict.detail, verdict.detail.contains("unexpected end of input"))
    }

    @Test
    fun `the inverted rule wants a series and fails without one`() {
        val watch = queryWatch.copy(queryRule = PromQueryRule.RETURNS_SOMETHING)
        assertTrue(PrometheusCheck.evaluate(monitor(watch), 200, DOWN_VECTOR).passed)

        val empty = PrometheusCheck.evaluate(monitor(watch), 200, EMPTY_VECTOR)
        assertFalse(empty.passed)
        assertTrue(empty.hint, empty.hint.contains("stopped being scraped"))
    }

    @Test
    fun `comparing the returned value`() {
        val watch = queryWatch.copy(
            queryRule = PromQueryRule.VALUE_PASSES,
            comparison = MetricComparison.AT_LEAST,
            threshold = 1.0,
        )
        // up == 0 in the fixture, so "at least 1" fails on it.
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, DOWN_VECTOR)
        assertFalse(verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("= 0"))
        assertTrue(verdict.message, verdict.message.contains(">= 1"))
    }

    @Test
    fun `a matrix is read at its newest sample`() {
        // Pasting a query with a range selector in it out of the expression
        // browser produces this, and refusing it would blame the user for a gap
        // in this parser.
        val watch = queryWatch.copy(queryRule = PromQueryRule.RETURNS_SOMETHING)
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, MATRIX)
        assertTrue(verdict.message, verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("= 3"))
    }

    @Test
    fun `a scalar result is read too`() {
        val watch = queryWatch.copy(
            queryRule = PromQueryRule.VALUE_PASSES,
            comparison = MetricComparison.BELOW,
            threshold = 10.0,
        )
        assertTrue(PrometheusCheck.evaluate(monitor(watch), 200, SCALAR).passed)
    }

    @Test
    fun `a body that is not the API says which URL to give`() {
        val verdict = PrometheusCheck.evaluate(monitor(queryWatch), 200, "not json at all")
        assertFalse(verdict.passed)
        assertEquals(FailureKind.BODY, verdict.kind)
        assertTrue(verdict.hint, verdict.hint.contains("/api/v1/query"))
    }

    // ----------------------------------------------------------- alertmanager

    private val alertWatch = PrometheusWatch(source = PrometheusSource.ALERTMANAGER)

    @Test
    fun `an empty alert list is healthy`() {
        val verdict = PrometheusCheck.evaluate(monitor(alertWatch), 200, "[]")
        assertTrue(verdict.message, verdict.passed)
        assertEquals("Nothing firing", verdict.message)
    }

    @Test
    fun `a firing alert pages, worst severity named first`() {
        val verdict = PrometheusCheck.evaluate(monitor(alertWatch), 200, ALERTS)
        assertFalse(verdict.passed)
        assertEquals(FailureKind.METRIC, verdict.kind)
        assertTrue(verdict.message, verdict.message.startsWith("2 firing: DiskFillingUp (critical)"))
        assertTrue(verdict.detail, verdict.detail.contains("Disk will fill in 4 hours"))
    }

    @Test
    fun `a silenced alert stays quiet by default`() {
        // The one in the fixture with a silencedBy entry must not come through:
        // waking somebody at 04:00 about an alert they silenced at 23:00 is the
        // fastest way to get the app uninstalled.
        val verdict = PrometheusCheck.evaluate(monitor(alertWatch), 200, ALERTS)
        assertFalse(verdict.message, verdict.message.contains("SilencedNoise"))
    }

    @Test
    fun `a silenced alert can be asked for`() {
        val watch = alertWatch.copy(includeSilenced = true)
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, ALERTS)
        assertTrue(verdict.message, verdict.message.contains("3 firing"))
    }

    @Test
    fun `the severity floor filters`() {
        val watch = alertWatch.copy(minimumSeverity = AlertSeverity.CRITICAL)
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, ALERTS)
        assertFalse(verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("Firing: DiskFillingUp"))
        assertFalse(verdict.message, verdict.message.contains("HighLatency"))
    }

    @Test
    fun `an unrankable severity is never swallowed by the floor`() {
        // Labelling severity `page` and `ticket` is a common house style. Hiding
        // what this app cannot rank would be hiding outages to keep a chip
        // selector tidy.
        val watch = alertWatch.copy(minimumSeverity = AlertSeverity.CRITICAL)
        val body = """[{"labels":{"alertname":"Odd","severity":"ticket"},"status":{"state":"active","silencedBy":[],"inhibitedBy":[]}}]"""
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, body)
        assertFalse(verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("Odd"))
    }

    @Test
    fun `a label filter narrows to one team`() {
        val watch = alertWatch.copy(alertLabelFilter = """team="storage"""")
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, ALERTS)
        assertFalse(verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("Firing: DiskFillingUp"))
    }

    @Test
    fun `alerts held but filtered out report as healthy and say how many`() {
        val watch = alertWatch.copy(alertLabelFilter = """team="nobody"""")
        val verdict = PrometheusCheck.evaluate(monitor(watch), 200, ALERTS)
        assertTrue(verdict.message, verdict.passed)
        assertTrue(verdict.message, verdict.message.contains("none past the filter"))
    }

    @Test
    fun `an inhibited alert stays quiet by default`() {
        val body = """[{"labels":{"alertname":"Downstream","severity":"warning"},"status":{"state":"suppressed","silencedBy":[],"inhibitedBy":["abc"]}}]"""
        assertTrue(PrometheusCheck.evaluate(monitor(alertWatch), 200, body).passed)
        val watch = alertWatch.copy(includeInhibited = true)
        assertFalse(PrometheusCheck.evaluate(monitor(watch), 200, body).passed)
    }

    // --------------------------------------------------------------- requests

    @Test
    fun `a metrics URL is fetched exactly as typed`() {
        val m = monitor(metricsWatch, url = "http://node:9100/metrics")
        assertEquals("http://node:9100/metrics", PrometheusCheck.requestUrl(m))
    }

    @Test
    fun `the query path is added once, whichever way the URL was typed`() {
        // Pasting the full API path is the obvious thing to do and used to
        // produce /api/v1/query/api/v1/query.
        val plain = PrometheusCheck.requestUrl(monitor(queryWatch, "https://prom.example.com"))
        val trailing = PrometheusCheck.requestUrl(monitor(queryWatch, "https://prom.example.com/"))
        val full = PrometheusCheck.requestUrl(monitor(queryWatch, "https://prom.example.com/api/v1/query"))
        assertEquals(plain, trailing)
        assertEquals(plain, full)
        assertTrue(plain, plain.startsWith("https://prom.example.com/api/v1/query?query="))
        assertEquals(1, Regex("/api/v1/query").findAll(plain).count())
    }

    @Test
    fun `the query is URL encoded`() {
        val url = PrometheusCheck.requestUrl(monitor(queryWatch))
        assertTrue(url, url.contains("up%7Bjob%3D%22api%22%7D"))
        assertFalse(url, url.contains("{"))
    }

    @Test
    fun `alertmanager asks the server to exclude what this monitor excludes`() {
        val url = PrometheusCheck.requestUrl(monitor(alertWatch, "https://am.example.com"))
        assertTrue(url, url.startsWith("https://am.example.com/api/v2/alerts?"))
        assertTrue(url, url.contains("silenced=false"))
        assertTrue(url, url.contains("inhibited=false"))

        val loud = monitor(alertWatch.copy(includeSilenced = true), "https://am.example.com")
        assertTrue(PrometheusCheck.requestUrl(loud).contains("silenced=true"))
    }

    @Test
    fun `basic auth is built, and absent when there is nothing to build it from`() {
        assertEquals(null, PrometheusCheck.basicAuthHeader(PrometheusWatch()))
        val header = PrometheusCheck.basicAuthHeader(
            PrometheusWatch(username = "aladdin", password = "opensesame"),
        )
        // The example from RFC 7617, so the encoding is checked against something
        // written down rather than against itself.
        assertEquals("Basic YWxhZGRpbjpvcGVuc2VzYW1l", header)
    }

    @Test
    fun `a threshold is written back the way it was typed`() {
        assertEquals("2", PrometheusWatch.formatValue(2.0))
        assertEquals("0.5", PrometheusWatch.formatValue(0.5))
        assertEquals("NaN", PrometheusWatch.formatValue(Double.NaN))
    }

    companion object {
        val EMPTY_VECTOR = PrometheusPayloads.EMPTY_VECTOR
        val DOWN_VECTOR = PrometheusPayloads.DOWN_VECTOR
        val QUERY_ERROR = PrometheusPayloads.QUERY_ERROR
        val MATRIX = PrometheusPayloads.MATRIX
        val SCALAR = PrometheusPayloads.SCALAR
        val ALERTS = PrometheusPayloads.ALERTS
    }
}
