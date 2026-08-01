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

/**
 * Shown when a human asks for a check with no connectivity. Worth saying out
 * loud: silently doing nothing would read as the app being broken, and running
 * the check would produce a false outage.
 */
private const val OFFLINE_TOAST = "You're offline — checks are paused"

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

    /** Drives the banner's paused state. See [me.river.pulse.data.net.NetworkMonitor]. */
    val offline: StateFlow<Boolean> = graph.network.online
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), !graph.network.isOnline())

    fun checkAll() {
        if (refreshing) return
        // Say so rather than reporting "nothing to check yet", which is what the
        // engine's honest 0 would otherwise be translated into.
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
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
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
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
            // All three id spaces. Cancelling only the down one used to strand
            // the monitor's urgent notification forever: it is `ongoing`, and
            // once the monitor is gone no per-monitor loop ever visits it again.
            graph.alerts.cancelAll(monitorId)
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

    fun acknowledgeUrgent(monitorId: String) {
        viewModelScope.launch {
            graph.engine.acknowledgeUrgent(monitorId)
            toast = "Urgent alert acknowledged"
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

    /**
     * Which element slot the picker is filling. `-1` means "append a new one" —
     * the picker itself is identical either way, only the commit differs.
     */
    var pickingIndex by mutableStateOf(-1)
        private set

    var saved by mutableStateOf(false)
        private set

    var isEditing by mutableStateOf(editingId != null)
        private set

    var realBlurEnabled by mutableStateOf(true)
        private set

    val report: Validation.Report get() = Validation.report(draft)

    /** Always the list, never the legacy single field. */
    val elements: List<ElementTarget> get() = draft.targets

    init {
        viewModelScope.launch {
            val snapshot = graph.store.currentSnapshot()
            realBlurEnabled = snapshot.settings.realBlurEnabled
            if (editingId != null) {
                snapshot.monitors.firstOrNull { it.id == editingId }?.let { draft = it.migrated }
            } else {
                draft = draft.copy(
                    intervalMinutes = snapshot.settings.defaultIntervalMinutes,
                    timeoutSeconds = snapshot.settings.defaultTimeoutSeconds,
                    latencySloMs = 0,
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
            ).withTargets(emptyList())

            MonitorKind.ADVANCED_REQUEST -> draft.copy(kind = kind).withTargets(emptyList())

            MonitorKind.WEBSITE_ELEMENT -> draft.copy(
                kind = kind,
                method = me.river.pulse.domain.HttpMethod.GET,
            )
        }
        testResult = null
    }

    // ---- multi-element editing ---------------------------------------------

    fun updateElement(index: Int, transform: (ElementTarget) -> ElementTarget) {
        val current = draft.targets
        if (index !in current.indices) return
        draft = draft.withTargets(current.toMutableList().also { it[index] = transform(it[index]) })
        testResult = null
    }

    fun removeElement(index: Int) {
        val current = draft.targets
        if (index !in current.indices) return
        draft = draft.withTargets(current.filterIndexed { i, _ -> i != index })
        testResult = null
    }

    fun moveElement(index: Int, delta: Int) {
        val current = draft.targets.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        current.add(target, current.removeAt(index))
        draft = draft.withTargets(current)
        testResult = null
    }

    fun goTo(target: Int) {
        step = target.coerceIn(0, LAST_STEP)
    }

    fun next() = goTo(step + 1)

    fun back() = goTo(step - 1)

    /** @param index element slot to overwrite, or -1 to append a new one. */
    fun openPicker(index: Int = -1) {
        if (Validation.urlNote(draft.url)?.severity == Validation.Severity.ERROR) return
        pickingIndex = index
        pickerOpen = true
    }

    fun closePicker() {
        pickerOpen = false
        pickingIndex = -1
    }

    fun applyPick(
        cssSelector: String,
        xpath: String,
        elementId: String,
        tagName: String,
        classSignature: String,
        text: String,
    ) {
        val current = draft.targets.toMutableList()
        val index = pickingIndex
        // Re-picking keeps the slot's mode/label/attribute — the user is
        // repairing a broken selector, not starting over.
        val existing = current.getOrNull(index) ?: ElementTarget()
        val updated = existing.copy(
            cssSelector = cssSelector,
            xpath = xpath,
            elementId = elementId,
            tagName = tagName,
            classSignature = classSignature,
            textSnippet = text,
            expectedText = existing.expectedText.ifBlank { text },
        )
        if (index in current.indices) current[index] = updated else current += updated
        draft = draft.withTargets(current)
        pickerOpen = false
        pickingIndex = -1
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
            ).migrated
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
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
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

    /**
     * In-app acknowledgement of an urgent outage. Same path as the notification
     * action, so the two can't drift apart.
     */
    fun acknowledgeUrgent() {
        viewModelScope.launch {
            graph.engine.acknowledgeUrgent(monitorId)
            toast = "Acknowledged — no more urgent alerts for this outage"
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.store.delete(monitorId)
            graph.scheduler.cancel(monitorId)
            graph.alerts.cancelAll(monitorId)
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
            // Strict mode is a setting, so flipping it has to start or stop the
            // service right away rather than at the next check.
            graph.notifyStateChanged()
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
