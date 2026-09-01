package me.river.nightbell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The uptime ring's label, on a real screen at a real density.
 *
 * Reported from a device: a monitor checked once rendered
 * "PAST UNDER A MINU…" over the top of the percentage. The string logic is
 * covered by `UptimeRingLabelTest`; this is the half that only a device can
 * answer, which is whether what it produces actually fits.
 */
@RunWith(AndroidJUnit4::class)
class UptimeRingLabelInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // Without this MainActivity asks for POST_NOTIFICATIONS on launch and the
    // system dialog sits over the app, so the rule finds no compose hierarchy.
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = NightbellTestSupport.resetApp()

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun aMonitorCheckedOnceLabelsTheRingWithoutOverflowing() {
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "ring",
            name = "example",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = "https://example.com",
        )
        runBlocking {
            val store = Nightbell.install(appContext).store
            store.upsert(monitor)
            store.updateRuntime(monitor.id) {
                it.copy(
                    health = Health.UP,
                    lastCheckedAt = now,
                    lastLatencyMs = 799,
                    samples = listOf(Sample(at = now, ok = true, latencyMs = 799, code = 200)),
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("example").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.captureScreenshot("ring-01-one-check")

        // The ring collapses its two Texts into one semantics node on purpose, so
        // TalkBack reads a sentence instead of spelling the label out. That node
        // is therefore where the label has to be asserted, and asserting it here
        // covers the spoken version too.
        //
        // One check, so the count is the honest label and the span is not.
        composeRule.onNodeWithContentDescription("100 percent, 1 check").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("under a minute", substring = true)
            .fetchSemanticsNodes()
            .let { org.junit.Assert.assertTrue("the overflowing label is back", it.isEmpty()) }
    }

    /**
     * A monitor that has never failed, on the screen it was reported from.
     *
     * The dial is 270 degrees of travel and a sweep gradient is anchored to the
     * canvas, so the brush that used to paint it dropped to 0.45 alpha at three
     * o'clock: 15 hours of nothing but 200s drew as a ring with a dark quarter on
     * its right side, and the number in the middle said 100%.
     * `UptimeRingFillInstrumentedTest` is the pixel assertion. This is the same
     * thing where a person met it, header and all.
     */
    @Test
    fun anUnbrokenDayFillsTheRingRatherThanThreeQuartersOfIt() {
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "clean",
            name = "Status page",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = "https://status.example.com/",
        )
        // Just over 15 hours, so the label is the "past 15h" of the report rather
        // than the "24h uptime" a complete window would print. The span is floored
        // to the hour, so 92 ten-minute slots and not 90: 890 minutes prints 14h.
        val samples = (0 until 92).map { index ->
            Sample(
                at = now - (91L - index) * 10L * 60_000L,
                ok = true,
                latencyMs = 4_100L + (index % 7) * 80L,
                code = 200,
            )
        }
        runBlocking {
            val store = Nightbell.install(appContext).store
            // Dark, because the report came in dark and because the light and dark
            // mints are different colours: a capture in the other theme is not the
            // picture to compare against it.
            store.updateSettings { it.copy(theme = ThemeChoice.DARK) }
            store.upsert(monitor)
            store.updateRuntime(monitor.id) {
                it.copy(
                    health = Health.UP,
                    lastCheckedAt = now,
                    lastLatencyMs = 4_260,
                    lastCode = 200,
                    samples = samples,
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Status page").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Status page").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("100 percent", substring = true)
            .assertIsDisplayed()
        composeRule.captureScreenshot("ring-02-unbroken-day")
    }
}
