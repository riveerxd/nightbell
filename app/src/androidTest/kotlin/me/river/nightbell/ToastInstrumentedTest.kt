package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.DashboardViewModel
import me.river.nightbell.ui.SettingsViewModel
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.components.ToastKind
import me.river.nightbell.ui.components.ToastHost
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The toast layer, driven by the taps that produce it.
 *
 * Three things are pinned here and all three were broken or absent before. A
 * failure has to arrive announcing itself as one, which is what the spoken role
 * in front of every message is for. Pausing has to read as a warning rather than
 * as a confirmation, because it is the app saying it will not page you. And the
 * thing has to leave, on its own and on a tap.
 *
 * The assertions read the role prefix rather than the colour, for the same reason
 * the prefix is in the content description at all: a kind carried only by hue is
 * not there for anyone using TalkBack, and it is not assertable either.
 *
 * **The clock is held for the whole class, and that is not optional.** A toast
 * takes itself down with `delay`, and under an auto-advancing test clock that
 * delay is virtual: the first idle wait after the tap runs the whole dwell to
 * completion, so the message is created and dismissed inside one pump and every
 * assertion sees an empty screen. That looked exactly like the semantics being
 * wrong for a good while. Stepping the clock by hand is also what makes the dwell
 * itself assertable.
 */
@RunWith(AndroidJUnit4::class)
class ToastInstrumentedTest {

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
            // Nothing listens here, so a hand-driven check finishes in
            // milliseconds and reaches nobody's server.
            url = "http://127.0.0.1:1/health",
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

    @After
    fun tearDown() {
        // Whatever a test did to connectivity, put it back: the emulator is
        // shared with every other class in the run.
        shell("svc wifi enable")
        shell("svc data enable")
    }

    /** ViewModels are built and driven on the main thread, as they would be. */
    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .close()
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
        composeRule.mainClock.autoAdvance = false
    }

    /**
     * Steps the clock a frame at a time until [condition] holds.
     *
     * One frame per 16ms of real time, so the two stay roughly in step. The work
     * behind a toast is a real DataStore write on a real dispatcher, and a
     * thousand instant frame advances would burn the whole dwell before that
     * write had landed.
     */
    private fun stepUntil(what: String, budgetFrames: Int = 240, condition: () -> Boolean) {
        repeat(budgetFrames) {
            if (condition()) return
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
        throw AssertionError("never happened within $budgetFrames frames: $what")
    }

    private fun toasts(role: String) =
        composeRule.onAllNodesWithContentDescription(role, substring = true)
            .fetchSemanticsNodes().size

    private fun awaitToast(role: String) = stepUntil("toast \"$role\"") { toasts(role) > 0 }

    /**
     * Screenshots the toast at rest, not on the frame it was first found.
     *
     * The semantics node exists as soon as the transition starts, and at that
     * point the surface is still fully transparent. The first version of this
     * captured there: every assertion passed and the PNG behind it was a
     * dashboard with nothing on it.
     */
    private fun captureAtRest(name: String) {
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.captureScreenshot(name)
    }

    @Test
    fun pausingAMonitorWarnsAndResumingConfirms() {
        dashboard()

        composeRule.onNodeWithContentDescription("Pause monitor").performClick()
        awaitToast("Warning: Monitor paused")
        captureAtRest("toast-live-1-paused-warning")

        // Straight back the other way. Also the case that proves the host
        // restarts its dwell rather than running out the first message's.
        composeRule.mainClock.advanceTimeBy(3_700)
        stepUntil("the resume button") {
            composeRule.onAllNodesWithContentDescription("Resume monitor")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Resume monitor").performClick()
        awaitToast("Done: Monitor resumed")
        captureAtRest("toast-live-2-resumed-success")
    }

    /**
     * The two failures, read off the view model rather than out of the UI.
     *
     * Not laziness. Every control that can fail for want of a network is greyed
     * out while there is no network, on purpose and as a shipped fix, so the
     * dashboard's own "check all now" reads "Waiting for a connection" and cannot
     * be tapped. The offline message is a backstop for the paths that are not
     * gated, and the only honest way to reach it is to call the thing the button
     * would have called. What it looks like when it arrives is
     * [ToastAppearanceInstrumentedTest]'s job.
     */
    @Test
    fun anUnreadableBackupIsReportedAsAnError() {
        val viewModel = onMain { SettingsViewModel(Nightbell.install(appContext)) }
        onMain { viewModel.importBackup { "this is not a backup" } }
        awaitTrue(10_000, "the import to answer") { viewModel.toast != null }
        assertEquals(ToastKind.ERROR, viewModel.toast?.kind)
    }

    @Test
    fun aCheckWithNoConnectivityIsAnError() {
        shell("svc wifi disable")
        shell("svc data disable")
        awaitTrue(20_000, "connectivity to actually drop") {
            !Nightbell.install(appContext).network.isOnline()
        }
        val viewModel = onMain { DashboardViewModel(Nightbell.install(appContext)) }
        onMain { viewModel.checkAll() }
        awaitTrue(10_000, "the check to answer") { viewModel.toast != null }
        assertEquals(ToastKind.ERROR, viewModel.toast?.kind)
    }

    @Test
    fun aWarningStaysUpLongerThanTheEnterAndThenLeaves() {
        dashboard()
        composeRule.onNodeWithContentDescription("Pause monitor").performClick()
        awaitToast("Warning: Monitor paused")

        // Still up well past the longest enter transition. A message that leaves
        // while the animation which brought it in is still running is not a
        // message, and this is the assertion that catches a mistyped dwell.
        composeRule.mainClock.advanceTimeBy(1_500)
        assertEquals(
            "a warning should still be up 1.5s in",
            1,
            toasts("Warning: Monitor paused"),
        )

        // 3.6s of dwell plus the exit.
        composeRule.mainClock.advanceTimeBy(2_600)
        stepUntil("the toast to leave", budgetFrames = 60) {
            toasts("Warning: Monitor paused") == 0
        }
    }

    @Test
    fun aToastLeavesOnATap() {
        dashboard()
        composeRule.onNodeWithContentDescription("Pause monitor").performClick()
        awaitToast("Warning: Monitor paused")

        composeRule.onNodeWithContentDescription("Warning: Monitor paused", substring = true)
            .performClick()
        // Only the exit to wait out. Deliberately short: with the dwell at 3.6s,
        // a generous budget here would pass whether the tap worked or not.
        composeRule.mainClock.advanceTimeBy(400)
        stepUntil("the tap to take it down", budgetFrames = 20) {
            toasts("Warning: Monitor paused") == 0
        }
    }
}
