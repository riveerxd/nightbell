package me.river.nightbell.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii

/**
 * How long a destructive action has to be held before it happens.
 *
 * 1200ms, and both directions of that number were argued. The reference pattern
 * this is taken from uses 300 to 500, which is a hold that says "you meant to
 * touch this"; five seconds was asked for first, which is a hold that says "you
 * really meant it" and is long enough on a phone to make someone wonder whether
 * the press registered at all. 1200 is past the threshold where a hold reads as
 * deliberate rather than as a slow tap, and short enough that deleting four
 * monitors in a row is not a chore.
 *
 * One constant, so it can be moved after being felt rather than after being
 * discussed. Deliberately not the same number as [HOLD_TO_CONFIRM_MS], which is
 * the pull-to-refresh hold: that one is already preceded by a long deliberate
 * drag, so it can afford two seconds, and this one is a bare press on a button.
 */
const val HOLD_TO_DELETE_MS = 1_200

/**
 * A destructive button that only fires if it is held.
 *
 * Replaces the "are you sure?" step. A confirmation dialog punishes every correct
 * deletion for the sake of the rare wrong one, and it trains the hand to dismiss
 * it without reading, which is worse than no guard at all. A hold cannot be
 * completed by accident and needs nothing to read.
 *
 * Three things about it are deliberate and each one is a case that happens:
 *
 * **The clock is the frame clock, not an animation.** `animateTo` with a tween
 * would be the obvious way to fill the bar, and it would be wrong: Compose scales
 * animation durations by the platform's animator setting, so with "Remove
 * animations" turned on the tween completes on its first frame and the guard
 * disappears entirely on exactly the devices whose owners are most likely to
 * mis-tap. [withFrameNanos] is wall time and cannot be scaled away.
 *
 * **The fill survives reduced motion**, for the same reason it is not a
 * decoration. It is the only thing on screen that says how much longer to hold,
 * and the house rule about motion is about movement that explains nothing. This
 * explains the finger currently on the glass.
 *
 * **A screen reader gets a plain action.** Holding for over a second is not
 * operable for everyone, and on the paths this guards it is the only route to
 * deleting your own data. The semantics node carries an ordinary click action
 * labelled with what will happen, which accessibility services invoke directly
 * and which real touch input never reaches.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the label becomes when [text] will not fit on one line.
     *
     * The same escape [NightbellButton] has, and needed here for the same reason
     * plus a sharper one: at the 200 per cent font scale "Hold to delete this
     * monitor" came out as "Hold to delete this mo…", and a destructive control
     * that trails off is the last label in the app that should. A button is one
     * phrase for one action, and the honest way to lose words is deliberately.
     */
    shortText: String? = null,
    icon: ImageVector? = NightbellIcons.Trash,
    holdMs: Int = HOLD_TO_DELETE_MS,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(NightbellRadii.chip)
    val progress = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    // The handler can change between the press and the commit, and the gesture
    // detector is keyed on `enabled` alone so it does not restart mid-hold.
    val confirm by rememberUpdatedState(onConfirm)
    val alpha = if (enabled) 1f else 0.45f
    val rose = NightbellColors.Rose

    Row(
        modifier = modifier
            .clip(shape)
            .background(rose.copy(alpha = 0.16f * alpha))
            .drawBehind {
                val filled = size.width * progress.value
                if (filled <= 0f) return@drawBehind
                drawRoundRect(
                    color = rose.copy(alpha = 0.42f),
                    size = Size(filled, size.height),
                    cornerRadius = CornerRadius(0f),
                )
            }
            .border(BorderStroke(1.dp, rose.copy(alpha = 0.45f)), shape)
            .pointerInput(enabled, holdMs) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        var committed = false
                        coroutineScope {
                            val filling = launch {
                                val startedAt = withFrameNanos { it }
                                while (true) {
                                    val now = withFrameNanos { it }
                                    val elapsed = (now - startedAt) / 1_000_000f
                                    progress.snapTo((elapsed / holdMs).coerceIn(0f, 1f))
                                    if (elapsed >= holdMs.toFloat()) break
                                }
                                committed = true
                                // At the commit and nowhere else. A tick per frame
                                // would be a buzz, and a tick on press would say
                                // "done" a second before anything was.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                confirm()
                            }
                            // Returns on release *and* on cancellation, so a hold
                            // that turns into a scroll unwinds like a release.
                            tryAwaitRelease()
                            filling.cancel()
                        }
                        if (committed) {
                            progress.snapTo(0f)
                        } else {
                            // Springs back rather than snapping, so a deliberate
                            // release reads as the action retreating.
                            progress.animateTo(0f, spring(0.7f, Spring.StiffnessMedium))
                        }
                    },
                )
            }
            .semantics {
                // The whole phrase, whatever the button had room to print.
                contentDescription = text
                onClick(label = text) {
                    confirm()
                    true
                }
            }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = rose.copy(alpha = alpha),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
        }
        // Measured against the room actually left, so the full phrase comes back
        // on a tablet or in landscape rather than being lost for good once a
        // narrow layout has been seen.
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        BoxWithConstraints {
            val roomFor = constraints.maxWidth
            val fits = remember(text, style, roomFor, LocalDensity.current) {
                measurer.measure(AnnotatedString(text), style, softWrap = false)
                    .size.width <= roomFor
            }
            val shown = if (fits || shortText.isNullOrBlank()) text else shortText
            Text(
                text = shown,
                // Trimming is a layout concession and not a change of meaning, so
                // the whole phrase stays on the node. A screen reader still hears
                // what will be deleted.
                modifier = if (shown == text) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = shown }
                },
                style = style,
                color = rose.copy(alpha = alpha),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
