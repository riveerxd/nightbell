package me.river.pulse.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/**
 * A looping driver that genuinely *stops* when motion is reduced.
 *
 * Speeding an infinite animation up isn't the same as turning it off: the frame
 * clock never goes idle, which burns battery and hangs any test framework that
 * waits for quiescence. When [PulseMotion.enabled] is false this collapses to a
 * plain constant and no animation is registered at all.
 */
@Composable
fun rememberLoopingFloat(
    initialValue: Float,
    targetValue: Float,
    durationMillis: Int,
    repeatMode: RepeatMode = RepeatMode.Restart,
    easing: Easing = LinearEasing,
    label: String = "loop",
): State<Float> {
    val motion = LocalPulseMotion.current
    if (!motion.enabled) {
        return remember(initialValue) { mutableFloatStateOf(initialValue) }
    }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis.coerceAtLeast(1), easing = easing),
            repeatMode = repeatMode,
        ),
        label = label,
    )
}
