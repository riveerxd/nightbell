package me.river.pulse.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.pulse.BuildConfig
import me.river.pulse.data.Pulse
import me.river.pulse.domain.CheckerHealth
import me.river.pulse.domain.CheckerLimit
import me.river.pulse.ui.components.AlertPolicyEditor
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.IconBadge
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.GlassField
import me.river.pulse.ui.components.GlassDivider
import androidx.compose.ui.text.input.KeyboardType
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.StaggeredEntrance
import me.river.pulse.ui.components.rememberEntranceLog
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberSettingsViewModel
import me.river.pulse.ui.theme.Backdrop
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.widget.PulseWidgetProvider
import me.river.pulse.widget.WidgetConfigActivity
import androidx.compose.ui.platform.testTag

@Composable
fun SettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val viewModel = rememberSettingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val checkerHealth by viewModel.checkerHealth.collectAsStateWithLifecycle()
    val checkerLimit by viewModel.checkerLimit.collectAsStateWithLifecycle()
    val batteryOptimised by viewModel.batteryOptimised.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read once per composition of this screen rather than observed: widgets are
    // placed and removed on the home screen, so this is only ever right at the
    // moment the user opens Settings — which is when they need it.
    val placedWidgetIds = remember {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, PulseWidgetProvider::class.java))
                .toList()
        }.getOrDefault(emptyList())
    }
    var notificationsAllowed by remember {
        mutableStateOf(Pulse.install(context).alerts.hasNotificationPermission())
    }
    val entrance = rememberEntranceLog()

    val toast = viewModel.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            onToast(toast)
            viewModel.consumeToast()
        }
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = topInset + 12.dp,
            bottom = bottomInset + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlassIconButton(
                    icon = PulseIcons.ArrowLeft,
                    onClick = onBack,
                    contentDescription = "Back",
                    accent = PulseColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.displayMedium,
                    color = PulseColors.TextPrimary,
                )
            }
        }

        item(key = "permission") {
            AnimatedVisibility(
                visible = !notificationsAllowed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                GlassCard(accent = PulseColors.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(PulseIcons.BellOff, PulseColors.Amber, size = 40.dp)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Notifications are blocked",
                                style = MaterialTheme.typography.titleMedium,
                                color = PulseColors.TextPrimary,
                            )
                            Text(
                                "Pulse can still check monitors, but it can't tell you when " +
                                    "something breaks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    PulseButton(
                        text = "Open notification settings",
                        onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        icon = PulseIcons.Bell,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "master") {
            StaggeredEntrance(index = 0, key = "master", log = entrance) {
                GlassCard(accent = if (settings.masterAlertsEnabled) Color.Transparent else PulseColors.Rose) {
                    ToggleRow(
                        title = "All alerts",
                        subtitle = if (settings.masterAlertsEnabled) {
                            "Monitors can notify you"
                        } else {
                            "Everything is muted — nothing will notify you"
                        },
                        checked = settings.masterAlertsEnabled,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(masterAlertsEnabled = value) }
                        },
                        icon = if (settings.masterAlertsEnabled) PulseIcons.Bell else PulseIcons.BellOff,
                        accent = PulseColors.Aqua,
                    )
                }
            }
        }

        item(key = "defaults") {
            StaggeredEntrance(index = 1, key = "defaults", log = entrance) {
                GlassCard {
                    SectionHeader("Default alert policy", icon = PulseIcons.Shield, accent = PulseColors.Aqua)
                    Text(
                        text = "Applies to every monitor set to “use my global alert settings”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    AlertPolicyEditor(
                        policy = settings.defaultAlert,
                        onChange = { policy -> viewModel.updateDefaultAlert { policy } },
                        onPreviewVibration = viewModel::previewVibration,
                        onSendTestAlert = {
                            notificationsAllowed = Pulse.install(context).alerts.hasNotificationPermission()
                            viewModel.sendTestAlert()
                        },
                        showMasterToggle = false,
                    )
                }
            }
        }

        item(key = "scheduling") {
            StaggeredEntrance(index = 2, key = "scheduling", log = entrance) {
                GlassCard {
                    SectionHeader("Background checks", icon = PulseIcons.Radar, accent = PulseColors.Violet)
                    ToggleRow(
                        title = "Run checks in the background",
                        subtitle = "Uses WorkManager; Android batches work to save battery",
                        checked = settings.backgroundChecksEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(backgroundChecksEnabled = v) } },
                        icon = PulseIcons.Power,
                        accent = PulseColors.Violet,
                    )
                    ToggleRow(
                        title = "Wi-Fi only",
                        subtitle = "Skip checks on metered mobile data",
                        checked = settings.onlyOnUnmeteredNetwork,
                        onCheckedChange = { v -> viewModel.update { it.copy(onlyOnUnmeteredNetwork = v) } },
                        icon = PulseIcons.Wifi,
                        accent = PulseColors.Violet,
                    )
                    Spacer(Modifier.height(6.dp))
                    StepperRow(
                        title = "Default interval",
                        value = settings.defaultIntervalMinutes,
                        onValueChange = { v -> viewModel.update { it.copy(defaultIntervalMinutes = v) } },
                        range = 1..1440,
                        suffix = "m",
                        icon = PulseIcons.Clock,
                        accent = PulseColors.Violet,
                    )
                    StepperRow(
                        title = "Default timeout",
                        value = settings.defaultTimeoutSeconds,
                        onValueChange = { v -> viewModel.update { it.copy(defaultTimeoutSeconds = v) } },
                        range = 1..120,
                        suffix = "s",
                        icon = PulseIcons.Gauge,
                        accent = PulseColors.Violet,
                    )
                    StepperRow(
                        title = "History kept",
                        value = settings.historyDepth,
                        onValueChange = { v -> viewModel.update { it.copy(historyDepth = v) } },
                        range = 10..300,
                        step = 10,
                        icon = PulseIcons.History,
                        accent = PulseColors.Violet,
                    )
                }
            }
        }

        item(key = "checker-health") {
            StaggeredEntrance(index = 3, key = "checker-health", log = entrance) {
                CheckerHealthCard(
                    health = checkerHealth,
                    limit = checkerLimit,
                    batteryOptimised = batteryOptimised,
                    strict = settings.strictForegroundMonitoring,
                    onOpenBatterySettings = {
                        runCatching { context.startActivity(viewModel.batterySettingsIntent()) }
                    },
                )
            }
        }

        item(key = "strict") {
            StaggeredEntrance(index = 4, key = "strict", log = entrance) {
                GlassCard(accent = if (settings.strictForegroundMonitoring) PulseColors.Amber else Color.Transparent) {
                    SectionHeader("Strict cadence", icon = PulseIcons.Zap, accent = PulseColors.Amber)
                    Text(
                        text = "WorkManager is battery-friendly but Doze can defer a check for " +
                            "a long time, and its periodic minimum is 15 minutes. Strict mode " +
                            "runs a foreground service that keeps your intervals exactly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = "Strict foreground monitoring",
                        subtitle = if (settings.strictForegroundMonitoring) {
                            "On — a permanent notification is showing"
                        } else {
                            "Off — checks are best-effort, batched by Android"
                        },
                        checked = settings.strictForegroundMonitoring,
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(strictForegroundMonitoring = v) }
                        },
                        icon = PulseIcons.Power,
                        accent = PulseColors.Amber,
                    )
                    AnimatedVisibility(
                        visible = settings.strictForegroundMonitoring,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Spacer(Modifier.height(6.dp))
                            WarningPanel(
                                "This costs real battery. Pulse wakes up as often as your " +
                                    "tightest interval needs and holds a persistent " +
                                    "notification Android will not let you dismiss.\n\n" +
                                    "Background checks stay armed underneath as a repair " +
                                    "sweep, so nothing is lost if the OS kills the service.",
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "An unacknowledged URGENT outage starts the same service on its " +
                            "own, whatever this is set to, and stops it once you confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                }
            }
        }

        item(key = "latency") {
            StaggeredEntrance(index = 5, key = "latency", log = entrance) {
                GlassCard {
                    SectionHeader("Latency budget", icon = PulseIcons.Gauge, accent = PulseColors.Amber)
                    Text(
                        text = "The default for monitors that don't set their own. A successful " +
                            "check slower than this is DEGRADED — up, but not well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = "Flag slow responses",
                        subtitle = if (settings.defaultLatencySloMs > 0) {
                            "Degraded above ${settings.defaultLatencySloMs} ms"
                        } else {
                            "Off — only up and down, never degraded"
                        },
                        checked = settings.defaultLatencySloMs > 0,
                        onCheckedChange = { on ->
                            viewModel.update { it.copy(defaultLatencySloMs = if (on) 2_500 else 0) }
                        },
                        icon = PulseIcons.Activity,
                        accent = PulseColors.Amber,
                    )
                    AnimatedVisibility(
                        visible = settings.defaultLatencySloMs > 0,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        StepperRow(
                            title = "Degraded above",
                            value = settings.defaultLatencySloMs.coerceAtLeast(100),
                            onValueChange = { v -> viewModel.update { it.copy(defaultLatencySloMs = v) } },
                            range = 100..60_000,
                            step = 100,
                            suffix = "ms",
                            icon = PulseIcons.Gauge,
                            accent = PulseColors.Amber,
                        )
                    }

                    AnimatedVisibility(
                        visible = settings.defaultLatencySloMs > 0,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            GlassDivider(Modifier.padding(vertical = 12.dp))
                            Text(
                                text = "A latency measured from this phone is your connection " +
                                    "plus the server. On bad wifi that makes everything look " +
                                    "slow at once. Pulse can time a known-good endpoint " +
                                    "alongside the checks and subtract whatever your " +
                                    "connection is adding.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.TextTertiary,
                            )
                            Spacer(Modifier.height(8.dp))
                            ToggleRow(
                                title = "Discount my connection",
                                subtitle = if (settings.latencyBaselineEnabled) {
                                    "Slow readings are checked against a reference first"
                                } else {
                                    "Off — a slow connection will read as slow services"
                                },
                                checked = settings.latencyBaselineEnabled,
                                onCheckedChange = { on ->
                                    viewModel.update { it.copy(latencyBaselineEnabled = on) }
                                },
                                icon = PulseIcons.Wifi,
                                accent = PulseColors.Aqua,
                            )
                            AnimatedVisibility(
                                visible = settings.latencyBaselineEnabled,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    GlassField(
                                        value = settings.latencyReferenceUrl,
                                        onValueChange = { v ->
                                            viewModel.update { it.copy(latencyReferenceUrl = v.trim()) }
                                        },
                                        label = "Reference endpoint",
                                        placeholder = "https://www.gstatic.com/generate_204",
                                        helper = "Wants to be always up and cheap to answer. If your " +
                                            "network blocks it, latency is judged raw — nothing breaks.",
                                        leadingIcon = PulseIcons.Globe,
                                        accent = PulseColors.Aqua,
                                        keyboardType = KeyboardType.Uri,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "widgets") {
            StaggeredEntrance(index = 6, key = "widgets", log = entrance) {
                WidgetsCard(
                    ids = placedWidgetIds,
                    onConfigure = { id ->
                        runCatching {
                            context.startActivity(
                                Intent(context, WidgetConfigActivity::class.java)
                                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }

        item(key = "motion") {
            StaggeredEntrance(index = 7, key = "motion", log = entrance) {
                GlassCard {
                    SectionHeader("Motion", icon = PulseIcons.Sparkle, accent = PulseColors.Mint)
                    Text(
                        text = when {
                            settings.motionIntensity < 0.06f -> "Animations off — everything snaps into place."
                            settings.motionIntensity < 0.7f -> "Subtle motion."
                            settings.motionIntensity < 1.2f -> "Full Pulse: aurora, sonar rings, the lot."
                            else -> "Extra lively."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = settings.motionIntensity,
                        onValueChange = { v -> viewModel.update { it.copy(motionIntensity = v) } },
                        valueRange = 0f..1.4f,
                        colors = SliderDefaults.colors(
                            thumbColor = PulseColors.Mint,
                            activeTrackColor = PulseColors.Mint,
                            inactiveTrackColor = PulseColors.GlassFill,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Frosted glass",
                        subtitle = when {
                            !Backdrop.isSupported ->
                                "Needs Android 12 — this device uses solid panes"
                            settings.realBlurEnabled ->
                                "Real backdrop blur behind floating sheets"
                            else ->
                                "Solid panes — cheaper to draw"
                        },
                        checked = settings.realBlurEnabled && Backdrop.isSupported,
                        onCheckedChange = { v -> viewModel.update { it.copy(realBlurEnabled = v) } },
                        icon = PulseIcons.Layers,
                        accent = PulseColors.Mint,
                        enabled = Backdrop.isSupported,
                    )
                }
            }
        }

        item(key = "about") {
            StaggeredEntrance(index = 8, key = "about", log = entrance) {
                GlassCard {
                    SectionHeader("About", icon = PulseIcons.Info, accent = PulseColors.Sky)
                    AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    AboutRow("Android", "API ${Build.VERSION.SDK_INT} · ${Build.MODEL}")
                    AboutRow("Storage", "Local only — nothing leaves the device")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Pulse checks HTTP endpoints, asserts on response bodies, and " +
                            "watches individual elements on real rendered pages. Background " +
                            "cadence is best-effort: Android may delay work in Doze.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * Checker health, and the honest account of what Android will and will not do.
 *
 * The point of this card is that the app now has somewhere to *show* things it
 * used to notify about. Before 1.6.0 there were two verdicts — fine, or down —
 * so Doze deferring work, a stopped service and a cancelled coroutine all had to
 * be reported as an outage, which is how a healthy fleet ended up buzzing
 * "Checker crashed" six times at once. Delay and restriction are visible here and
 * are never a notification; only a verified, repeated fault inside Pulse's own
 * code is.
 */
@Composable
private fun CheckerHealthCard(
    health: CheckerHealth.State,
    limit: CheckerLimit,
    batteryOptimised: Boolean,
    strict: Boolean,
    onOpenBatterySettings: () -> Unit,
) {
    val crashed = health.kind == CheckerHealth.Kind.CRASHED
    val accent = when {
        crashed -> PulseColors.Rose
        limit.isLimited -> PulseColors.Amber
        else -> Color.Transparent
    }
    GlassCard(accent = accent) {
        SectionHeader(
            "Checker health",
            icon = PulseIcons.Activity,
            accent = if (accent == Color.Transparent) PulseColors.Mint else accent,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                when {
                    crashed -> PulseIcons.Warning
                    limit == CheckerLimit.OFFLINE -> PulseIcons.WifiOff
                    limit.isLimited -> PulseIcons.Clock
                    else -> PulseIcons.Check
                },
                if (accent == Color.Transparent) PulseColors.Mint else accent,
                size = 40.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (crashed) "Pulse can't complete its checks" else limit.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.TextPrimary,
                )
                Text(
                    text = if (crashed) health.summary else limit.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
        }

        if (crashed && health.lastSignature.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            WarningPanel(
                "Last internal error: ${health.lastSignature}. This is a fault in " +
                    "Pulse, not in the sites you are watching — their status is " +
                    "unchanged. It clears as soon as one check completes.",
            )
        }

        GlassDivider(Modifier.padding(vertical = 12.dp))

        Text(
            text = "What Android allows",
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Background checks run through WorkManager, whose shortest " +
                "possible repeat is 15 minutes — a platform floor, not a Pulse " +
                "setting. A tighter interval than that is honoured at 15-minute " +
                "granularity in the background, and exactly only while strict " +
                "mode's foreground service is running. Doze can defer any of it " +
                "further. None of that is an outage and Pulse will never notify " +
                "you about it.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextTertiary,
        )

        if (batteryOptimised && !strict) {
            Spacer(Modifier.height(12.dp))
            PulseButton(
                text = "Exempt Pulse from battery optimisation",
                onClick = onOpenBatterySettings,
                icon = PulseIcons.Power,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Optional, and it helps — but it is not a guarantee. Only the " +
                    "foreground service is.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
            )
        }
    }
}

/**
 * A route into a placed widget's settings from inside the app.
 *
 * The third of three, and the one that always works. Android only offers
 * "reconfigure" behind a long-press and only on API 31+; the widget's own cog can
 * be switched off; this cannot be missed. Reported as a real problem: once the
 * widget was on the home screen, its configuration was unreachable.
 */
@Composable
private fun WidgetsCard(ids: List<Int>, onConfigure: (Int) -> Unit) {
    GlassCard {
        SectionHeader("Home-screen widgets", icon = PulseIcons.Layers, accent = PulseColors.Sky)
        if (ids.isEmpty()) {
            Text(
                text = "None placed yet. Long-press your home screen, choose Widgets, " +
                    "and look for Pulse.",
                style = MaterialTheme.typography.bodySmall,
                color = PulseColors.TextTertiary,
            )
            return@GlassCard
        }
        Text(
            text = "Colours, transparency, density and which monitors appear are set per " +
                "widget. You can also tap the cog on the widget itself.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextTertiary,
        )
        Spacer(Modifier.height(10.dp))
        ids.forEachIndexed { index, id ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            PulseButton(
                text = if (ids.size == 1) "Configure widget" else "Configure widget ${index + 1}",
                onClick = { onConfigure(id) },
                icon = PulseIcons.Sliders,
                tone = ButtonTone.Secondary,
                modifier = Modifier.fillMaxWidth().testTag("configure-widget-$id"),
            )
        }
    }
}

/** Amber-bordered panel for the "this costs you something" copy. */
@Composable
private fun WarningPanel(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PulseColors.Amber.copy(alpha = 0.10f))
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            PulseIcons.Warning,
            contentDescription = null,
            tint = PulseColors.Amber,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextSecondary,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = PulseColors.TextTertiary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextSecondary,
        )
    }
}
