package me.river.nightbell

import me.river.nightbell.domain.DiagnosticHeader
import me.river.nightbell.domain.FleetFacts
import me.river.nightbell.domain.LogArea
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import me.river.nightbell.domain.LogFormat
import me.river.nightbell.domain.LogLevel
import me.river.nightbell.domain.LogRetention
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {

    @Test
    fun `a line puts the level where the viewer looks for it`() {
        val line = LogFormat.line(
            atMs = stampAt(12, 4, 31, 882),
            event = LogEvent.PAGE_EXPIRED,
            fields = listOf(LogField.of("percent", 43)),
            offsetMs = 0,
        )
        assertTrue(line.startsWith("12:04:31.882 W PAGE"))
        // The settings viewer colours by the character at index 13, so that
        // position is part of the format rather than an accident of padding.
        assertEquals('W', line[13])
        assertTrue(line.contains("page.expired"))
        assertTrue(line.contains("percent=43"))
    }

    @Test
    fun `every level marker sits at the index the viewer reads`() {
        for (event in LogEvent.entries) {
            val line = LogFormat.line(0L, event, emptyList(), offsetMs = 0)
            assertEquals("wrong marker column for ${event.code}", event.level.marker, line[13])
        }
    }

    @Test
    fun `every event code is unique and greppable`() {
        val codes = LogEvent.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        for (code in codes) {
            assertFalse("$code has a space in it", code.contains(" "))
            assertTrue("$code is not dotted", code.contains("."))
        }
    }

    @Test
    fun `text fields are quoted so a sentence cannot look like two fields`() {
        val line = LogFormat.line(
            atMs = 0L,
            event = LogEvent.CHECK_DONE,
            fields = listOf(
                LogField.of("ok", false),
                LogField.text("verdict", "HTTP 503 in 1204ms"),
            ),
        )
        assertTrue(line.contains("ok=false"))
        assertTrue(line.contains("verdict=\"HTTP 503 in 1204ms\""))
    }

    @Test
    fun `a monitor is logged by a short id and never by its name`() {
        val field = LogField.monitor("7f3a1c2e-4b6d-4f0a-8c4e-6b8da1b2c3d4")
        assertEquals("monitor=7f3a1c2e", field.render())
    }

    @Test
    fun `a secret field carries a fingerprint and never content`() {
        val field = LogField.secret("cookies", "session=abcdef0123456789")
        assertFalse(field.render().contains("abcdef"))
        assertTrue(field.render().startsWith("cookies=["))
        assertEquals("cookies=none", LogField.secret("cookies", "").render())
    }

    @Test
    fun `a present field says only whether there is one`() {
        assertEquals("pin=present", LogField.present("pin", "sha256/abc").render())
        assertEquals("pin=absent", LogField.present("pin", "   ").render())
    }

    @Test
    fun `a tag field fingerprints anything that looks like an identifier`() {
        // The guard against a caller reaching for the wrong factory: a constant
        // passes through, something dynamic and opaque does not.
        assertEquals("why=offline", LogField.tag("why", "offline").render())
        val opaque = LogField.tag("why", "a7f3c91e04b8d26f5c1a9e3b").render()
        assertFalse(opaque.contains("a7f3c91e04b8d26f5c1a9e3b"))
    }

    @Test
    fun `tag is for lowercase constants and text is for everything else`() {
        // `releaseTest` is a build type Gradle chose and it has a capital in it,
        // so `tag` fingerprints it and the log said `build=[11:253637]` on a
        // minified build. A constant with a capital belongs in `text`.
        assertFalse(LogField.tag("build", "releaseTest").render().contains("releaseTest"))
        assertEquals("build=releaseTest", LogField.text("build", "releaseTest").render())
    }

    @Test
    fun `an error field keeps the class and scrubs the message`() {
        val error = IllegalStateException("token ghp_ABCDEFGHIJKLMNOPQRSTUVWX rejected")
        val rendered = LogField.error("error", error).render()
        assertTrue(rendered.contains("IllegalStateException"))
        assertFalse(rendered.contains("ABCDEFGHIJKLMNOPQRSTUVWX"))
    }

    @Test
    fun `a route field never carries a query string`() {
        val field = LogField.route("url", "https://api.example.com/v1/ping?key=s3cr3t")
        assertFalse(field.render().contains("s3cr3t"))
        assertEquals("url=https://api.example.com/*2?*1", field.render())
    }

    @Test
    fun `a stack trace scrubs each message and keeps the frames`() {
        val cause = IllegalArgumentException("bad Authorization: Bearer abcdefghijklmnop")
        val error = RuntimeException("wrapped", cause)
        val lines = LogFormat.stack(error)
        assertTrue(lines.first().contains("RuntimeException"))
        assertTrue(lines.any { it.contains("caused by") })
        assertTrue(lines.any { it.trimStart().startsWith("at ") })
        assertFalse(lines.joinToString(" ").contains("abcdefghijklmnop"))
    }

    @Test
    fun `the header answers the questions a bug report never carries`() {
        val rendered = header().render().joinToString("\n")
        assertTrue(rendered.contains("3.8.0 (37)"))
        assertTrue(rendered.contains("API 34"))
        // Issue 8 asked which WebView the app uses, and the answer is per device.
        assertTrue(rendered.contains("com.google.android.webview 113.0.5672.136"))
        assertTrue(rendered.contains("notifications=true"))
        assertTrue(rendered.contains("minified"))
        // And it says what has been left out, because the file gets published.
        assertTrue(rendered.contains("Credentials, cookies,"))
    }

    @Test
    fun `the header never names a monitor`() {
        val rendered = header().render().joinToString("\n")
        assertTrue(rendered.contains("4 monitors, 3 enabled"))
        assertFalse(rendered.contains("https://"))
    }

    @Test
    fun `the fleet is counted and not described`() {
        val facts = FleetFacts.of(
            listOf(
                monitor("a", enabled = true, kind = MonitorKind.HTTP_STATUS),
                monitor("b", enabled = true, kind = MonitorKind.WEBSITE_ELEMENT, urgent = true),
                monitor("c", enabled = false, kind = MonitorKind.WEBSITE_ELEMENT),
                monitor("d", enabled = true, kind = MonitorKind.GITHUB_REPO),
            ),
        )
        assertEquals(FleetFacts(total = 4, enabled = 3, urgent = 1, page = 2), facts)
    }

    @Test
    fun `retention is bounded so the worst case on disk is knowable`() {
        assertEquals(500, LogRetention.RING_LINES)
        assertTrue(LogRetention.shouldRotate(LogRetention.FILE_BYTES.toLong()))
        assertFalse(LogRetention.shouldRotate(LogRetention.FILE_BYTES - 1L))
        // Two generations, so the bound is twice the cap and under half a megabyte.
        assertTrue(LogRetention.FILE_BYTES * 2 < 512 * 1024)
    }

    @Test
    fun `the five reported surfaces all have events`() {
        // Each of these is a class of issue that has actually been filed. An area
        // with no events is a surface a log cannot explain.
        for (area in listOf(
            LogArea.SCHED,
            LogArea.HTTP,
            LogArea.PAGE,
            LogArea.ALERT,
            LogArea.APP,
        )) {
            assertTrue("$area has no events", LogEvent.entries.any { it.area == area })
        }
        assertTrue(LogEvent.entries.any { it.level == LogLevel.ERROR })
    }

    private fun header() = DiagnosticHeader(
        versionName = "3.8.0",
        versionCode = 37,
        buildType = "release",
        minified = true,
        applicationId = "me.river.nightbell",
        sdkInt = 34,
        manufacturer = "Google",
        model = "Pixel 6",
        webViewPackage = "com.google.android.webview",
        webViewVersion = "113.0.5672.136",
        batteryOptimised = false,
        notificationsAllowed = true,
        exactAlarmsAllowed = true,
        fullScreenIntentAllowed = false,
        online = true,
        metered = false,
        servicePaging = false,
        monitorCount = 4,
        enabledCount = 3,
        urgentCount = 1,
        pageMonitorCount = 2,
        loggingSince = "on since 2026-09-04 17:12:00 CEST",
        capturedAt = "2026-09-04 17:14:03 CEST",
    )

    private fun monitor(
        id: String,
        enabled: Boolean,
        kind: MonitorKind,
        urgent: Boolean = false,
    ) = Monitor(id = id, name = "secret name", enabled = enabled, kind = kind, urgent = urgent)

    @Test
    fun `the clock column is local, not utc`() {
        // It was UTC, and on a phone two hours ahead every line in the file
        // disagreed with the same event in logcat by two hours.
        val noonUtc = stampAt(12, 0, 0, 0)
        val line = LogFormat.line(noonUtc, LogEvent.APP_START, emptyList(), offsetMs = 7_200_000)
        assertTrue(line.startsWith("14:00:00.000"))
    }

    /** Wall-clock millis for a local time on an arbitrary day. */
    private fun stampAt(h: Int, m: Int, s: Int, ms: Int): Long =
        h * 3_600_000L + m * 60_000L + s * 1_000L + ms
}
