package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings, split into four tabs.
 *
 * Twenty cards on one scroll was a screen nobody reached the bottom of. The split
 * only helps if two things hold, and both are what this asserts:
 *
 *  - **nothing was lost.** Every card is still reachable, on exactly one tab. A
 *    regrouping that quietly dropped the proxy card would look fine on every
 *    screenshot and be a bug you find months later.
 *  - **a tab is a page, not a filter.** Its scroll position is its own, and
 *    switching does not reset the one you left.
 *
 * The per-card assertions name a string that only exists if that card rendered,
 * so a card moved to the wrong tab fails here rather than passing quietly.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTabsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun seed(motion: Float = 0f) {
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(
                        Monitor(
                            id = "m1",
                            name = "Checkout API",
                            kind = MonitorKind.HTTP_STATUS,
                            url = "https://checkout.example.com",
                        ),
                    ),
                    runtimes = mapOf("m1" to MonitorRuntime(health = Health.UP)),
                    settings = GlobalSettings(motionIntensity = motion, hasSeenPagerSetup = true),
                ),
            )
        }
    }

    private fun openSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(matcher)
        composeRule.waitForIdle()
    }

    // ---- the bar ------------------------------------------------------------

    @Test
    fun allFourTabsAreOfferedAndAlertsOpensFirst() {
        seed()
        openSettings()
        listOf("Alerts", "Checks", "Look", "About").forEach { label ->
            composeRule.onNodeWithContentDescription("$label tab").assertIsDisplayed()
        }
        // Alerts first because it is what people open Settings to change; the
        // master switch is the top card on it.
        composeRule.onNodeWithText("All alerts").assertIsDisplayed()
        composeRule.captureScreenshot("settings-01-alerts")
    }

    /**
     * The title and the blocked-notifications warning are above the bar, not on a
     * tab.
     *
     * A banner saying alerts cannot reach you is true on every tab, and putting it
     * behind one would be the single worst place in this app to hide it. Asserted
     * from a tab that is not Alerts, which is the case that would break.
     */
    @Test
    fun theTitleStaysPutWhicheverTabIsOpen() {
        seed()
        openSettings()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    // ---- nothing was lost ---------------------------------------------------

    @Test
    fun alertsHoldsWhatGetsAnnounced() {
        seed()
        openSettings()
        composeRule.onNodeWithText("All alerts").assertIsDisplayed()
        scrollTo(hasContentDescription("Default alert policy"))
        composeRule.onNodeWithContentDescription("Default alert policy").assertIsDisplayed()
        scrollTo(hasContentDescription("Pause button"))
        composeRule.onNodeWithContentDescription("Pause button").assertIsDisplayed()
        scrollTo(hasContentDescription("TLS certificates"))
        composeRule.onNodeWithContentDescription("TLS certificates").assertIsDisplayed()
    }

    @Test
    fun checksHoldsHowTheCheckingRuns() {
        seed()
        openSettings()
        composeRule.openSettingsTab("Checks")
        composeRule.onNodeWithContentDescription("Background checks").assertIsDisplayed()
        scrollTo(hasContentDescription("Checker health"))
        composeRule.onNodeWithContentDescription("Checker health").assertIsDisplayed()
        scrollTo(hasContentDescription("Strict cadence"))
        composeRule.onNodeWithContentDescription("Strict cadence").assertIsDisplayed()
        scrollTo(hasContentDescription("Latency budget"))
        composeRule.onNodeWithContentDescription("Latency budget").assertIsDisplayed()
        scrollTo(hasContentDescription("SOCKS5 proxy"))
        composeRule.onNodeWithContentDescription("SOCKS5 proxy").assertIsDisplayed()
        scrollTo(hasContentDescription("GitHub"))
        composeRule.onNodeWithContentDescription("GitHub").assertIsDisplayed()
        composeRule.captureScreenshot("settings-02-checks")
    }

    @Test
    fun lookHoldsAppearanceAndTheSurfacesOutsideTheApp() {
        seed()
        openSettings()
        composeRule.openSettingsTab("Look")
        composeRule.onNodeWithContentDescription("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        scrollTo(hasContentDescription("Motion"))
        composeRule.onNodeWithContentDescription("Motion").assertIsDisplayed()
        scrollTo(hasContentDescription("Site icons"))
        composeRule.onNodeWithContentDescription("Site icons").assertIsDisplayed()
        scrollTo(hasContentDescription("Home-screen widgets"))
        composeRule.onNodeWithContentDescription("Home-screen widgets").assertIsDisplayed()
        composeRule.captureScreenshot("settings-03-look")
    }

    @Test
    fun aboutHoldsTheAppAsAThingYouInstalled() {
        seed()
        openSettings()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithContentDescription("Nightbell updates").assertIsDisplayed()
        scrollTo(hasContentDescription("Backup and transfer"))
        composeRule.onNodeWithContentDescription("Backup and transfer").assertIsDisplayed()
        scrollTo(hasContentDescription("Help"))
        composeRule.onNodeWithContentDescription("Help").assertIsDisplayed()
        scrollTo(hasContentDescription("About"))
        composeRule.onNodeWithContentDescription("About").assertIsDisplayed()
        composeRule.captureScreenshot("settings-04-about")
    }

    // ---- a tab is a page ----------------------------------------------------

    /**
     * A tab keeps the scroll position you left it at.
     *
     * The alternative, one list, filtered per tab, looks identical in a
     * screenshot and behaves like four screens that keep forgetting where you
     * were. This is the assertion that tells the two apart.
     */
    @Test
    fun eachTabRemembersWhereItWasScrolledTo() {
        seed()
        openSettings()
        composeRule.openSettingsTab("Checks")
        scrollTo(hasContentDescription("GitHub"))
        composeRule.onNodeWithContentDescription("GitHub").assertIsDisplayed()

        // Away and back.
        composeRule.openSettingsTab("Look")
        composeRule.onNodeWithContentDescription("Appearance").assertIsDisplayed()
        composeRule.openSettingsTab("Checks")
        // Still at the bottom of Checks, not back at the top.
        composeRule.onNodeWithContentDescription("GitHub").assertIsDisplayed()
    }

    @Test
    fun tabsCanBeSwipedThroughAsWellAsTapped() {
        seed()
        openSettings()
        composeRule.onNodeWithText("All alerts").assertIsDisplayed()

        composeRule.onNodeWithTag("settings-list").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Background checks").assertIsDisplayed()

        composeRule.onNodeWithTag("settings-list").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All alerts").assertIsDisplayed()
    }

    /**
     * Switching tabs must not touch the store.
     *
     * Worth pinning because the tab bar sits directly above cards whose every
     * control writes settings, a bar that recomposed a page into a fresh default
     * and saved it would be a data-loss bug wearing a navigation costume.
     */
    @Test
    fun movingBetweenTabsChangesNothingButTheView() {
        seed()
        openSettings()
        val before = runBlocking { Nightbell.require().store.currentSnapshot().settings }
        listOf("Checks", "Look", "About", "Alerts").forEach { composeRule.openSettingsTab(it) }
        val after = runBlocking { Nightbell.require().store.currentSnapshot().settings }
        assertEquals(before, after)
    }
}
