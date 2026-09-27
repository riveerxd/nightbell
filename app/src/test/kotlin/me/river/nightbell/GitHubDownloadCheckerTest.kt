package me.river.nightbell

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire half of counting downloads.
 *
 * Everything here is about which request goes out and what is done with the
 * answer, against a real socket. The arithmetic is [GitHubDownloadsTest]'s and
 * the rules about announcing are [GitHubDownloadEventsTest]'s.
 *
 * The case that earns this file is the page walk. A repository whose releases
 * do not fit one page produces a number that is a floor, and the difference
 * between a floor and a total has to survive the trip from the socket to the
 * card.
 */
class GitHubDownloadCheckerTest {

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

    /** One release carrying an APK and its signature, the shape this repo ships. */
    private fun releaseJson(tag: String, apk: Int, asc: Int = 0, draft: Boolean = false) = """
        {
          "id": ${tag.hashCode()},
          "tag_name": "$tag",
          "name": "Nightbell $tag",
          "prerelease": false,
          "draft": $draft,
          "published_at": "2026-08-26T19:11:44Z",
          "html_url": "https://github.com/riveerxd/nightbell/releases/tag/$tag",
          "assets": [
            { "name": "Nightbell-${tag.removePrefix("v")}-release.apk", "download_count": $apk },
            { "name": "Nightbell-${tag.removePrefix("v")}-release.apk.asc", "download_count": $asc }
          ]
        }
    """.trimIndent()

    private fun watch(
        trackDownloads: Boolean = true,
        acrossAll: Boolean = true,
        filter: String = "",
    ) = GitHubWatch(
        owner = "riveerxd",
        repo = "nightbell",
        notifyOnIssues = false,
        trackDownloads = trackDownloads,
        downloadsAcrossAllReleases = acrossAll,
        downloadFilterText = filter,
    )

    private fun monitor(watch: GitHubWatch) = Monitor(
        id = "gh",
        kind = MonitorKind.GITHUB_REPO,
        url = watch.repository.url,
        timeoutSeconds = 5,
        github = watch,
    )

    private fun checker(server: TinyHttpServer, settings: GlobalSettings = GlobalSettings()) =
        GitHubChecker(
            settingsFor = { settings },
            apiBase = server.baseUrl,
            minGapMs = 0L,
        )

    /**
     * A server that pages the release list.
     *
     * [pages] is one list of release bodies per page, and a `Link` header
     * pointing at the next one is sent for every page but the last, which is
     * how GitHub actually says there is more.
     */
    private fun pagedServer(
        pages: List<List<String>>,
        rateRemaining: Int = 59,
        seen: MutableList<String> = CopyOnWriteArrayList(),
    ) = TinyHttpServer { request ->
        seen += request.path
        val headers = mapOf(
            "x-ratelimit-limit" to "60",
            "x-ratelimit-remaining" to rateRemaining.toString(),
            "x-ratelimit-reset" to "1787776320",
        )
        val path = request.path.substringBefore('?')
        val query = request.path.substringAfter('?', "")
        // The narrow call a monitor makes when it is not counting. It answers
        // with one release object rather than an array, and it carries the same
        // assets, which is the whole reason the file types can be learned
        // without anybody switching counting on.
        if (path.endsWith("/releases/latest")) {
            val newest = pages.firstOrNull()?.firstOrNull()
            return@TinyHttpServer if (newest == null) {
                TinyHttpServer.Response(
                    code = 404,
                    reason = "Not Found",
                    body = """{ "message": "Not Found" }""",
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            } else {
                TinyHttpServer.Response(
                    body = newest,
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            }
        }
        if (!path.endsWith("/releases")) {
            return@TinyHttpServer TinyHttpServer.Response(
                body = repoJson,
                contentType = "application/json",
                extraHeaders = headers,
            )
        }
        // Anchored on the separator, because "per_page=100" contains "page="
        // and a looser match reads the page size as the page number.
        val page = PAGE_PARAM.find(query)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        val body = pages.getOrNull(page - 1).orEmpty().joinToString(",", "[", "]")
        val link = if (page < pages.size) {
            mapOf("Link" to "<${server0(request)}/repos/riveerxd/nightbell/releases?per_page=100&page=${page + 1}>; rel=\"next\"")
        } else {
            emptyMap()
        }
        TinyHttpServer.Response(
            body = body,
            contentType = "application/json",
            extraHeaders = headers + link,
        )
    }

    private companion object {
        val PAGE_PARAM = Regex("""(?:^|&)page=(\d+)""")
    }

    /** The absolute base a Link header has to carry, taken from the request. */
    private fun server0(request: TinyHttpServer.Request): String =
        "http://" + (request.headers["host"] ?: "127.0.0.1")

    // ---- which request goes out ---------------------------------------------

    @Test
    fun `tracking downloads asks for the release list at the full page size`() {
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 295))), seen = seen).use { server ->
            runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
        }
        assertTrue(seen.any { it.contains("/releases?per_page=100") })
        assertFalse(seen.any { it.contains("/releases/latest") })
    }

    @Test
    fun `not tracking downloads leaves the narrow call exactly as it was`() {
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(listOf(emptyList()), seen = seen).use { server ->
            runBlocking {
                checker(server).poll(monitor(watch(trackDownloads = false)), GitHubState())
            }
        }
        assertTrue(seen.any { it.contains("/releases/latest") })
        assertFalse(seen.any { it.contains("per_page=100") })
    }

    @Test
    fun `the counts ride the call the release watcher was making anyway`() {
        // The whole cost argument for this feature: one request serves both.
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 295))), seen = seen).use { server ->
            runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
        }
        assertEquals(1, seen.count { it.contains("/releases") })
    }

    // ---- what comes back -----------------------------------------------------

    @Test
    fun `one page is summed and reported as complete`() {
        val page = listOf(
            releaseJson("v3.13.0", apk = 295, asc = 2),
            releaseJson("v3.12.0", apk = 125, asc = 1),
        )
        pagedServer(listOf(page)).use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            val downloads = outcome.snapshot?.downloads
            assertNotNull(downloads)
            assertEquals(297, downloads!!.latest)
            assertEquals(423, downloads.total)
            assertEquals(2, downloads.releases)
            assertTrue(downloads.complete)
        }
    }

    @Test
    fun `a filter narrows the total to the files it names`() {
        val page = listOf(
            releaseJson("v3.13.0", apk = 295, asc = 2),
            releaseJson("v3.12.0", apk = 125, asc = 1),
        )
        pagedServer(listOf(page)).use { server ->
            val outcome = runBlocking {
                checker(server).poll(monitor(watch(filter = ".apk")), GitHubState())
            }
            assertEquals(420, outcome.snapshot?.downloads?.total)
            assertEquals(295, outcome.snapshot?.downloads?.latest)
        }
    }

    @Test
    fun `the breakdown groups every release of one file into one row`() {
        val page = (1..4).map { n -> releaseJson("v3.$n.0", apk = n * 10) }
        pagedServer(listOf(page)).use { server ->
            val outcome = runBlocking {
                checker(server).poll(monitor(watch(filter = ".apk")), GitHubState())
            }
            val byFile = outcome.snapshot?.downloads?.byFile.orEmpty()
            assertEquals(1, byFile.size)
            assertEquals("nightbell-*-release.apk", byFile.single().pattern)
            assertEquals(100, byFile.single().downloads)
            assertEquals(4, byFile.single().releases)
        }
    }

    @Test
    fun `the extensions seen are offered for the chips`() {
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 1, asc = 1)))).use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            assertEquals(listOf(".apk", ".asc"), outcome.snapshot?.assetTypes)
        }
    }

    @Test
    fun `the file types are learned even when nothing is being counted`() {
        // The setup screen offers them as chips, and it has to be able to do
        // that the first time somebody switches counting on rather than after a
        // save and a second visit. The payload carries the assets either way.
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 1, asc = 1)))).use { server ->
            val outcome = runBlocking {
                checker(server).poll(monitor(watch(trackDownloads = false)), GitHubState())
            }
            assertNull(outcome.snapshot?.downloads)
            assertEquals(listOf(".apk", ".asc"), outcome.snapshot?.assetTypes)
        }
    }

    @Test
    fun `latest only counts the newest release and is always complete`() {
        val page = listOf(
            releaseJson("v3.13.0", apk = 295),
            releaseJson("v3.12.0", apk = 125),
        )
        pagedServer(listOf(page)).use { server ->
            val outcome = runBlocking {
                checker(server).poll(monitor(watch(acrossAll = false)), GitHubState())
            }
            val downloads = outcome.snapshot?.downloads
            assertEquals(295, downloads?.latest)
            assertEquals(295, downloads?.total)
            assertEquals(1, downloads?.releases)
            assertTrue(downloads!!.complete)
        }
    }

    @Test
    fun `a draft is not counted even when it is the newest thing on the page`() {
        val page = listOf(
            releaseJson("v4.0.0", apk = 900, draft = true),
            releaseJson("v3.13.0", apk = 295),
        )
        pagedServer(listOf(page)).use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            assertEquals(295, outcome.snapshot?.downloads?.total)
            assertEquals(295, outcome.snapshot?.downloads?.latest)
        }
    }

    // ---- the page walk -------------------------------------------------------

    @Test
    fun `a next link is followed and the pages are added together`() {
        val pages = listOf(
            listOf(releaseJson("v3.13.0", apk = 300)),
            listOf(releaseJson("v3.12.0", apk = 120)),
        )
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(pages, seen = seen).use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            assertEquals(420, outcome.snapshot?.downloads?.total)
            assertEquals(2, outcome.snapshot?.downloads?.releases)
            assertTrue(outcome.snapshot?.downloads?.complete == true)
            assertEquals(2, seen.count { it.contains("/releases") })
        }
    }

    @Test
    fun `the walk stops at the cap and says the number is a floor`() {
        // More pages than the cap allows. The total is real as far as it goes
        // and must not be stored as if it covered the repository.
        val pages = (1..6).map { n -> listOf(releaseJson("v3.$n.0", apk = 10)) }
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(pages, seen = seen).use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            val downloads = outcome.snapshot?.downloads
            assertFalse(downloads!!.complete)
            assertEquals(GitHubChecker.DOWNLOADS_PAGE_CAP, downloads.releases)
            assertEquals(
                GitHubChecker.DOWNLOADS_PAGE_CAP,
                seen.count { it.contains("/releases") },
            )
        }
    }

    @Test
    fun `latest only never walks, however many pages there are`() {
        val pages = (1..6).map { n -> listOf(releaseJson("v3.$n.0", apk = 10)) }
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(pages, seen = seen).use { server ->
            runBlocking {
                checker(server).poll(monitor(watch(acrossAll = false)), GitHubState())
            }
            assertEquals(1, seen.count { it.contains("/releases") })
        }
    }

    // ---- the budget ----------------------------------------------------------

    @Test
    fun `a thin budget yields rather than taking the wide call`() {
        val seen = CopyOnWriteArrayList<String>()
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 1))), rateRemaining = 1, seen = seen)
            .use { server ->
                val outcome = runBlocking {
                    checker(server).poll(monitor(watch()), GitHubState())
                }
                // The narrow call still answers the release watcher; only the
                // counting is deferred, and nothing is recorded about it.
                assertTrue(seen.any { it.contains("/releases/latest") })
                assertNull(outcome.snapshot?.downloads)
            }
    }

    @Test
    fun `a walk that runs the budget out stops and reports a floor`() {
        // A budget that falls as the walk spends it, which is the only way to
        // reach the guard inside the loop: a device already under the floor
        // never starts the wide call at all, and that is the test above.
        val pages = (1..4).map { n -> listOf(releaseJson("v3.$n.0", apk = 10)) }
        val remaining = java.util.concurrent.atomic.AtomicInteger(5)
        val seen = CopyOnWriteArrayList<String>()
        TinyHttpServer { request ->
            seen += request.path
            val left = remaining.decrementAndGet()
            val headers = mapOf(
                "x-ratelimit-limit" to "60",
                "x-ratelimit-remaining" to left.toString(),
                "x-ratelimit-reset" to "1787776320",
            )
            val path = request.path.substringBefore('?')
            if (!path.endsWith("/releases")) {
                return@TinyHttpServer TinyHttpServer.Response(
                    body = repoJson,
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            }
            val page = PAGE_PARAM.find(request.path.substringAfter('?', ""))
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val host = request.headers["host"] ?: "127.0.0.1"
            TinyHttpServer.Response(
                body = pages.getOrNull(page - 1).orEmpty().joinToString(",", "[", "]"),
                contentType = "application/json",
                extraHeaders = headers + if (page < pages.size) {
                    mapOf(
                        "Link" to "<http://$host/repos/riveerxd/nightbell/releases" +
                            "?per_page=100&page=${page + 1}>; rel=\"next\"",
                    )
                } else {
                    emptyMap()
                },
            )
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            val downloads = outcome.snapshot?.downloads
            assertNotNull(downloads)
            assertFalse(downloads!!.complete)
            // It stopped for the budget rather than for the cap, so it read
            // fewer pages than the cap would have allowed.
            assertTrue(seen.count { it.contains("/releases") } < GitHubChecker.DOWNLOADS_PAGE_CAP)
        }
    }

    // ---- no releases ---------------------------------------------------------

    @Test
    fun `a repository with no releases reports zero rather than nothing`() {
        TinyHttpServer { request ->
            val headers = mapOf(
                "x-ratelimit-limit" to "60",
                "x-ratelimit-remaining" to "59",
                "x-ratelimit-reset" to "1787776320",
            )
            if (request.path.substringBefore('?').endsWith("/releases")) {
                TinyHttpServer.Response(
                    code = 404,
                    reason = "Not Found",
                    body = """{ "message": "Not Found" }""",
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            } else {
                TinyHttpServer.Response(
                    body = repoJson,
                    contentType = "application/json",
                    extraHeaders = headers,
                )
            }
        }.use { server ->
            val outcome = runBlocking { checker(server).poll(monitor(watch()), GitHubState()) }
            val downloads = outcome.snapshot?.downloads
            assertNotNull(downloads)
            assertEquals(0, downloads!!.total)
            assertTrue(downloads.complete)
            assertTrue(outcome.result?.ok == true)
        }
    }

    // ---- the sample ----------------------------------------------------------

    @Test
    fun `the sample carries the reading forward when a poll did not ask`() {
        pagedServer(listOf(listOf(releaseJson("v3.13.0", apk = 295)))).use { server ->
            val previous = GitHubState(
                seeded = true,
                latestDownloads = 295,
                totalDownloads = 1_250,
                totalComplete = true,
            )
            val outcome = runBlocking {
                checker(server).poll(monitor(watch(trackDownloads = false)), previous)
            }
            // Nothing asked, so nothing learned, and the history must not read
            // as the repository losing its downloads for one check.
            assertEquals(1_250, outcome.result?.repo?.totalDownloads)
            assertEquals(295, outcome.result?.repo?.latestDownloads)
        }
    }
}
