package me.river.nightbell

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AssertionMode
import me.river.nightbell.domain.BodyAssertion
import me.river.nightbell.domain.ElementMode
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.VibrationStyle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private fun base(url: String = "https://example.com") = Monitor(id = "m1", url = url)

    @Test
    fun `well formed https urls pass cleanly`() {
        assertNull(Validation.urlNote("https://example.com"))
        assertNull(Validation.urlNote("https://api.example.com:8443/v1/health?deep=1"))
    }

    @Test
    fun `plain http is allowed but warned about`() {
        val note = Validation.urlNote("http://example.com")
        assertNotNull(note)
        assertEquals(Validation.Severity.WARNING, note!!.severity)
    }

    @Test
    fun `bad urls produce blocking errors`() {
        listOf("", "example.com", "ftp://example.com", "https://", "https://ex ample.com")
            .forEach { candidate ->
                val note = Validation.urlNote(candidate)
                assertNotNull("expected an error for '$candidate'", note)
                assertEquals(
                    "expected ERROR for '$candidate'",
                    Validation.Severity.ERROR,
                    note!!.severity,
                )
            }
    }

    @Test
    fun `a plain status monitor with a good url is valid`() {
        assertTrue(Validation.report(base()).isValid)
    }

    @Test
    fun `header without a name is an error`() {
        val report = Validation.report(base().copy(headers = listOf(HeaderPair("", "value"))))
        assertFalse(report.isValid)
        assertEquals(Validation.Field.HEADERS, report.errors.first().field)
    }

    @Test
    fun `illegal header characters are rejected`() {
        val report = Validation.report(base().copy(headers = listOf(HeaderPair("Bad Header", "v"))))
        assertFalse(report.isValid)
    }

    @Test
    fun `duplicate headers only warn`() {
        val report = Validation.report(
            base().copy(headers = listOf(HeaderPair("Accept", "a"), HeaderPair("accept", "b"))),
        )
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.WARNING, report.of(Validation.Field.HEADERS)!!.severity)
    }

    @Test
    fun `assertions requiring a value block until filled in`() {
        val empty = Validation.report(
            base().copy(assertion = BodyAssertion(AssertionMode.CONTAINS, value = "")),
        )
        assertFalse(empty.isValid)

        val filled = Validation.report(
            base().copy(assertion = BodyAssertion(AssertionMode.CONTAINS, value = "ok")),
        )
        assertTrue(filled.isValid)
    }

    @Test
    fun `invalid regex is caught before the monitor can be saved`() {
        val report = Validation.report(
            base().copy(assertion = BodyAssertion(AssertionMode.REGEX, value = "a(b")),
        )
        assertFalse(report.isValid)
        assertEquals(Validation.Field.ASSERTION, report.errors.first().field)
    }

    @Test
    fun `json assertions require a path`() {
        val report = Validation.report(
            base().copy(
                assertion = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "ok", jsonPath = ""),
            ),
        )
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.field == Validation.Field.JSON_PATH })
    }

    @Test
    fun `status codes outside the http range are rejected`() {
        val report = Validation.report(
            base().copy(status = StatusExpectation(StatusMode.EXACT, code = 999)),
        )
        assertFalse(report.isValid)
    }

    @Test
    fun `element monitors need a captured selector`() {
        val uncaptured = Validation.report(
            base().copy(kind = MonitorKind.WEBSITE_ELEMENT, element = ElementTarget()),
        )
        assertFalse(uncaptured.isValid)
        assertTrue(uncaptured.errors.any { it.field == Validation.Field.ELEMENT })

        val captured = Validation.report(
            base().copy(
                kind = MonitorKind.WEBSITE_ELEMENT,
                element = ElementTarget(cssSelector = "#price", mode = ElementMode.EXISTS),
            ),
        )
        assertTrue(captured.isValid)
    }

    @Test
    fun `element text modes need expected text`() {
        val report = Validation.report(
            base().copy(
                kind = MonitorKind.WEBSITE_ELEMENT,
                element = ElementTarget(
                    cssSelector = "#price",
                    mode = ElementMode.TEXT_EQUALS,
                    expectedText = "",
                ),
            ),
        )
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.field == Validation.Field.ELEMENT_TEXT })
    }

    @Test
    fun `cadence bounds are enforced`() {
        assertFalse(Validation.report(base().copy(intervalMinutes = 0)).isValid)
        assertFalse(Validation.report(base().copy(timeoutSeconds = 0)).isValid)
        assertFalse(Validation.report(base().copy(timeoutSeconds = 500)).isValid)
        assertTrue(Validation.report(base().copy(intervalMinutes = 5)).isValid)
    }

    @Test
    fun `sub 15 minute intervals are allowed with a hint`() {
        val report = Validation.report(base().copy(intervalMinutes = 5))
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.HINT, report.of(Validation.Field.INTERVAL)!!.severity)
    }

    @Test
    fun `body on a GET only warns`() {
        val report = Validation.report(base().copy(method = HttpMethod.GET, body = "hello"))
        assertTrue(report.isValid)
        assertEquals(Validation.Severity.WARNING, report.of(Validation.Field.BODY)!!.severity)
    }
}

class PersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `snapshot survives a full round trip`() {
        val snapshot = NightbellSnapshot(
            monitors = listOf(
                Monitor(
                    id = "a",
                    name = "API",
                    kind = MonitorKind.ADVANCED_REQUEST,
                    url = "https://api.example.com/health",
                    method = HttpMethod.POST,
                    headers = listOf(HeaderPair("Authorization", "Bearer x")),
                    body = """{"probe":1}""",
                    status = StatusExpectation(StatusMode.RANGE, rangeStart = 200, rangeEnd = 204),
                    assertion = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, "green", "data.health"),
                    alert = AlertPolicy(
                        vibrationStyle = VibrationStyle.SOS,
                        repeatEnabled = true,
                        quietHoursEnabled = true,
                    ),
                    intervalMinutes = 7,
                ),
                Monitor(
                    id = "b",
                    name = "Shop",
                    kind = MonitorKind.WEBSITE_ELEMENT,
                    url = "https://shop.example.com",
                    element = ElementTarget(
                        cssSelector = "#price",
                        xpath = "/html/body[1]/div[2]",
                        textSnippet = "£42.00",
                        mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                    ),
                ),
            ),
            runtimes = mapOf(
                "a" to MonitorRuntime(
                    lastCheckedAt = 1_700_000_000_000,
                    consecutiveFailures = 2,
                    alerting = true,
                    mutedUntil = 1_700_000_600_000,
                    samples = listOf(Sample(1L, true, 120), Sample(2L, false, 5_000, 500, "boom")),
                ),
            ),
            settings = GlobalSettings(masterAlertsEnabled = false, historyDepth = 120),
        )

        val encoded = json.encodeToString(snapshot)
        val decoded = json.decodeFromString<NightbellSnapshot>(encoded)

        assertEquals(snapshot, decoded)
        assertEquals(2, decoded.monitors.size)
        assertEquals(VibrationStyle.SOS, decoded.monitors[0].alert.vibrationStyle)
        assertEquals("£42.00", decoded.monitors[1].element?.textSnippet)
        assertEquals(2, decoded.runtimes.getValue("a").samples.size)
        assertFalse(decoded.settings.masterAlertsEnabled)
    }

    @Test
    fun `unknown fields from a future version are ignored`() {
        val raw = """
            {"schema":1,"monitors":[{"id":"x","url":"https://a.b","futureField":42}],
             "runtimes":{},"settings":{"masterAlertsEnabled":true,"somethingNew":"?"}}
        """.trimIndent()
        val decoded = json.decodeFromString<NightbellSnapshot>(raw)
        assertEquals(1, decoded.monitors.size)
        assertEquals("https://a.b", decoded.monitors[0].url)
        assertEquals(MonitorKind.HTTP_STATUS, decoded.monitors[0].kind)
    }

    @Test
    fun `vibration patterns and amplitudes stay aligned`() {
        VibrationStyle.entries.forEach { style ->
            assertEquals(
                "pattern/amplitude mismatch for $style",
                style.pattern.size,
                style.amplitudes.size,
            )
            assertTrue("empty pattern for $style", style.pattern.isNotEmpty())
            assertTrue(
                "amplitudes out of range for $style",
                style.amplitudes.all { it in 0..255 },
            )
        }
    }

    @Test
    fun `display name falls back to the host`() {
        val unnamed = Monitor(id = "1", url = "https://status.example.com/health")
        assertEquals("status.example.com/health", unnamed.displayName)
        assertEquals("Named", unnamed.copy(name = "Named").displayName)
    }
}
