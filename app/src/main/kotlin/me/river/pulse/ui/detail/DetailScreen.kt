package me.river.pulse.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Sample
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.EmptyState
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassDivider
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.IconBadge
import me.river.pulse.ui.components.LatencyBars
import me.river.pulse.ui.components.MetricTile
import me.river.pulse.ui.components.MicroTag
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.StaggeredEntrance
import me.river.pulse.ui.components.rememberEntranceLog
import me.river.pulse.ui.components.StatusOrb
import me.river.pulse.ui.components.UptimeRing
import me.river.pulse.ui.components.formatLatency
import me.river.pulse.ui.components.formatRelative
import me.river.pulse.ui.dashboard.kindIcon
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberDetailViewModel
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.accentFor
import me.river.pulse.ui.theme.healthColor
import me.river.pulse.ui.theme.healthRim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.testTag

@Composable
fun DetailScreen(
    monitorId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val viewModel = rememberDetailViewModel(monitorId)
    val card by viewModel.card.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
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

    val current = card
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "Monitor not found",
                message = "It may have been deleted from another screen.",
                icon = PulseIcons.Warning,
                accent = PulseColors.Amber,
                action = { PulseButton("Back to dashboard", onBack, icon = PulseIcons.ArrowLeft) },
            )
        }
        return
    }

    val monitor = current.monitor
    val runtime = current.runtime
    val (accent, accentEnd) = accentFor(monitor.accent)
    val health = if (!monitor.enabled) Health.PAUSED else runtime.health

    LazyColumn(
        Modifier.fillMaxSize().testTag("detail-list"),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = topInset + 12.dp,
            bottom = bottomInset + 36.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassIconButton(
                    icon = PulseIcons.ArrowLeft,
                    onClick = onBack,
                    contentDescription = "Back",
                    accent = PulseColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = monitor.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = PulseColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = monitor.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                GlassIconButton(
                    icon = PulseIcons.Pencil,
                    onClick = { onEdit(monitor.id) },
                    contentDescription = "Edit monitor",
                    accent = accent,
                    size = 38.dp,
                )
            }
        }

        if (monitor.urgent && runtime.urgentState.nagging) {
            item(key = "urgent") {
                UrgentBanner(
                    repeatMinutes = monitor.urgentRepeatMinutes,
                    onAcknowledge = viewModel::acknowledgeUrgent,
                )
            }
        }

        item(key = "hero") {
            StaggeredEntrance(index = 0, key = "hero-${monitor.id}", log = entrance) {
                HeroCard(monitor, runtime, health, current.checking, accent, accentEnd)
            }
        }

        item(key = "actions") {
            StaggeredEntrance(index = 1, key = "actions-${monitor.id}", log = entrance) {
                ActionsRow(
                    enabled = monitor.enabled,
                    busy = viewModel.busy || current.checking,
                    muted = runtime.mutedUntil > System.currentTimeMillis(),
                    accent = accent,
                    onCheck = viewModel::checkNow,
                    onToggle = { viewModel.setEnabled(!monitor.enabled) },
                    onMute = { viewModel.mute(1) },
                    onUnmute = viewModel::unmute,
                )
            }
        }

        if (runtime.samples.isNotEmpty()) {
            item(key = "chart") {
                StaggeredEntrance(index = 2, key = "chart-${monitor.id}", log = entrance) {
                    GlassCard {
                        SectionHeader("Response time", icon = PulseIcons.Chart, accent = accentEnd)
                        LatencyBars(
                            samples = runtime.samples.takeLast(40),
                            accent = accentEnd,
                            modifier = Modifier.fillMaxWidth().height(112.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricTile(
                                label = "Average",
                                value = formatLatency(runtime.averageLatencyMs),
                                accent = accentEnd,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "p95",
                                value = formatLatency(runtime.p95LatencyMs),
                                accent = PulseColors.Violet,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Checks",
                                value = runtime.samples.size.toString(),
                                accent = PulseColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        item(key = "config") {
            StaggeredEntrance(index = 3, key = "config-${monitor.id}", log = entrance) {
                ConfigCard(monitor, accent)
            }
        }

        item(key = "events-header") {
            SectionHeader("Recent checks", icon = PulseIcons.History, accent = accent)
        }

        if (runtime.samples.isEmpty()) {
            item(key = "no-events") {
                GlassCard(contentPadding = 20.dp) {
                    Text(
                        text = "No checks recorded yet. Run one now to start the history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PulseColors.TextTertiary,
                    )
                }
            }
        } else {
            val recent = runtime.samples.asReversed().take(24)
            item(key = "events") {
                GlassCard(contentPadding = 6.dp) {
                    recent.forEachIndexed { index, sample ->
                        EventRow(sample)
                        if (index < recent.lastIndex) {
                            GlassDivider(Modifier.padding(horizontal = 12.dp), alpha = 0.06f)
                        }
                    }
                }
            }
        }

        item(key = "danger") {
            Spacer(Modifier.height(6.dp))
            AnimatedVisibility(
                visible = !confirmDelete,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                PulseButton(
                    text = "Delete monitor",
                    onClick = { confirmDelete = true },
                    icon = PulseIcons.Trash,
                    tone = ButtonTone.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(
                visible = confirmDelete,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                GlassCard(accent = PulseColors.Rose) {
                    Text(
                        text = "Delete “${monitor.displayName}”?",
                        style = MaterialTheme.typography.titleMedium,
                        color = PulseColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Its history and scheduled checks go with it. This can't be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PulseButton(
                            text = "Keep it",
                            onClick = { confirmDelete = false },
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        PulseButton(
                            text = "Delete",
                            onClick = { viewModel.delete(onBack) },
                            tone = ButtonTone.Danger,
                            icon = PulseIcons.Trash,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    monitor: Monitor,
    runtime: MonitorRuntime,
    health: Health,
    checking: Boolean,
    accent: Color,
    accentEnd: Color,
) {
    GlassCard(accent = healthRim(health), contentPadding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UptimeRing(
                percent = runtime.uptimePercent,
                modifier = Modifier.size(124.dp),
                accent = healthColor(health),
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusOrb(health = health, checking = checking, size = 12.dp)
                    Text(
                        text = if (checking) "Checking…" else health.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = healthColor(health),
                    )
                }
                Text(
                    text = runtime.lastMessage.ifBlank {
                        when {
                            runtime.lastCheckedAt <= 0 -> "Not checked yet"
                            // DEGRADED is a pass, so lastMessage is empty — spell
                            // out why the card is amber rather than green.
                            health == Health.DEGRADED ->
                                "Responded in ${formatLatency(runtime.lastLatencyMs)} — " +
                                    "over its latency budget"
                            else -> "Last response ${formatLatency(runtime.lastLatencyMs)}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MicroTag(monitor.kind.label, color = accent, icon = kindIcon(monitor.kind))
                    if (runtime.lastCode > 0) {
                        MicroTag(runtime.lastCode.toString(), color = accentEnd)
                    }
                }
                Text(
                    text = "Checked ${formatRelative(runtime.lastCheckedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
        }
        if (runtime.lastDetail.isNotBlank() && health == Health.DOWN) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PulseColors.Rose.copy(alpha = 0.09f))
                    .padding(13.dp),
            ) {
                Text(
                    text = runtime.lastDetail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = PulseColors.TextSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActionsRow(
    enabled: Boolean,
    busy: Boolean,
    muted: Boolean,
    accent: Color,
    onCheck: () -> Unit,
    onToggle: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseButton(
            text = if (busy) "Checking…" else "Check now",
            onClick = onCheck,
            loading = busy,
            icon = PulseIcons.Refresh,
            accent = accent,
            modifier = Modifier.weight(1f),
        )
        PulseButton(
            text = if (enabled) "Pause" else "Resume",
            onClick = onToggle,
            icon = if (enabled) PulseIcons.Pause else PulseIcons.Play,
            tone = ButtonTone.Secondary,
        )
        PulseButton(
            text = if (muted) "Un-mute" else "Mute 1h",
            onClick = if (muted) onUnmute else onMute,
            icon = if (muted) PulseIcons.Bell else PulseIcons.BellOff,
            tone = ButtonTone.Secondary,
        )
    }
}

/**
 * Sits above the hero card because it is the only thing on this screen that
 * needs an action rather than a read.
 */
@Composable
private fun UrgentBanner(repeatMinutes: Int, onAcknowledge: () -> Unit) {
    GlassCard(accent = PulseColors.Rose, contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(PulseIcons.Zap, PulseColors.Rose, size = 40.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Urgent alert active",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.Rose,
                )
                Text(
                    text = "Repeating every $repeatMinutes min until acknowledged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        PulseButton(
            text = "I've got it — acknowledge",
            onClick = onAcknowledge,
            icon = PulseIcons.Check,
            tone = ButtonTone.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The monitor stays down until it recovers. Acknowledging only stops " +
                "the repeats for this outage — the next one will shout again.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextTertiary,
        )
    }
}

@Composable
private fun ConfigCard(monitor: Monitor, accent: Color) {
    GlassCard {
        SectionHeader("Configuration", icon = PulseIcons.Sliders, accent = accent)
        ConfigRow("Type", monitor.kind.label)
        if (monitor.kind != MonitorKind.WEBSITE_ELEMENT) {
            ConfigRow("Method", monitor.method.name)
            ConfigRow("Expected status", monitor.status.summary)
            if (monitor.assertion.isActive) {
                ConfigRow("Body check", "${monitor.assertion.mode.label} · ${monitor.assertion.value}")
            }
            if (monitor.assertion.jsonPath.isNotBlank()) {
                ConfigRow("JSON path", monitor.assertion.jsonPath)
            }
            if (monitor.headers.isNotEmpty()) {
                ConfigRow("Headers", monitor.headers.joinToString(", ") { it.name })
            }
            if (monitor.body.isNotBlank()) {
                ConfigRow("Body", "${monitor.body.length} chars · ${monitor.contentType}")
            }
        } else {
            val elements = monitor.targets
            ConfigRow("Watching", "${elements.size} element${if (elements.size == 1) "" else "s"} on one page load")
            elements.forEachIndexed { index, element ->
                val prefix = if (elements.size == 1) "Element" else "Element ${index + 1}"
                ConfigRow(prefix, "${element.displayLabel} · ${element.mode.label}")
                ConfigRow("  Selector", element.displaySelector)
                if (element.expectedText.isNotBlank()) {
                    ConfigRow("  Expected", element.expectedText)
                }
                if (element.attribute.isNotBlank()) {
                    ConfigRow("  Attribute", element.attribute)
                }
            }
        }
        ConfigRow("Interval", "every ${monitor.intervalMinutes} min")
        ConfigRow("Timeout", "${monitor.timeoutSeconds}s")
        ConfigRow(
            "Latency budget",
            if (monitor.latencySloMs > 0) "${monitor.latencySloMs} ms" else "Global default",
        )
        if (monitor.urgent) {
            ConfigRow("Urgent", "repeats every ${monitor.urgentRepeatMinutes} min until acknowledged")
        }
        ConfigRow(
            "Alerts",
            if (monitor.useGlobalAlerts) "Global policy" else monitor.alert.summary,
        )
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextTertiary,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

@Composable
private fun EventRow(sample: Sample) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = if (sample.ok) PulseIcons.Check else PulseIcons.Warning,
            accent = if (sample.ok) PulseColors.Mint else PulseColors.Rose,
            size = 28.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (sample.ok) {
                    "Healthy${if (sample.code > 0) " · ${sample.code}" else ""}"
                } else {
                    sample.note.ifBlank { "Failed" }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (sample.ok) PulseColors.TextSecondary else PulseColors.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${dateFormat.format(Date(sample.at))} · ${timeFormat.format(Date(sample.at))}",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatLatency(sample.latencyMs),
            style = MaterialTheme.typography.labelLarge,
            color = PulseColors.TextSecondary,
        )
    }
}
