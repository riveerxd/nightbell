package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.components.ToastHost
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the capsule actually moves, and a filmstrip of it doing so.
 *
 * Written because it did not. The first build had zero travel and a 130ms fade,
 * which was reported from a phone as the thing having no animation at all, and it
 * was right: at that scale and that speed there is nothing for the eye to catch.
 * The second and worse half was that a message arriving while another was still up
 * did not transition even in principle, because `AnimatedVisibility` was keyed on
 * whether *any* message existed and that never went false between two taps.
 *
 * So the assertions are about displacement rather than about appearance. If the
 * capsule ever goes back to arriving already at rest, the numbers here fail, and no
 * screenshot could have told anyone.
 *
 * A recording would be the obvious way to show the motion and is the wrong tool:
 * the test clock is virtual, so anything filmed off it runs at whatever speed the
 * framework happens to pump. Stopping it and stepping it by hand gives the same
 * information at exact milliseconds, and gives it the same way twice.
 */
@RunWith(AndroidJUnit4::class)
class ToastMotionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var message by mutableStateOf<ToastMessage?>(null)

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "checkout",
            name = "Checkout API",
            kind = MonitorKind.HTTP_STATUS,
            url = "https://api.example.com/v1/health",
            intervalMinutes = 15,
        )
        runBlocking {
            val store = Nightbell.install(appContext).store
            store.upsert(monitor)
            store.updateRuntime(monitor.id) {
                it.copy(
                    health = Health.UP,
                    lastCheckedAt = now,
                    lastLatencyMs = 342,
                    lastCode = 200,
                    samples = listOf(Sample(at = now, ok = true, latencyMs = 342, code = 200)),
                )
            }
        }
        message = null
    }

    /**
     * Composes the dashboard, lets it finish arriving, then takes the clock away.
     *
     * The dashboard has an entrance stagger of its own. Freezing before that has
     * run leaves every card mid-fade, and a filmstrip of the toast against a
     * half-drawn screen says nothing about either.
     */
    private fun settle() {
        composeRule.setContent {
            // Zero intensity stops the aurora's infinite loop, which would keep
            // the frame clock busy and give `waitForIdle` nothing to wait for. It
            // does not touch what this class measures: `LocalNightbellMotion`
            // gates `rememberLoopingFloat` and nothing else, and the capsule's
            // transitions are Compose's own animation system.
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                AuroraBackground(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        onAddMonitor = {},
                        onOpenMonitor = {},
                        onOpenSettings = {},
                        onToast = {},
                    )
                    ToastHost(
                        message = message,
                        onDismissed = {},
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Checkout API").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun topOf(role: String) =
        composeRule.onNodeWithContentDescription(role, substring = true)
            .getUnclippedBoundsInRoot()
            .top

    /** Steps the clock to [atMs] since the transition started and photographs it. */
    private fun frameAt(name: String, atMs: Long, sinceMs: Long): Long {
        composeRule.mainClock.advanceTimeBy(atMs - sinceMs)
        composeRule.captureScreenshot(name)
        return atMs
    }

    @Test
    fun theCapsuleTravelsOnItsWayIn() {
        settle()
        message = ToastMessage.success("Imported 12 monitors")
        // One frame, so the transition has been handed its start value. Anything
        // less and there is nothing on screen to measure.
        composeRule.mainClock.advanceTimeByFrame()

        val start = topOf("Done: Imported 12 monitors")
        var at = 0L
        listOf(0L, 60L, 130L, 240L, 420L).forEach { t ->
            at = frameAt("motion-in-$t", t, at)
        }
        val rest = topOf("Done: Imported 12 monitors")

        // It comes down onto its resting position. 12dp of travel, so anything
        // over 4 proves the surface moved rather than arrived.
        val travelled = rest - start
        assertTrue(
            "the capsule should drop onto its resting position, moved ${travelled.value}dp",
            travelled > 4.dp,
        )
    }

    @Test
    fun theCapsuleTravelsOnItsWayOut() {
        settle()
        message = ToastMessage.error("Couldn't read that file")
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(700)
        val rest = topOf("Failed: Couldn't read that file")

        message = null
        composeRule.mainClock.advanceTimeByFrame()
        var at = 0L
        listOf(0L, 70L, 150L).forEach { t -> at = frameAt("motion-out-$t", t, at) }
        // Still in the tree while it leaves, and above where it sat: the exit
        // travels back the way it came.
        val leaving = topOf("Failed: Couldn't read that file")
        assertTrue(
            "the capsule should rise as it leaves, moved ${(rest - leaving).value}dp",
            rest - leaving > 2.dp,
        )
    }

    /**
     * The case that had no animation at all: a second message while the first is
     * still up.
     *
     * Pause a monitor and resume it. Two taps, and until this was keyed on the
     * message rather than on its existence the words changed under a surface that
     * never moved.
     */
    @Test
    fun oneMessageReplacingAnotherIsATransition() {
        settle()
        message = ToastMessage.warning("Monitor paused")
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.captureScreenshot("motion-swap-0-first-at-rest")
        val firstRest = topOf("Warning: Monitor paused")

        message = ToastMessage.success("Monitor resumed")
        composeRule.mainClock.advanceTimeByFrame()
        var at = 0L
        listOf(0L, 80L, 180L, 340L).forEach { t -> at = frameAt("motion-swap-$t", t, at) }

        // The incoming message has to have travelled too, not simply replaced the
        // outgoing one's text in place.
        composeRule.mainClock.advanceTimeBy(500)
        val secondRest = topOf("Done: Monitor resumed")
        assertTrue(
            "both messages should come to rest in the same place",
            (secondRest - firstRest).value.let { it > -2f && it < 2f },
        )
        // And the surface that arrived is a different height from nothing, which
        // is what makes the swap a transition rather than a text edit.
        assertTrue(
            "the replacement should be a real capsule",
            composeRule.onNodeWithContentDescription("Done: Monitor resumed", substring = true)
                .getUnclippedBoundsInRoot()
                .height > 20.dp,
        )
    }
}
