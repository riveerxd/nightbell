package me.river.nightbell

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Monitor
import me.river.nightbell.widget.WidgetConfigActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget's own settings screen, driven the way the launcher opens it.
 *
 * The count of monitors a widget lists is a control someone has to understand from
 * looking at it, and the position that matters most is the one that is not a number:
 * "Auto" is the difference between a widget that fills the size it was dragged to and one
 * that draws five rows into a surface with room for twelve.
 */
@RunWith(AndroidJUnit4::class)
class WidgetConfigUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<WidgetConfigActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        val graph = Nightbell.install(NightbellTestSupport.appContext)
        runBlocking {
            (1..9).forEach { index ->
                graph.store.upsert(
                    Monitor(
                        id = "m$index",
                        name = "Monitor $index",
                        url = "https://m$index.example.com",
                    ),
                )
            }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun open() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            WidgetConfigActivity::class.java,
        ).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 41)
        scenario = ActivityScenario.launch(intent)
        composeRule.waitForIdle()
    }

    /** The list is lazy, so a control below the fold is not composed until it is scrolled to. */
    private fun scrollToMonitors() {
        composeRule.onNodeWithTag("widget-config-list").performScrollToNode(hasText("Monitors"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Monitors").assertIsDisplayed()
    }

    /** "Auto" appears twice on this screen: this is the stepper's readout, not the chip. */
    private fun stepperReads(text: String) {
        composeRule.onAllNodesWithText(text)
            .filterToOne(hasAnyAncestor(hasTestTag("widget-monitor-count")))
            .assertIsDisplayed()
    }

    @Test
    fun theMonitorCountStartsOnAutoAndSaysWhatThatMeans() {
        open()
        // The preview at the top of the screen, which has no widget size to plan against
        // and so shows a sample rather than a promise about how many rows appear.
        composeRule.captureScreenshot("widget-config-preview")
        scrollToMonitors()
        stepperReads("Auto")
        composeRule.onNodeWithText(
            "As many as the widget's size holds.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.captureScreenshot("widget-config-auto")
    }

    @Test
    fun theCountCanBePinnedToANumberAndHandedBack() {
        open()
        scrollToMonitors()
        composeRule.onNodeWithContentDescription("Increase Monitors").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("At most 1", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("widget-config-capped")

        composeRule.onNodeWithContentDescription("Decrease Monitors").performClick()
        composeRule.waitForIdle()
        stepperReads("Auto")
    }
}
