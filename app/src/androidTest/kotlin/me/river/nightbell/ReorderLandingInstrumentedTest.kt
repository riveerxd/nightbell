package me.river.nightbell

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.ui.DashboardViewModel
import me.river.nightbell.ui.detail.DetailScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two things that happened on the way out of an action, reported from a phone.
 *
 * Both are one frame wide and both were invisible to every assertion in the
 * suite, because the suite waits for idle and then looks. What the reporter saw
 * was the state in between, which is the state a person actually watches.
 */
@RunWith(AndroidJUnit4::class)
class ReorderLandingInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    private fun monitor(id: String, name: String) = Monitor(
        id = id,
        name = name,
        kind = MonitorKind.HTTP_STATUS,
        url = "https://$id.example.com",
    )

    private fun seed(vararg pairs: Pair<Monitor, Health>) {
        runBlocking {
            val store = Nightbell.install(appContext).store
            pairs.forEach { (monitor, health) ->
                store.upsert(monitor)
                store.updateRuntime(monitor.id) { it.copy(health = health) }
            }
        }
    }

    // ---- 1. the card that landed twice --------------------------------------

    /**
     * A moved card lands once.
     *
     * The drop used to hand the list straight back to `MonitorQuery.apply` while
     * the store still held the old order and the sort still ranked by severity,
     * so every card was drawn home again and only reached the dropped position
     * once the write arrived a frame or two later. From a phone that reads as the
     * swap animation playing twice, which is how it was reported.
     *
     * Asserted against the view model rather than through the UI, and that is the
     * difference between a test and a shape of a test. The first version of this
     * drove the accessibility action and advanced two frames with the clock
     * stopped, and it passed against the unfixed code: `viewModelScope` is not
     * gated by that clock, so the write and the sort switch had both landed
     * before the assertion looked. What follows has no timing in it at all.
     * `commitReorder` returns without suspending, so `visible` immediately
     * afterwards is exactly the list the next frame will draw.
     */
    @Test
    fun aMovedCardDoesNotSnapBackBeforeItLands() {
        // Healths chosen so worst-first and the stored order disagree: Alpha is
        // stored first but ranks last, so a list that reverts to the computed
        // sort for even a frame is visibly a different list.
        seed(
            monitor("a", "Alpha") to Health.UP,
            monitor("b", "Bravo") to Health.DOWN,
            monitor("c", "Charlie") to Health.DEGRADED,
        )
        val viewModel = DashboardViewModel(Nightbell.install(appContext))
        // `cards` is a `WhileSubscribed` StateFlow, so without a collector it sits
        // at its empty initial value and every assertion below would be made
        // against a dashboard of nothing. The screen provides this subscriber in
        // the app; here the test has to be it.
        val subscriber = CoroutineScope(Dispatchers.Default)
        subscriber.launch { viewModel.cards.collect { } }
        try {
            NightbellTestSupport.awaitTrue(description = "cards loaded") {
                viewModel.visible.size == 3
            }

            // Worst first: Bravo, Charlie, Alpha.
            assertEquals(listOf("b", "c", "a"), viewModel.visible.map { it.monitor.id })

            viewModel.beginReorder()
            viewModel.moveInReorder("a", "c")
            val dropped = viewModel.visible.map { it.monitor.id }
            assertEquals("the drag preview itself is wrong", listOf("b", "a", "c"), dropped)

            org.junit.Assert.assertTrue("the drop should have counted as a move", viewModel.commitReorder())

            // The assertion the bug fails. Nothing has been awaited, so this is the
            // list as it stands for the frames between the drop and the write.
            assertEquals(
                "the card snapped back to its ranked position before landing",
                dropped,
                viewModel.visible.map { it.monitor.id },
            )

            // And it stays there once the store and the sort have caught up, rather
            // than the preview merely papering over a write that never happened.
            NightbellTestSupport.awaitTrue(description = "order and sort persisted") {
                runBlocking {
                    val snapshot = Nightbell.install(appContext).store.currentSnapshot()
                    snapshot.monitors.map { it.id } == dropped &&
                        snapshot.settings.dashboardSort == MonitorQuery.Sort.MANUAL
                }
            }
            assertEquals(dropped, viewModel.visible.map { it.monitor.id })
        } finally {
            subscriber.cancel()
        }
    }

    // ---- 2. the warning on the way out --------------------------------------

    /**
     * Deleting a monitor from its own screen shows no warning on the way out.
     *
     * `delete` writes to the store first and pops the back stack afterwards, so
     * the card flow hands back null in between and this screen drew "Monitor not
     * found" at full size, in amber, every time a delete worked. The reporter
     * sent a screenshot of it.
     *
     * `onBack` does nothing here on purpose. In the app the pop hides the
     * mistake after a frame or two; holding the screen open is what makes the
     * frames in between assertable at all.
     */
    @Test
    fun deletingFromTheMonitorScreenNeverShowsTheNotFoundWarning() {
        seed(monitor("a", "Alpha") to Health.UP)
        var popped = false
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                DetailScreen(
                    monitorId = "a",
                    onBack = { popped = true },
                    onEdit = {},
                    onToast = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()

        val delete = composeRule.onNodeWithText("Hold to delete", substring = true)
        // The hold times itself off `withFrameNanos`, so nothing advances it
        // unless this test does.
        composeRule.mainClock.autoAdvance = false
        delete.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.mainClock.advanceTimeByFrame()
        delete.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true

        NightbellTestSupport.awaitTrue(description = "monitor deleted") {
            runBlocking { Nightbell.install(appContext).store.currentSnapshot().monitors }.isEmpty()
        }
        composeRule.waitForIdle()

        // The store is empty and the screen is still mounted, which is precisely
        // the window the warning used to fill.
        composeRule.onAllNodesWithText("Monitor not found").assertCountEquals(0)
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        org.junit.Assert.assertTrue("delete should have asked to go back", popped)
        composeRule.captureScreenshot("111-deleted-without-the-warning")
    }

    /**
     * The warning is still there for the case it was written for: a monitor that
     * really did go away under this screen, from somewhere else.
     */
    @Test
    fun aMonitorDeletedElsewhereStillSaysSo() {
        seed(monitor("a", "Alpha") to Health.UP)
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                DetailScreen(monitorId = "a", onBack = {}, onEdit = {}, onToast = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()

        runBlocking { Nightbell.install(appContext).store.delete("a") }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Monitor not found").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Monitor not found").assertIsDisplayed()
    }
}
