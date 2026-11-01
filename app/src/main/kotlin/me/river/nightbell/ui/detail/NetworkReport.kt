package me.river.nightbell.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkTarget
import me.river.nightbell.domain.Outages
import me.river.nightbell.domain.PingReading
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.SameHost
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassDivider
import me.river.nightbell.ui.components.LabelledRow
import me.river.nightbell.ui.components.MetricTile
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.healthColor

/** Built per use, so a locale changed while the app runs is the one dates are written in. */
private fun whenText(at: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(at))

/** How many outages the card lists before it says how many more there were. */
private const val OUTAGES_SHOWN = 5

/**
 * The last ping in full: fastest, average and slowest reply, then what was lost
 * on that check and across every stored one.
 *
 * Read off the newest sample that got any reply, so a check that lost every
 * packet does not blank the round trips; the line under them says that check
 * was lost, which is the part that changed.
 */
@Composable
internal fun PingCard(samples: List<Sample>, accent: Color) {
    val pings = samples.filter { it.ping != null }
    val last = pings.lastOrNull()?.ping ?: return
    val answered = pings.lastOrNull { it.ping!!.answered }?.ping
    GlassCard(Modifier.fillMaxWidth().testTag("ping-card")) {
        SectionHeader("Echo replies", icon = NightbellIcons.Echo, accent = accent)
        if (answered != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Fastest", PingReading.formatUs(answered.minUs), Modifier.weight(1f), accent = NightbellColors.Mint)
                MetricTile("Average", PingReading.formatUs(answered.avgUs), Modifier.weight(1f), accent = accent)
                MetricTile("Slowest", PingReading.formatUs(answered.maxUs), Modifier.weight(1f), accent = NightbellColors.Violet)
            }
            Spacer(Modifier.height(12.dp))
        }
        val lostColor = when {
            last.received == 0 -> NightbellColors.Rose
            last.lost > 0 -> NightbellColors.Amber
            else -> NightbellColors.TextSecondary
        }
        Text(
            text = "Last check: ${last.repliesLine}" + if (last.lost > 0) ", ${last.lossPercent}% lost" else ", none lost",
            style = MaterialTheme.typography.bodyMedium,
            color = lostColor,
            modifier = Modifier.testTag("ping-last"),
        )
        Outages.lossPercent(pings)?.let { loss ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = run {
                    val sent = pings.sumOf { it.ping!!.sent }
                    val span = if (pings.size == 1) "the last check" else "the last ${pings.size} checks"
                    if (pings.all { it.ping!!.lost == 0 }) {
                        "Across $span, all $sent echo requests were answered."
                    } else {
                        "Across $span, $loss% of $sent echo requests went unanswered."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
}

/**
 * When it last answered, when it last did not, and each outage the stored
 * history holds, newest first, with how long it lasted.
 */
@Composable
internal fun OutagesCard(samples: List<Sample>, nowMs: Long, accent: Color) {
    val summary = Outages.of(samples)
    if (summary.isEmpty) return
    GlassCard(Modifier.fillMaxWidth().testTag("outages-card")) {
        SectionHeader("Outages", icon = NightbellIcons.History, accent = accent)
        FactLine("Last answered", whenLine(summary.lastSuccessAt, nowMs, "not in the stored checks"))
        FactLine("Last failed", whenLine(summary.lastFailureAt, nowMs, "not in the stored checks"))
        Spacer(Modifier.height(10.dp))
        if (summary.outages.isEmpty()) {
            Text(
                text = "No outages in " +
                    (if (summary.checks == 1) "the one stored check" else "the last ${summary.checks} checks") + ", back to " +
                    "${whenText(summary.sinceAt)}.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
            return@GlassCard
        }
        summary.outages.take(OUTAGES_SHOWN).forEachIndexed { index, outage ->
            if (index > 0) GlassDivider(Modifier.padding(vertical = 2.dp), alpha = 0.06f)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = whenText(outage.startAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NightbellColors.TextPrimary,
                    )
                    Text(
                        text = buildString {
                            append(outage.failedChecks)
                            append(if (outage.failedChecks == 1) " failed check" else " failed checks")
                            if (outage.reason.isNotBlank()) append(" · ").append(outage.reason)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (outage.ongoing) {
                        "down ${Outages.span(outage.durationMs(nowMs))} so far"
                    } else {
                        Outages.span(outage.durationMs(nowMs))
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = if (outage.ongoing) NightbellColors.Rose else NightbellColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val more = summary.outages.size - OUTAGES_SHOWN
        Text(
            text = buildString {
                append(summary.outages.size)
                append(if (summary.outages.size == 1) " outage, " else " outages, ")
                append(Outages.span(summary.downMs(nowMs)))
                append(" down in total since ")
                append(whenText(summary.sinceAt))
                append('.')
                if (more > 0) append(" $more older not listed.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

/**
 * The other monitors on this host, each a way to it, the sentence that reads
 * them together, and a way to add one more.
 */
@Composable
internal fun SameHostCard(
    monitor: Monitor,
    health: Health,
    siblings: List<Pair<Monitor, Health>>,
    accent: Color,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val host = NetworkTarget.hostOf(monitor)
    if (host.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().testTag("same-host-card")) {
        SectionHeader("This host", icon = NightbellIcons.Globe, accent = accent)
        if (siblings.isEmpty()) {
            Text(
                text = "Nothing else watches $host. " + if (monitor.kind == MonitorKind.PING) {
                    "A port check beside this ping tells a filtered network from a dead service."
                } else {
                    "A ping beside this check tells a dead service from an unreachable host."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        } else {
            Text(
                text = "Also watching $host:",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
            Spacer(Modifier.height(4.dp))
            siblings.forEach { (other, otherHealth) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NightbellRadii.inCard))
                        .clickable(role = Role.Button, onClickLabel = "Open") { onOpen(other.id) }
                        .padding(vertical = 9.dp, horizontal = 4.dp)
                        .testTag("same-host-${other.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusOrb(health = otherHealth, checking = false, size = 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = other.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NightbellColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${other.kind.label} · ${otherHealth.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = healthColor(otherHealth).copy(alpha = 0.9f),
                        )
                    }
                    Icon(
                        NightbellIcons.ChevronRight,
                        contentDescription = null,
                        tint = NightbellColors.TextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            SameHost.reading(listOf(monitor to health) + siblings)?.let { line ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightbellColors.TextSecondary,
                    modifier = Modifier.testTag("same-host-reading"),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        NightbellButton(
            text = "Add a check on this host",
            onClick = onAdd,
            icon = NightbellIcons.Plus,
            tone = ButtonTone.Secondary,
            modifier = Modifier.fillMaxWidth().testTag("same-host-add"),
        )
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    LabelledRow(
        labelWidth = 112.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        label = { mod ->
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = NightbellColors.TextTertiary, modifier = mod)
        },
        value = { mod ->
            Text(text = value, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary, modifier = mod)
        },
    )
}

private fun whenLine(at: Long, nowMs: Long, never: String): String =
    if (at <= 0L) never else "${formatRelative(at, nowMs)}, ${whenText(at)}"
