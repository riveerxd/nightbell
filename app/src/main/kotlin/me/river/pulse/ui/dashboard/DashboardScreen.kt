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
import me.river.pulse.ui.components.MicroTag
import me.river.pulse.ui.components.PullToRefreshLayout
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SAMPLE_WINDOW
import me.river.pulse.ui.components.Sparkline
import me.river.pulse.ui.components.StaggeredEntrance
import me.river.pulse.ui.components.rememberEntranceLog
import me.river.pulse.ui.components.rememberFavicon
import me.river.pulse.ui.components.StatusOrb
import me.river.pulse.ui.components.StatusPill
import me.river.pulse.ui.components.formatLatency
import me.river.pulse.ui.components.formatRelative
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberDashboardViewModel
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.accentFor
import me.river.pulse.ui.theme.healthColor
import me.river.pulse.ui.theme.healthRim
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
    val offline by viewModel.offline.collectAsStateWithLifecycle()
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
                    DashboardHeader(onOpenSettings = onOpenSettings)
                }

                item(key = "overview") {
                    StaggeredEntrance(index = 0, key = "overview", log = entrance) {
                        FleetBanner(
                            stats = fleetStatsOf(cards, offline = offline),
                            refreshing = viewModel.refreshing,
                            onCheckAll = { viewModel.checkAll() },
                        )
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
                                onAcknowledge = { viewModel.acknowledgeUrgent(card.monitor.id) },
                            )
                        }
                    }

                    item(key = "footer") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Pull down and hold to re-check everything",
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

/**
 * Identity only. The fleet's verdict used to live here as a subtitle; it is now
 * the [FleetBanner] directly below, which can say it far louder.
 */
@Composable
private fun DashboardHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseWordmark()
        Spacer(Modifier.weight(1f))
        GlassIconButton(
            icon = PulseIcons.Sliders,
            onClick = onOpenSettings,
            contentDescription = "Settings",
            size = 34.dp,
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
        Canvas(Modifier.size(18.dp)) {
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
                style = Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawCircle(
                color = PulseColors.Aqua,
                radius = 1.8.dp.toPx(),
                center = Offset(w * sweep, h * 0.55f),
            )
        }
        Spacer(Modifier.width(8.dp))
        // Small and tracked-out: the banner underneath is the loud thing now, and
        // two competing headlines at the top of one screen is one too many.
        Mono(
            text = "PULSE",
            color = PulseColors.TextSecondary,
            size = 11,
            weight = androidx.compose.ui.text.font.FontWeight.Bold,
            tracking = 3.0,
            spoken = "Pulse",
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
    onAcknowledge: () -> Unit,
) {
    val monitor = card.monitor
    val runtime = card.runtime
    val (accent, accentEnd) = accentFor(monitor.accent)
    val health = if (!monitor.enabled) Health.PAUSED else runtime.health
    val muted = runtime.mutedUntil > System.currentTimeMillis()
    val urgentPending = monitor.urgent && runtime.urgentState.nagging

    GlassCard(
        // A muted monitor is a decision, not an emergency. Red is reserved for
        // "this needs you now"; once you've snoozed it the rim goes amber so
        // the card still stands out without competing with a live outage.
        accent = when {
            muted && healthRim(health) != Color.Transparent -> PulseColors.Amber
            else -> healthRim(health)
        },
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(monitor.displayName)
                    append(", ")
                    append(health.label)
                    if (muted) append(", muted")
                    if (urgentPending) append(", urgent, not acknowledged")
                    append(", open details")
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A page-element monitor *is* the page it watches, so the site's own
            // mark identifies it far faster than one more identical cursor glyph
            // down a list. Only for that kind: an API endpoint has no favicon
            // worth showing, and the kind icon is the useful signal there.
            val favicon = rememberFavicon(
                pageUrl = monitor.url,
                enabled = monitor.kind == MonitorKind.WEBSITE_ELEMENT,
            )
            IconBadge(
                icon = kindIcon(monitor.kind),
                accent = accent,
                size = 42.dp,
                image = favicon,
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
            if (muted) {
                MicroTag(
                    text = "Muted",
                    color = PulseColors.Amber,
                    background = PulseColors.Amber.copy(alpha = 0.14f),
                    icon = PulseIcons.BellOff,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (urgentPending) {
                MicroTag(
                    text = "Urgent",
                    color = PulseColors.Rose,
                    background = PulseColors.Rose.copy(alpha = 0.16f),
                    icon = PulseIcons.Zap,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (runtime.lastLatencyMs > 0) {
                MicroTag(
                    text = formatLatency(runtime.lastLatencyMs),
                    color = if (health == Health.DEGRADED) PulseColors.Amber else PulseColors.TextSecondary,
                    icon = PulseIcons.Gauge,
                )
                Spacer(Modifier.width(6.dp))
            }
            // Says why a number that looks slow was not treated as slow. Without
            // this the compensation is invisible, and an invisible correction to a
            // number the user is reading is indistinguishable from a bug.
            if (runtime.lastLatencySuspect) {
                MicroTag(
                    text = "connection",
                    color = PulseColors.Sky,
                    background = PulseColors.Sky.copy(alpha = 0.14f),
                    icon = PulseIcons.Wifi,
                )
                Spacer(Modifier.width(6.dp))
            } else if (runtime.lastNetworkExcessMs > 0) {
                MicroTag(
                    text = "−${formatLatency(runtime.lastNetworkExcessMs)}",
                    color = PulseColors.Sky,
                    icon = PulseIcons.Wifi,
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
            // One list, both charts. They are stacked and read as a single
            // figure, so a failure has to appear at the same x in each; windowing
            // them separately is what put a red tick under a blue line.
            val history = runtime.samples.takeLast(SAMPLE_WINDOW)
            Spacer(Modifier.height(12.dp))
            // A single data point isn't a trend — the strip alone reads better.
            if (history.size >= 2) {
                Sparkline(
                    samples = history,
                    accent = accentEnd,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            HistoryStrip(
                samples = history,
                accent = accent,
                modifier = Modifier.fillMaxWidth().height(5.dp),
            )
        }

        AnimatedVisibility(
            visible = !runtime.ok(monitor.enabled) && runtime.lastMessage.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val tone = if (muted) PulseColors.Amber else PulseColors.Rose
            Column {
                Spacer(Modifier.height(11.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(tone.copy(alpha = 0.10f))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (muted) PulseIcons.BellOff else PulseIcons.Warning,
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (muted) {
                            "${runtime.lastMessage} · muted, no alerts"
                        } else {
                            runtime.lastMessage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(visible = urgentPending, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(11.dp))
                PulseButton(
                    text = "Acknowledge urgent alert",
                    onClick = onAcknowledge,
                    icon = PulseIcons.Check,
                    tone = ButtonTone.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicroTag(text = monitor.kind.label, color = accent, icon = kindIcon(monitor.kind))
            if (monitor.kind == MonitorKind.WEBSITE_ELEMENT && monitor.targets.size > 1) {
                MicroTag(
                    text = "${monitor.targets.size} elements",
                    color = PulseColors.TextTertiary,
                    icon = PulseIcons.Target,
                )
            }
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
