package me.river.nightbell

import androidx.compose.ui.graphics.Color
import me.river.nightbell.ui.theme.uptimeRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The colour of the uptime dial, pinned to numbers.
 *
 * This exists because the ramp has been wrong twice in ways that only showed up
 * as "that looks odd": a channel blend put khaki in the upper range, and an HSV
 * hue rotation put a neon chartreuse two steps away from mint. Both compiled,
 * both drew a smooth arc, and both were only caught by looking. The table below
 * is the ramp as accepted, so the next change to it has to be deliberate.
 */
class UptimeRampTest {

    private val rose = Color(0xFFFF4D57)
    private val amber = Color(0xFFFFB020)
    private val mint = Color(0xFF2FD98A)

    private fun dark(percent: Float) = uptimeRamp(rose, amber, mint, percent)

    private fun hex(color: Color): String {
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        return "#%02X%02X%02X".format(channel(color.red), channel(color.green), channel(color.blue))
    }

    private fun assertClose(expected: Color, actual: Color, slack: Float, at: String) {
        val drift = maxOf(
            abs(expected.red - actual.red),
            abs(expected.green - actual.green),
            abs(expected.blue - actual.blue),
        )
        assertTrue(
            "$at should be ${hex(expected)}, was ${hex(actual)} (drift ${"%.3f".format(drift)})",
            drift <= slack,
        )
    }

    @Test
    fun `the anchors are the palette's own colours`() {
        assertClose(rose, dark(0f), 0.004f, "0%")
        assertClose(amber, dark(50f), 0.004f, "50%")
        assertClose(mint, dark(100f), 0.004f, "100%")
    }

    @Test
    fun `the ramp is red then orange then yellow then mint`() {
        // Accepted by eye against the dial itself, then read back out of the
        // ramp. 4/255 of slack: enough for a rounding difference on another JDK,
        // nowhere near enough for a different interpolation or weighting.
        val accepted = mapOf(
            10f to "#FD5456",
            18f to "#FD5E52",
            25f to "#FD6B4C",
            37f to "#FE883E",
            58f to "#FCB126",
            64f to "#DFBD48",
            66f to "#C5C45B",
            68f to "#9ACE70",
            69f to "#77D37D",
        )
        accepted.forEach { (percent, expected) ->
            assertClose(Color(("FF" + expected.removePrefix("#")).toLong(16)), dark(percent), 0.016f, "$percent%")
        }
    }

    @Test
    fun `a fifth of a day down is still red, not orange`() {
        // The reading this whole weighting exists for. A monitor at 18% spent
        // twenty hours of the day unreachable; it does not get to look amber.
        val low = dark(18f)
        assertTrue("18% has drifted off red: ${hex(low)}", low.red > 0.9f && low.green < 0.45f)
        assertTrue("18% is bluer than rose: ${hex(low)}", low.blue <= rose.blue + 0.02f)
    }

    @Test
    fun `nothing in the ramp lands outside what the display can show`() {
        (0..100).forEach { percent ->
            val colour = dark(percent.toFloat())
            listOf(colour.red, colour.green, colour.blue).forEach { channel ->
                assertTrue("$percent% is out of gamut: ${hex(colour)}", channel in 0f..1f)
            }
        }
    }

    @Test
    fun `the top of the range is one colour`() {
        // The whole point of stopping the ramp at 70: a dial in the green does
        // not get a second green. 74% was a grass green next to mint's teal, and
        // the two did not read as neighbours.
        listOf(70f, 74f, 82f, 91f, 100f).forEach { percent ->
            assertEquals("$percent% should be exactly mint", hex(mint), hex(dark(percent)))
        }
        assertTrue("just under the line should not be mint yet", hex(dark(69f)) != hex(mint))
    }

    @Test
    fun `the light scheme keeps its own darkened anchors`() {
        val lightRose = Color(0xFFC4111F)
        val lightAmber = Color(0xFF8A5200)
        val lightMint = Color(0xFF07834B)
        fun light(percent: Float) = uptimeRamp(lightRose, lightAmber, lightMint, percent)
        assertClose(lightRose, light(0f), 0.004f, "light 0%")
        assertClose(lightAmber, light(50f), 0.004f, "light 50%")
        assertClose(lightMint, light(85f), 0.004f, "light 85%")
        // The fade band, which has to stay dark enough to read on paper. Nothing
        // here brightens: every step is between two anchors that already pass.
        val fade = light(64f)
        assertTrue("the light fade has gone pale: ${hex(fade)}", fade.red < 0.55f && fade.green < 0.55f)
    }

    @Test
    fun `readings outside the range clamp to the ends`() {
        assertClose(rose, dark(-40f), 0.004f, "below zero")
        assertClose(mint, dark(140f), 0.004f, "above a hundred")
    }
}
