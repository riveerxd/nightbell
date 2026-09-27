package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.CheckEngine
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.UpdateSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Counting release downloads end to end on a device.
 *
 * A local server stands in for api.github.com so the count can actually move
 * between two checks, which is the thing the JVM suite cannot do. The store, the
 * engine and the screens are the real ones.
 *
 * The cases here are the ones that only a real screen can settle: that a floor
 * is drawn as a floor, that a filter matching nothing says so instead of drawing
 * a zero, and that a repository which has never been polled does not claim its
 * files have never been downloaded.
 */
@RunWith(AndroidJUnit4::class)
class GitHubDownloadsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)

    private lateinit var server: TinyHttpServer
    private lateinit var engine: CheckEngine

    /** One page of releases as the fake API currently reports them. */
    private val releases = AtomicReference(
        listOf(
            FakeRelease("v3.13.0", apk = 295, asc = 2),
            FakeRelease("v3.12.0", apk = 125, asc = 1),
        ),
    )

    /** Whether the fake API claims there is another page behind this one. */
    private val hasNextPage = AtomicReference(false)

    private data class FakeRelease(val tag: String, val apk: Int, val asc: Int)

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(
            GlobalSettings(motionIntensity = 0f, updateSource = UpdateSource.GITHUB),
        )
        server = TinyHttpServer { request -> respond(request) }
        engine = CheckEngine(
            store = graph.store,
            http = HttpChecker(),
            element = ElementChecker(appContext),
            alerts = graph.alerts,
            github = GitHubChecker(
                settingsFor = { graph.store.snapshot.value.settings },
                apiBase = server.baseUrl,
                minGapMs = 0L,
            ),
            updates = UpdateChecker(githubBase = server.baseUrl, fdroidBase = server.baseUrl),
            installedVersion = { "3.13.0" },
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun respond(request: TinyHttpServer.Request): TinyHttpServer.Response {
        val headers = mapOf(
            "x-ratelimit-limit" to "5000",
            "x-ratelimit-remaining" to "4999",
            "x-ratelimit-reset" to "1787776320",
        )
        val path = request.path.substringBefore('?')
        if (!path.endsWith("/releases")) {
            return TinyHttpServer.Response(
                body = """
                    {
                      "full_name": "riveerxd/nightbell",
                      "stargazers_count": 13,
                      "open_issues_count": 1,
                      "forks_count": 2,
                      "subscribers_count": 3,
                      "pushed_at": "2026-08-26T19:15:34Z"
                    }
                """.trimIndent(),
                contentType = "application/json",
                extraHeaders = headers,
            )
        }
        val body = releases.get().joinToString(",", "[", "]") { it.json() }
        val host = request.headers["host"] ?: "127.0.0.1"
        val link = if (hasNextPage.get()) {
            mapOf(
                "Link" to "<http://$host/repos/riveerxd/nightbell/releases" +
                    "?per_page=100&page=2>; rel=\"next\"",
            )
        } else {
            emptyMap()
        }
        return TinyHttpServer.Response(
            body = body,
            contentType = "application/json",
            extraHeaders = headers + link,
        )
    }

    private fun FakeRelease.json(): String = """
        {
          "id": ${tag.hashCode()},
          "tag_name": "$tag",
          "name": "Nightbell $tag",
          "prerelease": false,
          "draft": false,
          "published_at": "2026-08-26T19:11:44Z",
          "html_url": "https://github.com/riveerxd/nightbell/releases/tag/$tag",
          "assets": [
            { "name": "Nightbell-${tag.removePrefix("v")}-release.apk", "download_count": $apk },
            { "name": "Nightbell-${tag.removePrefix("v")}-release.apk.asc", "download_count": $asc }
          ]
        }
    """.trimIndent()

    private fun seed(
        watch: GitHubWatch = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnIssues = false,
            trackDownloads = true,
        ),
    ): Monitor {
        val monitor = Monitor(
            id = MONITOR_ID,
            name = "Nightbell repo",
            kind = MonitorKind.GITHUB_REPO,
            url = watch.repository.url,
            intervalMinutes = 15,
            timeoutSeconds = 10,
            github = watch,
        )
        runBlocking { graph.store.upsert(monitor) }
        return monitor
    }

    private fun check() = runBlocking { engine.run(MONITOR_ID, force = true) }

    private fun state(): GitHubState = runtime().github

    private fun runtime(): MonitorRuntime =
        runBlocking { graph.store.currentSnapshot() }.runtimes[MONITOR_ID] ?: MonitorRuntime()

    /** Opens the dashboard and taps into the seeded monitor's detail screen. */
    private fun openDetail() {
        ActivityScenario.launch(MainActivity::class.java)
        awaitTrue(description = "the dashboard card") {
            composeRule.onAllNodesWithText("Nightbell repo").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Nightbell repo").onFirst().performClick()
        awaitTrue(description = "the repository card") {
            composeRule.onAllNodesWithText("Repository").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- the engine ----------------------------------------------------------

    @Test
    fun aPollCountsEveryReleaseAndEveryFile() {
        seed()
        check()
        val github = state()
        assertTrue(github.downloadsSeeded)
        assertEquals(297, github.latestDownloads)
        assertEquals(423, github.totalDownloads)
        assertEquals(2, github.totalCoversReleases)
        assertTrue(github.totalComplete)
    }

    @Test
    fun aFilterCountsOnlyTheFilesItNames() {
        seed(
            GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnIssues = false,
                trackDownloads = true,
                downloadFilterText = ".apk",
            ),
        )
        check()
        assertEquals(420, state().totalDownloads)
        assertEquals(295, state().latestDownloads)
    }

    @Test
    fun everyReleaseOfOneFileIsOneRowInTheBreakdown() {
        seed()
        check()
        val byFile = state().downloadsByFile
        assertEquals(2, byFile.size)
        assertEquals("nightbell-*-release.apk", byFile.first().pattern)
        assertEquals(420, byFile.first().downloads)
        assertEquals(2, byFile.first().releases)
    }

    // ---- the screens ---------------------------------------------------------

    @Test
    fun theCardShowsBothNumbers() {
        seed()
        check()
        openDetail()
        composeRule.onNodeWithTag("github-download-metrics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("423").assertIsDisplayed()
        composeRule.onNodeWithText("297").assertIsDisplayed()
        // The captions say which slice each number counted. The first cut read
        // "297 newest release", which names a quantity of nothing, and the
        // glyph beside the number is what carries the noun now.
        composeRule.onNodeWithText("v3.13.0").assertIsDisplayed()
        composeRule.onNodeWithText("total").assertIsDisplayed()
        composeRule.captureScreenshot("downloads-01-card")
    }

    @Test
    fun aRepositoryNeverPolledDoesNotClaimZeroDownloads() {
        seed()
        openDetail()
        // No check has run, so there are no tiles and no number anywhere: a zero
        // here would be a claim about the repository rather than about what the
        // app knows.
        composeRule.onNodeWithText("Not read yet").performScrollTo().assertIsDisplayed()
        assertFalse(state().downloadsSeeded)
        composeRule.captureScreenshot("downloads-02-not-read-yet")
    }

    @Test
    fun aFilterThatMatchesNothingSaysSoRatherThanShowingAZero() {
        seed(
            GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnIssues = false,
                trackDownloads = true,
                downloadFilterText = ".exe",
            ),
        )
        check()
        openDetail()
        composeRule.onNodeWithText("No release file matches that")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.captureScreenshot("downloads-03-filter-matches-nothing")
    }

    @Test
    fun aTruncatedTotalIsDrawnAsAFloor() {
        // The fake API claims a next page it never serves, which is what a
        // repository past the walk's cap looks like from here.
        hasNextPage.set(true)
        seed()
        check()
        assertFalse(state().totalComplete)
        openDetail()
        composeRule.onAllNodesWithText("423+", substring = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.captureScreenshot("downloads-04-floor")
    }

    @Test
    fun theCountMovingBetweenTwoChecksIsAnnouncedAsADelta() {
        seed(
            GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnIssues = false,
                notifyOnStars = false,
                trackDownloads = true,
                notifyOnDownloads = true,
            ),
        )
        check()
        assertEquals(423, state().totalDownloads)

        releases.set(
            listOf(
                FakeRelease("v3.13.0", apk = 302, asc = 2),
                FakeRelease("v3.12.0", apk = 125, asc = 1),
            ),
        )
        check()
        assertEquals(430, state().totalDownloads)

        openDetail()
        // The history row, which is what the count moving looks like after the
        // notification has gone.
        composeRule.onAllNodesWithText("7 downloads", substring = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.captureScreenshot("downloads-05-delta-row")
    }

    private companion object {
        const val MONITOR_ID = "gh-downloads"
    }
}
