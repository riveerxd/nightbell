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
}
