package me.river.nightbell.ui.detail

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
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import me.river.nightbell.domain.CertificateWatch
import me.river.nightbell.domain.DigestMode
import me.river.nightbell.domain.GitHubState
import me.river.nightbell.domain.githubInstantMs
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.UptimeWindows
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.EmptyState
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassDivider
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.LatencyBars
import me.river.nightbell.ui.components.MetricTile
import me.river.nightbell.ui.components.MicroTag
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.StaggeredEntrance
import me.river.nightbell.ui.components.rememberEntranceLog
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.UptimeRing
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.components.formatSpan
import me.river.nightbell.ui.dashboard.kindIcon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.rememberDetailViewModel
import me.river.nightbell.ui.theme.LocalNowMs
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.accentFor
import me.river.nightbell.ui.theme.readableContentPadding
import me.river.nightbell.ui.theme.healthColor
import me.river.nightbell.ui.theme.healthRim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.testTag

/** How many checks the history shows before it asks whether you want the rest. */
private const val EVENT_PREVIEW = 24

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
    var showAllChecks by remember { mutableStateOf(false) }
    val entrance = rememberEntranceLog()
    val context = LocalContext.current

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
                icon = NightbellIcons.Warning,
                accent = NightbellColors.Amber,
                action = { NightbellButton("Back to dashboard", onBack, icon = NightbellIcons.ArrowLeft) },
            )
        }
        return
    }

    val monitor = current.monitor
    val runtime = current.runtime
    val (accent, accentEnd) = accentFor(monitor.accent)
    val health = if (!monitor.enabled) Health.PAUSED else runtime.health
    val now = LocalNowMs.current

    LazyColumn(
        Modifier.fillMaxSize().testTag("detail-list"),
        contentPadding = readableContentPadding(
            top = topInset + 12.dp,
            bottom = bottomInset + 36.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassIconButton(
                    icon = NightbellIcons.ArrowLeft,
                    onClick = onBack,
                    contentDescription = "Back",
                    accent = NightbellColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = monitor.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = NightbellColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = monitor.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                GlassIconButton(
                    icon = NightbellIcons.Pencil,
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
                HeroCard(monitor, runtime, health, current.checking, accent, accentEnd, now)
            }
        }

        item(key = "actions") {
            StaggeredEntrance(index = 1, key = "actions-${monitor.id}", log = entrance) {
                ActionsRow(
                    enabled = monitor.enabled,
                    busy = viewModel.busy || current.checking,
                    muted = runtime.mutedUntil > now,
                    accent = accent,
                    onCheck = viewModel::checkNow,
                    onToggle = { viewModel.setEnabled(!monitor.enabled) },
                    onMute = { viewModel.mute(1) },
                    onUnmute = viewModel::unmute,
                )
            }
        }

        if (monitor.kind == MonitorKind.GITHUB_REPO) {
            item(key = "github") {
                StaggeredEntrance(index = 2, key = "github-${monitor.id}", log = entrance) {
                    GitHubHealthCard(
                        monitor = monitor,
                        state = runtime.github,
                        nowMs = now,
                        accent = accent,
                        onOpen = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        onMarkSeen = viewModel::markGitHubSeen,
                        onMute = { viewModel.mute(24) },
                    )
                }
            }
        }

        if (runtime.samples.isNotEmpty()) {
            item(key = "chart") {
                StaggeredEntrance(index = 2, key = "chart-${monitor.id}", log = entrance) {
                    GlassCard {
                        SectionHeader("Response time", icon = NightbellIcons.Chart, accent = accentEnd)
                        LatencyBars(
                            samples = runtime.samples.takeLast(40),
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
                                accent = NightbellColors.Violet,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Checks",
                                value = runtime.samples.size.toString(),
                                accent = NightbellColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (runtime.certExpiresAt > 0L) {
            item(key = "cert") {
                StaggeredEntrance(index = 3, key = "cert-${monitor.id}", log = entrance) {
                    CertificateCard(runtime, now)
                }
            }
        }

        item(key = "config") {
            StaggeredEntrance(index = 4, key = "config-${monitor.id}", log = entrance) {
                ConfigCard(monitor, accent)
            }
        }

        item(key = "events-header") {
            SectionHeader("Recent checks", icon = NightbellIcons.History, accent = accent)
        }

        if (runtime.samples.isEmpty()) {
            item(key = "no-events") {
                GlassCard(contentPadding = 20.dp) {
                    Text(
                        text = "No checks recorded yet. Run one now to start the history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        } else {
            val all = runtime.samples.asReversed()
            // The list is capped at a readable length, but the rest is *stored* —
            // it was simply unreachable, which is a strange thing to do to the
            // history someone came to this screen to read.
            val recent = if (showAllChecks) all else all.take(EVENT_PREVIEW)
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
            if (all.size > EVENT_PREVIEW) {
                item(key = "events-more") {
                    NightbellButton(
                        text = if (showAllChecks) {
                            "Show fewer"
                        } else {
                            "Show all ${all.size} checks"
                        },
                        onClick = { showAllChecks = !showAllChecks },
                        icon = if (showAllChecks) NightbellIcons.ChevronUp else NightbellIcons.History,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                NightbellButton(
                    text = "Delete monitor",
                    onClick = { confirmDelete = true },
                    icon = NightbellIcons.Trash,
                    tone = ButtonTone.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(
                visible = confirmDelete,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                GlassCard(accent = NightbellColors.Rose) {
                    Text(
                        text = "Delete “${monitor.displayName}”?",
                        style = MaterialTheme.typography.titleMedium,
                        color = NightbellColors.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Its history and scheduled checks go with it. This can't be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NightbellButton(
                            text = "Keep it",
                            onClick = { confirmDelete = false },
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        NightbellButton(
                            text = "Delete",
                            onClick = { viewModel.delete(onBack) },
                            tone = ButtonTone.Danger,
                            icon = NightbellIcons.Trash,
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
    nowMs: Long,
) {
    GlassCard(accent = healthRim(health), contentPadding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The ring used to show passing-checks-over-retained-checks under the
            // word UPTIME, which is a different quantity wearing the name of the
            // one people read it as. It now reports a real day, and when there is
            // not yet a day of history it says how far back it can actually see
            // instead of quietly reporting a shorter window as if it were one.
            val window = runtime.uptimeWithin(nowMs, UptimeWindows.DAY_MS)
            UptimeRing(
                percent = window?.percent ?: 0f,
                modifier = Modifier.size(124.dp),
                accent = healthColor(health),
                label = UptimeWindows.ringLabel(window),
                unknown = window == null,
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
                    color = NightbellColors.TextSecondary,
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
                    text = "Checked ${formatRelative(runtime.lastCheckedAt, LocalNowMs.current)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }
        if (runtime.lastDetail.isNotBlank() && health == Health.DOWN) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightbellColors.Rose.copy(alpha = 0.09f))
                    .padding(13.dp),
            ) {
                Text(
                    text = runtime.lastDetail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = NightbellColors.TextSecondary,
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
        NightbellButton(
            text = if (busy) "Checking…" else "Check now",
            onClick = onCheck,
            loading = busy,
            icon = NightbellIcons.Refresh,
            accent = accent,
            modifier = Modifier.weight(1f),
        )
        NightbellButton(
            text = if (enabled) "Pause" else "Resume",
            onClick = onToggle,
            icon = if (enabled) NightbellIcons.Pause else NightbellIcons.Play,
            tone = ButtonTone.Secondary,
        )
        NightbellButton(
            text = if (muted) "Un-mute" else "Mute 1h",
            onClick = if (muted) onUnmute else onMute,
            icon = if (muted) NightbellIcons.Bell else NightbellIcons.BellOff,
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
    GlassCard(accent = NightbellColors.Rose, contentPadding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(NightbellIcons.Zap, NightbellColors.Rose, size = 40.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Urgent alert active",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.Rose,
                )
                Text(
                    text = "Repeating every $repeatMinutes min until acknowledged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        NightbellButton(
            text = "I've got it — acknowledge",
            onClick = onAcknowledge,
            icon = NightbellIcons.Check,
            tone = ButtonTone.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The monitor stays down until it recovers. Acknowledging only stops " +
                "the repeats for this outage — the next one will shout again.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

/**
 * The certificate the last handshake presented.
 *
 * Its own card rather than a row in Configuration, because it is the only thing
 * on this screen that is an *observation with a deadline*: everything in
 * Configuration is a setting the user typed, and everything in the hero is what
 * happened on the last check. A date the site will stop working on is neither.
 *
 * Only rendered when a certificate was actually seen, so a plain-HTTP monitor
 * gets no empty card claiming nothing.
 */
@Composable
private fun CertificateCard(runtime: MonitorRuntime, nowMs: Long) {
    // Thresholds here are the reporting defaults rather than the user's, because
    // this card describes the certificate rather than the alerting decision — the
    // date and the days remaining are true regardless of when someone chose to be
    // told about them.
    val days = CertificateWatch.daysLeft(runtime.certExpiresAt, nowMs)
    val expired = nowMs >= runtime.certExpiresAt
    val tone = when {
        expired -> NightbellColors.Rose
        days <= 14 -> NightbellColors.Amber
        else -> NightbellColors.Mint
    }
    GlassCard(accent = if (tone == NightbellColors.Mint) Color.Transparent else tone) {
        SectionHeader("TLS certificate", icon = NightbellIcons.Shield, accent = tone)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        expired -> "Expired ${formatSpan(nowMs - runtime.certExpiresAt)} ago"
                        days <= 0L -> "Expires today"
                        days == 1L -> "Expires tomorrow"
                        else -> "$days days left"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = tone,
                )
                Text(
                    text = certDateFormat.format(Date(runtime.certExpiresAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            if (runtime.certIssuer.isNotBlank()) {
                MicroTag(runtime.certIssuer, color = NightbellColors.TextSecondary)
            }
        }
        if (expired) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Every client that verifies certificates is refusing this " +
                    "connection. A check that still passes is checking something " +
                    "that stopped being trustworthy.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ConfigCard(monitor: Monitor, accent: Color) {
    GlassCard {
        SectionHeader("Configuration", icon = NightbellIcons.Sliders, accent = accent)
        ConfigRow("Type", monitor.kind.label)
        if (monitor.kind == MonitorKind.GITHUB_REPO) {
            ConfigRow("Repository", monitor.github.slug)
            ConfigRow("Watching", monitor.github.summary)
            if (monitor.github.digestMode != DigestMode.OFF) {
                ConfigRow("Star digest", monitor.github.digestMode.label)
            }
            if (monitor.github.issueKeywords.isNotEmpty()) {
                ConfigRow("Only if it mentions", monitor.github.keywordsText)
            }
            if (monitor.github.issueAuthors.isNotEmpty()) {
                ConfigRow("Only from", monitor.github.authorsText)
            }
            if (monitor.github.watchReleases && monitor.github.includePrereleases) {
                ConfigRow("Prereleases", "included")
            }
        } else if (monitor.kind != MonitorKind.WEBSITE_ELEMENT) {
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
        if (monitor.kind != MonitorKind.GITHUB_REPO) {
            ConfigRow(
                "Latency budget",
                if (monitor.latencySloMs > 0) "${monitor.latencySloMs} ms" else "Global default",
            )
        }
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
            color = NightbellColors.TextTertiary,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val certDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

@Composable
private fun EventRow(sample: Sample) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = if (sample.ok) NightbellIcons.Check else NightbellIcons.Warning,
            accent = if (sample.ok) NightbellColors.Mint else NightbellColors.Rose,
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
                color = if (sample.ok) NightbellColors.TextSecondary else NightbellColors.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${dateFormat.format(Date(sample.at))} · ${timeFormat.format(Date(sample.at))}",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatLatency(sample.latencyMs),
            style = MaterialTheme.typography.labelLarge,
            color = NightbellColors.TextSecondary,
        )
    }
}

/**
 * What the repository looks like right now, and how to get to it.
 *
 * Everything on this card came back with a request the monitor was making
 * anyway, which is the argument for showing all of it: the star count is why the
 * monitor exists, and the open-issue count, the fork count and the last push are
 * free with the same response.
 *
 * The rate-limit line is the one that earns its place on a bad day. Being refused
 * by GitHub is invisible otherwise: the checks quietly learn nothing, the card
 * shows a stale count, and nothing anywhere explains why. So it is stated, with
 * the reset time, and it is never dressed up as an outage.
 */
@Composable
private fun GitHubHealthCard(
    monitor: Monitor,
    state: GitHubState,
    nowMs: Long,
    accent: Color,
    onOpen: (String) -> Unit,
    onMarkSeen: () -> Unit,
    onMute: () -> Unit,
) {
    val repo = monitor.github.repository
    GlassCard(accent = if (state.rateLimited) NightbellColors.Amber else Color.Transparent) {
        SectionHeader("Repository", icon = NightbellIcons.Repo, accent = accent)
        Row(
            Modifier.testTag("github-metrics"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile(
                label = "Stars",
                value = state.lastStarCount.toString(),
                accent = NightbellColors.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Open issues",
                value = state.openIssues.toString(),
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Forks",
                value = state.forks.toString(),
                accent = NightbellColors.Violet,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        if (!state.seeded) {
            Text(
                text = "Nothing recorded yet. The first check writes down where the repository " +
                    "stands and says nothing, so you are told about what happens next rather " +
                    "than about everything that already had.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }

        ConfigRow("Watching", monitor.github.summary)
        if (state.lastReleaseTag.isNotBlank()) {
            ConfigRow(
                "Latest release",
                listOf(state.lastReleaseTag, state.lastReleaseName)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · "),
            )
        }
        if (state.lastIssueNumber > 0) {
            ConfigRow("Latest issue", "#${state.lastIssueNumber} ${state.lastIssueTitle}")
        }
        val pushed = githubInstantMs(state.pushedAt)
        if (pushed > 0L) ConfigRow("Last push", formatRelative(pushed, nowMs))
        if (state.watchers > 0) ConfigRow("Watching it", state.watchers.toString())
        ConfigRow("GitHub API", state.rateSummary(nowMs))
        if (state.seenAt > 0L) ConfigRow("Marked seen", formatRelative(state.seenAt, nowMs))

        if (state.rateLimited) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightbellColors.Amber.copy(alpha = 0.10f))
                    .padding(13.dp),
            ) {
                Text(
                    text = "GitHub is refusing requests until the budget resets. That is a " +
                        "limit on this device's address, not a problem with the repository, " +
                        "so nothing was recorded and no alert was raised. A token in Settings " +
                        "raises the ceiling from 60 an hour to 5,000.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NightbellButton(
                text = "Open repo",
                onClick = { onOpen(repo.url) },
                icon = NightbellIcons.Repo,
                accent = accent,
                modifier = Modifier.weight(1f).testTag("github-open-repo"),
            )
            if (state.lastIssueUrl.isNotBlank()) {
                NightbellButton(
                    text = "Latest issue",
                    onClick = { onOpen(state.lastIssueUrl) },
                    icon = NightbellIcons.Warning,
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.lastReleaseUrl.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            NightbellButton(
                text = "Latest release",
                onClick = { onOpen(state.lastReleaseUrl) },
                icon = NightbellIcons.Import,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NightbellButton(
                text = "Mark seen",
                onClick = onMarkSeen,
                icon = NightbellIcons.Check,
                tone = ButtonTone.Secondary,
                modifier = Modifier.weight(1f).testTag("github-mark-seen"),
            )
            NightbellButton(
                text = "Mute 24h",
                onClick = onMute,
                icon = NightbellIcons.BellOff,
                tone = ButtonTone.Secondary,
                modifier = Modifier.weight(1f).testTag("github-mute-24h"),
            )
        }
    }
}
