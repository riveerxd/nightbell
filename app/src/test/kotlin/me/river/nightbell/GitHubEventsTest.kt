package me.river.nightbell

import me.river.nightbell.domain.DigestMode
import me.river.nightbell.domain.GitHubComment
import me.river.nightbell.domain.GitHubEtags
import me.river.nightbell.domain.GitHubEvent
import me.river.nightbell.domain.GitHubEvents
import me.river.nightbell.domain.GitHubItem
import me.river.nightbell.domain.GitHubRelease
import me.river.nightbell.domain.GitHubSnapshot
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule about what a repository monitor says out loud.
 *
 * All of it is here rather than behind a network, because all of it is the sort
 * of thing that can only otherwise be tested by starring a repository from a
 * second account and waiting a quarter of an hour.
 */
class GitHubEventsTest {

    private val watch = GitHubWatch(owner = "riveerxd", repo = "nightbell")

    private fun snapshot(
        stars: Int = 10,
        openIssues: Int = 1,
        issues: List<GitHubItem> = emptyList(),
        release: GitHubRelease? = null,
        repoChanged: Boolean = true,
        issuesChanged: Boolean = true,
        releaseChanged: Boolean = true,
        comments: List<GitHubComment> = emptyList(),
        commentsChanged: Boolean = false,
        commentsAnswered: Boolean = commentsChanged,
        commentsRefusedCode: Int = 0,
    ) = GitHubSnapshot(
        stars = stars,
        openIssues = openIssues,
        forks = 0,
        watchers = 0,
        pushedAt = "2026-08-26T19:15:34Z",
        repoChanged = repoChanged,
        issues = issues,
        issuesChanged = issuesChanged,
        release = release,
        releaseChanged = releaseChanged,
        comments = comments,
        commentsChanged = commentsChanged,
        commentsAnswered = commentsAnswered,
        commentsRefusedCode = commentsRefusedCode,
        etags = GitHubEtags(repo = "\"r1\"", issues = "\"i1\"", releases = "\"v1\""),
    )

    private fun comment(
        id: Long,
        issueNumber: Int = 47,
        author: String = "river",
        body: String = "Still happens on a fresh install of 3.7.0.",
        onPullRequest: Boolean = false,
        minimized: Boolean = false,
        isApp: Boolean = false,
    ) = GitHubComment(
        id = id,
        issueNumber = issueNumber,
        author = author,
        body = body,
        url = "https://github.com/riveerxd/nightbell/issues/$issueNumber#issuecomment-$id",
        onPullRequest = onPullRequest,
        minimized = minimized,
        isApp = isApp,
    )

    /** A watch with comments on, and a state that has already looked once. */
    private val commentWatch = watch.copy(notifyOnComments = true)

    private fun commentsSeen(
        issueId: Long = 100L,
        pullId: Long = 100L,
    ) = seeded().copy(
        commentsSeeded = true,
        lastIssueCommentId = issueId,
        lastPullCommentId = pullId,
    )

    private fun issue(
        id: Long,
        number: Int,
        title: String = "Something broke",
        body: String = "",
        author: String = "octocat",
        pr: Boolean = false,
    ) = GitHubItem(
        id = id,
        number = number,
        title = title,
        body = body,
        author = author,
        createdAt = "2026-08-26T18:22:30Z",
        url = "https://github.com/riveerxd/nightbell/issues/$number",
        isPullRequest = pr,
    )

    /** The state after one poll, which is what every "later" case starts from. */
    private fun seeded(
        watch: GitHubWatch = this.watch,
        stars: Int = 10,
        issues: List<GitHubItem> = emptyList(),
        release: GitHubRelease? = null,
    ): GitHubState = GitHubEvents.evaluate(
        watch = watch,
        previous = GitHubState(),
        snapshot = snapshot(stars = stars, issues = issues, release = release),
        nowMs = 1_000L,
    ).state

    // ---- stars ---------------------------------------------------------------

    @Test
    fun `the first check seeds the baseline and says nothing`() {
        val outcome = GitHubEvents.evaluate(watch, GitHubState(), snapshot(stars = 137), 1_000L)
        assertTrue(outcome.events.toString(), outcome.events.isEmpty())
        assertTrue(outcome.state.seeded)
        assertEquals(137, outcome.state.lastStarCount)
    }

    @Test
    fun `a single new star notifies by default`() {
        val before = seeded(stars = 12)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 13), 2_000L)
        val event = outcome.events.single() as GitHubEvent.Stars
        assertEquals(12, event.from)
        assertEquals(13, event.to)
        assertEquals(1, event.delta)
        assertEquals("New star on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        assertEquals(13, outcome.state.lastStarCount)
    }

    @Test
    fun `three new stars are one notification that says three`() {
        val before = seeded(stars = 12)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 15), 2_000L)
        val event = outcome.events.single() as GitHubEvent.Stars
        assertEquals(3, event.delta)
        assertTrue(event.body, event.body.contains("12 to 15 stars"))
        assertEquals("New stars on riveerxd/nightbell", event.title("riveerxd/nightbell"))
    }

    @Test
    fun `an unchanged star count says nothing`() {
        val before = seeded(stars = 12)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 12), 2_000L)
        assertTrue(outcome.events.isEmpty())
        assertEquals(12, outcome.state.lastStarCount)
    }

    @Test
    fun `a lower star count is recorded and never reported as growth`() {
        // Somebody un-starred. Real, not news, and above all not a "+1" produced
        // by comparing in the wrong direction.
        val before = seeded(stars = 42)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 39), 2_000L)
        assertTrue(outcome.events.toString(), outcome.events.isEmpty())
        assertEquals(39, outcome.state.lastStarCount)

        // And the next real increase is measured from the lower number rather
        // than from the high-water mark, so it reads +1 and not "back to 42".
        val next = GitHubEvents.evaluate(watch, outcome.state, snapshot(stars = 40), 3_000L)
        val event = next.events.single() as GitHubEvent.Stars
        assertEquals(39, event.from)
        assertEquals(1, event.delta)
    }

    @Test
    fun `stars can be switched off entirely`() {
        val quiet = watch.copy(notifyOnStars = false)
        val before = seeded(watch = quiet, stars = 12)
        val outcome = GitHubEvents.evaluate(quiet, before, snapshot(stars = 99), 2_000L)
        assertTrue(outcome.events.isEmpty())
        // Still recorded, so turning it back on does not announce the backlog.
        assertEquals(99, outcome.state.lastStarCount)
    }

    // ---- milestones ----------------------------------------------------------

    @Test
    fun `milestone mode only speaks when a threshold is crossed`() {
        val milestones = watch.copy(notifyOnEveryStar = false, notifyOnStarMilestones = true)
        var state = seeded(watch = milestones, stars = 96)

        // Four increases below the line: nothing at all.
        listOf(97, 98, 99).forEach { stars ->
            val outcome = GitHubEvents.evaluate(milestones, state, snapshot(stars = stars), 2_000L)
            assertTrue("$stars should be silent", outcome.events.isEmpty())
            state = outcome.state
        }

        val crossing = GitHubEvents.evaluate(milestones, state, snapshot(stars = 100), 3_000L)
        val event = crossing.events.single() as GitHubEvent.Milestone
        assertEquals(100, event.milestone)
        assertEquals(100, event.stars)

        // And nothing again on the far side of it.
        val after = GitHubEvents.evaluate(milestones, crossing.state, snapshot(stars = 101), 4_000L)
        assertTrue(after.events.isEmpty())
    }

    @Test
    fun `a jump past several milestones reports the highest`() {
        val milestones = watch.copy(notifyOnEveryStar = false)
        val before = seeded(watch = milestones, stars = 8)
        val outcome = GitHubEvents.evaluate(milestones, before, snapshot(stars = 260), 2_000L)
        assertEquals(250, (outcome.events.single() as GitHubEvent.Milestone).milestone)
    }

    @Test
    fun `a milestone replaces the plain notice rather than doubling it`() {
        // Both switches on, which is the shipped default. One event, because it
        // is one piece of news.
        val before = seeded(stars = 99)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 100), 2_000L)
        assertEquals(1, outcome.events.size)
        assertTrue(outcome.events.single() is GitHubEvent.Milestone)
    }

    @Test
    fun `milestones off leaves the plain notice in place`() {
        val plain = watch.copy(notifyOnStarMilestones = false)
        val before = seeded(watch = plain, stars = 99)
        val outcome = GitHubEvents.evaluate(plain, before, snapshot(stars = 100), 2_000L)
        assertTrue(outcome.events.single() is GitHubEvent.Stars)
    }

    // ---- digest --------------------------------------------------------------

    @Test
    fun `digest mode summarises instead of announcing every star`() {
        val digest = watch.copy(digestMode = DigestMode.DAILY)
        // Well clear of a milestone, which speaks for itself even in digest mode.
        var state = seeded(watch = digest, stars = 60)
        var now = 1_000L

        // A day's worth of drip-feed, none of it announced.
        listOf(61, 62, 65, 66).forEach { stars ->
            now += 60 * 60 * 1000L
            val outcome = GitHubEvents.evaluate(digest, state, snapshot(stars = stars), now)
            assertTrue("$stars should be held", outcome.events.isEmpty())
            state = outcome.state
        }
        assertEquals(60, state.digestStarsFrom)

        // Past the window, one line covering the lot.
        now += 21 * 60 * 60 * 1000L
        val flushed = GitHubEvents.evaluate(digest, state, snapshot(stars = 67), now)
        val event = flushed.events.single() as GitHubEvent.Digest
        assertEquals(60, event.from)
        assertEquals(67, event.to)
        assertEquals(7, event.delta)
        assertEquals("+7 stars on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        // Window closed, so the next star opens a fresh one.
        assertEquals(-1, flushed.state.digestStarsFrom)
    }

    @Test
    fun `a digest window that saw no growth produces nothing when it closes`() {
        val digest = watch.copy(digestMode = DigestMode.HOURLY)
        val state = seeded(watch = digest, stars = 20)
        val later = GitHubEvents.evaluate(
            digest,
            state,
            snapshot(stars = 20),
            1_000L + 2 * 60 * 60 * 1000L,
        )
        assertTrue(later.events.isEmpty())
    }

    @Test
    fun `a milestone still speaks immediately in digest mode`() {
        // The point of a digest is to stop plus-one spam. Passing a thousand
        // stars is not spam, and holding it back for a day would be silly.
        val digest = watch.copy(digestMode = DigestMode.DAILY)
        val before = seeded(watch = digest, stars = 998)
        val outcome = GitHubEvents.evaluate(digest, before, snapshot(stars = 1_001), 2_000L)
        assertEquals(1_000, (outcome.events.single() as GitHubEvent.Milestone).milestone)
    }

    @Test
    fun `switching the digest off drops the open window`() {
        val digest = watch.copy(digestMode = DigestMode.DAILY)
        val opened = GitHubEvents.evaluate(
            digest,
            seeded(watch = digest, stars = 20),
            snapshot(stars = 22),
            2_000L,
        ).state
        assertEquals(20, opened.digestStarsFrom)

        val off = GitHubEvents.evaluate(watch, opened, snapshot(stars = 22), 3_000L)
        assertEquals(-1, off.state.digestStarsFrom)
        assertEquals(0L, off.state.digestSince)
    }

    // ---- issues --------------------------------------------------------------

    @Test
    fun `the newest issue at setup is a baseline, not news`() {
        val outcome = GitHubEvents.evaluate(
            watch,
            GitHubState(),
            snapshot(issues = listOf(issue(500, 4))),
            1_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertTrue(outcome.state.issuesSeeded)
        assertEquals(500L, outcome.state.lastIssueId)
        assertEquals(4, outcome.state.lastIssueNumber)
    }

    @Test
    fun `a new issue notifies exactly once`() {
        val before = seeded(issues = listOf(issue(500, 4)))
        val fresh = snapshot(issues = listOf(issue(501, 5, title = "Crash on launch"), issue(500, 4)))

        val first = GitHubEvents.evaluate(watch, before, fresh, 2_000L)
        val event = first.events.single() as GitHubEvent.NewIssue
        assertEquals(5, event.item.number)
        assertEquals("New issue on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        assertEquals("#5 Crash on launch", event.body)

        // The same response again, which is what the next check will see.
        val second = GitHubEvents.evaluate(watch, first.state, fresh, 3_000L)
        assertTrue(second.events.isEmpty())
    }

    @Test
    fun `several new issues each get their own notice, oldest first`() {
        val before = seeded(issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(
            watch,
            before,
            snapshot(issues = listOf(issue(503, 7), issue(502, 6), issue(501, 5), issue(500, 4))),
            2_000L,
        )
        assertEquals(listOf(5, 6, 7), outcome.events.map { (it as GitHubEvent.NewIssue).item.number })
    }

    @Test
    fun `a flood is capped, and the cap does not make the flood repeat`() {
        val before = seeded(issues = listOf(issue(500, 4)))
        val many = (1..9).map { issue(500L + it, 4 + it) }.reversed()
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(issues = many), 2_000L)
        assertEquals(GitHubEvents.MAX_ITEMS_PER_CHECK, outcome.events.size)
        // State advanced past all nine, so the next poll is quiet.
        assertEquals(509L, outcome.state.lastIssueId)
        assertTrue(GitHubEvents.evaluate(watch, outcome.state, snapshot(issues = many), 3_000L).events.isEmpty())
    }

    @Test
    fun `pull requests are not issues`() {
        // GitHub serves pull requests through the issues endpoint. Letting them
        // through is the single most common way a repo monitor cries wolf.
        val before = seeded(issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(
            watch,
            before,
            snapshot(issues = listOf(issue(501, 5, title = "Bump okhttp", pr = true), issue(500, 4))),
            2_000L,
        )
        assertTrue(outcome.events.toString(), outcome.events.isEmpty())
        // The issue track's last-seen id is untouched by a PR.
        assertEquals(500L, outcome.state.lastIssueId)
        // The PR track recorded it, so switching PR watching on later is quiet.
        assertEquals(501L, outcome.state.lastPullId)
    }

    @Test
    fun `pull requests notify when their own watcher is on`() {
        val withPulls = watch.copy(watchPullRequests = true)
        val before = seeded(watch = withPulls, issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(
            withPulls,
            before,
            snapshot(issues = listOf(issue(501, 5, title = "Bump okhttp", pr = true), issue(500, 4))),
            2_000L,
        )
        val event = outcome.events.single() as GitHubEvent.NewPull
        assertEquals(5, event.item.number)
        assertEquals("New pull request on riveerxd/nightbell", event.title("riveerxd/nightbell"))
    }

    @Test
    fun `turning the issue watcher on later does not announce the backlog`() {
        // The track seeds on its first sighting rather than on the monitor's, so
        // a repository with old issues stays quiet the day it starts watching.
        val quiet = watch.copy(notifyOnIssues = false, watchPullRequests = false)
        val before = GitHubEvents.evaluate(
            quiet,
            GitHubState(),
            snapshot(issuesChanged = false),
            1_000L,
        ).state
        assertFalse(before.issuesSeeded)

        val on = watch.copy(notifyOnIssues = true)
        val first = GitHubEvents.evaluate(
            on,
            before,
            snapshot(issues = listOf(issue(500, 4), issue(499, 3))),
            2_000L,
        )
        assertTrue(first.events.toString(), first.events.isEmpty())
        assertEquals(500L, first.state.lastIssueId)
    }

    // ---- filters -------------------------------------------------------------

    @Test
    fun `the keyword filter passes a match and suppresses everything else`() {
        val filtered = watch.copy(issueKeywords = listOf("crash", "security"))
        val before = seeded(watch = filtered, issues = listOf(issue(500, 4)))

        val matching = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, title = "Crash on launch"), issue(500, 4))),
            2_000L,
        )
        assertEquals(5, (matching.events.single() as GitHubEvent.NewIssue).item.number)

        val notMatching = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, title = "Typo in the readme"), issue(500, 4))),
            2_000L,
        )
        assertTrue(notMatching.events.isEmpty())
        // Suppressed, not deferred: the id advanced, so it is never reconsidered.
        assertEquals(501L, notMatching.state.lastIssueId)
    }

    @Test
    fun `the keyword filter reads the body as well as the title`() {
        val filtered = watch.copy(issueKeywords = listOf("security"))
        val before = seeded(watch = filtered, issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(
                issues = listOf(
                    issue(501, 5, title = "Question", body = "Is this a SECURITY problem?"),
                    issue(500, 4),
                ),
            ),
            2_000L,
        )
        assertEquals(1, outcome.events.size)
    }

    @Test
    fun `the author filter passes a match and suppresses everything else`() {
        val filtered = watch.copy(issueAuthors = listOf("shortwavesurfer2009"))
        val before = seeded(watch = filtered, issues = listOf(issue(500, 4)))

        val matching = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, author = "ShortwaveSurfer2009"), issue(500, 4))),
            2_000L,
        )
        assertEquals(1, matching.events.size)

        val notMatching = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, author = "someone-else"), issue(500, 4))),
            2_000L,
        )
        assertTrue(notMatching.events.isEmpty())
    }

    @Test
    fun `both filters have to pass`() {
        val filtered = watch.copy(issueKeywords = listOf("crash"), issueAuthors = listOf("octocat"))
        val before = seeded(watch = filtered, issues = listOf(issue(500, 4)))
        val wrongAuthor = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, title = "Crash", author = "nobody"), issue(500, 4))),
            2_000L,
        )
        assertTrue(wrongAuthor.events.isEmpty())
    }

    @Test
    fun `filters apply to pull requests too`() {
        val filtered = watch.copy(watchPullRequests = true, issueAuthors = listOf("octocat"))
        val before = seeded(watch = filtered, issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(
            filtered,
            before,
            snapshot(issues = listOf(issue(501, 5, author = "dependabot", pr = true), issue(500, 4))),
            2_000L,
        )
        assertTrue(outcome.events.isEmpty())
    }

    @Test
    fun `comma separated filter text round-trips`() {
        val edited = watch.withKeywordsText(" crash , security ,, android ")
        assertEquals(listOf("crash", "security", "android"), edited.issueKeywords)
        assertEquals("crash, security, android", edited.keywordsText)
        assertTrue(watch.withKeywordsText("").issueKeywords.isEmpty())
    }

    // ---- releases ------------------------------------------------------------

    private fun release(id: Long, tag: String, prerelease: Boolean = false, draft: Boolean = false) =
        GitHubRelease(
            id = id,
            tag = tag,
            name = "Nightbell $tag",
            url = "https://github.com/riveerxd/nightbell/releases/tag/$tag",
            prerelease = prerelease,
            draft = draft,
        )

    @Test
    fun `the release that already existed is a baseline, not news`() {
        val outcome = GitHubEvents.evaluate(
            watch,
            GitHubState(),
            snapshot(release = release(377361469, "v3.1.1")),
            1_000L,
        )
        assertTrue(outcome.events.isEmpty())
        assertEquals("v3.1.1", outcome.state.lastReleaseTag)
        assertTrue(outcome.state.releasesSeeded)
    }

    @Test
    fun `a new release notifies exactly once`() {
        val before = seeded(release = release(1, "v3.1.1"))
        val next = snapshot(release = release(2, "v3.2.0"))

        val first = GitHubEvents.evaluate(watch, before, next, 2_000L)
        val event = first.events.single() as GitHubEvent.NewRelease
        assertEquals("v3.2.0", event.release.tag)
        assertEquals("New release on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        assertTrue(event.body, event.body.startsWith("v3.2.0"))

        assertTrue(GitHubEvents.evaluate(watch, first.state, next, 3_000L).events.isEmpty())
    }

    @Test
    fun `a deleted release making an older one latest is not announced`() {
        val before = seeded(release = release(9, "v3.2.0"))
        val rolledBack = GitHubEvents.evaluate(watch, before, snapshot(release = release(8, "v3.1.1")), 2_000L)
        assertTrue(rolledBack.events.isEmpty())
        // And the recorded release is untouched, so the real next one is news.
        assertEquals("v3.2.0", rolledBack.state.lastReleaseTag)
    }

    @Test
    fun `prereleases are ignored unless asked for`() {
        val before = seeded(release = release(1, "v3.1.1"))
        val beta = snapshot(release = release(2, "v3.2.0-rc1", prerelease = true))
        assertTrue(GitHubEvents.evaluate(watch, before, beta, 2_000L).events.isEmpty())

        val opted = watch.copy(includePrereleases = true)
        val outcome = GitHubEvents.evaluate(opted, seeded(watch = opted, release = release(1, "v3.1.1")), beta, 2_000L)
        val event = outcome.events.single() as GitHubEvent.NewRelease
        assertTrue(event.body, event.body.contains("prerelease"))
    }

    @Test
    fun `a draft release is never announced`() {
        val before = seeded(release = release(1, "v3.1.1"))
        val draft = snapshot(release = release(2, "v3.2.0", draft = true))
        assertTrue(GitHubEvents.evaluate(watch, before, draft, 2_000L).events.isEmpty())
    }

    @Test
    fun `a repository with no releases still seeds the track`() {
        // A 404 from releases/latest is "there aren't any", which counts as
        // having looked, so the first real release is news.
        val empty = GitHubEvents.evaluate(watch, GitHubState(), snapshot(release = null), 1_000L)
        assertTrue(empty.state.releasesSeeded)
        val first = GitHubEvents.evaluate(watch, empty.state, snapshot(release = release(1, "v1.0.0")), 2_000L)
        assertEquals(1, first.events.size)
    }

    // ---- 304 -----------------------------------------------------------------

    @Test
    fun `a not-modified response preserves every track's state`() {
        val before = seeded(
            stars = 42,
            issues = listOf(issue(500, 4)),
            release = release(1, "v3.1.1"),
        )
        // What the checker hands over when all three endpoints answered 304: the
        // previous values carried forward, and nothing flagged as changed.
        val unchanged = GitHubSnapshot(
            stars = before.lastStarCount,
            openIssues = before.openIssues,
            forks = before.forks,
            watchers = before.watchers,
            pushedAt = before.pushedAt,
            repoChanged = false,
            issues = emptyList(),
            issuesChanged = false,
            release = null,
            releaseChanged = false,
            etags = GitHubEtags(before.repoEtag, before.issuesEtag, before.releasesEtag),
        )
        val outcome = GitHubEvents.evaluate(watch, before, unchanged, 5_000L)

        assertTrue(outcome.events.toString(), outcome.events.isEmpty())
        assertEquals(42, outcome.state.lastStarCount)
        assertEquals(500L, outcome.state.lastIssueId)
        assertEquals(4, outcome.state.lastIssueNumber)
        assertEquals(1L, outcome.state.lastReleaseId)
        assertEquals("v3.1.1", outcome.state.lastReleaseTag)
        assertEquals(before.repoEtag, outcome.state.repoEtag)
        assertEquals(before.issuesEtag, outcome.state.issuesEtag)
        assertEquals(before.releasesEtag, outcome.state.releasesEtag)
        // Everything the previous poll knew survives, apart from the poll clock.
        assertEquals(before, outcome.state.copy(lastPolledAt = before.lastPolledAt))
    }

    @Test
    fun `an empty issues list after a real fetch is not a reason to forget`() {
        // Every open issue was closed. Nothing new happened, and the last-seen id
        // must not fall back to zero or the next issue opened would be the fifth
        // announcement of things already read.
        val before = seeded(issues = listOf(issue(500, 4)))
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(issues = emptyList()), 2_000L)
        assertTrue(outcome.events.isEmpty())
        assertEquals(500L, outcome.state.lastIssueId)
    }

    @Test
    fun `the rate-limited flag is cleared by a poll that got answers`() {
        val before = seeded().copy(rateLimited = true, rateRemaining = 0)
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(), 2_000L)
        assertFalse(outcome.state.rateLimited)
    }

    @Test
    fun `the summary line names what is watched`() {
        assertEquals("every star · issues · releases", watch.summary)
        assertEquals(
            "star milestones · issues · releases",
            watch.copy(notifyOnEveryStar = false).summary,
        )
        assertEquals(
            "stars daily · issues · pull requests · releases",
            watch.copy(digestMode = DigestMode.DAILY, watchPullRequests = true).summary,
        )
        assertEquals(
            "Nothing selected",
            watch.copy(
                notifyOnStars = false,
                notifyOnIssues = false,
                watchReleases = false,
            ).summary,
        )
    }

    @Test
    fun `an unchanged evaluation does not churn the object graph`() {
        // Cheap guard on a hot path: the sweep evaluates every repo monitor on
        // every wake, and most of those are a 304 with nothing to say.
        val before = seeded()
        val outcome = GitHubEvents.evaluate(watch, before, snapshot(stars = 10), before.lastPolledAt)
        assertSame(GitHubState::class.java, outcome.state::class.java)
        assertNull(outcome.events.firstOrNull())
    }

    // ---- comments ------------------------------------------------------------

    @Test
    fun `the first page of comments is never announced, however old it is`() {
        val page = (1L..100L).map { comment(id = it, issueNumber = it.toInt()) }
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = seeded(watch = commentWatch),
            snapshot = snapshot(comments = page, commentsChanged = true),
            nowMs = 2_000L,
        )
        assertTrue(
            "a decade of conversation must not arrive as news",
            outcome.events.none { it is GitHubEvent.NewComments },
        )
        assertTrue("the track has now looked", outcome.state.commentsSeeded)
        assertEquals(100L, outcome.state.lastIssueCommentId)
    }

    @Test
    fun `turning comments on later does not announce the backlog`() {
        // Seeded long ago with comments off, so the star track knows the repo and
        // the comment track has never seen a response.
        val before = seeded()
        assertFalse("precondition: the comment track is unseeded", before.commentsSeeded)
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = before,
            snapshot = snapshot(comments = listOf(comment(id = 900L)), commentsChanged = true),
            nowMs = 3_000L,
        )
        assertTrue(outcome.events.none { it is GitHubEvent.NewComments })
        assertEquals(900L, outcome.state.lastIssueCommentId)
    }

    @Test
    fun `a comment on a closed issue is announced like any other`() {
        // Nothing in the decider knows or cares about the parent's state: the
        // endpoint has no state filter, which is the whole reason this works.
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(comment(id = 101L, issueNumber = 12)),
                commentsChanged = true,
            ),
            nowMs = 4_000L,
        )
        val event = outcome.events.filterIsInstance<GitHubEvent.NewComments>().single()
        assertEquals("New comment on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        assertEquals("#12 by river: Still happens on a fresh install of 3.7.0.", event.body)
        assertEquals("comment-issue-12", event.key)
    }

    @Test
    fun `many replies to one issue are one row carrying the count`() {
        val flood = (101L..140L).map { comment(id = it, issueNumber = 47, author = "bob") }
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(comments = flood, commentsChanged = true),
            nowMs = 5_000L,
        )
        val event = outcome.events.filterIsInstance<GitHubEvent.NewComments>().single()
        assertEquals(40, event.count)
        assertEquals("40 new comments on riveerxd/nightbell", event.title("riveerxd/nightbell"))
        assertTrue("the latest author leads the line", event.body.startsWith("#47, latest by bob"))
        assertEquals(140L, outcome.state.lastIssueCommentId)
    }

    @Test
    fun `comments on many threads are capped, and the cap does not make them repeat`() {
        val spread = (101L..111L).map { comment(id = it, issueNumber = it.toInt()) }
        val first = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(comments = spread, commentsChanged = true),
            nowMs = 6_000L,
        )
        assertEquals(
            GitHubEvents.MAX_ITEMS_PER_CHECK,
            first.events.filterIsInstance<GitHubEvent.NewComments>().size,
        )
        // The state moved past all eleven, so the same page is quiet next time.
        val again = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = first.state,
            snapshot = snapshot(comments = spread, commentsChanged = true),
            nowMs = 7_000L,
        )
        assertTrue(again.events.none { it is GitHubEvent.NewComments })
    }

    @Test
    fun `an edited comment is not a new one`() {
        val before = commentsSeen()
        val original = comment(id = 101L, body = "first")
        val first = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = before,
            snapshot = snapshot(comments = listOf(original), commentsChanged = true),
            nowMs = 8_000L,
        )
        assertEquals(1, first.events.filterIsInstance<GitHubEvent.NewComments>().size)
        // Same id, rewritten body: this is what a bot editing its progress
        // comment looks like, and it must not page a second time.
        val edited = original.copy(body = "first, now with a build log")
        val second = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = first.state,
            snapshot = snapshot(comments = listOf(edited), commentsChanged = true),
            nowMs = 9_000L,
        )
        assertTrue(second.events.none { it is GitHubEvent.NewComments })
    }

    @Test
    fun `deleting the newest comment does not walk the watermark backwards`() {
        val before = commentsSeen(issueId = 500L)
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = before,
            snapshot = snapshot(comments = listOf(comment(id = 300L)), commentsChanged = true),
            nowMs = 10_000L,
        )
        assertEquals(500L, outcome.state.lastIssueCommentId)
        assertTrue(outcome.events.none { it is GitHubEvent.NewComments })
    }

    @Test
    fun `pull request comments stay out until pull requests are watched`() {
        val reply = comment(id = 101L, issueNumber = 8, onPullRequest = true)
        val quiet = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(comments = listOf(reply), commentsChanged = true),
            nowMs = 11_000L,
        )
        assertTrue(quiet.events.none { it is GitHubEvent.NewComments })
        // The watermark still advanced, which is what stops the day the user
        // switches pull requests on from being the day old threads arrive.
        assertEquals(101L, quiet.state.lastPullCommentId)

        val later = GitHubEvents.evaluate(
            watch = commentWatch.copy(watchPullRequests = true),
            previous = quiet.state,
            snapshot = snapshot(comments = listOf(reply), commentsChanged = true),
            nowMs = 12_000L,
        )
        assertTrue(
            "switching pull requests on must not replay them",
            later.events.none { it is GitHubEvent.NewComments },
        )
    }

    @Test
    fun `an app comment is skipped by default and let through when asked`() {
        val bot = comment(id = 101L, author = "rust-bors[bot]", isApp = true)
        val skipped = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(comments = listOf(bot), commentsChanged = true),
            nowMs = 13_000L,
        )
        assertTrue(skipped.events.none { it is GitHubEvent.NewComments })
        // Filtered out is not unseen: the watermark moved anyway, so turning the
        // switch on is not a request for everything the bot has ever said.
        assertEquals(101L, skipped.state.lastIssueCommentId)

        val allowed = GitHubEvents.evaluate(
            watch = commentWatch.copy(notifyOnBotComments = true),
            previous = commentsSeen(),
            snapshot = snapshot(comments = listOf(bot), commentsChanged = true),
            nowMs = 14_000L,
        )
        assertEquals(1, allowed.events.filterIsInstance<GitHubEvent.NewComments>().size)
    }

    @Test
    fun `a hidden comment is skipped even when bots are allowed`() {
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch.copy(notifyOnBotComments = true),
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(comment(id = 101L, minimized = true)),
                commentsChanged = true,
            ),
            nowMs = 15_000L,
        )
        assertTrue(outcome.events.none { it is GitHubEvent.NewComments })
    }

    @Test
    fun `a muted login never pages, whatever case it was typed in`() {
        val muted = commentWatch.copy(commentMutedText = "RustBot, rust-timer")
        val outcome = GitHubEvents.evaluate(
            watch = muted,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(
                    comment(id = 101L, author = "rustbot", issueNumber = 1),
                    comment(id = 102L, author = "river", issueNumber = 2),
                ),
                commentsChanged = true,
            ),
            nowMs = 16_000L,
        )
        val events = outcome.events.filterIsInstance<GitHubEvent.NewComments>()
        assertEquals(1, events.size)
        assertEquals(2, events.single().issueNumber)
    }

    @Test
    fun `a login that merely contains a muted name still gets through`() {
        val muted = commentWatch.copy(commentMutedText = "bot")
        val outcome = GitHubEvents.evaluate(
            watch = muted,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(comment(id = 101L, author = "talbot")),
                commentsChanged = true,
            ),
            nowMs = 17_000L,
        )
        assertEquals(1, outcome.events.filterIsInstance<GitHubEvent.NewComments>().size)
    }

    @Test
    fun `the keyword filter reads a comment's body, and the author allowlist does not apply`() {
        val filtered = commentWatch.copy(
            issueKeywords = listOf("crash"),
            // An allowlist for issues must not silence everyone else's comments.
            issueAuthors = listOf("octocat"),
        )
        val outcome = GitHubEvents.evaluate(
            watch = filtered,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(
                    comment(id = 101L, issueNumber = 1, body = "it crashes on rotate"),
                    comment(id = 102L, issueNumber = 2, body = "thanks for the fix"),
                ),
                commentsChanged = true,
            ),
            nowMs = 18_000L,
        )
        val events = outcome.events.filterIsInstance<GitHubEvent.NewComments>()
        assertEquals(1, events.size)
        assertEquals(1, events.single().issueNumber)
    }

    @Test
    fun `a quoted reply shows its own words rather than the ones it quotes`() {
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(
                    comment(id = 101L, body = "> does it still happen\n\nYes, on 3.7.0."),
                ),
                commentsChanged = true,
            ),
            nowMs = 19_000L,
        )
        val event = outcome.events.filterIsInstance<GitHubEvent.NewComments>().single()
        assertEquals("#47 by river: Yes, on 3.7.0.", event.body)
    }

    @Test
    fun `an empty comment body leaves no trailing colon`() {
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(comment(id = 101L, body = "   ")),
                commentsChanged = true,
            ),
            nowMs = 20_000L,
        )
        assertEquals("#47 by river", outcome.events.filterIsInstance<GitHubEvent.NewComments>().single().body)
    }

    @Test
    fun `a long comment is cut short in the row and runs longer when expanded`() {
        val long = "x".repeat(600)
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = commentsSeen(),
            snapshot = snapshot(
                comments = listOf(comment(id = 101L, body = long)),
                commentsChanged = true,
            ),
            nowMs = 21_000L,
        )
        val event = outcome.events.filterIsInstance<GitHubEvent.NewComments>().single()
        assertTrue("the collapsed row stays short", event.body.length < 160)
        assertTrue("the expanded card says more", event.expanded.length > event.body.length)
        assertTrue(event.body.endsWith("…"))
    }

    @Test
    fun `a not-modified comment response announces nothing and moves nothing`() {
        val before = commentsSeen(issueId = 400L)
        val outcome = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = before,
            snapshot = snapshot(commentsChanged = false, commentsAnswered = true),
            nowMs = 22_000L,
        )
        assertTrue(outcome.events.none { it is GitHubEvent.NewComments })
        assertEquals(400L, outcome.state.lastIssueCommentId)
    }

    @Test
    fun `a refusal backs the track off, doubling to a ceiling, and never seeds it`() {
        var state = seeded(watch = commentWatch)
        val waits = mutableListOf<Long>()
        var now = 1_000L
        repeat(8) {
            val outcome = GitHubEvents.evaluate(
                watch = commentWatch,
                previous = state,
                snapshot = snapshot(commentsChanged = false, commentsRefusedCode = 404),
                nowMs = now,
            )
            state = outcome.state
            waits += state.commentsRetryAt - now
            now += 1_000L
        }
        val base = GitHubEvents.COMMENTS_RETRY_BASE_MS
        assertEquals(
            listOf(base, base * 2, base * 4, base * 8, base * 16, base * 32, base * 32, base * 32),
            waits,
        )
        assertEquals(404, state.commentsFailedCode)
        assertFalse(
            "a refusal is not a look, so the next real page is not news",
            state.commentsSeeded,
        )
    }

    @Test
    fun `an answer clears the back-off`() {
        val refused = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = seeded(watch = commentWatch),
            snapshot = snapshot(commentsChanged = false, commentsRefusedCode = 403),
            nowMs = 1_000L,
        ).state
        assertTrue(refused.commentsRetryAt > 0L)

        val answered = GitHubEvents.evaluate(
            watch = commentWatch,
            previous = refused,
            snapshot = snapshot(commentsChanged = false, commentsAnswered = true),
            nowMs = 2_000L,
        ).state
        assertEquals(0, answered.commentsFailures)
        assertEquals(0L, answered.commentsRetryAt)
        assertEquals(0, answered.commentsFailedCode)
    }

    @Test
    fun `a comments-only watch says so rather than reading as nothing selected`() {
        val only = GitHubWatch(
            owner = "riveerxd",
            repo = "nightbell",
            notifyOnStars = false,
            notifyOnIssues = false,
            watchReleases = false,
            notifyOnComments = true,
        )
        assertEquals("comments", only.summary)
    }
}
