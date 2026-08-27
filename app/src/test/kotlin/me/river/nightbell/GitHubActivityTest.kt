package me.river.nightbell

import me.river.nightbell.domain.GitHubActivity
import me.river.nightbell.domain.RepoFacts
import me.river.nightbell.domain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository history, which is a difference of samples rather than a log.
 *
 * Everything interesting here is about what does *not* get a row: a poll that
 * changed nothing, a count that moved because a new issue arrived, a push that
 * came in with the release that tagged it.
 */
class GitHubActivityTest {

    private val start = 1_700_000_000_000L
    private val step = 15 * 60_000L

    private fun facts(
        stars: Int = 11,
        openIssues: Int = 0,
        forks: Int = 0,
        releaseTag: String = "v3.2.0",
        issueNumber: Int = 0,
        issueTitle: String = "",
        pushedAt: Long = 0L,
    ) = RepoFacts(stars, openIssues, forks, releaseTag, issueNumber, issueTitle, pushedAt)

    private fun ok(index: Int, facts: RepoFacts?) = Sample(
        at = start + index * step,
        ok = true,
        latencyMs = 480,
        code = if (index % 2 == 0) 200 else 304,
        repo = facts,
    )

    private fun failed(index: Int, note: String, code: Int = 0) = Sample(
        at = start + index * step,
        ok = false,
        latencyMs = 30_000,
        code = code,
        note = note,
    )

    @Test
    fun theFirstMeasuredCheckIsABaselineRatherThanNews() {
        val rows = GitHubActivity.of(listOf(ok(0, facts())))
        assertEquals(1, rows.size)
        assertTrue(rows.single() is GitHubActivity.Baseline)
        assertTrue("a baseline is not news", !GitHubActivity.hasNews(rows))
    }

    @Test
    fun aStarChangeCarriesBothReadings() {
        val rows = GitHubActivity.of(
            listOf(ok(0, facts(stars = 11)), ok(1, facts(stars = 13))),
        )
        val stars = rows.first() as GitHubActivity.Stars
        assertEquals(11, stars.from)
        assertEquals(13, stars.to)
        assertEquals(2, stars.delta)
        assertTrue(GitHubActivity.hasNews(rows))
    }

    @Test
    fun rowsComeBackNewestFirst() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(stars = 11)),
                ok(1, facts(stars = 12)),
                ok(2, facts(stars = 13)),
            ),
        )
        val ats = rows.map { it.at }
        assertEquals(ats.sortedDescending(), ats)
    }

    @Test
    fun unchangedChecksCollapseIntoOneRow() {
        val samples = (0..5).map { ok(it, facts(stars = 11)) }
        val rows = GitHubActivity.of(samples)
        // The baseline, then five polls that found nothing.
        assertEquals(2, rows.size)
        val quiet = rows.first() as GitHubActivity.Quiet
        assertEquals(5, quiet.checks)
        assertEquals(start + step, quiet.since)
        assertEquals(start + 5 * step, quiet.at)
    }

    @Test
    fun quietRunsAreBrokenByTheChangeThatEndsThem() {
        val samples = listOf(
            ok(0, facts(stars = 11)),
            ok(1, facts(stars = 11)),
            ok(2, facts(stars = 11)),
            ok(3, facts(stars = 12)),
            ok(4, facts(stars = 12)),
            ok(5, facts(stars = 12)),
        )
        val rows = GitHubActivity.of(samples).reversed()
        assertTrue(rows[0] is GitHubActivity.Baseline)
        assertEquals(2, (rows[1] as GitHubActivity.Quiet).checks)
        assertEquals(12, (rows[2] as GitHubActivity.Stars).to)
        assertEquals(2, (rows[3] as GitHubActivity.Quiet).checks)
    }

    @Test
    fun aSingleQuietCheckBetweenTwoEventsIsNotWorthALine() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(stars = 11)),
                ok(1, facts(stars = 12)),
                ok(2, facts(stars = 12)),
                ok(3, facts(stars = 13)),
            ),
        )
        assertTrue(rows.none { it is GitHubActivity.Quiet })
        assertEquals(2, rows.count { it is GitHubActivity.Stars })
    }

    @Test
    fun samplesWithNoRepoFactsAreQuietRatherThanChanges() {
        // What history written before this existed looks like.
        val rows = GitHubActivity.of(listOf(ok(0, null), ok(1, null), ok(2, facts())))
        assertEquals(2, rows.size)
        assertTrue(rows.any { it is GitHubActivity.Quiet && it.checks == 2 })
        assertTrue(rows.any { it is GitHubActivity.Baseline })
    }

    @Test
    fun aNewIssueDoesNotAlsoReportTheCountItMoved() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(openIssues = 0, issueNumber = 5)),
                ok(1, facts(openIssues = 1, issueNumber = 6, issueTitle = "[Bug] certs")),
            ),
        )
        // The baseline, and the issue. Not the open-issue count as well.
        assertEquals(2, rows.size)
        val issue = rows.first() as GitHubActivity.Issue
        assertEquals(6, issue.number)
        assertEquals("[Bug] certs", issue.title)
        assertTrue(rows.none { it is GitHubActivity.IssueCount })
    }

    @Test
    fun anIssueClosingGetsItsOwnRow() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(openIssues = 2, issueNumber = 6)),
                ok(1, facts(openIssues = 1, issueNumber = 6)),
            ),
        )
        val closed = rows.first() as GitHubActivity.IssueCount
        assertEquals(2, closed.from)
        assertEquals(1, closed.to)
        assertEquals(1, closed.closed)
    }

    @Test
    fun aReleaseSwallowsThePushThatTaggedIt() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(releaseTag = "v3.2.0", pushedAt = start)),
                ok(1, facts(releaseTag = "v3.3.0", pushedAt = start + step)),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("v3.3.0", (rows.first() as GitHubActivity.Release).tag)
        assertTrue(rows.none { it is GitHubActivity.Push })
    }

    @Test
    fun aPlainPushIsItsOwnRow() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(pushedAt = start)),
                ok(1, facts(pushedAt = start + step)),
            ),
        )
        assertTrue(rows.first() is GitHubActivity.Push)
    }

    @Test
    fun oneCheckCanReportSeveralThings() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts(stars = 11, forks = 0, openIssues = 0, issueNumber = 5)),
                ok(1, facts(stars = 13, forks = 1, openIssues = 1, issueNumber = 6)),
            ),
        )
        val changes = rows.filter { it !is GitHubActivity.Baseline }
        assertEquals(3, changes.size)
        assertTrue(changes.any { it is GitHubActivity.Stars })
        assertTrue(changes.any { it is GitHubActivity.Issue })
        assertTrue(changes.any { it is GitHubActivity.Forks })
        // All three came out of the same poll.
        assertEquals(1, changes.map { it.at }.distinct().size)
    }

    @Test
    fun failuresAreNeverCollapsed() {
        val rows = GitHubActivity.of(
            listOf(
                ok(0, facts()),
                ok(1, facts()),
                failed(2, "Token rejected", code = 401),
                failed(3, "Token rejected", code = 401),
                ok(4, facts()),
            ),
        )
        val failures = rows.filterIsInstance<GitHubActivity.Failed>()
        assertEquals(2, failures.size)
        assertEquals(401, failures.first().code)
        assertEquals("Token rejected", failures.first().note)
    }

    @Test
    fun anEmptyHistoryHasNoRows() {
        assertTrue(GitHubActivity.of(emptyList()).isEmpty())
    }
}
