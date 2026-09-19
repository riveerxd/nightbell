package me.river.nightbell

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rearranging with no mode in front of it. Issue #16, third report.
 *
 * The report was that the reorder grips never went away. Two fixes were tried:
 * first a mode with an explicit way in and out, then this, which is a hold on a
 * card and nothing else. There is no grip, no "Arrange monitors" button and no
 * bar, so the only state involved is whether the list is narrowed.
 *
 * What is asserted here is the part that makes a modeless drag honest: moving a
 * monitor sets the sort. Without that the dashboard would stay ranked worst
 * first, and the next completed check would put the dragged monitor back where it
 * was.
 *
 * The drag gesture itself is driven in `RevisionVerificationTest`, which has the
 * activity and the seeded fleet for injecting touches. This class covers the
 * rules around it.
 */
@RunWith(AndroidJUnit4::class)
class DashboardArrangeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    private fun seed(vararg monitors: Monitor) {
        runBlocking {
            val store = Nightbell.install(appContext).store
            monitors.forEach { monitor ->
                store.upsert(monitor)
                store.updateRuntime(monitor.id) { it.copy(health = Health.UP) }
            }
        }
    }

    private fun monitor(id: String, name: String) = Monitor(
        id = id,
        name = name,
        kind = MonitorKind.HTTP_STATUS,
        url = "https://$id.example.com",
    )

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
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun storedSort() = runBlocking {
        Nightbell.install(appContext).store.currentSnapshot().settings.dashboardSort
    }

    /**
     * The cards a hold would pick up.
     *
     * There is no grip to count, so the marker is the card's own description: it
     * offers "hold to move or select" while dragging is available and only "open
     * details" when it is not, which is both what a screen reader says and what a
     * test can see.
     */
    private fun draggableCards() =
        composeRule.onAllNodesWithContentDescription("hold to move or select", substring = true)

    /**
     * Nothing has to be chosen first. This is the regression: the grips used to
     * require picking "My order" out of a panel, and then never went away again.
     */
    @Test
    fun cardsAreDraggableOnAPlainDashboard() {
        seed(monitor("a", "Alpha"), monitor("b", "Bravo"), monitor("c", "Charlie"))
        openDashboard()

        assertEquals(MonitorQuery.Sort.WORST_FIRST, storedSort())
        draggableCards().assertCountEquals(3)
        // And nothing on screen is a mode: no bar, no button in the panel.
        composeRule.onAllNodesWithText("Arranging").assertCountEquals(0)
        composeRule.onAllNodesWithText("Arrange monitors").assertCountEquals(0)

        // The gesture is told about under the default sort, not only once the
        // user is already in the sort that dragging produces. Without this the
        // only explanation of how to rearrange sat behind having rearranged.
        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hold any card", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Monitors stay where you put them", substring = true)
            .assertCountEquals(0)
    }

    /**
     * Moving a monitor is what puts the dashboard in manual sort.
     *
     * Driven through the accessibility action rather than a synthesised drag,
     * because the point being asserted is the consequence rather than the
     * gesture, and both paths go through the same `writeOrder`.
     */
    @Test
    fun movingAMonitorSwitchesTheSortToMyOrder() {
        seed(monitor("a", "Alpha"), monitor("b", "Bravo"))
        openDashboard()
        assertEquals(MonitorQuery.Sort.WORST_FIRST, storedSort())

        val moveUp = composeRule.onNode(
            hasContentDescription("Bravo", substring = true) and
                hasContentDescription("hold to move or select", substring = true),
        ).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == "Move up" }
        composeRule.runOnUiThread { moveUp.action() }
        composeRule.waitForIdle()

        composeRule.waitUntil(10_000) { storedSort() == MonitorQuery.Sort.MANUAL }
        val order = runBlocking {
            Nightbell.install(appContext).store.currentSnapshot().monitors.map { it.id }
        }
        assertEquals(listOf("b", "a"), order)

        // And the panel says what happened, on a chip nobody tapped: the manual
        // sort is selected, and only now does it promise to keep the order.
        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Monitors stay where you put them", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Narrowing the list stops the dragging, because a card's place in a filtered
     * view says nothing about where it belongs among the monitors it is hiding. A
     * hold goes back to meaning "select".
     */
    @Test
    fun filteringTakesTheGestureAway() {
        seed(monitor("a", "Alpha"), monitor("b", "Bravo"))
        openDashboard()
        draggableCards().assertCountEquals(2)

        composeRule.onNodeWithContentDescription("Filter and sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Problems").performClick()
        composeRule.waitForIdle()

        draggableCards().assertCountEquals(0)
        composeRule.onNodeWithText("Dragging is off", substring = true).assertIsDisplayed()
    }
}
