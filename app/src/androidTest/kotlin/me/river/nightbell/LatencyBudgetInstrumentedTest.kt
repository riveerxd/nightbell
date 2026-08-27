package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The latency budget line on the response-time chart, issue 5.
 *
 * On a device rather than the JVM because the arithmetic already has
 * [LatencyChartTest] and what is left is the part only a real composition can
 * answer: that the budget reaches the chart at all. It arrives from
 * `Monitor.sloMs(settings)`, which needs `GlobalSettings` at a call site that had
 * none before this, so the failure mode being guarded against is a chart that
 * draws perfectly and is simply never told what the budget is.
 *
 * Asserted through the chart's own `contentDescription` and the legend's text.
 * That is not a proxy for the drawing, it is the same numbers the drawing uses,
 * and it is the half of the feature a screen reader gets. The drawing itself is
 * reviewed from the PNGs this writes.
 */
@RunWith(AndroidJUnit4::class)
class LatencyBudgetInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val now = System.currentTimeMillis()

    private fun samples(vararg latencies: Long): List<Sample> =
        latencies.mapIndexed { index, latency ->
            Sample(
                at = now - (latencies.size - index) * 15 * 60_000L,
                ok = latency > 0,
                latencyMs = if (latency > 0) latency else 0,
                code = if (latency > 0) 200 else 0,
                note = if (latency > 0) "" else "Connection refused or dropped",
            )
        }

    /**
     * Four monitors, one per state the feature has: a budget sitting inside the
     * plotted range with some checks over it, a budget nothing is near, no budget
     * at all, and a budget inherited from Settings rather than set on the monitor.
     *
     * [defaultSloMs] is a parameter because "no budget" is not a property of a
     * monitor. A monitor storing 0 inherits [GlobalSettings.defaultLatencySloMs],
     * which ships as 2500, so the only way to reach a chart with no budget line at
     * all is for Settings to hold no budget either. Writing this test as though
     * `latencySloMs = 0` were enough is how the first version of it failed, and
     * the failure was correct.
     */
    private fun seed(defaultSloMs: Int = 2_500) {
        val monitors = listOf(
            monitor("budget-breached", "Monero RPC", sloMs = 5_300),
            monitor("budget-capped", "LAN printer", sloMs = 30_000),
            monitor("budget-none", "Marketing site", sloMs = 0),
            monitor("budget-inherited", "Asset CDN", sloMs = 0),
        )
        val runtimes = mapOf(
            // The reporter's own numbers, from the screenshot on issue 5: a 5300ms
            // budget under a p95 of 5.68s. Three of these eight are over it.
            "budget-breached" to runtime(
                samples(3_770, 4_250, 5_680, 2_900, 5_400, 3_100, 6_020, 4_100),
            ),
            "budget-capped" to runtime(samples(180, 210, 195, 205, 188, 214, 199, 202)),
            "budget-none" to runtime(samples(320, 410, 355, 380, 340, 395, 362, 371)),
            // Two failures in here as well, so the count can be checked against
            // something that must not be folded into it.
            "budget-inherited" to runtime(
                samples(1_400, 2_900, -1, 3_400, 1_800, -1, 2_100, 2_600),
            ),
        )
        runBlocking {
            Nightbell.install(NightbellTestSupport.appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = runtimes,
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = ThemeChoice.DARK,
                        hasSeenPagerSetup = true,
                        defaultLatencySloMs = defaultSloMs,
                    ),
                ),
            )
        }
    }

    private fun monitor(id: String, name: String, sloMs: Int) = Monitor(
        id = id,
        name = name,
        kind = MonitorKind.HTTP_STATUS,
        url = "https://example.com/$id",
        intervalMinutes = 15,
        timeoutSeconds = 60,
        latencySloMs = sloMs,
        createdAt = now,
    )

    private fun runtime(history: List<Sample>) = MonitorRuntime(
        health = Health.UP,
        lastCheckedAt = history.last().at,
        lastLatencyMs = history.last().latencyMs,
        lastCode = 200,
        consecutiveSuccesses = history.count { it.ok },
        samples = history,
    )

    private fun openDetail(name: String) {
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(hasText(name))
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    private fun backToDashboard() {
        composeRule.onNodeWithTag("detail-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard-list").performScrollToIndex(0)
        composeRule.waitForIdle()
    }

    /** The chart's spoken description, which is where the budget clause lands. */
    private fun assertChartSays(vararg fragments: String) {
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Response time history", substring = true))
        fragments.forEach { fragment ->
            composeRule.onNode(
                hasContentDescription(fragment, substring = true),
            ).assertExists()
        }
    }

    @Test
    fun aBudgetInsideTheChartIsDrawnWithItsBreachesCounted() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        openDetail("Monero RPC")

        // 5.30 s budget, and 5680 / 5400 / 6020 are the three over it.
        assertChartSays("budget 5.30 s", "3 over budget")
        composeRule.onNodeWithText("Budget 5.30 s · 3 of 8 over").assertExists()
        composeRule.captureScreenshot("50-budget-inside-range")
    }

    @Test
    fun aBudgetNothingIsNearSaysSoRatherThanFlatteningTheBars() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        openDetail("LAN printer")

        // 30s over 200ms responses. The line cannot be placed honestly, so the
        // legend says the budget is off the top instead of pretending otherwise.
        assertChartSays("budget 30.00 s", "none over budget")
        composeRule.onNodeWithText(
            "Budget 30.00 s, above this range · all 8 inside it",
        ).assertExists()
        composeRule.captureScreenshot("51-budget-above-range")
    }

    @Test
    fun noBudgetLeavesTheChartExactlyAsItWas() {
        // Settings holding no budget either, which is the only way a monitor ends
        // up with none. See seed().
        seed(defaultSloMs = 0)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        openDetail("Marketing site")

        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Response time history", substring = true))
        // No budget clause at all, not "budget 0". A monitor with neither its own
        // budget nor a global one must be indistinguishable from before this
        // feature existed.
        composeRule.onNode(hasContentDescription("budget", substring = true))
            .assertDoesNotExist()
        composeRule.onNode(hasText("Budget", substring = true)).assertDoesNotExist()
        composeRule.captureScreenshot("52-budget-absent")
    }

    @Test
    fun aMonitorWithNoBudgetOfItsOwnDrawsTheGlobalOne() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        openDetail("Asset CDN")

        // latencySloMs is 0 on this monitor, so 2500 has to arrive from
        // GlobalSettings. This is the assertion the whole plumbing change exists
        // for. 2900 / 3400 / 2600 are over it; the two failures are not counted,
        // because a failed check is a failure and not a slow success.
        assertChartSays("budget 2.50 s", "3 over budget", "2 failed")
        composeRule.onNodeWithText("Budget 2.50 s · 3 of 8 over").assertExists()
        composeRule.captureScreenshot("53-budget-inherited-from-settings")
    }

    /**
     * All four in one pass, so the PNGs can be compared side by side without four
     * app launches. Kept separate from the assertions above: a screenshot run that
     * fails halfway through should not be able to take an assertion with it.
     */
    @Test
    fun capturesEveryBudgetState() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("54-dashboard-with-budgets")

        listOf(
            "Monero RPC" to "55-inside-range",
            "LAN printer" to "56-above-range",
            "Marketing site" to "57-no-budget",
            "Asset CDN" to "58-inherited",
        ).forEach { (name, shot) ->
            openDetail(name)
            composeRule.onNodeWithTag("detail-list")
                .performScrollToNode(hasContentDescription("Response time history", substring = true))
            composeRule.captureScreenshot(shot)
            backToDashboard()
        }
    }
}
