package me.river.nightbell.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.river.nightbell.data.icons.GroupIcon
import me.river.nightbell.domain.GroupIconChoice
import me.river.nightbell.domain.GroupMark
import me.river.nightbell.domain.iconOriginOf
import me.river.nightbell.domain.mark
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.DismissKeyboardOnOutsideTap
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.accentFor

/**
 * Naming a group, and choosing its mark.
 *
 * Two fields and a member list, in that order, because that is the order of the
 * decisions: what is this called, what does it look like, and is the right stuff
 * in it. The icon field takes a *page* rather than an image, so the answer to
 * "what does this look like" is almost always a URL the user already has.
 *
 * A dialog rather than a screen. Creating a group happens with several monitors
 * selected on the dashboard, and pushing a route would throw that selection away
 * to ask two questions.
 */
@Composable
fun GroupEditorDialog(
    draft: GroupDraft,
    members: List<MonitorCard>,
    onChange: ((MonitorGroup) -> MonitorGroup) -> Unit,
    onSave: () -> Unit,
    onUngroup: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val group = draft.group
    val (accent, _) = accentFor(group.accent)
    var confirmUngroup by remember { mutableStateOf(false) }

    // The picture is decoded whether or not it is the chosen source, because the
    // picker draws it as an option you can switch back to.
    val picture = rememberGroupPicture(group.iconImage)
    val memberCandidates = iconCandidatesOf(members)
    // Resolved by the same function the dashboard card uses, so the tile drawn as
    // selected is the mark that will actually appear. Two independent guesses at
    // "which one is in use" is how a picker ends up lying.
    val mark = group.mark(members.map { it.monitor.url })
    val pictureSelected = mark is GroupMark.Picture
    val markOrigin = (mark as? GroupMark.Site)?.url?.let(::iconOriginOf).orEmpty()

    // A site the user typed that no member shares gets a tile of its own.
    //
    // Without one, typing an address produced *no* visible change: the row showed
    // only the members' sites, none of them selected, and the mark in force was
    // accounted for nowhere on screen. It was applied, it saved fine, but the
    // interface never said so, which is indistinguishable from a field that does
    // not work. The row has to hold every source currently on the table.
    val typedIsItsOwnSite = markOrigin.isNotBlank() &&
        memberCandidates.none { it.origin == markOrigin }
    // Fetched from a settled value, not from every keystroke: `rememberFavicon`
    // asks the network per distinct URL, and "h", "ht", "htt" are three lookups
    // for hosts that do not exist. The *tile* still appears immediately, on the
    // glyph, so typing has feedback before the icon lands.
    var settledIconUrl by remember(group.id) { mutableStateOf(group.iconUrl) }
    LaunchedEffect(group.iconUrl) {
        delay(FAVICON_SETTLE_MS)
        settledIconUrl = group.iconUrl
    }
    val candidates = remember(memberCandidates, typedIsItsOwnSite, markOrigin, settledIconUrl) {
        if (typedIsItsOwnSite) {
            memberCandidates + IconCandidate(
                origin = markOrigin,
                // Blank until the settled URL is *this* host. The label comes from
                // what is typed and the image from what has settled, so for the
                // width of the debounce they can disagree, and a tile reading
                // "example.org" under nightbell.app's mark is a tile telling a lie.
                // Better a glyph for 450 ms than the wrong icon for 450 ms.
                url = settledIconUrl.takeIf { iconOriginOf(it) == markOrigin }.orEmpty(),
                // A globe, not a monitor's kind glyph: this is a site the user
                // named, not one of the things being watched.
                kindIcon = NightbellIcons.Globe,
            )
        } else {
            memberCandidates
        }
    }
    val selectedOrigin = markOrigin

    // Open when the site in use is one the user typed, so the address behind that
    // tile is visible and editable rather than hidden behind a disclosure.
    var showUrlField by remember(group.id) { mutableStateOf(typedIsItsOwnSite) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickFailed by remember { mutableStateOf(false) }
    // The photo picker, not a storage permission and not a file browser. It hands
    // back one image the user chose and grants read access to that alone, so
    // Nightbell never asks for the photo library.
    val pickPicture = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Downscaled and encoded off the main thread, see GroupIcon. A failure
            // here is a picture Nightbell could not read, which the user has to be
            // told about rather than left staring at an unchanged badge.
            val encoded = GroupIcon.encodeFrom(context, uri)
            pickFailed = encoded == null
            if (encoded != null) onChange { it.copy(iconImage = encoded) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Sized to the app's card margins, like PauseDialog, Material's default
        // dialog width is not a measurement this design uses anywhere.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(GROUP_DIALOG_SCRIM)

        // Its own handler: the one at the root of the activity is in a different
        // window and never sees a tap that lands in this dialog, so without it the
        // keyboard stayed up over the member list with nothing to dismiss it but
        // the back gesture, which closes the dialog too.
        DismissKeyboardOnOutsideTap {
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                shape = RoundedCornerShape(NightbellRadii.sheet),
                corner = NightbellRadii.sheet,
                accent = accent,
                contentPadding = 20.dp,
            ) {
                SectionHeader(
                    title = if (draft.creating) "New group" else "Edit group",
                    icon = NightbellIcons.Layers,
                    accent = accent,
                )

                GlassField(
                    value = group.title,
                    onValueChange = { value -> onChange { it.copy(title = value) } },
                    label = "Title",
                    placeholder = "Nightbell",
                    leadingIcon = NightbellIcons.Sparkle,
                    accent = accent,
                )

                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = "Group icon",
                    icon = NightbellIcons.Sparkle,
                    accent = NightbellColors.TextSecondary,
                )
                Text(
                    // Names the consequence rather than the control. The reason to
                    // care about this section is what it changes on the screen the
                    // user actually looks at.
                    text = "Shown on the group's card on the dashboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
                Spacer(Modifier.height(12.dp))

                GroupIconPicker(
                    candidates = candidates,
                    selectedOrigin = selectedOrigin,
                    pictureSelected = pictureSelected,
                    picture = picture,
                    accent = accent,
                    onPickSite = { candidate ->
                        pickFailed = false
                        // Both fields, together: the URL says which site and the
                        // choice says to use a site at all. Writing one without the
                        // other is how a tap on a tile does nothing visible.
                        onChange {
                            it.copy(
                                iconUrl = candidate.url,
                                iconChoice = GroupIconChoice.SITE,
                            )
                        }
                        // Left open when the tile *is* the typed site, closing the
                        // field the user is working in would be the picker taking
                        // the pen out of their hand.
                        if (candidate.origin != markOrigin || !typedIsItsOwnSite) {
                            showUrlField = false
                        }
                    },
                    onUsePicture = {
                        pickFailed = false
                        onChange { it.copy(iconChoice = GroupIconChoice.PICTURE) }
                    },
                    onUploadPicture = {
                        pickFailed = false
                        pickPicture.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onDeletePicture = {
                        pickFailed = false
                        // Back to a site rather than to nothing. Deleting the
                        // picture is not a request for a group with no mark.
                        onChange {
                            it.copy(iconImage = "", iconChoice = GroupIconChoice.SITE)
                        }
                    },
                )

                if (pickFailed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        // A picture Nightbell could not read has to be said out
                        // loud. The first version left the badge unchanged, which is
                        // indistinguishable from the tap not registering.
                        text = "That picture could not be read. Try another one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.Rose,
                    )
                }

                if (candidates.isEmpty() && picture == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "No monitor here has a site to borrow a mark from. " +
                            "Upload a picture, or name a site below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NightbellButton(
                        text = if (showUrlField) "Hide site address" else "Another site…",
                        onClick = { showUrlField = !showUrlField },
                        icon = NightbellIcons.Globe,
                        tone = ButtonTone.Secondary,
                    )
                    if (picture != null) {
                        // Replacing is a separate labelled action because tapping the
                        // tile means *select*. Overloading the tap with "and re-open
                        // the picker if it was already selected" is the kind of
                        // cleverness nobody discovers and everybody triggers.
                        NightbellButton(
                            text = "Replace picture",
                            onClick = {
                                pickFailed = false
                                pickPicture.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            icon = NightbellIcons.Eye,
                            tone = ButtonTone.Secondary,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showUrlField,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        GlassField(
                            value = group.iconUrl,
                            onValueChange = { value ->
                                onChange {
                                    it.copy(iconUrl = value, iconChoice = GroupIconChoice.SITE)
                                }
                            },
                            label = "Site address",
                            placeholder = "https://nightbell.app",
                            helper = if (typedIsItsOwnSite) {
                                "In use. It is the last tile above."
                            } else {
                                "Any page on the site. Nightbell fetches its favicon."
                            },
                            leadingIcon = NightbellIcons.Globe,
                            accent = accent,
                            // Last field in the form, so the keyboard offers a way
                            // out rather than a Next with nowhere to go.
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = if (members.size == 1) "1 monitor" else "${members.size} monitors",
                    icon = NightbellIcons.Radar,
                    accent = NightbellColors.TextSecondary,
                )

                if (members.isEmpty()) {
                    Text(
                        text = "This group is empty. Long-press monitors on the dashboard to add " +
                            "them, or ungroup it below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                } else {
                    members.forEach { card ->
                        MemberRow(
                            card = card,
                            // Removing the last member is allowed. The alternative is a
                            // group you cannot empty without deleting a monitor, and the
                            // empty group still offers Ungroup.
                            onRemove = { onRemoveMember(card.monitor.id) },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (confirmUngroup) {
                    Text(
                        text = "Ungroup “${group.displayTitle}”? The ${members.size} " +
                            "${if (members.size == 1) "monitor" else "monitors"} stay exactly as " +
                            "they are, only the grouping goes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NightbellButton(
                            text = "Keep it",
                            onClick = { confirmUngroup = false },
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        NightbellButton(
                            text = "Ungroup",
                            onClick = onUngroup,
                            icon = NightbellIcons.Layers,
                            tone = ButtonTone.Danger,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NightbellButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        NightbellButton(
                            text = if (draft.creating) "Create group" else "Save",
                            onClick = onSave,
                            icon = NightbellIcons.Check,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!draft.creating) {
                        Spacer(Modifier.height(10.dp))
                        NightbellButton(
                            text = "Ungroup",
                            onClick = { confirmUngroup = true },
                            icon = NightbellIcons.Layers,
                            tone = ButtonTone.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** One member of the group, with the door out. */
@Composable
private fun MemberRow(card: MonitorCard, onRemove: () -> Unit) {
    val monitor = card.monitor
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = kindIcon(monitor.kind),
            accent = NightbellColors.TextSecondary,
            size = 32.dp,
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = monitor.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = NightbellColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = monitor.prettyHost,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            icon = NightbellIcons.Close,
            onClick = onRemove,
            contentDescription = "Remove ${monitor.displayName} from the group",
            accent = NightbellColors.TextTertiary,
            size = 30.dp,
        )
    }
}

/** Matches PauseDialog: the platform default leaves the dashboard too readable. */
private const val GROUP_DIALOG_SCRIM = 0.72f

/**
 * How long the address field has to be quiet before its favicon is fetched.
 *
 * Long enough that typing a hostname is one lookup rather than one per letter,
 * short enough that it lands while the user is still looking at the tile.
 */
private const val FAVICON_SETTLE_MS = 450L
