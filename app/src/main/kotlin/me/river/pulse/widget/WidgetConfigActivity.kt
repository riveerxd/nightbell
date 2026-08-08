package me.river.pulse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import me.river.pulse.data.Nightbell
import me.river.pulse.domain.Health
import me.river.pulse.domain.Summary
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.ChipSelector
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.NightbellButton
import me.river.pulse.ui.components.NightbellMark
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.icons.NightbellIcons
import me.river.pulse.ui.theme.NightbellColors
import me.river.pulse.ui.theme.NightbellTheme
import me.river.pulse.ui.theme.healthColor
import kotlinx.coroutines.launch

/**
 * Per-instance widget configuration, launched by the launcher when a widget is
 * dropped and re-openable from the app.
 *
 * Every option previews live against real monitor data, because "compact vs
 * detailed" and "black vs white" are impossible to choose from a label.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Nightbell.install(applicationContext)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelled unless the user commits: the launcher removes the widget if
        // a config activity finishes without RESULT_OK.
        setResult(Activity.RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            var config by remember { mutableStateOf(WidgetConfig()) }
            var loaded by remember { mutableStateOf(false) }
            var reconfiguring by remember { mutableStateOf(false) }
            val snapshot by Nightbell.store.snapshot.collectAsState()

            LaunchedEffect(Unit) {
                reconfiguring = WidgetConfigStore.exists(applicationContext, appWidgetId)
                config = WidgetConfigStore.load(applicationContext, appWidgetId)
                loaded = true
            }

            NightbellTheme(
                motionIntensity = snapshot.settings.motionIntensity,
                theme = snapshot.settings.theme,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(NightbellColors.Void),
                ) {
                    if (loaded) {
                        ConfigBody(
                            config = config,
                            fleet = Summary.of(snapshot.monitors, snapshot.runtimes),
                            reconfiguring = reconfiguring,
                            onChange = { config = it },
                            onCancel = { finish() },
                            onSave = { commit(config) },
                        )
                    }
                }
            }
        }
    }

    private fun commit(config: WidgetConfig) {
        lifecycleScope.launch {
            WidgetConfigStore.save(applicationContext, appWidgetId, config)
            NightbellWidgetProvider.refresh(applicationContext)
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun ConfigBody(
    config: WidgetConfig,
    fleet: Summary.Fleet,
    reconfiguring: Boolean,
    onChange: (WidgetConfig) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).testTag("widget-config-list"),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = top + 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "title") {
                Text(
                    text = "Widget",
                    style = MaterialTheme.typography.displayMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = if (reconfiguring) {
                        "Changes apply as soon as you save."
                    } else {
                        "Monitors are always listed worst first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NightbellColors.TextSecondary,
                )
            }

            item(key = "preview") {
                GlassCard {
                    SectionHeader("Preview", icon = NightbellIcons.Eye, accent = NightbellColors.Aqua)
                    WidgetPreview(config, fleet)
                }
            }

            item(key = "look") {
                GlassCard {
                    SectionHeader("Look", icon = NightbellIcons.Sparkle, accent = NightbellColors.Aqua)
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelMedium,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ChipSelector(
                        options = WidgetTheme.entries.toList(),
                        selected = config.theme,
                        onSelect = { onChange(config.copy(theme = it)) },
                        label = { it.label },
                        accent = NightbellColors.Aqua,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Density",
                        style = MaterialTheme.typography.labelMedium,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ChipSelector(
                        options = WidgetDensity.entries.toList(),
                        selected = config.density,
                        onSelect = { onChange(config.copy(density = it)) },
                        label = { it.label },
                        accent = NightbellColors.Aqua,
                    )
                }
            }

            item(key = "colours") {
                GlassCard {
                    SectionHeader("Colours", icon = NightbellIcons.Sparkle, accent = NightbellColors.Mint)
                    if (config.theme == WidgetTheme.CUSTOM) {
                        Text(
                            text = "Background",
                            style = MaterialTheme.typography.labelMedium,
                            color = NightbellColors.TextTertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        SwatchGrid(
                            swatches = WidgetConfig.BACKGROUND_SWATCHES,
                            selected = config.customBackgroundRgb,
                            onSelect = { rgb ->
                                // Move the text colour with the background *only* while
                                // it is still whatever the previous background suggested.
                                // Someone who has deliberately picked a colour keeps it;
                                // someone who has not is spared white-on-white.
                                val untouched = config.customTextRgb ==
                                    WidgetConfig.suggestedTextRgb(config.customBackgroundRgb)
                                onChange(
                                    config.copy(
                                        customBackgroundRgb = rgb,
                                        customTextRgb = if (untouched) {
                                            WidgetConfig.suggestedTextRgb(rgb)
                                        } else {
                                            config.customTextRgb
                                        },
                                    ),
                                )
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Text",
                            style = MaterialTheme.typography.labelMedium,
                            color = NightbellColors.TextTertiary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        SwatchGrid(
                            swatches = WidgetConfig.TEXT_SWATCHES,
                            selected = config.customTextRgb,
                            onSelect = { onChange(config.copy(customTextRgb = it)) },
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Background opacity",
                                style = MaterialTheme.typography.labelMedium,
                                color = NightbellColors.TextTertiary,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${(config.backgroundOpacity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = NightbellColors.TextSecondary,
                            )
                        }
                        Slider(
                            value = config.backgroundOpacity,
                            onValueChange = { onChange(config.copy(backgroundOpacity = it)) },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = NightbellColors.Mint,
                                activeTrackColor = NightbellColors.Mint,
                                inactiveTrackColor = NightbellColors.GlassFill,
                            ),
                            modifier = Modifier.testTag("widget-opacity"),
                        )
                        Text(
                            text = when {
                                config.backgroundOpacity <= 0.01f ->
                                    "Fully transparent — just text on your wallpaper. " +
                                        "Legibility is then entirely up to the wallpaper."
                                config.backgroundOpacity < 0.5f ->
                                    "Mostly see-through. Check it against your actual wallpaper."
                                else -> "Solid enough to stay readable over anything."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    } else {
                        Text(
                            text = "The ${config.theme.label.lowercase()} preset is a fixed " +
                                "surface with known contrast. Choose Custom above to set your " +
                                "own background, text colour and transparency.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
            }

            item(key = "content") {
                GlassCard {
                    SectionHeader("Content", icon = NightbellIcons.Layers, accent = NightbellColors.Violet)
                    // Three switches rather than one, because "clean" means different things
                    // to different people: some want the mark and nothing else, some want
                    // the status line and no branding at all.
                    ToggleRow(
                        title = "Logo",
                        subtitle = if (config.showLogo) "The mark, top left" else "Hidden",
                        checked = config.showLogo,
                        onCheckedChange = { onChange(config.copy(showLogo = it)) },
                        icon = NightbellIcons.Activity,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "App name",
                        subtitle = if (config.showTitle) "\"Nightbell\" beside the logo" else "Hidden",
                        checked = config.showTitle,
                        onCheckedChange = { onChange(config.copy(showTitle = it)) },
                        icon = NightbellIcons.Sparkle,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "Fleet summary",
                        subtitle = if (config.showHeadline) {
                            "\"${fleet.headline}\" in the header"
                        } else {
                            "Hidden"
                        },
                        checked = config.showHeadline,
                        onCheckedChange = { onChange(config.copy(showHeadline = it)) },
                        icon = NightbellIcons.Gauge,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "Settings button",
                        subtitle = if (config.showSettingsButton) {
                            "A cog in the corner reopens this screen"
                        } else {
                            "Hidden — long-press the widget instead (Android 12+)"
                        },
                        checked = config.showSettingsButton,
                        onCheckedChange = { onChange(config.copy(showSettingsButton = it)) },
                        icon = NightbellIcons.Sliders,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "Last checked",
                        subtitle = if (config.showTimestamp) {
                            "Footer shows how fresh the data is"
                        } else {
                            "Footer hidden"
                        },
                        checked = config.showTimestamp,
                        onCheckedChange = { onChange(config.copy(showTimestamp = it)) },
                        icon = NightbellIcons.Clock,
                        accent = NightbellColors.Violet,
                    )
                    ToggleRow(
                        title = "Only show problems",
                        subtitle = if (config.onlyProblems) {
                            "Healthy monitors are hidden"
                        } else {
                            "Every monitor is listed"
                        },
                        checked = config.onlyProblems,
                        onCheckedChange = { onChange(config.copy(onlyProblems = it)) },
                        icon = NightbellIcons.Filter,
                        accent = NightbellColors.Violet,
                    )
                    Spacer(Modifier.height(6.dp))
                    StepperRow(
                        title = "Monitors",
                        value = config.maxRows,
                        onValueChange = { onChange(config.copy(maxRows = it)) },
                        range = 1..NightbellWidgetProvider.MAX_ROWS,
                        icon = NightbellIcons.Layers,
                        accent = NightbellColors.Violet,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Columns",
                        style = MaterialTheme.typography.labelMedium,
                        color = NightbellColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ChipSelector(
                        options = listOf(0) + (1..WidgetLayout.MAX_COLUMNS).toList(),
                        selected = config.columns.coerceIn(0, WidgetLayout.MAX_COLUMNS),
                        onSelect = { onChange(config.copy(columns = it)) },
                        label = { if (it == 0) "Auto" else it.toString() },
                        accent = NightbellColors.Aqua,
                    )
                    Text(
                        text = if (config.columns == 0) {
                            "Automatic spills monitors into a second column when the widget " +
                                "is too short to stack them — make it flatter and they move " +
                                "sideways instead of disappearing."
                        } else {
                            "Fixed at ${config.columns}. Columns still collapse if the widget " +
                                "is dragged too narrow to read them."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextTertiary,
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(NightbellColors.Ink)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .padding(bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NightbellButton("Cancel", onCancel, tone = ButtonTone.Secondary, icon = NightbellIcons.Close)
            NightbellButton(
                text = if (reconfiguring) "Save" else "Add widget",
                onClick = onSave,
                icon = NightbellIcons.Check,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A row-wrapping grid of colour swatches.
 *
 * Deliberately a fixed palette rather than a hue/saturation picker: a widget has
 * to stay legible over a photograph, and a free picker mostly produces colours
 * nobody can read grey text on. Twelve considered options cover the intent
 * ("dark", "light", "match my accent") without that trap.
 */
@Composable
private fun SwatchGrid(swatches: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val perRow = 6
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.chunked(perRow).forEach { rowColours ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColours.forEach { rgb ->
                    val isSelected = (rgb and 0x00FFFFFF) == (selected and 0x00FFFFFF)
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(rgb or 0xFF000000.toInt()))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) NightbellColors.Mint else NightbellColors.sheen(0.18f),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelect(rgb) }
                            .testTag("swatch-$rgb"),
                    )
                }
            }
        }
    }
}

/**
 * Width the preview claims to be when planning a fixed column count.
 *
 * Only ever used to let [WidgetLayout.plan]'s width cap pass, so an explicitly chosen
 * two or three columns previews as two or three. Not a measurement of anything.
 */
private const val PREVIEW_WIDTH_DP = 400

/** One monitor in the preview. Extracted so both columns draw the identical thing. */
@Composable
private fun PreviewRow(
    entry: Summary.Entry,
    detailed: Boolean,
    showValue: Boolean,
    primary: Color,
    tertiary: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(healthColor(entry.health)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (entry.urgentNagging) "⚠ ${entry.name}" else entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detailed) {
                Text(
                    text = entry.message.ifBlank { entry.host },
                    style = MaterialTheme.typography.bodySmall,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showValue) {
            Text(
                text = when {
                    entry.health == Health.DOWN -> "DOWN"
                    entry.health == Health.PAUSED -> "PAUSED"
                    entry.latencyMs > 0 -> "${entry.latencyMs} ms"
                    else -> "—"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.health == Health.UP) tertiary else healthColor(entry.health),
            )
        }
    }
}

/**
 * A Compose stand-in for the RemoteViews widget. Not pixel-identical by
 * construction — it mirrors the same palette, ordering and row content so the
 * choice being made is the choice being previewed.
 */
@Composable
private fun WidgetPreview(config: WidgetConfig, fleet: Summary.Fleet) {
    // One source of truth with the real widget. The preview used to hard-code its
    // own copy of the three themes, which is fine until the palettes gain
    // arithmetic — then the preview and the widget quietly disagree about exactly
    // the setting the user is trying to see.
    val palette = config.palette
    val background = Color(palette.background)
    val primary = Color(palette.primary)
    val tertiary = Color(palette.tertiary)
    val rows = fleet.ranked
        .filter { !config.onlyProblems || it.health != Health.UP }
        .take(config.maxRows)

    // A checkerboard behind the surface, so "fully transparent" previews as
    // see-through rather than as whatever colour this screen happens to be.
    val checker = NightbellColors.sheen(0.05f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawBehind {
                val cell = 10.dp.toPx()
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var col = 0
                    while (x < size.width) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = checker,
                                topLeft = Offset(x, y),
                                size = Size(
                                    minOf(cell, size.width - x),
                                    minOf(cell, size.height - y),
                                ),
                            )
                        }
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            },
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, Color(palette.border), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        if (config.headerVisible || config.showSettingsButton) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (config.showLogo) {
                    // The fixed brand blue rather than the theme-aware Aqua, because that is
                    // what ic_widget_mark draws — the light scheme's darker Aqua here would
                    // make this a nicer picture of a widget that does not exist.
                    NightbellMark(
                        size = 18.dp,
                        color = Color(0xFF2F6BFF),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (config.showTitle) {
                    Text("Nightbell", style = MaterialTheme.typography.titleMedium, color = primary)
                }
                Spacer(Modifier.weight(1f))
                if (config.showHeadline) {
                    Text(
                        text = fleet.headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (config.showSettingsButton) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = NightbellIcons.Sliders,
                        contentDescription = null,
                        tint = tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (rows.isEmpty()) {
            Text(
                text = if (fleet.total == 0) "No monitors yet" else "Everything is healthy.",
                style = MaterialTheme.typography.bodySmall,
                color = tertiary,
            )
        }
        // Columns, laid out by the same planner the widget uses.
        //
        // The preview is a fixed-width card with unbounded height, so there is no real
        // measurement to plan against: an explicit column count is honoured, and Auto
        // previews as one column rather than inventing a widget size and showing a layout
        // the user might never get. The caption under the Columns chips says so.
        val plan = WidgetLayout.plan(
            config = config,
            wanted = rows.size,
            widthDp = if (config.columns > 0) PREVIEW_WIDTH_DP else 0,
            heightDp = 0,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WidgetLayout.distribute(rows, plan).forEach { column ->
                Column(Modifier.weight(1f)) {
                    column.forEach { entry ->
                        PreviewRow(
                            entry = entry,
                            detailed = config.density == WidgetDensity.DETAILED,
                            showValue = plan.showValues,
                            primary = primary,
                            tertiary = tertiary,
                        )
                    }
                }
            }
        }
        if (config.showTimestamp) {
            Spacer(Modifier.height(6.dp))
            val newest = fleet.entries.maxOfOrNull { it.lastCheckedAt } ?: 0L
            Text(
                text = if (newest > 0L) {
                    "Checked ${NightbellWidgetProvider.relative(newest)}"
                } else {
                    "Not checked yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tertiary,
            )
        }
    }
    }
}
