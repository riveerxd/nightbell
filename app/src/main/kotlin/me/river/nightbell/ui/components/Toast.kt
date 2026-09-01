package me.river.nightbell.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.softShadow

/**
 * What a toast is claiming happened.
 *
 * Three of these, and the third one is the reason this file exists. Every
 * confirmation in the app used to come back as one capsule with one mint dot, so
 * "imported 12 monitors" and "couldn't read that file" arrived in the same
 * colour, the same shape and the same two seconds. A failure that looks like a
 * success is worse than no message, because the user walks away believing the
 * thing worked.
 *
 * The split is about what the app is telling you, not about how loud it wants to
 * be. [SUCCESS] is "the thing you asked for happened". [WARNING] is "it happened
 * and you now have less cover than you did", which is where every pause, mute,
 * silence and delete lands, plus the answers that report having done nothing.
 * [ERROR] is "it did not happen".
 */
enum class ToastKind { SUCCESS, WARNING, ERROR }

/**
 * One transient message.
 *
 * @property serial what separates two identical sentences in a row. The host
 *  restarts its dwell when this value changes, and with a plain string a second
 *  tap that produced the same words was equal to the first, so the capsule kept
 *  running its original timer and the second tap looked like it had done nothing.
 */
@Immutable
data class ToastMessage(
    val text: String,
    val kind: ToastKind = ToastKind.SUCCESS,
    val serial: Long = System.nanoTime(),
) {
    companion object {
        fun success(text: String) = ToastMessage(text, ToastKind.SUCCESS)
        fun warning(text: String) = ToastMessage(text, ToastKind.WARNING)
        fun error(text: String) = ToastMessage(text, ToastKind.ERROR)
    }
}

/**
 * How long a message stays up, by what it is saying.
 *
 * Not one number, because the three are not read the same way. A confirmation is
 * glanced at and dismissed by the next tap. A failure has to be read, and the
 * sentence that reports one is usually longer, so five seconds is roughly the
 * time it takes to look up from a button and finish it.
 */
internal fun toastDwellMs(kind: ToastKind): Long = when (kind) {
    ToastKind.SUCCESS -> 2_400L
    ToastKind.WARNING -> 3_600L
    ToastKind.ERROR -> 5_000L
}

/**
 * Status colour for [kind], off the dark anchors in both schemes.
 *
 * The toast surface is dark whichever scheme is in force, see
 * [me.river.nightbell.ui.theme.NightbellColorScheme.ToastSurface], and the light
 * scheme's mint and amber were darkened specifically to pass against white. On
 * this surface they are the wrong pair: `#07834B` on `#14161B` is under 2:1.
 */
internal fun toastAccent(kind: ToastKind): Color = when (kind) {
    ToastKind.SUCCESS -> Color(0xFF2FD98A)
    ToastKind.WARNING -> Color(0xFFFFB020)
    ToastKind.ERROR -> Color(0xFFFF4D57)
}

internal fun toastIcon(kind: ToastKind): ImageVector = when (kind) {
    ToastKind.SUCCESS -> NightbellIcons.Check
    ToastKind.WARNING -> NightbellIcons.Warning
    ToastKind.ERROR -> NightbellIcons.AlertCircle
}

/** Spoken first, so the kind is not carried by colour alone. */
internal fun toastRole(kind: ToastKind): String = when (kind) {
    ToastKind.SUCCESS -> "Done"
    ToastKind.WARNING -> "Warning"
    ToastKind.ERROR -> "Failed"
}

/** White in both schemes: this surface is dark in both. */
private val ToastText = Color(0xFFF7F8FA)

/**
 * Four, and it is a real number rather than a shrug.
 *
 * Two was the first answer and it was wrong in the way that matters: at the 200
 * per cent font scale Android 14 offers, "Notifications are blocked, enable them
 * in system settings" arrived as "Notifications are blocked, enable them in
 * system ...", which names a problem and withholds the fix. Four clears the
 * longest string the app can produce at that scale with a line to spare, and at
 * ordinary sizes nothing comes close to reaching it.
 */
private const val TOAST_MAX_LINES = 4

/**
 * Parks the toast over the wordmark row and nothing else.
 *
 * The fleet verdict lives in the banner below, which is the line someone opened
 * Nightbell to read, so the cheap row to cover is the app's own name.
 */
private val TOAST_TOP_GAP = 10.dp

/**
 * The transient message layer. One at a time, top of the screen, tap to dismiss.
 *
 * Owns the dwell and the transitions, all three of which are real: arriving,
 * leaving, and being replaced by the next message. [ToastCapsule] owns only its
 * own drawing, which is what lets a design test photograph it without waiting five
 * seconds for it to time out.
 */
@Composable
fun ToastHost(
    message: ToastMessage?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(toastDwellMs(message.kind))
        onDismissed()
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    // 12dp, and the number is a compromise that was asked for in both directions.
    // The original fell its own full height, about 44dp from behind the status
    // bar, which arrives like a banner rather than like an answer to a tap. Taking
    // the travel to zero and leaving scale and opacity to carry it went too far
    // the other way: a 12 per cent scale over a fifth of a second, on a pill this
    // small, gives the eye nothing to catch and reads as having simply appeared.
    // Twelve is enough to see and not enough to announce.
    val travel = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedContent(
        targetState = message,
        // Keyed on the serial, so two different sentences are two different
        // states. This is the half that was missing outright: with
        // `AnimatedVisibility` on `message != null`, a second message arriving
        // while the first was still up left `visible` true from start to finish,
        // so the words were swapped underneath a surface that never moved. Pause a
        // monitor and resume it, two taps most people do in a row, and neither
        // toast animated at all.
        contentKey = { it?.serial },
        transitionSpec = {
            // Underdamped on the way in: it settles a shade past rest and comes
            // back, which is what makes a short travel legible as movement rather
            // than as a jump. Straight tweens on the way out, faster than in,
            // because a message on its way out has already been read.
            val enter = slideInVertically(spring(0.62f, Spring.StiffnessMediumLow)) { -travel } +
                scaleIn(
                    animationSpec = spring(0.62f, Spring.StiffnessMediumLow),
                    initialScale = 0.90f,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                ) +
                fadeIn(tween(200))
            // One message giving way to another gets a shorter fade than one
            // simply leaving. Both surfaces are in the same place while they
            // cross, and at 170ms the two sentences are legible on top of each
            // other for long enough to read as a rendering fault. A message with
            // nothing following it has the space to leave more slowly.
            val replaced = initialState != null && targetState != null
            val exit = slideOutVertically(tween(200)) { -travel } +
                scaleOut(tween(200), 0.95f) +
                fadeOut(tween(if (replaced) 90 else 170))
            // `clip = false` so the outgoing pill is not cut off by the incoming
            // one's width while the two overlap, and a spring on the size so a
            // longer sentence replacing a shorter one grows rather than snaps.
            enter togetherWith exit using SizeTransform(clip = false) { _, _ ->
                spring(0.9f, Spring.StiffnessMediumLow)
            }
        },
        contentAlignment = Alignment.TopCenter,
        label = "toast",
        modifier = modifier
            .zIndex(Float.MAX_VALUE)
            .padding(top = topInset + TOAST_TOP_GAP, start = 16.dp, end = 16.dp),
    ) { current ->
        // The empty branch is what the slot looks like with nothing to say, and it
        // has to occupy no space: this sits over the wordmark row, and a box with
        // height here would push the dashboard down every time a toast left.
        if (current != null) ToastCapsule(current, onDismissed) else Spacer(Modifier)
    }
}

/**
 * The capsule itself: an opaque pill, lifted hard off whatever it covers, rimmed
 * and marked in the status colour.
 *
 * The shadow is deliberately twice a card's. This thing floats over glass panes
 * and charts, and without a real pool of dark under it the eye reads it as part
 * of what it happens to be sitting on, which is the whole original complaint.
 * Not three times, which was tried: [softShadow] stacks five translucent rects,
 * and that far up their steps are visible over off-white as a grey slab with an
 * edge.
 *
 * The glyph is drawn in the accent and stands on the surface. It sat inside a
 * filled disc of the accent for a while, with the glyph knocked out of it, and
 * that version was louder but it was also the only thing on the screen shaped
 * like that: at the 200 per cent font scale the pill grows to three lines and the
 * disc ends up stranded in the left-hand curve, next to text rather than in front
 * of it. One coloured mark and one coloured rim say the same thing without
 * inventing a component.
 */
@Composable
internal fun ToastCapsule(message: ToastMessage, onDismissed: () -> Unit = {}) {
    val accent = toastAccent(message.kind)
    // 22dp and not `RoundedCornerShape(100)`, which is a percentage of the
    // shorter side. At one line the two are the same thing, a true pill: 12dp of
    // padding either side of a 20dp line is 44dp tall and half of that is 22. The
    // percentage version only diverges when the text wraps, and there it turns
    // into a 60dp lozenge whose curves push the glyph away from the words it
    // belongs to. A fixed radius stays a pill at the size it is normally read at
    // and becomes an ordinary rounded rectangle when it has to grow.
    val shape = RoundedCornerShape(22.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .softShadow(corner = 100.dp, radius = 26.dp, strength = 2.1f)
            .clip(shape)
            .background(NightbellColors.ToastSurface)
            .border(1.dp, accent.copy(alpha = 0.40f), shape)
            // Tap anywhere to take it down, with no ripple: it is a message, not
            // a button, and a control that lights up invites a second look at
            // something already on its way out.
            .clickable(interactionSource = interaction, indication = null, onClick = onDismissed)
            .padding(start = 16.dp, end = 18.dp, top = 13.dp, bottom = 13.dp)
            .semantics { contentDescription = "${toastRole(message.kind)}: ${message.text}" },
        // Centred against the whole text block, not pinned to the first line.
        // Pinned was tried and it is the thing that made a wrapped message look
        // broken: the mark sits in the top left with a growing pool of nothing
        // under it, and the sentence reads as though it has been pushed off the
        // side of its own container.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = toastIcon(message.kind),
            contentDescription = null,
            tint = accent,
            // Sized in sp rather than dp, so it grows with the sentence beside
            // it. At a fixed 19dp the glyph stayed the same while 200 per cent
            // text doubled around it, and a mark that small against type that
            // large reads as a stray dot rather than as the thing saying which
            // of the three kinds this is.
            modifier = Modifier.size(with(LocalDensity.current) { 19.sp.toDp() }),
        )
        Text(
            text = message.text,
            // `titleMedium` is 15.5 on 20, which is right for the button labels
            // and chip text it was cut for and too tight for a sentence that
            // wraps: two lines of it close up into a block. This is the same face
            // and size with room to breathe.
            style = MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp),
            color = ToastText,
            maxLines = TOAST_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 13.dp),
        )
    }
}
