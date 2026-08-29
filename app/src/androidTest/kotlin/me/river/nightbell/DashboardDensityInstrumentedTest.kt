package me.river.nightbell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The dashboard at the font sizes accessibility settings actually offer.
 *
 * Android 14 lets the font scale reach 200 per cent, and every row on this
 * screen used to be a plain `Row` that measured its tags before its controls. At
 * 150 per cent the card's re-check button was drawn past the card's right edge;
 * at 180 per cent the pause button measured to nothing and left the tree
 * altogether, so a monitor could not be paused from the dashboard at all, and the
 * fleet banner's monitor count set one letter to a line down the side of the
 * banner. Measured on a device before any of this was written.
 *
 * The density override is what makes it a test rather than a note: the same
 * composition, one number changed.
 */
@RunWith(AndroidJUnit4::class)
class DashboardDensityInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "wide",
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
    }

    private fun dashboardAt(fontScale: Float) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    DashboardScreen(
                        onAddMonitor = {},
                        onOpenMonitor = {},
                        onOpenSettings = {},
                        onToast = {},
                    )
                }
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Checkout API").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun bothCardActionsSurviveTheLargestFontScale() {
        dashboardAt(2.0f)
        composeRule.captureScreenshot("density-01-card-actions-at-200")

        // The one that used to disappear.
        composeRule.onNodeWithContentDescription("Pause monitor").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Check now").assertIsDisplayed()

        listOf("Pause monitor", "Check now").forEach { label ->
            val bounds = composeRule.onNodeWithContentDescription(label)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$label is ${bounds.width}, under the 48dp floor",
                bounds.width >= 47.dp,
            )
            assertTrue(
                "$label is ${bounds.height}, under the 48dp floor",
                bounds.height >= 47.dp,
            )
        }
    }

    @Test
    fun theFleetCountStaysOnALineAtTheLargestFontScale() {
        dashboardAt(2.0f)
        composeRule.captureScreenshot("density-02-fleet-banner-at-200")

        // It measured 5px wide and 999 tall on a 1080x2340 device before the
        // banner's metrics became a flow, which is one character per line.
        val bounds = composeRule.onNodeWithText("1 MONITOR").getUnclippedBoundsInRoot()
        assertTrue(
            "the monitor count is ${bounds.height} tall, so it has wrapped per character",
            bounds.height <= 80.dp,
        )
        assertTrue(
            "the monitor count is only ${bounds.width} wide",
            bounds.width >= 40.dp,
        )
    }

    @Test
    fun theCardStillReadsNormallyAtTheDefaultScale() {
        dashboardAt(1.0f)
        composeRule.captureScreenshot("density-03-card-at-100")

        composeRule.onNodeWithContentDescription("Pause monitor").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Check now").assertIsDisplayed()
        composeRule.onNodeWithText("Operational").assertIsDisplayed()
        composeRule.onNodeWithText("1 MONITOR").assertIsDisplayed()
    }
}
