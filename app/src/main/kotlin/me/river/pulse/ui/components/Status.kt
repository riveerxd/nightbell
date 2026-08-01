package me.river.pulse.ui.components

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.pulse.domain.Health
import me.river.pulse.domain.Sample
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.LocalPulseMotion
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseRadii
import me.river.pulse.ui.theme.healthColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import me.river.pulse.ui.theme.rememberLoopingFloat

// ------------------------------------------------------------------- counters

/** Number that rolls when it changes — used for latency, uptime, counts. */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
    prefix: String = "",
    color: Color = PulseColors.TextPrimary,
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
    val motion = LocalPulseMotion.current
    val color = if (checking) PulseColors.Aqua else healthColor(health)
    val ping by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 1f,
        durationMillis = 1_800,
        label = "ping",
    )
    val alive = checking || health == Health.DOWN

    Canvas(
        modifier
            .size(size * 3)
            .semantics { contentDescription = if (checking) "Checking" else health.label },
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val core = size.toPx() / 2f

        // Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.42f), Color.Transparent),
                center = center,
                radius = core * 3.6f,
            ),
            radius = core * 3.6f,
            center = center,
        )
        if (alive && motion.enabled) {
            listOf(0f, 0.5f).forEach { phase ->
                val p = (ping + phase) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.5f),
                    radius = core * (1f + p * 2.4f),
                    center = center,
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
        }
        drawCircle(color = color, radius = core, center = center)
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
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
    val color = if (checking) PulseColors.Aqua else healthColor(health)
    val label = if (checking) "Checking" else health.label
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PulseRadii.chip))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(PulseRadii.chip))
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
 * Latency sparkline with a gradient underfill. Failures are punched out as
 * rose-coloured markers so an outage is visible at a glance.
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Aqua,
    animate: Boolean = true,
) {
    val motion = LocalPulseMotion.current
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

    Canvas(modifier.semantics { contentDescription = "Response time trend" }) {
        val w = size.width
        val h = size.height
        val stepX = if (samples.size <= 1) w else w / (samples.size - 1)
        fun pointAt(index: Int): Offset {
            val sample = samples[index]
            val normalized = (sample.latencyMs.toFloat() / maxLatency).coerceIn(0f, 1f)
            return Offset(index * stepX, h - (normalized * (h * 0.78f)) - h * 0.11f)
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
        drawPath(
            path = line,
            brush = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.85f)),
            ),
            style = Stroke(width = 2.2.dp.toPx()),
        )

        samples.forEachIndexed { index, sample ->
            if (index >= visibleCount) return@forEachIndexed
            if (!sample.ok) {
                val point = pointAt(index)
                drawCircle(PulseColors.Rose.copy(alpha = 0.28f), radius = 5.dp.toPx(), center = point)
                drawCircle(PulseColors.Rose, radius = 2.4.dp.toPx(), center = point)
            }
        }
        // Leading dot
        val head = pointAt(min(visibleCount, samples.size) - 1)
        drawCircle(accent.copy(alpha = 0.3f), radius = 6.dp.toPx(), center = head)
        drawCircle(Color.White, radius = 2.6.dp.toPx(), center = head)
    }
}

/** Bar chart of the last N latencies, used on the detail screen. */
@Composable
fun LatencyBars(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Aqua,
) {
    val motion = LocalPulseMotion.current
    val grow = remember(samples.size) { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(samples.size) {
        if (motion.enabled) grow.animateTo(1f, tween(620, easing = EaseOutCubic))
    }
    if (samples.isEmpty()) {
        Box(modifier)
        return
    }
    val maxLatency = max(1L, samples.maxOf { it.latencyMs })
    Canvas(modifier.semantics { contentDescription = "Response time history" }) {
        val count = samples.size
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)
        samples.forEachIndexed { index, sample ->
            val ratio = (sample.latencyMs.toFloat() / maxLatency).coerceIn(0.04f, 1f) * grow.value
            val barHeight = size.height * ratio
            val x = index * (barWidth + gap)
            val color = if (sample.ok) accent else PulseColors.Rose
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.28f)),
                ),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2.2f),
            )
        }
    }
}

/** Big animated uptime dial. */
@Composable
fun UptimeRing(
    percent: Float,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Mint,
    label: String = "uptime",
) {
    val motion = LocalPulseMotion.current
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 100f),
        animationSpec = if (motion.enabled) {
            tween(900, easing = FastOutSlowInEasing)
        } else {
            spring(stiffness = Spring.StiffnessHigh)
        },
        label = "uptime",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = Color.White.copy(alpha = 0.07f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.4f), accent, PulseColors.Aqua, accent),
                ),
                startAngle = 135f,
                sweepAngle = 270f * (animated / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${animated.roundToInt()}%",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = PulseColors.TextPrimary,
            )
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.TextTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Slim horizontal strip of pass/fail ticks — a compact outage history. */
@Composable
fun HistoryStrip(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Mint,
) {
    Canvas(modifier.semantics { contentDescription = "Recent check outcomes" }) {
        if (samples.isEmpty()) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.05f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            return@Canvas
        }
        val count = samples.size
        val gap = 2.dp.toPx()
        val tickWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.2f)
        samples.forEachIndexed { index, sample ->
            drawRoundRect(
                color = if (sample.ok) accent.copy(alpha = 0.85f) else PulseColors.Rose,
                topLeft = Offset(index * (tickWidth + gap), 0f),
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
    icon: ImageVector = PulseIcons.Radar,
    accent: Color = PulseColors.Aqua,
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
                        listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                    center = center,
                )
                listOf(0.42f, 0.62f, 0.82f).forEachIndexed { i, factor ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.09f - i * 0.02f),
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
                            listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent),
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
            color = PulseColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = PulseColors.TextSecondary,
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
 * Bespoke pull-to-refresh. Rolling our own keeps the indicator on-brand (a glass
 * puck with a sweeping arc) and avoids depending on an experimental API.
 */
@Composable
fun PullToRefreshLayout(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Aqua,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 78.dp.toPx() }
    val maxPull = threshold * 1.7f
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshing) {
        if (refreshing) {
            offset.animateTo(threshold * 0.72f, spring(dampingRatio = 0.7f))
        } else {
            offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
        }
    }

    val connection = remember(refreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && offset.value > 0f) {
                    val consumed = min(-available.y, offset.value)
                    scope.launch { offset.snapTo(offset.value - consumed) }
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput && !refreshing) {
                    val resistance = 1f - (offset.value / maxPull).coerceIn(0f, 0.85f)
                    val next = (offset.value + available.y * 0.55f * resistance).coerceAtMost(maxPull)
                    scope.launch { offset.snapTo(next) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!refreshing && offset.value >= threshold) {
                    onRefresh()
                } else if (!refreshing) {
                    offset.animateTo(0f, spring(dampingRatio = 0.7f))
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        RefreshIndicator(
            progress = (offset.value / threshold).coerceIn(0f, 1.4f),
            refreshing = refreshing,
            accent = accent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (offset.value - with(density) { 54.dp.toPx() }).roundToInt()) },
        )
        Box(Modifier.offset { IntOffset(0, offset.value.roundToInt()) }) {
            content()
        }
    }
}

@Composable
private fun RefreshIndicator(
    progress: Float,
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
    val armed = progress >= 1f
    val scale = (0.55f + progress * 0.45f).coerceIn(0.55f, 1.05f)

    Box(
        modifier = modifier
            .size(46.dp)
            .graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(22.dp)
                .rotate(if (refreshing) spin else progress * 220f),
        ) {
            drawArc(
                color = if (armed || refreshing) accent else PulseColors.TextTertiary,
                startAngle = -90f,
                sweepAngle = if (refreshing) 250f else (progress.coerceAtMost(1f) * 300f),
                useCenter = false,
                style = Stroke(width = 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

// ------------------------------------------------------------------- helpers

/** Formats a millisecond duration the way a human would say it. */
fun formatLatency(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1_000 -> "$ms ms"
    else -> String.format("%.2f s", ms / 1000.0)
}

/** "just now", "4m ago", "3h ago", "2d ago". */
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
    accent: Color = PulseColors.Aqua,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
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
                color = PulseColors.TextTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
            color = PulseColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
fun ProgressPips(total: Int, current: Int, modifier: Modifier = Modifier, accent: Color = PulseColors.Aqua) {
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
                        else Color.White.copy(alpha = 0.12f),
                    ),
            )
        }
    }
}
