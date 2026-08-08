package me.river.pulse.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassDivider
import me.river.pulse.ui.components.MinTouchTarget
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.icons.NightbellIcons
import me.river.pulse.ui.theme.NightbellColors

/**
 * In-app help.
 *
 * Everything here was already written down — in the README, in `docs/reference.md`
 * and in the comments — and none of it was reachable from inside the app. The
 * questions chosen are the ones the design actively invites: Nightbell deliberately
 * does surprising things (it clamps intervals visibly, it refuses to call a slow
 * connection an outage, it acknowledges rather than dismisses), and a surprising
 * behaviour with no explanation reads as a bug.
 *
 * Collapsed by default, one open at a time, because a wall of prose in Settings is
 * a wall nobody reads.
 */
private data class HelpTopic(val question: String, val answer: String)

private val topics = listOf(
    HelpTopic(
        "Why is my monitor checked less often than I set?",
        "Android's scheduler has a hard fifteen-minute floor for periodic background " +
            "work, and it silently clamps anything shorter. Nightbell clamps it visibly " +
            "instead, and a sweep picks up whatever is overdue on every wake. If you " +
            "genuinely need a tighter cadence, Strict cadence above runs a foreground " +
            "service that keeps your interval exactly — at the cost of a permanent " +
            "notification and real battery. That is Android's price, not a setting " +
            "Nightbell is withholding.",
    ),
    HelpTopic(
        "What does acknowledging an urgent alert actually do?",
        "It stops the repeats for that outage and nothing else. The monitor stays " +
            "down, its card stays red, and the ordinary down notification stays where " +
            "it was. When the monitor recovers the loop re-arms, so the next outage " +
            "shouts again. It means “I have seen this”, not “stop watching”.",
    ),
    HelpTopic(
        "Why did a slow response not trigger a latency alert?",
        "Because the slowness may have been yours. Nightbell times a known-good endpoint " +
            "alongside your checks and discounts whatever your own connection appears " +
            "to be adding before calling a monitor slow — otherwise bad wifi makes " +
            "every monitor breach its budget at once and every one of those alerts is " +
            "wrong. When a reading has been adjusted the card shows a −time tag; when " +
            "the connection was too poor to judge through at all, it shows " +
            "“connection” instead.",
    ),
    HelpTopic(
        "What is the difference between down, degraded and paused?",
        "Down means the check failed: unreachable, wrong status, or an assertion that " +
            "did not hold. Degraded means it succeeded but took longer than its " +
            "latency budget — the service answered, so it is amber rather than red. " +
            "Paused means you switched it off and nothing is being checked. Muted is " +
            "different again: still checked, still shown, just not allowed to make a " +
            "noise.",
    ),
    HelpTopic(
        "What does “24h uptime” count?",
        "The share of checks in the last twenty-four hours that passed. When the " +
            "history does not reach back that far the label says how far it does reach " +
            "instead — a monitor added an hour ago cannot report a day, and reporting " +
            "one anyway would be the easiest number in the app to mislead yourself " +
            "with.",
    ),
    HelpTopic(
        "How do certificate warnings work?",
        "Every HTTPS check completes a handshake, and the handshake carries the " +
            "certificate's expiry date, so watching it is free. Nightbell says something " +
            "once when a certificate crosses each threshold and then at most once a " +
            "day, never urgently and never during quiet hours — there is nothing to do " +
            "at 3am about a certificate with nine days left. A renewal clears the " +
            "notice on the next check.",
    ),
    HelpTopic(
        "Does anything leave my phone?",
        "No. There is no account, no server and no telemetry; the checks are made by " +
            "your phone directly to the things you pointed it at. Android's automatic " +
            "cloud backup is switched off on purpose, because your monitors' request " +
            "headers can contain API tokens and that backup would have uploaded them. " +
            "Moving to a new phone is the JSON export in Backup and transfer.",
    ),
    HelpTopic(
        "Why is pull-to-refresh a two-stage gesture?",
        "Re-checking everything fires a real request at every endpoint you watch, all " +
            "at once. That should not be reachable by an accidental over-scroll, so " +
            "past the threshold you have to hold for a moment while a ring closes. " +
            "Releasing early cancels. The banner's “Check all now” is the one-tap " +
            "route when you actually mean it.",
    ),
    HelpTopic(
        "An element monitor says the element is missing, but I can see it.",
        "Element checks load the page in an offscreen browser and wait a few seconds " +
            "for it to settle. A page that hydrates more slowly than that, or one that " +
            "blocks headless browsers, can genuinely render for you and not for Nightbell. " +
            "Raising the monitor's timeout helps; re-capturing the element from the " +
            "live preview helps more, because the stored signature falls back through " +
            "id, then CSS path, then XPath, then a text fingerprint.",
    ),
)

@Composable
fun HelpCard() {
    var openQuestion by remember { mutableStateOf<String?>(null) }
    GlassCard {
        SectionHeader("Help", icon = NightbellIcons.Info, accent = NightbellColors.Sky)
        Text(
            text = "The behaviours people ask about most, including the deliberately " +
                "surprising ones.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        topics.forEachIndexed { index, topic ->
            val open = openQuestion == topic.question
            // One at a time: nine expanded answers is the wall of prose this is
            // meant to replace.
            val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { openQuestion = if (open) null else topic.question }
                        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = topic.question,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (open) NightbellColors.TextPrimary else NightbellColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        NightbellIcons.ChevronDown,
                        contentDescription = null,
                        tint = NightbellColors.TextTertiary,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                    )
                }
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        text = topic.answer,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 12.dp, end = 26.dp),
                    )
                }
            }
            if (index < topics.lastIndex) GlassDivider(alpha = 0.06f)
        }
    }
}
