package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.down
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.components.ToastHost
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard on deleting things, and the way back from having deleted them.
 *
 * There is no "are you sure?" anywhere in this app any more. A destructive button
 * has to be held, which an accident cannot do, and what it reports carries an undo,
 * which covers changing your mind. The two halves are tested separately because
 * they fail separately: a hold that commits on a tap is a data loss bug, and an
 * undo that puts a monitor back at the wrong place with an empty history is a
 * different one that still reads as success.
 *
 * The clock is held for the hold tests. `HoldToConfirmButton` times itself off
 * `withFrameNanos` rather than off an animation, deliberately, so that turning
 * platform animations off cannot shorten the guard to nothing. That also means the
 * only way to drive it from a test is to advance the frame clock by hand between a
 * synthetic press and release.
 */
@RunWith(AndroidJUnit4::class)
class DestructiveActionsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var message by mutableStateOf<ToastMessage?>(null)

    private val store get() = Nightbell.install(appContext).store

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        message = null
        val now = System.currentTimeMillis()
        runBlocking {
            listOf("Checkout API", "Billing API", "Docs site").forEachIndexed { index, name ->
                val monitor = Monitor(
                    id = "m$index",
                    name = name,
                    kind = MonitorKind.HTTP_STATUS,
                    url = "http://127.0.0.1:${9000 + index}/health",
                    intervalMinutes = 15,
                )
                store.upsert(monitor)
                store.updateRuntime(monitor.id) {
                    it.copy(
                        health = Health.UP,
                        lastCheckedAt = now,
                        lastLatencyMs = (300 + index).toLong(),
                        lastCode = 200,
                        samples = List(4) { s ->
                            Sample(at = now - s * 60_000L, ok = true, latencyMs = 300L, code = 200)
                        },
                    )
                }
            }
            // The middle one is in a group, so an undo has somewhere specific to
            // put it back rather than merely somewhere.
            // Expanded, because a group is drawn shut by default and a member of a
            // collapsed one is not on screen to long-press.
            store.upsertGroup(
                MonitorGroup(
                    id = "g1",
                    title = "Payments",
                    memberIds = listOf("m1"),
                    collapsed = false,
                ),
            )
        }
    }

    private fun dashboard() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                AuroraBackground(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        onAddMonitor = {},
                        onOpenMonitor = {},
                        onOpenSettings = {},
                        onToast = { message = it },
                    )
                    ToastHost(
                        message = message,
                        onDismissed = { message = null },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Checkout API").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun monitorCount() = runBlocking { store.currentSnapshot().monitors.size }

    private fun selectFirst() {
        composeRule.onNodeWithText("Checkout API").performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    /** Presses the hold button, waits [heldMs] of real clock, and releases. */
    private fun hold(label: String, heldMs: Long) {
        composeRule.mainClock.autoAdvance = false
        val node = composeRule.onNodeWithText(label, substring = true)
        node.performTouchInput { down(center) }
        // One frame so the fill's first `withFrameNanos` returns and it has a start.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(heldMs)
        composeRule.mainClock.advanceTimeByFrame()
        node.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun theSelectionBarOffersOneStateButtonRatherThanPauseAndResumeTogether() {
        dashboard()
        selectFirst()
        composeRule.captureScreenshot("destructive-01-selection-bar")

        // Everything selected is running, so the bar says Pause and there is no
        // Resume anywhere on it. Offering both was the original complaint: the
        // first control on the bar was the one that could not do anything.
        assertTrue(
            "a running selection should be offered Pause",
            composeRule.onAllNodesWithText("Pause", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals(
            "a running selection must not be offered Resume",
            0,
            composeRule.onAllNodesWithText("Resume", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aTapOnTheDeleteButtonDeletesNothing() {
        dashboard()
        selectFirst()
        // A tap, not a hold. This is the whole reason the control exists, and it
        // is the assertion that would catch the guard being wired to onClick.
        hold("Hold to delete", heldMs = 60)
        assertEquals("a tap must not delete", 3, monitorCount())
        assertTrue("nothing should have been reported", message == null)
    }

    @Test
    fun holdingTheDeleteButtonDeletesAndOffersAnUndo() {
        dashboard()
        selectFirst()
        hold("Hold to delete", heldMs = 1_500)

        awaitTrue(description = "the monitor to be deleted") { monitorCount() == 2 }
        // By text, not by content description: the capsule's description is the
        // spoken role plus the sentence, and the action is its own node beside it.
        // That separation is the point, so the assertion respects it.
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.captureScreenshot("destructive-02-deleted-with-undo")
    }

    @Test
    fun undoPutsTheMonitorBackWhereItWasWithItsHistory() {
        dashboard()
        // The grouped one, so the restore has a position and a membership to get
        // right rather than just an existence.
        composeRule.onNodeWithText("Billing API").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val before = runBlocking { store.currentSnapshot() }
        val index = before.monitors.indexOfFirst { it.id == "m1" }
        val samples = before.runtimes["m1"]?.samples?.size ?: 0
        assertTrue("the fixture should have history to lose", samples > 0)

        hold("Hold to delete", heldMs = 1_500)
        awaitTrue(description = "the monitor to be deleted") { monitorCount() == 2 }

        composeRule.waitUntil(8_000) {
            composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Undo").performClick()
        awaitTrue(description = "the monitor to come back") { monitorCount() == 3 }

        val after = runBlocking { store.currentSnapshot() }
        assertEquals(
            "it should come back where it was, not at the end",
            index,
            after.monitors.indexOfFirst { it.id == "m1" },
        )
        assertEquals(
            "its history is the one thing here nobody can retype",
            samples,
            after.runtimes["m1"]?.samples?.size,
        )
        assertTrue(
            "and it belongs to its group again",
            after.groups.single { it.id == "g1" }.memberIds.contains("m1"),
        )
        composeRule.captureScreenshot("destructive-03-restored")
    }
}
