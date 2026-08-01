package me.river.pulse.widget

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Widget palettes. Deliberately the same three surfaces the app itself uses. */
@Serializable
enum class WidgetTheme {
    BLACK,
    WHITE,
    BLUE,
    ;

    val label: String
        get() = when (this) {
            BLACK -> "Black"
            WHITE -> "White"
            BLUE -> "Blue"
        }
}

@Serializable
enum class WidgetDensity {
    /** One line per monitor: dot, name, status. */
    COMPACT,

    /** Adds host, latency and the failure message. */
    DETAILED,
    ;

    val label: String get() = if (this == COMPACT) "Compact" else "Detailed"
}

/**
 * Per-instance widget configuration.
 *
 * Every field has a default, and unknown keys are ignored on read, so a widget
 * placed by an older build keeps working after an update — and one placed by a
 * newer build degrades gracefully if the app is ever rolled back.
 */
@Serializable
data class WidgetConfig(
    val theme: WidgetTheme = WidgetTheme.BLACK,
    val density: WidgetDensity = WidgetDensity.COMPACT,
    val showTitle: Boolean = true,
    val showTimestamp: Boolean = true,
    /** Hide healthy monitors so the widget only speaks up when it matters. */
    val onlyProblems: Boolean = false,
    val maxRows: Int = 5,
)

private val Context.widgetDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulse_widgets")

/**
 * Widget configs live in their own DataStore rather than inside the monitor
 * snapshot: they are keyed by an `appWidgetId` the launcher owns, they change
 * on a completely different schedule, and a corrupt widget preference must
 * never be able to take the monitor list down with it.
 */
object WidgetConfigStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(appWidgetId: Int) = stringPreferencesKey("widget.$appWidgetId")

    suspend fun load(context: Context, appWidgetId: Int): WidgetConfig {
        val raw = runCatching { context.widgetDataStore.data.first()[key(appWidgetId)] }.getOrNull()
        return decode(raw)
    }

    /** Blocking read for [android.appwidget.AppWidgetProvider] callbacks. */
    fun loadBlocking(context: Context, appWidgetId: Int): WidgetConfig =
        runCatching { kotlinx.coroutines.runBlocking { load(context, appWidgetId) } }
            .getOrDefault(WidgetConfig())

    suspend fun save(context: Context, appWidgetId: Int, config: WidgetConfig) {
        runCatching {
            context.widgetDataStore.edit { it[key(appWidgetId)] = json.encodeToString(config) }
        }.onFailure { Log.e(TAG, "Could not save widget $appWidgetId", it) }
    }

    suspend fun delete(context: Context, appWidgetIds: IntArray) {
        runCatching {
            context.widgetDataStore.edit { prefs -> appWidgetIds.forEach { prefs.remove(key(it)) } }
        }.onFailure { Log.e(TAG, "Could not clean up widget configs", it) }
    }

    /**
     * The launcher may hand out new ids after a restore. Copies each old config
     * onto its new id so a restored home screen keeps its look.
     */
    suspend fun remap(context: Context, oldIds: IntArray, newIds: IntArray) {
        runCatching {
            val configs = oldIds.map { load(context, it) }
            context.widgetDataStore.edit { prefs ->
                oldIds.forEach { prefs.remove(key(it)) }
                newIds.forEachIndexed { index, id ->
                    val config = configs.getOrNull(index) ?: WidgetConfig()
                    prefs[key(id)] = json.encodeToString(config)
                }
            }
        }.onFailure { Log.e(TAG, "Could not remap widget configs", it) }
    }

    private fun decode(raw: String?): WidgetConfig {
        if (raw.isNullOrBlank()) return WidgetConfig()
        return runCatching { json.decodeFromString<WidgetConfig>(raw) }
            .getOrDefault(WidgetConfig())
    }

    private const val TAG = "WidgetConfigStore"
}
