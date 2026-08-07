package me.river.pulse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.pulse.ui.theme.PulseColors

/**
 * The Pulse mark: the heartbeat trace on its own, in the brand blue.
 *
 * One definition, used by the dashboard header and the widget configuration preview, so
 * the drawn mark cannot drift from the vector copies in `res/drawable`. Those copies —
 * the launcher icon, the widget header, the notification silhouette, the adaptive
 * foreground — are generated from the same numbers by `docs/brand/android_assets.py`, and
 * `docs/brand/pulse-30-ringpulse-icon.svg` is the drawing they all came from.
 *
 * The ring that direction 30 drew around the trace was dropped in 2.4.3: the trace is the
 * whole mark now. Coordinates below are the source SVG's 512-unit space, mapped at draw
 * time; written as literals rather than fractions of the canvas so they stay checkable
 * against the source drawing.
 */
private const val TRACE_STROKE = 26f

/**
 * The trace: a flat line, a short beat up, the tall spike down, back to the line. In the
 * landscape lockup it runs further and bleeds past where the ring used to be; contained is
 * the right call anywhere the mark has to sit in a square or survive a circular mask.
 */
private val TRACE = listOf(
    107f to 256f,
    168f to 256f,
    200f to 172f,
    252f to 344f,
    284f to 256f,
    405f to 256f,
)

/**
 * The trace's stroked bounding box fills this fraction of the canvas, and its stroke is
 * scaled to match — the same 0.86 / 1.1 the widget and notification drawables use in
 * `android_assets.py`, so the Compose mark and the resource copies stay one picture.
 */
private const val FILL = 0.86f
private const val STROKE_SCALE = 1.1f

@Composable
fun PulseMark(
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    // Aqua — the brand blue. Green in the app means "this thing is working" (it is the
    // colour of the charts and the status orbs), so a green mark read as one more status
    // indicator rather than as the app's identity. Aqua darkens for the light scheme, so
    // the mark stays legible there without a second set of values.
    color: Color = PulseColors.Aqua,
) {
    Canvas(modifier.size(size)) {
        val minX = TRACE.minOf { it.first }
        val maxX = TRACE.maxOf { it.first }
        val minY = TRACE.minOf { it.second }
        val maxY = TRACE.maxOf { it.second }
        // Width dominates, so this fills the canvas side to side and centres vertically.
        val boxWidth = (maxX - minX) + TRACE_STROKE
        val boxHeight = (maxY - minY) + TRACE_STROKE
        val scale = this.size.minDimension * FILL / maxOf(boxWidth, boxHeight)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f

        val path = Path().apply {
            TRACE.forEachIndexed { index, (x, y) ->
                val px = center.x + (x - cx) * scale
                val py = center.y + (y - cy) * scale
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = TRACE_STROKE * scale * STROKE_SCALE,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
