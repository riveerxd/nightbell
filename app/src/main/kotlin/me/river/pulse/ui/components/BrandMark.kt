package me.river.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.asin
import me.river.pulse.ui.theme.PulseColors

/**
 * The Pulse mark: a blue ring, open where the red trace cuts through it.
 *
 * One definition, used by the dashboard header and the widget configuration preview, so
 * the drawn mark cannot drift away from the ones in `res/drawable`. The vector copies
 * Android needs as resources — launcher foreground, widget header, notification
 * silhouette — carry the same geometry by hand, and
 * `docs/brand/pulse-30-ringpulse-icon.svg` is the drawing all four came from.
 *
 * Coordinates below are that SVG's 512-unit space, mapped at draw time. Writing them as
 * literals rather than fractions of the canvas is what keeps them checkable against the
 * source drawing.
 *
 * The ring is two arcs. The first version drew a full circle and punched the gap with
 * [androidx.compose.ui.graphics.BlendMode.Clear] inside an offscreen layer, which worked
 * but cost a render layer on every frame and could not be mirrored by the widget's
 * drawable at all. Stopping each arc short of the trace produces the same picture out of
 * plain geometry: no layer, no blend mode, and one construction that every copy of the
 * mark can use.
 */
private const val MID = 256f

/**
 * Distance from centre to the ring's outer edge: radius 142 plus half of the 40-unit
 * stroke. The mark is scaled so this lands exactly on the canvas edge, which makes
 * [PulseMark]'s `size` mean the ring's overall diameter and nothing else.
 */
private const val EXTENT = 162f

private const val RING_RADIUS = 142f
private const val RING_STROKE = 40f
private const val TRACE_STROKE = 26f

/**
 * How far short of the trace each arc stops, in degrees.
 *
 * The brand drawing opens the ring by the width of its 52-unit casing, which is twice the
 * trace. Half of that is one whole [TRACE_STROKE], so the angle is
 * `asin(TRACE_STROKE / RING_RADIUS)` — it leaves half a trace-width of clearance either
 * side of the red line. Derived rather than eyeballed so it stays correct if the stroke
 * weights are ever retuned.
 *
 * This was `TRACE_STROKE / 2` and produced a gap half the intended width, which did not
 * match the gap the vector copies in `res/drawable` are cut with.
 */
private val GAP_DEGREES =
    Math.toDegrees(asin(TRACE_STROKE / RING_RADIUS).toDouble()).toFloat()

private val ARC_SWEEP = 180f - 2f * GAP_DEGREES

/**
 * The trace, ending 149 units either side of centre — which with a round cap of half
 * [TRACE_STROKE] puts the tips exactly on [EXTENT]. In the landscape lockup the trace
 * runs further and bleeds past the ring; contained is the right call anywhere the mark
 * has to sit in a square or survive a circular mask.
 */
private val TRACE = listOf(
    107f to 256f,
    168f to 256f,
    200f to 172f,
    252f to 344f,
    284f to 256f,
    405f to 256f,
)

@Composable
fun PulseMark(
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    // Aqua, not Mint. Green in the app means "this thing is working" — it is the colour
    // of the charts and the status orbs — and a green mark read as one more status
    // indicator rather than as the app's identity. Both tokens darken for the light
    // scheme, so the mark stays legible there without a second set of values.
    ring: Color = PulseColors.Aqua,
    trace: Color = PulseColors.Rose,
) {
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / (EXTENT * 2f)
        val radius = RING_RADIUS * scale
        val box = Size(radius * 2f, radius * 2f)
        val topLeft = Offset(this.center.x - radius, this.center.y - radius)
        val ringStyle = Stroke(width = RING_STROKE * scale)

        // Lower arc sweeps from just below the right-hand crossing round to just below
        // the left-hand one; upper arc mirrors it over the top. Compose measures from
        // three o'clock, clockwise, so both start angles are offset by the gap.
        drawArc(
            color = ring,
            startAngle = GAP_DEGREES,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = ringStyle,
        )
        drawArc(
            color = ring,
            startAngle = 180f + GAP_DEGREES,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = ringStyle,
        )

        val path = Path().apply {
            TRACE.forEachIndexed { index, (x, y) ->
                val px = this@Canvas.center.x + (x - MID) * scale
                val py = this@Canvas.center.y + (y - MID) * scale
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(
            path = path,
            color = trace,
            style = Stroke(
                width = TRACE_STROKE * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
