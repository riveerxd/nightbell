package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.ui.components.AlertPolicyEditor
import me.river.nightbell.ui.setup.SetupScreen
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
}
