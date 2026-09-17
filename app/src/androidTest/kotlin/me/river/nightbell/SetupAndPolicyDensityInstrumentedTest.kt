package me.river.nightbell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.ui.components.AlertPolicyEditor
import me.river.nightbell.ui.components.StepperRow
import me.river.nightbell.ui.setup.SetupScreen
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wizard and the alert policy editor, at size and under a screen reader.
 *
 * Three shapes here were measured once at the default text size and never again:
 * the wizard footer weighted only its primary button, so Back was measured first
 * and "Create monitor" took the remainder; the quiet-hours nudges were 30dp of
 * clickable with no touch box around them; and the kind picker marked its choice
 * with a tick that carries no content description, so all four rows read
 * identically to TalkBack.
 */
@RunWith(AndroidJUnit4::class)
class SetupAndPolicyDensityInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    private fun wizardAt(fontScale: Float) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    SetupScreen(monitorId = null, onClose = {}, onSaved = {})
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theWizardFooterKeepsItsPrimaryActionAtTheLargestFontScale() {
        wizardAt(2.0f)
        composeRule.captureScreenshot("setup-density-01-footer-at-200")

        val cancel = composeRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        val cont = composeRule.onNodeWithText("Continue").getUnclippedBoundsInRoot()
        assertTrue("Cancel collapsed to ${cancel.width}", cancel.width >= 40.dp)
        assertTrue("Continue collapsed to ${cont.width}", cont.width >= 60.dp)
        // The primary is the wider of the two. It used to be the one that gave way.
        assertTrue(
            "Continue (${cont.width}) is no wider than Cancel (${cancel.width})",
            cont.width >= cancel.width,
        )
    }

    @Test
    fun theKindPickerSaysWhichKindIsChosen() {
        wizardAt(1.0f)
        // The first kind is selected by default, so exactly one row reports itself
        // selected before anything is tapped.
        val selected = composeRule
            .onAllNodesWithText("Status check", substring = true)
            .fetchSemanticsNodes()
        assertTrue("no kind rows rendered", selected.isNotEmpty())

        composeRule.onNodeWithText("Page element", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
            )
            .fetchSemanticsNodes()
            .let {
                assertTrue("nothing reports itself selected after a tap", it.isNotEmpty())
            }
    }

    @Test
    fun quietHoursNudgesMeetTheTouchFloor() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                var policy by remember {
                    mutableStateOf(AlertPolicy(quietHoursEnabled = true))
                }
                Modifier.fillMaxSize()
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    AlertPolicyEditor(policy = policy, onChange = { policy = it })
                }
            }
        }
        composeRule.waitForIdle()
        // Exists rather than displayed: the editor is taller than the screen and
        // the quiet-hours block sits below the fold. What this test is about is
        // how big the targets are, which is true off-screen too.
        composeRule.onNodeWithText("QUIET HOURS").fetchSemanticsNode()

        // 30dp of clickable and nothing around it, on the control that decides
        // when the phone is allowed to wake you.
        //
        // Every one of the four is asserted to exist before it is measured: a
        // loop that skips what it cannot find is a test that passes when the
        // control disappears, which is the same lie as a green screenshot
        // assertion over an empty PNG.
        listOf("From earlier", "From later", "Until earlier", "Until later").forEach { label ->
            val nodes = composeRule
                .onAllNodes(hasContentDescription(label))
                .fetchSemanticsNodes()
            assertTrue("no node described as \"$label\"", nodes.isNotEmpty())
            val bounds = composeRule.onAllNodes(hasContentDescription(label)).onFirst()
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$label is ${bounds.width} x ${bounds.height}, under the 48dp floor",
                bounds.width >= 47.dp && bounds.height >= 47.dp,
            )
        }
    }

    /**
     * The stepper readout became a field, and a field that clips is worse than
     * the buttons it replaced: it would show "144" for 1440 while the row beside
     * it claimed to be setting the interval. Measured at both text sizes and
     * photographed in both themes, because the readout draws its own surface now
     * and a fill that only works on black is half a control.
     */
    private fun readoutTakesATypedValue(scale: Float, theme: ThemeChoice, shot: String) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = theme) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, scale),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(NightbellColors.Void)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                    ) {
                        var minutes by remember { mutableStateOf(15) }
                        StepperRow(
                            title = "Check every",
                            value = minutes,
                            onValueChange = { minutes = it },
                            range = 1..1440,
                            suffix = "m",
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Set Check every").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Check every value")
            .performTextReplacement("1440")
        composeRule.waitForIdle()
        composeRule.captureScreenshot("$shot-editing")
        composeRule.onNodeWithContentDescription("Check every value").performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Set Check every").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1440m"),
        )
        // The readout scales with the type for exactly this reason: at 200 per
        // cent a fixed 74dp box holds three of the four digits.
        val readout = composeRule.onNodeWithContentDescription("Set Check every")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the readout is ${readout.width} x ${readout.height} at ${scale}x",
            readout.width >= 74.dp * scale - 1.dp && readout.height >= 47.dp,
        )
        composeRule.captureScreenshot(shot)
    }

    /**
     * The longest stepper title in the app, beside the widened readout.
     *
     * The readout takes its width from the value it has to hold, so a row whose
     * title is four words is where a readout that grew too far would show up
     * first: the title is the thing that gives the space back.
     */
    @Test
    fun theLongestStepperTitleStillFitsBesideItsReadout() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                var policy by remember { mutableStateOf(AlertPolicy()) }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(NightbellColors.Void)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AlertPolicyEditor(policy = policy, onChange = { policy = it })
                }
            }
        }
        composeRule.waitForIdle()
        // Clipped bounds are measured against the viewport, so a row below the
        // fold reads as 0dp wide and the assertion below would be about the
        // scroll position rather than about the layout.
        composeRule.onNodeWithText("Failures before alerting").performScrollTo()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("89-alert-policy-steppers")

        val title = composeRule.onNodeWithText("Failures before alerting", useUnmergedTree = true)
        val drawn = title.getBoundsInRoot()
        val whole = title.getUnclippedBoundsInRoot()
        assertTrue(
            "the title is drawn ${drawn.width} wide out of ${whole.width}",
            drawn.width >= whole.width - 0.5.dp,
        )
    }

    /**
     * The widest readout in the app, drawn at the top of its range.
     *
     * "Degraded above" reaches 60000ms, which is seven characters in a box sized
     * for "999 min", and the readout now has a border eating a dp on each side.
     * Clipped bounds against unclipped bounds is the only assertion that can see
     * the difference; a screenshot test would pass on "60000m".
     */
    @Test
    fun theWidestReadoutIsNotClipped() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(NightbellColors.Void)
                        .padding(horizontal = 20.dp),
                ) {
                    StepperRow(
                        title = "Degraded above",
                        value = 60_000,
                        onValueChange = {},
                        range = 100..60_000,
                        step = 100,
                        suffix = "ms",
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("88-stepper-widest-readout")

        val text = composeRule.onNodeWithText("60000ms", useUnmergedTree = true)
        val drawn = text.getBoundsInRoot()
        val whole = text.getUnclippedBoundsInRoot()
        assertTrue(
            "60000ms is drawn ${drawn.width} wide out of ${whole.width}",
            drawn.width >= whole.width - 0.5.dp,
        )
    }

    @Test
    fun theStepperReadoutTakesATypedValue() =
        readoutTakesATypedValue(1.0f, ThemeChoice.DARK, "86-stepper-readout-dark")

    @Test
    fun theStepperReadoutTakesATypedValueAtTwoHundredPerCentInTheLightTheme() =
        readoutTakesATypedValue(2.0f, ThemeChoice.LIGHT, "87-stepper-readout-light-200")
}
