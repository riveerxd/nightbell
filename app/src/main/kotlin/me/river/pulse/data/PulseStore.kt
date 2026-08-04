package me.river.pulse.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import me.river.pulse.domain.CheckerHealth
import me.river.pulse.domain.CheckerStreak
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.UrgentAlerts
import me.river.pulse.domain.LegacyCrashRepair
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.ReferenceSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PulseSnapshot(
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
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

private val Context.pulseDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_store")

/**
 * Single source of truth for monitors, their rolling runtime state, and global
 * settings. Backed by Preferences DataStore holding one JSON document, which
 * keeps writes atomic and the whole store trivially serialisable/importable.
 */
class PulseStore(
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

    val snapshot: StateFlow<PulseSnapshot> = context.pulseDataStore.data
        .catch { error ->
            Log.e(TAG, "DataStore read failed, falling back to empty store", error)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs -> decode(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, PulseSnapshot())

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

    suspend fun awaitLoaded(): PulseSnapshot = context.pulseDataStore.data
        .map { decode(it[key]) }
        .first()

    fun monitorFlow(id: String): Flow<MonitorCard?> = cards.map { list -> list.firstOrNull { it.monitor.id == id } }

    suspend fun currentSnapshot(): PulseSnapshot = awaitLoaded()

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
     * `PulseApplication.repairNotificationsIfNeeded`, which has to reset alert
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

    suspend fun replaceAll(snapshot: PulseSnapshot) = mutate { snapshot }

    fun markChecking(id: String, checking: Boolean) {
        inFlight.value = if (checking) inFlight.value + id else inFlight.value - id
    }

    private suspend fun mutate(transform: (PulseSnapshot) -> PulseSnapshot) {
        context.pulseDataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = transform(current)
            prefs[key] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): PulseSnapshot {
        if (raw.isNullOrBlank()) return PulseSnapshot()
        return runCatching { json.decodeFromString<PulseSnapshot>(raw) }
            .onFailure { Log.e(TAG, "Corrupt snapshot, resetting", it) }
            .getOrDefault(PulseSnapshot())
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
    private fun migrate(snapshot: PulseSnapshot): PulseSnapshot {
        val monitors = snapshot.monitors.map { it.migrated }
        val runtimes = scrubFakeCrashState(snapshot.runtimes)
        return if (monitors == snapshot.monitors && runtimes == snapshot.runtimes) {
            snapshot
        } else {
            snapshot.copy(
                schema = PulseSnapshot.SCHEMA_VERSION,
                monitors = monitors,
                runtimes = runtimes,
            )
        }
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
        private const val TAG = "PulseStore"
    }
}
