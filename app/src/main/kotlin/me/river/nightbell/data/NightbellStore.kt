package me.river.nightbell.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import me.river.nightbell.domain.CheckerHealth
import me.river.nightbell.domain.CheckerStreak
import me.river.nightbell.domain.ConnectivityReference
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.UrgentAlerts
import me.river.nightbell.domain.LegacyCrashRepair
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PauseState
import me.river.nightbell.domain.ReferenceSample
import me.river.nightbell.domain.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NightbellSnapshot(
    val schema: Int = SCHEMA_VERSION,
    val monitors: List<Monitor> = emptyList(),
    val runtimes: Map<String, MonitorRuntime> = emptyMap(),
    val settings: GlobalSettings = GlobalSettings(),
    /**
     * Rolling timings of the latency reference, newest last.
     *
     * Persisted rather than held in memory because the WorkManager path can run
     * each check pass in a fresh process — an in-memory window would never reach
     * the minimum size and the compensation would silently never engage.
     */
    val reference: List<ReferenceSample> = emptyList(),
    /**
     * The checker's own error streak, carried across processes.
     *
     * The *evidence* only — never the claim or its notification. See
     * [CheckerStreak] for why this cannot live in memory alone.
     */
    val checkerStreak: CheckerStreak = CheckerStreak(),
    /**
     * The standing "leave me alone" instruction, if there is one.
     *
     * Top level rather than inside [GlobalSettings] because it is state with an
     * expiry, not a preference: the preference is
     * [GlobalSettings.pauseChoice], which says what the button does, while this
     * says what it did.
     */
    val pause: PauseState = PauseState(),
    /**
     * What Nightbell knows about newer versions of itself, and what the user has
     * already answered about them.
     *
     * Top level rather than inside [GlobalSettings] for the same reason [pause]
     * is: the preference is [GlobalSettings.updateChecksEnabled], and this is
     * state with an expiry ("remind me tomorrow", "never this version").
     */
    val update: UpdateState = UpdateState(),
    /**
     * Monotonic write counter, bumped by every [NightbellStore.mutate].
     *
     * A total order on committed state, which is what lets the in-memory snapshot
     * be updated from two directions — the write that just happened, and the
     * DataStore flow catching up — without either being able to overwrite
     * something newer. See [NightbellStore.publish].
     *
     * Persisted because the ordering has to survive a process death: an in-memory
     * counter would restart at zero and the first write of a new process would
     * look older than the state it is replacing.
     */
    val revision: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

private val Context.nightbellDataStore: DataStore<Preferences> by preferencesDataStore(name = "nightbell_store")

/**
 * Single source of truth for monitors, their rolling runtime state, and global
 * settings. Backed by Preferences DataStore holding one JSON document, which
 * keeps writes atomic and the whole store trivially serialisable/importable.
 */
class NightbellStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val key = stringPreferencesKey("snapshot_v1")

    /** Set while a check is in flight so the UI can show its live shimmer. */
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    private val live = MutableStateFlow(NightbellSnapshot())
    private val loadedFlag = MutableStateFlow(false)

    /**
     * Current state, readable synchronously.
     *
     * **Updated by the write itself**, before [mutate] returns — not only when the
     * DataStore flow gets around to emitting. That distinction is the whole point
     * of this being a hand-rolled [MutableStateFlow] rather than a `stateIn` over
     * `dataStore.data`.
     *
     * With `stateIn`, a caller that wrote and then read `.value` observed the state
     * from *before* its own write, because the emission is delivered on this
     * store's collector coroutine and nothing orders that against the writer
     * continuing. Every surface outside Compose reads `.value` — the home-screen
     * widget most visibly — so the widget rendered the previous verdict of
     * whichever monitor had just been checked: a monitor that recovered was drawn
     * DOWN, and stayed DOWN until the next check, while the app showed it up.
     *
     * [loaded] says whether the first read has landed; until it has, this is
     * [NightbellSnapshot] defaults and means "not known yet" rather than "empty".
     */
    val snapshot: StateFlow<NightbellSnapshot> = live.asStateFlow()

    /**
     * Whether the first read from disk has completed.
     *
     * Readers that would otherwise present "not loaded" as "nothing here" have to
     * check this. A widget rendered from a cold process — the launcher's 30-minute
     * `updatePeriodMillis` is enough to cause one — would otherwise paint "No
     * monitors yet" over a working fleet.
     */
    val loaded: Boolean get() = loadedFlag.value

    init {
        // The only reader of `dataStore.data`. Everything else in the app goes
        // through `live`, so a decode of the JSON document happens once per commit
        // rather than once per interested party.
        scope.launch {
            context.nightbellDataStore.data
                .catch { error ->
                    Log.e(TAG, "DataStore read failed, falling back to empty store", error)
                    emit(androidx.datastore.preferences.core.emptyPreferences())
                }
                .collect { prefs ->
                    publish(decode(prefs[key]))
                    loadedFlag.value = true
                }
        }
    }

    /**
     * Adopts [next] unless what we already hold is newer.
     *
     * Both callers race by design: a write publishes the value it just committed,
     * and the DataStore collector publishes every commit a beat later. Revisions
     * are a total order on committed state, so "arrived second but older" is
     * decidable and simply loses. The loop is a CAS retry because the two can
     * genuinely land on different threads.
     *
     * Also protects against the `catch` above: a transient read failure emits
     * empty preferences, which would otherwise blow away live state that is
     * perfectly good.
     */
    private fun publish(next: NightbellSnapshot) {
        while (true) {
            val current = live.value
            // Not gated on `loaded`: the first emission from disk can itself arrive
            // after a write this process already made, and it is the older of the
            // two. Equal revisions adopt, which is what makes a legacy store (no
            // counter, so revision 0) and the initial default (also 0) resolve to
            // the disk copy rather than sticking on empty.
            if (next.revision < current.revision) return
            if (live.compareAndSet(current, next)) return
        }
    }

    val cards: Flow<List<MonitorCard>> = combine(snapshot, inFlight) { snap, busy ->
        snap.monitors.map { monitor ->
            MonitorCard(
                monitor = monitor,
                runtime = snap.runtimes[monitor.id]
                    ?: MonitorRuntime(health = if (monitor.enabled) Health.UNKNOWN else Health.PAUSED),
                checking = monitor.id in busy,
            )
        }
    }

    val settings: Flow<GlobalSettings> = snapshot.map { it.settings }

    suspend fun awaitLoaded(): NightbellSnapshot = context.nightbellDataStore.data
        .map { decode(it[key]) }
        .first()

    fun monitorFlow(id: String): Flow<MonitorCard?> = cards.map { list -> list.firstOrNull { it.monitor.id == id } }

    suspend fun currentSnapshot(): NightbellSnapshot = awaitLoaded()

    suspend fun upsert(monitor: Monitor) = mutate { snap ->
        val normalised = monitor.migrated
        val existing = snap.monitors.indexOfFirst { it.id == normalised.id }
        val monitors = if (existing >= 0) {
            snap.monitors.toMutableList().also { it[existing] = normalised }
        } else {
            snap.monitors + normalised
        }
        val runtimes = if (snap.runtimes.containsKey(monitor.id)) {
            snap.runtimes
        } else {
            snap.runtimes + (monitor.id to MonitorRuntime())
        }
        snap.copy(monitors = monitors, runtimes = runtimes)
    }

    /**
     * Rewrite the monitor order to match [orderedIds].
     *
     * Ids the caller did not mention keep their relative order and land at the end,
     * so a drag that raced a monitor being created or deleted elsewhere cannot lose
     * one. Unknown ids are ignored for the same reason.
     */
    suspend fun reorder(orderedIds: List<String>) = mutate { snap ->
        val byId = snap.monitors.associateBy { it.id }
        val ranked = orderedIds.mapNotNull(byId::get)
        val rest = snap.monitors.filter { it.id !in orderedIds.toSet() }
        val monitors = ranked + rest
        if (monitors.map { it.id } == snap.monitors.map { it.id }) snap
        else snap.copy(monitors = monitors)
    }

    suspend fun delete(id: String) = mutate { snap ->
        snap.copy(
            monitors = snap.monitors.filterNot { it.id == id },
            runtimes = snap.runtimes - id,
        )
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { snap ->
        snap.copy(
            monitors = snap.monitors.map { if (it.id == id) it.copy(enabled = enabled) else it },
            runtimes = snap.runtimes.mapValues { (mid, rt) ->
                when {
                    mid != id -> rt
                    // Pausing ends the outage as far as paging is concerned.
                    // Clearing `health` alone was not enough: the urgent state
                    // stayed active, so the service kept itself alive and kept
                    // paging about a monitor the user had just switched off, and
                    // the page is un-dismissable by design. Same class of bug as
                    // the 1.1.2 orphan, reached by a different door.
                    !enabled -> rt.copy(health = Health.PAUSED, alerting = false)
                        .withUrgentState(UrgentAlerts.State.Idle)
                    // Resuming clears the due-clock, so the monitor is checked at
                    // once rather than waiting out an interval measured from before
                    // it was paused — and so a monitor paused for a day is not
                    // reported as "Android is delaying checks" the moment it
                    // comes back.
                    else -> rt.copy(lastCheckedAt = 0L)
                }
            },
        )
    }

    suspend fun updateRuntime(id: String, transform: (MonitorRuntime) -> MonitorRuntime) = mutate { snap ->
        val current = snap.runtimes[id] ?: MonitorRuntime()
        snap.copy(runtimes = snap.runtimes + (id to transform(current)))
    }

    /**
     * One transform across every runtime, in a single atomic write.
     *
     * Exists for fleet-wide repairs — see
     * `NightbellApplication.repairNotificationsIfNeeded`, which has to reset alert
     * bookkeeping for every monitor at once after wiping the notification shade.
     * Doing that as N `updateRuntime` calls would be N DataStore writes with a
     * check able to land between any two of them.
     */
    suspend fun updateAllRuntimes(transform: (MonitorRuntime) -> MonitorRuntime) = mutate { snap ->
        snap.copy(runtimes = snap.runtimes.mapValues { (_, runtime) -> transform(runtime) })
    }

    suspend fun updateReference(transform: (List<ReferenceSample>) -> List<ReferenceSample>) =
        mutate { snap -> snap.copy(reference = transform(snap.reference)) }

    suspend fun updateCheckerStreak(transform: (CheckerStreak) -> CheckerStreak) =
        mutate { snap -> snap.copy(checkerStreak = transform(snap.checkerStreak)) }

    suspend fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) = mutate { snap ->
        snap.copy(settings = transform(snap.settings))
    }

    suspend fun setPause(state: PauseState) = mutate { snap -> snap.copy(pause = state) }

    suspend fun updateAppUpdate(transform: (UpdateState) -> UpdateState) =
        mutate { snap -> snap.copy(update = transform(snap.update)) }

    suspend fun replaceAll(snapshot: NightbellSnapshot) = mutate { snapshot }

    fun markChecking(id: String, checking: Boolean) {
        inFlight.value = if (checking) inFlight.value + id else inFlight.value - id
    }

    private suspend fun mutate(transform: (NightbellSnapshot) -> NightbellSnapshot) {
        var written: NightbellSnapshot? = null
        context.nightbellDataStore.edit { prefs ->
            val current = decode(prefs[key])
            // The revision is stamped here rather than by the transform, so it is
            // assigned under DataStore's write lock and cannot be skipped. It also
            // means an imported backup (`replaceAll`) is renumbered onto the end of
            // this store's history instead of carrying whatever counter the file was
            // written with, which could be lower than what is on disk.
            val next = transform(current).copy(revision = current.revision + 1)
            written = next
            prefs[key] = json.encodeToString(next)
        }
        // Before returning, so a caller that writes and then reads `snapshot.value`
        // sees its own write. `edit` has already committed at this point.
        written?.let(::publish)
    }

    private fun decode(raw: String?): NightbellSnapshot {
        if (raw.isNullOrBlank()) return NightbellSnapshot()
        return runCatching { json.decodeFromString<NightbellSnapshot>(raw) }
            .onFailure { Log.e(TAG, "Corrupt snapshot, resetting", it) }
            .getOrDefault(NightbellSnapshot())
            .let(::migrate)
    }

    /**
     * Forward-migrates a decoded snapshot.
     *
     * Everything added since 1.0.0 has a default, so `ignoreUnknownKeys` plus
     * defaults handles almost all of it for free. Two real migrations:
     *
     *  - **multi-element monitors** — 1.0.0 wrote a single `element`, and this
     *    lifts it into `elements` so checkers and screens only ever read the
     *    list. It never *drops* `element`, so a store written here still decodes
     *    on 1.0.0 and a downgrade doesn't lose the user's monitors.
     *  - **fake crash state** — see [scrubFakeCrashState].
     *
     * Idempotent.
     */
    private fun migrate(snapshot: NightbellSnapshot): NightbellSnapshot {
        val monitors = snapshot.monitors.map { it.migrated }
        val runtimes = scrubFakeCrashState(snapshot.runtimes)
        val settings = retireGoogleReference(snapshot.settings)
        return if (monitors == snapshot.monitors &&
            runtimes == snapshot.runtimes &&
            settings == snapshot.settings
        ) {
            snapshot
        } else {
            snapshot.copy(
                schema = NightbellSnapshot.SCHEMA_VERSION,
                monitors = monitors,
                runtimes = runtimes,
                settings = settings,
            )
        }
    }

    /**
     * Moves an install that never chose a latency reference off Google's.
     *
     * Changing the default alone would have fixed this for new installs and for
     * nobody else: a stored value equal to the old default is on disk because a
     * default was written there, not because anyone picked it. Anything the user
     * actually typed is left alone, gstatic included, because the field is theirs.
     *
     * Applied on read, like [scrubFakeCrashState], so it is in force from the
     * first check of the new build rather than after a startup task that a
     * background worker could beat to the punch.
     */
    private fun retireGoogleReference(settings: GlobalSettings): GlobalSettings {
        val migrated = ConnectivityReference.migrate(settings.latencyReferenceUrl)
        if (migrated == settings.latencyReferenceUrl) return settings
        Log.i(TAG, "Moving the latency reference off the Google endpoint it defaulted to")
        return settings.copy(latencyReferenceUrl = migrated)
    }

    /**
     * Erases the "Checker crashed" verdicts 1.5.0 and earlier persisted — see
     * [LegacyCrashRepair] for what is on disk and why it all has to go.
     *
     * Applied on **read** so it is in force from the first moment the new build
     * runs, with no write to schedule and no race against a worker that starts
     * before a startup repair would have finished.
     */
    private fun scrubFakeCrashState(
        runtimes: Map<String, MonitorRuntime>,
    ): Map<String, MonitorRuntime> {
        if (!LegacyCrashRepair.needsRepair(runtimes)) return runtimes
        Log.i(TAG, "Scrubbing fabricated \"${CheckerHealth.LEGACY_CRASH_MESSAGE}\" state")
        return LegacyCrashRepair.scrub(runtimes)
    }

    companion object {
        private const val TAG = "NightbellStore"
    }
}
