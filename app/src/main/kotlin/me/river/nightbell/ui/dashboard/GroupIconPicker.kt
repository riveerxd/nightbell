package me.river.nightbell.ui.dashboard

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.iconOriginOf
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * One site a group could take its mark from.
 *
 * [url] is a real member URL rather than a reconstructed origin, because that is
 * what [me.river.nightbell.data.icons.FaviconStore] is keyed on and what the card
 * will fetch. [origin] is only for deduplicating and for the label.
 */
data class IconCandidate(
    val origin: String,
    val url: String,
    val kindIcon: ImageVector,
)

/**
 * The sites a group's members can supply, one per origin, in draw order.
 *
 * Deduplicated because three monitors on one host would otherwise offer three
 * identical tiles and no way to tell which was which, a picker whose options
 * look the same is not a picker.
 */
fun iconCandidatesOf(members: List<MonitorCard>): List<IconCandidate> {
    val seen = mutableSetOf<String>()
    return members.mapNotNull { card ->
        val origin = iconOriginOf(card.monitor.url)
        if (origin.isBlank() || !seen.add(origin)) {
            null
        } else {
            IconCandidate(origin, card.monitor.url, kindIcon(card.monitor.kind))
        }
    }
}

/**
 * Choosing where a group's mark comes from.
 *
 * The design decision worth stating: **the previews are the control.** An earlier
 * version was a URL field with a live badge beside it, which worked for one site
 * and fell apart at two, the user's actual question is "that one or that one",
 * and no text field can be asked it. So every candidate is drawn at the size it
 * will appear, and picking one is looking at it and tapping.
 *
 * Three rules it has to keep, because each is a way this goes wrong:
 *
 *  - **No blank tiles.** A favicon may be unresolved, cached-negative, or the
 *    site may simply not have one. A tile whose image is missing falls back to
 *    the monitor's own kind glyph and always carries its host underneath, so it
 *    still says *which site* it is. The label is not decoration: two favicons are
 *    routinely both dark squares.
 *  - **The selected state cannot rely on colour.** A favicon can be any colour,
 *    including the accent, so selection is carried three ways at once, a ring, a
 *    tinted ground, and a check badge.
 *  - **Nothing is destroyed by switching.** Picking a site keeps the uploaded
 *    picture in the group, so going back to it is one tap and not a re-upload.
 *    Deleting it is a separate, labelled action on the tile.
 *
 * Semantically a radio group: [selectableGroup] plus [Role.RadioButton] per tile,
 * so a screen reader announces "2 of 3, selected" rather than reading three
 * unrelated buttons.
 */
@Composable
fun GroupIconPicker(
    candidates: List<IconCandidate>,
    /** Origin currently in use, or blank when the picture is selected. */
    selectedOrigin: String,
    pictureSelected: Boolean,
    picture: ImageBitmap?,
    accent: Color,
    onPickSite: (IconCandidate) -> Unit,
    onUsePicture: () -> Unit,
    onUploadPicture: () -> Unit,
    onDeletePicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Scrolls rather than wraps. A wrapped second row of two tiles reads as
            // a second, different set of options; a row that runs off the edge reads
            // as "there are more", which is what is true.
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        candidates.forEach { candidate ->
            val selected = !pictureSelected && candidate.origin == selectedOrigin
            // Blank url means "no icon for this one yet", see the typed-site
            // candidate in the editor. `enabled` keeps it off the network.
            val favicon = rememberFavicon(
                pageUrl = candidate.url,
                enabled = candidate.url.isNotBlank(),
            )
            IconChoiceTile(
                label = candidate.origin,
                selected = selected,
                accent = accent,
                image = favicon,
                fallbackIcon = candidate.kindIcon,
                // Names the consequence, not the widget: "use" is what the tap does.
                description = "Use the icon from ${candidate.origin}",
                onClick = { onPickSite(candidate) },
            )
        }

        if (picture != null) {
            IconChoiceTile(
                label = "Your picture",
                selected = pictureSelected,
                accent = accent,
                image = picture,
                fallbackIcon = NightbellIcons.Layers,
                description = "Use your own picture",
                onClick = onUsePicture,
                // Sits on the tile rather than in the row below, so "remove which
                // one" can never be ambiguous once a second picture source exists.
                onDelete = onDeletePicture,
                deleteDescription = "Delete your picture",
            )
        } else {
            UploadTile(accent = accent, onClick = onUploadPicture)
        }
    }
}

/** One tile: a preview at the size it will be used, its source, and its state. */
@Composable
private fun IconChoiceTile(
    label: String,
    selected: Boolean,
    accent: Color,
    image: ImageBitmap?,
    fallbackIcon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteDescription: String = "",
) {
    // Animated so a tap reads as this tile taking the ring off the last one,
    // rather than two tiles changing independently.
    val ring by animateDpAsState(if (selected) 2.dp else 1.dp, label = "iconRing")
    val ground by animateFloatAsState(if (selected) 0.16f else 0.05f, label = "iconGround")
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .width(TILE_COLUMN)
            .clip(RoundedCornerShape(14.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = accent),
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(TILE_SIZE)) {
            Box(
                Modifier
                    .size(TILE_SIZE)
                    .clip(shape)
                    .background(
                        if (selected) accent.copy(alpha = ground) else NightbellColors.sheen(ground),
                    )
                    .border(
                        BorderStroke(
                            ring,
                            if (selected) accent else NightbellColors.sheen(0.12f),
                        ),
                        shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // IconBadge already handles "image if there is one, glyph if not",
                // and it is what the dashboard card draws, so the preview is the
                // same component as the thing being previewed.
                IconBadge(
                    icon = fallbackIcon,
                    accent = if (selected) accent else NightbellColors.TextSecondary,
                    size = 38.dp,
                    image = image,
                )
            }

            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = NightbellIcons.Check,
                        contentDescription = null,
                        tint = NightbellColors.Void,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            if (onDelete != null) {
                // A 34dp target carrying a 21dp badge, and deliberately under the
                // 48dp floor the rest of the app keeps.
                //
                // The tile underneath is 60dp and is itself a target: it picks
                // this picture. A 48dp delete would cover four fifths of it and
                // make choosing a picture harder than removing one, which is the
                // wrong trade for the rarer and more destructive of the two. 34dp
                // is what the tile can give up, it is a large improvement on 21,
                // and the mis-tap it still allows selects the picture rather than
                // deleting it.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-10).dp)
                        .size(34.dp)
                        .clickable(
                            indication = ripple(bounded = false, color = NightbellColors.Rose),
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDelete,
                        )
                        .semantics { contentDescription = deleteDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(21.dp)
                            .clip(CircleShape)
                            .background(NightbellColors.ToastFill)
                            .border(BorderStroke(1.dp, NightbellColors.sheen(0.16f)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NightbellIcons.Close,
                            contentDescription = null,
                            tint = NightbellColors.TextSecondary,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) NightbellColors.TextPrimary else NightbellColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The way in for a picture, drawn as a tile so it reads as one more option.
 *
 * Dashed would have been the obvious "empty slot" treatment and is not available
 * without a custom border, so the distinction is carried by the glyph and the
 * label instead: a plus never reads as a chosen mark.
 */
@Composable
private fun UploadTile(accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .width(TILE_COLUMN)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                indication = ripple(color = accent),
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Upload a picture" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE_SIZE)
                .clip(shape)
                .background(NightbellColors.sheen(0.04f))
                .border(BorderStroke(1.dp, NightbellColors.sheen(0.10f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NightbellIcons.Plus,
                contentDescription = null,
                tint = NightbellColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Upload",
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 60 dp of preview, in a 76 dp column.
 *
 * The preview is bigger than the 42 dp badge it feeds so the choice can actually
 * be made, several site marks are only distinguishable above about 32 dp, and
 * the column is wider than the tile so a host label has somewhere to sit. Both
 * together clear the touch floor without the tile having to be enormous.
 */
private val TILE_SIZE = 60.dp
private val TILE_COLUMN = 76.dp
