package me.river.nightbell.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.HealthComponent
import me.river.nightbell.domain.HealthCondition
import me.river.nightbell.domain.HealthLogLevel
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.HealthStatus
import me.river.nightbell.domain.Monitor
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.healthColor

private fun HealthStatus.asHealth(): Health = when (this) {
    HealthStatus.UP -> Health.UP
    HealthStatus.DEGRADED -> Health.DEGRADED
    HealthStatus.DOWN -> Health.DOWN
}

/**
 * What a health monitor's last report said, part by part, with the rules each
 * part was judged by and the evidence the service logged.
 *
 * Built for the question asked at 3am, which is not "is it down" but "is it
 * real, and where": the verdict is on the hero card above, and this card is
 * where the reasons go. Colour is the health of each part and nothing else;
 * a log entry at error level is a fact the service reported, not a verdict,
 * so it is labelled rather than painted.
 */
@Composable
internal fun HealthReportCard(monitor: Monitor, report: HealthState, nowMs: Long, accent: Color) {
    val signsIn = monitor.health.signIn == HealthSignIn.SSO || monitor.health.signIn == HealthSignIn.BASIC
    val context = listOf(report.service, report.version, report.environment).filter { it.isNotBlank() }
    if (report.components.isEmpty() && !signsIn && context.isEmpty() && report.notes.isEmpty() &&
        report.disagreement.isBlank()
    ) {
        return
    }

    GlassCard(Modifier.fillMaxWidth().testTag("health-report")) {
        if (signsIn) {
            SignInSection(monitor, report, nowMs)
            if (report.components.isNotEmpty() || context.isNotEmpty()) Spacer(Modifier.height(14.dp))
        }

        if (context.isNotEmpty() || report.reportedAt > 0L) {
            val reported = if (report.reportedAt > 0L) {
                "Reported ${clock(report.reportedAt)} (${formatRelative(report.reportedAt, nowMs)})"
            } else {
                ""
            }
            Text(
                text = (context + listOf(reported).filter { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = Modifier.testTag("health-context"),
            )
            Spacer(Modifier.height(10.dp))
        }

        if (report.disagreement.isNotBlank()) {
            Note(report.disagreement, NightbellIcons.Info, "health-disagreement")
            Spacer(Modifier.height(10.dp))
        }

        if (report.components.isNotEmpty()) {
            val total = maxOf(report.componentsTotal, report.components.size)
            SectionHeader(
                title = if (total > report.components.size) {
                    "Showing ${report.components.size} of $total parts"
                } else {
                    "Components"
                },
                icon = NightbellIcons.Layers,
                accent = accent,
            )
            // Worst first, then the order the service chose.
            val ordered = report.components.withIndex()
                .sortedWith(compareByDescending<IndexedValue<HealthComponent>> { it.value.status.rank }.thenBy { it.index })
                .map { it.value }
            val strip = ordered.size > 1
            if (strip) PartsSummary(ordered)
            ordered.forEachIndexed { i, part ->
                ComponentRow(
                    part = part,
                    openAtStart = i == 0 && part.status != HealthStatus.UP,
                    divider = strip || i > 0,
                    nowMs = nowMs,
                )
            }
        }

        if (report.notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Not read from the report",
                style = MaterialTheme.typography.labelMedium,
                color = NightbellColors.TextSecondary,
            )
            report.notes.forEach { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
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
            text = "Not checking · Sign in needed",
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

@Composable
private fun PartsSummary(parts: List<HealthComponent>) {
    val counts = listOf(HealthStatus.DOWN, HealthStatus.DEGRADED, HealthStatus.UP)
        .map { status -> status to parts.count { it.status == status } }
        .filter { it.second > 0 }
    // The bar says nothing the line under it does not, so TalkBack reads the line only.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(6.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        counts.forEach { (status, count) ->
            Box(
                Modifier
                    .weight(count.toFloat())
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(healthColor(status.asHealth())),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (counts.size == 1 && counts[0].first == HealthStatus.UP) {
            "All ${parts.size} up"
        } else {
            counts.joinToString(", ") { (status, count) -> "$count ${status.label.lowercase()}" }
        },
        style = MaterialTheme.typography.bodySmall,
        color = NightbellColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp).testTag("health-parts-summary"),
    )
}

@Composable
private fun ComponentRow(part: HealthComponent, openAtStart: Boolean, divider: Boolean, nowMs: Long) {
    val facts = buildList {
        if (part.since > 0L) add("${part.status.label} since ${clock(part.since)}")
        if (part.durationMs >= 0) add("checked in ${part.durationMs.toLong()} ms")
    }.joinToString(" · ")
    val expandable = part.message.isNotBlank() || facts.isNotBlank() || part.conditions.isNotEmpty() || part.log.isNotEmpty()
    // Only the worst part starts open. Opening every failing part turned one bad
    // night into a wall of rules and log lines with the part names lost in it.
    var open by rememberSaveable(part.id) { mutableStateOf(openAtStart) }
    val tone = healthColor(part.status.asHealth())
    Column(Modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(NightbellColors.GlassStrokeSoft))
        // Only the header row is the tap target. Clickable merges everything inside it into
        // one node, and the rules and log read as one blob to TalkBack when they are in it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (expandable) {
                        Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") {
                            open = !open
                        }
                    } else {
                        Modifier
                    },
                )
                .testTag("health-part-${part.id}"),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone)
                    .semantics { contentDescription = part.status.label },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = part.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (part.reading.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(part.reading, style = MaterialTheme.typography.bodySmall.tabular(), color = NightbellColors.TextSecondary, maxLines = 1)
            }
            if (expandable) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (open) NightbellIcons.ChevronUp else NightbellIcons.ChevronDown,
                    contentDescription = null,
                    tint = NightbellColors.TextTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = open && expandable,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(start = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (part.message.isNotBlank() || facts.isNotBlank()) {
                    Column {
                        if (part.message.isNotBlank()) {
                            Text(part.message, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
                        }
                        if (facts.isNotBlank()) {
                            Text(facts, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
                        }
                    }
                }
                if (part.conditions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Rules", style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextTertiary)
                        part.conditions.forEach { ConditionRow(it) }
                    }
                }
                if (part.log.isNotEmpty()) {
                    LogSection(part, nowMs)
                }
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
            color = NightbellColors.Aqua,
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
private fun Note(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, tint = NightbellColors.TextTertiary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
    }
}

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
