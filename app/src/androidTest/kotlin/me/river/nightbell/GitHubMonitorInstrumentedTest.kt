package me.river.nightbell

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertActionReceiver
import me.river.nightbell.data.check.CheckEngine
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.UpdateSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The repository monitor end to end on a device: store, engine, notifications.
 *
 * A local server stands in for api.github.com so a star can actually appear
 * between two checks, which is the one thing the JVM suite cannot do and the
 * whole behaviour anybody cares about. Everything else is real: the DataStore
 * the app ships with, the real [me.river.nightbell.data.alerts.AlertCenter], and
 * notifications asserted by reading them back out of the shade.
 */
@RunWith(AndroidJUnit4::class)
class GitHubMonitorInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private lateinit var server: TinyHttpServer
    private lateinit var engine: CheckEngine

    /** What the fake API currently reports. Mutated between checks. */
    private val stars = AtomicInteger(13)
    private val issues = AtomicReference(listOf<FakeIssue>())
    private val release = AtomicReference<FakeRelease?>(null)
    private val forced = AtomicReference<TinyHttpServer.Response?>(null)
    private val repoEtag = AtomicReference("\"repo-1\"")
    private val comments = AtomicReference(listOf<FakeComment>())

    private data class FakeIssue(
        val id: Long,
        val number: Int,
        val title: String,
        val author: String = "octocat",
        val pull: Boolean = false,
    )

    private data class FakeRelease(val id: Long, val tag: String)

    private data class FakeComment(
        val id: Long,
        val issue: Int,
        val author: String = "river",
        val body: String = "Still happens on a fresh install of 3.7.0.",
        val pull: Boolean = false,
        val app: Boolean = false,
    )

    @Before
    fun setUp() {
        notifications.cancelAll()
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f))
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
            installedVersion = { "3.1.1" },
        )
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        server.close()
    }

    private fun respond(request: TinyHttpServer.Request): TinyHttpServer.Response {
        forced.get()?.let { return it }
        val headers = mapOf(
            "x-ratelimit-limit" to "60",
            "x-ratelimit-remaining" to "58",
            "x-ratelimit-reset" to "1787776320",
        )
        val path = request.path.substringBefore('?')
        return when {
            // Before the issues branch: this path ends in "/comments", but a
            // careless matcher on "/issues" would swallow it.
            path.endsWith("/issues/comments") -> TinyHttpServer.Response(
                body = comments.get().joinToString(",", "[", "]") { it.json() },
                contentType = "application/json",
                extraHeaders = headers,
            )

            path.endsWith("/issues") -> TinyHttpServer.Response(
                body = issues.get().joinToString(",", "[", "]") { it.json() },
                contentType = "application/json",
                extraHeaders = headers,
            )

            path.endsWith("/releases/latest") -> release.get()?.let {
                TinyHttpServer.Response(
                    body = it.json(),
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            } ?: TinyHttpServer.Response(
                code = 404,
                reason = "Not Found",
                body = """{"message":"Not Found"}""",
                extraHeaders = headers,
            )

            path.endsWith("/releases/latest".dropLast(0)) -> TinyHttpServer.Response(code = 404)

            else -> {
                val etag = repoEtag.get()
                if (request.headers["if-none-match"] == etag) {
                    TinyHttpServer.Response(
                        code = 304,
                        reason = "Not Modified",
                        extraHeaders = headers + ("ETag" to etag),
                    )
                } else {
                    TinyHttpServer.Response(
                        body = """
                            {"full_name":"riveerxd/nightbell","stargazers_count":${stars.get()},
                             "open_issues_count":${issues.get().count { !it.pull }},
                             "forks_count":2,"subscribers_count":3,
                             "pushed_at":"2026-08-26T19:15:34Z"}
                        """.trimIndent(),
                        contentType = "application/json",
                        extraHeaders = headers + ("ETag" to etag),
                    )
                }
            }
        }
    }

    private fun FakeIssue.json(): String = buildString {
        append("""{"id":$id,"number":$number,"title":"$title","body":"",""")
        append(""""created_at":"2026-08-26T18:22:30Z",""")
        append(""""html_url":"https://github.com/riveerxd/nightbell/issues/$number",""")
        append(""""user":{"login":"$author"}""")
        if (pull) append(""","pull_request":{"url":"x"}""")
        append("}")
    }

    /** Shaped the way GitHub really sends one, nulls included. */
    private fun FakeComment.json(): String = buildString {
        val kind = if (pull) "pull" else "issues"
        append("""{"id":$id,""")
        append(""""issue_url":"https://api.github.com/repos/riveerxd/nightbell/issues/$issue",""")
        append(
            """"html_url":"https://github.com/riveerxd/nightbell/$kind/$issue""" +
                """#issuecomment-$id",""",
        )
        append(""""body":"$body","created_at":"2026-08-31T19:59:33Z",""")
        append(""""updated_at":"2026-08-31T19:59:33Z","author_association":"NONE",""")
        append(""""user":{"login":"$author","type":"${if (app) "Bot" else "User"}"},""")
        // Both keys present with a null value on an ordinary comment, which is
        // the case that decides whether this feature says anything at all.
        append(""""performed_via_github_app":""")
        append(if (app) """{"id":278306,"slug":"ci"}""" else "null")
        append(""","minimized":null}""")
    }

    private fun FakeRelease.json(): String =
        """{"id":$id,"tag_name":"$tag","name":"Nightbell $tag","prerelease":false,"draft":false,
           "html_url":"https://github.com/riveerxd/nightbell/releases/tag/$tag"}""".trimIndent()

    // ---- helpers -------------------------------------------------------------

    private fun seed(watch: GitHubWatch = GitHubWatch(owner = "riveerxd", repo = "nightbell")): Monitor {
        val monitor = Monitor(
            id = MONITOR_ID,
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

    private fun repoNotifications(): List<StatusBarNotification> =
        notifications.activeNotifications.filter { it.tag == graph.alerts.githubTag(MONITOR_ID) }

    private fun titles(): List<String> = repoNotifications()
        .mapNotNull { it.notification.extras.getString(Notification.EXTRA_TITLE) }

    private fun awaitTitle(fragment: String) = awaitTrue(description = "a notification saying \"$fragment\"") {
        titles().any { it.contains(fragment) }
    }

    // ---- the flow ------------------------------------------------------------

    @Test
    fun a_first_check_seeds_the_baseline_without_saying_anything() {
        stars.set(13)
        issues.set(listOf(FakeIssue(500, 4, "Consider GrapheneOS instead of gstatic")))
        release.set(FakeRelease(1, "v3.1.1"))
        seed()

        val result = check()
        assertNotNull(result)
        assertTrue(result!!.message, result.ok)

        val state = state()
        assertTrue("the first poll must record a baseline", state.seeded)
        assertEquals(13, state.lastStarCount)
        assertEquals(500L, state.lastIssueId)
        assertEquals("v3.1.1", state.lastReleaseTag)
        assertEquals(Health.UP, runtime().health)
        assertTrue("nothing should be announced: " + titles(), repoNotifications().isEmpty())
    }

    @Test
    fun b_one_new_star_produces_one_notification() {
        stars.set(13)
        seed()
        check()
        assertTrue(repoNotifications().isEmpty())

        stars.set(14)
        // The repository changed, so GitHub would issue a new ETag.
        repoEtag.set("\"repo-2\"")
        check()

        awaitTitle("New star on riveerxd/nightbell")
        assertEquals(1, repoNotifications().size)
        val text = repoNotifications().single().notification.extras
            .getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text, text.contains("13 to 14 stars"))
        assertEquals(14, state().lastStarCount)

        // And the same count again says nothing more.
        val before = repoNotifications().size
        check()
        assertEquals(before, repoNotifications().size)
    }

    @Test
    fun c_a_new_issue_and_a_new_release_each_get_their_own_row() {
        stars.set(13)
        issues.set(listOf(FakeIssue(500, 4, "Old news")))
        release.set(FakeRelease(1, "v3.1.1"))
        seed()
        check()
        assertTrue(repoNotifications().isEmpty())

        issues.set(listOf(FakeIssue(501, 5, "Crash on launch"), FakeIssue(500, 4, "Old news")))
        release.set(FakeRelease(2, "v3.2.0"))
        check()

        awaitTitle("New issue on riveerxd/nightbell")
        awaitTitle("New release on riveerxd/nightbell")
        // Two separate rows: they are two separate things, and one must not
        // silently replace the other.
        assertEquals(titles().toString(), 2, repoNotifications().size)
        assertEquals(501L, state().lastIssueId)
        assertEquals("v3.2.0", state().lastReleaseTag)
    }

    @Test
    fun d_a_pull_request_never_reaches_the_issue_watcher() {
        issues.set(listOf(FakeIssue(500, 4, "Old news")))
        seed()
        check()

        issues.set(
            listOf(
                FakeIssue(501, 5, "Bump okhttp", author = "dependabot", pull = true),
                FakeIssue(500, 4, "Old news"),
            ),
        )
        check()

        assertTrue("a pull request was announced as an issue: " + titles(), repoNotifications().isEmpty())
        assertEquals(500L, state().lastIssueId)
        assertEquals(501L, state().lastPullId)
    }

    @Test
    fun e_a_not_modified_response_changes_nothing() {
        stars.set(13)
        issues.set(listOf(FakeIssue(500, 4, "Old news")))
        release.set(FakeRelease(1, "v3.1.1"))
        seed()
        check()
        val before = state()

        // Same ETag, so the repository endpoint answers 304.
        check()
        val after = state()

        assertTrue(repoNotifications().isEmpty())
        assertEquals(before.lastStarCount, after.lastStarCount)
        assertEquals(before.lastIssueId, after.lastIssueId)
        assertEquals(before.lastReleaseTag, after.lastReleaseTag)
        assertEquals(before.repoEtag, after.repoEtag)
        assertEquals(Health.UP, runtime().health)
    }

    @Test
    fun f_being_rate_limited_is_not_an_outage() {
        stars.set(13)
        seed()
        check()
        val healthy = runtime()
        assertEquals(Health.UP, healthy.health)
        val samplesBefore = healthy.samples.size

        forced.set(
            TinyHttpServer.Response(
                code = 403,
                reason = "rate limit exceeded",
                body = """{"message":"API rate limit exceeded"}""",
                extraHeaders = mapOf(
                    "x-ratelimit-limit" to "60",
                    "x-ratelimit-remaining" to "0",
                    "x-ratelimit-reset" to "1787776320",
                ),
            ),
        )
        val result = check()
        forced.set(null)

        // No verdict, so no sample, no health change and above all no alert.
        assertEquals(null, result)
        val after = runtime()
        assertEquals(Health.UP, after.health)
        assertEquals(samplesBefore, after.samples.size)
        assertFalse(after.alerting)
        assertTrue(after.github.rateLimited)
        assertEquals(0, after.github.rateRemaining)
        assertTrue(notifications.activeNotifications.isEmpty())
        // The state the card reads says what happened, in words.
        assertTrue(after.github.rateSummary(System.currentTimeMillis()).contains("Rate limited"))
    }

    @Test
    fun g_mark_seen_clears_the_rows_and_leaves_the_state_alone() {
        stars.set(13)
        seed()
        check()
        stars.set(20)
        repoEtag.set("\"repo-2\"")
        check()
        awaitTitle("New stars on riveerxd/nightbell")

        appContext.sendBroadcast(
            android.content.Intent(appContext, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_MARK_SEEN
                putExtra(AlertActionReceiver.EXTRA_MONITOR_ID, MONITOR_ID)
            },
        )

        awaitTrue(description = "the repository rows to be cleared") { repoNotifications().isEmpty() }
        awaitTrue(description = "the seen timestamp to be written") { state().seenAt > 0L }
        // Marked seen means read, not un-seen: the counts stay where they were, or
        // the next check would announce all of it again.
        assertEquals(20, state().lastStarCount)
    }

    @Test
    fun h_muting_for_a_day_silences_the_repository_without_stopping_it() {
        stars.set(13)
        seed()
        check()

        appContext.sendBroadcast(
            android.content.Intent(appContext, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_MUTE_24H
                putExtra(AlertActionReceiver.EXTRA_MONITOR_ID, MONITOR_ID)
            },
        )
        awaitTrue(description = "the mute to be persisted") {
            runtime().mutedUntil > System.currentTimeMillis() + 23 * 60 * 60 * 1000L
        }

        stars.set(30)
        repoEtag.set("\"repo-2\"")
        check()

        assertTrue("a muted repository still shouted: " + titles(), repoNotifications().isEmpty())
        // Still watching, though: the count advanced, so the mute lifting does not
        // release a day of backdated notifications.
        assertEquals(30, state().lastStarCount)
    }

    @Test
    fun i_a_keyword_filter_holds_on_device() {
        issues.set(listOf(FakeIssue(500, 4, "Old news")))
        seed(
            GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnStars = false,
                issueKeywords = listOf("crash"),
            ),
        )
        check()

        issues.set(listOf(FakeIssue(501, 5, "Typo in the readme"), FakeIssue(500, 4, "Old news")))
        check()
        assertTrue("a non-matching issue was announced: " + titles(), repoNotifications().isEmpty())

        issues.set(
            listOf(
                FakeIssue(502, 6, "Crash on launch"),
                FakeIssue(501, 5, "Typo in the readme"),
                FakeIssue(500, 4, "Old news"),
            ),
        )
        check()
        awaitTitle("New issue on riveerxd/nightbell")
        assertEquals(1, repoNotifications().size)
    }

    @Test
    fun j_an_update_notice_is_posted_once_and_can_be_refused() {
        // The fake server's repo endpoint doubles as the releases/latest endpoint
        // the update checker asks about, so this is the real notification path.
        release.set(FakeRelease(9, "v9.9.9"))
        runBlocking {
            graph.store.updateSettings { it.copy(updateChecksEnabled = true) }
        }

        val posted = runBlocking { engine.checkForAppUpdate(force = true) }
        assertTrue("no update notification was posted", posted)
        awaitTrue(description = "the update notification") {
            notifications.activeNotifications.any {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == "Nightbell update available"
            }
        }
        val body = notifications.activeNotifications
            .first { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Nightbell update available" }
            .notification.extras.getString(Notification.EXTRA_TEXT)
        assertEquals("Version 9.9.9 is ready", body)

        // Said once. A second check finds the same version and stays quiet.
        assertFalse(runBlocking { engine.checkForAppUpdate(force = true) })

        // "Ignore this version" takes the row down and keeps it down.
        appContext.sendBroadcast(
            android.content.Intent(appContext, AlertActionReceiver::class.java).apply {
                action = AlertActionReceiver.ACTION_UPDATE_IGNORE
                putExtra(AlertActionReceiver.EXTRA_VERSION, "9.9.9")
            },
        )
        awaitTrue(description = "the ignored version to be recorded") {
            runBlocking { graph.store.currentSnapshot() }.update.ignoredVersion == "9.9.9"
        }
        assertFalse(runBlocking { engine.checkForAppUpdate(force = true) })
    }

    @Test
    fun k_the_update_source_can_be_f_droid() {
        runBlocking {
            graph.store.updateSettings {
                it.copy(updateChecksEnabled = true, updateSource = UpdateSource.FDROID)
            }
        }
        // The fake server answers the F-Droid package endpoint with the same
        // handler, which returns the repo JSON: no versionName, so nothing to say.
        assertFalse(runBlocking { engine.checkForAppUpdate(force = true) })
        val state = runBlocking { graph.store.currentSnapshot() }.update
        assertTrue("the attempt should still be recorded", state.lastCheckedAt > 0L)
    }

    /**
     * The feature end to end: a comment lands and the phone says so.
     *
     * Everything before this proves a rule in isolation. This is the one that
     * says the whole chain works: the fourth request goes out, the payload
     * parses, the decider announces, and a row appears in the shade with the
     * comment's own words on it.
     */
    @Test
    fun l_a_new_comment_reaches_the_shade_with_what_was_said() {
        val watch = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnComments = true,
            // Off, so the only rows in play are the comment rows.
            notifyOnStars = false,
            notifyOnIssues = false,
            watchReleases = false,
        )
        comments.set(listOf(FakeComment(id = 100L, issue = 12)))
        seed(watch)
        check()
        // The first look is the track learning where it is, however old the page.
        assertTrue("a first look must be silent: " + titles(), repoNotifications().isEmpty())
        assertEquals(100L, state().lastIssueCommentId)

        comments.set(
            listOf(
                FakeComment(id = 101L, issue = 47, author = "river", body = "Reproduced on 3.7.0."),
                FakeComment(id = 100L, issue = 12),
            ),
        )
        check()

        awaitTitle("New comment on riveerxd/nightbell")
        assertEquals(1, repoNotifications().size)
        val posted = repoNotifications().single()
        val text = posted.notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertEquals("#47 by river: Reproduced on 3.7.0.", text)
        // On its own channel, so muting replies leaves releases alone.
        assertTrue(
            "a comment must not post on the news channel: " + posted.notification.channelId,
            posted.notification.channelId.startsWith("nightbell.comments."),
        )
        assertEquals(101L, state().lastIssueCommentId)

        // The same page again says nothing more.
        val before = repoNotifications().size
        check()
        assertEquals(before, repoNotifications().size)
    }

    @Test
    fun m_replies_on_separate_threads_get_separate_rows_and_one_thread_gets_one() {
        val watch = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnComments = true,
            notifyOnStars = false,
            notifyOnIssues = false,
            watchReleases = false,
        )
        comments.set(listOf(FakeComment(id = 100L, issue = 1)))
        seed(watch)
        check()
        assertTrue(repoNotifications().isEmpty())

        // Three threads: three separate things to read, so three rows.
        comments.set(
            listOf(
                FakeComment(id = 101L, issue = 11),
                FakeComment(id = 102L, issue = 12),
                FakeComment(id = 103L, issue = 13),
            ),
        )
        check()
        awaitTrue(description = "three comment rows") { repoNotifications().size == 3 }
        assertEquals(3, titles().count { it.contains("New comment on") })

        notifications.cancelAll()

        // Five replies to one thread: one conversation, one tap target, one row.
        comments.set((201L..205L).map { FakeComment(id = it, issue = 50, author = "bob") })
        check()
        awaitTitle("5 new comments on riveerxd/nightbell")
        assertEquals(1, repoNotifications().size)
        val text = repoNotifications().single().notification.extras
            .getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text, text.startsWith("#50, latest by bob"))
    }

    @Test
    fun n_a_bot_comment_is_silent_until_the_switch_says_otherwise() {
        val watch = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnComments = true,
            notifyOnStars = false,
            notifyOnIssues = false,
            watchReleases = false,
        )
        comments.set(listOf(FakeComment(id = 100L, issue = 1)))
        seed(watch)
        check()

        comments.set(
            listOf(FakeComment(id = 101L, issue = 60, author = "ci-bot[bot]", app = true)),
        )
        check()
        assertTrue("a build bot must not page: " + titles(), repoNotifications().isEmpty())
        // Skipped is not unseen. The watermark moved, so turning the switch on is
        // not a request for everything the bot has ever said.
        assertEquals(101L, state().lastIssueCommentId)
    }

    /**
     * Photographs two comment rows in the real shade.
     *
     * Captured inside the test rather than left on screen for `adb` to find,
     * because [tearDown] clears the shade the moment this returns. Follows the
     * pattern the urgent heads-up tests already use: post, open the shade, ask
     * the platform for the pixels, write the file the runner will pull.
     */
    @Test
    fun zz_posts_comment_rows_for_eyeballing() {
        val watch = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnComments = true,
            notifyOnStars = false,
            notifyOnIssues = false,
            watchReleases = false,
        )
        comments.set(listOf(FakeComment(id = 100L, issue = 1)))
        seed(watch)
        check()
        comments.set(
            listOf(
                FakeComment(
                    id = 101L,
                    issue = 47,
                    author = "shortwavesurfer2009",
                    body = "Still happens on a fresh install of 3.7.0, on a Pixel 6.",
                ),
                FakeComment(id = 102L, issue = 8, author = "river", body = "Fixed on master."),
            ),
        )
        check()
        awaitTrue(description = "two comment rows to photograph") { repoNotifications().size == 2 }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        // Long enough for the shade to finish sliding down.
        Thread.sleep(2_000)
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(appContext.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "gh-comment-shade.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
    }

    private companion object {
        const val MONITOR_ID = "github-e2e"
    }
}
