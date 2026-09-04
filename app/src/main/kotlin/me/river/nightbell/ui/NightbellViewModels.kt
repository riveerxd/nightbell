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
import me.river.nightbell.data.NightbellStore
import me.river.nightbell.data.transfer.BackupCodec
import me.river.nightbell.data.transfer.BackupError
import me.river.nightbell.data.transfer.toImportableSnapshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AssertionMode
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerLimit
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.data.alerts.PageSpeaker
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.SpokenPage
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.GroupRollup
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorGroup
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
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardRow
import me.river.nightbell.ui.dashboard.GroupDraft
import me.river.nightbell.ui.dashboard.GroupTarget
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
 *
 * An error rather than a warning: the tap did not do the thing it was for.
 */
private val OFFLINE_TOAST get() = ToastMessage.error("You're offline, checks are paused")

/**
 * Copy for the operations a user can reach from more than one screen.
 *
 * Held here because they had drifted. Acknowledging an outage said "Urgent alert
 * acknowledged" on the dashboard and "Acknowledged, no more urgent alerts for this
 * outage" on the monitor, and un-muting said "Alerts un-muted" in one place and
 * "Alerts back on" in the other. Same button, same effect, two answers, and the
 * longer of each pair was also the string that wrapped to three lines at the
 * largest font scale.
 */
private const val ACKNOWLEDGED = "Acknowledged, no more pages for this outage"
private const val ALERTS_BACK_ON = "Alerts back on"
private fun mutedFor(hours: Int) = "Alerts muted for ${hours}h"

// ------------------------------------------------------------------ dashboard

class DashboardViewModel(private val graph: Nightbell.Graph) : ViewModel() {

    val cards: StateFlow<List<MonitorCard>> = graph.store.cards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether [cards] is an answer yet.
     *
     * It starts as an empty list, which is indistinguishable from a fleet of
     * nothing, so without this the dashboard greets an existing user with the
     * first-run screen for as long as the disk takes. The widget has guarded this
     * since it was reported there; this is the same guard on the other reader.
     */
    val loaded: StateFlow<Boolean> = graph.store.loadedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.store.loaded)

    val settings: StateFlow<GlobalSettings> = graph.store.snapshot
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    /** Groups as stored, in the order they should be drawn. */
    val groups: StateFlow<List<MonitorGroup>> = graph.store.snapshot
        .map { it.groups }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var refreshing by mutableStateOf(false)
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
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

        // Ask about a new version when the app is opened, not only when the sweep
        // runs. This is what closes the hole: SweepWorker returns early when
        // background checks are off, so it takes the update check down with it and
        // a user who turned background work off would never hear about a release
        // again. Doze does the same thing on most phones for less obvious reasons.
        //
        // On appScope rather than viewModelScope, so leaving the dashboard before
        // the request lands does not cancel it and waste the six-hour window.
        //
        // No second throttle. AppUpdate.isDue already caps this at one request
        // every six hours whatever calls it, and a launch counter on top would be
        // two mechanisms disagreeing about the same number.
        graph.appScope.launch { graph.engine.checkForAppUpdate() }
    }

    // ---- nightbell's own updates -------------------------------------------

    /**
     * The update banner, or null when there is nothing to say.
     *
     * Combined from the two flows rather than read once, because both halves move
     * while this screen is open: the check writes `update` from a background scope,
     * and the switch that gates it lives in Settings one navigation away.
     */
    val updateBanner: StateFlow<AppUpdate.Banner?> = graph.store.snapshot
        .map { snapshot ->
            AppUpdate.bannerFor(
                state = snapshot.update,
                installedVersion = BuildConfig.VERSION_NAME,
                enabled = snapshot.settings.updateChecksEnabled,
                nowMs = System.currentTimeMillis(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Not now": the modal's close, its scrim and the back gesture.
     *
     * `remindLater` rather than `ignore`, and the difference is the whole reason
     * the notice could become a modal at all. A dialog that can only be left by
     * silencing a release forever is a trap, and it was one even as a banner: the
     * close sat a few dp from the Settings gear. Deferring for a day asks again
     * on its own, so nothing needs an undo and no gesture can cost anything.
     *
     * Refusing a version outright still exists, on the notification's own Ignore
     * action, where it is a labelled choice made deliberately.
     */
    fun dismissUpdate() {
        viewModelScope.launch {
            graph.store.updateAppUpdate { AppUpdate.remindLater(it, System.currentTimeMillis()) }
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

    // ---- grouping ----------------------------------------------------------

    /**
     * Takes [groupList] as a parameter rather than reading [groups] itself.
     *
     * `StateFlow.value` is invisible to Compose, so a `rows` that read it would
     * hand the grid a stale list every time a group was created, renamed or
     * collapsed. The screen would only catch up on the next unrelated
     * recomposition. The caller collects the flow, which is what makes the read
     * observable.
     *
     * Grouping is suppressed while the list is narrowed. A search for "night"
     * that matches one member of a three-member group cannot honestly draw the
     * group, because the card would state a verdict for two monitors it is
     * hiding, and a flat list of matches is exactly what someone searching asked
     * for. Sort is still honoured, inside each group and across the ungrouped
     * tail.
     */
    fun rowsFor(groupList: List<MonitorGroup>): List<DashboardRow> {
        run {
            val shown = visible
            if (groupList.isEmpty() || narrowed) {
                return shown.map(DashboardRow::Single)
            }
            val rank = shown.withIndex().associate { (index, card) -> card.monitor.id to index }
            val rows = mutableListOf<DashboardRow>()
            GroupRollup.of(groupList, shown).forEach { rolled ->
                // Drawn even when empty: a group whose last member was deleted
                // would otherwise vanish with no trace, and the user would have no
                // way to tell it apart from one they never made.
                rows += DashboardRow.Group(
                    rolled.copy(members = rolled.members.sortedBy { rank[it.monitor.id] ?: 0 }),
                )
            }
            rows += GroupRollup.ungrouped(groupList, shown).map(DashboardRow::Single)
            return rows
        }
    }

    /** The group open in the editor, or null when it is closed. */
    var groupDraft by mutableStateOf<GroupDraft?>(null)
        private set

    /**
     * Open while the user is choosing which group the selection should join.
     *
     * Null both when nothing is selected and when there is nothing to choose
     * between. See [startGroupFromSelection].
     */
    var groupTarget by mutableStateOf<GroupTarget?>(null)
        private set

    /**
     * The selection is going into a group. Which one is the next question, unless
     * there are no groups yet, in which case there is only one answer.
     *
     * Skipping the chooser on a fresh install matters: a menu with one item is a
     * tap that asks a question the user cannot answer wrongly, and every early
     * group would have paid for it.
     */
    fun startGroupFromSelection() {
        val ids = selectedInOrder()
        if (ids.isEmpty()) return
        if (groups.value.isEmpty()) {
            createGroupFromSelection()
        } else {
            groupTarget = GroupTarget(
                monitorIds = ids,
                // Named here rather than worked out in the dialog: a monitor
                // leaving the group it is in is the one surprising consequence of
                // this action, and the sheet has to be able to say so.
                leavingGroups = groups.value
                    .filter { group -> group.memberIds.any { it in ids } }
                    .map { it.displayTitle },
            )
        }
    }

    fun dismissGroupTarget() {
        groupTarget = null
    }

    /** Opens the editor on a brand-new group made of the selection. */
    fun createGroupFromSelection() {
        val ids = selectedInOrder()
        if (ids.isEmpty()) return
        groupTarget = null
        val monitors = cards.value.filter { it.monitor.id in ids }.map { it.monitor }
        groupDraft = GroupDraft(
            group = MonitorGroup(
                id = java.util.UUID.randomUUID().toString(),
                title = GroupRollup.suggestedTitle(monitors),
                // Seeded from the first member with a URL, because that is the icon
                // the user would have typed by hand nine times out of ten.
                iconUrl = monitors.firstOrNull { it.url.isNotBlank() }?.url.orEmpty(),
                memberIds = ids,
            ),
            creating = true,
        )
    }

    /**
     * Appends the selection to an existing group.
     *
     * Two details that are not obvious and both matter:
     *
     *  - **Appended, not replaced.** The group keeps the members it had, in the
     *    order it had them, and the new ones land at the end. [MonitorGroup]
     *    order is the user's, and rewriting it as a side effect of adding one
     *    monitor would be a silent reorder.
     *  - **The group opens.** Adding to a *collapsed* group makes the card vanish
     *    from the dashboard, because it is inside a group that is drawn shut,
     *    which reads exactly like the monitor was deleted. Expanding is what
     *    turns a disappearance into a move you can see.
     */
    fun addSelectionToGroup(groupId: String) {
        val ids = groupTarget?.monitorIds ?: selectedInOrder()
        groupTarget = null
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val group = graph.store.currentSnapshot().groups.firstOrNull { it.id == groupId }
                ?: return@launch
            graph.store.upsertGroup(
                group.copy(
                    memberIds = group.memberIds + ids.filterNot { it in group.memberIds },
                    collapsed = false,
                ),
            )
            graph.notifyStateChanged()
            toast = ToastMessage.success("Added ${ids.size} ${plural(ids.size)} to “${group.displayTitle}”")
            clearSelection()
        }
    }

    /**
     * Pulls the selection out of whichever groups hold it.
     *
     * The monitors themselves are untouched. They come back to the top level of
     * the dashboard, which is where they were before anyone grouped them. There
     * is deliberately no path from this button that deletes anything.
     */
    fun removeSelectionFromGroups() {
        val ids = selection
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val holders = graph.store.currentSnapshot().groups
                .filter { group -> group.memberIds.any { it in ids } }
            val moved = holders.sumOf { group -> group.memberIds.count { it in ids } }
            if (moved == 0) {
                clearSelection()
                return@launch
            }
            graph.store.removeFromGroups(ids)
            graph.notifyStateChanged()
            toast = ToastMessage.success(
                if (holders.size == 1) {
                    "Removed $moved ${plural(moved)} from “${holders.single().displayTitle}”"
                } else {
                    "Removed $moved ${plural(moved)} from their groups"
                },
            )
            clearSelection()
        }
    }

    fun editGroup(groupId: String) {
        val group = groups.value.firstOrNull { it.id == groupId } ?: return
        groupDraft = GroupDraft(group = group, creating = false)
    }

    fun updateGroupDraft(transform: (MonitorGroup) -> MonitorGroup) {
        groupDraft = groupDraft?.let { it.copy(group = transform(it.group)) }
    }

    fun dismissGroupDraft() {
        groupDraft = null
    }

    fun saveGroupDraft() {
        val draft = groupDraft ?: return
        val group = draft.group.copy(title = draft.group.title.trim())
        val creating = draft.creating
        groupDraft = null
        viewModelScope.launch {
            graph.store.upsertGroup(group)
            graph.notifyStateChanged()
            toast = ToastMessage.success(
                if (creating) "Grouped ${group.size} ${plural(group.size)}" else "Group updated",
            )
            if (creating) clearSelection()
        }
    }

    /**
     * Deletes the group and keeps every monitor in it.
     *
     * Called "ungroup" everywhere the user can see it, because that is what it
     * does: the rows come back to the top level rather than going anywhere. There
     * is deliberately no path from here that deletes the monitors, which would
     * put four monitors' history behind a button labelled about arrangement.
     */
    fun ungroupDraft() {
        val draft = groupDraft ?: return
        val group = draft.group
        groupDraft = null
        viewModelScope.launch {
            graph.store.deleteGroup(group.id)
            graph.notifyStateChanged()
            // Undoable but not held: the monitors are all still there, so the
            // worst case is retyping a title, and a hold on a rearrangement is
            // the modal-fatigue problem in a new costume.
            toast = ToastMessage.undoable("Ungrouped ${group.size} ${plural(group.size)}") {
                viewModelScope.launch {
                    graph.store.upsertGroup(group)
                    graph.notifyStateChanged()
                    toast = ToastMessage.success("Group restored")
                }
            }
        }
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) {
        viewModelScope.launch { graph.store.setGroupCollapsed(groupId, collapsed) }
    }

    /**
     * Pulls one monitor back out to the top level.
     *
     * Reports it, because the bulk path beside it always did and this is the same
     * event: the row disappears from the group card and reappears somewhere else
     * in the list, possibly below the fold. Silence made one monitor leaving a
     * group look like one monitor being deleted, which is the same confusion
     * [addSelectionToGroup] expands a collapsed group to avoid.
     */
    fun removeFromGroup(groupId: String, monitorId: String) {
        viewModelScope.launch {
            val group = graph.store.currentSnapshot().groups.firstOrNull { it.id == groupId }
            graph.store.removeFromGroup(groupId, monitorId)
            graph.notifyStateChanged()
            toast = ToastMessage.success(
                group?.displayTitle
                    ?.let { "Removed 1 monitor from “$it”" }
                    ?: "Removed 1 monitor from its group",
            )
        }
    }

    /**
     * The selection in the order it is drawn, not the order it was tapped.
     *
     * A set has no order, and membership order is what the group will keep, so it
     * has to come from the list the user was looking at.
     */
    private fun selectedInOrder(): List<String> =
        visible.map { it.monitor.id }.filter { it in selection }

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
            toast = if (enabled) {
                ToastMessage.success("${ids.size} ${plural(ids.size)} resumed")
            } else {
                ToastMessage.warning("${ids.size} ${plural(ids.size)} paused")
            }
            clearSelection()
        }
    }

    fun muteSelection(hours: Int) {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { graph.engine.mute(it, hours * 60 * 60 * 1000L) }
            toast = ToastMessage.warning("${ids.size} ${plural(ids.size)} muted for ${hours}h")
            clearSelection()
        }
    }

    fun deleteSelection() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // Captured before anything is torn down, and the whole set at once, so
            // an undo puts them back in their old order and their old groups
            // rather than in a heap at the end of the dashboard.
            val undo = graph.store.captureForRestore(ids)
            ids.forEach { id ->
                graph.scheduler.cancel(id)
                graph.alerts.cancelAll(id)
                graph.engine.forgetMonitor(id)
                graph.store.delete(id)
            }
            graph.notifyStateChanged()
            toast = ToastMessage.undoable("${ids.size} ${plural(ids.size)} deleted") {
                restore(undo)
            }
            clearSelection()
        }
    }

    /**
     * Puts deleted monitors back, schedule and all.
     *
     * The schedule is the part that has to be rebuilt rather than resurrected:
     * `delete` cancelled the work request, and the restored monitor needs a new
     * one built from its own interval and the settings in force now. Reaching for
     * the cancelled request would leave a monitor that exists and never checks,
     * which looks exactly like the app being broken.
     */
    private fun restore(records: List<NightbellStore.DeletedMonitor>) {
        if (records.isEmpty()) return
        viewModelScope.launch {
            graph.store.restore(records)
            val snapshot = graph.store.currentSnapshot()
            records.forEach { record ->
                snapshot.monitors.firstOrNull { it.id == record.monitor.id }?.let {
                    graph.scheduler.schedule(it, snapshot.settings)
                }
            }
            graph.scheduler.ensureSweep(snapshot.settings)
            graph.notifyStateChanged()
            toast = ToastMessage.success(
                if (records.size == 1) "Monitor restored" else "${records.size} monitors restored",
            )
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
            // Every one of these is a warning, including the ones that worked.
            // A pause is the app saying it will not page you, which is the state
            // a monitoring app should never confirm in the same green it uses for
            // "your fleet is fine".
            toast = ToastMessage.warning(
                when {
                    minutes == null && scope == PauseScope.STOP_CHECKS -> "Paused until you resume"
                    minutes == null -> "Silenced until you resume"
                    scope == PauseScope.STOP_CHECKS -> "Paused for ${durationLabel(minutes)}"
                    else -> "Silenced for ${durationLabel(minutes)}"
                },
            )
        }
    }

    fun resume() {
        pausePrompt = null
        refreshing = true
        viewModelScope.launch {
            try {
                graph.engine.resumeAll()
                toast = ToastMessage.success("Monitoring again")
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
                // Zero is a warning and not a success: the button ran and
                // nothing happened, which is the one answer here a user would
                // want to look at twice.
                //
                // And it says which nothing. This pass is forced, so due-ness
                // cannot be why a monitor was skipped, and offline and a standing
                // fleet pause are both handled before we get here. What is left is
                // that every monitor is individually paused, which "Nothing to
                // check yet" described as a matter of timing and told the user to
                // come back later for something no amount of waiting fixes.
                toast = when (count) {
                    0 -> ToastMessage.warning("Every monitor is paused")
                    1 -> ToastMessage.success("1 monitor checked")
                    else -> ToastMessage.success("$count monitors checked")
                }
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Ids with a hand-driven check in flight.
     *
     * The card's re-check button is disabled on `card.checking`, which is the
     * store's own in-flight set and therefore only true once the engine has
     * picked the work up. Between the tap and that moment the button is still
     * live, and this is a control whose whole job is to fire a real request at
     * somebody's server. Held here rather than derived, so the guard closes on
     * the tap rather than on the round trip.
     */
    private val checkingNow = mutableSetOf<String>()

    fun check(monitorId: String) {
        if (!graph.network.isOnline()) {
            toast = OFFLINE_TOAST
            return
        }
        if (!checkingNow.add(monitorId)) return
        viewModelScope.launch {
            try {
                graph.engine.run(monitorId)
            } finally {
                checkingNow.remove(monitorId)
            }
        }
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
            toast = if (enabled) {
                ToastMessage.success("Monitor resumed")
            } else {
                ToastMessage.warning("Monitor paused")
            }
        }
    }

    fun delete(monitorId: String) {
        viewModelScope.launch {
            val undo = graph.store.captureForRestore(listOf(monitorId))
            graph.store.delete(monitorId)
            graph.scheduler.cancel(monitorId)
            // All three id spaces. Cancelling only the down one used to strand
            // the monitor's urgent notification forever: it is `ongoing`, and
            // once the monitor is gone no per-monitor loop ever visits it again.
            graph.alerts.cancelAll(monitorId)
            graph.engine.forgetMonitor(monitorId)
            toast = ToastMessage.undoable("Monitor deleted") { restore(undo) }
        }
    }

    fun mute(monitorId: String, hours: Int) {
        viewModelScope.launch {
            graph.engine.mute(monitorId, hours * 60 * 60 * 1000L)
            toast = ToastMessage.warning(mutedFor(hours))
        }
    }

    fun unmute(monitorId: String) {
        viewModelScope.launch {
            graph.engine.unmute(monitorId)
            toast = ToastMessage.success(ALERTS_BACK_ON)
        }
    }

    fun acknowledgeUrgent(monitorId: String) {
        viewModelScope.launch {
            graph.engine.acknowledgeUrgent(monitorId)
            toast = ToastMessage.warning(ACKNOWLEDGED)
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
    /**
     * A toast channel, for the one thing on this screen that is not the draft.
     *
     * Everything else the wizard does is reported by the wizard: a step advances,
     * a test result appears, a field turns red. Saving the token is the exception
     * because it writes a global setting from inside a form about one monitor, and
     * the only visible answer was the field collapsing.
     */
    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    fun consumeToast() {
        toast = null
    }

    fun setGitHubToken(value: String) {
        val cleaned = value.trim()
        settings = settings.copy(githubToken = cleaned)
        viewModelScope.launch { graph.store.updateSettings { it.copy(githubToken = cleaned) } }
        // The same words the Settings screen uses for the same write, which is a
        // different screen writing the same key.
        toast = if (cleaned.isBlank()) {
            ToastMessage.warning("Token removed")
        } else {
            ToastMessage.success("Token saved on this device")
        }
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
        /** The page the selector was derived on. Blank keeps the draft's URL. */
        pageUrl: String = "",
        /** What the preview was carrying. Empty for an ordinary page. */
        browserState: BrowserState = BrowserState(),
    ) {
        val current = draft.targets.toMutableList()
        val index = pickingIndex
        // Re-picking keeps the slot's mode, label and attribute: the user is
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
        draft = draft.withTargets(current).withPickedPage(pageUrl, browserState)
        pickerOpen = false
        pickingIndex = -1
        testResult = null
    }

    /**
     * Points the draft at the page a selector was actually taken from.
     *
     * Re-validated here even though the picker will not offer a page that fails
     * these, because this is where the draft is allowed to change and a guard
     * that only lives in a composable is a guard that a second caller walks past.
     * A page that cannot be adopted leaves the URL alone, which is the behaviour
     * every version before this one had.
     */
    private fun Monitor.withPickedPage(pageUrl: String, state: BrowserState): Monitor {
        val page = pageUrl.trim()
        val moved = page.isNotBlank() &&
            page != url &&
            Validation.urlNote(page)?.severity != Validation.Severity.ERROR &&
            ProxyRoute.previewRefusal(page, ProxyRoute.forMonitor(this, settings)) == null
        val target = if (moved) page else url
        return copy(
            url = target,
            // A capture with nothing in it does not erase a session that is still
            // good for this page: picking a second element on the same site reads
            // no new cookies and would otherwise undo the first pick's work.
            browserState = when {
                !state.isEmpty -> state
                browserState.appliesTo(target) -> browserState
                else -> BrowserState()
            },
        )
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

    /**
     * Guarded, and the guard is about the request rather than the record.
     *
     * `upsert` is keyed by id so writing twice is harmless, but the last line of
     * this is `engine.run`, which fires a real request at the user's endpoint. A
     * double tap on a button that has not visibly changed yet is the ordinary
     * human response to a slow save, and it hit the service twice.
     */
    var saving by mutableStateOf(false)
        private set

    fun save() {
        if (saving) return
        val validation = report
        if (!validation.isValid) return
        saving = true
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
            saving = false
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

    /**
     * Live settings, because a monitor that sets no latency budget of its own
     * inherits the global one and the detail screen has to draw the effective
     * figure rather than the stored zero. See `Monitor.sloMs`.
     *
     * A flow rather than a value read at open: the budget can be retyped in
     * Settings while this screen is behind it in the back stack, and coming back
     * to a chart drawing the old threshold would be a small lie that is hard to
     * spot.
     */
    val settings: StateFlow<GlobalSettings> = graph.store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    var busy by mutableStateOf(false)
        private set

    /** Greys out "check now" rather than letting it fire and answer with a toast. */
    val offline: StateFlow<Boolean> = graph.network.online
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), !graph.network.isOnline())

    var toast by mutableStateOf<ToastMessage?>(null)
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
            // The dashboard's pause has said this since it shipped and this one
            // said nothing, so the same switch answered on one screen and not on
            // the other. Pausing from here changes whether the phone will ring
            // tonight, which is not a thing to leave to a toggle's own position.
            toast = if (enabled) {
                ToastMessage.success("Monitor resumed")
            } else {
                ToastMessage.warning("Monitor paused")
            }
        }
    }

    fun mute(hours: Int) {
        viewModelScope.launch {
            graph.engine.mute(monitorId, hours * 60 * 60 * 1000L)
            toast = ToastMessage.warning(mutedFor(hours))
        }
    }

    /** Accepts a certificate that was deliberately replaced. See CheckEngine. */
    fun repinCertificate() {
        viewModelScope.launch {
            graph.engine.repinCertificate(monitorId)
            toast = ToastMessage.warning("The next successful check will record the new key")
        }
    }

    fun unmute() {
        viewModelScope.launch {
            graph.engine.unmute(monitorId)
            toast = ToastMessage.success(ALERTS_BACK_ON)
        }
    }

    /**
     * In-app acknowledgement of an urgent outage. Same path as the notification
     * action, so the two can't drift apart.
     */
    fun acknowledgeUrgent() {
        viewModelScope.launch {
            graph.engine.acknowledgeUrgent(monitorId)
            toast = ToastMessage.warning(ACKNOWLEDGED)
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
            toast = ToastMessage.success("Marked as seen")
        }
    }

    /**
     * Guarded, and the guard is about the navigation rather than the delete.
     *
     * Deleting twice is harmless in the store, but `onDone` is a back-stack pop:
     * two of them take the dashboard with them and drop the user out of the app.
     * A second tap on a button that is still working is the normal human response
     * to a screen that has not changed yet.
     */
    private var deleting = false

    fun delete(onDone: (ToastMessage) -> Unit) {
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            val undo = graph.store.captureForRestore(listOf(monitorId))
            graph.store.delete(monitorId)
            graph.scheduler.cancel(monitorId)
            graph.alerts.cancelAll(monitorId)
            graph.engine.forgetMonitor(monitorId)
            // Handed out rather than reported from here, and the undo runs on the
            // application scope rather than this one. Both for the same reason:
            // the next thing that happens is a back stack pop that takes this
            // view model with it, so a message set here would never be read and a
            // restore launched here would be cancelled halfway through.
            onDone(ToastMessage.undoable("Monitor deleted") { restoreOnAppScope(undo) })
        }
    }

    /**
     * Puts the monitor back after this screen is gone.
     *
     * No confirming toast: by the time the undo can be pressed the user is
     * looking at the dashboard, and the card reappearing in its old position is a
     * better answer than a sentence claiming it did.
     */
    private fun restoreOnAppScope(records: List<NightbellStore.DeletedMonitor>) {
        if (records.isEmpty()) return
        graph.appScope.launch {
            graph.store.restore(records)
            val snapshot = graph.store.currentSnapshot()
            records.forEach { record ->
                snapshot.monitors.firstOrNull { it.id == record.monitor.id }?.let {
                    graph.scheduler.schedule(it, snapshot.settings)
                }
            }
            graph.scheduler.ensureSweep(snapshot.settings)
            graph.notifyStateChanged()
        }
    }

    fun consumeToast() {
        toast = null
    }
}

// ------------------------------------------------------------------- settings

private const val SAMPLE_NAME = "Wireguard gateway"
private const val SAMPLE_REASON = "Host not found"
private const val SAMPLE_DOWN_FOR_MS = 4 * 60_000L

class SettingsViewModel(private val graph: Nightbell.Graph) : ViewModel() {

    val settings: StateFlow<GlobalSettings> = graph.store.snapshot
        .map { it.settings }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSettings())

    /**
     * Writes a setting that changes nothing about scheduling or alerting state.
     *
     * [update] rebuilds every monitor's work request, drops the checker-health
     * claim and pokes the foreground service, which is right for a setting that
     * changes how checks run and absurd for one keystroke in a text field. The
     * spoken sentence is read at the moment something is announced and affects
     * nothing else.
     */
    fun updateText(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch { graph.store.updateSettings(transform) }
    }

    /** For the spoken-alerts count and the fleet-wide switch. */
    val monitors: StateFlow<List<Monitor>> = graph.store.snapshot
        .map { it.monitors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    var toast by mutableStateOf<ToastMessage?>(null)
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
                val line = "Exported $count monitor" + (if (count == 1) "" else "s") +
                    if (secrets) ", token included" else ""
                // Amber when the token went with it. The export worked either
                // way, so this is not a failure, but a file that now contains a
                // credential is the one outcome here worth reading twice, and
                // green is the colour this app uses for "nothing to see".
                toast = if (secrets) ToastMessage.warning(line) else ToastMessage.success(line)
            } catch (error: Throwable) {
                if (isCancellation(error)) throw error
                Log.w(TAG, "Export failed", error)
                toast = ToastMessage.error("Couldn't write that file")
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
                    toast = ToastMessage.error(
                        (error as? BackupCodec.BackupFailure)?.error?.message
                            ?: BackupError.Unreadable.message,
                    )
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
                toast = ToastMessage.success("Imported $count monitor" + if (count == 1) "" else "s")

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
                toast = ToastMessage.error("Couldn't read that file")
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
                    result.sites == 0 ->
                        ToastMessage.warning("No website monitors to fetch icons for")
                    result.changed == 0 -> ToastMessage.success("Checked $sites, icons unchanged")
                    else -> ToastMessage.success("${result.changed} of $sites updated")
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
        toast = if (cleaned.isBlank()) {
            // Warned rather than confirmed: with no token the repository monitors
            // fall back to the unauthenticated budget, which is a real change to
            // how often they can poll.
            ToastMessage.warning("Token removed")
        } else {
            ToastMessage.success("Token saved on this device")
        }
    }

    // ---- Nightbell's own updates --------------------------------------------

    val appUpdate: StateFlow<UpdateState> = graph.store.snapshot
        .map { it.update }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateState())

    var checkingForUpdate by mutableStateOf(false)
        private set

    /** The version this build actually is, for the card's Installed line. */
    val installedVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Lifts a refusal, so a version dismissed by mistake can come back.
     *
     * New work, and not optional. Dismissal used to take two deliberate taps on a
     * notification action; it is now one tap on a banner, and a control that can
     * silence a release forever from a single mis-tap needs an undo sitting next to
     * where it says what it did.
     */
    fun unignoreUpdate() {
        viewModelScope.launch {
            graph.store.updateAppUpdate { AppUpdate.unignore(it) }
            toast = ToastMessage.success("That version will be announced again")
        }
    }

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
                    latest.isBlank() ->
                        ToastMessage.error("Couldn't reach ${settings.value.updateSource.label}")
                    AppUpdate.isNewer(latest, BuildConfig.VERSION_NAME) ->
                        ToastMessage.success("Version $latest is available")
                    else -> ToastMessage.success("You're on the newest version")
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

    // ---- spoken pages -------------------------------------------------------

    /** Null until the engine has been asked. See [PageSpeaker.Readiness]. */
    var speechReadiness by mutableStateOf<PageSpeaker.Readiness?>(null)
        private set

    /** Installed voices that work with no connection, one per language. */
    var speechVoices by mutableStateOf<List<PageSpeaker.Choice>>(emptyList())
        private set

    var speakingSample by mutableStateOf(false)
        private set

    /** The voice that would read the next alert, chosen or fallen back to. */
    var effectiveVoice by mutableStateOf<String?>(null)
        private set

    /**
     * Asks the engine what it can currently do.
     *
     * Re-asked whenever the card comes back into view rather than cached for the
     * session, because the fix for both bad answers is a trip to the system's
     * speech settings, and a user who installs a language pack has to come back
     * to a screen that agrees they did.
     */
    /**
     * Turns speech on or off for the whole fleet, in one write, undoably.
     *
     * Writes the flag into the global default *and* into every monitor carrying
     * its own alert settings. Doing only the default would leave anyone who had
     * ever customised a monitor with a button that silently skipped it, which is
     * worse than no button.
     *
     * Undoable, because the change is lossy in one direction that is easy to miss:
     * a fleet of thirty with speech on for the two that matter, flattened by one
     * tap, cannot be restored by tapping the other button. That turns every
     * monitor on rather than putting the two back. So the previous answers are
     * captured first and the toast carries the way back, which is the pattern the
     * rest of the app uses instead of asking "are you sure?".
     */
    fun setSpeakOnEveryMonitor(speak: Boolean) {
        if (bulkSpeakInFlight) return
        bulkSpeakInFlight = true
        viewModelScope.launch {
            try {
                val before = graph.store.currentSnapshot()
                val previousDefault = before.settings.defaultAlert.speak
                val previousPerMonitor = before.monitors.associate { it.id to it.alert.speak }
                graph.store.updateSettings {
                    it.copy(defaultAlert = it.defaultAlert.copy(speak = speak))
                }
                graph.store.updateAllMonitors { monitor ->
                    if (monitor.useGlobalAlerts) {
                        monitor
                    } else {
                        monitor.copy(alert = monitor.alert.copy(speak = speak))
                    }
                }
                val after = graph.store.currentSnapshot()
                val count = SpokenPage.speakingCount(after.monitors, after.settings)
                val changed = previousPerMonitor.any { (id, was) ->
                    after.monitors.firstOrNull { it.id == id }?.alert?.speak != was
                } || previousDefault != speak
                toast = when {
                    !changed -> ToastMessage.success(
                        if (speak) "Every monitor already speaks" else "No monitor was speaking",
                    )

                    !speak -> ToastMessage.undoable("No monitor will speak") {
                        restoreSpeak(previousDefault, previousPerMonitor)
                    }

                    count == 0 -> ToastMessage.undoable(
                        // Nothing to celebrate: alerts are switched off somewhere
                        // above this, so the flag is set and still nothing will be
                        // said. Saying "3 monitors will speak" there would be a lie.
                        "Speech is on, but no monitor can alert at all",
                    ) { restoreSpeak(previousDefault, previousPerMonitor) }

                    else -> ToastMessage.undoable(
                        "$count ${if (count == 1) "monitor" else "monitors"} will speak",
                    ) { restoreSpeak(previousDefault, previousPerMonitor) }
                }
                if (speak) refreshSpeech()
            } finally {
                bulkSpeakInFlight = false
            }
        }
    }

    /** True while the fleet write is running, so a second tap is not a second write. */
    var bulkSpeakInFlight by mutableStateOf(false)
        private set

    private fun restoreSpeak(default: Boolean, perMonitor: Map<String, Boolean>) {
        viewModelScope.launch {
            graph.store.updateSettings {
                it.copy(defaultAlert = it.defaultAlert.copy(speak = default))
            }
            graph.store.updateAllMonitors { monitor ->
                val was = perMonitor[monitor.id] ?: return@updateAllMonitors monitor
                if (monitor.alert.speak == was) monitor else monitor.copy(alert = monitor.alert.copy(speak = was))
            }
        }
    }

    /**
     * True while the engine is being asked what it can do.
     *
     * Surfaced because the answer is not instant: binding a cold engine has taken
     * seconds, and the probe synthesises a word with an eight second ceiling on
     * top of that. Without this the card sat there looking settled and then
     * produced "this phone produces no audio" out of nowhere, which reads as a
     * fault that just happened rather than a check that just finished.
     */
    var checkingSpeech by mutableStateOf(false)
        private set

    fun refreshSpeech() {
        if (checkingSpeech) return
        checkingSpeech = true
        viewModelScope.launch {
            try {
                // Probed, not merely asked: an engine can report an installed voice
                // and then fail every utterance, and this card is the only place that
                // can tell the user so before they rely on it.
                speechReadiness = graph.speaker.readiness(probe = true)
                speechVoices = graph.speaker.offlineVoices()
                effectiveVoice = graph.speaker
                    .effectiveVoiceTag(graph.store.currentSnapshot().settings.speakVoice)
            } finally {
                checkingSpeech = false
            }
        }
    }

    /**
     * Says the sample sentence with the user's own template.
     *
     * Uses the same usage a real page would, so what the preview sounds like is
     * what 3am sounds like. When the ringer would silence a page the preview says
     * nothing and the toast explains that, rather than leaving the user tapping a
     * button that appears to be broken.
     */
    fun previewAnnouncement() {
        if (speakingSample) return
        speakingSample = true
        viewModelScope.launch {
            try {
                val current = graph.store.currentSnapshot().settings
                val usage = graph.alarm.speechUsage(current.urgentRespectsRingerMode)
                if (usage == null) {
                    toast = ToastMessage.warning("Silent or vibrate: a page would not speak")
                    return@launch
                }
                when (graph.speaker.readiness()) {
                    PageSpeaker.Readiness.NO_ENGINE -> {
                        toast = ToastMessage.error("No speech engine on this phone")
                        return@launch
                    }

                    PageSpeaker.Readiness.NO_OFFLINE_VOICE -> {
                        toast = ToastMessage.error("No offline voice installed")
                        return@launch
                    }

                    PageSpeaker.Readiness.ENGINE_SILENT -> {
                        toast = ToastMessage.error("The speech engine produces no audio")
                        return@launch
                    }

                    PageSpeaker.Readiness.READY -> Unit
                }
                val spoken = graph.speaker.say(
                    text = SpokenPage.render(
                        template = current.speakTemplate,
                        name = SAMPLE_NAME,
                        reason = SAMPLE_REASON,
                        downForMs = SAMPLE_DOWN_FOR_MS,
                    ),
                    usage = usage,
                    voice = current.speakVoice,
                )
                if (!spoken) toast = ToastMessage.error("The engine would not say it")
            } finally {
                speakingSample = false
                refreshSpeech()
            }
        }
    }

    /** True while a test alert is on its way, so a second tap is not a second page. */
    var sendingTestAlert by mutableStateOf(false)
        private set

    fun sendTestAlert() {
        if (sendingTestAlert) return
        sendingTestAlert = true
        viewModelScope.launch {
            try {
                val policy = graph.store.currentSnapshot().settings.defaultAlert
                if (!graph.alerts.hasNotificationPermission()) {
                    toast = ToastMessage.error("Notifications are blocked, enable them in system settings")
                    return@launch
                }
                graph.alerts.previewPolicy(policy)
                toast = ToastMessage.success("Test alert sent")
            } finally {
                sendingTestAlert = false
            }
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
