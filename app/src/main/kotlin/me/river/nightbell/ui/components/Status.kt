package me.river.nightbell.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.LatencyChart
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.LocalNightbellMotion
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.healthColor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import me.river.nightbell.ui.theme.rememberLoopingFloat

// ------------------------------------------------------------------- counters

/** Number that rolls when it changes — used for latency, uptime, counts. */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
    prefix: String = "",
    color: Color = NightbellColors.TextPrimary,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val goingUp = targetState > initialState
            (
                slideInVertically { height -> if (goingUp) height else -height } + fadeIn()
                ) togetherWith (
                slideOutVertically { height -> if (goingUp) -height else height } + fadeOut()
                )
        },
        label = "counter",
        modifier = modifier,
    ) { shown ->
        Text(
            text = "$prefix$shown$suffix",
            style = style,
            color = color,
            maxLines = 1,
        )
    }
}

// --------------------------------------------------------------------- status

/**
 * The status "orb": a solid core, a soft bloom, and — while a check is in
 * flight — two expanding sonar rings.
 */
@Composable
fun StatusOrb(
    health: Health,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    size: Dp = 14.dp,
) {
    val motion = LocalNightbellMotion.current
    val color = if (checking) NightbellColors.Aqua else healthColor(health)
    val ping by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 1f,
        durationMillis = 1_800,
        label = "ping",
    )
    val alive = checking || health == Health.DOWN
    val gloss = NightbellColors.sheen(0.40f)

    Canvas(
        modifier
            .size(size * 3)
            .semantics { contentDescription = if (checking) "Checking" else health.label },
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val core = size.toPx() / 2f

        // A tight halo, just enough to lift the dot off the card.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.20f), Color.Transparent),
                center = center,
                radius = core * 2.4f,
            ),
            radius = core * 2.4f,
            center = center,
        )
        if (alive && motion.enabled) {
            listOf(0f, 0.5f).forEach { phase ->
                val p = (ping + phase) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.26f),
                    radius = core * (1f + p * 2.0f),
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
        }
        drawCircle(color = color, radius = core, center = center)
        drawCircle(
            color = gloss,
            radius = core * 0.4f,
            center = center.copy(x = center.x - core * 0.25f, y = center.y - core * 0.25f),
        )
    }
}

@Composable
fun StatusPill(
    health: Health,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    detail: String = "",
) {
    val color = if (checking) NightbellColors.Aqua else healthColor(health)
    val label = if (checking) "Checking" else health.label
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NightbellRadii.chip))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(NightbellRadii.chip))
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusOrb(health = health, checking = checking, size = 7.dp)
        Text(
            text = if (detail.isBlank()) label else "$label · $detail",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// --------------------------------------------------------------------- charts

/**
 * How many samples the sparkline and the tick strip both render.
 *
 * They are stacked on a dashboard card and read as one chart, so they have to
 * cover the *same* checks — showing 28 above 40 put a red tick under a blue
 * stretch of line and made the pair look broken.
 */
const val SAMPLE_WINDOW = 40

/** Gap between ticks, and so the inset that positions every sample. */
private val SAMPLE_GAP = 2.dp

private fun sampleTickWidth(count: Int, width: Float, gap: Float): Float =
    ((width - gap * (count - 1)) / count).coerceAtLeast(1.2f)

/**
 * Horizontal centre of sample [index] of [count] across [width].
 *
 * The single source of truth for both charts' x axis. They used to derive it
 * separately — the strip laying out cells and the line spanning edge to edge —
 * which put every point up to half a cell away from its own tick. Two formulas
 * that merely agree in the common case is exactly how they drifted apart.
 */
private fun sampleCenterX(index: Int, count: Int, width: Float, gap: Float): Float {
    val tick = sampleTickWidth(count, width, gap)
    return index * (tick + gap) + tick / 2f
}

/**
 * Horizontal position of sample [index] on the sparkline, spread edge to edge.
 *
 * Deliberately not [sampleCenterX]. The strip below draws cells, so its ticks
 * have to sit at cell centres; the line above is a trend and only reads as one
 * when it spans the same width the strip does. At two or three samples that
 * difference is the whole picture: centres drew the line across the middle half
 * of the card with dead space either side, which looks like a rendering fault
 * rather than like two data points.
 *
 * Every point still lands within its own tick. The first sits on that tick's
 * left edge, the last on the last tick's right edge, and in between the offset
 * from the centre never exceeds half a cell, so no point ever drifts over a
 * neighbour and the gradient stop for a failure stays above its own red tick.
 */
private fun sampleSpreadX(index: Int, count: Int, width: Float): Float =
    if (count <= 1) width / 2f else index.toFloat() / (count - 1) * width

/** Radius of the head dot's halo, and the room the line leaves it at the end. */
private val HEAD_HALO = 6.dp

/**
 * Latency sparkline with a gradient underfill.
 *
 * Failures are carried by the stroke's own colour rather than by markers on top
 * of it: the line bleeds to rose at a failed check and interpolates back to
 * [accent] at the next passing one. Dots made every outage the same size no
 * matter how long it lasted, and stacked up into noise on a bad run.
 *
 * Pass the same list to [HistoryStrip] — see [SAMPLE_WINDOW].
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    // Mint, not the brand blue: the line is a picture of something working, and it
    // should agree with the orb and the pill on the same card. Failed samples still
    // bleed to Rose inside the draw below.
    accent: Color = NightbellColors.Mint,
    animate: Boolean = true,
) {
    val motion = LocalNightbellMotion.current
    val reveal = remember(samples.size) { Animatable(if (animate && motion.enabled) 0f else 1f) }
    LaunchedEffect(samples.size) {
        if (animate && motion.enabled) {
            reveal.animateTo(1f, tween(700, easing = EaseOutCubic))
        }
    }
    if (samples.isEmpty()) {
        Box(modifier) { }
        return
    }

    val maxLatency = max(1L, samples.maxOf { it.latencyMs })
    val fail = NightbellColors.Rose
    val headCore = NightbellColors.TextPrimary

    Canvas(modifier.clearAndSetSemantics { contentDescription = chartSummary("Response time trend", samples) }) {
        val w = size.width
        val h = size.height
        val halo = HEAD_HALO.toPx()
        // The line stops where the dot is, and the dot's halo is what reaches the
        // edge. Running the line to the full width and then nudging the dot inwards
        // so its halo would not clip left a stub of stroke sticking out past the
        // dot, which is not what the head of a trend looks like.
        val span = (w - halo).coerceAtLeast(1f)
        fun pointAt(index: Int): Offset {
            val sample = samples[index]
            val normalized = (sample.latencyMs.toFloat() / maxLatency).coerceIn(0f, 1f)
            return Offset(
                x = sampleSpreadX(index, samples.size, span),
                y = h - (normalized * (h * 0.78f)) - h * 0.11f,
            )
        }

        val line = Path()
        val fill = Path()
        val visibleCount = max(2, (samples.size * reveal.value).roundToInt())
        for (i in 0 until min(visibleCount, samples.size)) {
            val point = pointAt(i)
            if (i == 0) {
                line.moveTo(point.x, point.y)
                fill.moveTo(point.x, h)
                fill.lineTo(point.x, point.y)
            } else {
                val previous = pointAt(i - 1)
                val midX = (previous.x + point.x) / 2f
                line.cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
                fill.cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
            }
        }
        val lastX = pointAt(min(visibleCount, samples.size) - 1).x
        fill.lineTo(lastX, h)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.02f), Color.Transparent),
            ),
        )
        // The line carries the failures itself: one gradient stop per sample,
        // pinned to that sample's own x so the reddest part of the stroke sits
        // exactly above its tick below. The interpolation walks back to brand
        // blue as soon as the next check passes, so a single blip reads as a
        // blip and a run of them reads as a red plateau.
        //
        // Positioned from the same function the points are, so a stop cannot drift
        // away from the sample it belongs to. Two formulas that merely agree in
        // the common case is exactly how these drifted apart once already.
        val healthStops = samples.mapIndexed { index, sample ->
            // Fractions of the full width, while the points are laid out across
            // `span`. The last stop therefore lands just short of 1, and its colour
            // carries on to the edge, which is what should happen under the head.
            (sampleSpreadX(index, samples.size, span) / w) to
                if (sample.ok) accent else fail
        }
        drawPath(
            path = line,
            brush = if (healthStops.size < 2) {
                SolidColor(healthStops.first().second)
            } else {
                Brush.horizontalGradient(*healthStops.toTypedArray())
            },
            style = Stroke(width = 2.2.dp.toPx()),
        )

        // Leading dot, meaning "now", tinted by whether now is healthy. Sits exactly on
        // the end of the stroke, because `span` already left room for it.
        val lastIndex = min(visibleCount, samples.size) - 1
        val head = pointAt(lastIndex)
        val headTone = if (samples[lastIndex].ok) accent else fail
        drawCircle(headTone.copy(alpha = 0.3f), radius = halo, center = head)
        drawCircle(headCore, radius = 2.6.dp.toPx(), center = head)
    }
}

/**
 * Bar chart of the last N latencies, used on the detail screen.
 *
 * With a [sloMs] the chart also says where the latency budget is, twice over: a
 * dashed line across it, and every bar that answered slower than the budget drawn
 * in Amber instead of [accent]. The line says where the bar to beat is, the colour
 * says which checks missed it, and the second one is what was actually asked for.
 *
 * Amber is not a new colour here. [me.river.nightbell.domain.Health.DEGRADED]
 * already means "answered, but slower than its budget", and the degraded
 * notification is already this colour, so a slow bar reads the same as the alert
 * it would have raised.
 *
 * It *would have* raised, rather than did. A bar is Amber when the latency this
 * sample recorded is over budget, while the alert track judges on a latency
 * [me.river.nightbell.domain.NetworkBaseline] may have adjusted down after
 * deciding the phone's own connection was the slow part. So an Amber bar can
 * appear where no degraded alert fired, and that is right for both of them: this
 * chart is a picture of what was measured, and the alert track is a judgement
 * about what it meant.
 *
 * See [me.river.nightbell.domain.LatencyChart] for why the budget cannot simply
 * raise the scale whenever it is taller than the samples.
 */
@Composable
fun LatencyBars(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    /** Green for the same reason as [Sparkline]; a failed bar is drawn in Rose. */
    accent: Color = NightbellColors.Mint,
    /** Latency budget in millis, from `Monitor.sloMs`. 0 draws no line. */
    sloMs: Int = 0,
) {
    val motion = LocalNightbellMotion.current
    val grow = remember(samples.size) { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(samples.size) {
        if (motion.enabled) grow.animateTo(1f, tween(620, easing = EaseOutCubic))
    }
    if (samples.isEmpty()) {
        Box(modifier)
        return
    }
    val scaleMax = LatencyChart.scaleMax(samples, sloMs)
    val budgetFraction = LatencyChart.budgetFraction(samples, sloMs)
    val budgetIsCapped = LatencyChart.budgetIsCapped(samples, sloMs)
    val fail = NightbellColors.Rose
    val slow = NightbellColors.Amber
    val summary = chartSummary("Response time history", samples, sloMs)
    Canvas(modifier.clearAndSetSemantics { contentDescription = summary }) {
        val count = samples.size
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        samples.forEachIndexed { index, sample ->
            val ratio = (sample.latencyMs.toFloat() / scaleMax).coerceIn(0.04f, 1f) * grow.value
            val barHeight = size.height * ratio
            val x = index * (barWidth + gap)
            val color = when {
                !sample.ok -> fail
                LatencyChart.isOverBudget(sample, sloMs) -> slow
                else -> accent
            }
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.28f)),
                ),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2.2f),
            )
        }

        // Drawn after the bars so it reads as a threshold laid over them, and at a
        // fixed height while the bars grow into it. Animating the line's position
        // as well made the budget itself look like it was moving.
        if (budgetFraction > 0f) {
            val y = size.height - size.height * budgetFraction
            val dash = 5.dp.toPx()
            drawLine(
                color = slow.copy(alpha = if (budgetIsCapped) 0.34f else 0.72f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)),
                alpha = grow.value,
            )
        }
    }
}

/**
 * The budget line's caption, as a row of text under [LatencyBars].
 *
 * Outside the Canvas on purpose. Text drawn into the chart would need font
 * metrics, would fight the bars for contrast wherever the line crosses one, and
 * would arrive at a screen reader as part of the chart's own description instead
 * of as something readable on its own. A legend costs a row of small text and says
 * more: the figure, and how many checks missed it.
 */
@Composable
fun LatencyBudgetLegend(
    samples: List<Sample>,
    sloMs: Int,
    modifier: Modifier = Modifier,
) {
    if (sloMs <= 0 || samples.isEmpty()) return
    val over = LatencyChart.overBudget(samples, sloMs)
    val answered = LatencyChart.answered(samples)
    val capped = LatencyChart.budgetIsCapped(samples, sloMs)
    val swatch = NightbellColors.Amber.copy(alpha = if (capped) 0.34f else 0.72f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(6.dp)) {
            val dash = 3.dp.toPx()
            drawLine(
                color = swatch,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = buildString {
                append("Budget ${formatLatency(sloMs.toLong())}")
                // Said out loud, because a line pinned to the top edge is the one
                // case where the drawing cannot be read literally.
                if (capped) append(", above this range")
                append(" · ")
                append(
                    when {
                        // Counted against the checks that answered, not against
                        // every check. A failed check has no round trip to compare
                        // and must not be reported as being inside the budget.
                        answered == 0 -> "nothing answered to measure"
                        over == 0 -> "all $answered inside it"
                        over == answered -> "all $answered over"
                        else -> "$over of $answered over"
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

/** Big animated uptime dial. */
@Composable
fun UptimeRing(
    percent: Float,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Mint,
    label: String = "uptime",
    /** No reading available — show a dash rather than a confident 0%. */
    unknown: Boolean = false,
) {
    val motion = LocalNightbellMotion.current
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 100f),
        animationSpec = if (motion.enabled) {
            tween(900, easing = FastOutSlowInEasing)
        } else {
            spring(stiffness = Spring.StiffnessHigh)
        },
        label = "uptime",
    )
    // One node, one sentence. Left as two Texts inside a Canvas, TalkBack read
    // "ninety-three percent" and then spelled U-P-T-I-M-E as a separate item.
    val spoken = if (unknown) label else "${animated.roundToInt()} percent, $label"
    val track = NightbellColors.sheen(0.07f)
    Box(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            if (unknown) return@Canvas
            drawArc(
                // Stays in one hue: a green dial that fades through brand blue
                // reads as a gradient, not as "93% up".
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.45f), accent, accent),
                ),
                startAngle = 135f,
                sweepAngle = 270f * (animated / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        // Held inside the arc, not merely ellipsised.
        //
        // `softWrap = false` truncates the string but does not stop the Text from
        // measuring wider than the ring: a long label spilled past the stroke and
        // sat on top of the percentage above it. Bounding the column is what keeps
        // an over-long label a truncation rather than a collision. The fraction is
        // the widest chord that clears a 9 dp stroke at this radius.
        Column(
            modifier = Modifier.fillMaxWidth(RING_TEXT_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (unknown) "—" else "${animated.roundToInt()}%",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (unknown) NightbellColors.TextTertiary else NightbellColors.TextPrimary,
                maxLines = 1,
            )
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Share of the ring's width the text inside it may occupy. */
private const val RING_TEXT_WIDTH = 0.68f

/** Slim horizontal strip of pass/fail ticks — a compact outage history. */
@Composable
fun HistoryStrip(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Mint,
) {
    val empty = NightbellColors.sheen(0.05f)
    val fail = NightbellColors.Rose
    Canvas(modifier.clearAndSetSemantics { contentDescription = outcomeSummary(samples) }) {
        if (samples.isEmpty()) {
            drawRoundRect(
                color = empty,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            return@Canvas
        }
        val count = samples.size
        val gap = SAMPLE_GAP.toPx()
        val tickWidth = sampleTickWidth(count, size.width, gap)
        samples.forEachIndexed { index, sample ->
            // Positioned from the shared centre so the sparkline above can pin a
            // gradient stop to the same x.
            val centerX = sampleCenterX(index, count, size.width, gap)
            drawRoundRect(
                color = if (sample.ok) accent.copy(alpha = 0.85f) else fail,
                topLeft = Offset(centerX - tickWidth / 2f, 0f),
                size = Size(tickWidth, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tickWidth / 2f),
            )
        }
    }
}

// -------------------------------------------------------------- empty & error

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = NightbellIcons.Radar,
    accent: Color = NightbellColors.Aqua,
    action: (@Composable () -> Unit)? = null,
) {
    val float by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 7f,
        durationMillis = 3_200,
        repeatMode = RepeatMode.Reverse,
        easing = FastOutSlowInEasing,
        label = "float",
    )
    val spin by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 14_000,
        label = "spin",
    )
    val rings = List(3) { i -> NightbellColors.sheen(0.09f - i * 0.02f) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(148.dp)
                .graphicsLayer { translationY = float },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                    center = center,
                )
                listOf(0.42f, 0.62f, 0.82f).forEachIndexed { i, factor ->
                    drawCircle(
                        color = rings[i],
                        radius = size.minDimension / 2f * factor,
                        center = center,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), i * 4f),
                        ),
                    )
                }
                rotate(spin, center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, accent.copy(alpha = 0.30f), Color.Transparent),
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = 70f,
                        useCenter = true,
                        topLeft = Offset(size.width * 0.09f, size.height * 0.09f),
                        size = Size(size.width * 0.82f, size.height * 0.82f),
                    )
                }
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NightbellColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NightbellColors.TextSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

// ------------------------------------------------------------ pull to refresh

/**
 * Bespoke pull-to-refresh with a **hold to confirm** commit.
 *
 * Re-checking every monitor is not free — it fires real network requests at
 * every endpoint at once — so it should not be reachable by an accidental
 * over-scroll. Two changes make the gesture deliberate without making it
 * annoying:
 *
 *  1. **The pull is rubber-banded, not linear.** Travel maps through
 *     `maxPull · (1 − e^(−raw/maxPull))`, so the sheet moves freely at first
 *     and asymptotically stiffens. Every visual — puck scale, ring sweep,
 *     glyph rotation, label — is a pure function of that one distance, so the
 *     indicator tracks the finger exactly rather than animating on its own
 *     schedule.
 *  2. **Past the threshold you have to hold.** Releasing early springs back
 *     and cancels. A ring fills over [HOLD_TO_CONFIRM_MS]; only when it closes
 *     does the refresh fire, with a haptic thump to confirm.
 *
 * The hold timer keys off "finger still down", which nested scroll reports
 * implicitly: [NestedScrollConnection.onPostScroll] marks the drag live and
 * `onPreFling`/`onPostFling` (both delivered on release) end it. A finger held
 * perfectly still sends no events at all, which is exactly the case that has to
 * keep counting.
 */
@Composable
fun PullToRefreshLayout(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Aqua,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 78.dp.toPx() }
    val maxPull = threshold * 1.9f
    val offset = remember { Animatable(0f) }
    val hold = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val motion = LocalNightbellMotion.current

    // Raw finger travel. The rubber band is a pure function of it, so the
    // mapping stays reversible when the user drags back up mid-pull.
    var raw by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val rubberBand = remember(maxPull) {
        { travel: Float -> maxPull * (1f - exp(-travel.coerceAtLeast(0f) / maxPull)) }
    }

    val armed = offset.value >= threshold && !refreshing

    LaunchedEffect(refreshing) {
        if (refreshing) {
            offset.animateTo(threshold * 0.72f, spring(dampingRatio = 0.7f))
        } else {
            raw = 0f
            offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
        }
    }

    // Crossing the threshold is the moment the gesture changes meaning, so it
    // gets its own light tick before the heavier confirmation thump.
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LaunchedEffect(armed, dragging, refreshing) {
        if (armed && dragging) {
            // Resumes from wherever a previous, cancelled hold left off, so
            // wobbling around the threshold doesn't reset your progress.
            val remaining = ((1f - hold.value) * HOLD_TO_CONFIRM_MS).toInt().coerceAtLeast(1)
            hold.animateTo(1f, tween(if (motion.enabled) remaining else 1, easing = LinearEasing))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            hold.snapTo(0f)
            raw = 0f
            onRefresh()
        } else if (hold.value > 0f) {
            hold.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        }
    }

    val connection = remember(refreshing, maxPull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || raw <= 0f) return Offset.Zero
                // Collapse the pull before letting the list scroll, and consume
                // only the part we actually absorbed.
                val nextRaw = (raw + available.y).coerceAtLeast(0f)
                val consumedRaw = nextRaw - raw
                raw = nextRaw
                scope.launch { offset.snapTo(rubberBand(raw)) }
                return Offset(0f, consumedRaw)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput && !refreshing) {
                    dragging = true
                    raw += available.y
                    scope.launch { offset.snapTo(rubberBand(raw)) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                dragging = false
                if (!refreshing) {
                    raw = 0f
                    offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        RefreshIndicator(
            progress = offset.value / threshold,
            holdProgress = hold.value,
            refreshing = refreshing,
            accent = accent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (offset.value - with(density) { 62.dp.toPx() }).roundToInt()) },
        )
        Box(Modifier.offset { IntOffset(0, offset.value.roundToInt()) }) {
            content()
        }
    }
}

/** How long the user has to keep holding past the threshold before it commits. */
const val HOLD_TO_CONFIRM_MS = 2_000f

@Composable
private fun RefreshIndicator(
    progress: Float,
    holdProgress: Float,
    refreshing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val spin by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 950,
        label = "refreshSpin",
    )
    val pull = progress.coerceIn(0f, 1f)
    val armed = progress >= 1f
    val holding = holdProgress > 0.01f
    // Everything below is driven by `pull`, so the puck is welded to the finger.
    val scale = 0.62f + pull * 0.38f + holdProgress * 0.06f
    val ringColor = if (armed) accent else NightbellColors.TextTertiary

    Column(
        modifier = modifier.graphicsLayer { alpha = (progress * 1.6f).coerceIn(0f, 1f) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(NightbellColors.ToastFill)
                .border(
                    1.dp,
                    NightbellColors.sheen(0.10f + holdProgress * 0.20f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(26.dp)) {
                val stroke = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                // Track — how far through the pull you are.
                drawArc(
                    color = ringColor.copy(alpha = if (holding) 0.22f else 0.85f),
                    startAngle = -90f,
                    sweepAngle = if (refreshing) 0f else pull * 300f,
                    useCenter = false,
                    style = stroke,
                )
                // Confirmation ring — closes over the two-second hold.
                if (holding) {
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = holdProgress * 360f,
                        useCenter = false,
                        style = Stroke(
                            width = 3.2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                }
                if (refreshing) {
                    rotate(spin) {
                        drawArc(
                            color = accent,
                            startAngle = -90f,
                            sweepAngle = 250f,
                            useCenter = false,
                            style = stroke,
                        )
                    }
                }
            }
            if (!refreshing) {
                Icon(
                    imageVector = NightbellIcons.ArrowLeft,
                    contentDescription = null,
                    tint = if (armed) accent else NightbellColors.TextTertiary,
                    modifier = Modifier
                        .size(13.dp)
                        // Points down while pulling, rotates to up as it arms:
                        // the glyph itself says "you've gone far enough".
                        .rotate(-90f + pull * 180f),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = when {
                refreshing -> "Checking everything…"
                holding -> "Keep holding…"
                armed -> "Hold to confirm"
                else -> "Pull to re-check"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (armed) accent else NightbellColors.TextTertiary,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------- helpers

/** Formats a millisecond duration the way a human would say it. */
fun formatLatency(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1_000 -> "$ms ms"
    else -> String.format("%.2f s", ms / 1000.0)
}

/**
 * What a latency chart actually says, in words.
 *
 * Every chart in the app used to carry a fixed string — "Response time trend" —
 * which tells a screen reader that a chart exists and nothing whatsoever about
 * the data in it. The shape of these graphs is the whole content, so the numbers
 * have to be spoken: latest, worst, and how many checks are behind them.
 *
 * The budget clause is part of that and not an extra. A sighted user gets the
 * threshold from a dashed line and the breaches from the bar colour, and both of
 * those are invisible to TalkBack, so without this the two audiences are reading
 * different charts.
 */
internal fun chartSummary(kind: String, samples: List<Sample>, sloMs: Int = 0): String {
    if (samples.isEmpty()) return "$kind, no checks yet"
    val ok = samples.filter { it.ok }
    val failed = samples.size - ok.size
    return buildString {
        append("$kind, ${samples.size} checks")
        samples.lastOrNull()?.let { append(", latest ${formatLatency(it.latencyMs)}") }
        if (ok.isNotEmpty()) append(", slowest ${formatLatency(ok.maxOf { it.latencyMs })}")
        if (failed > 0) append(", $failed failed")
        if (sloMs > 0) {
            append(", budget ${formatLatency(sloMs.toLong())}")
            val over = LatencyChart.overBudget(samples, sloMs)
            append(
                when {
                    ok.isEmpty() -> ", no answered checks to measure against it"
                    over == 0 -> ", none over budget"
                    else -> ", $over over budget"
                },
            )
        }
    }
}

/** The pass/fail strip, spoken as the count it encodes. */
internal fun outcomeSummary(samples: List<Sample>): String {
    if (samples.isEmpty()) return "No checks recorded yet"
    val passed = samples.count { it.ok }
    return "Recent checks, $passed of ${samples.size} passed"
}

/** "40m", "6h", "3d" — a duration, for saying what a figure covers. */
fun formatSpan(ms: Long): String = when {
    ms < 60_000 -> "under a minute"
    ms < 3_600_000 -> "${ms / 60_000}m"
    ms < 86_400_000 -> "${ms / 3_600_000}h"
    else -> "${ms / 86_400_000}d"
}

/**
 * "just now", "4m ago", "3h ago", "2d ago".
 *
 * From a composable, always pass [me.river.nightbell.ui.theme.LocalNowMs] rather
 * than letting [nowMs] default: the default is read once per composition and
 * then frozen, which is how the dashboard used to sit on "just now" for a full
 * fifteen-minute interval. The default is for the widget and the notifications,
 * which are rebuilt from scratch on every update and have no clock to read.
 */
fun formatRelative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return "never"
    val delta = abs(nowMs - epochMs)
    return when {
        delta < 45_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Aqua,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(NightbellColors.sheen(0.05f))
            .border(1.dp, NightbellColors.sheen(0.07f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
            color = NightbellColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
fun ProgressPips(total: Int, current: Int, modifier: Modifier = Modifier, accent: Color = NightbellColors.Aqua) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            val active = index <= current
            val width by animateFloatAsState(
                targetValue = if (index == current) 26f else 7f,
                animationSpec = spring(dampingRatio = 0.7f),
                label = "pip",
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(width.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (active) accent.copy(alpha = if (index == current) 1f else 0.5f)
                        else NightbellColors.sheen(0.12f),
                    ),
            )
        }
    }
}
