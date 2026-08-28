package me.river.nightbell

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.icons.GroupIcon
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.GroupIconChoice
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grouping monitors, end to end through the real UI.
 *
 * The case the feature exists for is the one in the fixture: "is Nightbell up" is
 * not a question about the website or about the repository, it is a question
 * about both, and the dashboard could only ever answer it one row at a time. So
 * every assertion here is about the *single line* the group card adds, that it
 * exists, that it says the right thing, and that it changes when a member does.
 *
 * Driven through gestures rather than by calling the view model, because the
 * whole path is the point: long-press two cards, tap Group, name it, and see the
 * list fold. A store-level test would have passed with the button unwired.
 */
@RunWith(AndroidJUnit4::class)
class MonitorGroupInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val now = System.currentTimeMillis()

    @After
    fun tearDown() {
        scenario?.close()
    }

    // ---- fixture -----------------------------------------------------------

    private val site = "Nightbell website"
    private val repo = "Nightbell repository"
    private val other = "Checkout API"

    /**
     * The user's own example: two Nightbell monitors and one unrelated one.
     *
     * The third exists so the test can tell "grouped" apart from "the whole list
     * moved", an ungrouped monitor has to stay a plain top-level card.
     */
    private fun seed(
        siteHealth: Health = Health.UP,
        repoHealth: Health = Health.UP,
        groups: List<MonitorGroup> = emptyList(),
        /**
         * Zero everywhere except the animation test.
         *
         * Nightbell's looping animations keep the Compose frame clock busy for
         * ever, so with motion on nothing in this suite could ever reach idle 
         * see [NightbellTestSupport.resetApp]. The one test that needs motion
         * drives the clock by hand instead of waiting for it.
         */
        motion: Float = 0f,
    ) {
        val monitors = listOf(
            Monitor(
                id = "m-site",
                name = site,
                kind = MonitorKind.HTTP_STATUS,
                url = "https://nightbell.app",
                createdAt = now,
            ),
            Monitor(
                id = "m-repo",
                name = repo,
                kind = MonitorKind.HTTP_STATUS,
                url = "https://github.com/river/nightbell",
                createdAt = now,
            ),
            Monitor(
                id = "m-other",
                name = other,
                kind = MonitorKind.HTTP_STATUS,
                url = "https://checkout.example.com",
                createdAt = now,
            ),
        )
        val runtimes = mapOf(
            "m-site" to runtime(siteHealth),
            "m-repo" to runtime(repoHealth),
            "m-other" to runtime(Health.UP),
        )
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = runtimes,
                    groups = groups,
                    settings = GlobalSettings(motionIntensity = motion, hasSeenPagerSetup = true),
                ),
            )
        }
    }

    private fun runtime(health: Health) = MonitorRuntime(
        health = health,
        lastCheckedAt = now - 60_000,
        lastLatencyMs = if (health == Health.DEGRADED) 3_100 else 180,
        lastCode = if (health == Health.DOWN) 503 else 200,
        lastMessage = if (health == Health.DOWN) "Got 503, expected = 200" else "",
        consecutiveFailures = if (health == Health.DOWN) 2 else 0,
    )

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    private fun scrollTo(text: String) {
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    private fun storedGroups() = runBlocking { Nightbell.require().store.currentSnapshot().groups }

    /** Long-press the first, tap the second, and open the group editor. */
    private fun selectBothNightbellMonitorsAndTapGroup() {
        scrollTo(site)
        composeRule.onNodeWithText(site).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()

        scrollTo(repo)
        composeRule.onNodeWithText(repo).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.captureScreenshot("group-01-two-selected")

        composeRule.onNodeWithText("Group these 2").performClick()
        composeRule.waitForIdle()
    }

    // ---- creating ----------------------------------------------------------

    @Test
    fun twoSelectedMonitorsBecomeOneGroupWithACustomTitleAndIcon() {
        seed()
        launch()
        selectBothNightbellMonitorsAndTapGroup()

        composeRule.onNodeWithContentDescription("New group").assertIsDisplayed()
        // The title arrives pre-filled with the words both names share, which is
        // the whole reason the field is worth suggesting into.
        composeRule.onNodeWithContentDescription("Title").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-02-editor-prefilled")

        composeRule.onNodeWithContentDescription("Title").performTextClearance()
        composeRule.onNodeWithContentDescription("Title").performTextInput("Nightbell")
        // Both member sites are on offer, and one of them is already in force 
        // creating a group never lands on "no icon" and never asks for a URL.
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app").performClick()
        composeRule.waitForIdle()
        composeRule.captureDialogScreenshot("group-03-editor-filled")

        composeRule.onNodeWithText("Create group").performClick()

        awaitTrue(description = "the group was written to the store") {
            storedGroups().size == 1
        }
        val group = storedGroups().single()
        assertEquals("Nightbell", group.title)
        assertEquals("https://nightbell.app", group.iconUrl)
        assertEquals(GroupIconChoice.SITE, group.iconChoice)
        // Membership order follows the list the user was looking at, not the
        // order they happened to tap, worst-first puts the repository above the
        // website while both are healthy. Which two is the assertion; which
        // first is the dashboard's business.
        assertEquals(setOf("m-site", "m-repo"), group.memberIds.toSet())
        assertEquals(2, group.memberIds.size)

        composeRule.waitForIdle()
        // The line the feature exists for: one row, one verdict, on the home list.
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        composeRule.onNodeWithText("All 2 operational").assertIsDisplayed()
        // The ungrouped monitor is untouched and still a card of its own.
        scrollTo(other)
        composeRule.onNodeWithText(other).assertIsDisplayed()
        composeRule.captureScreenshot("group-04-collapsed-operational")
    }

    /**
     * A collapsed group replaces its members on the list rather than sitting above
     * them. Folding four rows into one is the point; a group that added a row
     * would have made the list longer.
     */
    @Test
    fun aCollapsedGroupHidesItsMembers() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        composeRule.onAllNodesWithTextCount(site, expected = 0)
        composeRule.onAllNodesWithTextCount(repo, expected = 0)
    }

    @Test
    fun tappingAGroupOpensItAndShowsEveryMemberAsItsOwnCard() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the group is stored as expanded") {
            storedGroups().single().collapsed.not()
        }
        composeRule.waitForIdle()
        scrollTo(site)
        composeRule.onNodeWithText(site).assertIsDisplayed()
        scrollTo(repo)
        composeRule.onNodeWithText(repo).assertIsDisplayed()
        composeRule.captureScreenshot("group-05-expanded")

        // And shut again, from the same target.
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").performClick()
        awaitTrue(description = "the group is stored as collapsed") {
            storedGroups().single().collapsed
        }
    }

    // ---- the verdict -------------------------------------------------------

    @Test
    fun oneDownMemberTakesTheGroupCardDown() {
        seed(repoHealth = Health.DOWN, groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        composeRule.onNodeWithText("1 of 2 is down").assertIsDisplayed()
        // Same word the fleet banner and a monitor card use, so a group is not a
        // third vocabulary for the same fact.
        composeRule.onNodeWithText("Down").assertIsDisplayed()
        composeRule.captureScreenshot("group-06-one-down")
    }

    @Test
    fun aSlowMemberIsReportedAsSlowRatherThanDown() {
        seed(siteHealth = Health.DEGRADED, groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("1 of 2 is slow").assertIsDisplayed()
        composeRule.captureScreenshot("group-07-one-slow")
    }

    /**
     * The regression the roll-up rule exists to prevent.
     *
     * Pausing one member must not let the group read "paused" while another member
     * is genuinely down, that would hide an outage behind a pause set last week.
     */
    @Test
    fun pausingOneMemberDoesNotHideAnotherMembersOutage() {
        seed(repoHealth = Health.DOWN, groups = listOf(nightbellGroup()))
        runBlocking { Nightbell.install(appContext).store.setEnabled("m-site", false) }
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("1 of 2 is down").assertIsDisplayed()
        composeRule.onNodeWithText("1 paused").assertIsDisplayed()
        composeRule.captureScreenshot("group-08-paused-and-down")
    }

    // ---- editing -----------------------------------------------------------

    @Test
    fun theGroupCanBeRenamedFromItsOwnCard() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithContentDescription("Edit group Nightbell").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Edit group").assertIsDisplayed()
        // Both members are listed, each with a way out.
        composeRule.onNodeWithContentDescription("2 monitors").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-09-editor")

        composeRule.onNodeWithContentDescription("Title").performTextClearance()
        composeRule.onNodeWithContentDescription("Title").performTextInput("Nightbell prod")
        composeRule.onNodeWithText("Save").performClick()

        awaitTrue(description = "the new title was stored") {
            storedGroups().single().title == "Nightbell prod"
        }
        composeRule.waitForIdle()
        scrollTo("Nightbell prod")
        composeRule.onNodeWithText("Nightbell prod").assertIsDisplayed()
        composeRule.captureScreenshot("group-10-renamed")
    }

    @Test
    fun removingAMemberInTheEditorLeavesTheMonitorAlone() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithContentDescription("Edit group Nightbell").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Remove $repo from the group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("1 monitor").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        awaitTrue(description = "the group holds one member") {
            storedGroups().single().memberIds == listOf("m-site")
        }
        // The monitor itself survives, and comes back as a top-level card.
        assertEquals(3, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
        composeRule.waitForIdle()
        scrollTo(repo)
        composeRule.onNodeWithText(repo).assertIsDisplayed()
        composeRule.captureScreenshot("group-11-member-removed")
    }

    /**
     * Ungrouping destroys the grouping and nothing else.
     *
     * Worth its own test because the button sits in a dialog full of edits and one
     * wrong call here would delete somebody's monitors and their history.
     */
    @Test
    fun ungroupingKeepsEveryMonitor() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithContentDescription("Edit group Nightbell").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ungroup").performClick()
        composeRule.waitForIdle()
        // It asks first, and says what it will and will not touch.
        composeRule.onNodeWithText("Keep it").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-12-ungroup-confirm")

        composeRule.onNodeWithText("Ungroup").performClick()
        awaitTrue(description = "the group is gone") { storedGroups().isEmpty() }

        val monitors = runBlocking { Nightbell.require().store.currentSnapshot().monitors }
        assertEquals(3, monitors.size)
        assertNotNull(monitors.firstOrNull { it.id == "m-site" })
        assertNotNull(monitors.firstOrNull { it.id == "m-repo" })
        composeRule.waitForIdle()
        scrollTo(site)
        composeRule.onNodeWithText(site).assertIsDisplayed()
        composeRule.captureScreenshot("group-13-ungrouped")
    }

    // ---- joining a group that already exists --------------------------------

    /**
     * The gap this closes: there was a way *out* of a group and no way in.
     *
     * Long-press picked monitors and the only group action created a new group, so
     * a monitor left out of one could never join it, the whole feature was
     * write-once.
     */
    @Test
    fun aMonitorCanBeAddedToAGroupThatAlreadyExists() {
        seed(groups = listOf(nightbellGroup().copy(memberIds = listOf("m-site"))))
        launch()
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        // The label says where they are going, which it can only promise once
        // there is somewhere to go.
        composeRule.onNodeWithText("Add this to a group").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Add to group").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-27-add-to-group")
        // By its action, not by its name: the card behind the dialog says
        // "Nightbell" too, and the matcher has to pick the row in front.
        composeRule.onNodeWithContentDescription("Add to Nightbell, Operational").performClick()

        awaitTrue(description = "the monitor joined the group") {
            storedGroups().single().memberIds == listOf("m-site", "m-other")
        }
        // Appended, not replaced: membership order is the user's.
        assertEquals(listOf("m-site", "m-other"), storedGroups().single().memberIds)
    }

    /**
     * Adding to a shut group opens it.
     *
     * Otherwise the card the user just moved vanishes from the dashboard, it is
     * inside a group drawn closed, which reads exactly like the monitor was
     * deleted. Expanding is what turns a disappearance into a move you can watch.
     */
    @Test
    fun addingToACollapsedGroupOpensItSoTheMoveIsVisible() {
        seed(
            groups = listOf(
                nightbellGroup().copy(memberIds = listOf("m-site"), collapsed = true),
            ),
        )
        launch()
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add this to a group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add to Nightbell, Operational").performClick()

        awaitTrue(description = "the group opened") { !storedGroups().single().collapsed }
        composeRule.waitForIdle()
        // And the monitor is on screen, inside the group rather than gone.
        scrollTo(other)
        composeRule.onNodeWithText(other).assertIsDisplayed()
        composeRule.captureScreenshot("group-28-added-and-opened")
    }

    /**
     * Moving between groups is warned about, because it is a move.
     *
     * One group per monitor, so joining a second one leaves the first. Silently
     * would look like the other group losing a member for no reason.
     */
    @Test
    fun movingAMonitorOutOfItsCurrentGroupIsSaidOutLoud() {
        seed(
            groups = listOf(
                nightbellGroup().copy(memberIds = listOf("m-site", "m-repo")),
                MonitorGroup(id = "g-other", title = "Storefront", memberIds = listOf("m-other")),
            ),
        )
        launch()
        openGroupIfCollapsed()
        scrollTo(repo)
        composeRule.onNodeWithText(repo).performTouchInput { longClick() }
        composeRule.waitForIdle()
        // Already grouped, so the verb is "move": one group per monitor means
        // joining another is leaving this one.
        composeRule.onNodeWithText("Move to another group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Already in “Nightbell”, moving out of it.")
            .assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-29-add-to-group-moving")

        composeRule.onNodeWithContentDescription("Add to Storefront, Operational").performClick()
        awaitTrue(description = "the monitor moved") {
            val groups = storedGroups()
            groups.first { it.id == "g-nightbell" }.memberIds == listOf("m-site") &&
                groups.first { it.id == "g-other" }.memberIds == listOf("m-other", "m-repo")
        }
    }

    @Test
    fun aGroupThatAlreadyHoldsEverythingSelectedSaysSoRatherThanLookingDead() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openGroupIfCollapsed()
        scrollTo(site)
        composeRule.onNodeWithText(site).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Move to another group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Already in this group").assertIsDisplayed()
    }

    /**
     * With no groups yet the chooser is skipped.
     *
     * A menu with one item asks a question the user cannot answer wrongly, and
     * every first group would have paid a tap for it.
     */
    @Test
    fun withNoGroupsYetTheSelectionGoesStraightToTheEditor() {
        seed()
        launch()
        scrollTo(site)
        composeRule.onNodeWithText(site).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Group this monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("New group").assertIsDisplayed()
    }

    /** The way out of the chooser, into a new group instead. */
    @Test
    fun theChooserCanStillMakeANewGroup() {
        seed(groups = listOf(nightbellGroup().copy(memberIds = listOf("m-site"))))
        launch()
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add this to a group").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New group instead").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("New group").assertIsDisplayed()
        composeRule.onNodeWithText("Create group").performClick()
        awaitTrue(description = "a second group exists") { storedGroups().size == 2 }
    }

    // ---- the bar knows where the selection lives ---------------------------

    /**
     * Long-pressing a monitor that is *in* a group offers to take it out.
     *
     * The bar used to offer "add this to a group" for a card already in one, with
     * no way to remove it, an action that could not do anything, in place of the
     * one that was wanted.
     */
    @Test
    fun selectingAGroupedMonitorOffersToRemoveItFromThatGroupByName() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openGroupIfCollapsed()
        scrollTo(site)
        composeRule.onNodeWithText(site).performTouchInput { longClick() }
        composeRule.waitForIdle()

        // Named, so the sentence is one the user can check.
        composeRule.onNodeWithText("Remove from “Nightbell”").assertIsDisplayed()
        // And the other verb is honest about what it does to a grouped monitor.
        composeRule.onNodeWithText("Move to another group").assertIsDisplayed()
        composeRule.captureScreenshot("group-31-selection-grouped")

        composeRule.onNodeWithText("Remove from “Nightbell”").performClick()
        awaitTrue(description = "the monitor left the group") {
            storedGroups().single().memberIds == listOf("m-repo")
        }
        // The monitor itself is untouched and back at the top level.
        assertEquals(3, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
        composeRule.waitForIdle()
        scrollTo(site)
        composeRule.onNodeWithText(site).assertIsDisplayed()
    }

    @Test
    fun selectingAnUngroupedMonitorOffersToAddItAndNothingToRemove() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add this to a group").assertIsDisplayed()
        // Nothing to remove it from, so no button claiming otherwise.
        composeRule.onAllNodesWithText("Remove from “Nightbell”").assertCountEquals(0)
    }

    /**
     * A selection spanning two groups is removed from both, and says so.
     *
     * Naming one group would be wrong and naming both would not fit, so the label
     * goes plural rather than lying about which.
     */
    @Test
    fun aSelectionSpanningTwoGroupsIsRemovedFromBoth() {
        seed(
            groups = listOf(
                nightbellGroup().copy(memberIds = listOf("m-site"), collapsed = false),
                MonitorGroup(
                    id = "g-other",
                    title = "Storefront",
                    memberIds = listOf("m-other"),
                    collapsed = false,
                ),
            ),
        )
        launch()
        // Lower card first. The selection bar covers the bottom of the list once it
        // appears, so a card scrolled to *after* that can land underneath it and the
        // bar takes the tap instead.
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        scrollTo(site)
        composeRule.onNodeWithText(site).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()

        composeRule.onNodeWithText("Remove from their groups").performClick()
        awaitTrue(description = "both groups let go") {
            storedGroups().all { it.memberIds.none { id -> id == "m-site" || id == "m-other" } }
        }
        // Two groups, both still there, both emptier. Nothing was deleted.
        assertEquals(2, storedGroups().size)
        assertEquals(3, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
    }

    /**
     * A mixed selection can do both, and neither button lies.
     *
     * One grouped and one not: "remove" applies to the one that is grouped, and the
     * other verb is still "add" rather than "move", because half of this is an add.
     */
    @Test
    fun aMixedSelectionOffersAddAndRemoveTogether() {
        seed(groups = listOf(nightbellGroup().copy(memberIds = listOf("m-site"))))
        launch()
        scrollTo(other)
        composeRule.onNodeWithText(other).performTouchInput { longClick() }
        composeRule.waitForIdle()
        openGroupIfCollapsed()
        scrollTo(site)
        composeRule.onNodeWithText(site).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()

        composeRule.onNodeWithText("Remove from “Nightbell”").assertIsDisplayed()
        composeRule.onNodeWithText("Add these 2 to a group").assertIsDisplayed()
        composeRule.captureScreenshot("group-32-selection-mixed")
    }

    // ---- choosing where the mark comes from --------------------------------

    /**
     * Two members on two sites means two choices, drawn as what they look like.
     *
     * This is the case a URL field cannot serve: the question is "that one or that
     * one", and the only honest way to ask it is to show both marks at the size
     * they will be used and let the user point.
     */
    @Test
    fun eachMemberSiteIsOfferedAsAChoice() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Use the icon from github.com")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Upload a picture").assertIsDisplayed()
        // The group names nightbell.app, so that is the one in force.
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app")
            .assertIsSelected()
        composeRule.onNodeWithContentDescription("Use the icon from github.com")
            .assertIsNotSelected()
        composeRule.captureDialogScreenshot("group-23-icon-picker")
    }

    @Test
    fun pickingTheOtherSiteChangesTheMark() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Use the icon from github.com").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Use the icon from github.com").assertIsSelected()
        composeRule.captureDialogScreenshot("group-24-icon-picker-other-site")
        composeRule.onNodeWithText("Save").performClick()

        awaitTrue(description = "the second site was stored") {
            storedGroups().single().iconUrl == "https://github.com/river/nightbell"
        }
        // Both halves, or the tap would have done nothing visible on a group that
        // also had a picture.
        assertEquals(GroupIconChoice.SITE, storedGroups().single().iconChoice)
    }

    /**
     * Three monitors on one host offer one choice, not three.
     *
     * A favicon is per origin, so three identical tiles would be three ways to
     * pick the same mark, and no way to tell them apart.
     */
    @Test
    fun monitorsSharingASiteOfferOneChoiceBetweenThem() {
        val extra = Monitor(
            id = "m-blog",
            name = "Nightbell blog",
            kind = MonitorKind.HTTP_STATUS,
            url = "https://nightbell.app/blog",
            createdAt = now,
        )
        seed(groups = listOf(nightbellGroup()))
        runBlocking {
            val store = Nightbell.install(appContext).store
            store.upsert(extra)
            store.upsertGroup(
                nightbellGroup().copy(memberIds = listOf("m-site", "m-blog", "m-repo")),
            )
        }
        launch()
        openEditor()
        assertEquals(
            1,
            composeRule.onAllNodesWithContentDescription("Use the icon from nightbell.app")
                .fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithContentDescription("Use the icon from github.com")
            .assertIsDisplayed()
    }

    // ---- a picture of your own ---------------------------------------------

    @Test
    fun aPickedPictureIsOfferedAsOneMoreChoiceAndIsTheOneInUse() {
        seed(groups = listOf(nightbellGroup().copy(iconImage = squarePicture())))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Use your own picture").assertIsSelected()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app")
            .assertIsNotSelected()
        // Replacing is its own labelled action, because tapping the tile selects.
        composeRule.onNodeWithText("Replace picture").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-25-icon-picker-picture")
    }

    /**
     * Switching to a site keeps the picture, so switching back is one tap.
     *
     * The whole reason `iconChoice` is stored. With "a picture wins if there is
     * one", this tap did nothing visible at all.
     */
    @Test
    fun goingBackToTheSiteIconKeepsThePictureForLater() {
        val picture = squarePicture()
        seed(groups = listOf(nightbellGroup().copy(iconImage = picture)))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Use your own picture").assertIsNotSelected()
        composeRule.onNodeWithText("Save").performClick()

        awaitTrue(description = "the site is the chosen source") {
            storedGroups().single().iconChoice == GroupIconChoice.SITE
        }
        // Not deleted. Nothing about picking a site asked for that.
        assertEquals(picture, storedGroups().single().iconImage)
    }

    @Test
    fun deletingThePictureFallsBackToASiteRatherThanToNothing() {
        seed(groups = listOf(nightbellGroup().copy(iconImage = squarePicture())))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Delete your picture").performClick()
        composeRule.waitForIdle()
        // The tile is gone and the way in is back.
        composeRule.onNodeWithContentDescription("Upload a picture").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app").assertIsSelected()
        composeRule.onNodeWithText("Save").performClick()

        awaitTrue(description = "the picture was cleared") {
            storedGroups().single().iconImage.isEmpty()
        }
        assertEquals(GroupIconChoice.SITE, storedGroups().single().iconChoice)
        assertEquals("https://nightbell.app", storedGroups().single().iconUrl)
    }

    @Test
    fun aPickedPictureSurvivesARestart() {
        val picture = squarePicture()
        seed(groups = listOf(nightbellGroup().copy(iconImage = picture)))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        scenario?.close()
        scenario = null

        launch()
        // Round-tripped through the store's JSON, still decodable, still the same
        // bytes, which is the whole reason it is held in the group rather than in
        // a file the export could not carry.
        assertEquals(picture, storedGroups().single().iconImage)
        assertNotNull(GroupIcon.decode(storedGroups().single().iconImage))
        scrollTo("Nightbell")
        composeRule.onNodeWithText("All 2 operational").assertIsDisplayed()
    }

    // ---- a site nobody is monitoring ---------------------------------------

    /**
     * The address field is behind a disclosure, not in front of it.
     *
     * Naming a site that is not one of your monitors is the rare case; making
     * everyone read a URL field to serve it put a form where a choice belonged.
     */
    @Test
    fun anotherSiteIsOfferedButNotInTheWay() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Site address").assertDoesNotExist()
        composeRule.onNodeWithText("Another site…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Site address").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-26-icon-picker-another-site")
    }

    /**
     * A typed address becomes a tile of its own, immediately.
     *
     * The bug this covers, reported from a real device: typing a site into the
     * address field changed nothing visible. The row only ever listed the
     * *members'* sites, so a mark the user had just typed was selected nowhere,
     * and the field looked like it had no way to submit. It saved correctly, the
     * interface simply never said so, which is the same thing as broken.
     */
    @Test
    fun aTypedAddressGetsItsOwnTileAndIsShownInUse() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openEditor()
        composeRule.onNodeWithText("Another site…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Site address").performTextClearance()
        composeRule.onNodeWithContentDescription("Site address")
            .performTextInput("https://example.org")
        composeRule.waitForIdle()

        // The tile exists, it is the selected one, and the members' are not.
        composeRule.onNodeWithContentDescription("Use the icon from example.org")
            .assertIsSelected()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app")
            .assertIsNotSelected()
        // And the field says so in words as well as in the row.
        composeRule.onNodeWithText("In use. It is the last tile above.").assertIsDisplayed()
        composeRule.captureDialogScreenshot("group-30-typed-site")

        composeRule.onNodeWithText("Save").performClick()
        awaitTrue(description = "the typed address was stored") {
            storedGroups().single().iconUrl == "https://example.org"
        }
        assertEquals(GroupIconChoice.SITE, storedGroups().single().iconChoice)
    }

    /**
     * Typing does not close the field it is being typed into.
     *
     * The tile for a typed address is selectable like any other, and selecting a
     * tile normally hides the address field, which, for this one tile, would pull
     * the field out from under the user mid-edit.
     */
    @Test
    fun selectingTheTypedTileLeavesTheAddressFieldOpen() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        openEditor()
        composeRule.onNodeWithText("Another site…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Site address").performTextClearance()
        composeRule.onNodeWithContentDescription("Site address")
            .performTextInput("https://example.org")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Use the icon from example.org").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Site address").assertIsDisplayed()
    }

    /**
     * A site in force that no tile accounts for opens the field by itself.
     *
     * Otherwise the group would be showing a mark with nothing on screen
     * explaining where it came from, the picker would look like it was lying.
     */
    @Test
    fun anAddressNoMemberSharesShowsItselfOnOpening() {
        seed(
            groups = listOf(
                nightbellGroup().copy(
                    iconUrl = "https://example.org",
                    iconChoice = GroupIconChoice.SITE,
                ),
            ),
        )
        launch()
        openEditor()
        composeRule.onNodeWithContentDescription("Site address").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Use the icon from nightbell.app")
            .assertIsNotSelected()
    }

    // ---- the rules the store owns ------------------------------------------

    @Test
    fun deletingAMonitorTakesItOutOfItsGroup() {
        seed(groups = listOf(nightbellGroup()))
        runBlocking { Nightbell.install(appContext).store.delete("m-repo") }
        val group = storedGroups().single()
        assertEquals(listOf("m-site"), group.memberIds)
        assertFalse("m-repo" in group.memberIds)
    }

    @Test
    fun aMonitorCanOnlyBelongToOneGroup() {
        val first = MonitorGroup(id = "g1", title = "First", memberIds = listOf("m-site", "m-repo"))
        seed(groups = listOf(first))
        runBlocking {
            Nightbell.install(appContext).store.upsertGroup(
                MonitorGroup(id = "g2", title = "Second", memberIds = listOf("m-repo")),
            )
        }
        val groups = storedGroups()
        assertEquals(listOf("m-site"), groups.first { it.id == "g1" }.memberIds)
        assertEquals(listOf("m-repo"), groups.first { it.id == "g2" }.memberIds)
    }

    /**
     * A search has to fall back to a flat list.
     *
     * A group card cannot honestly state a verdict for members the search is
     * hiding, so while the list is narrowed the matches are shown as themselves.
     */
    @Test
    fun searchingShowsMatchingMonitorsRatherThanTheirGroup() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        composeRule.onNodeWithContentDescription("Search monitors").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").performTextInput("website")
        composeRule.waitForIdle()
        scrollTo(site)
        composeRule.onNodeWithText(site).assertIsDisplayed()
        composeRule.captureScreenshot("group-14-search-flattens")
    }

    @Test
    fun aGroupSurvivesARestart() {
        seed(groups = listOf(nightbellGroup()))
        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        scenario?.close()
        scenario = null

        launch()
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").assertIsDisplayed()
        composeRule.onNodeWithText("All 2 operational").assertIsDisplayed()
        assertNull(storedGroups().firstOrNull { it.title != "Nightbell" })
        assertTrue(storedGroups().single().memberIds.size == 2)
    }

    /** Expands the seeded group if it is shut, so its members are on screen. */
    private fun openGroupIfCollapsed() {
        if (!storedGroups().first().collapsed) return
        scrollTo("Nightbell")
        composeRule.onNodeWithText("Nightbell").performClick()
        awaitTrue(description = "the group opened") { !storedGroups().first().collapsed }
        composeRule.waitForIdle()
    }

    /** Opens the editor on the seeded group. */
    private fun openEditor() {
        scrollTo("Nightbell")
        composeRule.onNodeWithContentDescription("Edit group Nightbell").performClick()
        composeRule.waitForIdle()
    }

    /** A small solid picture, encoded the way the picker would encode one. */
    private fun squarePicture(): String = GroupIcon.encode(
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        },
    )!!

    private fun nightbellGroup(collapsed: Boolean = true) = MonitorGroup(
        id = "g-nightbell",
        title = "Nightbell",
        iconUrl = "https://nightbell.app",
        memberIds = listOf("m-site", "m-repo"),
        collapsed = collapsed,
    )
}

/**
 * Asserts how many nodes carry [text].
 *
 * `assertDoesNotExist` on a lazy list is not the assertion wanted here: a card
 * off screen also does not exist. The collapsed-group case needs "the member is
 * not in the tree while the group card is", which is a count.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(
    text: String,
    expected: Int,
) {
    waitForIdle()
    val found = onAllNodes(
        androidx.compose.ui.test.hasText(text),
        useUnmergedTree = false,
    ).fetchSemanticsNodes().size
    if (found != expected) {
        throw AssertionError("Expected $expected node(s) with text \"$text\", found $found")
    }
}

/**
 * Captures the whole display, dialog included.
 *
 * `onRoot().captureToImage()` cannot do this: a Compose `Dialog` is a second
 * window, so with one open there are two roots, and the *last* of them turned
 * out to be the activity, which produced a screenshot of the dashboard behind a
 * dialog that was demonstrably on screen. `UiAutomation` photographs the display
 * rather than a composition, which is what a screenshot of a modal has to be.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.captureDialogScreenshot(name: String) {
    waitForIdle()
    val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .takeScreenshot()
    java.io.File(NightbellTestSupport.screenshotDir(), "$name.png").outputStream().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
}
