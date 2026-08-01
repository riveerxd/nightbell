package me.river.pulse.ui.setup

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.SegmentedSelector
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.components.formatLatency
import me.river.pulse.ui.dashboard.kindIcon
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberSetupViewModel
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseRadii
import me.river.pulse.ui.theme.accentFor
import me.river.pulse.ui.theme.glass
import androidx.compose.ui.platform.testTag

private val stepTitles = listOf("What to watch", "Target", "Expectations", "Cadence & alerts")

@Composable
fun SetupScreen(
    monitorId: String?,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel = rememberSetupViewModel(monitorId)
    val draft = viewModel.draft
    val report = viewModel.report
    val (accent, accentEnd) = accentFor(draft.accent)

    LaunchedEffect(viewModel.saved) {
        if (viewModel.saved) onSaved()
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SetupHeader(
                step = viewModel.step,
                editing = viewModel.isEditing,
                accent = accent,
                onClose = onClose,
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
                        .padding(horizontal = 18.dp),
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
                    Spacer(Modifier.height(8.dp))
                }
            }

            SetupFooter(
                step = viewModel.step,
                canContinue = canLeaveStep(viewModel.step, draft, report),
                canSave = report.isValid,
                editing = viewModel.isEditing,
                accent = accent,
                accentEnd = accentEnd,
                bottomInset = bottomInset,
                onBack = { if (viewModel.step == 0) onClose() else viewModel.back() },
                onNext = viewModel::next,
                onSave = viewModel::save,
            )
        }

        ElementPickerOverlay(
            visible = viewModel.pickerOpen,
            url = draft.url.trim(),
            existingSelector = draft.element?.displaySelector.orEmpty(),
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

        if (viewModel.loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(PulseColors.Void.copy(alpha = 0.7f)),
            )
        }
        Spacer(Modifier.height(topInset))
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
                icon = PulseIcons.Close,
                onClick = onClose,
                contentDescription = "Cancel setup",
                accent = PulseColors.TextSecondary,
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
                    color = PulseColors.TextPrimary,
                )
            }
            Text(
                text = "${step + 1}/${stepTitles.size}",
                style = MaterialTheme.typography.labelMedium,
                color = PulseColors.TextTertiary,
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
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(
                shape = RoundedCornerShape(topStart = PulseRadii.sheet, topEnd = PulseRadii.sheet),
                corner = PulseRadii.sheet,
                elevation = 26.dp,
                glow = accent,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .padding(bottom = bottomInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseButton(
            text = if (step == 0) "Cancel" else "Back",
            onClick = onBack,
            tone = ButtonTone.Secondary,
            icon = if (step == 0) PulseIcons.Close else PulseIcons.ArrowLeft,
        )
        if (step < SetupViewModel.LAST_STEP) {
            PulseButton(
                text = "Continue",
                onClick = onNext,
                enabled = canContinue,
                icon = PulseIcons.ArrowRight,
                accent = accent,
                accentEnd = accentEnd,
                modifier = Modifier.weight(1f),
            )
        } else {
            PulseButton(
                text = if (editing) "Save changes" else "Create monitor",
                onClick = onSave,
                enabled = canSave,
                icon = PulseIcons.Check,
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
        color = PulseColors.TextSecondary,
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
                .clip(RoundedCornerShape(PulseRadii.card))
                .background(
                    if (selected) {
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.05f)),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.03f)),
                        )
                    },
                )
                .border(
                    1.dp,
                    if (selected) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.09f),
                    RoundedCornerShape(PulseRadii.card),
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
                    color = PulseColors.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
            AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
                Icon(
                    PulseIcons.Check,
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
        leadingIcon = PulseIcons.Sparkle,
        accent = accent,
    )
    GlassField(
        value = draft.url,
        onValueChange = { value -> viewModel.update { it.copy(url = value.trim()) } },
        label = "URL",
        placeholder = "https://example.com/health",
        note = report.of(Validation.Field.URL),
        leadingIcon = PulseIcons.Link,
        accent = accent,
        keyboardType = KeyboardType.Uri,
    )

    if (draft.kind == MonitorKind.WEBSITE_ELEMENT) {
        ElementCaptureCard(viewModel, draft, report, accent)
    }

    if (draft.kind == MonitorKind.ADVANCED_REQUEST) {
        SectionHeader("Request", icon = PulseIcons.Braces, accent = accent)
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
                    leadingIcon = PulseIcons.Layers,
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

@Composable
private fun ElementCaptureCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val element = draft.element
    val captured = element?.isCaptured == true
    val urlUsable = Validation.urlNote(draft.url)?.severity != Validation.Severity.ERROR

    GlassCard(accent = if (captured) PulseColors.Mint else accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (captured) PulseIcons.Target else PulseIcons.Pointer,
                accent = if (captured) PulseColors.Mint else accent,
                size = 40.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (captured) "Element captured" else "Pick the element to watch",
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.TextPrimary,
                )
                Text(
                    text = if (captured) {
                        element?.displaySelector.orEmpty()
                    } else {
                        "Opens the page in-app; tap what you care about."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (captured) PulseColors.Mint else PulseColors.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (captured && !element?.textSnippet.isNullOrBlank()) {
            Spacer(Modifier.height(11.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(11.dp),
            ) {
                Text(
                    text = "“${element?.textSnippet?.take(160)}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        PulseButton(
            text = if (captured) "Re-pick element" else "Open live preview",
            onClick = viewModel::openPicker,
            enabled = urlUsable,
            icon = PulseIcons.Eye,
            tone = if (captured) ButtonTone.Secondary else ButtonTone.Primary,
            accent = accent,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!urlUsable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Enter a valid URL first.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Amber,
            )
        }
        report.of(Validation.Field.ELEMENT)?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it.message,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Rose,
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
            icon = PulseIcons.Layers,
            accent = accent,
            trailing = {
                GlassIconButton(
                    icon = PulseIcons.Plus,
                    onClick = { onChange(headers + HeaderPair()) },
                    contentDescription = "Add header",
                    size = 30.dp,
                    accent = accent,
                )
            },
        )
        if (headers.isEmpty()) {
            Text(
                text = "No custom headers. Pulse always sends a descriptive User-Agent.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
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
                        icon = PulseIcons.Trash,
                        onClick = { onChange(headers.filterIndexed { i, _ -> i != index }) },
                        contentDescription = "Remove header ${index + 1}",
                        size = 38.dp,
                        accent = PulseColors.Rose,
                    )
                }
            }
        }
        if (note != null) {
            Text(
                text = note.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (note.severity == Validation.Severity.ERROR) {
                    PulseColors.Rose
                } else {
                    PulseColors.Amber
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
        val element = draft.element ?: me.river.pulse.domain.ElementTarget()
        SectionHeader("Element expectation", icon = PulseIcons.Target, accent = accent)
        ChipSelector(
            options = ElementMode.entries.toList(),
            selected = element.mode,
            onSelect = { mode -> viewModel.update { it.copy(element = element.copy(mode = mode)) } },
            label = { it.label },
            accent = accent,
        )
        AnimatedVisibility(
            visible = element.mode == ElementMode.TEXT_EQUALS || element.mode == ElementMode.TEXT_CONTAINS,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            GlassField(
                value = element.expectedText,
                onValueChange = { value ->
                    viewModel.update { it.copy(element = element.copy(expectedText = value)) }
                },
                label = "Expected text",
                placeholder = element.textSnippet.take(40).ifBlank { "In stock" },
                note = report.of(Validation.Field.ELEMENT_TEXT),
                leadingIcon = PulseIcons.Search,
                accent = accent,
                singleLine = false,
                minLines = 2,
            )
        }
        GlassField(
            value = element.attribute,
            onValueChange = { value ->
                viewModel.update { it.copy(element = element.copy(attribute = value.trim())) }
            },
            label = "Compare an attribute instead (optional)",
            placeholder = "href, value, data-state…",
            helper = "Leave empty to compare the element's visible text.",
            leadingIcon = PulseIcons.Braces,
            accent = accent,
        )
        SelectorSummary(element, accent)
        return
    }

    SectionHeader("Status code", icon = PulseIcons.Server, accent = accent)
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
                icon = PulseIcons.Target,
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
    SectionHeader("Response body", icon = PulseIcons.Braces, accent = accent)
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
            leadingIcon = PulseIcons.Filter,
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
                leadingIcon = PulseIcons.Search,
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
                icon = PulseIcons.Filter,
                accent = accent,
            )
        }
    }
}

@Composable
private fun SelectorSummary(element: me.river.pulse.domain.ElementTarget, accent: Color) {
    if (!element.isCaptured) return
    GlassCard(contentPadding = 15.dp) {
        SectionHeader("Stored signature", icon = PulseIcons.Layers, accent = accent)
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
            color = PulseColors.TextTertiary,
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
            color = PulseColors.TextTertiary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextSecondary,
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
    SectionHeader("Cadence", icon = PulseIcons.Clock, accent = accent)
    StepperRow(
        title = "Check every",
        value = draft.intervalMinutes,
        onValueChange = { v -> viewModel.update { it.copy(intervalMinutes = v) } },
        range = 1..1440,
        step = 1,
        suffix = "m",
        icon = PulseIcons.Clock,
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
        icon = PulseIcons.Gauge,
        accent = accent,
        note = report.of(Validation.Field.TIMEOUT),
    )
    if (draft.kind != MonitorKind.WEBSITE_ELEMENT) {
        ToggleRow(
            title = "Follow redirects",
            subtitle = if (draft.followRedirects) "3xx responses are followed" else "3xx is reported as-is",
            checked = draft.followRedirects,
            onCheckedChange = { v -> viewModel.update { it.copy(followRedirects = v) } },
            icon = PulseIcons.Link,
            accent = accent,
        )
    }
    ToggleRow(
        title = "Active",
        subtitle = if (draft.enabled) "Runs on schedule" else "Paused — manual checks only",
        checked = draft.enabled,
        onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
        icon = PulseIcons.Power,
        accent = accent,
    )

    Spacer(Modifier.height(12.dp))
    SectionHeader("Alerts", icon = PulseIcons.Bell, accent = accent)
    ToggleRow(
        title = "Use my global alert settings",
        subtitle = if (draft.useGlobalAlerts) {
            "Inherits sound, haptics and escalation from Settings"
        } else {
            "This monitor has its own rules"
        },
        checked = draft.useGlobalAlerts,
        onCheckedChange = { v -> viewModel.update { it.copy(useGlobalAlerts = v) } },
        icon = PulseIcons.Shield,
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
        PulseButton(
            text = if (testing) "Running check…" else "Test now",
            onClick = onTest,
            enabled = canTest && !testing,
            loading = testing,
            icon = PulseIcons.Zap,
            tone = ButtonTone.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!canTest && blockingMessage != null) {
            Text(
                text = blockingMessage,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Amber,
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
    val tone = if (result.ok) PulseColors.Mint else PulseColors.Rose
    GlassCard(accent = tone, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (result.ok) PulseIcons.Check else PulseIcons.Warning,
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
                    color = PulseColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (result.statusCode > 0) {
                MicroTag("HTTP ${result.statusCode}", color = accent)
            }
            MicroTag(formatLatency(result.latencyMs), color = PulseColors.TextSecondary, icon = PulseIcons.Gauge)
            if (result.elementText.isNotBlank()) {
                MicroTag("element text", color = PulseColors.Violet, icon = PulseIcons.Target)
            }
        }
        if (!result.ok && result.failureKind.hint.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            Text(
                text = result.failureKind.hint,
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.Amber,
            )
        }
        if (result.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = PulseColors.TextTertiary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (result.elementText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "“${result.elementText.take(200)}”",
                style = MaterialTheme.typography.bodyMedium,
                color = PulseColors.TextSecondary,
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
                    color = PulseColors.TextSecondary,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
