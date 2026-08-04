package me.river.pulse.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the *system* wants animation at all.
 *
 * Android's "Remove animations" accessibility toggle zeroes
 * `ANIMATOR_DURATION_SCALE`, and honouring it is not optional: someone who turns
 * animation off OS-wide has usually done so because motion makes them ill, and an
 * app that respects only its own in-app slider is asking them to find and flip a
 * second switch they have no reason to know exists.
 *
 * Observed rather than read once — the setting can change while the app is open,
 * and a stale read would leave the aurora running for the rest of the session.
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var enabled by remember { mutableStateOf(animatorScale(context) > 0f) }
    DisposableEffect(resolver) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                enabled = animatorScale(context) > 0f
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Defaults to 1 — an unreadable setting must not be taken as "animations off". */
private fun animatorScale(context: android.content.Context): Float =
    runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)

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
