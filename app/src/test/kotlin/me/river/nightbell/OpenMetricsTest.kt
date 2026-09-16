package me.river.nightbell

import me.river.nightbell.domain.OpenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exposition parser, against the shapes a real scrape actually contains.
 *
 * The payload in [NODE_EXPORTER] is copied from a node_exporter response rather
 * than invented, including the scientific notation and the `# EOF` terminator,
 * because every awkward case here was awkward in the wild first.
 */
class OpenMetricsTest {

    @Test
    fun `a bare metric with no labels`() {
        val sample = OpenMetrics.parseLine("node_load1 0.29")
        assertNotNull(sample)
        assertEquals("node_load1", sample!!.name)
        assertEquals(0.29, sample.value, 1e-9)
        assertTrue(sample.labels.isEmpty())
        assertNull(sample.timestampMs)
    }

    @Test
    fun `labels are read in order and unescaped`() {
        val sample = OpenMetrics.parseLine(
            """http_requests_total{method="post",path="/a,b",note="say \"hi\"",win="C:\\tmp"} 1027""",
        )
        assertNotNull(sample)
        assertEquals("post", sample!!.labels["method"])
        // A comma inside a quoted value is the case every regex-based parser
        // gets wrong, and a path label containing one is ordinary.
        assertEquals("/a,b", sample.labels["path"])
        assertEquals("""say "hi"""", sample.labels["note"])
        assertEquals("""C:\tmp""", sample.labels["win"])
        assertEquals(1027.0, sample.value, 1e-9)
    }

    @Test
    fun `an unknown escape keeps its backslash`() {
        val sample = OpenMetrics.parseLine("""a{p="x\qy"} 1""")
        assertEquals("""x\qy""", sample!!.labels["p"])
    }

    @Test
    fun `positive infinity is a value, not a parse failure`() {
        // The last bucket of every histogram, so a parser that drops this drops a
        // line out of nearly every scrape it will ever read.
        val sample = OpenMetrics.parseLine("""http_duration_bucket{le="+Inf"} 144""")
        assertNotNull(sample)
        assertEquals(144.0, sample!!.value, 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, OpenMetrics.parseValue("+Inf"))
        assertEquals(Double.NEGATIVE_INFINITY, OpenMetrics.parseValue("-Inf"))
        assertTrue(OpenMetrics.parseValue("NaN")!!.isNaN())
    }

    @Test
    fun `scientific notation survives`() {
        val sample = OpenMetrics.parseLine("node_filesystem_avail_bytes 4.0763392e+10")
        assertEquals(40_763_392_000.0, sample!!.value, 1.0)
    }

    @Test
    fun `the two timestamp units are told apart by the decimal point`() {
        // Prometheus text writes integer milliseconds, OpenMetrics writes seconds
        // with a fraction. Reading one as the other is off by a thousand.
        assertEquals(1395066363000L, OpenMetrics.parseTimestampMs("1395066363000"))
        assertEquals(1395066363000L, OpenMetrics.parseTimestampMs("1395066363.000"))
        assertEquals(1395066363000L, OpenMetrics.parseLine("m 1 1395066363000")!!.timestampMs)
        assertEquals(1395066363000L, OpenMetrics.parseLine("m 1 1395066363.000")!!.timestampMs)
    }

    @Test
    fun `comments and the EOF terminator are not samples`() {
        val samples = OpenMetrics.parse(NODE_EXPORTER)
        assertTrue(samples.none { it.name.startsWith("#") })
        // `node_after_eof` sits past the terminator in the fixture on purpose.
        assertTrue(samples.none { it.name == "node_after_eof" })
        assertEquals(6, samples.size)
    }

    @Test
    fun `an HTML error page parses as nothing at all`() {
        // The whole reason the parser stays strict. This is what pointing a
        // monitor at a login page looks like, and it has to be distinguishable
        // from a scrape whose metric is simply missing.
        val html = "<!DOCTYPE html>\n<html><body><h1>401 Unauthorized</h1></body></html>"
        assertTrue(OpenMetrics.parse(html).isEmpty())
    }

    @Test
    fun `one unreadable line does not discard the scrape`() {
        val body = "good_metric 1\nthis line is nonsense\nalso_good 2"
        val samples = OpenMetrics.parse(body)
        assertEquals(listOf("good_metric", "also_good"), samples.map { it.name })
    }

    @Test
    fun `selecting by name and label`() {
        val samples = OpenMetrics.parse(NODE_EXPORTER)
        val idle = OpenMetrics.select(
            samples,
            "node_cpu_seconds_total",
            OpenMetrics.parseSelector("""mode="idle""""),
        )
        assertEquals(2, idle.size)
        assertTrue(idle.all { it.labels["mode"] == "idle" })
    }

    @Test
    fun `every PromQL operator is understood`() {
        val labels = mapOf("mode" to "idle", "cpu" to "0")
        assertTrue(OpenMetrics.parseSelector("""mode="idle"""").matches(labels))
        assertFalse(OpenMetrics.parseSelector("""mode!="idle"""").matches(labels))
        assertTrue(OpenMetrics.parseSelector("""mode=~"id.*"""").matches(labels))
        assertFalse(OpenMetrics.parseSelector("""mode!~"id.*"""").matches(labels))
        // Anchored, the way Prometheus anchors it: a partial match is not a match.
        assertFalse(OpenMetrics.parseSelector("""mode=~"id"""").matches(labels))
    }

    @Test
    fun `an absent label reads as empty, as Prometheus reads it`() {
        val labels = mapOf("cpu" to "0")
        assertTrue(OpenMetrics.parseSelector("""job=""""").matches(labels))
        assertFalse(OpenMetrics.parseSelector("""job="api"""").matches(labels))
    }

    @Test
    fun `the filter field forgives what a person types into it`() {
        val labels = mapOf("mode" to "idle", "cpu" to "0")
        // Pasted braces, no quotes, single quotes, a trailing comma left behind
        // while deleting the last clause, and stray whitespace.
        assertTrue(OpenMetrics.parseSelector("""{mode="idle", cpu="0"}""").matches(labels))
        assertTrue(OpenMetrics.parseSelector("mode=idle").matches(labels))
        assertTrue(OpenMetrics.parseSelector("mode='idle'").matches(labels))
        assertTrue(OpenMetrics.parseSelector("""mode="idle", """).matches(labels))
        assertTrue(OpenMetrics.parseSelector("   mode = \"idle\"  ").matches(labels))
    }

    @Test
    fun `a broken filter says so instead of matching everything`() {
        // Silently matching everything is the dangerous failure: the monitor
        // would watch series it was never asked to and look like it worked.
        val nonsense = OpenMetrics.parseSelector("just some words")
        assertFalse(nonsense.isValid)
        assertNotNull(nonsense.error)

        val badRegex = OpenMetrics.parseSelector("""mode=~"(unclosed"""")
        assertFalse(badRegex.isValid)
        assertTrue(badRegex.error!!.contains("regular expression"))
    }

    @Test
    fun `a blank filter matches everything and is not an error`() {
        val empty = OpenMetrics.parseSelector("   ")
        assertTrue(empty.isValid)
        assertTrue(empty.matches(mapOf("anything" to "at all")))
    }

    @Test
    fun `the _total suffix gets suggested rather than left as a mystery`() {
        // A counter's rule name and its exposed name differ by a suffix, so the
        // name copied off a dashboard finds nothing. Saying so is the difference
        // between a two-minute fix and giving up on the feature.
        val samples = OpenMetrics.parse(NODE_EXPORTER)
        assertEquals(listOf("node_cpu_seconds_total"), OpenMetrics.suggestNames(samples, "node_cpu_seconds"))
        assertTrue(OpenMetrics.suggestNames(samples, "node_load1").isEmpty())
    }

    companion object {
        /** See [PrometheusPayloads]. Named here because every test below reads it. */
        val NODE_EXPORTER = PrometheusPayloads.NODE_EXPORTER
    }
}
