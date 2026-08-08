package me.river.pulse.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.ElementMode
import me.river.pulse.domain.HeaderPair
import me.river.pulse.domain.HttpMethod
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.StatusMode
import me.river.pulse.domain.Validation
import me.river.pulse.ui.SetupViewModel
import me.river.pulse.ui.components.AlertPolicyEditor
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.ChipSelector
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassField
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.IconBadge
import me.river.pulse.ui.components.MicroTag
import me.river.pulse.ui.components.ProgressPips
import me.river.pulse.ui.components.NightbellButton
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.SegmentedSelector
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.components.formatLatency
import me.river.pulse.ui.dashboard.kindIcon
import me.river.pulse.ui.icons.NightbellIcons
import me.river.pulse.ui.rememberSetupViewModel
import me.river.pulse.ui.theme.BackdropHost
import me.river.pulse.ui.theme.BackdropScope
import me.river.pulse.ui.theme.NightbellColors
import me.river.pulse.ui.theme.NightbellRadii
import me.river.pulse.ui.theme.accentFor
import me.river.pulse.ui.theme.readableContentPadding
import me.river.pulse.ui.theme.sheetSurface
import me.river.pulse.ui.theme.softShadow
import androidx.compose.ui.platform.testTag

private val stepTitles = listOf("What to watch", "Target", "Expectations", "Cadence & alerts")

@Composable
fun SetupScreen(
    monitorId: String?,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    templateId: String? = null,
) {
    val viewModel = rememberSetupViewModel(monitorId, templateId)
    val draft = viewModel.draft
    val report = viewModel.report
    val (accent, accentEnd) = accentFor(draft.accent)

    LaunchedEffect(viewModel.saved) {
        if (viewModel.saved) onSaved()
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    val blurEnabled = viewModel.realBlurEnabled
    val density = LocalDensity.current
    // The footer floats over the form instead of sitting under it, so there is
    // something real for it to frost. Its measured height becomes the scroll
    // area's bottom padding, so nothing ever hides behind it.
    var footerHeight by remember { mutableStateOf(0.dp) }
    var confirmDiscard by remember { mutableStateOf(false) }

    /**
     * Leaving the wizard, from whichever direction the request arrived.
     *
     * The footer's Cancel and the system Back gesture have to agree: a draft that
     * cost a page load to capture must not evaporate because the user swiped
     * instead of tapping.
     */
    val requestLeave = {
        if (viewModel.isDirty) confirmDiscard = true else onClose()
    }

    // Step 0 is the only step where Back means "leave". Everywhere else the
    // gesture walks the wizard backwards, exactly as the footer's Back does.
    // Disabled while the picker is up — that has its own handler, and it needs to
    // consume Back for in-page navigation first.
    BackHandler(enabled = !viewModel.pickerOpen) {
        when {
            confirmDiscard -> confirmDiscard = false
            viewModel.step > 0 -> viewModel.back()
            else -> requestLeave()
        }
    }

    Box(Modifier.fillMaxSize()) {
        BackdropHost(
            modifier = Modifier.fillMaxSize().imePadding(),
            enabled = blurEnabled,
            content = {
                Column(Modifier.fillMaxSize().recordBackdrop()) {
                    SetupHeader(
                        step = viewModel.step,
                        editing = viewModel.isEditing,
                        accent = accent,
                        onClose = requestLeave,
                    )

                    AnimatedContent(
                        targetState = viewModel.step,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (
                                slideInHorizontally(tween(280)) { if (forward) it / 3 else -it / 3 } +
                                    fadeIn(tween(220))
                                ) togetherWith (
                                slideOutHorizontally(tween(240)) { if (forward) -it / 4 else it / 4 } +
                                    fadeOut(tween(160))
                                )
                        },
                        label = "setupStep",
                        modifier = Modifier.weight(1f),
                    ) { step ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .testTag("setup-scroll")
                                .verticalScroll(rememberScrollState())
                                // Clamped and centred on a tablet: a form field a
                                // thousand pixels wide is harder to read, not easier.
                                .padding(readableContentPadding()),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (step) {
                                0 -> StepKind(draft, viewModel::setKind)
                                1 -> StepTarget(viewModel, draft, report, accent)
                                2 -> StepExpectations(viewModel, draft, report, accent)
                                else -> StepSchedule(viewModel, draft, report, accent)
                            }

                            if (viewModel.step > 0) {
                                TestPanel(
                                    testing = viewModel.testing,
                                    result = viewModel.testResult,
                                    canTest = report.isValid,
                                    blockingMessage = report.blockingMessage,
                                    accent = accent,
                                    onTest = viewModel::runTest,
                                )
                            }
                            Spacer(Modifier.height(footerHeight + 8.dp))
                        }
                    }
                }
            },
            overlay = { backdrop ->
                SetupFooter(
                    step = viewModel.step,
                    canContinue = canLeaveStep(viewModel.step, draft, report),
                    canSave = report.isValid,
                    editing = viewModel.isEditing,
                    accent = accent,
                    accentEnd = accentEnd,
                    bottomInset = bottomInset,
                    backdrop = backdrop,
                    onBack = { if (viewModel.step == 0) requestLeave() else viewModel.back() },
                    onNext = viewModel::next,
                    onSave = viewModel::save,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { footerHeight = with(density) { it.height.toDp() } },
                )
            },
        )

        ElementPickerOverlay(
            visible = viewModel.pickerOpen,
            url = draft.url.trim(),
            existingSelector = draft.targets.getOrNull(viewModel.pickingIndex)
                ?.displaySelector.orEmpty(),
            alreadyWatching = draft.targets.size,
            onDismiss = viewModel::closePicker,
            onConfirm = { picked ->
                viewModel.applyPick(
                    cssSelector = picked.cssSelector,
                    xpath = picked.xpath,
                    elementId = picked.elementId,
                    tagName = picked.tagName,
                    classSignature = picked.classSignature,
                    text = picked.text,
                )
            },
        )

        DiscardDraftPrompt(
            visible = confirmDiscard,
            editing = viewModel.isEditing,
            onKeepEditing = { confirmDiscard = false },
            onDiscard = onClose,
        )

        if (viewModel.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(NightbellColors.Void.copy(alpha = 0.7f)),
            )
        }
        Spacer(Modifier.height(topInset))
    }
}

/**
 * The last thing between a captured draft and losing it.
 *
 * Only ever raised when there is genuinely something to lose — an untouched
 * wizard closes without argument, because a confirmation you always get is a
 * confirmation you stop reading.
 */
@Composable
private fun DiscardDraftPrompt(
    visible: Boolean,
    editing: Boolean,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(NightbellColors.Void.copy(alpha = 0.72f))
                // Swallows taps so the form underneath cannot be edited while the
                // prompt is up, and doubles as tap-outside-to-cancel.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onKeepEditing,
                )
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard(accent = NightbellColors.Amber, contentPadding = 20.dp) {
                Text(
                    text = if (editing) "Discard your changes?" else "Discard this monitor?",
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (editing) {
                        "The monitor keeps its current settings. Anything you changed here goes."
                    } else {
                        "Nothing has been saved yet, so everything you filled in — including " +
                            "any elements you captured — goes with it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NightbellButton(
                        text = "Keep editing",
                        onClick = onKeepEditing,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    NightbellButton(
                        text = "Discard",
                        onClick = onDiscard,
                        tone = ButtonTone.Danger,
                        icon = NightbellIcons.Trash,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun canLeaveStep(step: Int, draft: Monitor, report: Validation.Report): Boolean = when (step) {
    0 -> true
    1 -> report.of(Validation.Field.URL)?.severity != Validation.Severity.ERROR &&
        report.of(Validation.Field.HEADERS)?.severity != Validation.Severity.ERROR &&
        (draft.kind != MonitorKind.WEBSITE_ELEMENT || draft.element?.isCaptured == true)
    2 -> report.of(Validation.Field.ASSERTION)?.severity != Validation.Severity.ERROR &&
        report.of(Validation.Field.JSON_PATH)?.severity != Validation.Severity.ERROR &&
        report.of(Validation.Field.STATUS)?.severity != Validation.Severity.ERROR &&
        report.of(Validation.Field.ELEMENT_TEXT)?.severity != Validation.Severity.ERROR
    else -> report.isValid
}

// -------------------------------------------------------------------- chrome

@Composable
private fun SetupHeader(step: Int, editing: Boolean, accent: Color, onClose: () -> Unit) {
    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = topInset + 12.dp, start = 18.dp, end = 18.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                icon = NightbellIcons.Close,
                onClick = onClose,
                contentDescription = "Cancel setup",
                accent = NightbellColors.TextSecondary,
                size = 38.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (editing) "Edit monitor" else "New monitor",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                Text(
                    text = stepTitles.getOrElse(step) { "" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = NightbellColors.TextPrimary,
                )
            }
            Text(
                text = "${step + 1}/${stepTitles.size}",
                style = MaterialTheme.typography.labelMedium,
                color = NightbellColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(14.dp))
        ProgressPips(total = stepTitles.size, current = step, accent = accent)
    }
}

@Composable
private fun SetupFooter(
    step: Int,
    canContinue: Boolean,
    canSave: Boolean,
    editing: Boolean,
    accent: Color,
    accentEnd: Color,
    bottomInset: androidx.compose.ui.unit.Dp,
    backdrop: BackdropScope,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            // Real frosted glass on API 31+: the form scrolls visibly out of
            // focus underneath. Falls back to the opaque pane below that.
            .softShadow(corner = NightbellRadii.sheet, radius = 20.dp, strength = 1.6f)
            .sheetSurface(backdrop)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .padding(bottom = bottomInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NightbellButton(
            text = if (step == 0) "Cancel" else "Back",
            onClick = onBack,
            tone = ButtonTone.Secondary,
            icon = if (step == 0) NightbellIcons.Close else NightbellIcons.ArrowLeft,
        )
        if (step < SetupViewModel.LAST_STEP) {
            NightbellButton(
                text = "Continue",
                onClick = onNext,
                enabled = canContinue,
                icon = NightbellIcons.ArrowRight,
                accent = accent,
                accentEnd = accentEnd,
                modifier = Modifier.weight(1f),
            )
        } else {
            NightbellButton(
                text = if (editing) "Save changes" else "Create monitor",
                onClick = onSave,
                enabled = canSave,
                icon = NightbellIcons.Check,
                accent = accent,
                accentEnd = accentEnd,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --------------------------------------------------------------------- step 0

@Composable
private fun StepKind(draft: Monitor, onSelect: (MonitorKind) -> Unit) {
    Text(
        text = "Pick the kind of check. You can change everything later.",
        style = MaterialTheme.typography.bodyMedium,
        color = NightbellColors.TextSecondary,
    )
    MonitorKind.entries.forEachIndexed { index, kind ->
        val selected = draft.kind == kind
        val (accent, _) = accentFor(index * 2)
        val scale by animateFloatAsState(
            targetValue = if (selected) 1f else 0.985f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
            label = "kindScale",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(NightbellRadii.card))
                .background(
                    if (selected) {
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.05f)),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(NightbellColors.sheen(0.06f), NightbellColors.sheen(0.03f)),
                        )
                    },
                )
                .border(
                    1.dp,
                    if (selected) accent.copy(alpha = 0.6f) else NightbellColors.sheen(0.09f),
                    RoundedCornerShape(NightbellRadii.card),
                )
                .clickable { onSelect(kind) }
                .padding(16.dp)
                .semantics { contentDescription = "${kind.label}. ${kind.blurb}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = kindIcon(kind), accent = accent, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = kind.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
                Icon(
                    NightbellIcons.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------------- step 1

@Composable
private fun StepTarget(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    GlassField(
        value = draft.name,
        onValueChange = { value -> viewModel.update { it.copy(name = value) } },
        label = "Name",
        placeholder = "My API, marketing site, …",
        note = report.of(Validation.Field.NAME),
        leadingIcon = NightbellIcons.Sparkle,
        accent = accent,
    )
    GlassField(
        value = draft.url,
        onValueChange = { value -> viewModel.update { it.copy(url = value.trim()) } },
        label = "URL",
        placeholder = "https://example.com/health",
        note = report.of(Validation.Field.URL),
        leadingIcon = NightbellIcons.Link,
        accent = accent,
        keyboardType = KeyboardType.Uri,
    )

    if (draft.kind == MonitorKind.WEBSITE_ELEMENT) {
        ElementCaptureCard(viewModel, draft, report, accent)
    }

    if (draft.kind == MonitorKind.ADVANCED_REQUEST) {
        SectionHeader("Request", icon = NightbellIcons.Braces, accent = accent)
        SegmentedSelector(
            options = HttpMethod.entries.toList(),
            selected = draft.method,
            onSelect = { value -> viewModel.update { it.copy(method = value) } },
            label = { it.name },
            accent = accent,
        )
        HeadersEditor(
            headers = draft.headers,
            onChange = { value -> viewModel.update { it.copy(headers = value) } },
            note = report.of(Validation.Field.HEADERS),
            accent = accent,
        )
        AnimatedVisibility(
            visible = draft.method.allowsBody,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassField(
                    value = draft.contentType,
                    onValueChange = { value -> viewModel.update { it.copy(contentType = value) } },
                    label = "Content type",
                    placeholder = "application/json",
                    leadingIcon = NightbellIcons.Layers,
                    accent = accent,
                )
                GlassField(
                    value = draft.body,
                    onValueChange = { value -> viewModel.update { it.copy(body = value) } },
                    label = "Request body",
                    placeholder = "{\n  \"ping\": true\n}",
                    note = report.of(Validation.Field.BODY),
                    accent = accent,
                    singleLine = false,
                    minLines = 4,
                    imeAction = ImeAction.Default,
                )
            }
        }
    }
}

/**
 * The watched-element list.
 *
 * A page monitor watches N elements resolved against **one** page load, so
 * adding a second element is nearly free — the expensive part is rendering the
 * page. The list is a conjunction: any element failing fails the check.
 */
@Composable
private fun ElementCaptureCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val elements = draft.targets
    val urlUsable = Validation.urlNote(draft.url)?.severity != Validation.Severity.ERROR

    GlassCard(accent = if (elements.isNotEmpty()) NightbellColors.Mint else Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (elements.isNotEmpty()) NightbellIcons.Target else NightbellIcons.Pointer,
                accent = if (elements.isNotEmpty()) NightbellColors.Mint else accent,
                size = 40.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (elements.size) {
                        0 -> "Pick the elements to watch"
                        1 -> "1 element captured"
                        else -> "${elements.size} elements captured"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = if (elements.isEmpty()) {
                        "Opens the page in-app; tap what you care about."
                    } else {
                        "All checked on one page load. Any mismatch fails the check."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (elements.isEmpty()) NightbellColors.TextTertiary else NightbellColors.Mint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        elements.forEachIndexed { index, element ->
            Spacer(Modifier.height(11.dp))
            CapturedElementRow(
                index = index,
                total = elements.size,
                element = element,
                accent = accent,
                onRePick = { viewModel.openPicker(index) },
                onRemove = { viewModel.removeElement(index) },
                onMoveUp = { viewModel.moveElement(index, -1) },
                onMoveDown = { viewModel.moveElement(index, 1) },
            )
        }

        Spacer(Modifier.height(13.dp))
        NightbellButton(
            text = if (elements.isEmpty()) "Open live preview" else "Add another element",
            onClick = { viewModel.openPicker(-1) },
            enabled = urlUsable,
            icon = if (elements.isEmpty()) NightbellIcons.Eye else NightbellIcons.Plus,
            tone = if (elements.isEmpty()) ButtonTone.Primary else ButtonTone.Secondary,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!urlUsable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enter a valid URL first.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Amber,
            )
        }
        report.of(Validation.Field.ELEMENT)?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it.message,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Rose,
            )
        }
    }
}

@Composable
private fun CapturedElementRow(
    index: Int,
    total: Int,
    element: me.river.pulse.domain.ElementTarget,
    accent: Color,
    onRePick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(NightbellColors.sheen(0.05f))
            .border(1.dp, NightbellColors.sheen(0.08f), RoundedCornerShape(15.dp))
            .padding(12.dp)
            .semantics { contentDescription = "Element ${index + 1}: ${element.displayLabel}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroTag("${index + 1}", color = accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = element.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = element.displaySelector,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (total > 1) {
                GlassIconButton(
                    icon = NightbellIcons.ChevronDown,
                    onClick = onMoveUp,
                    contentDescription = "Move element ${index + 1} up",
                    size = 30.dp,
                    accent = NightbellColors.TextTertiary,
                    enabled = index > 0,
                    modifier = Modifier.graphicsLayer { rotationZ = 180f },
                )
                Spacer(Modifier.width(6.dp))
                GlassIconButton(
                    icon = NightbellIcons.ChevronDown,
                    onClick = onMoveDown,
                    contentDescription = "Move element ${index + 1} down",
                    size = 30.dp,
                    accent = NightbellColors.TextTertiary,
                    enabled = index < total - 1,
                )
                Spacer(Modifier.width(6.dp))
            }
            GlassIconButton(
                icon = NightbellIcons.Eye,
                onClick = onRePick,
                contentDescription = "Re-pick element ${index + 1}",
                size = 30.dp,
                accent = accent,
            )
            Spacer(Modifier.width(6.dp))
            GlassIconButton(
                icon = NightbellIcons.Trash,
                onClick = onRemove,
                contentDescription = "Remove element ${index + 1}",
                size = 30.dp,
                accent = NightbellColors.Rose,
            )
        }
        if (element.textSnippet.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "“${element.textSnippet.take(120)}”",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeadersEditor(
    headers: List<HeaderPair>,
    onChange: (List<HeaderPair>) -> Unit,
    note: Validation.Note?,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = "Headers",
            icon = NightbellIcons.Layers,
            accent = accent,
            trailing = {
                GlassIconButton(
                    icon = NightbellIcons.Plus,
                    onClick = { onChange(headers + HeaderPair()) },
                    contentDescription = "Add header",
                    size = 30.dp,
                    accent = accent,
                )
            },
        )
        if (headers.isEmpty()) {
            Text(
                text = "No custom headers. Nightbell always sends a descriptive User-Agent.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
        headers.forEachIndexed { index, header ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                GlassField(
                    value = header.name,
                    onValueChange = { value ->
                        onChange(headers.toMutableList().also { it[index] = header.copy(name = value) })
                    },
                    label = "Name",
                    placeholder = "Authorization",
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                GlassField(
                    value = header.value,
                    onValueChange = { value ->
                        onChange(headers.toMutableList().also { it[index] = header.copy(value = value) })
                    },
                    label = "Value",
                    placeholder = "Bearer …",
                    accent = accent,
                    modifier = Modifier.weight(1.2f),
                )
                Box(Modifier.padding(bottom = 4.dp)) {
                    GlassIconButton(
                        icon = NightbellIcons.Trash,
                        onClick = { onChange(headers.filterIndexed { i, _ -> i != index }) },
                        contentDescription = "Remove header ${index + 1}",
                        size = 38.dp,
                        accent = NightbellColors.Rose,
                    )
                }
            }
        }
        if (note != null) {
            Text(
                text = note.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (note.severity == Validation.Severity.ERROR) {
                    NightbellColors.Rose
                } else {
                    NightbellColors.Amber
                },
            )
        }
    }
}

// --------------------------------------------------------------------- step 2

@Composable
private fun StepExpectations(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    if (draft.kind == MonitorKind.WEBSITE_ELEMENT) {
        val elements = draft.targets
        if (elements.isEmpty()) {
            GlassCard(accent = NightbellColors.Amber, contentPadding = 16.dp) {
                Text(
                    text = "Go back a step and capture at least one element first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightbellColors.TextSecondary,
                )
            }
            return
        }
        elements.forEachIndexed { index, element ->
            ElementExpectationCard(
                index = index,
                total = elements.size,
                element = element,
                accent = accent,
                note = if (index == 0) report.of(Validation.Field.ELEMENT_TEXT) else null,
                onChange = { transform -> viewModel.updateElement(index, transform) },
            )
        }
        if (elements.size > 1) {
            Text(
                text = "Every element has to match. One mismatch marks the whole monitor down, " +
                    "and the alert names the first thing that broke.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
        return
    }

    SectionHeader("Status code", icon = NightbellIcons.Server, accent = accent)
    SegmentedSelector(
        options = StatusMode.entries.toList(),
        selected = draft.status.mode,
        onSelect = { mode -> viewModel.update { it.copy(status = it.status.copy(mode = mode)) } },
        label = { it.label },
        accent = accent,
    )
    AnimatedVisibility(
        visible = draft.status.mode == StatusMode.EXACT,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            StepperRow(
                title = "Expected code",
                value = draft.status.code,
                onValueChange = { code -> viewModel.update { it.copy(status = it.status.copy(code = code)) } },
                range = 100..599,
                icon = NightbellIcons.Target,
                accent = accent,
                note = report.of(Validation.Field.STATUS),
            )
            Spacer(Modifier.height(6.dp))
            ChipSelector(
                options = listOf(200, 201, 204, 301, 302, 401, 403, 404, 418, 500, 503),
                selected = draft.status.code,
                onSelect = { code -> viewModel.update { it.copy(status = it.status.copy(code = code)) } },
                label = { it.toString() },
                accent = accent,
            )
        }
    }
    AnimatedVisibility(
        visible = draft.status.mode == StatusMode.RANGE,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            StepperRow(
                title = "From",
                value = draft.status.rangeStart,
                onValueChange = { v -> viewModel.update { it.copy(status = it.status.copy(rangeStart = v)) } },
                range = 100..599,
                accent = accent,
            )
            StepperRow(
                title = "To",
                value = draft.status.rangeEnd,
                onValueChange = { v -> viewModel.update { it.copy(status = it.status.copy(rangeEnd = v)) } },
                range = 100..599,
                accent = accent,
                note = report.of(Validation.Field.STATUS),
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    SectionHeader("Response body", icon = NightbellIcons.Braces, accent = accent)
    ChipSelector(
        options = AssertionMode.entries.toList(),
        selected = draft.assertion.mode,
        onSelect = { mode -> viewModel.update { it.copy(assertion = it.assertion.copy(mode = mode)) } },
        label = { it.label },
        accent = accent,
    )
    AnimatedVisibility(
        visible = draft.assertion.mode.needsPath,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        GlassField(
            value = draft.assertion.jsonPath,
            onValueChange = { v -> viewModel.update { it.copy(assertion = it.assertion.copy(jsonPath = v)) } },
            label = "JSON path",
            placeholder = "data.status  ·  items[0].id",
            note = report.of(Validation.Field.JSON_PATH),
            leadingIcon = NightbellIcons.Filter,
            accent = accent,
        )
    }
    AnimatedVisibility(
        visible = draft.assertion.mode.needsValue,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassField(
                value = draft.assertion.value,
                onValueChange = { v -> viewModel.update { it.copy(assertion = it.assertion.copy(value = v)) } },
                label = when (draft.assertion.mode) {
                    AssertionMode.REGEX -> "Regular expression"
                    AssertionMode.JSON_FIELD_EQUALS -> "Expected value"
                    else -> "Expected text"
                },
                placeholder = when (draft.assertion.mode) {
                    AssertionMode.REGEX -> "\"status\"\\s*:\\s*\"ok\""
                    else -> "ok"
                },
                note = report.of(Validation.Field.ASSERTION),
                leadingIcon = NightbellIcons.Search,
                accent = accent,
                singleLine = draft.assertion.mode != AssertionMode.EXACT,
                minLines = if (draft.assertion.mode == AssertionMode.EXACT) 3 else 1,
            )
            ToggleRow(
                title = "Case sensitive",
                subtitle = if (draft.assertion.caseSensitive) "Exact character match" else "Ignores capitalisation",
                checked = draft.assertion.caseSensitive,
                onCheckedChange = { v ->
                    viewModel.update { it.copy(assertion = it.assertion.copy(caseSensitive = v)) }
                },
                icon = NightbellIcons.Filter,
                accent = accent,
            )
        }
    }
}

/** Everything one watched element asserts, in its own card. */
@Composable
private fun ElementExpectationCard(
    index: Int,
    total: Int,
    element: me.river.pulse.domain.ElementTarget,
    accent: Color,
    note: Validation.Note?,
    onChange: ((me.river.pulse.domain.ElementTarget) -> me.river.pulse.domain.ElementTarget) -> Unit,
) {
    GlassCard(contentPadding = 16.dp) {
        SectionHeader(
            title = if (total == 1) "Element expectation" else "Element ${index + 1}",
            icon = NightbellIcons.Target,
            accent = accent,
        )
        Text(
            text = element.displaySelector,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))

        if (total > 1) {
            GlassField(
                value = element.label,
                onValueChange = { value -> onChange { it.copy(label = value) } },
                label = "Nickname",
                placeholder = element.tagName.ifBlank { "price, stock badge…" },
                helper = "Shown in alerts so you know which element broke.",
                leadingIcon = NightbellIcons.Sparkle,
                accent = accent,
            )
            Spacer(Modifier.height(12.dp))
        }

        ChipSelector(
            options = ElementMode.entries.toList(),
            selected = element.mode,
            onSelect = { mode -> onChange { it.copy(mode = mode) } },
            label = { it.label },
            accent = accent,
        )
        AnimatedVisibility(
            visible = element.mode == ElementMode.TEXT_EQUALS || element.mode == ElementMode.TEXT_CONTAINS,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                GlassField(
                    value = element.expectedText,
                    onValueChange = { value -> onChange { it.copy(expectedText = value) } },
                    label = "Expected text",
                    placeholder = element.textSnippet.take(40).ifBlank { "In stock" },
                    note = note,
                    leadingIcon = NightbellIcons.Search,
                    accent = accent,
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassField(
            value = element.attribute,
            onValueChange = { value -> onChange { it.copy(attribute = value.trim()) } },
            label = "Compare an attribute instead (optional)",
            placeholder = "href, value, data-state…",
            helper = "Leave empty to compare the element's visible text.",
            leadingIcon = NightbellIcons.Braces,
            accent = accent,
        )
        Spacer(Modifier.height(8.dp))
        SelectorSummary(element, accent)
    }
}

@Composable
private fun SelectorSummary(element: me.river.pulse.domain.ElementTarget, accent: Color) {
    if (!element.isCaptured) return
    GlassCard(contentPadding = 15.dp) {
        SectionHeader("Stored signature", icon = NightbellIcons.Layers, accent = accent)
        SummaryLine("CSS", element.cssSelector)
        SummaryLine("XPath", element.xpath)
        SummaryLine("Tag", element.tagName)
        SummaryLine("Classes", element.classSignature)
        SummaryLine("Text snapshot", element.textSnippet)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Checks try id → CSS → XPath → text fingerprint, in that order, so " +
                "small markup changes won't false-alarm.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NightbellColors.TextTertiary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --------------------------------------------------------------------- step 3

@Composable
private fun StepSchedule(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    SectionHeader("Cadence", icon = NightbellIcons.Clock, accent = accent)
    StepperRow(
        title = "Check every",
        value = draft.intervalMinutes,
        onValueChange = { v -> viewModel.update { it.copy(intervalMinutes = v) } },
        range = 1..1440,
        step = 1,
        suffix = "m",
        icon = NightbellIcons.Clock,
        accent = accent,
        note = report.of(Validation.Field.INTERVAL),
    )
    ChipSelector(
        options = listOf(1, 5, 15, 30, 60, 180, 720),
        selected = draft.intervalMinutes,
        onSelect = { v -> viewModel.update { it.copy(intervalMinutes = v) } },
        label = { if (it >= 60) "${it / 60}h" else "${it}m" },
        accent = accent,
    )
    StepperRow(
        title = "Timeout",
        value = draft.timeoutSeconds,
        onValueChange = { v -> viewModel.update { it.copy(timeoutSeconds = v) } },
        range = 1..120,
        suffix = "s",
        icon = NightbellIcons.Gauge,
        accent = accent,
        note = report.of(Validation.Field.TIMEOUT),
    )
    if (draft.kind != MonitorKind.WEBSITE_ELEMENT) {
        ToggleRow(
            title = "Follow redirects",
            subtitle = if (draft.followRedirects) "3xx responses are followed" else "3xx is reported as-is",
            checked = draft.followRedirects,
            onCheckedChange = { v -> viewModel.update { it.copy(followRedirects = v) } },
            icon = NightbellIcons.Link,
            accent = accent,
        )
    }
    ToggleRow(
        title = "Active",
        subtitle = if (draft.enabled) "Runs on schedule" else "Paused — manual checks only",
        checked = draft.enabled,
        onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
        icon = NightbellIcons.Power,
        accent = accent,
    )

    Spacer(Modifier.height(12.dp))
    SectionHeader("Latency budget", icon = NightbellIcons.Gauge, accent = NightbellColors.Amber)
    LatencySloEditor(
        value = draft.latencySloMs,
        onChange = { v -> viewModel.update { it.copy(latencySloMs = v) } },
    )

    Spacer(Modifier.height(12.dp))
    SectionHeader("Urgent", icon = NightbellIcons.Zap, accent = NightbellColors.Rose)
    UrgentEditor(
        urgent = draft.urgent,
        repeatMinutes = draft.urgentRepeatMinutes,
        onUrgentChange = { v -> viewModel.update { it.copy(urgent = v) } },
        onRepeatChange = { v -> viewModel.update { it.copy(urgentRepeatMinutes = v) } },
    )

    Spacer(Modifier.height(12.dp))
    SectionHeader("Alerts", icon = NightbellIcons.Bell, accent = accent)
    ToggleRow(
        title = "Use my global alert settings",
        subtitle = if (draft.useGlobalAlerts) {
            "Inherits sound, haptics and escalation from Settings"
        } else {
            "This monitor has its own rules"
        },
        checked = draft.useGlobalAlerts,
        onCheckedChange = { v -> viewModel.update { it.copy(useGlobalAlerts = v) } },
        icon = NightbellIcons.Shield,
        accent = accent,
    )
    AnimatedVisibility(
        visible = !draft.useGlobalAlerts,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        AlertPolicyEditor(
            policy = draft.alert,
            onChange = { policy -> viewModel.update { it.copy(alert = policy) } },
            accent = accent,
        )
    }
}

/**
 * Per-monitor latency SLO. 0 means "inherit the global budget", which is what
 * almost everyone wants — the override exists for the one endpoint that is
 * legitimately slow, or the one that must never be.
 */
@Composable
private fun LatencySloEditor(value: Int, onChange: (Int) -> Unit) {
    ToggleRow(
        title = "Custom latency budget",
        subtitle = if (value > 0) {
            "Slower than $value ms counts as degraded"
        } else {
            "Using the global budget from Settings"
        },
        checked = value > 0,
        onCheckedChange = { on -> onChange(if (on) DEFAULT_SLO_MS else 0) },
        icon = NightbellIcons.Gauge,
        accent = NightbellColors.Amber,
    )
    AnimatedVisibility(
        visible = value > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(6.dp))
            StepperRow(
                title = "Degraded above",
                value = value.coerceAtLeast(100),
                onValueChange = onChange,
                range = 100..60_000,
                step = 100,
                suffix = "ms",
                icon = NightbellIcons.Activity,
                accent = NightbellColors.Amber,
            )
            ChipSelector(
                options = listOf(500, 1_000, 2_500, 5_000, 10_000),
                selected = value,
                onSelect = onChange,
                label = { if (it >= 1_000) "${it / 1000}s" else "${it}ms" },
                accent = NightbellColors.Amber,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A response slower than this is DEGRADED — up, but not well. " +
                    "Turn on latency alerts under Alerts to hear about it.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
    }
}

/**
 * URGENT mode. Distinct from "keep reminding me": that repeats on a schedule
 * and stops when you mute it, this one repeats until you explicitly say you've
 * seen it, and re-arms itself on the next outage.
 */
@Composable
private fun UrgentEditor(
    urgent: Boolean,
    repeatMinutes: Int,
    onUrgentChange: (Boolean) -> Unit,
    onRepeatChange: (Int) -> Unit,
) {
    ToggleRow(
        title = "URGENT",
        subtitle = if (urgent) {
            "Repeats every $repeatMinutes min while down until you acknowledge it"
        } else {
            "Normal alerting — one notification per outage"
        },
        checked = urgent,
        onCheckedChange = onUrgentChange,
        icon = NightbellIcons.Zap,
        accent = NightbellColors.Rose,
    )
    AnimatedVisibility(
        visible = urgent,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(6.dp))
            StepperRow(
                title = "Repeat every",
                value = repeatMinutes,
                onValueChange = onRepeatChange,
                range = 1..120,
                suffix = "m",
                icon = NightbellIcons.History,
                accent = NightbellColors.Rose,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightbellColors.Rose.copy(alpha = 0.09f))
                    .padding(13.dp),
            ) {
                Text(
                    text = "Acknowledge from the notification or the monitor screen. " +
                        "The card stays red until it recovers, and the next outage " +
                        "shouts again.\n\nWhile an urgent outage is unacknowledged Nightbell " +
                        "runs a foreground service to keep the interval — expect a " +
                        "persistent notification and extra battery use until you confirm it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextSecondary,
                )
            }
        }
    }
}

/** Starting point when someone switches a per-monitor budget on. */
private const val DEFAULT_SLO_MS = 2_500

// ---------------------------------------------------------------- test panel

@Composable
private fun TestPanel(
    testing: Boolean,
    result: CheckResult?,
    canTest: Boolean,
    blockingMessage: String?,
    accent: Color,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NightbellButton(
            text = if (testing) "Running check…" else "Test now",
            onClick = onTest,
            enabled = canTest && !testing,
            loading = testing,
            icon = NightbellIcons.Zap,
            tone = ButtonTone.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!canTest && blockingMessage != null) {
            Text(
                text = blockingMessage,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Amber,
            )
        }
        AnimatedVisibility(
            visible = result != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (result != null) TestResultCard(result, accent)
        }
    }
}

@Composable
private fun TestResultCard(result: CheckResult, accent: Color) {
    val tone = if (result.ok) NightbellColors.Mint else NightbellColors.Rose
    GlassCard(accent = tone, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (result.ok) NightbellIcons.Check else NightbellIcons.Warning,
                accent = tone,
                size = 38.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (result.ok) "Check passed" else result.failureKind.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = tone,
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (result.statusCode > 0) {
                MicroTag("HTTP ${result.statusCode}", color = accent)
            }
            MicroTag(formatLatency(result.latencyMs), color = NightbellColors.TextSecondary, icon = NightbellIcons.Gauge)
            if (result.elementText.isNotBlank()) {
                MicroTag("element text", color = NightbellColors.Violet, icon = NightbellIcons.Target)
            }
        }
        if (!result.ok && result.failureKind.hint.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            Text(
                text = result.failureKind.hint,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.Amber,
            )
        }
        if (result.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = NightbellColors.TextTertiary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (result.elementText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "“${result.elementText.take(200)}”",
                style = MaterialTheme.typography.bodyMedium,
                color = NightbellColors.TextSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (result.bodyPreview.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(11.dp),
            ) {
                Text(
                    text = result.bodyPreview.take(600),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = NightbellColors.TextSecondary,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
