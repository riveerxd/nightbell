@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package me.river.nightbell.ui.dashboard

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.nightbell.data.icons.GroupIcon
import me.river.nightbell.domain.GroupMark
import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.mark
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.MicroTag
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.StatusPill
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.LocalNightbellMotion
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.accentFor
import me.river.nightbell.ui.theme.healthColor
import me.river.nightbell.ui.theme.healthRim

/**
 * A group of monitors as one row.
 *
 * Built to answer one question before anything else on it is read: *is this
 * group operational*. That is why the roll-up sits where a monitor card puts its
 * host, directly under the name, in the line the eye lands on second, and why
 * the rim, the orb and the pill all carry the same verdict the members do. A
 * group that looked like a folder would be a container; this is meant to read as
 * a monitor that happens to be several.
 *
 * Tapping anywhere opens or shuts it. The pencil is the only other target, and
 * it is deliberately small: renaming a group is rare, and expanding one is not.
 */
@Composable
fun GroupCard(
    rolled: GroupRollup.Rolled,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = rolled.group
    val (accent, _) = accentFor(group.accent)
    val health = rolled.health

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

    GlassCard(
        accent = healthRim(health),
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(group.displayTitle)
                    append(", group of ")
                    append(rolled.total)
                    append(if (rolled.total == 1) " monitor, " else " monitors, ")
                    append(rolled.headline)
                    append(if (expanded) ", expanded, tap to collapse" else ", tap to expand")
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = NightbellIcons.Layers,
                accent = accent,
                size = 42.dp,
                image = picture ?: siteIcon,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The verdict, not the membership. "2 monitors" is on the tag row
                // below; what belongs here is whether they are all right.
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
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusOrb(health = health, checking = rolled.checking, size = 11.dp)
            // Rotated rather than swapped for an up-chevron, so the change reads as
            // the same object turning instead of one glyph replacing another.
            //
            // Springs, and slightly under-damped: the chevron is the one part of
            // the opening that is not a fade, and a touch of overshoot is what
            // makes the tap feel answered rather than merely obeyed. Snaps when
            // the user has asked for reduced motion.
            val motion = LocalNightbellMotion.current
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

        Spacer(Modifier.height(13.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Weighted and wrapping, so the pencil is measured before the tags
            // rather than after them. The same row on a monitor card lost a whole
            // control at a large font scale, and this one is the only route to
            // renaming or ungrouping.
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(health = health, checking = rolled.checking)
                MicroTag(
                    text = "${rolled.total}",
                    icon = NightbellIcons.Layers,
                    iconDescription = if (rolled.total == 1) "monitor" else "monitors",
                )
                if (rolled.urgentPending > 0) {
                    MicroTag(
                        text = if (rolled.urgentPending == 1) "Urgent" else "${rolled.urgentPending} urgent",
                        color = NightbellColors.Rose,
                        background = NightbellColors.Rose.copy(alpha = 0.16f),
                        icon = NightbellIcons.Zap,
                    )
                }
                if (rolled.paused in 1 until rolled.total) {
                    MicroTag(
                        text = "${rolled.paused} paused",
                        color = NightbellColors.Amber,
                        background = NightbellColors.Amber.copy(alpha = 0.14f),
                        icon = NightbellIcons.Pause,
                    )
                }
                if (rolled.slowestLatencyMs > 0) {
                    MicroTag(
                        // The slowest member, labelled as such. A group average would be
                        // a number that describes nothing anybody is watching.
                        text = formatLatency(rolled.slowestLatencyMs),
                        color = if (health == Health.DEGRADED) {
                            NightbellColors.Amber
                        } else {
                            NightbellColors.TextSecondary
                        },
                        icon = NightbellIcons.Gauge,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            GlassIconButton(
                icon = NightbellIcons.Pencil,
                onClick = onEdit,
                contentDescription = "Edit group ${group.displayTitle}",
                accent = NightbellColors.TextSecondary,
                size = 32.dp,
            )
        }

        if (rolled.total == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                // Names the pencil, because tapping the card is what expands it.
                // "Open it to rename or ungroup" sent the user into an empty
                // expansion holding neither of the two things it promised.
                text = "Nothing in here any more. Tap the pencil to rename or ungroup it.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
}

/**
 * Draws a member of an expanded group.
 *
 * The rail and the indent are the whole mechanism: a member is a full monitor
 * card, emitted as its own item in the same grid, and the only thing saying it
 * belongs to the group above it is this. That was worth more than nesting the
 * cards, which would have cost the drag handle, the long-press and the grid's
 * ability to virtualise the list.
 */
@Composable
fun GroupedMember(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        // matchParentSize, not fillMaxHeight: this sits in a lazy grid, where the
        // incoming max height is unbounded and "fill" has nothing to fill. Matching
        // the resolved size of the Box measures the rail against the card beside it
        // and keeps the rail out of the Box's own size calculation.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopStart) {
            Box(
                Modifier
                    .padding(start = (GROUP_RAIL_INSET - 2.dp) / 2)
                    .width(2.dp)
                    // Full height on every member, the last one included.
                    //
                    // The last member's rail used to stop at half height, meant to
                    // read as the column ending. It does not: the fraction is
                    // proportional, so where it stops depends on how tall that card
                    // happens to be, and beside a tall one, a page-element monitor
                    // with a sparkline, say, it terminates in the middle of a chart
                    // for no reason a reader can see. It looks like the line failed
                    // to draw.
                    //
                    // The column does not need a terminus drawn: the next top-level
                    // card has no rail beside it, and the arrangement gap is already
                    // there. Ending exactly where the last member ends is both the
                    // truth and the same at every card height.
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(accent.copy(alpha = 0.30f)),
            )
        }
        Column(Modifier.padding(start = GROUP_RAIL_INSET), content = content)
    }
}

/** Left inset for a grouped card, and the width of its rail gutter. */
val GROUP_RAIL_INSET = 18.dp

/**
 * A group's members, laid out as a labelled column footer.
 *
 * Shown when an expanded group has nothing in it, so the expand gesture is never
 * answered with silence.
 */
@Composable
fun GroupEmptyMembers(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = GROUP_RAIL_INSET),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NightbellIcons.Info,
            contentDescription = null,
            tint = NightbellColors.TextTertiary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "No monitors in this group",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
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
