package me.river.pulse.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.river.pulse.data.Pulse
import me.river.pulse.domain.PagerReadiness
import me.river.pulse.domain.PagerReadiness.Requirement
import me.river.pulse.ui.components.ButtonTone
import me.river.pulse.ui.components.GlassCard
import me.river.pulse.ui.components.PulseButton
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.PulseColors

/**
 * The one screen that stands between a fresh install and a pager that works.
 *
 * ### Why it cannot be a single button
 * Of the four things URGENT needs, exactly two can be *asked* for from inside an
 * app: the notifications runtime permission and the Doze exemption, both of which
 * show a system dialog in place. The other two — full-screen notifications and Do
 * Not Disturb access — are "special app access" toggles, and Android exposes no
 * API to request them. An app may only open the settings page that holds the
 * toggle. There is no grant-everything call to write, so this screen does the next
 * best thing: it asks for the two it can, deep-links straight to the exact toggle
 * for the two it cannot, and re-checks on every resume so it advances by itself
 * as each one flips.
 *
 * That turns "find four toggles across three settings sections" into four taps
 * and three back-presses, which is as short as the platform allows.
 */
/** Stable handle for the "get me past this" button, whatever its label says. */
const val TAG_DISMISS: String = "pager-setup-dismiss"

@Composable
fun PagerSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = Pulse.require()
    var state by remember { mutableStateOf(readState(context)) }

    // Re-read on resume. Three of the four grants are made in another app, so
    // coming back is the only signal that anything changed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = readState(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { state = readState(context) }

    fun grant(requirement: Requirement) {
        when (requirement) {
            Requirement.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    open(context, graph.alerts.channelSettingsIntent(graph.store.snapshot.value.settings.defaultAlert))
                }

            Requirement.BATTERY_EXEMPTION -> open(
                context,
                graph.limits.batteryExemptionRequestIntent(),
                fallback = graph.limits.batterySettingsIntent(),
            )

            Requirement.FULL_SCREEN -> open(
                context,
                graph.alerts.fullScreenIntentSettingsIntent(),
                fallback = graph.limits.appDetailsIntent(),
            )

            Requirement.DND_BYPASS -> open(
                context,
                graph.alerts.dndAccessIntent(),
                fallback = graph.limits.appDetailsIntent(),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            // The app draws edge to edge, so without this the first line sits
            // under the clock.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Text(
            "Before we start",
            style = MaterialTheme.typography.labelSmall,
            color = PulseColors.Rose,
            letterSpacing = 2.4.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Let Pulse wake you when something breaks",
            style = MaterialTheme.typography.displayMedium,
            color = PulseColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Android keeps two of these behind its own settings screens — no app " +
                "can switch them on for you. Tapping a row jumps straight to the " +
                "toggle, and this list updates itself when you come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulseColors.TextTertiary,
        )

        Spacer(Modifier.height(22.dp))
        Text(
            "${state.grantedCount} of ${state.total} ready",
            style = MaterialTheme.typography.titleMedium,
            color = if (state.allGranted) PulseColors.Mint else PulseColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        Requirement.entries.forEach { requirement ->
            RequirementRow(
                requirement = requirement,
                granted = state.granted(requirement),
                onClick = { grant(requirement) },
            )
            Spacer(Modifier.height(10.dp))
        }

        if (!state.audible) {
            Spacer(Modifier.height(4.dp))
            GlassCard(accent = PulseColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        PulseIcons.VolumeOff,
                        contentDescription = null,
                        tint = PulseColors.Amber,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Your ring volume is at zero",
                            style = MaterialTheme.typography.titleMedium,
                            color = PulseColors.TextPrimary,
                        )
                        Text(
                            "Pages will still vibrate, but they will not make a sound. " +
                                "This is not a permission — nothing but the volume keys fixes it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulseColors.TextTertiary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        val next = state.next
        if (next != null) {
            PulseButton(
                text = "Set up ${label(next).lowercase()}",
                onClick = { grant(next) },
                modifier = Modifier.fillMaxWidth(),
                accent = PulseColors.Rose,
                accentEnd = PulseColors.Coral,
            )
            Spacer(Modifier.height(10.dp))
            PulseButton(
                text = if (state.canPageAtAll) "Continue anyway" else "Skip for now",
                onClick = onDone,
                // Tagged because the label depends on what is already granted, so
                // a test cannot address it by text.
                modifier = Modifier.fillMaxWidth().testTag(TAG_DISMISS),
                tone = ButtonTone.Ghost,
            )
        } else {
            PulseButton(
                text = "All set — open Pulse",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag(TAG_DISMISS),
                accent = PulseColors.Mint,
                accentEnd = PulseColors.Mint,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "You can change any of this later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = PulseColors.TextTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RequirementRow(requirement: Requirement, granted: Boolean, onClick: () -> Unit) {
    GlassCard(
        accent = if (granted) Color.Transparent else PulseColors.Rose,
        onClick = if (granted) null else onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (granted) PulseIcons.Check else PulseIcons.Warning,
                    contentDescription = null,
                    tint = if (granted) PulseColors.Mint else PulseColors.Rose,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label(requirement),
                    style = MaterialTheme.typography.titleMedium,
                    color = PulseColors.TextPrimary,
                )
                Text(
                    if (granted) grantedBlurb(requirement) else blurb(requirement),
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (requirement.leavesTheApp) "Settings" else "Allow",
                    style = MaterialTheme.typography.labelLarge,
                    color = PulseColors.Rose,
                )
            }
        }
    }
}

private fun label(requirement: Requirement): String = when (requirement) {
    Requirement.NOTIFICATIONS -> "Notifications"
    Requirement.BATTERY_EXEMPTION -> "Unrestricted battery"
    Requirement.FULL_SCREEN -> "Full-screen alerts"
    Requirement.DND_BYPASS -> "Get through Do Not Disturb"
}

private fun blurb(requirement: Requirement): String = when (requirement) {
    Requirement.NOTIFICATIONS ->
        "Required. Without it Pulse cannot tell you anything at all."
    Requirement.BATTERY_EXEMPTION ->
        "Lets checks keep their schedule, and lets an urgent page keep repeating " +
            "while your phone is in your pocket."
    Requirement.FULL_SCREEN ->
        "Lets an urgent page wake the screen instead of waiting on the lockscreen. " +
            "Android only allows this from its own settings page."
    Requirement.DND_BYPASS ->
        "Without it, Do Not Disturb and Bedtime mode silence urgent pages " +
            "completely — the exact times you would want one."
}

private fun grantedBlurb(requirement: Requirement): String = when (requirement) {
    Requirement.NOTIFICATIONS -> "Allowed."
    Requirement.BATTERY_EXEMPTION -> "Unrestricted. Checks keep their cadence."
    Requirement.FULL_SCREEN -> "An urgent page can wake the screen."
    Requirement.DND_BYPASS -> "Urgent pages get through Do Not Disturb."
}

private fun readState(context: Context): PagerReadiness.State {
    val graph = Pulse.install(context)
    val settings = graph.store.snapshot.value.settings
    return PagerReadiness.State(
        notifications = graph.alerts.hasNotificationPermission(),
        batteryExempt = graph.limits.isIgnoringBatteryOptimizations(),
        fullScreen = graph.alerts.canUseFullScreenIntent(),
        dndBypass = graph.alerts.urgentBypassesDnd(),
        audible = graph.alarm.alarmStreamAudible(settings.urgentRespectsRingerMode),
    )
}

/**
 * Opens a settings screen, falling back when an OEM build does not answer the
 * documented action. A dead row is worse than a slightly less specific one.
 */
private fun open(context: Context, intent: Intent, fallback: Intent? = null) {
    val launch = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(launch) }.onFailure {
        fallback?.let { alt -> runCatching { context.startActivity(alt) } }
    }
}
