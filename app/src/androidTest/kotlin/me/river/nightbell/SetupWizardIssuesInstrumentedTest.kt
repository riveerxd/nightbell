package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two setup-screen reports in issue #16, driven through the wizard.
 *
 * Both are things the JVM suite cannot see. One is about what a field contains
 * after focus leaves it, and the other is about how many copies of a control are
 * on screen at once, which no amount of domain testing can answer.
 */
@RunWith(AndroidJUnit4::class)
class SetupWizardIssuesInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(
            GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK),
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** Opens the wizard on the plain status kind and walks to the Target step. */
    private fun openTargetStep(settings: GlobalSettings? = null) {
        if (settings != null) NightbellTestSupport.resetApp(settings)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Status check").performScrollTo().performClick()
        composeRule.waitForIdle()
        continueStep()
    }

    private fun continueStep() {
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    private fun typeUrl(text: String) {
        composeRule.onNodeWithContentDescription("URL").performScrollTo().performTextInput(text)
        composeRule.waitForIdle()
    }

    /** Takes focus off the URL field the way tapping the next field does. */
    private fun leaveTheField() {
        composeRule.onNodeWithContentDescription("Name").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    // ---- 1. the scheme -------------------------------------------------------

    /**
     * The report: the URL field should default to https unless http is typed.
     *
     * Two halves, and the second is the one that used to be broken in a way a
     * screenshot could not show. Nothing goes red while the host is being typed,
     * because the form judges the URL the draft is about to have.
     */
    @Test
    fun aBareHostBecomesHttpsWhenTheFieldIsLeft() {
        openTargetStep()
        typeUrl("status.example.com")

        // Mid-typing, before any completion: no error, and the wizard will let
        // the user move on. This is what used to block with "Start with http://".
        composeRule.onAllNodesWithText("Start with http", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Continue").assertIsEnabled()

        leaveTheField()
        composeRule.onNodeWithContentDescription("URL")
            .assertTextContains("https://status.example.com")
        // And it says it did it, rather than silently disagreeing with the user.
        composeRule.onNodeWithText("Added https://", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("102-url-scheme-completed")
    }

    /** The one exception the report asked for by name. */
    @Test
    fun anExplicitHttpUrlIsLeftAlone() {
        openTargetStep()
        typeUrl("http://10.0.0.4:8080/health")
        leaveTheField()

        composeRule.onNodeWithContentDescription("URL")
            .assertTextContains("http://10.0.0.4:8080/health")
        composeRule.onAllNodesWithText("Added https://", substring = true).assertCountEquals(0)
        // The plain-http warning is still on, because that part was never wrong.
        composeRule.onNodeWithText("Plain http", substring = true).assertIsDisplayed()
    }

    // ---- 2. one routing control ---------------------------------------------

    private fun routingToggles() =
        composeRule.onAllNodesWithText("Route through SOCKS5")

    private fun scrollFormToBottom() {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
    }

    /**
     * The report: the SOCKS5 toggle shows up on every screen of monitor creation.
     *
     * It did, on all three steps past the kind picker, because it has to be
     * visible wherever Test is and Test is offered from step 1 onwards. This is
     * the assertion that failed before the fix.
     */
    @Test
    fun theRoutingSwitchExistsOnceAndTheLaterStepsCarryAReadout() {
        openTargetStep()
        typeUrl("status.example.com")
        leaveTheField()

        scrollFormToBottom()
        routingToggles().assertCountEquals(1)
        composeRule.captureScreenshot("103-setup-target-routing")

        continueStep()
        scrollFormToBottom()
        routingToggles().assertCountEquals(0)
        composeRule.onNodeWithTag("route-summary").assertIsDisplayed()
        composeRule.onNodeWithText("Direct", substring = true).assertIsDisplayed()

        continueStep()
        scrollFormToBottom()
        routingToggles().assertCountEquals(0)
        composeRule.onNodeWithTag("route-summary").assertIsDisplayed()
        composeRule.captureScreenshot("104-setup-cadence-route-summary")
    }

    /**
     * The readout is not a dead end. Rule six: every setting a user can create has
     * a route back to it, and the summary's whole job is being that route.
     */
    @Test
    fun theReadoutWalksBackToTheOneSwitch() {
        openTargetStep()
        typeUrl("status.example.com")
        leaveTheField()
        continueStep()
        continueStep()

        scrollFormToBottom()
        composeRule.onNodeWithTag("route-summary").performClick()
        composeRule.waitForIdle()

        // Back on Target, at the control, not at the top of the form.
        composeRule.onNodeWithText("Route through SOCKS5").assertIsDisplayed()
        composeRule.captureScreenshot("105-setup-routing-reached-from-summary")
    }

    /** What the readout actually reads, once there is a route to report. */
    @Test
    fun theReadoutNamesTheEndpointItWouldUse() {
        openTargetStep(
            GlobalSettings(
                motionIntensity = 0f,
                theme = ThemeChoice.DARK,
                socksProxyEnabled = true,
            ),
        )
        typeUrl("status.example.com")
        leaveTheField()

        scrollFormToBottom()
        composeRule.onNodeWithText("Route through SOCKS5").performClick()
        composeRule.waitForIdle()

        continueStep()
        scrollFormToBottom()
        // The shared address from Settings, which is what a monitor with no
        // address of its own inherits.
        composeRule.onNodeWithText("127.0.0.1:9050", substring = true).assertIsDisplayed()
    }
}
