package me.river.nightbell

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.LatencyReference
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.ConnectivityReference
import me.river.nightbell.domain.GitHubEvents
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.UpdateSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same code against the real thing: api.github.com, from the device.
 *
 * Everything else about this feature is proven against a local server, which is
 * the only way to make a star appear on demand. What a local server cannot prove
 * is that the requests are shaped the way GitHub actually wants: the Accept
 * header, the API version, the conditional GET, the rate-limit headers, the
 * `pull_request` marker on an item served through the issues endpoint. So this
 * reads one real public repository, twice.
 *
 * Skips rather than fails when the network is unavailable or the anonymous
 * budget is spent. Sixty requests an hour is shared by everything behind one
 * address, and a red suite because somebody else used the allowance would be a
 * test reporting on the wrong thing.
 */
@RunWith(AndroidJUnit4::class)
class RealGitHubInstrumentedTest {

    private val graph get() = Nightbell.install(appContext)

    private val checker by lazy {
        GitHubChecker(settingsFor = { graph.store.snapshot.value.settings })
    }

    private val monitor = Monitor(
        id = "real-github",
        kind = MonitorKind.GITHUB_REPO,
        url = "https://github.com/riveerxd/nightbell",
        timeoutSeconds = 20,
        github = GitHubWatch(owner = "riveerxd", repo = "nightbell"),
    )

    @Before
    fun setUp() {
        assumeTrue("device is offline", graph.network.isOnline())
        resetApp(GlobalSettings(motionIntensity = 0f))
    }

    @Test
    fun a_real_repository_reads_stars_issues_and_the_latest_release() {
        val outcome = runBlocking { checker.poll(monitor, GitHubState()) }
        assumeFalse("GitHub is rate limiting this address", outcome.rateLimited)

        val result = outcome.result
        assertNotNull("no verdict from a live poll", result)
        assertTrue(result!!.message, result.ok)
        assertEquals(200, result.statusCode)

        val snapshot = outcome.snapshot!!
        Log.i(TAG, "riveerxd/nightbell: ${snapshot.stars} stars, ${snapshot.openIssues} open issues")
        assertTrue("a public repository should report a star count", snapshot.stars >= 0)
        assertTrue("the repository should report a last push", snapshot.pushedAt.isNotBlank())

        // The rate-limit headers are the thing the whole design turns on, so read
        // them back rather than assuming they arrived.
        assertTrue("no rate-limit headers came back", snapshot.rate.remaining >= 0)
        assertTrue(snapshot.rate.limit > 0)
        Log.i(TAG, "budget: ${snapshot.rate.remaining} of ${snapshot.rate.limit}")

        // Issue #4 is the one this whole release answers, and it is open.
        val issue = snapshot.issues.firstOrNull { it.number == 4 && !it.isPullRequest }
        if (issue != null) {
            assertTrue(issue.title, issue.title.contains("GrapheneOS", ignoreCase = true))
            assertTrue(issue.url.endsWith("/issues/4"))
            assertTrue(issue.author.isNotBlank())
        } else {
            Log.i(TAG, "issue #4 is no longer open; skipping that assertion")
        }

        // A released repository has a latest release, and it has to parse.
        val release = snapshot.release
        assertNotNull("riveerxd/nightbell has releases", release)
        assertTrue(release!!.tag, release.tag.startsWith("v"))
        assertFalse(release.draft)
        assertTrue(release.url.contains("/releases/tag/"))
        Log.i(TAG, "latest release: ${release.tag}")
    }

    @Test
    fun b_a_real_second_poll_is_a_conditional_GET_and_changes_nothing() {
        val first = runBlocking { checker.poll(monitor, GitHubState()) }
        assumeFalse("GitHub is rate limiting this address", first.rateLimited)
        assumeTrue("first poll produced no snapshot", first.snapshot != null)

        // The baseline this install would have written.
        val seeded = GitHubEvents.evaluate(
            watch = monitor.github,
            previous = GitHubState(),
            snapshot = first.snapshot!!,
            nowMs = System.currentTimeMillis(),
        )
        assertTrue("a first poll must announce nothing: " + seeded.events, seeded.events.isEmpty())
        assertTrue(seeded.state.repoEtag.isNotBlank())

        val second = runBlocking { checker.poll(monitor, seeded.state) }
        assumeFalse("GitHub is rate limiting this address", second.rateLimited)
        val snapshot = second.snapshot!!

        // Nothing plausibly changed in the second between the two calls, so the
        // repository endpoint answered 304 and the values were carried forward.
        assertFalse("expected a 304 from an unchanged repository", snapshot.repoChanged)
        assertEquals(304, second.result!!.statusCode)
        assertTrue(second.result!!.ok)
        assertEquals(seeded.state.lastStarCount, snapshot.stars)

        val again = GitHubEvents.evaluate(
            watch = monitor.github,
            previous = seeded.state,
            snapshot = snapshot,
            nowMs = System.currentTimeMillis(),
        )
        assertTrue("an unchanged repository must say nothing: " + again.events, again.events.isEmpty())
        assertEquals(seeded.state.lastIssueId, again.state.lastIssueId)
        assertEquals(seeded.state.lastReleaseTag, again.state.lastReleaseTag)
    }

    @Test
    fun c_the_real_update_check_reads_this_project_s_latest_release() {
        val release = runBlocking { UpdateChecker().latest(UpdateSource.GITHUB) }
        assumeTrue("could not reach GitHub releases", release != null)
        Log.i(TAG, "GitHub says the newest Nightbell is ${release!!.version}")
        assertTrue(release.version, release.version.first().isDigit())
        assertTrue(release.url, release.url.startsWith("https://github.com/riveerxd/nightbell"))
        // The installed build is either current or behind, never ahead of a
        // published tag by more than a development bump.
        assertNotNull(AppUpdate.compare(release.version, BuildConfig.VERSION_NAME))
    }

    @Test
    fun d_the_real_f_droid_index_reads_a_version_too() {
        val release = runBlocking { UpdateChecker().latest(UpdateSource.FDROID) }
        assumeTrue("could not reach the F-Droid index", release != null)
        Log.i(TAG, "F-Droid says the newest Nightbell is ${release!!.version}")
        assertTrue(release.version, release.version.first().isDigit())
        assertEquals(AppUpdate.FDROID_URL, release.url)
    }

    /**
     * Issue #4, proven rather than asserted: the endpoint the app now defaults to
     * answers from a real device, on a real network.
     */
    @Test
    fun e_the_default_latency_reference_answers_from_the_device() {
        val rtt = runBlocking { LatencyReference().probe(ConnectivityReference.DEFAULT_URL) }
        assumeTrue("this network blocks ${ConnectivityReference.DEFAULT_URL}", rtt != null)
        Log.i(TAG, "GrapheneOS connectivity check answered in ${rtt}ms")
        assertTrue("a round trip cannot take no time", rtt!! > 0)
        assertTrue("suspiciously slow for a 204: ${rtt}ms", rtt < 10_000)
    }

    @Test
    fun f_a_repository_that_does_not_exist_is_a_configuration_failure() {
        val ghost = monitor.copy(
            github = GitHubWatch(owner = "riveerxd", repo = "definitely-not-a-real-repo-9271"),
        )
        val outcome = runBlocking { checker.poll(ghost, GitHubState()) }
        assumeFalse("GitHub is rate limiting this address", outcome.rateLimited)
        val result = outcome.result!!
        assertFalse(result.ok)
        assertEquals(404, result.statusCode)
        assertTrue(result.message, result.message.contains("Repository not found"))
        assertNull(outcome.snapshot)
    }

    /**
     * The comment track against a real payload, which is the only thing that can
     * prove the parsing.
     *
     * GitHub sends `minimized` and `performed_via_github_app` on every comment
     * with a null value. A fixture can be written to match that, but only the
     * real endpoint proves the fixture was right about it, and reading either key
     * as merely present makes the whole feature silent with nothing in a log to
     * say why. Read against a busier repository than this one, because
     * riveerxd/nightbell may have no comments at all and an empty page proves
     * nothing about parsing.
     */
    @Test
    fun g_real_comments_parse_and_the_first_look_announces_nothing() {
        val busy = monitor.copy(
            github = GitHubWatch(owner = "kotlin", repo = "kotlinx.coroutines", notifyOnComments = true),
        )
        val outcome = runBlocking { checker.poll(busy, GitHubState()) }
        assumeFalse("GitHub is rate limiting this address", outcome.rateLimited)

        val snapshot = outcome.snapshot!!
        assumeTrue("the comment endpoint did not answer", snapshot.commentsAnswered)
        assumeTrue("this repository has no comments to read", snapshot.comments.isNotEmpty())
        Log.i(TAG, "read ${snapshot.comments.size} real comments")

        // Every row parsed into something usable. A silent parse failure shows up
        // here as an id of zero or an empty url rather than as an exception.
        snapshot.comments.forEach { comment ->
            assertTrue("a comment with no id: $comment", comment.id > 0L)
            assertTrue("a comment with no parent number: $comment", comment.issueNumber > 0)
            assertTrue("a comment with no url: $comment", comment.url.startsWith("https://"))
        }

        // The traps, against the real thing. Almost nothing on GitHub is hidden,
        // and a build that read a null `minimized` as present would mark every
        // one of these as hidden instead.
        assertTrue(
            "reading null minimized as present would hide everything",
            snapshot.comments.any { !it.minimized },
        )
        assertTrue(
            "reading a null app field as present would call everyone a bot",
            snapshot.comments.any { !it.isApp },
        )

        // A busy repository serves pull request conversation from this endpoint
        // too, and the path segment is the only thing that separates them.
        val onPulls = snapshot.comments.count { it.onPullRequest }
        Log.i(TAG, "$onPulls of ${snapshot.comments.size} were pull request threads")

        // The first look at a repository with years of conversation must be silent.
        val evaluation = GitHubEvents.evaluate(
            watch = busy.github,
            previous = GitHubState(),
            snapshot = snapshot,
            nowMs = System.currentTimeMillis(),
        )
        assertTrue(
            "a real backlog was announced as news",
            evaluation.events.none { it is me.river.nightbell.domain.GitHubEvent.NewComments },
        )
        assertTrue("the track must record that it looked", evaluation.state.commentsSeeded)
        assertTrue(
            "the watermark must have moved past the whole page",
            evaluation.state.lastIssueCommentId > 0L ||
                evaluation.state.lastPullCommentId > 0L,
        )
    }

    private companion object {
        const val TAG = "RealGitHubTest"
    }
}
