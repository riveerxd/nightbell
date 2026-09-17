package me.river.nightbell

import android.Manifest
import android.app.UiAutomation
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing into the cadence numbers instead of tapping at them.
 *
 * Reported as issue #15: the minus and plus buttons were the only way to move
 * "Check every" and "Timeout", so every value was a count of taps away from the
 * one before it. What is asserted here is the part a person does: tap the
 * number, type a value, and have the monitor that gets saved hold it.
 *
 * The clamp is covered too, because a field with a range has to say what it
 * will do with 5000 before it does it, and silently rewriting the number
 * somebody typed is worse than the taps were.
 *
 * The readout is read through its state description rather than through its
 * text, because the chip row under "Check every" carries the same strings and
 * an assertion that matches either one would pass with the stepper broken.
 */
@RunWith(AndroidJUnit4::class)
class StepperInputInstrumentedTest {

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

    /** Walks the wizard to step 4, which is where both numbers live. */
    private fun openCadenceStep() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Request & response").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Name").performTextReplacement("Orders service")
        composeRule.onNodeWithContentDescription("URL")
            .performTextReplacement("https://api.example.com/health")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    private fun openField(title: String) {
        composeRule.onNodeWithContentDescription("Set $title").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun typeInto(title: String, text: String) {
        composeRule.onNodeWithContentDescription("$title value").performTextReplacement(text)
        composeRule.waitForIdle()
    }

    private fun commit(title: String) {
        composeRule.onNodeWithContentDescription("$title value").performImeAction()
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun assertReadout(title: String, expected: String) {
        composeRule.onNodeWithContentDescription("Set $title").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected),
        )
    }

    @Test
    fun anExactIntervalCanBeTyped() {
        openCadenceStep()
        assertReadout("Check every", "15m")
        openField("Check every")
        typeInto("Check every", "45")
        composeRule.captureScreenshot("80-interval-being-typed")
        commit("Check every")

        assertReadout("Check every", "45m")
        composeRule.onNodeWithContentDescription("Set Check every").assertIsDisplayed()
        composeRule.captureScreenshot("81-interval-typed")
    }

    @Test
    fun anExactTimeoutCanBeTyped() {
        openCadenceStep()
        openField("Timeout")
        typeInto("Timeout", "90")
        commit("Timeout")

        assertReadout("Timeout", "90s")
        composeRule.captureScreenshot("82-timeout-typed")
    }

    /**
     * Above the top of the range the line under the row names the end it will
     * land on, and it lands there.
     */
    @Test
    fun aValueAboveTheRangeIsNamedAndThenClamped() {
        openCadenceStep()
        openField("Check every")
        typeInto("Check every", "5000")

        composeRule.onNodeWithText("Highest is 1440m").assertIsDisplayed()
        composeRule.captureScreenshot("83-interval-above-range")

        commit("Check every")
        assertReadout("Check every", "1440m")
    }

    @Test
    fun aValueBelowTheRangeIsNamedAndThenClamped() {
        openCadenceStep()
        openField("Timeout")
        typeInto("Timeout", "0")

        composeRule.onNodeWithText("Lowest is 1s").assertIsDisplayed()
        composeRule.captureScreenshot("84-timeout-below-range")

        commit("Timeout")
        assertReadout("Timeout", "1s")
    }

    /** Clearing the field and leaving is not a request for zero. */
    @Test
    fun anEmptyFieldLeavesTheValueAlone() {
        openCadenceStep()
        openField("Check every")
        composeRule.onNodeWithContentDescription("Check every value").performTextClearance()
        composeRule.waitForIdle()
        commit("Check every")

        assertReadout("Check every", "15m")
    }

    /** The buttons still step, including out of a field somebody is typing in. */
    @Test
    fun theButtonsStillStep() {
        openCadenceStep()
        composeRule.onNodeWithContentDescription("Increase Timeout").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertReadout("Timeout", "16s")

        openField("Timeout")
        typeInto("Timeout", "40")
        composeRule.onNodeWithContentDescription("Decrease Timeout").performClick()
        composeRule.waitForIdle()
        assertReadout("Timeout", "39s")
    }

    /**
     * Typing and then going straight for the button, which is what people do.
     *
     * Nothing about tapping "Create monitor" clears focus from a field by
     * itself, so this is the path where a typed value can be read, agreed with,
     * and then quietly dropped on the way out.
     */
    @Test
    fun aTypedValueSurvivesTappingCreateWithTheKeyboardStillUp() {
        openCadenceStep()
        openField("Check every")
        typeInto("Check every", "45")
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the monitor to be stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
        assertEquals(45, monitor.intervalMinutes)
    }

    /**
     * Turning the phone while the field is open.
     *
     * The wizard handles orientation itself rather than being recreated, so the
     * open editor and the number in it should both still be there afterwards.
     * Worth asserting rather than reasoning about: this is the state the app has
     * historically broken in.
     */
    @Test
    fun theOpenFieldSurvivesRotation() {
        openCadenceStep()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            composeRule.waitForIdle()
            // The landscape step with nothing open, for the comparison. On a
            // 540dp-tall phone this screen is a strip under a floating footer
            // before any keyboard appears, and that is not something the field
            // introduced.
            composeRule.captureScreenshot("90-landscape-cadence")

            openField("Check every")
            typeInto("Check every", "45")
            composeRule.onNodeWithContentDescription("Check every value")
                .assertTextEquals("45")
            composeRule.captureScreenshot("91-landscape-typing")
            commit("Check every")
            assertReadout("Check every", "45m")
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            composeRule.waitForIdle()
        }
    }

    /** The typed numbers have to reach the monitor that gets written. */
    @Test
    fun typedNumbersReachTheSavedMonitor() {
        openCadenceStep()
        openField("Check every")
        typeInto("Check every", "45")
        commit("Check every")
        openField("Timeout")
        typeInto("Timeout", "90")
        commit("Timeout")
        composeRule.captureScreenshot("85-cadence-both-typed")

        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the monitor to be stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
        assertEquals(45, monitor.intervalMinutes)
        assertEquals(90, monitor.timeoutSeconds)
    }
}
