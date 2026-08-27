package me.river.nightbell.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * "There is a newer Nightbell", on the dashboard.
 *
 * A banner and not a dialog, deliberately. A dialog on launch is the pattern
 * people call nagware, and it would cover the one thing this app exists to show
 * at the one moment it matters: opening it at three in the morning to find out
 * what is broken. The fleet verdict stays the first thing on the screen and this
 * sits under it, where it can be read and then ignored.
 *
 * Sky rather than amber or rose. Every warm colour in this app already means
 * something about a monitor being unwell, and a new release is not a fault. Sky
 * is what the dashboard already uses for "known, not a problem", which is exactly
 * what this is.
 *
 * Whether it appears at all is [AppUpdate.bannerFor], not a condition here, so
 * the rules are a JVM test rather than something only a device can answer.
 */
@Composable
fun UpdateBanner(
    banner: AppUpdate.Banner,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier, accent = NightbellColors.Sky, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(NightbellIcons.Sparkle, NightbellColors.Sky, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Nightbell ${banner.latestVersion} is available",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    // Both numbers, because "an update is available" invites the
                    // question this answers in the same breath.
                    text = "You are on ${banner.installedVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = NightbellIcons.Close,
                onClick = onDismiss,
                // Says what it does rather than what it looks like. "Dismiss" would
                // not tell a screen reader that this is permanent for this version,
                // which is the part worth knowing before tapping it.
                contentDescription = "Don't show ${banner.latestVersion} again",
                accent = NightbellColors.TextSecondary,
                size = 34.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightbellButton(
                text = "What's new",
                onClick = { onOpen(banner.url) },
                icon = NightbellIcons.Link,
                tone = ButtonTone.Secondary,
                accent = NightbellColors.Sky,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
