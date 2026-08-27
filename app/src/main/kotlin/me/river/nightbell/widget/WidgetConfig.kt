package me.river.nightbell.widget

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

/**
 * Widget palettes: the three surfaces the app itself uses, plus [CUSTOM].
 *
 * [CUSTOM] is a fourth *preset slot* rather than a flag, so the choice is a
 * single value the whole config can be reasoned about from — and so switching
 * back to a preset keeps the custom colours around to switch forward to again.
 */
@Serializable
enum class WidgetTheme {
    BLACK,
    WHITE,
    BLUE,
    CUSTOM,
    ;

    val label: String
        get() = when (this) {
            BLACK -> "Black"
            WHITE -> "White"
            BLUE -> "Blue"
            CUSTOM -> "Custom"
        }
}

/**
 * Every colour the widget draws, already resolved to plain ARGB ints.
 *
 * [RemoteViews][android.widget.RemoteViews] cannot compute anything, so all the
 * arithmetic — preset lookup, opacity, the derived secondary/tertiary text
 * shades — happens here, once, and is unit-testable without Android.
 */
data class WidgetPalette(
    /** The rounded surface, alpha included. Fully transparent is legal. */
    val background: Int,
    /** Hairline edge. Fades out with the background so a glass widget has no ring. */
    val border: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    /**
     * The star on a repository row.
     *
     * Gold, but not the same gold on every surface: #FFC53D on the white preset is
     * a pale smudge, so a light widget gets the dark gold the app's light theme
     * uses. Same decision as `NightbellColors.Gold`, made again here because
     * RemoteViews cannot read a Compose palette.
     */
    val star: Int,
)

/** Replaces a colour's alpha channel. Input may be RGB or ARGB; alpha is 0f..1f. */
internal fun Int.withAlpha(alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (this and 0x00FFFFFF)
}

/**
 * Relative luminance, 0f (black) to 1f (white).
 *
 * Used to pick a readable default text colour for a custom background: someone
 * who sets a pale background and never touches the text colour must not end up
 * with white-on-white. Rec. 709 coefficients, close enough for this and far
 * cheaper than a full contrast calculation.
 */
internal fun Int.luminance(): Float {
    val r = (this shr 16 and 0xFF) / 255f
    val g = (this shr 8 and 0xFF) / 255f
    val b = (this and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
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

    /**
     * The three header pieces, independently switchable.
     *
     * They used to be one flag. [showTitle] hid the mark, the word "Nightbell" and the
     * "all 6 operational" line together, which meant the only way to drop the
     * headline was to lose the branding with it and the only way to keep the
     * branding was to accept a line of prose on a widget the size of a stamp.
     * Anyone tuning a widget down to something clean wants to make those calls
     * separately.
     *
     * [WidgetConfigStore.decode] maps a pre-split config onto all three, so a
     * widget already on someone's home screen looks the same after the update.
     */
    val showLogo: Boolean = true,
    val showTitle: Boolean = true,
    val showHeadline: Boolean = true,

    val showTimestamp: Boolean = true,
    /** Hide healthy monitors so the widget only speaks up when it matters. */
    val onlyProblems: Boolean = false,
    val maxRows: Int = 5,

    /**
     * Monitor columns: `0` sizes itself, `1`–[WidgetLayout.MAX_COLUMNS] forces it.
     *
     * Automatic is the default because a widget's height is whatever the user
     * dragged it to, and the interesting case — squashing it flat until only two
     * rows fit — should spill monitors sideways rather than hide them. The manual
     * override exists because "always two columns" is a look, and a layout that
     * silently rearranges itself as you resize is not one you can commit to.
     */
    val columns: Int = 0,

    // ---- custom colours (theme == CUSTOM) -----------------------------------
    /**
     * Background hue, **RGB only**. Alpha is [backgroundOpacity]'s job, so the two
     * controls stay independent: dragging opacity to zero and back must not lose
     * the colour, and picking a colour must not silently reset transparency.
     */
    val customBackgroundRgb: Int = 0x0B0B0B,
    val customTextRgb: Int = 0xFFFFFF,
    /** 0f is genuinely fully transparent — the widget becomes text on wallpaper. */
    val backgroundOpacity: Float = 0.94f,

    /**
     * The in-widget gear.
     *
     * On by default, because the launcher's own "reconfigure" affordance is
     * hidden behind a long-press on API 31+ and simply does not exist below it —
     * a widget's settings were effectively unreachable once placed. Anyone who
     * wants the cleaner look can switch it off and still long-press.
     */
    val showSettingsButton: Boolean = true,
) {
    /**
     * Every colour resolved, presets and custom alike.
     *
     * Presets ignore [backgroundOpacity] on purpose: they are defined surfaces
     * with a known contrast, and letting opacity apply to them would quietly turn
     * a legible preset into an illegible one. Transparency is a custom-theme
     * decision, made deliberately.
     */
    val palette: WidgetPalette
        get() = when (theme) {
            WidgetTheme.BLACK -> WidgetPalette(
                background = 0xF00B0B0B.toInt(),
                border = 0x26FFFFFF,
                primary = 0xFFFFFFFF.toInt(),
                secondary = 0xFFD6D6D6.toInt(),
                tertiary = 0xFF8A8A8A.toInt(),
                star = STAR_ON_DARK,
            )

            WidgetTheme.WHITE -> WidgetPalette(
                background = 0xF5F6F6F8.toInt(),
                border = 0x22000000,
                primary = 0xFF0A0A0A.toInt(),
                secondary = 0xFF3A3A3A.toInt(),
                tertiary = 0xFF6B6B6B.toInt(),
                star = STAR_ON_LIGHT,
            )

            WidgetTheme.BLUE -> WidgetPalette(
                background = 0xF00C1A3A.toInt(),
                border = 0x402F6BFF,
                primary = 0xFFFFFFFF.toInt(),
                secondary = 0xFFBBD0FF.toInt(),
                tertiary = 0xFF7E9BD6.toInt(),
                star = STAR_ON_DARK,
            )

            WidgetTheme.CUSTOM -> {
                val text = customTextRgb and 0x00FFFFFF
                WidgetPalette(
                    background = customBackgroundRgb.withAlpha(backgroundOpacity),
                    // The edge follows the surface. A fully transparent widget with
                    // a visible ring around it looks like a rendering bug.
                    border = text.withAlpha(backgroundOpacity * BORDER_ALPHA),
                    primary = text.withAlpha(1f),
                    secondary = text.withAlpha(SECONDARY_ALPHA),
                    tertiary = text.withAlpha(TERTIARY_ALPHA),
                    // Judged by the surface it sits on, not by the text colour: the
                    // star is gold either way, and only the background decides
                    // which gold can be seen.
                    star = if (customBackgroundRgb.luminance() > 0.55f) STAR_ON_LIGHT else STAR_ON_DARK,
                )
            }
        }

    companion object {
        /** `NightbellColors.Gold`, dark and light, as opaque ARGB. */
        internal const val STAR_ON_DARK = 0xFFFFC53D.toInt()
        internal const val STAR_ON_LIGHT = 0xFF7A5600.toInt()

        private const val BORDER_ALPHA = 0.20f
        private const val SECONDARY_ALPHA = 0.80f
        private const val TERTIARY_ALPHA = 0.55f

        /** Background swatches offered in the picker. Kept short and opinionated. */
        val BACKGROUND_SWATCHES = listOf(
            0x000000, 0x0B0B0B, 0x1C1C1E, 0x3A3A3C,
            0xF6F6F8, 0xFFFFFF, 0x0C1A3A, 0x102A43,
            0x1B3A2A, 0x3A1B1B, 0x2E1B3A, 0x3A2E1B,
        )

        /** Text swatches. Includes the brand accents so a widget can be themed. */
        val TEXT_SWATCHES = listOf(
            0xFFFFFF, 0xD6D6D6, 0x8A8A8A, 0x0A0A0A,
            0x2FD98A, 0xFFB020, 0xFF4D57, 0x2F6BFF,
            0x6AA8FF, 0xFF7A59, 0xBBD0FF, 0x7E9BD6,
        )

        /**
         * The text colour a background suggests, for the moment a user picks a
         * pale surface without touching the text: white-on-white is not a look
         * anybody chose.
         */
        fun suggestedTextRgb(backgroundRgb: Int): Int =
            if (backgroundRgb.luminance() > 0.55f) 0x0A0A0A else 0xFFFFFF
    }
}

private val Context.widgetDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "nightbell_widgets")

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

    /**
     * Whether this widget has ever been configured.
     *
     * Distinguishes "the launcher just dropped a new widget" from "the user came
     * back to change one", which is the difference between an *Add widget* button
     * and a *Save* button — and between Cancel meaning "don't place it" and Cancel
     * meaning "leave it as it was".
     */
    suspend fun exists(context: Context, appWidgetId: Int): Boolean = runCatching {
        context.widgetDataStore.data.first()[key(appWidgetId)] != null
    }.getOrDefault(false)

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

    /**
     * Reads a stored config, migrating the header flag that used to be one value.
     *
     * `showTitle` once covered the mark, the wordmark and the fleet headline. Those are
     * three fields now, and all three default to true — so a widget saved with
     * `showTitle: false` would decode as *header showing*, quietly turning the header
     * back on for everyone who had switched it off. Absence of the new keys is the
     * signal that this document predates the split, so the old flag is copied across.
     *
     * Checked against the raw text rather than by comparing to a default, because
     * `showLogo: true` is both the default and a perfectly ordinary saved value —
     * there is no way to tell those apart after decoding.
     */
    // internal so the migration can be tested without a Context: the interesting cases are
    // all about what an older build wrote, which is a string, not a device.
    internal fun decode(raw: String?): WidgetConfig {
        if (raw.isNullOrBlank()) return WidgetConfig()
        val config = runCatching { json.decodeFromString<WidgetConfig>(raw) }
            .getOrDefault(WidgetConfig())
        if (raw.contains("\"showLogo\"")) return config
        return config.copy(showLogo = config.showTitle, showHeadline = config.showTitle)
    }

    private const val TAG = "WidgetConfigStore"
}
