package me.river.pulse.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.river.pulse.BuildConfig
import me.river.pulse.data.Nightbell
import me.river.pulse.data.transfer.BackupCodec
import me.river.pulse.data.transfer.BackupError
import me.river.pulse.data.transfer.toImportableSnapshot
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.CheckerHealth
import me.river.pulse.domain.CheckerLimit
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorQuery
import me.river.pulse.domain.MonitorTemplates
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.Validation
import me.river.pulse.domain.isCancellation
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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

class DashboardViewModel(private val graph: Nightbell.Graph) : ViewModel() {

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

    // ---- narrowing ---------------------------------------------------------
    //
    // Held here rather than in the composable so it survives a rotation and a
    // trip into a monitor's detail screen; coming back to a dashboard that has
    // silently forgotten the filter you set is its own small betrayal.

    var spec by mutableStateOf(MonitorQuery.Spec())
        private set

    init {
        // Restores only the sort. Search text and filters start clear on purpose: a
        // dashboard that opens with monitors hidden reads as a dashboard that has
        // lost them.
        viewModelScope.launch {
            val stored = graph.store.currentSnapshot().settings.dashboardSort
            if (spec.sort == MonitorQuery.Sort.WORST_FIRST) {
                spec = spec.copy(sort = stored)
            }
        }
    }

    /** True while anything is hidden — drives the "clear" affordance. */
    val narrowed: Boolean get() = !spec.hidesNothing

    /**
     * Ids in the order a drag currently has them, or null when nothing is dragging.
     *
     * The store is written once, on drop. Committing on every crossed boundary
     * would put a DataStore write behind each few pixels of finger travel, and a
     * reorder that fights the disk is a reorder that stutters.
     */
    var reorderPreview by mutableStateOf<List<String>?>(null)
        private set

    /** The list as shown: filtered, searched, sorted, and mid-drag if dragging. */
    val visible: List<MonitorCard>
        get() {
            val base = MonitorQuery.apply(cards.value, spec)
            val preview = reorderPreview ?: return base
            val byId = base.associateBy { it.monitor.id }
            // Fall back to the sorted list if the preview has gone stale — a monitor
            // can be deleted from a notification action while a drag is in progress.
            val ordered = preview.mapNotNull(byId::get)
            return if (ordered.size == base.size) ordered else base
        }

    fun beginReorder() {
        reorderPreview = visible.map { it.monitor.id }
    }

    fun moveInReorder(fromId: String, toId: String) {
        val current = reorderPreview ?: return
        val from = current.indexOf(fromId)
        val to = current.indexOf(toId)
        if (from < 0 || to < 0) return
        reorderPreview = MonitorQuery.reordered(current, from, to)
    }

    /** Nudge one monitor by a single place — the accessible path into the same edit. */
    fun nudge(monitorId: String, by: Int) {
        val ids = visible.map { it.monitor.id }
        val from = ids.indexOf(monitorId)
        if (from < 0) return
        val to = (from + by).coerceIn(0, ids.lastIndex)
        if (from == to) return
        val next = MonitorQuery.reordered(ids, from, to)
        reorderPreview = null
        viewModelScope.launch { graph.store.reorder(next) }
    }

    fun commitReorder() {
        val order = reorderPreview ?: return
        reorderPreview = null
        viewModelScope.launch { graph.store.reorder(order) }
    }

    fun cancelReorder() {
        reorderPreview = null
    }

    fun setQuery(value: String) {
        spec = spec.copy(query = value)
    }

    fun setFilter(filter: MonitorQuery.Filter) {
        spec = spec.copy(filter = filter)
    }

    fun setSort(sort: MonitorQuery.Sort) {
        spec = spec.copy(sort = sort)
        viewModelScope.launch { graph.store.updateSettings { it.copy(dashboardSort = sort) } }
    }

    /**
     * Clears the search and the filter, and leaves the sort alone.
     *
     * "Clear" means "stop hiding things". Resetting a hand-made order as a side
     * effect of clearing a search would be a destructive act behind a harmless
     * label.
     */
    fun clearNarrowing() {
        spec = MonitorQuery.Spec(sort = spec.sort)
    }

    // ---- selection ---------------------------------------------------------
    //
    // Entered by long-pressing a card. Bulk actions exist because pausing eight
    // monitors for a deploy window was eight trips into eight detail screens.

    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    val selecting: Boolean get() = selection.isNotEmpty()

    fun toggleSelected(monitorId: String) {
        selection = if (monitorId in selection) selection - monitorId else selection + monitorId
    }

    fun selectAllVisible() {
        selection = visible.map { it.monitor.id }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /**
     * Pause or resume everything selected.
     *
     * Re-schedules per monitor rather than once at the end, because the scheduler
     * is keyed per monitor and a single sync would not know which ones changed.
     */
    fun setEnabledForSelection(enabled: Boolean) {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                graph.store.setEnabled(id, enabled)
                if (!enabled) graph.engine.forgetMonitor(id)
            }
            val snapshot = graph.store.currentSnapshot()
            snapshot.monitors.filter { it.id in ids }.forEach {
                graph.scheduler.schedule(it, snapshot.settings)
            }
            graph.notifyStateChanged()
            toast = "${ids.size} ${plural(ids.size)} ${if (enabled) "resumed" else "paused"}"
            clearSelection()
        }
    }

    fun muteSelection(hours: Int) {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { graph.engine.mute(it, hours * 60 * 60 * 1000L) }
            toast = "${ids.size} ${plural(ids.size)} muted for ${hours}h"
            clearSelection()
        }
    }

    fun deleteSelection() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                graph.scheduler.cancel(id)
                graph.alerts.cancelAll(id)
                graph.engine.forgetMonitor(id)
                graph.store.delete(id)
            }
            graph.notifyStateChanged()
            toast = "${ids.size} ${plural(ids.size)} deleted"
            clearSelection()
        }
    }

    private fun plural(count: Int) = if (count == 1) "monitor" else "monitors"

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
                graph.scheduler.schedule(it, snapshot.settings)
            }
            // A paused monitor can no longer support a checker-crash claim.
            if (!enabled) graph.engine.forgetMonitor(monitorId)
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
            graph.engine.forgetMonitor(monitorId)
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
    private val graph: Nightbell.Graph,
    private val editingId: String?,
    private val templateId: String? = null,
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

    /**
     * The draft as it stood once the screen had finished opening.
     *
     * Captured *after* the store has loaded, so an edit is compared against what
     * is actually persisted and a new monitor against the defaults it was seeded
     * with — comparing against the pre-seed blank would call every new monitor
     * dirty before the user had touched anything.
     */
    private var baseline by mutableStateOf<Monitor?>(null)

    /** There is unsaved work worth asking about before throwing it away. */
    val isDirty: Boolean get() = baseline?.let { it != draft } == true

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
                // A template answers step 0 — "what kind of thing is this" — so the
                // wizard opens on step 1 with the URL field waiting. Skipping
                // straight past a question the user has already answered is the
                // entire value of picking a template.
                MonitorTemplates.byId(templateId.orEmpty())?.let { template ->
                    draft = template.apply(draft)
                    step = 1
                }
            }
            baseline = draft
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
            graph.scheduler.schedule(clean, snapshot.settings)
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
    private val graph: Nightbell.Graph,
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
                graph.scheduler.schedule(it, snapshot.settings)
            }
            if (!enabled) graph.engine.forgetMonitor(monitorId)
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
            graph.engine.forgetMonitor(monitorId)
            onDone()
        }
    }

    fun consumeToast() {
        toast = null
    }
}

// ------------------------------------------------------------------- settings

class SettingsViewModel(private val graph: Nightbell.Graph) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = graph.store.snapshot
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    /**
     * The checker's own health — see [me.river.pulse.domain.CheckerHealth].
     *
     * Combined with the persisted streak, not read from the engine alone: the
     * checks that failed were very likely run by a WorkManager process that no
     * longer exists, so the engine's in-memory state in *this* process would say
     * "running normally" while every background check was in fact throwing.
     */
    val checkerHealth: StateFlow<CheckerHealth.State> = graph.engine.checkerHealth
        .combine(graph.store.snapshot) { inMemory, snapshot ->
            CheckerHealth.hydrate(snapshot.checkerStreak, inMemory, System.currentTimeMillis())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckerHealth.State.Healthy)

    /**
     * Why background checks may be running late. Re-derived from the platform on
     * every store change *and* on a slow tick, because battery saver and
     * background restriction can be toggled while this screen is open and neither
     * emits anything the app can subscribe to.
     */
    val checkerLimit: StateFlow<CheckerLimit> = graph.store.snapshot
        .combine(ticker()) { snapshot, _ -> graph.limits.diagnose(snapshot) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckerLimit.NONE)

    /**
     * Whether Android is still deferring Nightbell's work.
     *
     * A `StateFlow` off the same ticker, not a plain getter. As a getter it was read
     * positionally during composition with nothing to invalidate it, so after the
     * user granted the exemption and pressed Back the card went on offering the
     * button — reading as though the grant had not taken.
     */
    val batteryOptimised: StateFlow<Boolean> = ticker()
        .map { !graph.limits.isIgnoringBatteryOptimizations() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            !graph.limits.isIgnoringBatteryOptimizations(),
        )

    fun batterySettingsIntent() = graph.limits.batterySettingsIntent()

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(LIMIT_POLL_MS)
        }
    }

    var toast by mutableStateOf<String?>(null)
        private set

    var refetchingFavicons by mutableStateOf(false)
        private set

    var transferring by mutableStateOf(false)
        private set

    /**
     * Writes the whole store out through [sink].
     *
     * The caller supplies the sink rather than the URI because opening it needs a
     * `ContentResolver`, which belongs to the screen; what belongs here is the
     * snapshot, the coroutine and the reporting. See
     * [me.river.pulse.data.transfer.NightbellBackup] for why this exists at
     * all.
     */
    fun exportBackup(sink: suspend (String) -> Unit) {
        if (transferring) return
        transferring = true
        viewModelScope.launch {
            try {
                val snapshot = graph.store.currentSnapshot()
                val document = BackupCodec.encode(
                    snapshot = snapshot,
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    nowMs = System.currentTimeMillis(),
                )
                sink(document)
                val count = snapshot.monitors.size
                toast = "Exported $count monitor" + if (count == 1) "" else "s"
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                Log.w(TAG, "Export failed", error)
                toast = "Couldn't write that file"
            } finally {
                transferring = false
            }
        }
    }

    /**
     * Replaces the store with the backup read from [source].
     *
     * Replace rather than merge, and the screen confirms before calling this. A
     * merge would have to invent an answer for two monitors with the same id and
     * different settings, and the case this exists for — moving a fleet to the
     * renamed app — is a fresh install where there is nothing to merge with.
     *
     * Everything is rescheduled afterwards, because the imported monitors have no
     * work enqueued for them in *this* install and would otherwise sit there
     * until something else triggered a sync.
     */
    fun importBackup(source: suspend () -> String) {
        if (transferring) return
        transferring = true
        viewModelScope.launch {
            try {
                val raw = source()
                val backup = BackupCodec.decode(raw).getOrElse { error ->
                    toast = (error as? BackupCodec.BackupFailure)?.error?.message
                        ?: BackupError.Unreadable.message
                    return@launch
                }
                val imported = backup.toImportableSnapshot()
                graph.store.replaceAll(imported)
                graph.scheduler.syncAll(imported.monitors, imported.settings)
                graph.scheduler.ensureSweep(imported.settings)
                // Nothing here has ever been checked — health is UNKNOWN by
                // construction — so a pass now is what makes the import look like
                // it worked rather than like a screen of grey cards.
                graph.engine.clearCheckerHealth("store replaced by import")
                graph.notifyStateChanged()
                if (graph.network.isOnline()) graph.engine.runAllDue(force = true)
                val count = imported.monitors.size
                toast = "Imported $count monitor" + if (count == 1) "" else "s"
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                Log.w(TAG, "Import failed", error)
                toast = "Couldn't read that file"
            } finally {
                transferring = false
            }
        }
    }

    /**
     * Throws away the cached site icons and fetches them again.
     *
     * Icons are cached for a month, which is right for something that almost
     * never changes and wrong on the day it does. Only page-element monitors
     * display one, so only those sites are visited — asking an API endpoint for a
     * favicon is a request nobody benefits from.
     */
    fun refetchFavicons() {
        if (refetchingFavicons) return
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
        refetchingFavicons = true
        viewModelScope.launch {
            try {
                val urls = graph.store.currentSnapshot().monitors
                    .filter { it.kind == MonitorKind.WEBSITE_ELEMENT && it.url.isNotBlank() }
                    .map { it.url }
                val result = graph.favicons.refetch(urls)
                val sites = "${result.sites} site" + if (result.sites == 1) "" else "s"
                toast = when {
                    result.sites == 0 -> "No website monitors to fetch icons for"
                    result.changed == 0 -> "Checked $sites — icons unchanged"
                    else -> "${result.changed} of $sites updated"
                }
            } finally {
                refetchingFavicons = false
            }
        }
    }

    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch {
            graph.store.updateSettings(transform)
            val snapshot = graph.store.currentSnapshot()
            graph.scheduler.syncAll(snapshot.monitors, snapshot.settings)
            // The schedule was just rebuilt, so a claim about how checks were
            // failing no longer describes how they run.
            graph.engine.clearCheckerHealth("settings changed")
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

    private companion object {
        const val TAG = "SettingsViewModel"

        /** Slow on purpose: this is a settings screen, not a dashboard. */
        const val LIMIT_POLL_MS = 5_000L
    }
}

// ------------------------------------------------------------------ factories

@androidx.compose.runtime.Composable
fun rememberDashboardViewModel(): DashboardViewModel = viewModel(
    factory = viewModelFactory { initializer { DashboardViewModel(Nightbell.require()) } },
)

@androidx.compose.runtime.Composable
fun rememberSetupViewModel(monitorId: String?, templateId: String? = null): SetupViewModel = viewModel(
    // The template is part of the key: two different templates are two different
    // drafts, and reusing one view model between them would show the first
    // template's fields under the second one's name.
    key = "setup-${monitorId ?: "new"}-${templateId ?: "blank"}",
    factory = viewModelFactory {
        initializer { SetupViewModel(Nightbell.require(), monitorId, templateId) }
    },
)

@androidx.compose.runtime.Composable
fun rememberDetailViewModel(monitorId: String): DetailViewModel = viewModel(
    key = "detail-$monitorId",
    factory = viewModelFactory { initializer { DetailViewModel(Nightbell.require(), monitorId) } },
)

@androidx.compose.runtime.Composable
fun rememberSettingsViewModel(): SettingsViewModel = viewModel(
    factory = viewModelFactory { initializer { SettingsViewModel(Nightbell.require()) } },
)
