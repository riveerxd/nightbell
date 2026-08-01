package me.river.pulse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import me.river.pulse.data.Pulse
import me.river.pulse.domain.Health
import me.river.pulse.domain.Summary
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.ChipSelector
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseTheme
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
        Pulse.install(applicationContext)

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
            val snapshot by Pulse.store.snapshot.collectAsState()

            LaunchedEffect(Unit) {
                config = WidgetConfigStore.load(applicationContext, appWidgetId)
                loaded = true
            }

            PulseTheme(motionIntensity = snapshot.settings.motionIntensity) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(PulseColors.Void),
                ) {
                    if (loaded) {
                        ConfigBody(
                            config = config,
                            fleet = Summary.of(snapshot.monitors, snapshot.runtimes),
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
            PulseWidgetProvider.refresh(applicationContext)
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
                    color = PulseColors.TextPrimary,
                )
                Text(
                    text = "Monitors are always listed worst first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulseColors.TextSecondary,
                )
            }

            item(key = "preview") {
                GlassCard {
                    SectionHeader("Preview", icon = PulseIcons.Eye, accent = PulseColors.Aqua)
                    WidgetPreview(config, fleet)
                }
            }

            item(key = "look") {
                GlassCard {
                    SectionHeader("Look", icon = PulseIcons.Sparkle, accent = PulseColors.Aqua)
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ChipSelector(
                        options = WidgetTheme.entries.toList(),
                        selected = config.theme,
                        onSelect = { onChange(config.copy(theme = it)) },
                        label = { it.label },
                        accent = PulseColors.Aqua,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Density",
                        style = MaterialTheme.typography.labelMedium,
                        color = PulseColors.TextTertiary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    ChipSelector(
                        options = WidgetDensity.entries.toList(),
                        selected = config.density,
                        onSelect = { onChange(config.copy(density = it)) },
                        label = { it.label },
                        accent = PulseColors.Aqua,
                    )
                }
            }

            item(key = "content") {
                GlassCard {
                    SectionHeader("Content", icon = PulseIcons.Layers, accent = PulseColors.Violet)
                    ToggleRow(
                        title = "App title and logo",
                        subtitle = if (config.showTitle) "Header row is shown" else "More room for monitors",
                        checked = config.showTitle,
                        onCheckedChange = { onChange(config.copy(showTitle = it)) },
                        icon = PulseIcons.Sparkle,
                        accent = PulseColors.Violet,
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
                        icon = PulseIcons.Clock,
                        accent = PulseColors.Violet,
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
                        icon = PulseIcons.Filter,
                        accent = PulseColors.Violet,
                    )
                    Spacer(Modifier.height(6.dp))
                    StepperRow(
                        title = "Rows",
                        value = config.maxRows,
                        onValueChange = { onChange(config.copy(maxRows = it)) },
                        range = 1..PulseWidgetProvider.MAX_ROWS,
                        icon = PulseIcons.Layers,
                        accent = PulseColors.Violet,
                    )
                    Text(
                        text = "A widget can only draw what fits its cell — resize it on the " +
                            "home screen if rows are cut off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(PulseColors.Ink)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .padding(bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulseButton("Cancel", onCancel, tone = ButtonTone.Secondary, icon = PulseIcons.Close)
            PulseButton(
                text = "Add widget",
                onClick = onSave,
                icon = PulseIcons.Check,
                modifier = Modifier.weight(1f),
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
    val background = when (config.theme) {
        WidgetTheme.BLACK -> Color(0xFF0B0B0B)
        WidgetTheme.WHITE -> Color(0xFFF6F6F8)
        WidgetTheme.BLUE -> Color(0xFF0C1A3A)
    }
    val primary = if (config.theme == WidgetTheme.WHITE) Color(0xFF0A0A0A) else Color.White
    val tertiary = when (config.theme) {
        WidgetTheme.WHITE -> Color(0xFF6B6B6B)
        WidgetTheme.BLUE -> Color(0xFF7E9BD6)
        WidgetTheme.BLACK -> Color(0xFF8A8A8A)
    }
    val rows = fleet.ranked
        .filter { !config.onlyProblems || it.health != Health.UP }
        .take(config.maxRows)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        if (config.showTitle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pulse", style = MaterialTheme.typography.titleMedium, color = primary)
                Spacer(Modifier.weight(1f))
                Text(
                    text = fleet.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = tertiary,
                )
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
        rows.forEach { entry ->
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
                    if (config.density == WidgetDensity.DETAILED) {
                        Text(
                            text = entry.message.ifBlank { entry.host },
                            style = MaterialTheme.typography.bodySmall,
                            color = tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
        if (config.showTimestamp) {
            Spacer(Modifier.height(6.dp))
            val newest = fleet.entries.maxOfOrNull { it.lastCheckedAt } ?: 0L
            Text(
                text = if (newest > 0L) {
                    "Checked ${PulseWidgetProvider.relative(newest)}"
                } else {
                    "Not checked yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = tertiary,
            )
        }
    }
}
