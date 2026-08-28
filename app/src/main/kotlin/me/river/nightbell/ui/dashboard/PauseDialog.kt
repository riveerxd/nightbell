package me.river.nightbell.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import me.river.nightbell.ui.PausePrompt
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii

/**
 * The pause question, asked properly.
 *
 * This started as an expansion inside the fleet banner, which was wrong on two
 * counts: the banner is a readout and stopped being one the moment it grew a
 * form, and five duration chips in a flow row wrapped to a ragged second line on
 * every phone narrower than a tablet. A dialog is the honest shape for it. It is
 * a question with a handful of answers, asked once, and answered before anything
 * else happens.
 *
 * Answers are full-width rows rather than chips on purpose. There is no ragged
 * edge to wrap, every target is the same size, and the two scope options have
 * room for the sentence that tells them apart, which is the whole difficulty of
 * choosing between them.
 */
@Composable
fun PauseDialog(
    prompt: PausePrompt,
    onChooseScope: (PauseScope) -> Unit,
    onPauseFor: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The default width is a Material measurement and this is not a Material
        // dialog: it is sized to the app's own card margins instead.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The platform's default dim leaves the dashboard behind this perfectly
        // legible, which makes a modal question look like a card that landed on
        // top of the page. There is no parameter for it, so the window is asked
        // directly.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(SCRIM)

        GlassCard(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(NightbellRadii.sheet),
            corner = NightbellRadii.sheet,
            accent = NightbellColors.Amber,
            contentPadding = 20.dp,
        ) {
            val scope = prompt.scope
            // Scrolls because it has to: five rows plus a header at the largest
            // font scale, or in landscape, is taller than the window, and the only
            // affordance that would clip is Cancel.
            //
            // SectionHeader rather than a bare label, because that is what every
            // other card in this app puts at the top: an icon, the title, and a
            // rule running out to the edge. A lone tracked-out caption floating
            // above the content belonged to no part of the design and read as a
            // piece of some other app.
            SectionHeader(
                title = if (scope == null) "Pause monitoring" else "For how long",
                icon = if (scope == null) NightbellIcons.Pause else NightbellIcons.Clock,
                accent = NightbellColors.Amber,
            )

            if (scope == null) {
                Text(
                    // Says what both options share and nothing else. It used to
                    // promise "nothing is checked or announced", directly above
                    // the option whose whole point is that checks keep running.
                    text = "Nothing pages you until it lifts. Pick what else stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
                Spacer(Modifier.height(14.dp))
                PauseScope.entries.forEach { option ->
                    PauseOption(
                        title = option.label,
                        detail = option.blurb,
                        icon = when (option) {
                            PauseScope.STOP_CHECKS -> NightbellIcons.Power
                            PauseScope.ALERTS_ONLY -> NightbellIcons.BellOff
                        },
                        onClick = { onChooseScope(option) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                Text(
                    text = when (scope) {
                        PauseScope.STOP_CHECKS -> "Checks stop and start again by themselves."
                        PauseScope.ALERTS_ONLY -> "Checks carry on, nothing pages you."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
                Spacer(Modifier.height(14.dp))
                PauseState.OFFERED_MINUTES.forEach { minutes ->
                    PauseOption(
                        title = minutes?.let(::durationTitle) ?: "Until I resume",
                        detail = if (minutes == null) "Nothing lifts this but you" else "",
                        // The open-ended one is the odd one out and should look it:
                        // every other row here is a clock winding down.
                        icon = if (minutes == null) NightbellIcons.Pause else NightbellIcons.Clock,
                        onClick = { onPauseFor(minutes) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(2.dp))
            NightbellButton(
                text = "Cancel",
                onClick = onDismiss,
                tone = ButtonTone.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One answer. Full width, so five of them stack without a ragged edge.
 *
 * Title over detail rather than beside it. Side by side, "Stop checking" was
 * squeezed into a third of the row while its sentence took the rest, which made
 * the two options hard to tell apart at a glance: the part that names the choice
 * was the part with the least room.
 */
@Composable
private fun PauseOption(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NightbellRadii.inSheet)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NightbellColors.sheen(0.06f))
            .border(BorderStroke(1.dp, NightbellColors.sheen(0.10f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same badge every ToggleRow and StepperRow in the app leads with, at
        // the size those rows use. Without it these were the only rows in Nightbell
        // with nothing down the left, which is most of what made the sheet look
        // like it came from somewhere else.
        IconBadge(icon = icon, accent = NightbellColors.Amber, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = NightbellColors.TextPrimary,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }
        Icon(
            imageVector = NightbellIcons.ChevronRight,
            contentDescription = null,
            tint = NightbellColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Dark enough that the dashboard reads as out of play rather than merely behind. */
private const val SCRIM = 0.72f

/** "30 minutes", "1 hour", "4 hours". Spelled out, unlike the banner's countdown. */
private fun durationTitle(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}
