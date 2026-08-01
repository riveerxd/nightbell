package me.river.pulse.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.river.pulse.data.Pulse
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.Validation
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ dashboard

class DashboardViewModel(private val graph: Pulse.Graph) : ViewModel() {

    val cards: StateFlow<List<MonitorCard>> = graph.store.cards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<GlobalSettings> = graph.store.snapshot
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    var refreshing by mutableStateOf(false)
        private set

    var toast by mutableStateOf<String?>(null)
        private set

    fun checkAll() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                val count = graph.engine.runAllDue(force = true)
                toast = when (count) {
                    0 -> "Nothing to check yet"
                    1 -> "1 monitor checked"
                    else -> "$count monitors checked"
                }
            } finally {
                refreshing = false
            }
        }
    }

    fun check(monitorId: String) {
        viewModelScope.launch { graph.engine.run(monitorId) }
    }

    fun setEnabled(monitorId: String, enabled: Boolean) {
        viewModelScope.launch {
            graph.store.setEnabled(monitorId, enabled)
            val snapshot = graph.store.currentSnapshot()
            snapshot.monitors.firstOrNull { it.id == monitorId }?.let {
                graph.scheduler.scheduleNext(it, snapshot.settings)
            }
            toast = if (enabled) "Monitor resumed" else "Monitor paused"
        }
    }

    fun delete(monitorId: String) {
        viewModelScope.launch {
            graph.store.delete(monitorId)
            graph.scheduler.cancel(monitorId)
            graph.alerts.cancel(monitorId)
            toast = "Monitor deleted"
        }
    }

    fun mute(monitorId: String, hours: Int) {
        viewModelScope.launch {
            graph.engine.mute(monitorId, hours * 60 * 60 * 1000L)
            toast = "Alerts muted for ${hours}h"
        }
    }

    fun unmute(monitorId: String) {
        viewModelScope.launch {
            graph.engine.unmute(monitorId)
            toast = "Alerts un-muted"
        }
    }

    fun consumeToast() {
        toast = null
    }
}

// ---------------------------------------------------------------------- setup

class SetupViewModel(
    private val graph: Pulse.Graph,
    private val editingId: String?,
) : ViewModel() {

    var draft by mutableStateOf(
        Monitor(
            id = editingId ?: UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
        ),
    )
        private set

    var step by mutableStateOf(0)
        private set

    var loading by mutableStateOf(editingId != null)
        private set

    var testing by mutableStateOf(false)
        private set

    var testResult by mutableStateOf<CheckResult?>(null)
        private set

    var pickerOpen by mutableStateOf(false)
        private set

    var saved by mutableStateOf(false)
        private set

    var isEditing by mutableStateOf(editingId != null)
        private set

    val report: Validation.Report get() = Validation.report(draft)

    init {
        viewModelScope.launch {
            val snapshot = graph.store.currentSnapshot()
            if (editingId != null) {
                snapshot.monitors.firstOrNull { it.id == editingId }?.let { draft = it }
            } else {
                draft = draft.copy(
                    intervalMinutes = snapshot.settings.defaultIntervalMinutes,
                    timeoutSeconds = snapshot.settings.defaultTimeoutSeconds,
                    accent = snapshot.monitors.size,
                )
            }
            loading = false
        }
    }

    fun update(transform: (Monitor) -> Monitor) {
        draft = transform(draft)
        testResult = null
    }

    fun setKind(kind: MonitorKind) {
        draft = when (kind) {
            MonitorKind.HTTP_STATUS -> draft.copy(
                kind = kind,
                method = me.river.pulse.domain.HttpMethod.GET,
                assertion = draft.assertion.copy(mode = AssertionMode.NONE),
                element = null,
            )

            MonitorKind.ADVANCED_REQUEST -> draft.copy(kind = kind, element = null)

            MonitorKind.WEBSITE_ELEMENT -> draft.copy(
                kind = kind,
                method = me.river.pulse.domain.HttpMethod.GET,
                element = draft.element ?: ElementTarget(),
            )
        }
        testResult = null
    }

    fun goTo(target: Int) {
        step = target.coerceIn(0, LAST_STEP)
    }

    fun next() = goTo(step + 1)

    fun back() = goTo(step - 1)

    fun openPicker() {
        pickerOpen = Validation.urlNote(draft.url)?.severity != Validation.Severity.ERROR
    }

    fun closePicker() {
        pickerOpen = false
    }

    fun applyPick(
        cssSelector: String,
        xpath: String,
        elementId: String,
        tagName: String,
        classSignature: String,
        text: String,
    ) {
        val existing = draft.element ?: ElementTarget()
        draft = draft.copy(
            element = existing.copy(
                cssSelector = cssSelector,
                xpath = xpath,
                elementId = elementId,
                tagName = tagName,
                classSignature = classSignature,
                textSnippet = text,
                expectedText = existing.expectedText.ifBlank { text },
            ),
            name = draft.name.ifBlank { "" },
        )
        pickerOpen = false
        testResult = null
    }

    fun runTest() {
        if (testing) return
        val validation = report
        if (!validation.isValid) return
        testing = true
        viewModelScope.launch {
            try {
                testResult = graph.engine.dryRun(draft)
            } finally {
                testing = false
            }
        }
    }

    fun save() {
        val validation = report
        if (!validation.isValid) return
        viewModelScope.launch {
            val clean = draft.copy(
                url = draft.url.trim(),
                name = draft.name.trim(),
                headers = draft.headers.filterNot { it.isBlank },
            )
            graph.store.upsert(clean)
            val snapshot = graph.store.currentSnapshot()
            graph.scheduler.scheduleNext(clean, snapshot.settings)
            graph.scheduler.ensureSweep(snapshot.settings)
            if (clean.enabled) graph.engine.run(clean.id)
            saved = true
        }
    }

    companion object {
        const val LAST_STEP = 3
    }
}

// --------------------------------------------------------------------- detail

class DetailViewModel(
    private val graph: Pulse.Graph,
    private val monitorId: String,
) : ViewModel() {

    val card: StateFlow<MonitorCard?> = graph.store.monitorFlow(monitorId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var busy by mutableStateOf(false)
        private set

    var toast by mutableStateOf<String?>(null)
        private set

    fun checkNow() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                graph.engine.run(monitorId)
            } finally {
                busy = false
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.store.setEnabled(monitorId, enabled)
            val snapshot = graph.store.currentSnapshot()
            snapshot.monitors.firstOrNull { it.id == monitorId }?.let {
                graph.scheduler.scheduleNext(it, snapshot.settings)
            }
        }
    }

    fun mute(hours: Int) {
        viewModelScope.launch {
            graph.engine.mute(monitorId, hours * 60 * 60 * 1000L)
            toast = "Muted for ${hours}h"
        }
    }

    fun unmute() {
        viewModelScope.launch {
            graph.engine.unmute(monitorId)
            toast = "Alerts back on"
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.store.delete(monitorId)
            graph.scheduler.cancel(monitorId)
            graph.alerts.cancel(monitorId)
            onDone()
        }
    }

    fun consumeToast() {
        toast = null
    }
}

// ------------------------------------------------------------------- settings

class SettingsViewModel(private val graph: Pulse.Graph) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = graph.store.snapshot
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    var toast by mutableStateOf<String?>(null)
        private set

    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch {
            graph.store.updateSettings(transform)
            val snapshot = graph.store.currentSnapshot()
            graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
        }
    }

    fun updateDefaultAlert(transform: (AlertPolicy) -> AlertPolicy) {
        update { it.copy(defaultAlert = transform(it.defaultAlert)) }
    }

    fun previewVibration(style: me.river.pulse.domain.VibrationStyle) {
        graph.alerts.previewVibration(style)
    }

    fun sendTestAlert() {
        viewModelScope.launch {
            val policy = graph.store.currentSnapshot().settings.defaultAlert
            if (!graph.alerts.hasNotificationPermission()) {
                toast = "Notifications are blocked — enable them in system settings"
                return@launch
            }
            graph.alerts.previewPolicy(policy)
            toast = "Test alert sent"
        }
    }

    fun consumeToast() {
        toast = null
    }
}

// ------------------------------------------------------------------ factories

@androidx.compose.runtime.Composable
fun rememberDashboardViewModel(): DashboardViewModel = viewModel(
    factory = viewModelFactory { initializer { DashboardViewModel(Pulse.require()) } },
)

@androidx.compose.runtime.Composable
fun rememberSetupViewModel(monitorId: String?): SetupViewModel = viewModel(
    key = "setup-${monitorId ?: "new"}",
    factory = viewModelFactory { initializer { SetupViewModel(Pulse.require(), monitorId) } },
)

@androidx.compose.runtime.Composable
fun rememberDetailViewModel(monitorId: String): DetailViewModel = viewModel(
    key = "detail-$monitorId",
    factory = viewModelFactory { initializer { DetailViewModel(Pulse.require(), monitorId) } },
)

@androidx.compose.runtime.Composable
fun rememberSettingsViewModel(): SettingsViewModel = viewModel(
    factory = viewModelFactory { initializer { SettingsViewModel(Pulse.require()) } },
)
