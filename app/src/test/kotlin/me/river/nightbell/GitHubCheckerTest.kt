package me.river.nightbell

import java.util.concurrent.CopyOnWriteArrayList
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of a repository monitor, against a real socket.
 *
 * Everything here is about how GitHub actually behaves rather than about what
 * the app does with the answer: conditional GETs, the two shapes of "too many
 * requests", a 404 from `releases/latest` meaning "there are none", and the
 * absolute rule that the token appears in exactly one header and nowhere else.
 */
class GitHubCheckerTest {

    private val repoJson = """
        {
          "full_name": "riveerxd/nightbell",
          "stargazers_count": 13,
          "open_issues_count": 1,
          "forks_count": 2,
          "subscribers_count": 3,
          "pushed_at": "2026-08-26T19:15:34Z"
        }
    """.trimIndent()

    private val issuesJson = """
        [
          {
            "id": 5260182706,
            "number": 4,
            "title": "[Feature] Consider using GrapheneOS checker instead of gstatic",
            "body": "gstatic is Google owned.",
            "created_at": "2026-08-26T18:22:30Z",
            "html_url": "https://github.com/riveerxd/nightbell/issues/4",
            "user": { "login": "shortwavesurfer2009" }
          },
          {
            "id": 5260182000,
            "number": 3,
            "title": "Bump okhttp",
            "body": "",
            "created_at": "2026-08-20T10:00:00Z",
            "html_url": "https://github.com/riveerxd/nightbell/pull/3",
            "user": { "login": "dependabot" },
            "pull_request": { "url": "https://api.github.com/repos/riveerxd/nightbell/pulls/3" }
          }
        ]
    """.trimIndent()

    private val releaseJson = """
        {
          "id": 377361469,
          "tag_name": "v3.1.1",
          "name": "Nightbell 3.1.1",
          "prerelease": false,
          "draft": false,
          "published_at": "2026-08-26T19:11:44Z",
          "html_url": "https://github.com/riveerxd/nightbell/releases/tag/v3.1.1"
        }
    """.trimIndent()

    private fun monitor(watch: GitHubWatch = GitHubWatch(owner = "riveerxd", repo = "nightbell")) =
        Monitor(
            id = "gh",
            kind = MonitorKind.GITHUB_REPO,
            url = watch.repository.url,
            timeoutSeconds = 5,
            github = watch,
        )

    private fun checker(
        server: TinyHttpServer,
        settings: GlobalSettings = GlobalSettings(),
    ) = GitHubChecker(
        settingsFor = { settings },
        apiBase = server.baseUrl,
        // No artificial pacing: the point of the gap is politeness to GitHub, and
        // a test that pays it three times over is just a slower test.
        minGapMs = 0L,
    )

    /** A server that answers all three endpoints, with per-path ETag support. */
    private fun apiServer(
        etags: Map<String, String> = emptyMap(),
        rateRemaining: Int = 59,
        onRequest: (TinyHttpServer.Request) -> Unit = {},
    ) = TinyHttpServer { request ->
        onRequest(request)
        val headers = mapOf(
            "x-ratelimit-limit" to "60",
            "x-ratelimit-remaining" to rateRemaining.toString(),
            "x-ratelimit-reset" to "1787776320",
        )
        val path = request.path.substringBefore('?')
        val etag = etags[path]
        if (etag != null && request.headers["if-none-match"] == etag) {
            return@TinyHttpServer TinyHttpServer.Response(
                code = 304,
                reason = "Not Modified",
                extraHeaders = headers + ("ETag" to etag),
            )
        }
        val body = when {
            path.endsWith("/issues") -> issuesJson
            path.endsWith("/releases/latest") -> releaseJson
            else -> repoJson
        }
        TinyHttpServer.Response(
            body = body,
            contentType = "application/json",
            extraHeaders = headers + (etag?.let { mapOf("ETag" to it) } ?: emptyMap()),
        )
    }

    @Test
    fun `a poll reads the repository, its issues and its latest release`() {
        apiServer().use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            val result = outcome.result
            assertNotNull(result)
            assertTrue(result!!.message, result.ok)
            assertEquals(200, result.statusCode)

            val snapshot = outcome.snapshot!!
            assertEquals(13, snapshot.stars)
            assertEquals(1, snapshot.openIssues)
            assertEquals(2, snapshot.forks)
            assertEquals(3, snapshot.watchers)
            assertEquals("2026-08-26T19:15:34Z", snapshot.pushedAt)
            assertEquals(2, snapshot.issues.size)
            assertEquals("v3.1.1", snapshot.release?.tag)
            assertEquals(59, snapshot.rate.remaining)
            assertEquals(60, snapshot.rate.limit)

            // The message is what the card and the sample note carry.
            assertTrue(result.message, result.message.contains("13 stars"))
            assertTrue(result.message, result.message.contains("1 open issue"))
        }
    }

    @Test
    fun `pull requests come back flagged rather than filtered out here`() {
        // The checker reports what GitHub sent. Deciding that a pull request is
        // not an issue is a product rule and lives in GitHubEvents, where it can
        // be argued about without a socket.
        apiServer().use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            val items = outcome.snapshot!!.issues
            assertEquals(listOf(false, true), items.map { it.isPullRequest })
            assertEquals("shortwavesurfer2009", items.first().author)
            assertEquals(4, items.first().number)
        }
    }

    @Test
    fun `the issues query asks for open issues, newest first`() {
        val paths = CopyOnWriteArrayList<String>()
        apiServer(onRequest = { paths += it.path }).use { server ->
            runBlocking { checker(server).poll(monitor(), GitHubState()) }
        }
        val issues = paths.first { it.contains("/issues") }
        assertTrue(issues, issues.contains("state=open"))
        assertTrue(issues, issues.contains("sort=created"))
        assertTrue(issues, issues.contains("direction=desc"))
        assertTrue(issues, issues.contains("per_page="))
    }

    @Test
    fun `a conditional GET is sent and a 304 preserves state`() {
        val etags = mapOf(
            "/repos/riveerxd/nightbell" to "\"repo-1\"",
            "/repos/riveerxd/nightbell/issues" to "\"issues-1\"",
            "/repos/riveerxd/nightbell/releases/latest" to "\"rel-1\"",
        )
        val sent = CopyOnWriteArrayList<Pair<String, String?>>()
        apiServer(etags = etags, onRequest = { sent += it.path to it.headers["if-none-match"] })
            .use { server ->
                val gh = checker(server)
                val first = runBlocking { gh.poll(monitor(), GitHubState()) }
                // First time round there is nothing to send, and every ETag comes back.
                assertTrue(sent.all { it.second == null })
                val etagState = first.snapshot!!.etags
                assertEquals("\"repo-1\"", etagState.repo)
                assertEquals("\"issues-1\"", etagState.issues)
                assertEquals("\"rel-1\"", etagState.releases)

                val stored = GitHubState(
                    seeded = true,
                    issuesSeeded = true,
                    releasesSeeded = true,
                    lastStarCount = 13,
                    openIssues = 1,
                    forks = 2,
                    watchers = 3,
                    pushedAt = "2026-08-26T19:15:34Z",
                    lastIssueId = 5260182706,
                    lastReleaseId = 377361469,
                    lastReleaseTag = "v3.1.1",
                    repoEtag = etagState.repo,
                    issuesEtag = etagState.issues,
                    releasesEtag = etagState.releases,
                )
                sent.clear()
                val second = runBlocking { gh.poll(monitor(), stored) }

                // Every request carried its ETag, and every answer was a 304.
                assertEquals(3, sent.size)
                assertTrue(sent.toString(), sent.all { it.second != null })

                val snapshot = second.snapshot!!
                assertFalse(snapshot.repoChanged)
                assertFalse(snapshot.issuesChanged)
                assertFalse(snapshot.releaseChanged)
                // The values are carried forward rather than lost, so nothing
                // downstream sees a repository that suddenly has zero stars.
                assertEquals(13, snapshot.stars)
                assertEquals(1, snapshot.openIssues)
                assertEquals(2, snapshot.forks)
                assertEquals("2026-08-26T19:15:34Z", snapshot.pushedAt)
                assertEquals(etagState.repo, snapshot.etags.repo)
                assertEquals(etagState.issues, snapshot.etags.issues)
                assertEquals(etagState.releases, snapshot.etags.releases)
                // A 304 is a healthy answer, not a failure.
                assertTrue(second.result!!.ok)
                assertEquals(304, second.result!!.statusCode)
                assertTrue(second.result!!.message.contains("unchanged"))
            }
    }

    @Test
    fun `403 with no budget left is rate-limit state, not an outage`() {
        TinyHttpServer { _ ->
            TinyHttpServer.Response(
                code = 403,
                reason = "rate limit exceeded",
                body = """{"message":"API rate limit exceeded"}""",
                extraHeaders = mapOf(
                    "x-ratelimit-limit" to "60",
                    "x-ratelimit-remaining" to "0",
                    "x-ratelimit-reset" to "1787776320",
                ),
            )
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            // No verdict at all: nothing was learned about the repository, so
            // nothing may be claimed about it.
            assertNull(outcome.result)
            assertNull(outcome.snapshot)
            assertTrue(outcome.rateLimited)
            assertTrue(outcome.state.rateLimited)
            assertEquals(0, outcome.state.rateRemaining)
            assertEquals(60, outcome.state.rateLimit)
            assertEquals(1787776320L * 1000, outcome.state.rateResetAt)
        }
    }

    @Test
    fun `429 with a Retry-After is rate-limit state too`() {
        TinyHttpServer { _ ->
            TinyHttpServer.Response(
                code = 429,
                reason = "Too Many Requests",
                body = "",
                extraHeaders = mapOf("Retry-After" to "60"),
            )
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            assertNull(outcome.result)
            assertTrue(outcome.rateLimited)
            assertTrue(outcome.state.rateLimited)
            // Retry-After is the authority when there is one: a secondary limit
            // has its own clock and leaves the primary counters alone.
            assertTrue(outcome.state.rateResetAt > 0L)
        }
    }

    @Test
    fun `a 403 with budget remaining is a real problem and says so`() {
        // Not the limiter. GitHub also answers 403 when a token may not read
        // something, and hiding that behind a rate-limit chip would leave the
        // user waiting for a reset that changes nothing.
        TinyHttpServer { _ ->
            TinyHttpServer.Response(
                code = 403,
                reason = "Forbidden",
                body = """{"message":"Resource not accessible by personal access token"}""",
                extraHeaders = mapOf(
                    "x-ratelimit-limit" to "5000",
                    "x-ratelimit-remaining" to "4998",
                ),
            )
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            assertFalse(outcome.rateLimited)
            val result = outcome.result!!
            assertFalse(result.ok)
            assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
            assertEquals(403, result.statusCode)
        }
    }

    @Test
    fun `a missing repository is reported as a configuration problem`() {
        TinyHttpServer { _ ->
            TinyHttpServer.Response(code = 404, reason = "Not Found", body = """{"message":"Not Found"}""")
        }.use { server ->
            val result = runBlocking { checker(server).poll(monitor(), GitHubState()) }.result!!
            assertFalse(result.ok)
            assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
            assertTrue(result.message, result.message.contains("Repository not found"))
        }
    }

    @Test
    fun `a repository with no releases is not a failed check`() {
        TinyHttpServer { request ->
            when {
                request.path.endsWith("/releases/latest") ->
                    TinyHttpServer.Response(code = 404, reason = "Not Found", body = """{"message":"Not Found"}""")
                request.path.contains("/issues") ->
                    TinyHttpServer.Response(body = "[]", contentType = "application/json")
                else -> TinyHttpServer.Response(body = repoJson, contentType = "application/json")
            }
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(), GitHubState()) }
            assertTrue(outcome.result!!.ok)
            assertNull(outcome.snapshot!!.release)
            // Flagged as looked-at, so the first real release counts as news.
            assertTrue(outcome.snapshot!!.releaseChanged)
        }
    }

    @Test
    fun `a monitor with no repository never opens a socket`() {
        var hits = 0
        TinyHttpServer { _ -> hits++; TinyHttpServer.Response(body = "{}") }.use { server ->
            val result = runBlocking {
                checker(server).poll(monitor(GitHubWatch()), GitHubState())
            }.result!!
            assertFalse(result.ok)
            assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
            assertEquals(0, hits)
        }
    }

    @Test
    fun `endpoints nobody asked about are never requested`() {
        val paths = CopyOnWriteArrayList<String>()
        apiServer(onRequest = { paths += it.path.substringBefore('?') }).use { server ->
            val quiet = GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnIssues = false,
                watchPullRequests = false,
                watchReleases = false,
            )
            runBlocking { checker(server).poll(monitor(quiet), GitHubState()) }
        }
        // One request, not three. The budget is sixty an hour for the whole
        // device, so asking about a track nobody is watching is a real cost.
        assertEquals(listOf("/repos/riveerxd/nightbell"), paths.toList())
    }

    @Test
    fun `prerelease watching lists releases instead of asking for the latest`() {
        val paths = CopyOnWriteArrayList<String>()
        TinyHttpServer { request ->
            paths += request.path.substringBefore('?')
            when {
                request.path.contains("/releases") -> TinyHttpServer.Response(
                    body = "[$releaseJson]",
                    contentType = "application/json",
                )
                request.path.contains("/issues") ->
                    TinyHttpServer.Response(body = "[]", contentType = "application/json")
                else -> TinyHttpServer.Response(body = repoJson, contentType = "application/json")
            }
        }.use { server ->
            val watch = GitHubWatch(owner = "riveerxd", repo = "nightbell", includePrereleases = true)
            val outcome = runBlocking { checker(server).poll(monitor(watch), GitHubState()) }
            assertEquals("v3.1.1", outcome.snapshot!!.release?.tag)
            assertTrue(paths.toString(), paths.none { it.endsWith("/releases/latest") })
            assertTrue(paths.toString(), paths.any { it.endsWith("/releases") })
        }
    }

    // ---- the token -----------------------------------------------------------

    @Test
    fun `a token is sent as a bearer header and nowhere else`() {
        val seen = CopyOnWriteArrayList<TinyHttpServer.Request>()
        apiServer(onRequest = { seen += it }).use { server ->
            val settings = GlobalSettings(githubToken = TOKEN)
            runBlocking { checker(server, settings).poll(monitor(), GitHubState()) }
        }
        assertTrue(seen.isNotEmpty())
        seen.forEach { request ->
            assertEquals("Bearer $TOKEN", request.headers["authorization"])
            // Not in the path, not in the query string, not in the body.
            assertFalse(request.path, request.path.contains(TOKEN))
            assertFalse(request.body, request.body.contains(TOKEN))
        }
    }

    @Test
    fun `no token means no authorization header at all`() {
        val seen = CopyOnWriteArrayList<TinyHttpServer.Request>()
        apiServer(onRequest = { seen += it }).use { server ->
            runBlocking { checker(server).poll(monitor(), GitHubState()) }
        }
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertNull(it.headers["authorization"]) }
    }

    @Test
    fun `the token never reaches the check result, even when GitHub echoes it`() {
        // The backstop. Nightbell composes none of these strings from the token,
        // but a server can put anything in a body, and a detail line ends up on
        // the monitor's screen and in a screenshot attached to a bug report.
        TinyHttpServer { _ ->
            TinyHttpServer.Response(
                code = 401,
                reason = "Unauthorized",
                body = """{"message":"Bad credentials: $TOKEN"}""",
            )
        }.use { server ->
            val settings = GlobalSettings(githubToken = TOKEN)
            val result = runBlocking {
                checker(server, settings).poll(monitor(), GitHubState())
            }.result!!
            assertFalse(result.ok)
            assertFalse(result.message, result.message.contains(TOKEN))
            assertFalse(result.detail, result.detail.contains(TOKEN))
            assertFalse(result.bodyPreview, result.bodyPreview.contains(TOKEN))
            assertTrue(result.message, result.message.contains("rejected the token"))
        }
    }

    @Test
    fun `a healthy poll leaks nothing about the token either`() {
        apiServer().use { server ->
            val settings = GlobalSettings(githubToken = TOKEN)
            val result = runBlocking {
                checker(server, settings).poll(monitor(), GitHubState())
            }.result!!
            assertFalse(result.message.contains(TOKEN))
            assertFalse(result.detail.contains(TOKEN))
            assertFalse(result.bodyPreview.contains(TOKEN))
        }
    }

    private companion object {
        /** Shaped like a real fine-grained token, and not one. */
        const val TOKEN = "github_pat_11ABCDEFG0aBcDeFgHiJkL_mNoPqRsTuVwXyZ0123456789abcdEFGH"
    }
}
