package me.river.nightbell

import me.river.nightbell.domain.AssertionMode
import me.river.nightbell.domain.BodyAssertion
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PromQueryRule
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import me.river.nightbell.domain.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the setup form refuses to save, and what it only warns about. */
class PrometheusValidationTest {

    private fun monitor(watch: PrometheusWatch, url: String = "https://prom.example.com") = Monitor(
        id = "p",
        name = "Prom",
        kind = MonitorKind.PROMETHEUS,
        url = url,
        prometheus = watch,
    )

    private fun errorOn(monitor: Monitor, field: Validation.Field) =
        Validation.report(monitor).notes
            .firstOrNull { it.field == field && it.severity == Validation.Severity.ERROR }

    @Test
    fun `a metrics monitor needs a metric name`() {
        val blank = monitor(PrometheusWatch(source = PrometheusSource.METRICS))
        assertNotNull(errorOn(blank, Validation.Field.METRIC))
        assertFalse(Validation.report(blank).isValid)

        val named = monitor(PrometheusWatch(source = PrometheusSource.METRICS, metricName = "node_load1"))
        assertNull(errorOn(named, Validation.Field.METRIC))
        assertTrue(Validation.report(named).errors.toString(), Validation.report(named).isValid)
    }

    @Test
    fun `a selector typed into the metric field is caught where it happens`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.METRICS,
            metricName = """node_load1{mode="idle"}""",
        )
        val note = errorOn(monitor(watch), Validation.Field.METRIC)
        assertNotNull(note)
        assertTrue(note!!.message, note.message.contains("filter below"))
    }

    @Test
    fun `a broken label filter blocks the save`() {
        // Its own field, so the metric name's own error is not suppressed along
        // with it: the filter's note is held back until the field is left, and
        // sharing one field would have held back the other.
        val watch = PrometheusWatch(
            source = PrometheusSource.METRICS,
            metricName = "node_load1",
            labelFilter = "nonsense words here",
        )
        assertNotNull(errorOn(monitor(watch), Validation.Field.LABELS))
        assertNull(errorOn(monitor(watch), Validation.Field.METRIC))
        assertFalse(Validation.report(monitor(watch)).isValid)
    }

    @Test
    fun `a broken alert filter blocks the save too`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.ALERTMANAGER,
            alertLabelFilter = "team",
        )
        assertNotNull(errorOn(monitor(watch), Validation.Field.LABELS))
    }

    @Test
    fun `a promql monitor needs a query`() {
        val blank = monitor(PrometheusWatch(source = PrometheusSource.QUERY))
        assertNotNull(errorOn(blank, Validation.Field.PROMQL))

        val asked = monitor(PrometheusWatch(source = PrometheusSource.QUERY, query = "up == 0"))
        assertNull(errorOn(asked, Validation.Field.PROMQL))
    }

    @Test
    fun `switching source does not demand the other source's fields`() {
        // The whole point of keeping both sets of fields: somebody who tried
        // Alertmanager and came back finds their metric name still typed, and
        // that convenience must not turn into a form that cannot be saved.
        val watch = PrometheusWatch(
            source = PrometheusSource.ALERTMANAGER,
            metricName = "",
            query = "",
        )
        assertTrue(Validation.report(monitor(watch)).errors.toString(), Validation.report(monitor(watch)).isValid)
    }

    @Test
    fun `a stale body assertion cannot block a form that does not show it`() {
        // A monitor converted from a request kind, or restored from an older
        // backup, can be carrying an assertion whose field is not on the
        // Prometheus screen. Failing validation on it would be unfixable.
        val watch = PrometheusWatch(source = PrometheusSource.METRICS, metricName = "node_load1")
        val carrying = monitor(watch).copy(
            assertion = BodyAssertion(mode = AssertionMode.CONTAINS, value = ""),
        )
        assertTrue(Validation.report(carrying).errors.toString(), Validation.report(carrying).isValid)
    }

    @Test
    fun `a password with no username is refused`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.METRICS,
            metricName = "node_load1",
            password = "hunter2",
        )
        assertNotNull(errorOn(monitor(watch), Validation.Field.TOKEN))
    }

    @Test
    fun `basic auth over plain http warns without blocking`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.METRICS,
            metricName = "node_load1",
            username = "u",
            password = "p",
        )
        val report = Validation.report(monitor(watch, url = "http://node:9100/metrics"))
        val note = report.notes.first { it.field == Validation.Field.TOKEN }
        assertEquals(Validation.Severity.WARNING, note.severity)
        assertTrue(note.message, note.message.contains("clear text"))
        assertTrue(report.isValid)
    }

    @Test
    fun `counting silenced alerts is a warning, because it undoes a decision`() {
        val watch = PrometheusWatch(source = PrometheusSource.ALERTMANAGER, includeSilenced = true)
        val note = Validation.report(monitor(watch)).notes
            .first { it.field == Validation.Field.ALERTS && it.severity == Validation.Severity.WARNING }
        assertTrue(note.message, note.message.contains("no longer quieten"))
    }

    @Test
    fun `a severity floor explains what it does not hide`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.ALERTMANAGER,
            minimumSeverity = me.river.nightbell.domain.AlertSeverity.CRITICAL,
        )
        val note = Validation.report(monitor(watch)).notes
            .first { it.field == Validation.Field.ALERTS }
        assertEquals(Validation.Severity.HINT, note.severity)
        assertTrue(note.message, note.message.contains("still"))
    }

    @Test
    fun `the cadence and latency rules still apply to a Prometheus monitor`() {
        // The GitHub kind returns early and loses these. Prometheus must not.
        val watch = PrometheusWatch(source = PrometheusSource.METRICS, metricName = "node_load1")
        val bad = monitor(watch).copy(timeoutSeconds = 300, latencySloMs = -1)
        val report = Validation.report(bad)
        assertNotNull(report.notes.firstOrNull { it.field == Validation.Field.TIMEOUT })
        assertNotNull(report.notes.firstOrNull { it.field == Validation.Field.LATENCY_SLO })
        assertFalse(report.isValid)
    }

    @Test
    fun `an unusable URL is still an unusable URL`() {
        val watch = PrometheusWatch(source = PrometheusSource.QUERY, query = "up")
        assertNotNull(errorOn(monitor(watch, url = "prom.example.com"), Validation.Field.URL))
    }

    @Test
    fun `a value-comparing query keeps the threshold rules`() {
        val watch = PrometheusWatch(
            source = PrometheusSource.QUERY,
            query = "up",
            queryRule = PromQueryRule.VALUE_PASSES,
            threshold = Double.NaN,
        )
        assertNotNull(errorOn(monitor(watch), Validation.Field.METRIC))
    }
}

/**
 * The reading a passing Prometheus check leaves behind for the dashboard.
 *
 * Its own field rather than [MonitorRuntime.lastMessage], because that one means
 * "why is this not OK" and is cleared on every success on purpose.
 */
class PrometheusReadingTest {

    private fun result(ok: Boolean, message: String) = me.river.nightbell.domain.CheckResult(
        ok = ok,
        latencyMs = 5,
        statusCode = 200,
        message = message,
        at = 1_000L,
    )

    @Test
    fun `a passing check records what it read`() {
        val runtime = me.river.nightbell.domain.AlertDecider.advance(
            previous = me.river.nightbell.domain.MonitorRuntime(),
            result = result(ok = true, message = "node_load1 = 0.29"),
            historyDepth = 10,
        )
        assertEquals("node_load1 = 0.29", runtime.lastReading)
        // The invariant three other call sites depend on has to survive.
        assertEquals("", runtime.lastMessage)
    }

    @Test
    fun `a failure clears the reading instead of leaving it beside the failure`() {
        val up = me.river.nightbell.domain.AlertDecider.advance(
            previous = me.river.nightbell.domain.MonitorRuntime(),
            result = result(ok = true, message = "node_load1 = 0.29"),
            historyDepth = 10,
        )
        val down = me.river.nightbell.domain.AlertDecider.advance(
            previous = up,
            result = result(ok = false, message = "node_load1 = 9.1, expected < 2"),
            historyDepth = 10,
        )
        // A stale good number sitting under a live failure reads as the app
        // contradicting itself.
        assertEquals("", down.lastReading)
        assertEquals("node_load1 = 9.1, expected < 2", down.lastMessage)
    }
}

/** How a metric value is written back, which is also how it is typed. */
class MetricValueFormatTest {

    @Test
    fun `whole numbers lose their decimal point`() {
        assertEquals("2", PrometheusWatch.formatValue(2.0))
        assertEquals("0", PrometheusWatch.formatValue(0.0))
        assertEquals("-5", PrometheusWatch.formatValue(-5.0))
    }

    @Test
    fun `float noise does not reach the screen`() {
        assertEquals("0.29", PrometheusWatch.formatValue(0.29))
        assertEquals("137464.2", PrometheusWatch.formatValue(137_464.2))
        assertEquals("0.1", PrometheusWatch.formatValue(0.1))
    }

    @Test
    fun `nothing is ever shown in exponent notation`() {
        // node_memory_MemAvailable_bytes came back as "3.4359738368E9" in the
        // metric list, which is not a number anybody reads and not one anybody
        // could have typed into the threshold field beside it.
        val big = PrometheusWatch.formatValue(3.4359738368e9)
        assertEquals("3435973836.8", big)
        assertFalse(big, big.contains("E", ignoreCase = true))

        val whole = PrometheusWatch.formatValue(4.0763392e10)
        assertEquals("40763392000", whole)

        val small = PrometheusWatch.formatValue(1e-9)
        assertEquals("0.000000001", small)
        assertFalse(small, small.contains("E", ignoreCase = true))
    }

    @Test
    fun `everything it writes can be read back`() {
        // It seeds the threshold field, so a value it formats has to parse again
        // or reopening a monitor would silently reset the threshold.
        listOf(0.29, 2.0, -5.5, 3.4359738368e9, 1e-9, 137_464.2, 0.0).forEach { value ->
            val text = PrometheusWatch.formatValue(value)
            assertEquals(text, value, text.toDoubleOrNull() ?: Double.NaN, kotlin.math.abs(value) * 1e-9 + 1e-12)
        }
    }

    @Test
    fun `the three special values keep the format's own spelling`() {
        assertEquals("NaN", PrometheusWatch.formatValue(Double.NaN))
        assertEquals("+Inf", PrometheusWatch.formatValue(Double.POSITIVE_INFINITY))
        assertEquals("-Inf", PrometheusWatch.formatValue(Double.NEGATIVE_INFINITY))
    }
}
