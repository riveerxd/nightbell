package me.river.nightbell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.sin
import me.river.nightbell.ui.components.NightbellMark
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.rememberSystemAnimationsEnabled

/**
 * The cold-start animation: the bell arrives, rings, the heartbeat carves through
 * it, and the word arrives beside it.
 *
 * This is the promo's opening beat rebuilt in Compose rather than a video baked
 * into the APK. Same geometry, same order, same durations, and it costs a few
 * hundred bytes instead of a few megabytes. It also means the mark it draws is
 * [NightbellMark], the one definition every other copy is checked against, so the
 * splash cannot drift from the icon it is introducing.
 *
 * ## It does not delay anything
 *
 * The app is composed underneath this from the first frame and keeps loading
 * while it plays, so the splash covers work that was happening anyway rather than
 * adding a wait. If the store resolves early the animation still finishes, which
 * is the one deliberate cost: about 2.8 s, of which the last 0.7 s is the finished
 * lockup simply sitting there. That hold is the point rather than padding, because
 * an opening that dissolves the instant it resolves reads as a glitch. It is still
 * a real tax on an app people open to check one thing, which is why it runs on
 * cold start only, and why the whole thing is skipped outright when the system
 * says animations are off.
 *
 * ## Timing
 *
 * One [Animatable] from 0 to 1 across [TOTAL_MS], with each beat reading its own
 * window out of it. One clock rather than five means the beats cannot drift apart,
 * and re-timing the sequence is editing the table below rather than chasing
 * delays through nested coroutines.
 */
private const val TOTAL_MS = 2800

private const val ARRIVE_FROM = 0.00f
private const val ARRIVE_TO = 0.14f    //    0 -  390 ms  bell fades and scales in
private const val RING_FROM = 0.11f    //  310 - 1290 ms  the strike, and the settle
private const val RING_TO = 0.46f
private const val WORD_FROM = 0.30f    //  840 - 1570 ms  the word wipes in
private const val WORD_TO = 0.56f
private const val LEAVE_FROM = 0.82f   // 2300 - 2800 ms  fade to the app

/**
 * Two full swings, not two and a half. At 1.65 s the extra half read as a wobble
 * because each one was over in under 200 ms; with the longer window the swings
 * are slow enough to see individually, and three of them starts to look like a
 * pendulum rather than a bell that was struck once.
 */
private const val SWINGS = 2.0f

/** Where in the mark's own box the crown sits, as a fraction of its height. */
private const val CROWN_PIVOT = 0.09f

/** Degrees of swing at the first strike, before the damping takes it down. */
private const val SWING_DEGREES = 11f

/** Progress of `t` through a window, clamped, as a plain 0 to 1 ramp. */
private fun window(t: Float, from: Float, to: Float): Float =
    ((t - from) / (to - from)).coerceIn(0f, 1f)

/** Ease-out cubic. Fast to start, settles rather than stops. */
private fun easeOut(x: Float): Float {
    val inv = 1f - x
    return 1f - inv * inv * inv
}

@Composable
fun NightbellSplash(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
) {
    val finish by rememberUpdatedState(onFinished)
    val animationsOn = rememberSystemAnimationsEnabled()
    val progress = remember { Animatable(0f) }

    LaunchedEffect(animationsOn) {
        if (!animationsOn) {
            // Animations are off at the system level. A splash is decoration, and
            // decoration is exactly what that setting is asking us not to play, so
            // this hands over on the first frame rather than holding a still for
            // the same two seconds.
            finish()
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(durationMillis = TOTAL_MS, easing = LinearEasing))
        finish()
    }

    if (!animationsOn) return

    val t = progress.value
    val arrive = easeOut(window(t, ARRIVE_FROM, ARRIVE_TO))
    // No carve beat. An earlier cut animated the trace into the bell here, which
    // looked good on its own and was wrong in sequence: the system splash already
    // shows the finished icon, so animating the cut meant the bell arrived whole,
    // lost its heartbeat, and grew it back. The mark is the mark from the first
    // frame the platform draws to the last frame of this one; the ring and the
    // word are the animation.
    val word = easeOut(window(t, WORD_FROM, WORD_TO))
    val leave = window(t, LEAVE_FROM, 1f)

    // A struck bell swings hardest first and settles: a sine inside a decaying
    // envelope, not a loop.
    val ringT = window(t, RING_FROM, RING_TO)
    val swing = if (ringT <= 0f || ringT >= 1f) {
        0f
    } else {
        (SWING_DEGREES * exp(-3.4f * ringT) * sin(ringT * SWINGS * 2f * Math.PI.toFloat()))
    }

    Box(
        modifier
            .fillMaxSize()
            .background(NightbellColors.Void)
            .graphicsLayer { alpha = 1f - leave }
            .semantics { contentDescription = "Nightbell" },
        contentAlignment = Alignment.Center,
    ) {
        // The system splash centres the icon; this row centres bell *and* word, so
        // handing over would slide the bell left by half the word the instant our
        // frame took the screen. Shifting the row right by half the word block
        // while the word is still hidden puts our bell exactly where the
        // platform's was, and lets it drift into place as the word arrives. The
        // width is measured rather than guessed, so it holds at any font scale.
        var wordBlockPx by remember { mutableIntStateOf(0) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                translationX = (wordBlockPx / 2f) * (1f - word)
            },
        ) {
            NightbellMark(
                size = 92.dp,
                modifier = Modifier.graphicsLayer {
                    alpha = arrive
                    scaleX = 0.86f + 0.14f * arrive
                    scaleY = 0.86f + 0.14f * arrive
                    rotationZ = swing
                    // Swing from the crown. Rotating about the centre reads as a
                    // logo being spun; rotating about the point it hangs from reads
                    // as a bell.
                    transformOrigin = TransformOrigin(0.5f, CROWN_PIVOT)
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onSizeChanged { wordBlockPx = it.width },
            ) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = "Nightbell",
                color = NightbellColors.TextPrimary,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.6).sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    // Revealed by clipping rather than by fading or by animating a
                    // width. A fade arrives everywhere at once and reads as a
                    // cross-dissolve; an animated width has to be told how wide the
                    // word ends up and clips the last letter when that number is
                    // even slightly short. Clipping a naturally-sized element to a
                    // fraction of its own width has neither problem.
                    .drawWithContent {
                        clipRect(right = size.width * word) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .graphicsLayer {
                        // Rides in a few pixels behind the wipe so the word feels
                        // pushed out of the bell rather than painted onto the frame.
                        translationX = -18f * (1f - word)
                    },
            )
            }
        }
    }
}
