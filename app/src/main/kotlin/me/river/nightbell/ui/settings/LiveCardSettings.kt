package me.river.nightbell.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import me.river.nightbell.R
import me.river.nightbell.data.alerts.LiveCard
import me.river.nightbell.domain.LiveNotice
import me.river.nightbell.domain.LiveTimeline
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.ui.theme.LocalNowMs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.LiveCardPrefs
import me.river.nightbell.domain.LiveFollow
import me.river.nightbell.domain.LiveLabel
import me.river.nightbell.domain.LiveScope
import me.river.nightbell.domain.LiveWindow
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.components.ToggleRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * The three choices for the strict monitoring notice: whose line it draws, how
 * far back it reaches, and which figure sits at its end.
 *
 * Each one is saved the moment it is tapped, and saving wakes the service loop
 * that draws the notice, so the change is on the notification before the
 * finger is off the glass. No Save button, because there is nothing to confirm:
 * every choice can be undone with one more tap.
 *
 * Shown whether strict mode is on or not, so the card can be set up before it is
 * switched on, but it says when nothing will show yet.
 */
@Composable
fun LiveCardSettings(
    prefs: LiveCardPrefs,
    strict: Boolean,
    monitors: List<Monitor>,
    groups: List<MonitorGroup>,
    runtimes: Map<String, MonitorRuntime>,
    isOffline: () -> Boolean,
    checksPaused: Boolean,
    onResume: () -> Unit,
    onChange: ((LiveCardPrefs) -> LiveCardPrefs) -> Unit,
) {
    val scope = LiveScope.resolve(prefs, monitors, groups)
    // The line itself is an Android 16 template. Below it the same choices still
    // change the notice, but only its words, and the card says so instead of
    // letting someone pick a window and wonder where it went.
    val lineSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    GlassCard(
        accent = if (strict) NightbellColors.Aqua else Color.Transparent,
        modifier = Modifier.testTag("live-card"),
    ) {
        SectionHeader("Live card", icon = NightbellIcons.Activity, accent = NightbellColors.Aqua)
        Text(
            text = when {
                !strict -> "What the strict monitoring notification shows. It appears once strict " +
                    "foreground monitoring above is on, and you can set it up before then."
                checksPaused -> "Checks are paused, so strict monitoring is not running and there is " +
                    "no notification. The preview shows what it will say once you resume."
                lineSupported -> "What the strict monitoring notification draws. A change here is on " +
                    "the notification straight away."
                else -> "What the strict monitoring notification says. This phone is older than " +
                    "Android 16, which has no line to draw, so these change its text."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (strict && checksPaused) NightbellColors.Amber else NightbellColors.TextTertiary,
        )
        if (strict && checksPaused) {
            Spacer(Modifier.height(10.dp))
            NightbellButton(
                text = "Resume checks",
                onClick = onResume,
                icon = NightbellIcons.Play,
                tone = ButtonTone.Secondary,
            )
        }

        // What the notification will say with these choices, from the same function
        // the service posts with, against the fleet as it is right now.
        val now = LocalNowMs.current
        val offline = remember(now) { isOffline() }
        val notice = LiveNotice.of(
            monitors = monitors,
            runtimes = runtimes,
            groups = groups,
            prefs = prefs,
            nowMs = now,
            offline = offline,
            lineSupported = lineSupported,
        )
        NoticePreview(notice)

        Heading("Follows")
        SegmentedSelector(
            options = LiveFollow.entries,
            selected = prefs.follows,
            onSelect = { follows ->
                onChange { current ->
                    current.copy(
                        follows = follows,
                        targetId = defaultTarget(follows, current.targetId, monitors, groups),
                    )
                }
            },
            label = { it.label },
        )
        when (prefs.follows) {
            LiveFollow.EVERYTHING -> Unit
            LiveFollow.GROUP -> if (groups.isEmpty()) {
                Note(
                    "No groups yet. Hold two monitors on the dashboard and choose Group these 2, " +
                        "then pick it here. Until then the card follows everything.",
                )
            } else {
                Spacer(Modifier.height(4.dp))
                ChipSelector(
                    options = groups.map { it.id },
                    selected = prefs.targetId,
                    onSelect = { id -> onChange { it.copy(targetId = id) } },
                    label = { id -> groups.firstOrNull { it.id == id }?.displayTitle.orEmpty() },
                )
            }
            LiveFollow.MONITOR -> if (monitors.isEmpty()) {
                Note("No monitors yet, so the card has nothing to follow.")
            } else {
                Spacer(Modifier.height(4.dp))
                ChipSelector(
                    options = monitors.map { it.id },
                    selected = prefs.targetId,
                    onSelect = { id -> onChange { it.copy(targetId = id) } },
                    label = { id -> monitors.firstOrNull { it.id == id }?.displayName.orEmpty() },
                )
            }
        }
        if (scope.fellBack && monitors.isNotEmpty() && (prefs.follows != LiveFollow.GROUP || groups.isNotEmpty())) {
            Note(
                if (prefs.follows == LiveFollow.GROUP) {
                    "The group this followed is gone or empty, so the card shows everything until you pick another."
                } else {
                    "The monitor this followed is gone, so the card shows everything until you pick another."
                },
                color = NightbellColors.Amber,
            )
        }
        Note(
            when (prefs.follows) {
                LiveFollow.EVERYTHING -> "Every monitor that is not paused, on one line."
                LiveFollow.GROUP -> "Only this group's monitors, and the notification is titled with its name. " +
                    "An outage anywhere else is still mentioned under the line."
                LiveFollow.MONITOR -> "One monitor and its own history. An outage anywhere else is still " +
                    "mentioned under the line."
            },
        )

        Heading("Window")
        SegmentedSelector(
            options = LiveWindow.entries,
            selected = prefs.window,
            onSelect = { window -> onChange { it.copy(window = window) } },
            label = { it.label },
        )
        Note(
            when (prefs.window) {
                LiveWindow.AUTO -> "As far back as the stored checks go, up to a day."
                LiveWindow.HOUR -> "The last hour. Short outages read wide."
                LiveWindow.SIX_HOURS -> "The last six hours."
                LiveWindow.DAY -> "The last day. Grey where nothing was checked yet."
            },
        )

        Heading("Label at the end")
        SegmentedSelector(
            options = LiveLabel.entries,
            selected = prefs.label,
            onSelect = { label -> onChange { it.copy(label = label) } },
            label = { it.label },
        )
        Note(
            when (prefs.label) {
                LiveLabel.NEXT_CHECK -> "How long until the next check. The grey tail empties at the same pace."
                LiveLabel.UPTIME -> "Share of the checks in the window that passed."
                LiveLabel.LATENCY -> "The slowest reply right now, from the monitors that are answering."
            },
        )
    }
}

/**
 * Who a newly chosen mode should follow when nothing has been picked for it yet.
 *
 * The first group or monitor, so choosing "One group" takes effect on the very
 * tap rather than waiting for a second one, and an earlier pick of the same kind
 * is kept.
 */
private fun defaultTarget(
    follows: LiveFollow,
    current: String,
    monitors: List<Monitor>,
    groups: List<MonitorGroup>,
): String = when (follows) {
    LiveFollow.EVERYTHING -> current
    LiveFollow.GROUP -> if (groups.any { it.id == current }) current else groups.firstOrNull()?.id ?: current
    LiveFollow.MONITOR -> if (monitors.any { it.id == current }) current else monitors.firstOrNull()?.id ?: current
}

/**
 * The notification, drawn flat inside the settings card rather than as a second
 * card in it: an app line, the title, the text and the line, between two rules.
 *
 * The line is drawn from the same [LiveTimeline.Timeline] the notification gets
 * and in the same colour resources, so a band here is the band the shade shows.
 * The platform's own rendering differs in detail, corner radii and spacing, and
 * this does not pretend otherwise: it is a preview of the content.
 */
@Composable
private fun NoticePreview(notice: LiveNotice.Notice) {
    Spacer(Modifier.height(12.dp))
    Rule()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("live-card-preview")
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = NightbellIcons.Bell,
                contentDescription = null,
                tint = NightbellColors.Aqua,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Preview · Nightbell",
                style = MaterialTheme.typography.labelMedium,
                color = NightbellColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = notice.title,
            style = MaterialTheme.typography.titleMedium,
            color = NightbellColors.TextPrimary,
        )
        Text(
            text = notice.body,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
        notice.line?.let { line ->
            Spacer(Modifier.height(12.dp))
            PreviewLine(line)
        }
    }
    Rule()
}

@Composable
private fun PreviewLine(line: LiveTimeline.Timeline) {
    val max = line.max.coerceAtLeast(1)
    val tracker = colorFor(line.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            contentDescription = "Line covering the last ${line.spanLabel}, " +
                "${line.markers.size} outage starts, ending at ${line.endLabel}"
        },
    ) {
        EndLabel(line.spanShortLabel)
        Spacer(Modifier.width(8.dp))
        BoxWithConstraints(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
            val width = maxWidth
            Row(
                Modifier.fillMaxWidth().height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                line.bands.forEach { band ->
                    Box(
                        Modifier
                            .weight(band.length.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(colorFor(band.tone)),
                    )
                }
            }
            line.markers.forEach { marker ->
                Box(
                    Modifier
                        .offset(x = width * (marker.position.toFloat() / max) - 2.dp)
                        .size(width = 4.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorFor(marker.tone)),
                )
            }
            Box(
                Modifier
                    .offset(x = width * (line.progress.toFloat() / max) - 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(tracker),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.width(8.dp))
        EndLabel(line.endLabel)
    }
}

@Composable
private fun EndLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colorResource(R.color.live_label),
        maxLines = 1,
    )
}

@Composable
private fun colorFor(tone: LiveTimeline.Tone): Color = colorResource(
    when (tone) {
        LiveTimeline.Tone.UP -> R.color.live_up
        LiveTimeline.Tone.DEGRADED -> R.color.live_degraded
        LiveTimeline.Tone.DOWN -> R.color.live_down
        LiveTimeline.Tone.UNKNOWN -> R.color.live_unknown
        LiveTimeline.Tone.AHEAD -> R.color.live_ahead
    },
)

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(NightbellColors.sheen(0.08f)))
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = NightbellColors.TextPrimary,
    )
}

@Composable
private fun Note(text: String, color: Color = NightbellColors.TextTertiary) {
    Spacer(Modifier.height(6.dp))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
