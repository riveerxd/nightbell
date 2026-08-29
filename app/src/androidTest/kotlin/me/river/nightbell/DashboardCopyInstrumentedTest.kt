package me.river.nightbell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the dashboard says when it is not simply listing monitors.
 *
 * Each of these was a sentence that pointed at a control which was not there, or
 * two sentences naming one action differently.
 */
@RunWith(AndroidJUnit4::class)
class DashboardCopyInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    private fun seed(vararg monitors: Monitor, groups: List<MonitorGroup> = emptyList()) {
        runBlocking {
            val store = Nightbell.install(appContext).store
            monitors.forEach { monitor ->
                store.upsert(monitor)
                store.updateRuntime(monitor.id) { it.copy(health = Health.UP) }
            }
            groups.forEach { store.upsertGroup(it) }
        }
    }

    private fun openDashboard() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                DashboardScreen(
                    onAddMonitor = {},
                    onOpenMonitor = {},
                    onOpenSettings = {},
                    onToast = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun monitor(id: String, name: String) = Monitor(
        id = id,
        name = name,
        kind = MonitorKind.HTTP_STATUS,
        url = "https://$id.example.com",
    )

    /**
     * Manual sort alone does not put grips on the cards: `MonitorQuery.canReorder`
     * also wants the list un-narrowed. The panel used to promise the grip anyway,
     * which sent the user hunting a control the code had deliberately withheld.
     */
    @Test
    fun theManualSortHintSaysWhyTheGripsAreMissing() {
        seed(monitor("a", "Alpha"), monitor("b", "Bravo"))
        openDashboard()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("My order").performClick()
        composeRule.waitForIdle()

        // Nothing is narrowing yet, so the grips are real and the hint says so.
        composeRule.onNodeWithText("Drag the grip", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The grips stay hidden", substring = true).assertIsDisplayed()
    }

    /**
     * One action, one label. The empty state used to call it "Clear filters" while
     * the panel and the strip beside it both called it "Show all".
     */
    @Test
    fun everyRouteOutOfANarrowedListIsCalledTheSameThing() {
        seed(monitor("a", "Alpha"), monitor("b", "Bravo"))
        openDashboard()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        // Both monitors are up, so filtering to problems empties the list.
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close panel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nothing here").assertIsDisplayed()
        composeRule.onAllNodesWithText("Clear filters").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("the old second label is back", it.isEmpty())
        }
        composeRule.onAllNodesWithText("Show all").fetchSemanticsNodes().let {
            org.junit.Assert.assertTrue("nothing offers to show everything", it.isNotEmpty())
        }
    }

    /**
     * A group whose last member was deleted stays on the dashboard on purpose, so
     * it can be renamed or ungrouped. It used to say "Open it" to do that, and
     * opening it is the one gesture that leads to neither.
     */
    @Test
    fun anEmptiedGroupPointsAtThePencil() {
        seed(
            monitor("a", "Alpha"),
            groups = listOf(
                MonitorGroup(id = "g", title = "Storefront", memberIds = emptyList()),
            ),
        )
        openDashboard()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Storefront").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Tap the pencil", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit group Storefront").assertIsDisplayed()
    }
}
