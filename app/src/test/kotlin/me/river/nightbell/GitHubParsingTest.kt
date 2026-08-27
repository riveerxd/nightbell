package me.river.nightbell

import me.river.nightbell.domain.GitHubRepo
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as naming a repository.
 *
 * The field takes whatever the user has in front of them, so the interesting
 * cases are all the shapes that mean the same thing, and the ones that look
 * close enough to be dangerous: another host, a path with an extra segment, a
 * login that GitHub would never issue.
 */
class GitHubParsingTest {

    @Test
    fun `plain owner slash repo parses`() {
        val repo = GitHubRepo.parse("riveerxd/nightbell")
        assertEquals(GitHubRepo("riveerxd", "nightbell"), repo)
        assertEquals("riveerxd/nightbell", repo?.slug)
        assertEquals("https://github.com/riveerxd/nightbell", repo?.url)
    }

    @Test
    fun `surrounding whitespace is not the user's problem`() {
        assertEquals(GitHubRepo("a", "b"), GitHubRepo.parse("  a/b  "))
    }

    @Test
    fun `a repository URL parses`() {
        assertEquals(
            GitHubRepo("riveerxd", "nightbell"),
            GitHubRepo.parse("https://github.com/riveerxd/nightbell"),
        )
    }

    @Test
    fun `every shape of github link resolves to the same repository`() {
        val expected = GitHubRepo("riveerxd", "nightbell")
        val forms = listOf(
            "http://github.com/riveerxd/nightbell",
            "https://www.github.com/riveerxd/nightbell/",
            "github.com/riveerxd/nightbell",
            // The page someone is most likely to be looking at when they decide
            // to watch a repository.
            "https://github.com/riveerxd/nightbell/issues/4",
            "https://github.com/riveerxd/nightbell/tree/master/app",
            "https://github.com/riveerxd/nightbell?tab=readme-ov-file",
            "https://github.com/riveerxd/nightbell#install",
            "https://github.com/riveerxd/nightbell.git",
            "git@github.com:riveerxd/nightbell.git",
            "https://api.github.com/repos/riveerxd/nightbell",
        )
        forms.forEach { form ->
            assertEquals("failed on $form", expected, GitHubRepo.parse(form))
        }
    }

    @Test
    fun `another host is not a github repository`() {
        // The dangerous near-miss: right shape, wrong service. Accepting it would
        // produce a monitor that polls api.github.com for a repository that does
        // not exist, forever.
        assertNull(GitHubRepo.parse("https://gitlab.com/owner/repo"))
        assertNull(GitHubRepo.parse("https://codeberg.org/owner/repo"))
        assertNull(GitHubRepo.parse("https://github.example.com/owner/repo"))
    }

    @Test
    fun `invalid repository input is rejected`() {
        val bad = listOf(
            "",
            "   ",
            "nightbell",
            "/",
            "riveerxd/",
            "/nightbell",
            "riveerxd/night bell",
            // A hyphen may not start or end a GitHub login.
            "-riveerxd/nightbell",
            "riveerxd-/nightbell",
            // Legal characters, illegal name: both resolve to a directory.
            "riveerxd/.",
            "riveerxd/..",
            // Three bare segments is a path, not a slug.
            "riveerxd/nightbell/app",
            "https://github.com",
            "https://github.com/riveerxd",
        )
        bad.forEach { input ->
            assertNull("should have been rejected: \"$input\"", GitHubRepo.parse(input))
        }
    }

    @Test
    fun `a login longer than github allows is rejected`() {
        assertNull(GitHubRepo.parse("${"a".repeat(40)}/repo"))
        assertEquals(GitHubRepo("a".repeat(39), "repo"), GitHubRepo.parse("${"a".repeat(39)}/repo"))
    }

    @Test
    fun `validation reports a missing repository as an error`() {
        val note = Validation.repoNote("")
        assertEquals(Validation.Field.REPO, note?.field)
        assertEquals(Validation.Severity.ERROR, note?.severity)
    }

    @Test
    fun `validation reports nonsense as an error and a real repo as clean`() {
        assertEquals(Validation.Severity.ERROR, Validation.repoNote("not a repo")?.severity)
        assertNull(Validation.repoNote("riveerxd/nightbell"))
    }

    @Test
    fun `a github monitor with no repository does not validate`() {
        val monitor = Monitor(id = "m", kind = MonitorKind.GITHUB_REPO)
        val report = Validation.report(monitor)
        assertTrue(report.errors.isNotEmpty())
        assertEquals(Validation.Field.REPO, report.errors.first().field)
    }

    @Test
    fun `a github monitor with a repository validates`() {
        val monitor = Monitor(
            id = "m",
            kind = MonitorKind.GITHUB_REPO,
            url = "https://github.com/riveerxd/nightbell",
            intervalMinutes = 15,
            github = GitHubWatch(owner = "riveerxd", repo = "nightbell"),
        )
        val report = Validation.report(monitor)
        assertTrue(report.errors.map { it.message }.toString(), report.isValid)
    }

    @Test
    fun `a tight interval warns about the anonymous rate limit`() {
        val monitor = Monitor(
            id = "m",
            kind = MonitorKind.GITHUB_REPO,
            url = "https://github.com/riveerxd/nightbell",
            intervalMinutes = 5,
            github = GitHubWatch(owner = "riveerxd", repo = "nightbell"),
        )
        val note = Validation.report(monitor).of(Validation.Field.INTERVAL)
        assertEquals(Validation.Severity.WARNING, note?.severity)
        assertTrue(note!!.message.contains("60 requests"))
        // A warning, not an error: the user may well have a token.
        assertTrue(Validation.report(monitor).isValid)
    }

    @Test
    fun `a monitor with nothing switched on says so`() {
        val monitor = Monitor(
            id = "m",
            kind = MonitorKind.GITHUB_REPO,
            url = "https://github.com/riveerxd/nightbell",
            intervalMinutes = 15,
            github = GitHubWatch(
                owner = "riveerxd",
                repo = "nightbell",
                notifyOnStars = false,
                notifyOnIssues = false,
                watchReleases = false,
                watchPullRequests = false,
            ),
        )
        val note = Validation.report(monitor).of(Validation.Field.GITHUB)
        assertEquals(Validation.Severity.WARNING, note?.severity)
    }

    @Test
    fun `a github monitor is named after its repository`() {
        val monitor = Monitor(
            id = "m",
            kind = MonitorKind.GITHUB_REPO,
            url = "https://github.com/riveerxd/nightbell",
            github = GitHubWatch(owner = "riveerxd", repo = "nightbell"),
        )
        assertEquals("riveerxd/nightbell", monitor.displayName)
        assertEquals("Watchtower", monitor.copy(name = "Watchtower").displayName)
    }

    @Test
    fun `the token link is github's own fine-grained page`() {
        // Named in the issue and in the plan. Worth pinning: a typo here sends
        // people to a page that cannot make the token the app asks for.
        assertEquals(
            "https://github.com/settings/personal-access-tokens/new",
            GitHubWatch.TOKEN_PAGE_URL,
        )
    }
}

/**
 * "Repos first", the sort that admits the dashboard answers two questions.
 *
 * Worst-first is the right default and stays it. This mode exists because a
 * healthy repository sorted below eleven healthy websites is not an answer to
 * "what happened on my repos", which is a real reason to open the app.
 */
class ReposFirstSortTest {

    private fun card(
        id: String,
        name: String,
        kind: MonitorKind,
        health: me.river.nightbell.domain.Health = me.river.nightbell.domain.Health.UP,
    ) = me.river.nightbell.domain.MonitorCard(
        monitor = Monitor(id = id, name = name, kind = kind, url = "https://example.com/$id"),
        runtime = me.river.nightbell.domain.MonitorRuntime(health = health),
    )

    private val website = card("w1", "Alpha site", MonitorKind.HTTP_STATUS)
    private val brokenSite = card(
        "w2", "Broken site", MonitorKind.HTTP_STATUS, me.river.nightbell.domain.Health.DOWN,
    )
    private val repoA = card("g1", "riveerxd/nightbell", MonitorKind.GITHUB_REPO)
    private val repoB = card("g2", "octocat/hello", MonitorKind.GITHUB_REPO)
    private val brokenRepo = card(
        "g3", "zzz/broken", MonitorKind.GITHUB_REPO, me.river.nightbell.domain.Health.DOWN,
    )

    private fun order(cards: List<me.river.nightbell.domain.MonitorCard>) =
        MonitorQuery.apply(
            cards,
            MonitorQuery.Spec(sort = MonitorQuery.Sort.REPOS_FIRST),
        ).map { it.monitor.displayName }

    @Test
    fun `repositories come before everything else`() {
        val result = order(listOf(website, repoA, brokenSite, repoB))
        assertEquals(listOf("octocat/hello", "riveerxd/nightbell", "Broken site", "Alpha site"), result)
    }

    @Test
    fun `an outage is never buried by the grouping`() {
        // Worst-first still applies inside each group, so a broken repo leads the
        // repos and a broken site leads the rest.
        val result = order(listOf(repoA, brokenRepo, website, brokenSite))
        assertEquals(listOf("zzz/broken", "riveerxd/nightbell", "Broken site", "Alpha site"), result)
    }

    @Test
    fun `with no repositories it is just worst first`() {
        val grouped = order(listOf(website, brokenSite))
        val worstFirst = MonitorQuery.apply(
            listOf(website, brokenSite),
            MonitorQuery.Spec(sort = MonitorQuery.Sort.WORST_FIRST),
        ).map { it.monitor.displayName }
        assertEquals(worstFirst, grouped)
    }

    @Test
    fun `the default is still worst first`() {
        // The one thing this must not do is quietly become the default.
        assertEquals(MonitorQuery.Sort.WORST_FIRST, MonitorQuery.Spec().sort)
        assertTrue(MonitorQuery.Spec().isDefault)
    }

    @Test
    fun `the mode has a label and survives being stored`() {
        assertEquals("Repos first", MonitorQuery.Sort.REPOS_FIRST.label)
        val json = kotlinx.serialization.json.Json.encodeToString(
            MonitorQuery.Sort.serializer(),
            MonitorQuery.Sort.REPOS_FIRST,
        )
        assertEquals("\"repos_first\"", json)
    }
}
