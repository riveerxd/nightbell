@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package me.river.nightbell.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.Summary
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.domain.UptimeWindows
import me.river.nightbell.ui.components.formatSpan
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.healthColor
import kotlin.math.roundToInt

/**
 * The fleet roll-up, as one state-coloured banner.
 *
 * The whole surface takes the worst monitor's colour, so the answer to "is
 * anything broken" arrives before any text is read. That is the entire point of
 * the design: the dial it replaced encoded the *number* prominently and the
 * *verdict* incidentally, which is backwards for a screen someone opens to be
 * reassured in one glance.
 */
data class FleetStats(
    val total: Int,
    val down: Int,
    val degraded: Int,
    val paused: Int,
    val checked: Int,
    val uptime: Float,
    /** At least one check landed inside the reporting window. */
    val uptimeKnown: Boolean,
    /** Every monitor's history reaches back across the whole window. */
    val uptimeComplete: Boolean,
    /** The shortest reach across the fleet — what the figure can honestly claim. */
    val uptimeSpanMs: Long,
    val avgLatencyMs: Long,
    /** Worst-first, one entry per monitor — the tick row's source. */
    val healths: List<Health>,
    val headline: String,
    /** No connectivity: nothing is being checked, so nothing here is current. */
    val offline: Boolean = false,
) {
    val allGood: Boolean get() = down == 0 && degraded == 0

    val uptimeText: String get() = if (total == 0 || !uptimeKnown) "—" else "${uptime.roundToInt()}%"
    val avgText: String get() = if (checked == 0) "—" else formatLatency(avgLatencyMs)

    /**
     * What the percentage is a percentage of.
     *
     * Printed next to the number rather than left implied. "93% UPTIME" over a
     * span that silently varies with every monitor's interval is a figure nobody
     * can act on, and the fix is one word, not a smaller number.
     */
    val uptimeScope: String
        get() = when {
            !uptimeKnown -> "NO CHECKS YET"
            uptimeComplete -> "24H UPTIME"
            // "PAST UNDER A MINUTE" is both long and awkward. Under a minute the
            // span is barely a fact, so it is stated as a bound rather than a
            // duration.
            uptimeSpanMs < 60_000 -> "UPTIME, UNDER 1M"
            else -> "UPTIME, PAST ${UptimeWindows.shortSpan(uptimeSpanMs).uppercase()}"
        }
}

fun fleetStatsOf(
    cards: List<MonitorCard>,
    nowMs: Long,
    offline: Boolean = false,
): FleetStats {
    val fleet = Summary.of(cards.map { it.monitor }, cards.associate { it.monitor.id to it.runtime })
    // Uptime is pooled across the fleet over one real day, so it means the same
    // thing whether a monitor runs every minute or every ten hours. Latency stays
    // on the same window for the same reason: two numbers side by side that cover
    // different spans invite exactly the wrong comparison.
    val windows = cards.mapNotNull { it.runtime.uptimeWithin(nowMs, UptimeWindows.DAY_MS) }
    val inDay = cards.flatMap { card ->
        card.runtime.samples.filter { nowMs - it.at in 0..UptimeWindows.DAY_MS }
    }
    val ok = inDay.filter { it.ok }
    return FleetStats(
        total = cards.size,
        down = fleet.down,
        degraded = fleet.degraded,
        paused = fleet.paused,
        checked = cards.count { it.runtime.lastCheckedAt > 0 },
        uptime = if (inDay.isEmpty()) 0f else ok.size * 100f / inDay.size,
        uptimeKnown = inDay.isNotEmpty(),
        // Only the fleet's shortest reach can be claimed: one monitor with a
        // full day of history does not let the roll-up speak for a monitor added
        // ten minutes ago.
        uptimeComplete = windows.isNotEmpty() && windows.all { it.complete },
        uptimeSpanMs = windows.minOfOrNull { it.spanMs } ?: 0L,
        avgLatencyMs = if (ok.isEmpty()) 0L else ok.sumOf { it.latencyMs } / ok.size,
        healths = fleet.ranked.map { it.health },
        headline = fleet.headline,
        offline = offline,
    )
}

/**
 * Colour means health here exactly as it does on a card — with one exception.
 * Offline is deliberately *not* red: nothing is known to be broken, we have
 * simply stopped looking, and claiming an outage we did not observe is the same
 * lie the notifications were telling.
 *
 * A composable read rather than a property on [FleetStats], because the palette
 * now depends on the active scheme and a plain getter cannot see one.
 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun FleetStats.tone(): Color = when {
    offline -> NightbellColors.Sky
    down > 0 -> NightbellColors.Rose
    degraded > 0 -> NightbellColors.Amber
    total == 0 -> NightbellColors.Sky
    else -> NightbellColors.Mint
}

@Composable
fun FleetBanner(
    stats: FleetStats,
    refreshing: Boolean,
    onCheckAll: () -> Unit,
    pause: PauseState,
    nowMs: Long,
    promptOpen: Boolean,
    onPauseTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = stats.tone()
    val shape = RoundedCornerShape(26.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(tone.copy(alpha = 0.20f), tone.copy(alpha = 0.055f)),
                ),
            )
            .border(1.dp, tone.copy(alpha = 0.32f), shape)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        stats.offline -> NightbellIcons.WifiOff
                        stats.total == 0 -> NightbellIcons.Radar
                        stats.allGood -> NightbellIcons.Shield
                        else -> NightbellIcons.Warning
                    },
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val eyebrow = when {
                    stats.offline -> "NO CONNECTION"
                    stats.total == 0 -> "NOTHING WATCHED"
                    stats.allGood -> "ALL CLEAR"
                    else -> "NEEDS ATTENTION"
                }
                Mono(
                    text = eyebrow,
                    color = tone,
                    size = 9,
                    weight = FontWeight.Bold,
                    tracking = 2.2,
                    // Screen readers spell ALL-CAPS out letter by letter.
                    spoken = eyebrow.lowercase().replaceFirstChar { it.uppercase() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Offline replaces the verdict rather than decorating it: the
                    // last-known headline read as current, which is precisely the
                    // false reassurance this whole change exists to remove.
                    text = if (stats.offline) "Checks paused" else stats.headline,
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                    color = NightbellColors.TextPrimary,
                )
            }
        }

        // With nothing to monitor there is no uptime, no fleet and nothing to
        // re-check; the empty state below carries the only useful action.
        if (stats.total == 0) return@Column

        Spacer(Modifier.height(16.dp))

        // Dot-separated rather than tiled: three numbers do not each need a box,
        // and the banner's job is the verdict above, not a metrics dashboard.
        //
        // A flow rather than a row, because the three do not fit on one line at
        // every font scale. As a Row the last of them was measured against
        // whatever the first two left, and at 150 per cent that was two lines, at
        // 200 per cent it was five pixels wide and a thousand tall: "1 MONITOR"
        // set one letter to a line, down the side of the banner. Wrapping puts a
        // separator at the end of a line, which reads the way prose does.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(
                text = "${stats.uptimeText} ${stats.uptimeScope}",
                color = NightbellColors.TextSecondary,
                size = 10,
                weight = FontWeight.Bold,
                tracking = 1.2,
                spoken = "${stats.uptimeText} ${stats.uptimeScope.lowercase()}",
            )
            MonoDot()
            Mono(
                text = stats.avgText,
                color = NightbellColors.TextSecondary,
                size = 10,
                tracking = 1.2,
                spoken = "${stats.avgText} average response",
            )
            MonoDot()
            val monitors = if (stats.total == 1) "1 MONITOR" else "${stats.total} MONITORS"
            Mono(
                text = monitors,
                color = NightbellColors.TextSecondary,
                size = 10,
                tracking = 1.2,
                spoken = monitors.lowercase(),
            )
        }

        Spacer(Modifier.height(16.dp))
        FleetTicks(stats.healths, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))

        // A standing pause takes the whole row over rather than sitting beside a
        // "check all now" that would do nothing. Two buttons where one is inert is
        // how a user concludes the app is broken.
        if (pause.isActive(nowMs)) {
            Mono(
                text = pauseHeadline(pause, nowMs),
                color = NightbellColors.Amber,
                size = 10,
                weight = FontWeight.Bold,
                tracking = 1.2,
            )
            Spacer(Modifier.height(10.dp))
            NightbellButton(
                text = "Resume monitoring",
                onClick = onPauseTapped,
                icon = NightbellIcons.Play,
                loading = refreshing,
                tone = ButtonTone.Primary,
                accent = NightbellColors.Amber,
                accentEnd = NightbellColors.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Offline the button is shown disabled rather than hidden: a control that
            // vanishes leaves you wondering where it went, one that greys out tells
            // you it will come back.
            Row(verticalAlignment = Alignment.CenterVertically) {
                NightbellButton(
                    text = when {
                        stats.offline -> "Waiting for a connection"
                        refreshing -> "Checking everything…"
                        else -> "Check all now"
                    },
                    onClick = onCheckAll,
                    icon = if (stats.offline) NightbellIcons.WifiOff else NightbellIcons.Radar,
                    loading = refreshing,
                    enabled = !stats.offline,
                    tone = ButtonTone.Primary,
                    accent = tone,
                    accentEnd = tone,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                // Enabled while offline, unlike the button beside it. Losing signal
                // is exactly when someone reaches for this, and a pause is a local
                // decision that needs no network to take effect.
                GlassIconButton(
                    icon = NightbellIcons.Pause,
                    onClick = onPauseTapped,
                    contentDescription = "Pause monitoring",
                    accent = NightbellColors.Amber,
                    active = promptOpen,
                )
            }

        }
    }
}

/**
 * What the banner says while a pause is standing.
 *
 * Names the scope, because "paused" and "silent" are different promises and the
 * user picked one of them.
 */
private fun pauseHeadline(pause: PauseState, nowMs: Long): String {
    val what = if (pause.scope == PauseScope.STOP_CHECKS) "PAUSED" else "SILENT"
    val remaining = pause.remainingMs(nowMs) ?: return "$what · UNTIL YOU RESUME"
    // Rounded down, not up. The clock driving this composition ticks on its own
    // schedule and can sit a second behind the one the pause was stamped with,
    // and rounding up turned that second into "31M LEFT" on a pause the user had
    // just set to thirty minutes. Never claim more time than was asked for.
    val minutes = (remaining / 60_000L).toInt().coerceAtLeast(1)
    val left = when {
        minutes < 60 -> "${minutes}M"
        minutes % 60 == 0 -> "${minutes / 60}H"
        else -> "${minutes / 60}H ${minutes % 60}M"
    }
    return "$what · $left LEFT"
}

// --------------------------------------------------------------------- pieces

/**
 * Monospace, wide-tracked — the metadata voice, distinct from the verdict.
 *
 * [spoken] overrides what a screen reader announces, which every ALL-CAPS
 * string needs: TalkBack reads `UPTIME` as six letters otherwise. The visible
 * text is left intact so `onNodeWithText` still finds it.
 */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NightbellColors.TextTertiary,
    size: Int = 10,
    weight: FontWeight = FontWeight.Medium,
    tracking: Double = 1.4,
    spoken: String? = null,
) = Text(
    text = text,
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    color = color,
    modifier = if (spoken == null) {
        modifier
    } else {
        modifier.semantics { contentDescription = spoken }
    },
)

/** The separator alone. Its spacing is the flow's, or it would double up on wrap. */
@Composable
private fun MonoDot() = Mono("·", color = NightbellColors.TextTertiary, size = 10)

/**
 * What the tick row is actually saying, in words.
 *
 * The strip's whole content is a proportion: how much of the fleet is red. That
 * is free to read and invisible to TalkBack, which used to be told only
 * "Per-monitor health", the name of a thing rather than anything it says. Same
 * argument as `chartSummary` in `ui/components/Status.kt`.
 */
internal fun ticksSummary(healths: List<Health>): String {
    if (healths.isEmpty()) return "Per-monitor health, nothing watched"
    val counted = Health.entries.mapNotNull { health ->
        val count = healths.count { it == health }
        if (count == 0) null else "$count ${health.label.lowercase()}"
    }
    val monitors = if (healths.size == 1) "1 monitor" else "${healths.size} monitors"
    return "Per-monitor health, $monitors: ${counted.joinToString(", ")}"
}

/**
 * One tick per monitor, worst-first, coloured by health, so a red banner also
 * tells you *how much* of the fleet is red without opening anything.
 */
@Composable
fun FleetTicks(
    healths: List<Health>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    gap: Dp = 3.dp,
    corner: Dp = 3.dp,
) {
    Row(
        modifier
            .height(height)
            .clearAndSetSemantics { contentDescription = ticksSummary(healths) },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (healths.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner))
                    .background(NightbellColors.sheen(0.06f)),
            )
        }
        healths.forEach { health ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner))
                    .background(
                        healthColor(health)
                            .copy(alpha = if (health == Health.PAUSED) 0.35f else 0.9f),
                    ),
            )
        }
    }
}
