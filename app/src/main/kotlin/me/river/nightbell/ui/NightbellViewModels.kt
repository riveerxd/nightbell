package me.river.nightbell.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.river.nightbell.BuildConfig
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.BackupError
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AssertionMode
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerLimit
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.domain.MonitorTemplates
import me.river.nightbell.domain.PauseScope
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.GitHubRepo
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Secrets
import me.river.nightbell.domain.UpdateState
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import me.river.nightbell.domain.Validation
import me.river.nightbell.domain.isCancellation
import me.river.nightbell.domain.runCatchingCancellable
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

    /** The standing pause, if any. Drives the banner and the button's label. */
    val pause: StateFlow<PauseState> = graph.store.snapshot
        .map { it.pause }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PauseState())

    /**
     * Open when the user has tapped pause and still has something to answer.
     *
     * Null when the sheet is closed. Holds the scope once it is known, so the
     * sheet can ask for a scope first and a duration second without the caller
     * having to track which half it is on.
     */
    var pausePrompt by mutableStateOf<PausePrompt?>(null)
        private set

    /** Drives the banner's paused state. See [me.river.nightbell.data.net.NetworkMonitor]. */
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

    /**
     * Tapping pause. Resumes if a pause is standing, otherwise starts asking.
     *
     * Which questions get asked is [GlobalSettings.pauseChoice]: with a scope
     * already chosen the sheet only needs a duration, and with
     * [PauseChoice.ASK] it needs both.
     */
    fun onPauseTapped() {
        if (pause.value.isActive(System.currentTimeMillis())) {
            resume()
            return
        }
        pausePrompt = PausePrompt(scope = settings.value.pauseChoice.scope)
    }

    fun choosePauseScope(scope: PauseScope) {
        pausePrompt = (pausePrompt ?: PausePrompt()).copy(scope = scope)
    }

    fun dismissPausePrompt() {
        pausePrompt = null
    }

    /** Commits the pause. [minutes] of null is the indefinite entry. */
    fun pauseFor(minutes: Int?) {
        val scope = pausePrompt?.scope ?: settings.value.pauseChoice.scope ?: PauseScope.STOP_CHECKS
        pausePrompt = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val state = if (minutes == null) {
                PauseState.forever(now, scope)
            } else {
                PauseState.timed(now, minutes, scope)
            }
            graph.engine.pauseAll(state)
            toast = when {
                minutes == null && scope == PauseScope.STOP_CHECKS -> "Paused until you resume"
                minutes == null -> "Silenced until you resume"
                scope == PauseScope.STOP_CHECKS -> "Paused for ${durationLabel(minutes)}"
                else -> "Silenced for ${durationLabel(minutes)}"
            }
        }
    }

    fun resume() {
        pausePrompt = null
        refreshing = true
        viewModelScope.launch {
            try {
                graph.engine.resumeAll()
                toast = "Monitoring again"
            } finally {
                refreshing = false
            }
        }
    }

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
     * Global settings as they stood when this screen opened.
     *
     * Read once at open like [realBlurEnabled], because the routing toggle needs
     * to say where a check would actually go, and a toggle offering to route
     * traffic nowhere is worse than one that says the proxy is not set up yet.
     */
    var settings by mutableStateOf(GlobalSettings())
        private set

    /** The shared proxy, or null when there is nothing usable to route through. */
    val proxy: ProxyRoute.Endpoint? get() = ProxyRoute.endpoint(settings)

    /** The shared budget a routed check inherits when this monitor sets none. */
    val proxiedTimeoutSeconds: Int get() = settings.proxiedTimeoutSeconds

    /**
     * Where the element picker is allowed to load the draft's URL from.
     *
     * The same decision the check makes, from the same two inputs, because the
     * picker renders the same page from the same device. 3.1.0 routed the check
     * and left the picker unrouted, so a monitor on a hidden service leaked the
     * hostname to the phone's resolver at setup time.
     */
    val pickerRoute: ProxyRoute.Route get() = ProxyRoute.forMonitor(draft, settings)

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
            settings = snapshot.settings
            if (editingId != null) {
                snapshot.monitors.firstOrNull { it.id == editingId }?.let { draft = it.migrated }
                if (draft.github.repository.isSet) repoInput = draft.github.slug
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
                method = me.river.nightbell.domain.HttpMethod.GET,
                assertion = draft.assertion.copy(mode = AssertionMode.NONE),
            ).withTargets(emptyList())

            MonitorKind.ADVANCED_REQUEST -> draft.copy(kind = kind).withTargets(emptyList())

            MonitorKind.WEBSITE_ELEMENT -> draft.copy(
                kind = kind,
                method = me.river.nightbell.domain.HttpMethod.GET,
            )

            MonitorKind.GITHUB_REPO -> draft.copy(
                kind = kind,
                method = me.river.nightbell.domain.HttpMethod.GET,
                status = StatusExpectation(mode = StatusMode.ANY_SUCCESS),
                assertion = draft.assertion.copy(mode = AssertionMode.NONE),
                // GitHub allows 60 requests an hour without a token and one poll
                // spends up to three of them, so anything tighter than a quarter
                // of an hour runs the device out of budget before the hour is up.
                intervalMinutes = maxOf(draft.intervalMinutes, MIN_GITHUB_INTERVAL),
            ).withTargets(emptyList())
        }
        testResult = null
    }

    // ---- github ------------------------------------------------------------

    /**
     * What the user has typed into the repository field.
     *
     * Held separately from the draft because the two are not the same string: the
     * field takes anything that names a repository and the draft stores the parsed
     * `owner/repo`, so binding the field to the draft would rewrite a
     * half-pasted URL under the cursor on every keystroke.
     */
    var repoInput by mutableStateOf("")
        private set

    /** The repository field, and everything it derives. */
    fun setRepo(raw: String) {
        repoInput = raw
        val parsed = GitHubRepo.parse(raw)
        draft = if (parsed == null) {
            // Cleared rather than left stale: a URL that no longer parses must not
            // leave the previous repository quietly saved behind it.
            draft.copy(url = "", github = draft.github.copy(owner = "", repo = ""))
        } else {
            draft.copy(
                // The canonical page, so every screen that shows a monitor's URL,
                // and every "open this" the app offers, lands somewhere useful.
                url = parsed.url,
                github = draft.github.copy(owner = parsed.owner, repo = parsed.name),
            )
        }
        testResult = null
    }

    fun updateGitHub(transform: (GitHubWatch) -> GitHubWatch) {
        draft = draft.copy(github = transform(draft.github))
        testResult = null
    }

    /** The saved token, redacted. Never the token itself. See [Validation]. */
    val githubTokenRedacted: String
        get() = Secrets.redact(settings.githubToken)

    val hasGitHubToken: Boolean get() = settings.githubToken.isNotBlank()

    /**
     * Saves a token from the setup flow.
     *
     * Global rather than per monitor: the rate limit is per device, so a token is
     * a property of the phone rather than of one repository, and asking for it
     * again per monitor would be asking the user to paste a credential twice.
     */
    fun setGitHubToken(value: String) {
        val cleaned = value.trim()
        settings = settings.copy(githubToken = cleaned)
        viewModelScope.launch { graph.store.updateSettings { it.copy(githubToken = cleaned) } }
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

        /** Below this, an unauthenticated device runs out of GitHub budget. */
        const val MIN_GITHUB_INTERVAL = 15
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

    /**
     * "I have read this repository's news."
     *
     * Takes down what is on screen and records when, and deliberately does not
     * touch the last-seen ids: those advanced when the poll found the news, and
     * rewinding them would announce all of it again on the next check. Same path
     * as the notification action, so the two cannot drift.
     */
    fun markGitHubSeen() {
        viewModelScope.launch {
            graph.alerts.cancelGitHub(monitorId)
            graph.store.updateRuntime(monitorId) {
                it.copy(github = it.github.copy(seenAt = System.currentTimeMillis()))
            }
            toast = "Marked as seen"
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
     * The checker's own health — see [me.river.nightbell.domain.CheckerHealth].
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

    /**
     * Which file transfer is running, if either.
     *
     * More than a boolean because both buttons read it, and a single flag made
     * the import button announce itself as busy while an export was the thing
     * actually happening.
     */
    var transfer by mutableStateOf<Transfer?>(null)
        private set

    val transferring: Boolean get() = transfer != null

    /**
     * Writes the whole store out through [sink].
     *
     * The caller supplies the sink rather than the URI because opening it needs a
     * `ContentResolver`, which belongs to the screen; what belongs here is the
     * snapshot, the coroutine and the reporting. See
     * [me.river.nightbell.data.transfer.NightbellBackup] for why this exists at
     * all.
     */
    fun exportBackup(sink: suspend (String) -> Unit) {
        if (transferring) return
        transfer = Transfer.EXPORT
        viewModelScope.launch {
            try {
                val snapshot = graph.store.currentSnapshot()
                val document = BackupCodec.encode(
                    snapshot = snapshot,
                    applicationId = BuildConfig.APPLICATION_ID,
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    nowMs = System.currentTimeMillis(),
                    includeSecrets = snapshot.settings.includeSecretsInExport,
                )
                sink(document)
                val count = snapshot.monitors.size
                val secrets = snapshot.settings.includeSecretsInExport &&
                    snapshot.settings.githubToken.isNotBlank()
                toast = "Exported $count monitor" + (if (count == 1) "" else "s") +
                    if (secrets) ", token included" else ""
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                Log.w(TAG, "Export failed", error)
                toast = "Couldn't write that file"
            } finally {
                transfer = null
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
        transfer = Transfer.IMPORT
        viewModelScope.launch {
            try {
                val raw = source()
                val backup = BackupCodec.decode(raw).getOrElse { error ->
                    toast = (error as? BackupCodec.BackupFailure)?.error?.message
                        ?: BackupError.Unreadable.message
                    return@launch
                }
                // The pause belongs to this device and this afternoon, not to the
                // file. A backup describes monitors; being somewhere with one bar
                // is a live local instruction, and letting an unrelated import
                // clear it would resume the whole fleet and page for every failure
                // the pause existed to hold back.
                val standing = graph.store.currentSnapshot().pause
                val imported = backup.toImportableSnapshot().copy(pause = standing)
                graph.store.replaceAll(imported)
                graph.scheduler.syncAll(imported.monitors, imported.settings)
                graph.scheduler.ensureSweep(imported.settings)
                // Nothing here has ever been checked — health is UNKNOWN by
                // construction — so a pass now is what makes the import look like
                // it worked rather than like a screen of grey cards.
                graph.engine.clearCheckerHealth("store replaced by import")
                graph.notifyStateChanged()
                val count = imported.monitors.size
                toast = "Imported $count monitor" + if (count == 1) "" else "s"

                // The import is done at this point: the monitors are in the store
                // and on screen. The first check pass is not part of it, and
                // waiting on it here is what made importing look like the app had
                // frozen, because it is a full round trip per monitor before
                // anything at all is reported back.
                //
                // On `appScope`, not this ViewModel's: leaving Settings to go and
                // look at the dashboard is the expected thing to do the moment the
                // toast appears, and that must not cancel the pass. The cards
                // carry their own checking shimmer while it runs, which is a
                // better account of what is happening than a spinner on a button
                // in a screen the user has already left.
                if (graph.network.isOnline()) {
                    graph.appScope.launch {
                        runCatchingCancellable { graph.engine.runAllDue(force = true) }
                    }
                }
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                Log.w(TAG, "Import failed", error)
                toast = "Couldn't read that file"
            } finally {
                transfer = null
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

    // ---- GitHub token ------------------------------------------------------

    /**
     * The saved token, redacted.
     *
     * The screen never has access to the token itself, which is the point: there
     * is no path from a composable to the credential, so no future edit to that
     * screen can put it on the display or into a screenshot by accident.
     */
    val githubTokenRedacted: StateFlow<String> = graph.store.snapshot
        .map { Secrets.redact(it.settings.githubToken) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setGitHubToken(raw: String) {
        val cleaned = raw.trim()
        update { it.copy(githubToken = cleaned) }
        toast = if (cleaned.isBlank()) "Token removed" else "Token saved on this device"
    }

    // ---- Nightbell's own updates --------------------------------------------

    val appUpdate: StateFlow<UpdateState> = graph.store.snapshot
        .map { it.update }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateState())

    var checkingForUpdate by mutableStateOf(false)
        private set

    /**
     * The "check now" button.
     *
     * Forces past the six-hour throttle, because a human asking is a better
     * reason than a timer, and past `notifiedVersion` would be a step too far:
     * being told again about a version already refused is not what the button
     * offers. The result is reported either way, so a tap always answers.
     */
    fun checkForUpdateNow() {
        if (checkingForUpdate) return
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
        checkingForUpdate = true
        viewModelScope.launch {
            try {
                graph.engine.checkForAppUpdate(force = true)
                val state = graph.store.currentSnapshot().update
                val latest = state.latestVersion
                toast = when {
                    latest.isBlank() -> "Couldn't reach ${settings.value.updateSource.label}"
                    AppUpdate.isNewer(latest, BuildConfig.VERSION_NAME) ->
                        "Version $latest is available"
                    else -> "You're on the newest version"
                }
            } finally {
                checkingForUpdate = false
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

    fun previewVibration(style: me.river.nightbell.domain.VibrationStyle) {
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

/**
 * What the pause sheet still has to ask.
 *
 * A null [scope] means the scope question is still open; a set one means the
 * only thing left is how long.
 */
data class PausePrompt(val scope: PauseScope? = null)

/** "30 minutes", "4 hours". Used in toasts and on the sheet. */
fun durationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "$minutes minutes"
}

/** The two directions a backup can move. See [SettingsViewModel.transfer]. */
enum class Transfer { EXPORT, IMPORT }
