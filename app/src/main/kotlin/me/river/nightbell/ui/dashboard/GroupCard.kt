@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package me.river.nightbell.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.river.nightbell.data.icons.GroupIcon
import me.river.nightbell.domain.GroupMark
import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.GroupRoster
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.effectiveHealth
import me.river.nightbell.domain.mark
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.LocalNightbellMotion
import me.river.nightbell.ui.theme.LocalNowMs
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.accentFor
import me.river.nightbell.ui.theme.healthColor
import me.river.nightbell.ui.theme.healthRim

/** What a member row can do, handed down from the screen that owns the actions. */
class MemberActions(
    val onOpen: (String) -> Unit,
    val onLongPress: (String) -> Unit,
    val onCheck: (String) -> Unit,
    val onSetEnabled: (String, Boolean) -> Unit,
    val onAcknowledge: (String) -> Unit,
    /** Moves a member one place within the dashboard order, or null at that end. */
    val moveBy: (String, Int) -> (() -> Unit)?,
)

/**
 * A group of monitors, and the monitors in it, as one card.
 *
 * Shut, it answers two questions in this order: is the group all right, and if
 * not, which member is the problem. The second used to need a tap. The card said
 * "1 of 3 is down" four ways at once (rim, orb, line, pill) and never the name,
 * so the problem members are now listed by name under the header and everything
 * that is fine folds into one counted line.
 *
 * Open, the members are rows inside the same surface rather than full monitor
 * cards after it. Three members used to fill a phone screen, and the rail that
 * said "these belong to the group above" was a two pixel line nobody read as
 * grouping. A row keeps what a member needs from the dashboard: open it, hold to
 * select it, pause it, check it now, acknowledge an urgent page. The rest of a
 * monitor card is one tap away on its own screen.
 *
 * What this cost: a member can no longer be dragged to a new place, because the
 * drag finds drop targets by grid item and a row is not one. Move up and Move
 * down are still there as accessibility actions, which is the same route a
 * screen reader always had.
 */
@Composable
fun GroupCard(
    rolled: GroupRollup.Rolled,
    expanded: Boolean,
    selecting: Boolean,
    selection: Set<String>,
    offline: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    actions: MemberActions,
    modifier: Modifier = Modifier,
) {
    val group = rolled.group
    val (accent, _) = accentFor(group.accent)
    val health = rolled.health
    val now = LocalNowMs.current
    val motion = LocalNightbellMotion.current

    // One resolution, shared with the editor's preview via `mark()`, so the tile
    // shown as selected in the picker cannot disagree with the badge drawn here.
    val mark = group.mark(rolled.members.map { it.monitor.url })
    val picture = rememberGroupPicture(
        if (mark is GroupMark.Picture) group.iconImage else "",
    )
    val siteIcon = rememberFavicon(
        pageUrl = (mark as? GroupMark.Site)?.url.orEmpty(),
        enabled = mark is GroupMark.Site,
    )
    val peek = remember(rolled.members) { GroupRoster.peek(rolled.members) }

    // Height, not just a fade: the rows below have to make room, and a card that
    // grows in one frame reads as the list jumping. Snaps with reduced motion.
    val open = if (motion.enabled) {
        expandVertically(tween(motion.scale(190))) + fadeIn(tween(motion.scale(190)))
    } else {
        EnterTransition.None
    }
    // Faster than the way in. Closing is a dismissal, and making someone watch it
    // leave at the speed it arrived is the wrong side of the trade.
    val shut = if (motion.enabled) {
        shrinkVertically(tween(motion.scale(130))) + fadeOut(tween(motion.scale(130)))
    } else {
        ExitTransition.None
    }

    GlassCard(
        accent = healthRim(health),
        contentPadding = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = buildString {
                        append(group.displayTitle)
                        append(", group of ")
                        append(rolled.total)
                        append(if (rolled.total == 1) " monitor, " else " monitors, ")
                        append(rolled.headline)
                        append(if (expanded) ", expanded, tap to collapse" else ", tap to expand")
                    }
                }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        ) {
            IconBadge(
                icon = NightbellIcons.Layers,
                accent = accent,
                size = 38.dp,
                image = picture ?: siteIcon,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The verdict. Its own node so the test and TalkBack read the
                    // same sentence the fleet banner uses.
                    Text(
                        text = rolled.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (health == Health.UP || health == Health.PAUSED) {
                            NightbellColors.TextTertiary
                        } else {
                            healthColor(health)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The slowest member, labelled as such, only while nothing is
                    // wrong. With a member down the rows below carry the numbers
                    // that matter and this would be a fourth figure to read.
                    if (peek == null && rolled.slowestLatencyMs > 0 && health == Health.UP) {
                        Text(
                            text = " · slowest ${formatLatency(rolled.slowestLatencyMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            StatusOrb(health = health, checking = rolled.checking, size = 11.dp)
            Spacer(Modifier.width(8.dp))
            // Rotated rather than swapped for an up-chevron, so the change reads
            // as the same object turning. Slightly under-damped: the one part of
            // the opening that is not a fade, and the overshoot is what makes the
            // tap feel answered. Snaps when motion is reduced.
            val turn by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = if (motion.enabled) {
                    spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMedium)
                } else {
                    snap()
                },
                label = "groupChevron",
            )
            Icon(
                imageVector = NightbellIcons.ChevronDown,
                contentDescription = null,
                tint = NightbellColors.TextTertiary,
                modifier = Modifier.size(19.dp).rotate(turn),
            )
        }

        // Held through the exit, so a group whose last problem clears shrinks shut
        // instead of jumping: with the live value the rows were gone in the same
        // frame the animation started and there was nothing left to collapse.
        var shownPeek by remember { mutableStateOf(peek) }
        if (peek != null) shownPeek = peek
        AnimatedVisibility(visible = !expanded && peek != null, enter = open, exit = shut) {
            val peek = shownPeek
            if (peek != null) {
                Column {
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                    peek.problems.forEach { card ->
                        MemberRow(
                            card = card,
                            full = false,
                            selecting = selecting,
                            selected = card.monitor.id in selection,
                            offline = offline,
                            now = now,
                            actions = actions,
                        )
                    }
                    if (peek.rest.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .padding(start = 20.dp, end = 16.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(healthColor(peek.restHealth)),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = peek.rest,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NightbellColors.TextTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        AnimatedVisibility(visible = expanded, enter = open, exit = shut) {
            Column {
                rolled.members.forEach { card ->
                    Hairline()
                    MemberRow(
                        card = card,
                        full = true,
                        selecting = selecting,
                        selected = card.monitor.id in selection,
                        offline = offline,
                        now = now,
                        actions = actions,
                    )
                }
                if (rolled.members.isEmpty()) {
                    Hairline()
                    Text(
                        text = "No monitors in this group. Add one by holding it on the " +
                            "dashboard and choosing Add to a group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                Hairline()
                EditGroupButton(title = group.displayTitle, onEdit = onEdit)
            }
        }

        // A group emptied from outside, by deleting its last monitor, still needs
        // a way to be renamed or ungrouped without opening it first.
        if (!expanded && rolled.total == 0) {
            Hairline()
            EditGroupButton(title = group.displayTitle, onEdit = onEdit)
        }
    }
}

/**
 * One member, as a row.
 *
 * [full] is the open group's row: the monitor's kind and address under its name,
 * and its own pause and check controls. The shut card's rows are a summary, so
 * they carry the name and the state and nothing to mis-tap.
 */
@Composable
private fun MemberRow(
    card: MonitorCard,
    full: Boolean,
    selecting: Boolean,
    selected: Boolean,
    offline: Boolean,
    now: Long,
    actions: MemberActions,
) {
    val monitor = card.monitor
    val id = monitor.id
    val health = card.effectiveHealth
    val detail = GroupRoster.detail(card, now)
    val urgentPending = monitor.urgent && card.runtime.urgentState.nagging
    val moveUp = actions.moveBy(id, -1)
    val moveDown = actions.moveBy(id, 1)

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                // While picking several, a tap toggles. Opening a monitor in the
                // middle of a selection is never what was meant.
                onClick = { if (selecting) actions.onLongPress(id) else actions.onOpen(id) },
                onLongClick = { actions.onLongPress(id) },
            )
            .semantics {
                if (selecting) this.selected = selected
                contentDescription = buildString {
                    append(monitor.displayName)
                    append(", ")
                    append(detail)
                    if (urgentPending) append(", urgent, not acknowledged")
                    append(if (selecting) ", tap to select" else ", open details, or hold to select")
                }
                customActions = buildList {
                    moveUp?.let { add(CustomAccessibilityAction("Move up") { it(); true }) }
                    moveDown?.let { add(CustomAccessibilityAction("Move down") { it(); true }) }
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (full) 60.dp else 40.dp)
                .padding(start = 16.dp, end = if (full) 6.dp else 16.dp),
        ) {
            // The orb's slot holds the tick while selecting, so nothing on the row
            // moves when the mode flips.
            Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                if (selecting) {
                    RowTick(selected)
                } else {
                    StatusOrb(health = health, checking = card.checking, size = 9.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = monitor.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = NightbellColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (full) {
                    Text(
                        text = "${monitor.kind.label} · ${monitor.prettyHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (urgentPending) {
                Icon(
                    imageVector = NightbellIcons.Zap,
                    contentDescription = null,
                    tint = NightbellColors.Rose,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = when (health) {
                    Health.DOWN, Health.DEGRADED -> healthColor(health)
                    else -> NightbellColors.TextSecondary
                },
                maxLines = 1,
            )
            // Per-row actions step aside while selecting: a check that fires a real
            // request is the last thing that should be one mis-tap away while the
            // finger is busy picking rows. Neither can do anything for a kind this
            // build cannot run.
            if (full && !selecting && monitor.kind != MonitorKind.UNSUPPORTED) {
                Spacer(Modifier.width(2.dp))
                RowIconButton(
                    icon = if (monitor.enabled) NightbellIcons.Pause else NightbellIcons.Play,
                    contentDescription = if (monitor.enabled) {
                        "Pause ${monitor.displayName}"
                    } else {
                        "Resume ${monitor.displayName}"
                    },
                    onClick = { actions.onSetEnabled(id, !monitor.enabled) },
                )
                // Greyed with no connection, the same as the fleet's own check all.
                RowIconButton(
                    icon = if (offline) NightbellIcons.WifiOff else NightbellIcons.Refresh,
                    contentDescription = if (offline) {
                        "Check ${monitor.displayName} now, waiting for a connection"
                    } else {
                        "Check ${monitor.displayName} now"
                    },
                    onClick = { actions.onCheck(id) },
                    enabled = !card.checking && !offline,
                    tint = NightbellColors.Aqua,
                )
            }
        }
        // The one action a row cannot fold away: an urgent page that is still
        // ringing has to be stoppable from wherever the monitor is drawn.
        if (full && urgentPending) {
            NightbellButton(
                text = "Acknowledge urgent alert",
                onClick = { actions.onAcknowledge(id) },
                icon = NightbellIcons.Check,
                tone = ButtonTone.Danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun EditGroupButton(title: String, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onEdit)
                .semantics(mergeDescendants = true) { contentDescription = "Edit group $title" }
                .padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = NightbellIcons.Pencil,
                contentDescription = null,
                tint = NightbellColors.Aqua,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Edit group",
                style = MaterialTheme.typography.labelLarge,
                color = NightbellColors.Aqua,
            )
        }
    }
}

/** A bare icon control: the row is already a surface, so no tile behind it. */
@Composable
private fun RowIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = NightbellColors.TextSecondary,
    size: Dp = 44.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 20.dp, color = tint),
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RowTick(selected: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(16.dp)
            .clip(shape)
            .background(if (selected) NightbellColors.Aqua else NightbellColors.sheen(0.07f))
            .border(1.dp, if (selected) NightbellColors.Aqua else NightbellColors.sheen(0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                NightbellIcons.Check,
                contentDescription = null,
                tint = NightbellColors.Void,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(NightbellColors.sheen(0.07f)),
    )
}

/**
 * Decodes a group's picked picture once per distinct string.
 *
 * Keyed on the base64 rather than on the group, so scrolling a group card in and
 * out of view does not re-run `BitmapFactory` and editing another field of the
 * same group does not either.
 */
@Composable
fun rememberGroupPicture(encoded: String): ImageBitmap? = remember(encoded) {
    if (encoded.isBlank()) null else GroupIcon.decode(encoded)?.asImageBitmap()
}
