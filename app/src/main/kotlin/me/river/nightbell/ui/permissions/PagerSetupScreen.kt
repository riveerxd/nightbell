package me.river.nightbell.ui.permissions

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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.PagerReadiness
import me.river.nightbell.domain.PagerReadiness.Requirement
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

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

/** The "stop showing me this" button, which exists only on the launch gate. */
const val TAG_SILENCE: String = "pager-setup-silence"

/**
 * Where the screen was entered from, which decides how it is left.
 *
 * The gate stands in front of the dashboard and has nothing behind it, so it
 * offers to stop appearing. Reached from Settings there is a screen behind it
 * and the user came looking on purpose, so it offers a way back instead: an
 * "are you sure you want this" button on a screen somebody navigated to is a
 * question nobody asked.
 */
enum class PagerSetupMode { GATE, REVISIT }

@Composable
fun PagerSetupScreen(
    mode: PagerSetupMode,
    onDone: () -> Unit,
    onSilence: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = Nightbell.require()
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
        if (mode == PagerSetupMode.REVISIT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    icon = NightbellIcons.ArrowLeft,
                    onClick = onDone,
                    contentDescription = "Back",
                    accent = NightbellColors.TextSecondary,
                    size = 38.dp,
                )
                Spacer(Modifier.width(14.dp))
                // Same row, same size, same colour as the Settings header this
                // screen is now reached from. Arriving at a screen whose title
                // sits somewhere else and in another weight reads as a different
                // app, and the eyebrow-over-headline shape below belongs to a
                // first run, not to a page somebody navigated to.
                Text(
                    text = "Alert permissions",
                    style = MaterialTheme.typography.displayMedium,
                    color = NightbellColors.TextPrimary,
                )
            }
        } else {
            Text(
                "Before we start",
                style = MaterialTheme.typography.labelSmall,
                color = NightbellColors.Aqua,
                letterSpacing = 2.4.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Let Nightbell wake you when something breaks",
                style = MaterialTheme.typography.displayMedium,
                color = NightbellColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Android keeps two of these behind its own settings screens — no app " +
                "can switch them on for you. Tapping a row jumps straight to the " +
                "toggle, and this list updates itself when you come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = NightbellColors.TextTertiary,
        )

        Spacer(Modifier.height(22.dp))
        Text(
            "${state.grantedCount} of ${state.total} ready",
            style = MaterialTheme.typography.titleMedium,
            color = when {
                state.allGranted -> NightbellColors.Mint
                !state.canPageAtAll -> NightbellColors.Rose
                else -> NightbellColors.Amber
            },
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
            GlassCard(accent = NightbellColors.Amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        NightbellIcons.VolumeOff,
                        contentDescription = null,
                        tint = NightbellColors.Amber,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Your ring volume is at zero",
                            style = MaterialTheme.typography.titleMedium,
                            color = NightbellColors.TextPrimary,
                        )
                        Text(
                            "Pages will still vibrate, but they will not make a sound. " +
                                "This is not a permission — nothing but the volume keys fixes it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        val next = state.next
        if (next != null) {
            NightbellButton(
                text = actionLabel(next),
                onClick = { grant(next) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            NightbellButton(
                text = when {
                    mode == PagerSetupMode.REVISIT -> "Done"
                    state.canPageAtAll -> "Continue anyway"
                    else -> "Skip for now"
                },
                onClick = onDone,
                // Tagged because the label depends on what is already granted, so
                // a test cannot address it by text.
                modifier = Modifier.fillMaxWidth().testTag(TAG_DISMISS),
                tone = ButtonTone.Ghost,
            )
        } else {
            NightbellButton(
                text = if (mode == PagerSetupMode.REVISIT) "Done" else "All set, open Nightbell",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().testTag(TAG_DISMISS),
                accent = NightbellColors.Mint,
                accentEnd = NightbellColors.Mint,
            )
        }

        if (mode == PagerSetupMode.GATE) {
            // Quieter than the two above it, and deliberately not a third
            // NightbellButton: this is the one control here that gives something
            // up, and it should not read as an equal third way out. It still
            // takes a full row so the tap target clears 48dp.
            Spacer(Modifier.height(6.dp))
            Text(
                "Don't ask again",
                style = MaterialTheme.typography.bodyMedium,
                color = NightbellColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_SILENCE)
                    .clickable(onClick = onSilence)
                    .padding(vertical = 14.dp),
            )
        }

        // Only worth saying to somebody who has not been there. Told to a user
        // who arrived from that exact screen it reads as the app having lost
        // track of where they are.
        if (mode == PagerSetupMode.GATE) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Settings has all of this again, under Alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * What a row's state costs, in the palette's own terms.
 *
 * Rose is down and amber is degraded everywhere else in this app, and
 * [Requirement.essential] already draws exactly that line: without notifications
 * nothing is delivered at all, and the other three each make the page worse in
 * one specific, survivable way. This screen used to paint all four rose, which
 * spent the colour that means "your service is down" on a Do Not Disturb toggle.
 */
@Composable
private fun toneOf(requirement: Requirement, granted: Boolean): Color = when {
    granted -> NightbellColors.Mint
    requirement.essential -> NightbellColors.Rose
    else -> NightbellColors.Amber
}

@Composable
private fun RequirementRow(requirement: Requirement, granted: Boolean, onClick: () -> Unit) {
    GlassCard(
        accent = if (granted) Color.Transparent else toneOf(requirement, granted),
        onClick = if (granted) null else onClick,
        // The tick and the trailing "Allow" both carry the state visually and
        // neither reaches TalkBack: the icon is decorative and the word only
        // exists on the rows that are not done yet, so a granted row and a
        // pending one differed by a blurb nobody would read as a status.
        modifier = Modifier.semantics {
            stateDescription = if (granted) "Granted" else "Not granted"
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (granted) NightbellIcons.Check else NightbellIcons.Warning,
                    contentDescription = null,
                    tint = toneOf(requirement, granted),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label(requirement),
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    if (granted) grantedBlurb(requirement) else blurb(requirement),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (requirement.leavesTheApp) "Settings" else "Allow",
                    style = MaterialTheme.typography.labelLarge,
                    color = toneOf(requirement, granted),
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

/**
 * What the primary button promises.
 *
 * Written out per requirement rather than composed from [label]: lowercasing the
 * row titles produced "Set up get through do not disturb", and the wording also
 * has to tell the user whether they are about to get a dialog or be sent to
 * Settings — which is the difference between one tap and a round trip.
 */
private fun actionLabel(requirement: Requirement): String = when (requirement) {
    Requirement.NOTIFICATIONS -> "Allow notifications"
    Requirement.BATTERY_EXEMPTION -> "Allow unrestricted battery"
    Requirement.FULL_SCREEN -> "Open full-screen alert settings"
    Requirement.DND_BYPASS -> "Open Do Not Disturb access"
}

private fun blurb(requirement: Requirement): String = when (requirement) {
    Requirement.NOTIFICATIONS ->
        "Required. Without it Nightbell cannot tell you anything at all."
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

/**
 * One live reading of the four grants, plus whether the phone can make a sound.
 *
 * Public because three screens need the same answer: this one, the launch gate's
 * decision in `NightbellApp`, and the Alerts tab in Settings. It was written
 * twice before that third caller existed, and two copies of a platform read is
 * one copy too many for something that decides whether a page can wake anybody.
 *
 * Blocking, and called from composition on purpose: every one of these is a
 * synchronous lookup against a system service, and the alternative is a screen
 * that renders "0 of 4 ready" for a frame before correcting itself.
 */
fun pagerReadiness(context: Context): PagerReadiness.State = readState(context)

private fun readState(context: Context): PagerReadiness.State {
    val graph = Nightbell.install(context)
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
