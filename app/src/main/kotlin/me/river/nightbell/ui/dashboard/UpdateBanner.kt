package me.river.nightbell.ui.dashboard

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import me.river.nightbell.data.update.UpdateInstaller
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.LocalNightbellMotion
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.update.UpdateActions
import me.river.nightbell.ui.update.megabytes

/**
 * "There is a newer Nightbell", as a modal over the dashboard.
 *
 * This was a card in the monitor grid until 3.6 and a floating notice after
 * that, and both failed the same way: pinned at the top it covered the app's own
 * controls, and anywhere else it read as one more card among cards. The comment
 * that used to live here refused a dialog outright on the grounds that a dialog
 * on launch is nagware. That objection was about a dialog whose only exit was
 * destructive, and it no longer applies, because the exits changed:
 *
 *  - **Nothing here is permanent.** The close, the scrim and the back gesture
 *    all mean "not now" and go through [AppUpdate.remindLater], quiet for a day
 *    and then asking again. Refusing a version for good is still possible, but
 *    only from the notification's own Ignore action, where it is a labelled
 *    choice rather than something a stray tap can do.
 *  - **It costs one tap to leave**, and leaving puts the dashboard back exactly
 *    as it was. Nagware is a dialog you have to fight; this is one you dismiss.
 *
 * What the modal buys, and why the interruption is worth it: the fleet verdict
 * and the header are not half-covered, they are behind it, and that is legible.
 * There is no state left where someone is looking at a control they cannot reach
 * and cannot see why.
 *
 * Sky rather than amber or rose. Every warm colour in this app already means
 * something about a monitor being unwell, and a new release is not a fault. Mint
 * is out for the same reason pointing the other way: it means a monitor is up.
 *
 * Whether it appears at all is [AppUpdate.bannerFor], not a condition here, so
 * the rules are a JVM test rather than something only a device can answer.
 */
@Composable
fun UpdateBanner(
    banner: AppUpdate.Banner,
    stage: UpdateInstaller.Stage,
    canRequestInstall: Boolean,
    onOpen: (String) -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Sized to the app's card margins, like every other dialog here.
        // Material's default dialog width is not a measurement this design uses.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BlurBehind()
        UpdateModalCard(
            banner = banner,
            stage = stage,
            canRequestInstall = canRequestInstall,
            onOpen = onOpen,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

/**
 * Dims the dashboard, and blurs it wherever the platform can afford to.
 *
 * `blurBehindRadius` is the real thing: the compositor blurs everything behind
 * this window, so the fleet banner and the cards go soft rather than merely
 * dark. It arrived in API 31, and the device can withdraw it at any moment for
 * battery saver or because the GPU cannot spare it, which is why the dim is set
 * unconditionally and the blur is only ever added on top. Below 31, or with
 * cross-window blur switched off, the dim is the whole effect and the modal
 * still reads as one.
 */
@Composable
private fun BlurBehind() {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    LaunchedEffect(window) {
        // The version check guards the call directly rather than through a
        // boolean held above it. Lint cannot follow a `val` into the branch it
        // protects and fails the release build on four counts of NewApi, and it
        // is right to: read this in a year and the guard should be next to the
        // thing it guards.
        //
        // The dim is lighter when the blur lands and heavier when it does not.
        // Separation has to come from somewhere, and piling a heavy scrim on a
        // blur paints out the dashboard the blur went to the trouble of
        // softening, which was the point of blurring rather than covering.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
        ) {
            window.setDimAmount(SCRIM_OVER_BLUR)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val params = window.attributes
            params.blurBehindRadius = with(density) { BLUR.roundToPx() }
            window.attributes = params
        } else {
            window.setDimAmount(SCRIM_ALONE)
        }
    }
}

@Composable
private fun UpdateModalCard(
    banner: AppUpdate.Banner,
    stage: UpdateInstaller.Stage,
    canRequestInstall: Boolean,
    onOpen: (String) -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One authored moment: the card arrives from slightly small and slightly
    // transparent over 180 ms. It is replaying the thing that just happened,
    // which is a surface appearing, and it does nothing on the way out because
    // the window is gone by then.
    val motion = LocalNightbellMotion.current
    var shown by remember { mutableStateOf(!motion.enabled) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "updateModal")

    GlassCard(
        modifier
            .testTag("update-banner")
            .widthIn(max = 420.dp)
            .padding(horizontal = 20.dp)
            .graphicsLayer {
                alpha = enter
                val scale = 0.96f + 0.04f * enter
                scaleX = scale
                scaleY = scale
            },
        accent = NightbellColors.Sky,
        contentPadding = 20.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    // The number in Sky, the sentence around it in the ordinary
                    // text colour. Settings paints its "Latest" value the same
                    // way, and the two are one fact stated in two places.
                    text = buildAnnotatedString {
                        append("Nightbell ")
                        withStyle(SpanStyle(color = NightbellColors.Sky)) {
                            append(banner.latestVersion)
                        }
                        append(" is available")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    // Both numbers, because "an update is available" invites the
                    // question this answers in the same breath. The size joins
                    // them when it is known, because it is the one thing worth
                    // checking before starting a transfer on a phone.
                    text = buildString {
                        append("You are on ${banner.installedVersion}")
                        if (banner.apkSize > 0L) append(" · ${megabytes(banner.apkSize)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = NightbellIcons.Close,
                onClick = onDismiss,
                // Says what it does, and what it does is no longer permanent, so
                // it no longer has to warn anyone. The scrim and the back gesture
                // mean this same thing.
                contentDescription = "Not now",
                accent = NightbellColors.TextSecondary,
                size = 40.dp,
            )
        }
        Spacer(Modifier.height(16.dp))
        UpdateActions(
            version = banner.latestVersion,
            releaseUrl = banner.url,
            apkUrl = banner.apkUrl,
            stage = stage,
            canRequestInstall = canRequestInstall,
            onWhatsNew = onOpen,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val SCRIM_OVER_BLUR = 0.45f

/** Deeper than the other dialogs use: with no blur it is doing all the work. */
private const val SCRIM_ALONE = 0.78f

private val BLUR = 28.dp
