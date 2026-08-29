package me.river.nightbell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.detail.DetailScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The monitor screen at the font sizes accessibility settings offer.
 *
 * Two shapes here used a figure measured at the default text size and never
 * revisited. The action row weighted only "Check now", so the two buttons beside
 * it were measured first and the screen's primary action took whatever was left.
 * The configuration rows pinned their label column at 112dp, which is a quarter
 * of a phone at 100 per cent and half of one at 200, where "Expected status"
 * broke over four lines beside a one-line value.
 */
@RunWith(AndroidJUnit4::class)
class DetailDensityInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "detail",
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
                    samples = List(6) { index ->
                        Sample(
                            at = now - index * 60_000L,
                            ok = true,
                            latencyMs = 300L + index,
                            code = 200,
                        )
                    },
                )
            }
        }
    }

    private fun detailAt(fontScale: Float) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    DetailScreen(
                        monitorId = "detail",
                        onBack = {},
                        onEdit = {},
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
    fun allThreeActionsStaySizedAtTheLargestFontScale() {
        detailAt(2.0f)
        composeRule.captureScreenshot("detail-density-01-actions-at-200")

        // The primary action by its tag: its label is trimmed to `shortText` when
        // it will not fit, and "Check" as a substring also matches the history
        // header two cards below.
        composeRule.onNodeWithTag("detail-check").assertIsDisplayed()
        val check = composeRule.onNodeWithTag("detail-check").getUnclippedBoundsInRoot()
        assertTrue("the check button is only ${check.width} wide", check.width >= 80.dp)

        listOf("Pause", "Mute 1h").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
            val bounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label is only ${bounds.width} wide", bounds.width >= 40.dp)
        }
    }

    @Test
    fun aConfigLabelDoesNotShredItselfAtTheLargestFontScale() {
        detailAt(2.0f)
        composeRule.captureScreenshot("detail-density-02-config-at-200")

        // Four lines of "Expected status" in a 112dp column was the reported
        // shape. Stacked, it is one line of label over its value.
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasText("Interval"))
        composeRule.waitForIdle()
        val bounds = composeRule.onNodeWithText("Interval").getUnclippedBoundsInRoot()
        assertTrue(
            "the config label is ${bounds.height} tall, so it has broken over lines",
            bounds.height <= 90.dp,
        )
    }

    @Test
    fun theScreenStillReadsNormallyAtTheDefaultScale() {
        detailAt(1.0f)
        composeRule.captureScreenshot("detail-density-03-at-100")

        composeRule.onNodeWithTag("detail-check").assertIsDisplayed()
        composeRule.onNodeWithText("Pause").assertIsDisplayed()
        composeRule.onNodeWithText("Mute 1h").assertIsDisplayed()
        // SectionHeader upper-cases its title, so the visible node is CONFIGURATION.
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasText("Interval"))
        composeRule.onNodeWithText("Interval").assertIsDisplayed()
        composeRule.onNodeWithText("every 15 min").assertIsDisplayed()
    }
}
