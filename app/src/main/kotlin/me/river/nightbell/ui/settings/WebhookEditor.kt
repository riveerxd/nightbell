package me.river.nightbell.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.webhook.WebhookSender
import me.river.nightbell.domain.HeaderPair
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorGroup
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.WebhookEvent
import me.river.nightbell.domain.WebhookFormat
import me.river.nightbell.domain.WebhookPayload
import me.river.nightbell.domain.WebhookSecrets
import me.river.nightbell.domain.WebhookTarget
import me.river.nightbell.domain.WebhookValidation
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassDivider
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.HoldToConfirmButton
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.components.ToggleRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.readableContentPadding

class WebhookEditorViewModel(
    private val graph: Nightbell.Graph,
    targetId: String?,
) : ViewModel() {

    private val existing: WebhookTarget? = targetId?.let { id ->
        graph.store.snapshot.value.settings.webhooks.firstOrNull { it.id == id }
    }

    /** The starting point, so "nothing changed" can close without asking. */
    private val initial: WebhookTarget = existing ?: WebhookTarget(
        id = UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
    )

    val isEditing: Boolean = existing != null

    /** The id asked for no longer exists: deleted from another screen, or a stale back stack. */
    val missing: Boolean = targetId != null && existing == null

    var draft by mutableStateOf(initial)
        private set

    /**
     * A saved address is shown redacted until the user asks to replace it.
     *
     * There is nothing in a redacted string to edit, and showing the whole
     * thing every time the screen opens puts a channel's posting permission on
     * the screen for whoever is looking over a shoulder.
     */
    var revealAddress by mutableStateOf(existing == null || existing.url.isBlank())
        private set

    /** Errors stay quiet until the first save attempt, warnings never do. */
    var attemptedSave by mutableStateOf(false)
        private set

    var testing by mutableStateOf(false)
        private set

    var testResult by mutableStateOf<WebhookSender.Attempt?>(null)
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    /**
     * Bumped on every refused save or test, with the field to go to. A counter
     * rather than the field alone, so a second refusal on the same field still
     * scrolls after the user has scrolled away from it.
     */
    var errorScroll by mutableStateOf(0 to WebhookValidation.Field.ADDRESS)
        private set

    private fun refuse(report: WebhookValidation.Report) {
        attemptedSave = true
        toast = ToastMessage.error(report.blockingMessage ?: "Fix the highlighted fields first")
        val first = report.problems.firstOrNull { it.severity == Validation.Severity.ERROR }?.field ?: return
        errorScroll = errorScroll.first + 1 to first
    }

    val report: WebhookValidation.Report get() = WebhookValidation.check(draft)

    val isDirty: Boolean get() = draft != initial

    val monitors: StateFlow<List<Monitor>> = graph.store.snapshot
        .map { it.monitors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.store.snapshot.value.monitors)

    val groups: StateFlow<List<MonitorGroup>> = graph.store.snapshot
        .map { it.groups }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.store.snapshot.value.groups)

    val proxyAvailable: Boolean get() = ProxyRoute.endpoint(graph.store.snapshot.value.settings) != null

    val realBlurEnabled: Boolean get() = graph.store.snapshot.value.settings.realBlurEnabled

    val redactedAddress: String get() = WebhookSecrets.redactAddress(initial.url, initial.format)

    fun update(transform: (WebhookTarget) -> WebhookTarget) {
        draft = transform(draft)
        // A result about a different configuration is a result about nothing.
        testResult = null
    }

    fun replaceAddress() {
        revealAddress = true
        update { it.copy(url = "") }
    }

    fun keepSavedAddress() {
        revealAddress = false
        update { it.copy(url = initial.url) }
    }

    fun consumeToast() {
        toast = null
    }

    fun sendTest() {
        if (testing) return
        val report = report
        if (!report.isValid) {
            refuse(report)
            return
        }
        testing = true
        testResult = null
        val target = draft
        viewModelScope.launch {
            val sender = graph.store.currentSnapshot().settings.webhookSender.trim()
            testResult = runCatching { graph.webhooks.sendTest(target, sender) }
                .getOrElse { WebhookSender.Attempt(0, 0, it.message ?: "The test could not be sent") }
            testing = false
        }
    }

    fun save(onSaved: () -> Unit) {
        val report = report
        if (!report.isValid) {
            refuse(report)
            return
        }
        val target = draft.copy(
            name = draft.name.trim(),
            url = draft.url.trim(),
            chatId = draft.chatId.trim(),
            headers = draft.headers.filterNot { it.isBlank },
        )
        // On the graph's scope, not this screen's: Save closes the screen, and a
        // write that dies with it is a save that did not happen.
        graph.appScope.launch {
            graph.store.upsertWebhook(target)
            if (target.url != initial.url) {
                // A failure streak against the old address says nothing about the
                // new one, and leaving it would show "Failing" under a target the
                // user has just fixed.
                graph.store.updateWebhookState { it.copy(status = it.status - target.id) }
            }
            graph.webhooks.flush()
        }
        onSaved()
    }

    fun delete(onDeleted: () -> Unit) {
        val id = initial.id
        graph.appScope.launch { graph.store.deleteWebhook(id) }
        onDeleted()
    }
}

@Composable
fun rememberWebhookEditorViewModel(targetId: String?): WebhookEditorViewModel = viewModel(
    key = "webhook-${targetId ?: "new"}",
    factory = viewModelFactory { initializer { WebhookEditorViewModel(Nightbell.require(), targetId) } },
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WebhookEditorScreen(
    targetId: String?,
    onClose: () -> Unit,
    onToast: (ToastMessage) -> Unit,
) {
    val viewModel = rememberWebhookEditorViewModel(targetId)
    val draft = viewModel.draft
    val report = viewModel.report
    val monitors by viewModel.monitors.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    var confirmDiscard by remember { mutableStateOf(false) }
    val accent = NightbellColors.Aqua
    val scroll = rememberScrollState()
    // Where each card starts inside the scrolling column, so a refused save can
    // go to the card holding the first error instead of leaving the user to
    // find it under a toast.
    val cardTops = remember { mutableStateMapOf<String, Int>() }
    fun Modifier.card(key: String) = onGloballyPositioned { cardTops[key] = it.positionInParent().y.toInt() }
    LaunchedEffect(viewModel.errorScroll) {
        if (viewModel.errorScroll.first == 0) return@LaunchedEffect
        val key = when (viewModel.errorScroll.second) {
            WebhookValidation.Field.NAME, WebhookValidation.Field.ADDRESS, WebhookValidation.Field.CHAT_ID -> "where"
            WebhookValidation.Field.METHOD, WebhookValidation.Field.BODY -> "custom"
            WebhookValidation.Field.EVENTS -> "events"
            WebhookValidation.Field.SCOPE -> "scope"
            WebhookValidation.Field.HEADERS, WebhookValidation.Field.SECRET -> "headers"
        }
        cardTops[key]?.let { scroll.animateScrollTo((it - 24).coerceAtLeast(0)) }
    }
    // While typing, the keyboard is the bottom of the screen and the footer would
    // sit on top of whichever field is being typed into, because bringing a field
    // into view only clears the keyboard. Save is not what anyone reaches for
    // mid-word, so the footer steps aside until the keyboard goes.
    val imeVisible = WindowInsets.isImeVisible

    val toast = viewModel.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            onToast(toast)
            viewModel.consumeToast()
        }
    }

    val requestLeave = { if (viewModel.isDirty) confirmDiscard = true else onClose() }
    BackHandler {
        if (confirmDiscard) confirmDiscard = false else requestLeave()
    }

    fun note(field: WebhookValidation.Field): Validation.Note? {
        val problem = report.of(field) ?: return null
        if (problem.severity == Validation.Severity.ERROR && !viewModel.attemptedSave) return null
        return Validation.Note(Validation.Field.URL, problem.severity, problem.message)
    }

    Box(Modifier.fillMaxSize()) {
        // The footer sits under the form, not over it. Floating it over the
        // scroll looked lighter, and it hid whatever was at the bottom of the
        // form at the moment it mattered: a test result is the last thing on the
        // screen, it grows when the answer arrives, and it arrived underneath.
        Column(Modifier.fillMaxSize().imePadding()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .testTag("webhook-scroll")
                        .padding(readableContentPadding())
                        .padding(top = topInset + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    EditorHeader(
                        editing = viewModel.isEditing,
                        title = draft.displayName,
                        onClose = requestLeave,
                    )

                    if (viewModel.missing) {
                        GlassCard(accent = NightbellColors.Amber) {
                            Text(
                                "This webhook was deleted. Close this screen and add it again if you still want it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                    }

                    WhereCard(viewModel, draft, ::note, accent, Modifier.card("where"))
                    if (draft.format == WebhookFormat.CUSTOM) CustomRequestCard(viewModel, draft, ::note, accent, Modifier.card("custom"))
                    EventsCard(draft, ::note, accent, Modifier.card("events")) { viewModel.update(it) }
                    ScopeCard(draft, monitors, groups, ::note, accent, Modifier.card("scope")) { viewModel.update(it) }
                    BehaviourCard(viewModel, draft, accent)
                    HeadersCard(draft, ::note, accent, Modifier.card("headers")) { viewModel.update(it) }
                    TestCard(viewModel, accent)
                    if (viewModel.isEditing) {
                        HoldToConfirmButton(
                            text = "Hold to delete this webhook",
                            shortText = "Hold to delete",
                            onConfirm = {
                                viewModel.delete {
                                    onToast(ToastMessage.success("Webhook deleted"))
                                    onClose()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("webhook-delete"),
                        )
                    }
                    Spacer(Modifier.height(16.dp).testTag("webhook-bottom"))
                }
                if (!imeVisible) Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = NightbellRadii.sheet, topEnd = NightbellRadii.sheet))
                        .background(NightbellColors.ToastFill)
                        .border(
                            1.dp,
                            NightbellColors.GlassStrokeSoft,
                            RoundedCornerShape(topStart = NightbellRadii.sheet, topEnd = NightbellRadii.sheet),
                        )
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .padding(bottom = bottomInset),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NightbellButton(
                        text = "Cancel",
                        onClick = requestLeave,
                        tone = ButtonTone.Secondary,
                    )
                    NightbellButton(
                        text = if (viewModel.isEditing) "Save changes" else "Save webhook",
                        shortText = "Save",
                        onClick = {
                            viewModel.save {
                                onToast(
                                    ToastMessage.success(
                                        if (viewModel.isEditing) "Webhook saved" else "Webhook added",
                                    ),
                                )
                                onClose()
                            }
                        },
                        icon = NightbellIcons.Check,
                        enabled = !viewModel.missing,
                        modifier = Modifier.weight(1f).testTag("webhook-save"),
                    )
                }
        }

        DiscardPrompt(
            visible = confirmDiscard,
            editing = viewModel.isEditing,
            onKeepEditing = { confirmDiscard = false },
            onDiscard = onClose,
        )
    }
}

@Composable
private fun EditorHeader(editing: Boolean, title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(
            icon = NightbellIcons.Close,
            onClick = onClose,
            contentDescription = "Close without saving",
            accent = NightbellColors.TextSecondary,
            size = 38.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (editing) "Edit webhook" else "New webhook",
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.Aqua,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.testTag("webhook-title"),
            )
        }
    }
}

@Composable
private fun WhereCard(
    viewModel: WebhookEditorViewModel,
    draft: WebhookTarget,
    note: (WebhookValidation.Field) -> Validation.Note?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        SectionHeader("Where it goes", icon = NightbellIcons.Link, accent = accent)
        ChipSelector(
            options = WebhookFormat.entries.toList(),
            selected = draft.format,
            onSelect = { format -> viewModel.update { it.copy(format = format) } },
            label = { it.label },
            accent = accent,
            modifier = Modifier.testTag("webhook-format"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = draft.format.howTo,
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(12.dp))
        GlassField(
            value = draft.name,
            onValueChange = { value -> viewModel.update { it.copy(name = value) } },
            label = "Name",
            placeholder = draft.format.label,
            helper = "Only for you, in this list. \"Ops channel\", \"My phone\".",
            accent = accent,
            corner = NightbellRadii.inCard,
            modifier = Modifier.testTag("webhook-name"),
        )
        Spacer(Modifier.height(12.dp))
        if (!viewModel.revealAddress) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(NightbellIcons.Shield, NightbellColors.Aqua, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${draft.format.addressLabel} saved",
                        style = MaterialTheme.typography.titleMedium,
                        color = NightbellColors.TextPrimary,
                    )
                    Text(
                        text = viewModel.redactedAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.testTag("webhook-address-redacted"),
                    )
                }
                NightbellButton(
                    text = "Replace",
                    onClick = viewModel::replaceAddress,
                    tone = ButtonTone.Secondary,
                    icon = NightbellIcons.Pencil,
                    modifier = Modifier.testTag("webhook-address-replace"),
                )
            }
        } else {
            GlassField(
                value = draft.url,
                onValueChange = { value -> viewModel.update { it.copy(url = value.trim()) } },
                label = draft.format.addressLabel,
                placeholder = draft.format.addressPlaceholder,
                note = note(WebhookValidation.Field.ADDRESS),
                helper = "Kept on this phone. Never in a log, and not in a backup unless you ask.",
                keyboardType = if (draft.format.addressIsUrl) KeyboardType.Uri else KeyboardType.Password,
                accent = accent,
                corner = NightbellRadii.inCard,
                singleLine = true,
                modifier = Modifier.testTag("webhook-address"),
            )
            if (viewModel.isEditing && viewModel.redactedAddress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                NightbellButton(
                    text = "Keep the saved one",
                    onClick = viewModel::keepSavedAddress,
                    tone = ButtonTone.Ghost,
                )
            }
        }
        if (draft.format == WebhookFormat.TELEGRAM) {
            Spacer(Modifier.height(12.dp))
            GlassField(
                value = draft.chatId,
                onValueChange = { value -> viewModel.update { it.copy(chatId = value.trim()) } },
                label = "Chat ID",
                placeholder = "-1001234567890 or @channel",
                note = note(WebhookValidation.Field.CHAT_ID),
                helper = "Send the bot a message, then open api.telegram.org/bot<token>/getUpdates to read it.",
                accent = accent,
                corner = NightbellRadii.inCard,
                modifier = Modifier.testTag("webhook-chat-id"),
            )
        }
    }
}

@Composable
private fun CustomRequestCard(
    viewModel: WebhookEditorViewModel,
    draft: WebhookTarget,
    note: (WebhookValidation.Field) -> Validation.Note?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        SectionHeader("The request", icon = NightbellIcons.Braces, accent = accent)
        ChipSelector(
            options = listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.GET, HttpMethod.DELETE),
            selected = draft.method,
            onSelect = { method -> viewModel.update { it.copy(method = method) } },
            label = { it.name },
            accent = accent,
        )
        note(WebhookValidation.Field.METHOD)?.let { NoteLine(it) }
        Spacer(Modifier.height(12.dp))
        GlassField(
            value = draft.contentType,
            onValueChange = { value -> viewModel.update { it.copy(contentType = value) } },
            label = "Content type",
            placeholder = "application/json",
            helper = when (WebhookPayload.escapeFor(draft.contentType)) {
                WebhookPayload.Escape.JSON -> "Values are JSON escaped, so write \"{{title}}\" inside quotes."
                WebhookPayload.Escape.URL -> "Values are URL encoded, for a form body."
                WebhookPayload.Escape.NONE -> "Values go in exactly as they are."
            },
            accent = accent,
            corner = NightbellRadii.inCard,
        )
        Spacer(Modifier.height(12.dp))
        GlassField(
            value = draft.bodyTemplate,
            onValueChange = { value -> viewModel.update { it.copy(bodyTemplate = value) } },
            label = "Body",
            placeholder = "{\"text\": \"{{title}}: {{text}}\"}",
            note = note(WebhookValidation.Field.BODY),
            helper = "Left empty, the plain JSON body is sent.",
            singleLine = false,
            minLines = 5,
            accent = accent,
            corner = NightbellRadii.inCard,
            modifier = Modifier.testTag("webhook-body"),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "TAP TO ADD A PLACEHOLDER",
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        val names = remember { WebhookPayload.placeholders(WebhookPayload.sampleFacts, "").keys.toList() }
        ChipSelector(
            options = names,
            selected = "",
            onSelect = { name -> viewModel.update { it.copy(bodyTemplate = it.bodyTemplate + "{{$name}}") } },
            label = { it },
            accent = accent,
            isSelected = { false },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Placeholders work in the address and header values too. The address is URL encoded.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

@Composable
private fun EventsCard(
    draft: WebhookTarget,
    note: (WebhookValidation.Field) -> Validation.Note?,
    accent: Color,
    modifier: Modifier,
    update: ((WebhookTarget) -> WebhookTarget) -> Unit,
) {
    GlassCard(modifier) {
        SectionHeader("What to send", icon = NightbellIcons.Bell, accent = accent)
        WebhookEvent.choosable.forEach { event ->
            ToggleRow(
                title = event.label,
                subtitle = event.blurb,
                checked = event in draft.events,
                onCheckedChange = { on ->
                    update { it.copy(events = if (on) it.events + event else it.events - event) }
                },
                accent = accent,
                modifier = Modifier.testTag("webhook-event-${event.code}"),
            )
        }
        note(WebhookValidation.Field.EVENTS)?.let { NoteLine(it) }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Each monitor's failure threshold, cooldown and repeat interval apply here as they do " +
                "on the phone, so a flapping endpoint does not flood the channel.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

@Composable
private fun ScopeCard(
    draft: WebhookTarget,
    monitors: List<Monitor>,
    groups: List<MonitorGroup>,
    note: (WebhookValidation.Field) -> Validation.Note?,
    accent: Color,
    modifier: Modifier,
    update: ((WebhookTarget) -> WebhookTarget) -> Unit,
) {
    GlassCard(modifier) {
        SectionHeader("Which monitors", icon = NightbellIcons.Radar, accent = accent)
        SegmentedSelector(
            options = listOf(true, false),
            selected = draft.scope.all,
            onSelect = { all -> update { it.copy(scope = it.scope.copy(all = all)) } },
            label = { if (it) "All of them" else "Only some" },
            accent = accent,
            modifier = Modifier.testTag("webhook-scope"),
        )
        Spacer(Modifier.height(8.dp))
        if (draft.scope.all) {
            Text(
                text = if (monitors.isEmpty()) {
                    "No monitors yet. Every one you add will be covered."
                } else {
                    "All ${monitors.size} monitors, and any you add later."
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        } else {
            if (groups.isNotEmpty()) {
                Text("GROUPS", style = MaterialTheme.typography.labelSmall, color = NightbellColors.TextTertiary)
                Text(
                    "A group covers whatever is in it at the time, including monitors added later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
                groups.forEach { group ->
                    ToggleRow(
                        title = group.displayTitle,
                        subtitle = if (group.memberIds.size == 1) "1 monitor" else "${group.memberIds.size} monitors",
                        checked = group.id in draft.scope.groupIds,
                        onCheckedChange = { on ->
                            update {
                                val ids = if (on) it.scope.groupIds + group.id else it.scope.groupIds - group.id
                                it.copy(scope = it.scope.copy(groupIds = ids))
                            }
                        },
                        accent = accent,
                    )
                }
                GlassDivider(Modifier.padding(vertical = 8.dp))
            }
            Text("MONITORS", style = MaterialTheme.typography.labelSmall, color = NightbellColors.TextTertiary)
            if (monitors.isEmpty()) {
                Text(
                    "No monitors yet. Add one first, or send for all of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            monitors.forEach { monitor ->
                val viaGroup = groups.any { it.id in draft.scope.groupIds && monitor.id in it.memberIds }
                ToggleRow(
                    title = monitor.displayName,
                    subtitle = if (viaGroup) "Covered by a group you picked" else monitor.prettyHost,
                    checked = viaGroup || monitor.id in draft.scope.monitorIds,
                    enabled = !viaGroup,
                    onCheckedChange = { on ->
                        update {
                            val ids = if (on) it.scope.monitorIds + monitor.id else it.scope.monitorIds - monitor.id
                            it.copy(scope = it.scope.copy(monitorIds = ids))
                        }
                    },
                    accent = accent,
                    modifier = Modifier.testTag("webhook-monitor-${monitor.id}"),
                )
            }
            note(WebhookValidation.Field.SCOPE)?.let { NoteLine(it) }
        }
    }
}

@Composable
private fun BehaviourCard(viewModel: WebhookEditorViewModel, draft: WebhookTarget, accent: Color) {
    GlassCard {
        SectionHeader("How it behaves", icon = NightbellIcons.Sliders, accent = accent)
        ToggleRow(
            title = "Send from this webhook",
            subtitle = if (draft.enabled) "On" else "Off, and anything waiting is thrown away",
            checked = draft.enabled,
            onCheckedChange = { on -> viewModel.update { it.copy(enabled = on) } },
            accent = accent,
            modifier = Modifier.testTag("webhook-enabled"),
        )
        ToggleRow(
            title = "Follow this phone's quiet hours and mute",
            subtitle = if (draft.followPhone) {
                "Only sends when the phone would notify too, including the alert switches"
            } else {
                "Sends whatever the phone is doing, for a channel other people read"
            },
            checked = draft.followPhone,
            onCheckedChange = { on -> viewModel.update { it.copy(followPhone = on) } },
            accent = accent,
            modifier = Modifier.testTag("webhook-follow-phone"),
        )
        ToggleRow(
            title = "Accept any certificate",
            subtitle = if (draft.acceptAnyCertificate) {
                "For a self-signed receiver. Anyone in the middle could read the message"
            } else {
                "The receiver's certificate has to be one this phone trusts"
            },
            checked = draft.acceptAnyCertificate,
            onCheckedChange = { on -> viewModel.update { it.copy(acceptAnyCertificate = on) } },
            accent = if (draft.acceptAnyCertificate) NightbellColors.Amber else accent,
        )
        ToggleRow(
            title = "Send through the SOCKS5 proxy",
            subtitle = when {
                draft.useProxy && !viewModel.proxyAvailable ->
                    "No proxy is set up in Settings, so nothing will be sent"
                viewModel.proxyAvailable -> "For a receiver on Tor or I2P"
                else -> "Set up a proxy under Settings, Checks first"
            },
            checked = draft.useProxy,
            enabled = viewModel.proxyAvailable || draft.useProxy,
            onCheckedChange = { on -> viewModel.update { it.copy(useProxy = on) } },
            accent = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "A pause from the dashboard stops webhooks as well, whichever way this is set. " +
                "A pause means the phone's own signal is not to be believed.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

@Composable
private fun HeadersCard(
    draft: WebhookTarget,
    note: (WebhookValidation.Field) -> Validation.Note?,
    accent: Color,
    modifier: Modifier,
    update: ((WebhookTarget) -> WebhookTarget) -> Unit,
) {
    GlassCard(modifier) {
        SectionHeader(
            "Headers and signing",
            icon = NightbellIcons.Shield,
            accent = accent,
            trailing = {
                GlassIconButton(
                    icon = NightbellIcons.Plus,
                    onClick = { update { it.copy(headers = it.headers + HeaderPair()) } },
                    contentDescription = "Add header",
                    size = 30.dp,
                    accent = accent,
                    modifier = Modifier.testTag("webhook-add-header"),
                )
            },
        )
        if (draft.headers.isEmpty()) {
            Text(
                text = "None. Add one for a token, \"Authorization: Bearer …\" for ntfy or your own server.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
        draft.headers.forEachIndexed { index, header ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                GlassField(
                    value = header.name,
                    onValueChange = { value ->
                        update { t -> t.copy(headers = t.headers.toMutableList().also { it[index] = header.copy(name = value) }) }
                    },
                    label = "Header name",
                    placeholder = "Authorization",
                    accent = accent,
                    corner = NightbellRadii.inCard,
                    modifier = Modifier.fillMaxWidth().testTag("webhook-header-name-$index"),
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassField(
                        value = header.value,
                        onValueChange = { value ->
                            update { t -> t.copy(headers = t.headers.toMutableList().also { it[index] = header.copy(value = value) }) }
                        },
                        label = "Value",
                        placeholder = "Bearer …",
                        masked = true,
                        accent = accent,
                        corner = NightbellRadii.inCard,
                        modifier = Modifier.weight(1f).testTag("webhook-header-value-$index"),
                    )
                    Box(Modifier.padding(bottom = 4.dp)) {
                        GlassIconButton(
                            icon = NightbellIcons.Trash,
                            onClick = { update { t -> t.copy(headers = t.headers.filterIndexed { i, _ -> i != index }) } },
                            contentDescription = "Remove header ${index + 1}",
                            size = 38.dp,
                            accent = NightbellColors.Rose,
                        )
                    }
                }
            }
        }
        note(WebhookValidation.Field.HEADERS)?.let { NoteLine(it) }
        GlassDivider(Modifier.padding(vertical = 12.dp))
        GlassField(
            value = draft.signingSecret,
            onValueChange = { value -> update { it.copy(signingSecret = value) } },
            label = "Signing secret",
            placeholder = "Optional",
            masked = true,
            note = note(WebhookValidation.Field.SECRET),
            helper = "Set one and every request carries X-Nightbell-Signature: sha256=HMAC of " +
                "\"timestamp.body\", with the timestamp in X-Nightbell-Timestamp.",
            accent = accent,
            corner = NightbellRadii.inCard,
            modifier = Modifier.testTag("webhook-secret"),
        )
    }
}

@Composable
private fun TestCard(viewModel: WebhookEditorViewModel, accent: Color) {
    GlassCard {
        SectionHeader("Try it", icon = NightbellIcons.Play, accent = accent)
        Text(
            text = "Sends one message now, marked as a test, using what is on this screen. Nothing is saved.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(10.dp))
        NightbellButton(
            text = if (viewModel.testing) "Sending…" else "Send a test message",
            onClick = viewModel::sendTest,
            loading = viewModel.testing,
            tone = ButtonTone.Secondary,
            icon = NightbellIcons.Play,
            modifier = Modifier.fillMaxWidth().testTag("webhook-test"),
        )
        val result = viewModel.testResult
        AnimatedVisibility(visible = result != null, enter = fadeIn(), exit = fadeOut()) {
            if (result == null) return@AnimatedVisibility
            val tone = if (result.delivered) NightbellColors.Mint else NightbellColors.Rose
            Column(Modifier.padding(top = 12.dp).testTag("webhook-test-result")) {
                Text(
                    text = if (result.delivered) {
                        "Delivered. HTTP ${result.code} in ${result.ms} ms"
                    } else if (result.code > 0) {
                        result.error
                    } else {
                        "Not delivered. ${result.error}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = tone,
                )
                if (result.delivered) {
                    Text(
                        text = "Check the other end: if the message is not there, the service accepted it " +
                            "and dropped it, which usually means the wrong channel or a format it ignores.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
                val page = result.snippet.trimStart().startsWith("<")
                // A web page is already named in the line above; quoting it again
                // here would say the same thing twice.
                if (result.snippet.isNotBlank() && !(page && !result.delivered)) {
                    Spacer(Modifier.height(6.dp))
                    Text("THE SERVICE ANSWERED", style = MaterialTheme.typography.labelSmall, color = NightbellColors.TextTertiary)
                    Text(
                        // Markup is unreadable and says nothing the title does not.
                        text = if (page) WebhookSender.readable(result.snippet) else result.snippet,
                        maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = NightbellColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteLine(note: Validation.Note) {
    Text(
        text = note.message,
        style = MaterialTheme.typography.bodySmall,
        color = if (note.severity == Validation.Severity.ERROR) NightbellColors.Rose else NightbellColors.Amber,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun DiscardPrompt(
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
                    text = if (editing) "Discard your changes?" else "Discard this webhook?",
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (editing) {
                        "The webhook keeps its current settings. Anything you changed here goes."
                    } else {
                        "Nothing has been saved yet, so everything filled in here goes with it."
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
                        modifier = Modifier.weight(1f).testTag("webhook-discard"),
                    )
                }
            }
        }
    }
}
