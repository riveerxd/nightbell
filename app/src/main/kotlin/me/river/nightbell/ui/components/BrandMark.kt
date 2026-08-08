package me.river.nightbell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.nightbell.ui.theme.NightbellColors

/**
 * The Nightbell mark: the heartbeat trace knocked out of a bell, in the brand blue.
 *
 * One definition, used by the dashboard header and the widget configuration preview, so the
 * drawn mark cannot drift from the vector copies in `res/drawable`. Those copies are
 * generated from the same numbers by `docs/brand/android_assets.py`, and
 * `docs/brand/nightbell-mark-icon.svg` is the drawing they all came from.
 *
 * The trace is the same six points the Pulse mark drew, scaled to [TRACE_SCALE] and centred
 * inside the bell rather than redrawn, so the old identity is literally the same line.
 * Coordinates below are the source SVG's 512-unit space, mapped at draw time; written as
 * literals rather than fractions of the canvas so they stay checkable against the drawing.
 *
 * ## How the hole is made
 *
 * The trace is punched out with [BlendMode.Clear] on an offscreen layer rather than expressed
 * as a second subpath. Clearing keeps the trace a stroked polyline of those six points, which
 * is the thing that has to stay identical to every other copy; turning it into a filled
 * outline first would mean carrying a few hundred generated coordinates here and hoping they
 * still match. The offscreen compositing strategy is what makes the clear affect only this
 * mark instead of the content behind it.
 */
private val BELL_BOX = Rect(134f, 105f, 378f, 408f)

private const val TRACE_SCALE = 0.42f
private const val TRACE_STROKE = 45f * TRACE_SCALE
private const val TRACE_DX = 148.48f
private const val TRACE_DY = 149.64f

/** A flat line, a short beat up, the tall spike down, back to the line. */
private val TRACE = listOf(
    76f to 256f,
    168f to 256f,
    200f to 172f,
    252f to 344f,
    284f to 256f,
    436f to 256f,
)

/** The mark's ink fills this fraction of the canvas. Height dominates, so it centres. */
private const val FILL = 0.94f

@Composable
fun NightbellMark(
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    // Aqua — the brand blue. Green in the app means "this thing is working" (it is the
    // colour of the charts and the status orbs), so a green mark read as one more status
    // indicator rather than as the app's identity. Aqua darkens for the light scheme, so
    // the mark stays legible there without a second set of values.
    color: Color = NightbellColors.Aqua,
) {
    Canvas(
        modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        val scale = this.size.minDimension * FILL / maxOf(BELL_BOX.width, BELL_BOX.height)
        val cx = BELL_BOX.left + BELL_BOX.width / 2f
        val cy = BELL_BOX.top + BELL_BOX.height / 2f
        fun px(x: Float) = center.x + (x - cx) * scale
        fun py(y: Float) = center.y + (y - cy) * scale

        val bell = Path().apply {
            fillType = PathFillType.NonZero
            moveTo(px(256f), py(146f))
            cubicTo(px(198f), py(146f), px(162f), py(208f), px(158f), py(296f))
            lineTo(px(134f), py(324f))
            lineTo(px(134f), py(344f))
            lineTo(px(378f), py(344f))
            lineTo(px(378f), py(324f))
            lineTo(px(354f), py(296f))
            cubicTo(px(350f), py(208f), px(314f), py(146f), px(256f), py(146f))
            close()
            addOval(circle(Offset(px(256f), py(124f)), 19f * scale))
            addOval(circle(Offset(px(256f), py(384f)), 24f * scale))
        }
        drawPath(path = bell, color = color)

        val trace = Path().apply {
            TRACE.forEachIndexed { index, (x, y) ->
                val tx = px(TRACE_DX + x * TRACE_SCALE)
                val ty = py(TRACE_DY + y * TRACE_SCALE)
                if (index == 0) moveTo(tx, ty) else lineTo(tx, ty)
            }
        }
        drawPath(
            path = trace,
            color = Color.Black,
            style = Stroke(
                width = TRACE_STROKE * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Clear,
        )
    }
}

private fun circle(centre: Offset, radius: Float) = Rect(
    left = centre.x - radius,
    top = centre.y - radius,
    right = centre.x + radius,
    bottom = centre.y + radius,
)
