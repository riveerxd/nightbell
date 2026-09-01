package me.river.nightbell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.ui.components.UptimeRing
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The uptime dial, read back out of the pixels it drew.
 *
 * Reported from a phone showing 100%: the ring looked about three quarters
 * filled. It was, and the fill was already the whole 270 degrees. What broke it
 * was the brush. A sweep gradient is anchored to the canvas axis, not to the arc
 * it paints, so the first stop of the alpha ramp landed at three o'clock, and the
 * last 45 degrees of a full dial came out at 0.45 alpha behind a hard seam.
 *
 * Nothing in the semantics tree could see that, so this walks the arc and
 * compares the colour it finds against the colour at the top of the dial.
 *
 * The same reading of the pixels then answers the two things that came after it:
 * that the dial's colour is the reading rather than a fixed mint, and that it
 * fills from empty when it appears instead of being drawn already full.
 */
@RunWith(AndroidJUnit4::class)
class UptimeRingFillInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ringSize = 180.dp

    /**
     * The brightest pixel on a radial line, which is the middle of whichever
     * stroke that line crosses. Found rather than calculated so that a change to
     * the ring's stroke width or inset does not turn into a false failure here.
     */
    private fun PixelMap.strokeAt(degrees: Float, sizePx: Int): Color {
        val centre = sizePx / 2f
        val radians = Math.toRadians(degrees.toDouble())
        var best = Color.Transparent
        var bestGreen = -1f
        var factor = 0.55f
        while (factor <= 0.99f) {
            val radius = centre * factor
            val x = (centre + radius * cos(radians)).roundToInt().coerceIn(0, sizePx - 1)
            val y = (centre + radius * sin(radians)).roundToInt().coerceIn(0, sizePx - 1)
            val pixel = this[x, y]
            if (pixel.green > bestGreen) {
                bestGreen = pixel.green
                best = pixel
            }
            factor += 0.004f
        }
        return best
    }

    /**
     * How much of the dial is painted, in sampled steps out of 128.
     *
     * The track is [NightbellColors.sheen] at 0.07 over black, so anything the
     * fill has reached is several times brighter than anything it has not. That
     * is a cheaper and steadier signal than trying to match the fill's colour,
     * which is the thing being varied.
     */
    private fun PixelMap.filledSteps(sizePx: Int): Int =
        (135..405 step 2).count { angle ->
            val pixel = strokeAt(angle.toFloat(), sizePx)
            maxOf(pixel.red, pixel.green, pixel.blue) > 0.25f
        }

    @Test
    fun a_hundred_percent_fills_the_dial_in_one_colour() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Box(
                    Modifier
                        .background(NightbellColors.Void)
                        .padding(12.dp)
                        .testTag("ring"),
                ) {
                    UptimeRing(
                        percent = 100f,
                        modifier = Modifier.size(ringSize),
                        label = "past 15h",
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val image = composeRule.onNodeWithTag("ring").captureToImage()
        val pixels = image.toPixelMap()
        // 135 degrees is the start of the arc and 405 the end. Both ends are round
        // caps, so the sweep stops short of them: a cap is antialiased against the
        // background over its outer half and would read as a dimmer green whether
        // or not the fill underneath it is correct.
        val reference = pixels.strokeAt(270f, image.width)
        val offenders = (142..398 step 4).map { it.toFloat() }.mapNotNull { angle ->
            val found = pixels.strokeAt(angle, image.width)
            val drift = maxOf(
                abs(found.red - reference.red),
                abs(found.green - reference.green),
                abs(found.blue - reference.blue),
            )
            if (drift > 0.06f) "$angle deg: $found, drift ${"%.2f".format(drift)}" else null
        }

        // The old brush put the whole tail from 360 to 405 degrees at roughly half
        // the green of the top of the dial, so this is not a tolerance question.
        check(offenders.isEmpty()) {
            "100% uptime is drawn in more than one colour. Reference at the top of " +
                "the dial is $reference. Off: ${offenders.joinToString("; ")}"
        }
    }


    /**
     * Rose at 0, amber at 50, mint at 100.
     *
     * Asserted as properties of the channels rather than against three hex
     * values, because the light and dark schemes carry different anchors for the
     * same three status colours and both have to hold. What matters is that a bad
     * reading is red, a middling one is yellow, a good one is green, and that the
     * three are ordered.
     */
    @Test
    fun the_dial_takes_its_colour_from_the_reading() {
        val reading = mutableStateOf(0f)
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Box(
                    Modifier
                        .background(NightbellColors.Void)
                        .padding(12.dp)
                        .testTag("ring"),
                ) {
                    UptimeRing(
                        percent = reading.value,
                        modifier = Modifier.size(ringSize),
                        label = "past 15h",
                    )
                }
            }
        }

        val sampled = mutableMapOf<Float, Color>()
        listOf(12f, 50f, 100f).forEach { percent ->
            composeRule.runOnUiThread { reading.value = percent }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("ring").captureToImage()
            val pixels = image.toPixelMap()
            // 15 degrees in, which is inside the sweep of all three readings and
            // clear of the round cap at the start. 12% is 32 degrees of arc, so
            // there is no lower reading this can be asked about.
            sampled[percent] = pixels.strokeAt(150f, image.width)
        }

        val low = sampled.getValue(12f)
        val mid = sampled.getValue(50f)
        val high = sampled.getValue(100f)
        check(low.red > 0.7f && low.green < 0.45f) { "12% should be rose, got $low" }
        check(mid.red > 0.6f && mid.green > 0.4f && mid.blue < 0.3f) {
            "50% should be amber, got $mid"
        }
        check(high.green > high.red * 1.5f) { "100% should be mint, got $high" }
        check(low.green < mid.green && mid.green < high.green) {
            "the ramp is not ordered: 12% $low, 50% $mid, 100% $high"
        }
        // Slack on the two reds because both are pinned at the top of the
        // channel, and the gamut walk in `uptimeRamp` can shave a step off one of
        // them: 12% comes back 0.996 where amber is a flat 1.0.
        check(high.red < mid.red && mid.red <= low.red + 0.01f) {
            "red should drain out as the reading climbs: 12% $low, 50% $mid, 100% $high"
        }
    }

    /**
     * The dial fills from empty, in the colour of the reading it is going to land
     * on.
     *
     * Driven off the test clock rather than off a sleep, so the frames below are
     * the frames the animation actually produced at those offsets. Motion is at
     * full intensity here on purpose: at 0 the reveal is meant to be skipped, and
     * that is what every other test in the app renders.
     */
    @Test
    fun the_dial_fills_from_empty_when_it_appears() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NightbellTheme(motionIntensity = 1f, theme = ThemeChoice.DARK) {
                Box(
                    Modifier
                        .background(NightbellColors.Void)
                        .padding(12.dp)
                        .testTag("ring"),
                ) {
                    UptimeRing(
                        percent = 100f,
                        modifier = Modifier.size(ringSize),
                        label = "past 15h",
                    )
                }
            }
        }

        fun frame(): Pair<Int, Color> {
            val image = composeRule.onNodeWithTag("ring").captureToImage()
            val pixels = image.toPixelMap()
            return pixels.filledSteps(image.width) to pixels.strokeAt(150f, image.width)
        }

        composeRule.mainClock.advanceTimeByFrame()
        val (startFill, _) = frame()
        // Still inside the beat the dial holds while the screen it is on slides in
        // and settles. Nothing may have moved yet.
        composeRule.mainClock.advanceTimeBy(500)
        val (heldFill, _) = frame()
        composeRule.mainClock.advanceTimeBy(230)
        val (earlyFill, earlyColour) = frame()
        composeRule.mainClock.advanceTimeBy(900)
        val (endFill, endColour) = frame()
        composeRule.mainClock.autoAdvance = true

        // 136 sampled steps, of which the two ends sit under the round caps.
        check(startFill <= 4) { "the dial is drawn already full: $startFill steps at the first frame" }
        check(heldFill <= 4) {
            "the fill starts under the screen transition: $heldFill steps at 500ms"
        }
        check(earlyFill in (startFill + 8)..(endFill - 8)) {
            "no reveal in between: $startFill then $earlyFill then $endFill steps"
        }
        check(endFill >= 130) { "the reveal does not finish: $endFill steps of 136" }
        // The colour does not move while the arc does. A dial on its way to 100%
        // is mint from the first frame, not a red one recovering in front of you.
        val drift = maxOf(
            abs(earlyColour.red - endColour.red),
            abs(earlyColour.green - endColour.green),
            abs(earlyColour.blue - endColour.blue),
        )
        check(drift <= 0.02f) {
            "the fill changes colour as it reveals: early $earlyColour, settled $endColour"
        }
    }

    /**
     * The same dial at the values a person actually sees, for looking at rather
     * than for asserting: a partial arc has to still read as a partial arc, and
     * the fix above is only a fix if it did not flatten that distinction.
     */
    @Test
    fun the_dial_still_reads_as_partial_below_a_hundred() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NightbellColors.Void)
                        .padding(14.dp),
                ) {
                    listOf(listOf(0f, 18f, 37f), listOf(50f, 64f, 100f)).forEach { row ->
                        Row {
                            row.forEach { percent ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    UptimeRing(
                                        percent = percent,
                                        modifier = Modifier.size(112.dp),
                                        label = "past 15h",
                                    )
                                    Text("$percent", color = NightbellColors.TextTertiary)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("96-uptime-ring-fill")
    }

    /**
     * The light scheme, and the dial with nothing to report.
     *
     * The ramp is read off the palette, so light mode gets the darkened anchors
     * and its own mid-ramp hues; those reach about 3.5:1 against the page rather
     * than the 4.5:1 the anchors hold, which is the thing to look at here. The
     * unknown dial is in the same picture because it is the state a monitor is in
     * for its first minute of life and it has no reading to take a colour from.
     */
    @Test
    fun the_ramp_holds_up_in_the_light_scheme() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.LIGHT) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NightbellColors.Void)
                        .padding(14.dp),
                ) {
                    listOf(listOf(0f, 18f, 37f), listOf(50f, 64f, 100f)).forEach { row ->
                        Row {
                            row.forEach { percent ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    UptimeRing(
                                        percent = percent,
                                        modifier = Modifier.size(112.dp),
                                        label = "past 15h",
                                    )
                                    Text("$percent", color = NightbellColors.TextTertiary)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    Row {
                        UptimeRing(
                            percent = 0f,
                            modifier = Modifier.size(112.dp),
                            label = "no checks yet",
                            unknown = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        UptimeRing(
                            percent = 100f,
                            modifier = Modifier.size(112.dp),
                            label = "1 check",
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("97-uptime-ring-light")
    }
}
