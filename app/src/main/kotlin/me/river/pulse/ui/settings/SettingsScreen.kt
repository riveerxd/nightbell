package me.river.pulse.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.pulse.BuildConfig
import me.river.pulse.data.Pulse
import me.river.pulse.ui.components.AlertPolicyEditor
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.GlassIconButton
import me.river.pulse.ui.components.IconBadge
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.components.SectionHeader
import me.river.pulse.ui.components.StaggeredEntrance
import me.river.pulse.ui.components.rememberEntranceLog
import me.river.pulse.ui.components.StepperRow
import me.river.pulse.ui.components.ToggleRow
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.rememberSettingsViewModel
import me.river.pulse.ui.theme.PulseColors
import androidx.compose.ui.platform.testTag

@Composable
fun SettingsScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val viewModel = rememberSettingsViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

        item(key = "motion") {
            StaggeredEntrance(index = 3, key = "motion", log = entrance) {
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
                }
            }
        }

        item(key = "about") {
            StaggeredEntrance(index = 4, key = "about", log = entrance) {
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
