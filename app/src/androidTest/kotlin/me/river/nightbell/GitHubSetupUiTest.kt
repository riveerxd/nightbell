package me.river.nightbell

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Creating a repository monitor the way a person does: through the wizard.
 *
 * The engine-level suite proves the behaviour; this proves it is reachable. A
 * feature that works perfectly and cannot be set up is not a feature, and the
 * shape of this one (a field that takes any GitHub link and stores a parsed
 * `owner/repo`) is exactly the kind that can be right in the domain and broken
 * in the form.
 */
@RunWith(AndroidJUnit4::class)
class GitHubSetupUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }


    /**
     * Every string on the one card that carries [label].
     *
     * A dashboard card is clickable, so Compose merges its descendants into one
     * semantics node and that node keeps the whole list of texts. Reading it back
     * is how an assertion can say "not on *this* card" rather than "nowhere on the
     * screen", which is a different and usually wrong claim.
     */
    private fun textsOn(label: String): List<String> =
        composeRule.onAllNodes(hasText(label))
            .fetchSemanticsNodes()
            .first()
            .config[SemanticsProperties.Text]
            .map { it.text }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    @Test
    fun theEmptyStateOffersARepositoryTemplate() {
        launchApp()
        composeRule.onNodeWithText("A GitHub repository").assertIsDisplayed()
        composeRule.onNodeWithText("A GitHub repository").performClick()
        composeRule.waitForIdle()
        // Step 0 is answered by the template, so the wizard opens on the target.
        composeRule.onNodeWithText("2/4").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Repository").assertIsDisplayed()
        composeRule.captureScreenshot("gh-01-template-target")
        // Nothing is saved by picking a template.
        assertEquals(0, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
    }

    @Test
    fun aPastedIssueLinkBecomesARepositoryMonitor() {
        launchApp()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("GitHub repo").performScrollTo().performClick()
        composeRule.onNodeWithText("Stars, issues, comments and releases on one repository.")
            .assertIsDisplayed()
        composeRule.captureScreenshot("gh-02-kind")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        // The address bar of the page the issue was read on, pasted whole.
        composeRule.onNodeWithContentDescription("Repository")
            .performTextInput("https://github.com/riveerxd/nightbell/issues/4")
        composeRule.waitForIdle()

        // The parsed slug is echoed back, which is the only feedback that the
        // link was understood rather than merely accepted. The keyboard goes
        // first: it covers the lower half of the form it was opened to fill in.
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("github-repo-parsed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("setup-create-github-token").performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("gh-03-repo-parsed")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        // Step 3 is what to be told about, not a status code.
        composeRule.onNodeWithText("Watch the star count").assertIsDisplayed()
        composeRule.onNodeWithText("Every new star").assertIsDisplayed()
        composeRule.onNodeWithText("Milestones").assertIsDisplayed()
        composeRule.onNodeWithText("New issues").assertIsDisplayed()
        composeRule.onNodeWithText("Pull requests").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("New comments").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("New releases").performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("gh-04-watch-options")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the repository monitor to be stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
        assertEquals(MonitorKind.GITHUB_REPO, monitor.kind)
        assertEquals("riveerxd", monitor.github.owner)
        assertEquals("nightbell", monitor.github.repo)
        // The URL is derived, so every "open this" in the app lands somewhere real.
        assertEquals("https://github.com/riveerxd/nightbell", monitor.url)
        // Defaults: every star, issues and releases, and nothing urgent.
        assertTrue(monitor.github.notifyOnStars)
        assertTrue(monitor.github.notifyOnEveryStar)
        assertTrue(monitor.github.notifyOnIssues)
        assertTrue(monitor.github.watchReleases)
        assertTrue("pull requests must stay off by default", !monitor.github.watchPullRequests)
        // A comment watcher that defaulted on would make the first poll of any
        // busy repository the loudest thing this app has ever done.
        assertTrue("comments must stay off by default", !monitor.github.notifyOnComments)
        assertTrue("bot comments must stay off by default", !monitor.github.notifyOnBotComments)
        assertTrue("a repo monitor must never default to paging", !monitor.urgent)
        assertTrue("cadence must respect the anonymous budget", monitor.intervalMinutes >= 15)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("riveerxd/nightbell").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("riveerxd/nightbell").onFirst().assertIsDisplayed()
        composeRule.captureScreenshot("gh-05-dashboard")
    }

    /**
     * The comment option, driven the way a user reaches it.
     *
     * Every assertion here is about something that can only be seen on a device:
     * that the row is where the section puts it, that turning it on reveals the
     * two controls that belong to it, that the filter field a comments-only
     * monitor needs is reachable, and that the subtitle tells the truth about
     * pull requests in both of its states.
     */
    @Test
    fun theCommentOptionRevealsItsControlsAndStoresWhatWasChosen() {
        launchApp()
        composeRule.onNodeWithText("A GitHub repository").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Repository").performTextInput("riveerxd/nightbell")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        // Off to begin with, and the subtitle says so rather than leaving the user
        // to work it out from an unlit switch.
        composeRule.onNodeWithText("New comments").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Comments are ignored").assertIsDisplayed()
        composeRule.captureScreenshot("gh-comments-off")

        composeRule.onNodeWithTag("github-watch-comments").performScrollTo().performClick()
        composeRule.waitForIdle()

        // On, with pull requests still off, so the subtitle has to explain that
        // pull request threads come from the same endpoint and stay out.
        composeRule.onNodeWithText("Comments from bots").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Comments posted by a GitHub app are skipped").assertIsDisplayed()
        composeRule.onNodeWithTag("github-comment-muted").performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("gh-comments-on")

        // Addressed by its label rather than its tag: the tag sits on the field's
        // frame, and the node that takes focus is the text field inside it, which
        // carries the raw label as its content description.
        composeRule.onNodeWithTag("github-comment-muted").performScrollTo()
        composeRule.onNodeWithContentDescription("Never from (optional)")
            .performTextInput("rustbot, rust-timer")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()

        // Two logins, typed as one string with a comma in the middle. The field
        // stores raw text precisely so the comma survives being typed.
        composeRule.onNodeWithText("rustbot, rust-timer").assertIsDisplayed()

        // Issues and pull requests both off, comments on: the keyword field must
        // still be reachable, because it still filters.
        composeRule.onNodeWithTag("github-watch-issues").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("github-keywords").performScrollTo().assertIsDisplayed()
        // The author allowlist does not reach comments, so it must not be offered
        // to a monitor that only watches them.
        composeRule.onAllNodesWithTag("github-authors").assertCountEquals(0)
        composeRule.captureScreenshot("gh-comments-only")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the comment watcher to be stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        val watch = runBlocking {
            Nightbell.require().store.currentSnapshot()
        }.monitors.single().github
        assertTrue(watch.notifyOnComments)
        assertFalse("the bot switch was never touched", watch.notifyOnBotComments)
        assertEquals(listOf("rustbot", "rust-timer"), watch.commentMutedAuthors)
        assertFalse("issues were switched off", watch.notifyOnIssues)
        // A comments-only monitor is watching something, so the config row must
        // not read as though nothing was selected.
        assertTrue(watch.summary.contains("comments"))
    }

    @Test
    fun theDetailScreenShowsTheRepositoryCard() {
        launchApp()
        composeRule.onNodeWithText("A GitHub repository").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Repository").performTextInput("riveerxd/nightbell")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the repository monitor to be stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("riveerxd/nightbell").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("riveerxd/nightbell").onFirst().performClick()
        composeRule.waitForIdle()

        // The health card, whether or not the live poll has landed yet.
        // A lazy list, and the card is taller than the viewport, so each assertion
        // scrolls to what it is about rather than assuming one scroll covers all.
        val list = composeRule.onNodeWithTag("detail-list")
        // The tile row, by tag: its labels live inside merged tile semantics, and
        // a lazy list can only be scrolled to something it can match on the way
        // past.
        list.performScrollToNode(hasTestTag("github-metrics"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Repository").assertIsDisplayed()
        composeRule.onNodeWithTag("github-metrics").assertIsDisplayed()
        // The strip is number-then-unit on one line, and the star count's unit is
        // the gold glyph, which carries the name as its content description.
        // Two of them on this screen: the hero line and the strip. Either proves it.
        composeRule.onAllNodesWithContentDescription("stars", useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
        // Substring, because the label is singular at a count of one, and "first"
        // because the hero line says the same thing further up the screen.
        composeRule.onAllNodesWithText("open issue", substring = true, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("fork", substring = true, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
        composeRule.captureScreenshot("gh-06-detail")

        list.performScrollToNode(hasTestTag("github-open-repo"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("github-open-repo").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("github-mark-seen"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("github-mark-seen").assertIsDisplayed()
        composeRule.onNodeWithTag("github-mute-24h").assertIsDisplayed()
        composeRule.captureScreenshot("gh-06b-detail-actions")

        // And no response-time chart anywhere on the screen. It plots the round
        // trip to api.github.com, which is not what this monitor is about. 3.2.0
        // took it off the dashboard card and left it standing here, which is the
        // whole of what 3.2.1 fixes.
        composeRule.onAllNodesWithContentDescription("Response time").assertCountEquals(0)
    }

    @Test
    fun settingsExplainsTheTokenAndLinksToGitHub() {
        launchApp()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()

        composeRule.openSettingsTab("Checks")
        composeRule.onNodeWithTag("settings-list").assertIsDisplayed()
        // A LazyColumn, so a card further down the list is not composed until the
        // list is asked to scroll to it.
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("create-github-token"))
        composeRule.waitForIdle()
        // The section header renders ALL CAPS and carries the real title as its
        // content description, so screen readers do not spell it out.
        composeRule.onNodeWithContentDescription("GitHub").assertIsDisplayed()
        composeRule.onNodeWithTag("create-github-token").assertIsDisplayed()
        composeRule.onNodeWithTag("github-token-field").assertIsDisplayed()
        composeRule.onNodeWithText("Create a GitHub token").assertIsDisplayed()
        composeRule.captureScreenshot("gh-07-settings-token")

        // GitHub's token page is a list of about thirty checkboxes with no hint
        // which ones matter, so the app has to name them. "Least privilege" is
        // advice; these three words are an instruction.
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("token-scopes-public"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("token-scopes-public").assertIsDisplayed()
        composeRule.onNodeWithText("PERMISSIONS TO TICK").assertIsDisplayed()
        listOf("Contents", "Issues", "Pull requests").forEach { permission ->
            composeRule.onNodeWithText(permission).assertIsDisplayed()
        }
        composeRule.captureScreenshot("gh-07b-settings-token-scopes")

        // Updates are not a checking setting, so they are a tab away.
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("check-for-update"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Nightbell updates").assertIsDisplayed()
        composeRule.onNodeWithTag("check-for-update").assertIsDisplayed()
        composeRule.captureScreenshot("gh-08-settings-updates")
    }

    /**
     * The dashboard card answers "what happened to my repo", not "is GitHub up".
     *
     * The card used to carry a status pill, a latency reading, a network-excess
     * correction, an HTTP code, a GET chip and an uptime sparkline. Every one of
     * those describes api.github.com, which nobody added this monitor to find out
     * about. What belongs there is what the user chose to watch.
     */
    @Test
    fun theDashboardCardShowsRepositoryFactsAndNotGitHubsUptime() {
        // Seeded straight into the store rather than created through the wizard.
        // Driving this one through a live poll made it depend on GitHub's 60/hour
        // anonymous budget, which is a thing other people on the same address can
        // spend: the test then fails for a reason that says nothing about the app.
        val monitor = Monitor(
            id = "dash-repo",
            kind = MonitorKind.GITHUB_REPO,
            url = "https://github.com/riveerxd/nightbell",
            intervalMinutes = 15,
            github = GitHubWatch(owner = "riveerxd", repo = "nightbell"),
        )
        // A second, ordinary monitor: the filter and sort controls only appear once
        // there is enough on the dashboard to be worth arranging, and a grouping
        // that puts repos first is meaningless with nothing to put them before.
        val website = Monitor(
            id = "dash-site",
            name = "Alpha site",
            kind = MonitorKind.HTTP_STATUS,
            url = "https://example.com/health",
        )
        runBlocking {
            val store = Nightbell.install(NightbellTestSupport.appContext).store
            store.upsert(monitor)
            store.upsert(website)
            store.setEnabled(website.id, false)
            store.updateRuntime(monitor.id) {
                it.copy(
                    health = Health.UP,
                    lastCheckedAt = System.currentTimeMillis(),
                    // Deliberately populated. The card has every one of these to
                    // hand and must decline to show them: they describe
                    // api.github.com, not the repository.
                    lastLatencyMs = 181,
                    lastCode = 200,
                    github = GitHubState(
                        seeded = true,
                        lastStarCount = 13,
                        openIssues = 1,
                        forks = 2,
                        lastReleaseTag = "v3.1.1",
                    ),
                )
            }
        }
        launchApp()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("13").fetchSemanticsNodes().isNotEmpty()
        }
        // The three facts this monitor is watching.
        composeRule.onAllNodesWithText("13").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1 open").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("v3.1.1").onFirst().assertIsDisplayed()
        composeRule.captureScreenshot("gh-10-dashboard-card")

        // And none of the things that describe GitHub rather than the repository.
        // Scoped to this card: the ordinary monitor beside it shows a GET chip and
        // a status pill quite correctly, and a dashboard-wide assertion would be
        // claiming something about it that is not true.
        val onTheRepoCard = textsOn("riveerxd/nightbell")
        listOf("Operational", "GET", "200", "181 ms").forEach { unwanted ->
            assertFalse(
                "the repository card should not carry \"$unwanted\": $onTheRepoCard",
                onTheRepoCard.contains(unwanted),
            )
        }

        // And the grouping that puts repositories at the top is on offer. Asserted
        // here rather than in its own test because the filter control only exists
        // once there is something to filter.
        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Repos first").performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("gh-11-sort-repos-first")
        // Still not the default: worst-first answers "is anything broken", and
        // that is what the app should open on.
        assertEquals(
            MonitorQuery.Sort.WORST_FIRST,
            runBlocking { Nightbell.require().store.currentSnapshot() }.settings.dashboardSort,
        )
    }

    /**
     * Issue #4, from the user's side: the endpoint the app shows is not Google's.
     */
    @Test
    fun settingsShowsTheGrapheneOsReferenceEndpoint() {
        launchApp()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("Checks")
        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToNode(hasText("Discount my connection"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discount my connection").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("reference-endpoint"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reference-endpoint").assertIsDisplayed()
        composeRule.captureScreenshot("gh-09-settings-reference")

        // The preset chip under the field, which is the fix as a user meets it.
        list.performScrollToNode(hasText("GrapheneOS"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GrapheneOS").assertIsDisplayed()
        composeRule.captureScreenshot("gh-09b-settings-preset")

        val settings = runBlocking { Nightbell.require().store.currentSnapshot() }.settings
        assertEquals(
            "https://connectivitycheck.grapheneos.network/generate_204",
            settings.latencyReferenceUrl,
        )
    }
}
