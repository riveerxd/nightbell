package me.river.nightbell.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.nightbell.data.Nightbell
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import me.river.nightbell.data.update.UpdateInstaller
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/**
 * The two things a person can do about a new version, wherever they meet one.
 *
 * One composable and not two, because the dashboard popup and the Settings card
 * are the same offer made in two places, and the fastest way to end up with two
 * different offers is to write them twice. Whatever is true here is true in both.
 *
 * Read the notes, or take the release. Nothing else: "remind me later" is what
 * closing the popup already means, and a third button would make the reader
 * choose between three things when there are two.
 */
@Composable
fun UpdateActions(
    version: String,
    releaseUrl: String,
    apkUrl: String,
    stage: UpdateInstaller.Stage,
    canRequestInstall: Boolean,
    onWhatsNew: (String) -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = stage is UpdateInstaller.Stage.Downloading ||
        stage is UpdateInstaller.Stage.Checking ||
        stage is UpdateInstaller.Stage.Installing

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightbellButton(
                text = "What's new",
                onClick = { onWhatsNew(releaseUrl) },
                icon = NightbellIcons.Link,
                tone = ButtonTone.Secondary,
                accent = NightbellColors.Sky,
                modifier = Modifier.weight(1f).testTag("update-whats-new"),
            )
            if (apkUrl.isNotBlank()) {
                if (canRequestInstall) {
                    NightbellButton(
                        // The label is the same word through the whole transfer.
                        // A button whose text changes under the finger is a button
                        // people tap twice; the numbers move in the line below it,
                        // which is where numbers belong.
                        text = if (busy) stageLabel(stage) else "Install",
                        onClick = onInstall,
                        icon = NightbellIcons.Download,
                        accent = NightbellColors.Sky,
                        accentEnd = NightbellColors.Indigo,
                        loading = busy,
                        modifier = Modifier.weight(1f).testTag("update-install"),
                    )
                } else {
                    // Named as the round trip it is. Android will not let this app
                    // install anything until the user turns it on in Settings, and
                    // a button called "Install" that opens Settings instead is the
                    // kind of lie the pager setup screen already refuses to tell.
                    NightbellButton(
                        text = "Settings",
                        onClick = onOpenInstallSettings,
                        icon = NightbellIcons.Sliders,
                        tone = ButtonTone.Secondary,
                        accent = NightbellColors.Sky,
                        modifier = Modifier.weight(1f).testTag("update-install-settings"),
                    )
                }
            }
        }

        if (apkUrl.isNotBlank() && !canRequestInstall) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Android has to allow Nightbell to install apps before it can " +
                    "hand you $version. Turn it on, then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }

        AnimatedVisibility(
            visible = busy,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                TransferBar(stage)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = transferCaption(stage),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    modifier = Modifier.testTag("update-progress"),
                )
            }
        }

        val failure = (stage as? UpdateInstaller.Stage.Failed)?.reason
        AnimatedVisibility(
            visible = failure != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.padding(top = 10.dp).testTag("update-failure"),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = NightbellIcons.Warning,
                    contentDescription = null,
                    tint = NightbellColors.Rose,
                    modifier = Modifier.padding(top = 2.dp).height(13.dp).width(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = failure.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.Rose,
                )
            }
        }
    }
}

/**
 * How far the transfer has got, as a line rather than a number in the button.
 *
 * Determinate whenever the server sent a length, which GitHub and F-Droid both
 * do. When it did not, the line fills to a third and stops: a bar that pretends
 * to know how far along it is would be the only fake number in this app.
 */
@Composable
private fun TransferBar(stage: UpdateInstaller.Stage) {
    val target = when (stage) {
        is UpdateInstaller.Stage.Downloading ->
            if (stage.total > 0L) stage.fraction else 0.33f

        UpdateInstaller.Stage.Checking -> 1f
        UpdateInstaller.Stage.Installing -> 1f
        else -> 0f
    }
    val filled by animateFloatAsState(target, tween(200), label = "transfer")
    val track = NightbellColors.sheen(0.10f)
    val fill = NightbellColors.Sky
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clearAndSetSemantics { contentDescription = transferCaption(stage) },
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        if (filled > 0f) {
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = Size((size.width * filled).coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

private fun stageLabel(stage: UpdateInstaller.Stage): String = when (stage) {
    is UpdateInstaller.Stage.Downloading -> "Downloading"
    UpdateInstaller.Stage.Checking -> "Checking"
    UpdateInstaller.Stage.Installing -> "Installing"
    else -> "Install"
}

private fun transferCaption(stage: UpdateInstaller.Stage): String = when (stage) {
    is UpdateInstaller.Stage.Downloading ->
        if (stage.total > 0L) {
            "${megabytes(stage.received)} of ${megabytes(stage.total)}"
        } else {
            megabytes(stage.received)
        }

    UpdateInstaller.Stage.Checking -> "Checking the signature against your copy"
    UpdateInstaller.Stage.Installing -> "Android is asking whether to install it"
    else -> ""
}

/** One decimal place, which is the resolution a download bar can justify. */
fun megabytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1_000_000L -> "${(bytes / 1_000L).coerceAtLeast(1L)} kB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}

/**
 * The installer's live state, plus the two calls that drive it.
 *
 * A composable rather than something on each view model, because the dashboard
 * and Settings would otherwise carry the same four members twice, and because
 * one of them cannot live on a view model at all: whether Android will let this
 * app install anything is a permission the user grants in Settings, so it has to
 * be re-read every time the app comes back to the foreground rather than read
 * once when a screen was constructed.
 */
@Immutable
class UpdateInstall internal constructor(
    val stage: UpdateInstaller.Stage,
    val canRequestInstall: Boolean,
    private val installer: UpdateInstaller,
) {
    fun start(apkUrl: String, version: String, sizeBytes: Long) {
        installer.start(apkUrl, version, sizeBytes)
    }

    fun openSettings() {
        installer.openInstallSettings()
    }
}

@Composable
fun rememberUpdateInstall(): UpdateInstall {
    val installer = Nightbell.require().installer
    val stage by installer.stage.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(installer.canRequestInstall()) }
    LifecycleResumeEffect(installer) {
        allowed = installer.canRequestInstall()
        onPauseOrDispose { }
    }
    return UpdateInstall(stage, allowed, installer)
}
