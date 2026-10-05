package me.river.nightbell

import me.river.nightbell.domain.DataSaver
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSaverTest {

    private val page = Monitor(id = "p", name = "page", url = "https://riveer.cz", kind = MonitorKind.WEBSITE_ELEMENT)

    @Test
    fun fontsMediaAndTrackersAreSkippedButScriptsAndStylesAreNot() {
        assertTrue(DataSaver.shouldBlock("https://riveer.cz/fonts/inter.woff2"))
        assertTrue(DataSaver.shouldBlock("https://cdn.x.cz/a.TTF?v=3"))
        assertTrue(DataSaver.shouldBlock("https://riveer.cz/hero.mp4"))
        assertTrue(DataSaver.shouldBlock("https://www.googletagmanager.com/gtag/js?id=G-1"))
        assertTrue(DataSaver.shouldBlock("https://region1.google-analytics.com/g/collect"))
        assertTrue(DataSaver.shouldBlock("https://fonts.googleapis.com/css2?family=Inter"))
        assertTrue(DataSaver.shouldBlock("https://www.youtube.com/embed/abc"))

        assertFalse(DataSaver.shouldBlock("https://riveer.cz/assets/app.3f9a.js"))
        assertFalse(DataSaver.shouldBlock("https://riveer.cz/style.css"))
        assertFalse(DataSaver.shouldBlock("https://riveer.cz/api/status"))
        assertFalse(DataSaver.shouldBlock("https://www.youtube.com/watch?v=abc"))
        // A host that only ends with a blocked name is not that host.
        assertFalse(DataSaver.shouldBlock("https://notclarity.ms/app.js"))
        assertFalse(DataSaver.shouldBlock("data:font/woff2;base64,AAAA"))
    }

    @Test
    fun aColdLoadIsDueOncePerDayAndAfterARestart() {
        val now = 1_700_000_000_000L
        assertTrue(DataSaver.coldDue(null, now))
        assertFalse(DataSaver.coldDue(now - 60_000L, now))
        assertTrue(DataSaver.coldDue(now - DataSaver.COLD_LOAD_EVERY_MS, now))
        // A clock that went backwards must not hold the cold load off forever.
        assertTrue(DataSaver.coldDue(now + 60_000L, now))
    }

    @Test
    fun theAverageStartsAtTheFirstSampleAndLeansOnHistory() {
        assertEquals(240_000L, DataSaver.fold(0L, 240_000L))
        assertEquals(190_000L, DataSaver.fold(240_000L, 40_000L))
    }

    @Test
    fun thePageFleetFromTheReportAddsUpToItsBill() {
        // Ten page monitors at fifteen minutes, 240 KB a cold load: the 7.14 GB
        // the report's data screen showed, give or take the month's length.
        val fleet = (1..10).map { page.copy(id = "p$it", intervalMinutes = 15) }
        val runtimes = fleet.associate { it.id to MonitorRuntime(bytesFull = 240_000L, bytesSaver = 30_000L) }
        val estimate = DataSaver.monthly(fleet, runtimes)
        assertEquals(6_912_000_000L, estimate.offBytes)
        // 2850 warm loads a monitor and one cold one a day.
        assertEquals(10 * (2_850L * 30_000L + 30L * 240_000L), estimate.onBytes)
        assertEquals(0, estimate.onUnmeasured)
        assertEquals("6.9 GB", DataSaver.format(estimate.offBytes))
        assertEquals("927 MB", DataSaver.format(estimate.onBytes))
    }

    @Test
    fun aSideWithNoMeasurementIsNotGivenANumber() {
        val (off, on) = DataSaver.perMonth(page, MonitorRuntime(bytesFull = 240_000L))
        assertEquals(2_880L * 240_000L, off)
        assertNull(on)

        val summary = DataSaver.summary(
            DataSaver.monthly(listOf(page), mapOf(page.id to MonitorRuntime(bytesFull = 240_000L))),
            saverOn = false,
        )
        assertTrue(summary, summary.endsWith("About 691 MB a month off. Turn it on to measure what it saves."))
    }

    @Test
    fun aMonitorTheSaverDoesNotChangeCostsTheSameEitherWay() {
        val http = Monitor(id = "h", name = "api", url = "https://x.cz", kind = MonitorKind.HTTP_STATUS, intervalMinutes = 30)
        assertEquals(1_440L * 2_000L to 1_440L * 2_000L, DataSaver.perMonth(http, MonitorRuntime(bytesSaver = 2_000L)))
    }

    @Test
    fun downloadCountsAreReadHourlyUnderTheSaver() {
        val repo = Monitor(
            id = "g",
            name = "repo",
            kind = MonitorKind.GITHUB_REPO,
            intervalMinutes = 15,
            github = GitHubWatch(trackDownloads = true),
        )
        val (off, on) = DataSaver.perMonth(repo, MonitorRuntime(bytesFull = 55_000L, bytesSaver = 3_000L))
        assertEquals(2_880L * 55_000L, off)
        assertEquals(2_160L * 3_000L + 720L * 55_000L, on)
    }

    @Test
    fun nothingMeasuredSaysSoInsteadOfZero() {
        val summary = DataSaver.summary(DataSaver.monthly(listOf(page), emptyMap()), saverOn = true)
        assertFalse(summary.contains("0 B"))
        assertTrue(summary.contains("Measuring"))
    }
}
