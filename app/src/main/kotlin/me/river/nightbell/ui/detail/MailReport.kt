package me.river.nightbell.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.MailState
import me.river.nightbell.domain.Monitor
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassDivider
import me.river.nightbell.ui.components.MetricTile
import me.river.nightbell.ui.components.MicroTag
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * The folder as the last check left it: unread and total, then what arrived
 * since the monitor started watching, newest first. Before the first check it
 * says what will happen instead of showing zeros it does not know.
 */
@Composable
internal fun MailboxCard(
    monitor: Monitor,
    state: MailState,
    nowMs: Long,
    accent: Color,
    onOpenWebmail: (String) -> Unit,
) {
    val folder = monitor.mail.cleanFolder
    GlassCard(Modifier.fillMaxWidth().testTag("mailbox-card")) {
        SectionHeader(
            if (folder.equals("INBOX", ignoreCase = true)) "Inbox" else folder,
            icon = NightbellIcons.Mail,
            accent = accent,
        )
        if (!state.seeded) {
            Text(
                text = "Not read yet. The first check notes where the folder is and announces nothing, " +
                    "so what you hear about is mail that arrives after it.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(
                    label = "Unread",
                    value = if (state.unseen >= 0) state.unseen.toString() else "?",
                    modifier = Modifier.weight(1f).testTag("mailbox-unread"),
                    accent = accent,
                )
                MetricTile(
                    label = "In folder",
                    value = if (state.messages >= 0) state.messages.toString() else "?",
                    modifier = Modifier.weight(1f),
                    accent = NightbellColors.TextSecondary,
                )
                MetricTile(
                    label = "Last arrival",
                    value = if (state.lastArrivalAt > 0L) formatRelative(state.lastArrivalAt, nowMs) else "none yet",
                    modifier = Modifier.weight(1f),
                    accent = NightbellColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (state.recent.isEmpty()) {
                Text(
                    text = "Nothing has arrived since this monitor started watching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            } else {
                Text(
                    text = "Arrived since watching",
                    style = MaterialTheme.typography.labelMedium,
                    color = NightbellColors.TextTertiary,
                )
                state.recent.forEachIndexed { index, message ->
                    if (index > 0) GlassDivider(Modifier.padding(vertical = 2.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("mailbox-row"),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = message.from,
                                style = MaterialTheme.typography.titleSmall,
                                color = NightbellColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = message.subjectOrPlaceholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatRelative(message.receivedAt, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = NightbellColors.TextTertiary,
                            )
                            if (message.matched) {
                                Spacer(Modifier.height(4.dp))
                                MicroTag(text = "Alert", color = accent, icon = NightbellIcons.Zap)
                            }
                        }
                    }
                }
            }
        }
        val webmail = monitor.mail.webmailUrl.trim()
        if (webmail.startsWith("https://") || webmail.startsWith("http://")) {
            Spacer(Modifier.height(12.dp))
            NightbellButton(
                text = "Open webmail",
                onClick = { onOpenWebmail(webmail) },
                tone = ButtonTone.Secondary,
                icon = NightbellIcons.Link,
                modifier = Modifier.fillMaxWidth().testTag("mailbox-webmail"),
            )
        }
    }
}
