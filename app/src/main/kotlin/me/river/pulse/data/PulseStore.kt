package me.river.pulse.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorCard
import me.river.pulse.domain.MonitorRuntime
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
                if (mid == id && !enabled) rt.copy(health = Health.PAUSED, alerting = false) else rt
            },
        )
    }

    suspend fun updateRuntime(id: String, transform: (MonitorRuntime) -> MonitorRuntime) = mutate { snap ->
        val current = snap.runtimes[id] ?: MonitorRuntime()
        snap.copy(runtimes = snap.runtimes + (id to transform(current)))
    }

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
     * defaults handles almost all of it for free. The one real migration is
     * multi-element monitors: 1.0.0 wrote a single `element`, and this lifts it
     * into `elements` so checkers and screens only ever read the list.
     *
     * Idempotent, and it never *drops* `element` — a store written here still
     * decodes on 1.0.0, so a downgrade doesn't lose the user's monitors.
     */
    private fun migrate(snapshot: PulseSnapshot): PulseSnapshot {
        val monitors = snapshot.monitors.map { it.migrated }
        return if (monitors == snapshot.monitors) {
            snapshot
        } else {
            snapshot.copy(schema = PulseSnapshot.SCHEMA_VERSION, monitors = monitors)
        }
    }

    companion object {
        private const val TAG = "PulseStore"
    }
}
