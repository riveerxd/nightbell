package me.river.nightbell

import me.river.nightbell.domain.DigestMode
import me.river.nightbell.domain.DownloadReading
import me.river.nightbell.domain.GitHubEtags
import me.river.nightbell.domain.GitHubEvent
import me.river.nightbell.domain.GitHubEvents
import me.river.nightbell.domain.GitHubSnapshot
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a moving download counter is allowed to say.
 *
 * The rules are the star track's, and the cases that matter are the ones where
 * a number falls for a reason that is not somebody undoing a download: a new
 * release resetting the latest count, a deleted release shrinking the total,
 * and a page walk that stopped early and produced a floor rather than a total.
 * None of those is news and all three look like news to a naive comparison.
 */
class GitHubDownloadEventsTest {

    /**
     * Stars and issues are switched off throughout, so every event asserted on
     * below can only have come from the download track. A repository monitor
     * with the defaults would announce the star count too, and a test that had
     * to look past it would stop failing for the reason it was written.
     */
    private val watch = GitHubWatch(
        owner = "riveerxd",
        repo = "nightbell",
        notifyOnStars = false,
        notifyOnIssues = false,
        trackDownloads = true,
        notifyOnDownloads = true,
    )

    private fun reading(
        latest: Int,
        total: Int = latest,
        releases: Int = 1,
        complete: Boolean = true,
    ) = DownloadReading(
        latest = latest,
        total = total,
        releases = releases,
        complete = complete,
        byFile = emptyList(),
    )

    private fun snapshot(
        downloads: DownloadReading?,
        assetTypes: List<String> = emptyList(),
    ) = GitHubSnapshot(
        stars = 10,
        openIssues = 0,
        forks = 0,
        watchers = 0,
        pushedAt = "2026-08-26T19:15:34Z",
        repoChanged = true,
        releaseChanged = false,
        downloads = downloads,
        assetTypes = assetTypes,
        etags = GitHubEtags(repo = "\"r1\""),
    )

    /** A state that has already seen one download reading of [total]. */
    private fun seeded(total: Int, complete: Boolean = true) = GitHubState(
        seeded = true,
        downloadsSeeded = true,
        latestDownloads = total,
        totalDownloads = total,
        totalComplete = complete,
    )

    // ---- seeding -------------------------------------------------------------

    @Test
    fun `the first reading is written down and says nothing`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = GitHubState(seeded = true),
            snapshot = snapshot(reading(latest = 295, total = 1_250, releases = 38)),
            nowMs = 1_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertTrue(outcome.state.downloadsSeeded)
        assertEquals(1_250, outcome.state.totalDownloads)
        assertEquals(295, outcome.state.latestDownloads)
        assertEquals(38, outcome.state.totalCoversReleases)
    }

    @Test
    fun `a monitor that has never polled downloads is not seeded by a poll that did not ask`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = GitHubState(seeded = true),
            snapshot = snapshot(null),
            nowMs = 1_000L,
        )
        assertFalse(outcome.state.downloadsSeeded)
        assertEquals(-1, outcome.state.totalDownloads)
    }

    // ---- growth --------------------------------------------------------------

    @Test
    fun `an increase is announced with the delta and never as a person`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 302, total = 1_257, releases = 38)),
            nowMs = 2_000L,
        )
        val event = outcome.events.single() as GitHubEvent.Downloads
        assertEquals(7, event.delta)
        assertEquals("1250 to 1257 downloads (+7 since the last check)", event.body)
        assertEquals("New downloads on riveerxd/nightbell", event.title("riveerxd/nightbell"))
    }

    @Test
    fun `one download reads as one`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 296, total = 1_251)),
            nowMs = 2_000L,
        )
        val event = outcome.events.single() as GitHubEvent.Downloads
        assertEquals("New download on riveerxd/nightbell", event.title("riveerxd/nightbell"))
    }

    @Test
    fun `a second notice replaces the first rather than stacking`() {
        assertEquals(
            "downloads",
            GitHubEvent.Downloads(1, 2, "https://example.invalid").key,
        )
    }

    // ---- the falls that are not news ----------------------------------------

    @Test
    fun `a fall is never growth`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 3, total = 1_100)),
            nowMs = 2_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertEquals(1_100, outcome.state.totalDownloads)
    }

    @Test
    fun `a new release resetting the latest count is not announced`() {
        // The case a latest-only monitor hits on every release: yesterday's
        // number was the old release's lifetime total, today's is a few hours
        // of the new one.
        val latestOnly = watch.copy(downloadsAcrossAllReleases = false)
        val outcome = GitHubEvents.evaluate(
            watch = latestOnly,
            previous = GitHubState(
                seeded = true,
                downloadsSeeded = true,
                latestDownloads = 295,
                totalDownloads = 295,
                totalComplete = true,
            ),
            snapshot = snapshot(reading(latest = 3, total = 3)),
            nowMs = 2_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertEquals(3, outcome.state.latestDownloads)
    }

    @Test
    fun `growth from the new release's own baseline is announced`() {
        val latestOnly = watch.copy(downloadsAcrossAllReleases = false)
        val outcome = GitHubEvents.evaluate(
            watch = latestOnly,
            previous = GitHubState(
                seeded = true,
                downloadsSeeded = true,
                latestDownloads = 3,
                totalDownloads = 3,
                totalComplete = true,
            ),
            snapshot = snapshot(reading(latest = 19, total = 19)),
            nowMs = 2_000L,
        )
        assertEquals(16, (outcome.events.single() as GitHubEvent.Downloads).delta)
    }

    @Test
    fun `a truncated reading is never compared with a complete one`() {
        // The repository grew past the page cap. Today's number covers fewer
        // releases than yesterday's, so the difference says where the walk
        // stopped rather than anything about downloads.
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 4_000, releases = 300, complete = false)),
            nowMs = 2_000L,
        )
        assertTrue(outcome.events.isEmpty())
        // The number is still recorded, and still flagged as a floor.
        assertEquals(4_000, outcome.state.totalDownloads)
        assertFalse(outcome.state.totalComplete)
    }

    @Test
    fun `a complete reading after a truncated one is not announced either`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250, complete = false),
            snapshot = snapshot(reading(latest = 300, total = 1_300, releases = 38)),
            nowMs = 2_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertTrue(outcome.state.totalComplete)
    }

    // ---- toggles -------------------------------------------------------------

    @Test
    fun `the state advances with every toggle off`() {
        val quiet = watch.copy(notifyOnDownloads = false)
        val outcome = GitHubEvents.evaluate(
            watch = quiet,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 302, total = 1_257)),
            nowMs = 2_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertEquals(1_257, outcome.state.totalDownloads)
    }

    @Test
    fun `every download can be switched off while milestones stay on`() {
        val milestonesOnly = watch.copy(notifyOnEveryDownload = false)
        val quiet = GitHubEvents.evaluate(
            watch = milestonesOnly,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 302, total = 1_257)),
            nowMs = 2_000L,
        )
        assertTrue(quiet.events.isEmpty())

        val crossing = GitHubEvents.evaluate(
            watch = milestonesOnly,
            previous = seeded(2_400),
            snapshot = snapshot(reading(latest = 302, total = 2_505)),
            nowMs = 2_000L,
        )
        assertEquals(2_500, (crossing.events.single() as GitHubEvent.DownloadMilestone).milestone)
    }

    // ---- milestones ----------------------------------------------------------

    @Test
    fun `a milestone supersedes the plain notice rather than joining it`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(990),
            snapshot = snapshot(reading(latest = 300, total = 1_010)),
            nowMs = 2_000L,
        )
        val event = outcome.events.single() as GitHubEvent.DownloadMilestone
        assertEquals(1_000, event.milestone)
        assertEquals("riveerxd/nightbell passed 1000 downloads", event.title("riveerxd/nightbell"))
    }

    @Test
    fun `crossing two milestones at once reports the larger`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(90),
            snapshot = snapshot(reading(latest = 300, total = 600)),
            nowMs = 2_000L,
        )
        assertEquals(500, (outcome.events.single() as GitHubEvent.DownloadMilestone).milestone)
    }

    @Test
    fun `the download milestones start well above the star ones`() {
        // A download is not a star. Reusing the star list would fire six times
        // in the first week on a repository this size.
        assertEquals(100, GitHubWatch.DEFAULT_DOWNLOAD_MILESTONES.first())
        assertTrue(GitHubWatch.DEFAULT_DOWNLOAD_MILESTONES.last() >= 1_000_000)
    }

    // ---- digest --------------------------------------------------------------

    /** A real wall clock. The window's start doubles as "a window is open". */
    private val startedAt = 1_790_000_000_000L

    @Test
    fun `a digest holds the window open and reports the span`() {
        val digesting = watch.copy(downloadDigest = DigestMode.HOURLY)
        val opened = GitHubEvents.evaluate(
            watch = digesting,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 1_260)),
            nowMs = startedAt,
        )
        assertTrue(opened.events.isEmpty())
        assertEquals(1_250, opened.state.digestDownloadsFrom)

        val closed = GitHubEvents.evaluate(
            watch = digesting,
            previous = opened.state,
            snapshot = snapshot(reading(latest = 340, total = 1_300)),
            nowMs = startedAt + DigestMode.HOURLY.windowMs + 1,
        )
        val event = closed.events.single() as GitHubEvent.DownloadDigest
        assertEquals(50, event.delta)
        assertEquals(-1, closed.state.digestDownloadsFrom)
    }

    @Test
    fun `switching the digest off drops an open window rather than holding it`() {
        val digesting = watch.copy(downloadDigest = DigestMode.DAILY)
        val opened = GitHubEvents.evaluate(
            watch = digesting,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 1_260)),
            nowMs = startedAt,
        )
        assertEquals(1_250, opened.state.digestDownloadsFrom)

        val stopped = GitHubEvents.evaluate(
            watch = watch,
            previous = opened.state,
            snapshot = snapshot(reading(latest = 310, total = 1_270)),
            nowMs = startedAt + 1_000L,
        )
        assertEquals(-1, stopped.state.digestDownloadsFrom)
    }

    // ---- the refresh cadence -------------------------------------------------

    @Test
    fun `every check leaves the gate open`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 1_260)),
            nowMs = startedAt,
        )
        assertTrue(outcome.state.downloadsRetryAt <= startedAt)
    }

    @Test
    fun `an hourly cadence closes the gate for an hour`() {
        val hourly = watch.copy(downloadsRefresh = DigestMode.HOURLY)
        val outcome = GitHubEvents.evaluate(
            watch = hourly,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 1_260)),
            nowMs = startedAt,
        )
        assertEquals(startedAt + DigestMode.HOURLY.windowMs, outcome.state.downloadsRetryAt)
    }

    @Test
    fun `a daily cadence closes it for a day`() {
        val daily = watch.copy(downloadsRefresh = DigestMode.DAILY)
        val outcome = GitHubEvents.evaluate(
            watch = daily,
            previous = seeded(1_250),
            snapshot = snapshot(reading(latest = 300, total = 1_260)),
            nowMs = startedAt,
        )
        assertEquals(startedAt + DigestMode.DAILY.windowMs, outcome.state.downloadsRetryAt)
    }

    @Test
    fun `file types are recorded by a poll that counted nothing`() {
        val outcome = GitHubEvents.evaluate(
            watch = watch.copy(trackDownloads = false),
            previous = GitHubState(seeded = true),
            snapshot = snapshot(null, assetTypes = listOf(".apk", ".asc")),
            nowMs = startedAt,
        )
        assertEquals(listOf(".apk", ".asc"), outcome.state.knownAssetTypes)
        assertFalse(outcome.state.downloadsSeeded)
    }
}
