package me.river.nightbell.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.HealthComponent
import me.river.nightbell.domain.HealthCondition
import me.river.nightbell.domain.HealthExpectation
import me.river.nightbell.domain.HealthLogLevel
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.HealthStatus
import me.river.nightbell.domain.HealthTriage
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.healthColor
import me.river.nightbell.ui.theme.healthRim

private fun HealthStatus.asHealth(): Health = when (this) {
    HealthStatus.UP -> Health.UP
    HealthStatus.DEGRADED -> Health.DEGRADED
    HealthStatus.DOWN -> Health.DOWN
}

/** An expected part is the user's call, not the service's health, so it never wears a health colour. */
private val ExpectedTone: Color
    @Composable get() = NightbellColors.TextTertiary

/**
 * The top of a health monitor's screen: the verdict in one word, why in one
 * sentence, and the parts counted in a strip.
 *
 * Replaces the uptime dial for this kind. A health monitor's question at 3am
 * is "which part, and do I care", and a ring with a percentage answered
 * neither; the last checks are drawn further down for whoever wants them.
 */
@Composable
internal fun HealthVerdictCard(
    monitor: Monitor,
    runtime: MonitorRuntime,
    health: Health,
    checking: Boolean,
    nowMs: Long,
) {
    val report = runtime.report
    val triage = HealthTriage.of(report, runtime.expectedParts)
    val notChecking = report.auth.problem != AuthProblem.NONE
    val tone = if (notChecking) NightbellColors.TextTertiary else healthColor(health)
    val word = when {
        checking -> "Checking…"
        notChecking -> "Not checking"
        else -> health.label
    }
    val reason = when {
        notChecking -> "Sign in needed: ${report.auth.problem.headline.replaceFirstChar { it.lowercase() }}"
        runtime.lastCheckedAt <= 0L -> "Not checked yet"
        health == Health.DOWN && runtime.lastMessage.isNotBlank() -> runtime.lastMessage
        triage.failing.isNotEmpty() -> triage.failing.first().let { part ->
            if (part.message.isBlank()) "${part.displayName} ${part.status.label.lowercase()}" else "${part.displayName}: ${part.message}"
        }
        triage.expected.isNotEmpty() -> triage.expectedLine
        triage.healthy.isNotEmpty() -> if (triage.healthy.size == 1) "Its only part reports up." else "All ${triage.healthy.size} parts report up."
        runtime.lastMessage.isNotBlank() -> runtime.lastMessage
        else -> "Reported ${health.label.lowercase()}. The report lists no parts."
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth().testTag("health-verdict"),
        accent = if (notChecking) Color.Transparent else healthRim(health),
        contentPadding = 20.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusOrb(health = if (notChecking) Health.UNKNOWN else health, checking = checking, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = word,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
                color = tone,
                modifier = Modifier.testTag("health-verdict-word"),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = NightbellColors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("health-verdict-reason"),
        )

        if (!triage.isEmpty && !notChecking) {
            Spacer(Modifier.height(16.dp))
            PartsStrip(triage)
            Spacer(Modifier.height(8.dp))
            Text(
                text = triage.tally,
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = NightbellColors.TextSecondary,
                modifier = Modifier.testTag("health-parts-summary"),
            )
            val total = maxOf(report.componentsTotal, report.components.size)
            if (total > report.components.size) {
                Text(
                    text = "Showing ${report.components.size} of $total parts",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }

        if (report.disagreement.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Note(report.disagreement, NightbellIcons.Info, "health-disagreement")
        }

        Spacer(Modifier.height(14.dp))
        val meta = buildList {
            if (runtime.lastCheckedAt > 0L) add("Checked ${formatRelative(runtime.lastCheckedAt, nowMs)}")
            if (runtime.lastCode > 0) add("HTTP ${runtime.lastCode}")
            if (runtime.lastLatencyMs > 0L) add(formatLatency(runtime.lastLatencyMs))
        }
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall.tabular(), color = NightbellColors.TextTertiary)
        }
        val context = listOf(report.service, report.version, report.environment).filter { it.isNotBlank() } +
            listOfNotNull(if (report.reportedAt > 0L) "reported ${clock(report.reportedAt)}" else null)
        if (context.isNotEmpty() && !notChecking) {
            Text(
                text = context.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = Modifier.testTag("health-context"),
            )
        }
    }
}

@Composable
private fun PartsStrip(triage: HealthTriage) {
    val segments = listOf(
        triage.failing.count { it.status == HealthStatus.DOWN } to healthColor(Health.DOWN),
        triage.failing.count { it.status == HealthStatus.DEGRADED } to healthColor(Health.DEGRADED),
        triage.expected.size to NightbellColors.GlassStroke,
        triage.healthy.size to healthColor(Health.UP),
    ).filter { it.first > 0 }
    // The tally under it says the same in words, so TalkBack reads that instead.
    Row(
        Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        segments.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

/**
 * The parts, in three groups by what they ask of the reader. Each group is its
 * own card so none sits inside another, and an empty group is not drawn.
 */
@Composable
internal fun HealthPartsSections(
    runtime: MonitorRuntime,
    onOpen: (String) -> Unit,
    onExpect: (HealthComponent) -> Unit,
    onUnexpect: (HealthComponent) -> Unit,
) {
    val triage = HealthTriage.of(runtime.report, runtime.expectedParts)
    if (triage.isEmpty || runtime.report.auth.problem != AuthProblem.NONE) return
    Column(Modifier.fillMaxWidth().testTag("health-report"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (triage.failing.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth().testTag("health-needs-you"), contentPadding = 18.dp) {
                GroupHeader("Needs you", triage.failing.size, NightbellIcons.AlertCircle, healthColor(triage.failing.first().status.asHealth()))
                Text(
                    text = "Failing on purpose, like a staging service? Mark it expected and it stops paging " +
                        "until its status changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                triage.failing.forEach { part ->
                    PartRow(
                        part = part,
                        dot = healthColor(part.status.asHealth()),
                        hollow = false,
                        line = part.message.ifBlank { part.status.label },
                        onOpen = { onOpen(part.id) },
                    ) {
                        PillButton(
                            text = "Mark expected",
                            icon = NightbellIcons.BellOff,
                            outlined = true,
                            tag = "health-expect-${part.id}",
                            onClick = { onExpect(part) },
                        )
                    }
                }
            }
        }
        if (triage.expected.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth().testTag("health-expected"), contentPadding = 18.dp) {
                GroupHeader("Expected, not paging", triage.expected.size, NightbellIcons.BellOff, ExpectedTone)
                Text(
                    text = "Any change in status pages you again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                triage.expected.forEach { part ->
                    PartRow(
                        part = part,
                        dot = ExpectedTone,
                        hollow = true,
                        line = part.status.label + if (part.since > 0L) " since ${clock(part.since)}" else "",
                        onOpen = { onOpen(part.id) },
                        rowTag = "health-expected-${part.id}",
                    ) {
                        PillButton(
                            text = "Page me again",
                            outlined = false,
                            tag = "health-unexpect-${part.id}",
                            onClick = { onUnexpect(part) },
                        )
                    }
                }
            }
        }
        if (triage.healthy.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth().testTag("health-healthy"), contentPadding = 18.dp) {
                GroupHeader("Healthy", triage.healthy.size, NightbellIcons.Check, healthColor(Health.UP))
                // Names only. Ten green rows were most of the scrolling on a bad
                // night and said nothing a count does not; a tap still opens one.
                // Fifty names are a wall again, so past a dozen the rest wait.
                var all by rememberSaveable { mutableStateOf(false) }
                val shown = if (all) triage.healthy else triage.healthy.take(HEALTHY_SHOWN)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { part ->
                        Chip(tag = "health-part-${part.id}", onClick = { onOpen(part.id) }, onClickLabel = "Show details") {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(healthColor(Health.UP)))
                            Spacer(Modifier.width(8.dp))
                            Text(part.displayName, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary, maxLines = 1)
                        }
                    }
                    if (triage.healthy.size > HEALTHY_SHOWN) {
                        Chip(tag = "health-healthy-more", onClick = { all = !all }) {
                            Text(
                                text = if (all) "Show fewer" else "Show all ${triage.healthy.size}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = NightbellColors.Sky,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val HEALTHY_SHOWN = 12

@Composable
private fun Chip(tag: String, onClick: () -> Unit, onClickLabel: String? = null, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, NightbellColors.GlassStrokeSoft, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
    ) { content() }
}

@Composable
private fun GroupHeader(title: String, count: Int, icon: ImageVector, accent: Color) {
    SectionHeader(
        title = title,
        icon = icon,
        accent = accent,
        trailing = {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium.tabular(), color = NightbellColors.TextTertiary)
        },
    )
}

/**
 * One part with its action on the row itself. The name and line open the
 * details; the action is its own target beside them, never nested inside.
 */
@Composable
private fun PartRow(
    part: HealthComponent,
    dot: Color,
    hollow: Boolean,
    line: String,
    onOpen: () -> Unit,
    rowTag: String = "health-part-${part.id}",
    action: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(NightbellColors.GlassStrokeSoft))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(rowTag)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onOpen)
                .padding(vertical = 8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .then(if (hollow) Modifier.border(2.dp, dot, CircleShape) else Modifier.background(dot))
                    .semantics { contentDescription = if (hollow) "${part.status.label}, expected" else part.status.label },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = part.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = NightbellColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (part.reading.isNotBlank() && !hollow) {
                        Spacer(Modifier.width(8.dp))
                        Text(part.reading, style = MaterialTheme.typography.bodySmall.tabular(), color = dot, maxLines = 1)
                    }
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
private fun PillButton(text: String, outlined: Boolean, tag: String, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .then(if (outlined) Modifier.border(1.dp, NightbellColors.GlassStroke, RoundedCornerShape(50)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .testTag(tag),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = NightbellColors.TextPrimary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (outlined) NightbellColors.TextPrimary else NightbellColors.Sky,
            maxLines = 1,
        )
    }
}

/**
 * The last checks as one strip, in place of the response time chart. For a
 * health monitor the timing of a service's report of itself is rarely the
 * question; whether it passed is.
 */
@Composable
internal fun HealthChecksCard(monitor: Monitor, samples: List<Sample>) {
    val shown = samples.takeLast(CHECKS_SHOWN)
    if (shown.isEmpty()) return
    val median = shown.map { it.latencyMs }.sorted()[shown.size / 2]
    GlassCard(Modifier.fillMaxWidth().testTag("health-checks")) {
        SectionHeader(
            title = "Last ${shown.size} ${if (shown.size == 1) "check" else "checks"}",
            icon = NightbellIcons.History,
            trailing = {
                Text("median ${formatLatency(median)}", style = MaterialTheme.typography.labelMedium.tabular(), color = NightbellColors.TextTertiary)
            },
        )
        val passed = shown.count { it.ok }
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = "$passed of ${shown.size} checks passed" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(CHECKS_SHOWN - shown.size) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(NightbellColors.GlassStrokeSoft))
            }
            shown.forEach { sample ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(healthColor(if (sample.ok) Health.UP else Health.DOWN)),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$passed of ${shown.size} passed · every ${monitor.intervalMinutes} min",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = NightbellColors.TextTertiary,
        )
    }
}

private const val CHECKS_SHOWN = 24

/**
 * Sign-in and what could not be read from the report. Both are about
 * Nightbell's side of the conversation, so they go last, below the parts.
 */
@Composable
internal fun HealthSignInCard(monitor: Monitor, report: HealthState, nowMs: Long) {
    val signsIn = monitor.health.signIn == HealthSignIn.SSO || monitor.health.signIn == HealthSignIn.BASIC
    if (!signsIn && report.notes.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().testTag("health-sign-in-card")) {
        if (signsIn) SignInSection(monitor, report, nowMs)
        if (signsIn && report.notes.isNotEmpty()) Spacer(Modifier.height(14.dp))
        if (report.notes.isNotEmpty()) {
            Text("Not read from the report", style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextSecondary)
            report.notes.forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun SignInSection(monitor: Monitor, report: HealthState, nowMs: Long) {
    val auth = report.auth
    val watch = monitor.health
    if (auth.problem != AuthProblem.NONE) {
        Text(
            text = "Sign in needed",
            style = MaterialTheme.typography.titleSmall,
            color = NightbellColors.TextPrimary,
            modifier = Modifier.testTag("health-not-checking"),
        )
        Text(
            text = listOf(auth.problem.headline, auth.detail).filter { it.isNotBlank() }.joinToString(". ") +
                ". Nothing is sent to the endpoint until you sign in again from Edit. " +
                "This says nothing about whether the service is up.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
        return
    }
    val host = runCatching { URI(watch.issuer).host }.getOrNull().orEmpty()
    val rows = buildList {
        if (watch.signIn == HealthSignIn.SSO) {
            if (host.isNotBlank()) add("Provider" to host)
            if (watch.clientId.isNotBlank()) add("Client" to watch.clientId)
        }
        if (auth.account.isNotBlank()) add("Account" to auth.account)
        if (watch.signIn == HealthSignIn.SSO) {
            add(
                "Session" to when {
                    auth.offline -> "Offline, stays signed in"
                    auth.refreshExpiresAt > 0 -> "Until ${day(auth.refreshExpiresAt)}"
                    else -> "Ends with the provider's session"
                },
            )
            if (auth.lastRefreshAt > 0) add("Last refresh" to formatRelative(auth.lastRefreshAt, nowMs))
        }
    }
    Text("Sign-in", style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextSecondary)
    Spacer(Modifier.height(4.dp))
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary, modifier = Modifier.width(96.dp))
            Text(value, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One part's rules, log and the expected switch, over the screen rather than
 * inside the list. Expanding rows in place turned a bad night into a wall of
 * rules with the part names lost in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthPartSheet(
    part: HealthComponent,
    expected: Boolean,
    nowMs: Long,
    onDismiss: () -> Unit,
    onExpect: () -> Unit,
    onUnexpect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tone = if (expected) ExpectedTone else healthColor(part.status.asHealth())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NightbellColors.GlassFillStrong,
        contentColor = NightbellColors.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .testTag("health-sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = part.status.label + if (expected) ", expected" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = tone,
                    )
                    if (part.since > 0L) {
                        Text(
                            text = "  since ${clock(part.since)}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = part.displayName,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = NightbellColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (part.reading.isNotBlank()) {
                        Text(part.reading, style = MaterialTheme.typography.titleMedium.tabular(), color = NightbellColors.TextSecondary)
                    }
                }
                if (part.message.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(part.message, style = MaterialTheme.typography.bodyMedium, color = NightbellColors.TextSecondary)
                }
                if (part.durationMs >= 0) {
                    Text(
                        text = "The service checked it in ${part.durationMs.toLong()} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }

            if (part.status != HealthStatus.UP) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (expected) {
                        PillButton(text = "Page me again", outlined = true, tag = "health-sheet-unexpect", onClick = onUnexpect)
                        Text(
                            "Marked expected. Not paging until its status changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    } else {
                        PillButton(
                            text = "Mark expected",
                            icon = NightbellIcons.BellOff,
                            outlined = true,
                            tag = "health-sheet-expect",
                            onClick = onExpect,
                        )
                        Text(
                            "Stops paging while it stays ${part.status.label.lowercase()}. Any change pages you again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
            }

            if (part.conditions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rules", style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextTertiary)
                    part.conditions.forEach { ConditionRow(it) }
                }
            }
            if (part.log.isNotEmpty()) LogSection(part, nowMs)
            if (part.conditions.isEmpty() && part.log.isEmpty()) {
                Text(
                    "The service reported no rules or log for this part.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun ConditionRow(condition: HealthCondition) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag("health-rule-${condition.id}")) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(healthColor(condition.status.asHealth()))
                .semantics { contentDescription = condition.status.label },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    condition.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                // What was measured, beside the rule it was held to on the line below,
                // so whether the line was really crossed can be judged without trusting
                // the colour.
                val measured = listOf(condition.observed, condition.unit).filter { it.isNotBlank() }.joinToString(" ")
                if (measured.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(measured, style = MaterialTheme.typography.bodySmall.tabular(), color = NightbellColors.TextSecondary)
                }
            }
            val rule = listOfNotNull(
                condition.rule.ifBlank { null },
                condition.window.ifBlank { null }?.let { "over $it" },
            ).joinToString(" ")
            if (rule.isNotBlank()) {
                Text(rule, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
            }
            if (condition.message.isNotBlank()) {
                Text(condition.message, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
            }
        }
    }
}

/**
 * The service's log for one part. A time and a message per line answers "what
 * happened" at a glance; the level, the age and the data pairs are there on a
 * tap for whoever is going to go and fix it.
 */
@Composable
private fun LogSection(part: HealthComponent, nowMs: Long) {
    var details by rememberSaveable(part.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Log", style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextTertiary)
        part.log.forEach { entry ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        seconds(entry.at),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = NightbellColors.TextTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
                        if (details) {
                            val extra = listOf(entry.level.word, formatRelative(entry.at, nowMs)) +
                                entry.data.map { (key, value) -> "$key=$value" }
                            Text(extra.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
                        }
                    }
                }
            }
        }
        Text(
            text = if (details) "Hide log details" else "Show log details",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = NightbellColors.Sky,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { details = !details }
                .wrapContentHeight(Alignment.CenterVertically)
                .testTag("health-log-details-${part.id}"),
        )
    }
}

@Composable
private fun Note(text: String, icon: ImageVector, tag: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = NightbellColors.TextTertiary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
    }
}

internal fun isExpected(runtime: MonitorRuntime, part: HealthComponent): Boolean =
    HealthExpectation.covers(runtime.expectedParts, part)

private val HealthLogLevel.word: String
    get() = when (this) {
        HealthLogLevel.INFO -> "info"
        HealthLogLevel.WARN -> "warn"
        HealthLogLevel.ERROR -> "error"
    }

/** Fixed-width digits, so readings and log times line up down the card. */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun seconds(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

private fun day(epochMs: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))
