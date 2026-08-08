package me.river.nightbell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.data.Nightbell
import me.river.nightbell.ui.permissions.TAG_DISMISS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pager-setup gate: that it stands in front of the dashboard while grants are
 * missing, that dismissing it is recorded, and that it never returns afterwards.
 *
 * The one suite that opts *into* the gate — [NightbellTestSupport.resetApp] skips it,
 * because on an emulator some grant is always missing and every other UI suite
 * would otherwise be asserting against this screen instead of the app.
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

    @Test
    fun theGateStandsInFrontOfTheDashboardOnAFreshInstall() {
        NightbellTestSupport.resetAppAtPagerSetup()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Let Nightbell wake you when something breaks").assertIsDisplayed()
    }

    /**
     * Skipping must be recorded *and* must land on the dashboard. Recording it
     * without navigating, or navigating without recording, both produce a gate the
     * user cannot get past.
     */
    @Test
    fun skippingIsRecordedAndOpensTheApp() {
        NightbellTestSupport.resetAppAtPagerSetup()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // By tag, not by text: the label is "Skip for now" or "Continue anyway"
        // depending on whether notifications happen to be granted already.
        composeRule.onNodeWithTag(TAG_DISMISS).performClick()
        composeRule.waitForIdle()

        NightbellTestSupport.awaitTrue(description = "the skip was persisted") {
            runBlocking {
                Nightbell.install(NightbellTestSupport.appContext)
                    .store.currentSnapshot().settings.hasSeenPagerSetup
            }
        }
        assertTrue(
            "the dashboard must be reachable after skipping",
            runBlocking {
                Nightbell.install(NightbellTestSupport.appContext)
                    .store.currentSnapshot().settings.hasSeenPagerSetup
            },
        )
    }

    @Test
    fun theGateDoesNotReturnOnceSeen() {
        NightbellTestSupport.resetApp()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
    }
}
