package me.river.nightbell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.ui.components.LatencyBars
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The response-time chart while a monitor has barely any history.
 *
 * The one thing this is about cannot be asserted, only looked at: a single check
 * used to be drawn as a bar the full width of the card, and a rounded rectangle
 * that size does not read as one measurement out of forty. It reads as something
 * broken, and it was the first thing every new monitor showed. The chart now
 * reserves ten slots and fills them from the left.
 *
 * So this renders the low-data range in one image and leaves it for a person to
 * open. There is no assertion here that would have caught the original, and
 * pretending otherwise with a pixel count would be worse than saying so.
 */
@RunWith(AndroidJUnit4::class)
class LatencyBarsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun samples(count: Int) = List(count) { index ->
        Sample(
            at = 1_800_000_000_000L + index * 60_000L,
            ok = true,
            latencyMs = 140L + (index % 5) * 90L,
            code = 200,
        )
    }

    /**
     * The segmented control, for the concentric-radius rule.
     *
     * Same reason as the chart above: what changed is whether two nested capsules
     * look like one shape inside another or like a mistake, and that is a thing
     * to look at rather than a thing to assert.
     */
    @Test
    fun the_segmented_control_keeps_its_two_capsules_parallel() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NightbellColors.Void)
                        .padding(18.dp),
                ) {
                    SegmentedSelector(
                        options = listOf("GitHub releases", "F-Droid"),
                        selected = "GitHub releases",
                        onSelect = {},
                        label = { it },
                    )
                    Spacer(Modifier.height(18.dp))
                    SegmentedSelector(
                        options = listOf("Dark", "Light", "System"),
                        selected = "Light",
                        onSelect = {},
                        label = { it },
                        accent = NightbellColors.Mint,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("79-segmented-control")
    }

    @Test
    fun the_chart_fills_from_the_left_instead_of_stretching_one_bar() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NightbellColors.Void)
                        .padding(18.dp),
                ) {
                    listOf(1, 2, 5, 10, 24).forEach { count ->
                        Text("$count", color = NightbellColors.TextTertiary)
                        LatencyBars(
                            samples = samples(count),
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            sloMs = 400,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("77-latency-bars-low-data")
    }
}
