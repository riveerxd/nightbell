package me.river.nightbell

import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.MetricComparison
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PrometheusCheck
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The website tells people to watch Uptime Kuma through its `/metrics`, with
 * `monitor_status != 0`. These pin that advice to what Kuma really serves.
 *
 * The body is a scrape of Uptime Kuma 2.5.9 in Docker with two monitors, one
 * pointed at itself and one at a closed port, cut down to the series that
 * matter plus two that share the endpoint, so the filter has something to skip.
 */
class UptimeKumaMetricsTest {

    private fun monitor(watch: PrometheusWatch) = Monitor(
        id = "kuma",
        kind = MonitorKind.PROMETHEUS,
        url = "https://kuma.example.com/metrics",
        prometheus = watch,
    )

    private val anyDown = PrometheusWatch(
        source = PrometheusSource.METRICS,
        metricName = "monitor_status",
        comparison = MetricComparison.NOT_EQUAL_TO,
        threshold = 0.0,
        password = "uk1_example",
    )

    private fun status(website: Int, api: Int) = KUMA
        .replace("{website}", website.toString())
        .replace("{api}", api.toString())

    @Test
    fun `every kuma monitor up is up, and counts them`() {
        val verdict = PrometheusCheck.evaluate(monitor(anyDown), 200, status(website = 1, api = 1))
        assertTrue(verdict.message, verdict.passed)
        assertEquals("2 series, all != 0", verdict.message)
    }

    @Test
    fun `one kuma monitor down pages, and names it`() {
        val verdict = PrometheusCheck.evaluate(monitor(anyDown), 200, status(website = 1, api = 0))
        assertFalse(verdict.passed)
        assertEquals(FailureKind.METRIC, verdict.kind)
        assertTrue(verdict.message, verdict.message.contains("""monitor_name="API""""))
    }

    @Test
    fun `pending and maintenance do not page`() {
        // Kuma reports 2 while a monitor retries and 3 inside a maintenance
        // window. The page says != 0 rather than = 1 so neither wakes anybody.
        val verdict = PrometheusCheck.evaluate(monitor(anyDown), 200, status(website = 2, api = 3))
        assertTrue(verdict.message, verdict.passed)
    }

    @Test
    fun `a label filter watches one kuma monitor and ignores the rest`() {
        val website = anyDown.copy(labelFilter = """monitor_name="Website"""")
        val verdict = PrometheusCheck.evaluate(monitor(website), 200, status(website = 1, api = 0))
        assertTrue(verdict.message, verdict.passed)
    }

    @Test
    fun `the api key goes out as the basic auth password`() {
        // Kuma ignores the username, so the page says to leave it blank and put
        // the key in the password.
        val header = PrometheusCheck.basicAuthHeader(anyDown)!!
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals(":uk1_example", decoded)
    }

    companion object {
        // The two status values are holes for status() to fill: the scrape had
        // Website at 1 and API at 0.
        private val KUMA = """
            # HELP monitor_response_time Monitor Response Time (ms)
            # TYPE monitor_response_time gauge
            monitor_response_time{monitor_id="1",monitor_name="Website",monitor_type="http",monitor_url="http://localhost:3001/",monitor_hostname="null",monitor_port="null"} 6
            monitor_response_time{monitor_id="2",monitor_name="API",monitor_type="http",monitor_url="http://127.0.0.1:9/",monitor_hostname="null",monitor_port="null"} -1
            # HELP monitor_status Monitor Status (1 = UP, 0= DOWN, 2= PENDING, 3= MAINTENANCE)
            # TYPE monitor_status gauge
            monitor_status{monitor_id="1",monitor_name="Website",monitor_type="http",monitor_url="http://localhost:3001/",monitor_hostname="null",monitor_port="null"} {website}
            monitor_status{monitor_id="2",monitor_name="API",monitor_type="http",monitor_url="http://127.0.0.1:9/",monitor_hostname="null",monitor_port="null"} {api}
            # HELP app_version The service version by package.json
            # TYPE app_version gauge
            app_version{version="2.5.9",major="2",minor="5",patch="9"} 1
        """.trimIndent() + "\n"
    }
}
