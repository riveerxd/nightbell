package me.river.nightbell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.ui.permissions.TAG_DISMISS
import me.river.nightbell.ui.permissions.TAG_SILENCE
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pager-setup gate: that it stands in front of the dashboard while grants are
 * missing, that it keeps coming back until they are made, and that the one button
 * which stops it says so.
 *
 * The one suite that opts *into* the gate — [NightbellTestSupport.resetApp] skips it,
 * because on an emulator some grant is always missing and every other UI suite
 * would otherwise be asserting against this screen instead of the app.
 *
 * The behaviour under test here replaced its own opposite. Skipping used to be
 * permanent, and the only route in the app to the full-screen permission runs
 * through this screen, so one tap past it meant an urgent page could never wake a
 * locked phone again and nothing anywhere said why.
 */
@RunWith(AndroidJUnit4::class)
class PagerSetupInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        NightbellTestSupport.resetApp()
    }

    /** A launch, closed afterwards, so the next one starts the app cold. */
    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollSettingsTo(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    @Test
    fun theGateStandsInFrontOfTheDashboardOnAFreshInstall() {
        NightbellTestSupport.resetAppAtPagerSetup()
        launch()
        composeRule.onNodeWithText("Let Nightbell wake you when something breaks").assertIsDisplayed()
        // The whole screen scrolls, and the opt-out is the last thing on it, so
        // the footer has to be brought up before it can be looked at.
        composeRule.onNodeWithTag(TAG_SILENCE).performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("pager-01-gate-with-the-opt-out")
    }

    /**
     * Skipping must land on the dashboard and must not be recorded as an answer.
     *
     * The grant is still missing after a skip, so the question is still live, and
     * this is the exact assertion that would have caught the shipped bug.
     */
    @Test
    fun skippingOpensTheAppAndTheGateComesBackNextLaunch() {
        NightbellTestSupport.resetAppAtPagerSetup()
        launch()

        // By tag, not by text: the label is "Skip for now" or "Continue anyway"
        // depending on whether notifications happen to be granted already.
        composeRule.onNodeWithTag(TAG_DISMISS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()

        assertFalse(
            "skipping must not answer a question the user was not asked",
            NightbellTestSupport.pagerSetupSilenced(),
        )

        launch()
        composeRule.onNodeWithText("Let Nightbell wake you when something breaks").assertIsDisplayed()
    }

    /** The one control that stops it, and the flag it writes. */
    @Test
    fun askingItToStopIsRecordedAndHolds() {
        NightbellTestSupport.resetAppAtPagerSetup()
        launch()

        composeRule.onNodeWithTag(TAG_SILENCE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()

        NightbellTestSupport.awaitTrue(description = "the silence was never persisted") {
            NightbellTestSupport.pagerSetupSilenced()
        }

        launch()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
    }

    /**
     * An activity recreation must not drag the user back to the gate.
     *
     * The start destination is computed fresh in `onCreate`, and a rotation or a
     * process restart runs that again with the grant still missing. What stops it
     * is the saved back stack, and that is worth an assertion rather than a
     * belief, because the failure mode is being thrown out of whatever you were
     * doing every time you turn the phone.
     */
    @Test
    fun rotatingAfterASkipDoesNotThrowTheUserBackIntoTheGate() {
        NightbellTestSupport.resetAppAtPagerSetup()
        launch()
        composeRule.onNodeWithTag(TAG_DISMISS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()

        scenario?.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
    }

    /**
     * The route back, which is the half that was missing entirely.
     *
     * Reached from Settings the screen has to be leavable without answering
     * anything, so it offers Done and no silence button.
     */
    @Test
    fun settingsOpensTheScreenAndTheSwitchTurnsTheCheckBackOn() {
        NightbellTestSupport.resetApp()
        launch()
        assertTrue(
            "this test needs to start silenced, the way a skipped install is",
            NightbellTestSupport.pagerSetupSilenced(),
        )

        openSettings()
        scrollSettingsTo("open-pager-setup")
        composeRule.captureScreenshot("pager-02-settings-card")
        composeRule.onNodeWithTag("open-pager-setup").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Let Nightbell wake you when something breaks").assertIsDisplayed()
        composeRule.onNodeWithText("Alert permissions").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SILENCE).assertDoesNotExist()
        composeRule.captureScreenshot("pager-03-revisited-from-settings")

        // Back to Settings rather than to the dashboard, because Settings is what
        // is behind it.
        composeRule.onNodeWithTag(TAG_DISMISS).performClick()
        composeRule.waitForIdle()
        scrollSettingsTo("pager-check-at-launch")
        composeRule.onNodeWithTag("pager-check-at-launch").performClick()
        composeRule.waitForIdle()

        NightbellTestSupport.awaitTrue(description = "the switch never cleared the silence") {
            !NightbellTestSupport.pagerSetupSilenced()
        }
    }
}
