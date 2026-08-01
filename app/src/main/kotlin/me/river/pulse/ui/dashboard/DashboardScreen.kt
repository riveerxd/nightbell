package me.river.pulse.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.pulse.domain.Health
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorKind
import me.river.pulse.ui.components.AnimatedCounter
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.EmptyState
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.HistoryStrip
import me.river.pulse.ui.components.IconBadge
import me.river.pulse.ui.components.MetricTile
import me.river.pulse.ui.components.MicroTag
import me.river.pulse.ui.components.PullToRefreshLayout
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.Sparkline
import me.river.pulse.ui.components.StaggeredEntrance
import me.river.pulse.ui.components.rememberEntranceLog
import me.river.pulse.ui.components.StatusOrb
import me.river.pulse.ui.components.StatusPill
import me.river.pulse.ui.components.UptimeRing
import me.river.pulse.ui.components.formatLatency
import me.river.pulse.ui.components.formatRelative
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberDashboardViewModel
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.accentFor
import me.river.pulse.ui.theme.healthColor
import me.river.pulse.ui.theme.healthRim
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.river.pulse.ui.theme.rememberLoopingFloat
import androidx.compose.ui.platform.testTag

fun kindIcon(kind: MonitorKind) = when (kind) {
    MonitorKind.HTTP_STATUS -> PulseIcons.Server
    MonitorKind.ADVANCED_REQUEST -> PulseIcons.Braces
    MonitorKind.WEBSITE_ELEMENT -> PulseIcons.Pointer
}

@Composable
fun DashboardScreen(
    onAddMonitor: () -> Unit,
    onOpenMonitor: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onToast: (String) -> Unit,
) {
    val viewModel = rememberDashboardViewModel()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val entrance = rememberEntranceLog()

    val toast = viewModel.toast
    if (toast != null) {
        androidx.compose.runtime.LaunchedEffect(toast) {
            onToast(toast)
            viewModel.consumeToast()
        }
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshLayout(
            refreshing = viewModel.refreshing,
            onRefresh = { viewModel.checkAll() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("dashboard-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = topInset + 14.dp,
                    bottom = bottomInset + 128.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header") {
                    DashboardHeader(
                        total = cards.size,
                        down = cards.count { it.runtime.health == Health.DOWN },
                        onOpenSettings = onOpenSettings,
                    )
                }

                if (cards.isNotEmpty()) {
                    item(key = "overview") {
                        StaggeredEntrance(index = 0, key = "overview", log = entrance) {
                            OverviewCard(
                                cards = cards,
                                refreshing = viewModel.refreshing,
                                onCheckAll = { viewModel.checkAll() },
                            )
                        }
                    }
                }

                if (cards.isEmpty()) {
                    item(key = "empty") {
                        Spacer(Modifier.height(40.dp))
                        EmptyState(
                            title = "Nothing on the radar",
                            message = "Add your first monitor and Pulse will keep an eye on it " +
                                "— status codes, response bodies, or a single element on a page.",
                            action = {
                                PulseButton(
                                    text = "Create a monitor",
                                    onClick = onAddMonitor,
                                    icon = PulseIcons.Plus,
                                )
                            },
                        )
                    }
                } else {
                    items(cards, key = { it.monitor.id }) { card ->
                        val index = cards.indexOfFirst { it.monitor.id == card.monitor.id }
                        StaggeredEntrance(index = index + 1, key = card.monitor.id, log = entrance) {
                            MonitorRowCard(
                                card = card,
                                onOpen = { onOpenMonitor(card.monitor.id) },
                                onCheck = { viewModel.check(card.monitor.id) },
                                onToggle = { viewModel.setEnabled(card.monitor.id, it) },
                            )
                        }
                    }

                    item(key = "footer") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pull down to re-check everything",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextTertiary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }

        MorphingFab(
            onClick = onAddMonitor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = bottomInset + 26.dp),
        )
    }

    // Keep the list pinned to the top when a monitor is added.
    androidx.compose.runtime.LaunchedEffect(cards.size) {
        if (cards.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
}

// ------------------------------------------------------------------- sections

@Composable
private fun DashboardHeader(total: Int, down: Int, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseWordmark()
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    total == 0 -> "No monitors yet"
                    down == 0 -> "All $total systems operational"
                    down == 1 -> "1 of $total is down"
                    else -> "$down of $total are down"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (down > 0) PulseColors.Rose else PulseColors.TextSecondary,
            )
        }
        GlassIconButton(
            icon = PulseIcons.Sliders,
            onClick = onOpenSettings,
            contentDescription = "Settings",
            accent = PulseColors.TextSecondary,
        )
    }
}

@Composable
private fun PulseWordmark() {
    val sweep by rememberLoopingFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        durationMillis = 3_600,
        label = "sweep",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(26.dp)) {
            val h = size.height
            val w = size.width
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h * 0.55f)
                lineTo(w * 0.22f, h * 0.55f)
                lineTo(w * 0.36f, h * 0.2f)
                lineTo(w * 0.55f, h * 0.86f)
                lineTo(w * 0.7f, h * 0.45f)
                lineTo(w * 0.8f, h * 0.55f)
                lineTo(w, h * 0.55f)
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(
                    listOf(PulseColors.Aqua, PulseColors.Violet),
                ),
                style = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawCircle(
                color = PulseColors.Aqua,
                radius = 2.2.dp.toPx(),
                center = Offset(w * sweep, h * 0.55f),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = "Pulse",
            style = MaterialTheme.typography.displayMedium,
            color = PulseColors.TextPrimary,
        )
    }
}

@Composable
private fun OverviewCard(
    cards: List<MonitorCard>,
    refreshing: Boolean,
    onCheckAll: () -> Unit,
) {
    val allSamples = cards.flatMap { it.runtime.samples }
    val uptime = if (allSamples.isEmpty()) 100f else allSamples.count { it.ok } * 100f / allSamples.size
    val avgLatency = allSamples.filter { it.ok }.map { it.latencyMs }.average()
        .let { if (it.isNaN()) 0L else it.roundToInt().toLong() }
    val incidents = cards.count { it.runtime.health == Health.DOWN }
    val checked = cards.count { it.runtime.lastCheckedAt > 0 }

    GlassCard(accent = if (incidents > 0) PulseColors.Rose else Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UptimeRing(
                percent = uptime,
                modifier = Modifier.size(112.dp),
                accent = if (incidents > 0) PulseColors.Amber else PulseColors.Mint,
            )
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile(
                        label = "Tracked",
                        value = cards.size.toString(),
                        accent = PulseColors.Aqua,
                        icon = PulseIcons.Layers,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "Down",
                        value = incidents.toString(),
                        accent = if (incidents > 0) PulseColors.Rose else PulseColors.TextTertiary,
                        icon = PulseIcons.Warning,
                        modifier = Modifier.weight(1f),
                    )
                }
                MetricTile(
                    label = "Average response",
                    value = if (checked == 0) "—" else formatLatency(avgLatency),
                    accent = PulseColors.Violet,
                    icon = PulseIcons.Gauge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        PulseButton(
            text = if (refreshing) "Checking everything…" else "Check all now",
            onClick = onCheckAll,
            icon = PulseIcons.Radar,
            loading = refreshing,
            tone = ButtonTone.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ----------------------------------------------------------------- list items

@Composable
private fun MonitorRowCard(
    card: MonitorCard,
    onOpen: () -> Unit,
    onCheck: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val monitor = card.monitor
    val runtime = card.runtime
    val (accent, accentEnd) = accentFor(monitor.accent)
    val health = if (!monitor.enabled) Health.PAUSED else runtime.health
    val statusColor = if (card.checking) PulseColors.Aqua else healthColor(health)

    GlassCard(
        accent = healthRim(health),
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "${monitor.displayName}, ${health.label}, open details"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = kindIcon(monitor.kind),
                accent = accent,
                size = 42.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = monitor.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = PulseColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = monitor.prettyHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusOrb(health = health, checking = card.checking, size = 11.dp)
        }

        Spacer(Modifier.height(13.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(health = health, checking = card.checking)
            Spacer(Modifier.width(8.dp))
            if (runtime.lastLatencyMs > 0) {
                MicroTag(
                    text = formatLatency(runtime.lastLatencyMs),
                    color = PulseColors.TextSecondary,
                    icon = PulseIcons.Gauge,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (runtime.lastCode > 0) {
                MicroTag(
                    text = runtime.lastCode.toString(),
                    color = if (runtime.health == Health.UP) PulseColors.Mint else PulseColors.Amber,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatRelative(runtime.lastCheckedAt),
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
            )
        }

        if (runtime.samples.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // A single data point isn't a trend — the strip alone reads better.
            if (runtime.samples.size >= 2) {
                Sparkline(
                    samples = runtime.samples.takeLast(28),
                    accent = accentEnd,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            HistoryStrip(
                samples = runtime.samples.takeLast(40),
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(5.dp),
            )
        }

        AnimatedVisibility(
            visible = !runtime.ok(monitor.enabled) && runtime.lastMessage.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(11.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(PulseColors.Rose.copy(alpha = 0.10f))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        PulseIcons.Warning,
                        contentDescription = null,
                        tint = PulseColors.Rose,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = runtime.lastMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicroTag(text = monitor.kind.label, color = accent, icon = kindIcon(monitor.kind))
            if (monitor.kind != MonitorKind.WEBSITE_ELEMENT) {
                MicroTag(text = monitor.method.name, color = PulseColors.TextTertiary)
            }
            MicroTag(text = "${monitor.intervalMinutes}m", color = PulseColors.TextTertiary, icon = PulseIcons.Clock)
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                icon = if (monitor.enabled) PulseIcons.Pause else PulseIcons.Play,
                onClick = { onToggle(!monitor.enabled) },
                contentDescription = if (monitor.enabled) "Pause monitor" else "Resume monitor",
                size = 34.dp,
                accent = PulseColors.TextSecondary,
            )
            GlassIconButton(
                icon = PulseIcons.Refresh,
                onClick = onCheck,
                contentDescription = "Check now",
                size = 34.dp,
                accent = accent,
                enabled = !card.checking,
            )
        }
    }
}

private fun me.river.pulse.domain.MonitorRuntime.ok(enabled: Boolean): Boolean =
    !enabled || health == Health.UP || health == Health.UNKNOWN || health == Health.DEGRADED

// ------------------------------------------------------------------------ fab

/**
 * The plus button: a spring-loaded press and a quarter-turn morph as it hands
 * off to the setup flow.
 */
@Composable
fun MorphingFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var launching by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            launching -> 1.28f
            pressed -> 0.9f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "fabScale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (launching) 135f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "fabRotate",
    )
    androidx.compose.runtime.LaunchedEffect(launching) {
        if (launching) {
            delay(140)
            onClick()
            delay(280)
            launching = false
        }
    }

    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        // One faint pool of light so the button doesn't float on nothing. The
        // pulsing halo that used to live here read as decoration, not affordance.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(PulseColors.Aqua.copy(alpha = 0.10f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = center,
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(PulseColors.Aqua, PulseColors.Indigo, PulseColors.Violet),
                    ),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, color = Color.White),
                ) { if (!launching) launching = true }
                .semantics { contentDescription = "Add a monitor" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PulseIcons.Plus,
                contentDescription = null,
                tint = PulseColors.Void,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
fun DashboardCountBadge(count: Int, accent: Color = PulseColors.Aqua) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedCounter(
            value = count,
            color = accent,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
        )
    }
}
