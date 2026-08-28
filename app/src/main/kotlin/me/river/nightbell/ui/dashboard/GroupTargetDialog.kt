package me.river.nightbell.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.river.nightbell.domain.GroupMark
import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.mark
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.accentFor

/**
 * Which group the selected monitors should join.
 *
 * Only shown when there is something to choose between, with no groups yet the
 * selection bar goes straight to the editor, because a menu with one item is a
 * tap that asks a question with one answer.
 *
 * Rows rather than a list of names: each carries the group's own mark and its
 * current verdict, so "which Nightbell" is answerable by looking. The same three
 * things the dashboard card leads with, at row scale.
 */
@Composable
fun GroupTargetDialog(
    target: GroupTarget,
    groups: List<MonitorGroup>,
    cards: List<MonitorCard>,
    onAddTo: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = target.monitorIds.size
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(TARGET_SCRIM)

        GlassCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(NightbellRadii.sheet),
            corner = NightbellRadii.sheet,
            accent = NightbellColors.Aqua,
            contentPadding = 20.dp,
        ) {
            SectionHeader(
                title = "Add to group",
                icon = NightbellIcons.Layers,
                accent = NightbellColors.Aqua,
            )
            Text(
                text = if (count == 1) {
                    "One monitor selected. Pick where it goes."
                } else {
                    "$count monitors selected. Pick where they go."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )

            if (target.leavingGroups.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    // The one consequence nobody expects. A monitor belongs to at
                    // most one group, so this is a move, and a move that happened
                    // silently would read as another group losing a monitor for no
                    // reason.
                    text = if (target.leavingGroups.size == 1) {
                        "Already in “${target.leavingGroups.single()}”, moving out of it."
                    } else {
                        "Already grouped under ${target.leavingGroups.joinToString(", ")}, " +
                            "moving out of those."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.Amber,
                )
            }

            Spacer(Modifier.height(14.dp))

            groups.forEach { group ->
                GroupTargetRow(
                    group = group,
                    cards = cards,
                    // Every selected monitor already here means the tap would do
                    // nothing. Said out loud rather than left as a dead row.
                    alreadyHolds = target.monitorIds.all { it in group.memberIds },
                    onClick = { onAddTo(group.id) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))
            NightbellButton(
                text = "New group instead",
                onClick = onCreateNew,
                icon = NightbellIcons.Plus,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            NightbellButton(
                text = "Cancel",
                onClick = onDismiss,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One candidate group: its mark, its name, its size, and its current verdict. */
@Composable
private fun GroupTargetRow(
    group: MonitorGroup,
    cards: List<MonitorCard>,
    alreadyHolds: Boolean,
    onClick: () -> Unit,
) {
    val members = group.memberIds.mapNotNull { id -> cards.firstOrNull { it.monitor.id == id } }
    val rolled = GroupRollup.rolled(group, members)
    val (accent, _) = accentFor(group.accent)
    val markSource = group.mark(members.map { it.monitor.url })
    val picture = rememberGroupPicture(
        if (markSource is GroupMark.Picture) group.iconImage else "",
    )
    val favicon = rememberFavicon(
        pageUrl = (markSource as? GroupMark.Site)?.url.orEmpty(),
        enabled = markSource is GroupMark.Site,
    )
    val shape = RoundedCornerShape(NightbellRadii.inSheet)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NightbellColors.sheen(0.05f))
            .border(BorderStroke(1.dp, NightbellColors.sheen(0.10f)), shape)
            .clickable(
                enabled = !alreadyHolds,
                indication = ripple(color = accent),
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp)
            .semantics {
                contentDescription = buildString {
                    append(if (alreadyHolds) "Already in " else "Add to ")
                    append(group.displayTitle)
                    append(", ")
                    append(rolled.headline)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = NightbellIcons.Layers,
            accent = if (alreadyHolds) NightbellColors.TextTertiary else accent,
            size = 38.dp,
            image = picture ?: favicon,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (alreadyHolds) {
                    NightbellColors.TextTertiary
                } else {
                    NightbellColors.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (alreadyHolds) {
                    "Already in this group"
                } else {
                    val size = group.size
                    if (size == 1) "1 monitor · ${rolled.headline}" else "$size monitors"
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (alreadyHolds) {
            Icon(
                imageVector = NightbellIcons.Check,
                contentDescription = null,
                tint = NightbellColors.TextTertiary,
                modifier = Modifier.size(17.dp),
            )
        } else {
            // The group's own verdict, because "which one" is sometimes answered by
            // "the one that is currently broken".
            StatusOrb(health = rolled.health, size = 8.dp)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = NightbellIcons.ChevronRight,
                    contentDescription = null,
                    tint = NightbellColors.TextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** Matches the other dialogs: the platform default leaves the list too readable. */
private const val TARGET_SCRIM = 0.72f
