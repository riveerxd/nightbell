package me.river.pulse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.SoundChoice
import me.river.pulse.domain.VibrationStyle
import me.river.pulse.ui.icons.NightbellIcons
import me.river.pulse.ui.theme.NightbellColors

/** Renders a minute-of-day as 24h wall clock. */
fun formatMinuteOfDay(minute: Int): String {
    val safe = ((minute % 1440) + 1440) % 1440
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun soundIcon(choice: SoundChoice) = when (choice) {
    SoundChoice.SILENT -> NightbellIcons.VolumeOff
    SoundChoice.DEFAULT_NOTIFICATION -> NightbellIcons.Bell
    SoundChoice.ALARM -> NightbellIcons.Warning
    SoundChoice.RINGTONE -> NightbellIcons.Volume
}

/**
 * The full alert-policy surface, shared by global settings and per-monitor
 * overrides so both always stay in lockstep.
 */
@Composable
fun AlertPolicyEditor(
    policy: AlertPolicy,
    onChange: (AlertPolicy) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Aqua,
    onPreviewVibration: (VibrationStyle) -> Unit = {},
    onSendTestAlert: (() -> Unit)? = null,
    showMasterToggle: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {

        if (showMasterToggle) {
            ToggleRow(
                title = "Alerts for this monitor",
                subtitle = if (policy.enabled) policy.summary else "You won't be notified at all",
                checked = policy.enabled,
                onCheckedChange = { onChange(policy.copy(enabled = it)) },
                icon = if (policy.enabled) NightbellIcons.Bell else NightbellIcons.BellOff,
                accent = accent,
            )
        }

        AnimatedVisibility(
            visible = policy.enabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

                ToggleRow(
                    title = "Notify when it goes down",
                    subtitle = "The main event",
                    checked = policy.alertOnDown,
                    onCheckedChange = { onChange(policy.copy(alertOnDown = it)) },
                    icon = NightbellIcons.Warning,
                    accent = NightbellColors.Rose,
                )

                ToggleRow(
                    title = "Notify when it recovers",
                    subtitle = "A quiet all-clear once it's healthy again",
                    checked = policy.alertOnRecovery,
                    onCheckedChange = { onChange(policy.copy(alertOnRecovery = it)) },
                    icon = NightbellIcons.Check,
                    accent = NightbellColors.Mint,
                )

                Spacer(Modifier.height(10.dp))
                SectionHeader("Latency", icon = NightbellIcons.Gauge, accent = NightbellColors.Amber)
                Text(
                    text = "Degraded is up-but-slow: the check passed, it just blew its " +
                        "latency budget. It has its own cooldown so a slow morning never " +
                        "eats the cooldown an outage needs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ToggleRow(
                    title = "Notify when it goes slow",
                    subtitle = if (policy.alertOnDegraded) {
                        "Alerts on a latency-budget breach, separately from outages"
                    } else {
                        "Degraded shows on the dashboard but stays silent"
                    },
                    checked = policy.alertOnDegraded,
                    onCheckedChange = { onChange(policy.copy(alertOnDegraded = it)) },
                    icon = NightbellIcons.Activity,
                    accent = NightbellColors.Amber,
                )
                AnimatedVisibility(
                    visible = policy.alertOnDegraded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        ToggleRow(
                            title = "Notify when it speeds up again",
                            subtitle = "All-clear when latency drops back under budget",
                            checked = policy.alertOnDegradedRecovery,
                            onCheckedChange = { onChange(policy.copy(alertOnDegradedRecovery = it)) },
                            icon = NightbellIcons.Check,
                            accent = NightbellColors.Amber,
                        )
                        StepperRow(
                            title = "Latency cooldown",
                            value = policy.degradedCooldownMinutes,
                            onValueChange = { onChange(policy.copy(degradedCooldownMinutes = it)) },
                            range = 0..720,
                            step = 5,
                            suffix = "m",
                            icon = NightbellIcons.Clock,
                            accent = NightbellColors.Amber,
                        )
                        ToggleRow(
                            title = "Keep reminding me it's slow",
                            subtitle = if (policy.degradedRepeatEnabled) {
                                "Re-alerts every ${policy.degradedRepeatEveryMinutes} minutes"
                            } else {
                                "One latency alert per slow spell"
                            },
                            checked = policy.degradedRepeatEnabled,
                            onCheckedChange = { onChange(policy.copy(degradedRepeatEnabled = it)) },
                            icon = NightbellIcons.History,
                            accent = NightbellColors.Amber,
                        )
                        AnimatedVisibility(
                            visible = policy.degradedRepeatEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            StepperRow(
                                title = "Repeat every",
                                value = policy.degradedRepeatEveryMinutes,
                                onValueChange = {
                                    onChange(policy.copy(degradedRepeatEveryMinutes = it))
                                },
                                range = 5..1440,
                                step = 5,
                                suffix = "m",
                                icon = NightbellIcons.Refresh,
                                accent = NightbellColors.Amber,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                SectionHeader("Sound", icon = NightbellIcons.Volume, accent = accent)
                ChipSelector(
                    options = SoundChoice.entries.toList(),
                    selected = policy.sound,
                    onSelect = { onChange(policy.copy(sound = it)) },
                    label = { it.label },
                    icon = { soundIcon(it) },
                    accent = accent,
                )
                Text(
                    text = "Android freezes sound and vibration onto a notification " +
                        "channel, so Nightbell creates one channel per combination. Long-press " +
                        "any Nightbell notification to fine-tune it in system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                )

                Spacer(Modifier.height(14.dp))
                SectionHeader("Haptics", icon = NightbellIcons.Vibrate, accent = accent)
                ToggleRow(
                    title = "Vibrate",
                    subtitle = if (policy.vibrate) {
                        "Style: ${policy.vibrationStyle.label} — tap a style to feel it"
                    } else {
                        "No haptic feedback"
                    },
                    checked = policy.vibrate,
                    onCheckedChange = { onChange(policy.copy(vibrate = it)) },
                    icon = NightbellIcons.Vibrate,
                    accent = accent,
                )
                AnimatedVisibility(
                    visible = policy.vibrate,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        ChipSelector(
                            options = VibrationStyle.entries.toList(),
                            selected = policy.vibrationStyle,
                            onSelect = {
                                onChange(policy.copy(vibrationStyle = it))
                                onPreviewVibration(it)
                            },
                            label = { it.label },
                            accent = NightbellColors.Violet,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionHeader("Escalation", icon = NightbellIcons.Zap, accent = accent)

                StepperRow(
                    title = "Failures before alerting",
                    value = policy.failureThreshold,
                    onValueChange = { onChange(policy.copy(failureThreshold = it)) },
                    range = 1..10,
                    icon = NightbellIcons.Filter,
                    accent = accent,
                )
                Text(
                    text = if (policy.failureThreshold == 1) {
                        "Alerts on the very first failed check."
                    } else {
                        "Ignores blips: needs ${policy.failureThreshold} failures in a row."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                )

                StepperRow(
                    title = "Cooldown",
                    value = policy.cooldownMinutes,
                    onValueChange = { onChange(policy.copy(cooldownMinutes = it)) },
                    range = 0..240,
                    step = 5,
                    suffix = "m",
                    icon = NightbellIcons.Clock,
                    accent = accent,
                )
                Text(
                    text = "Minimum gap between two alerts for the same monitor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                )

                ToggleRow(
                    title = "Keep reminding me",
                    subtitle = if (policy.repeatEnabled) {
                        "Re-alerts every ${policy.repeatEveryMinutes} minutes while down"
                    } else {
                        "One alert per outage"
                    },
                    checked = policy.repeatEnabled,
                    onCheckedChange = { onChange(policy.copy(repeatEnabled = it)) },
                    icon = NightbellIcons.History,
                    accent = NightbellColors.Amber,
                )
                AnimatedVisibility(
                    visible = policy.repeatEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    StepperRow(
                        title = "Repeat every",
                        value = policy.repeatEveryMinutes,
                        onValueChange = { onChange(policy.copy(repeatEveryMinutes = it)) },
                        range = 5..720,
                        step = 5,
                        suffix = "m",
                        icon = NightbellIcons.Refresh,
                        accent = NightbellColors.Amber,
                    )
                }

                Spacer(Modifier.height(14.dp))
                SectionHeader("Quiet hours", icon = NightbellIcons.Moon, accent = accent)
                ToggleRow(
                    title = "Silence overnight",
                    subtitle = if (policy.quietHoursEnabled) {
                        "${formatMinuteOfDay(policy.quietStartMinute)} → " +
                            formatMinuteOfDay(policy.quietEndMinute)
                    } else {
                        "Alerts can fire at any hour"
                    },
                    checked = policy.quietHoursEnabled,
                    onCheckedChange = { onChange(policy.copy(quietHoursEnabled = it)) },
                    icon = NightbellIcons.Moon,
                    accent = NightbellColors.Violet,
                )
                AnimatedVisibility(
                    visible = policy.quietHoursEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        TimeRangeRow(
                            startMinute = policy.quietStartMinute,
                            endMinute = policy.quietEndMinute,
                            onStartChange = { onChange(policy.copy(quietStartMinute = it)) },
                            onEndChange = { onChange(policy.copy(quietEndMinute = it)) },
                        )
                        ToggleRow(
                            title = "Still notify, but silently",
                            subtitle = "Posts the alert with no sound or vibration",
                            checked = policy.criticalBypassesQuiet,
                            onCheckedChange = { onChange(policy.copy(criticalBypassesQuiet = it)) },
                            icon = NightbellIcons.Shield,
                            accent = NightbellColors.Violet,
                        )
                    }
                }

                if (onSendTestAlert != null) {
                    Spacer(Modifier.height(16.dp))
                    NightbellButton(
                        text = "Send a test alert",
                        onClick = onSendTestAlert,
                        icon = NightbellIcons.Bell,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRangeRow(
    startMinute: Int,
    endMinute: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TimeStepper(
            label = "From",
            minute = startMinute,
            onChange = onStartChange,
            modifier = Modifier.weight(1f),
        )
        TimeStepper(
            label = "Until",
            minute = endMinute,
            onChange = onEndChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimeStepper(
    label: String,
    minute: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NightbellColors.sheen(0.05f))
            .border(1.dp, NightbellColors.sheen(0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = NightbellColors.TextTertiary,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallStep(NightbellIcons.ChevronLeft, "$label earlier") {
                onChange(((minute - 30) + 1440) % 1440)
            }
            Text(
                text = formatMinuteOfDay(minute),
                style = MaterialTheme.typography.titleLarge,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            SmallStep(NightbellIcons.ChevronRight, "$label later") {
                onChange((minute + 30) % 1440)
            }
        }
    }
}

@Composable
private fun SmallStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NightbellColors.sheen(0.06f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = NightbellColors.TextSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}
