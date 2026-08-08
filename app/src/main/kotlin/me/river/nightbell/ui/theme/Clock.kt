package me.river.nightbell.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * A wall clock the UI can actually read.
 *
 * Every "checked 4m ago" and every "is this mute still running" is a comparison
 * against *now*, and Compose has no reason to re-run a composition just because
 * time passed. Reading `System.currentTimeMillis()` directly inside a composable
 * therefore freezes the answer at whatever it was when something else last
 * changed: on a fifteen-minute interval a row could sit on "just now" for the
 * whole fifteen minutes, and an expired mute kept its amber rim until an
 * unrelated state change happened to redraw the card.
 *
 * On a screen whose entire job is saying how current the data is, that is the
 * worst possible thing to get wrong, so the clock is a first-class dependency
 * rather than an ambient call.
 */
val LocalNowMs = compositionLocalOf { System.currentTimeMillis() }

/** How often the clock advances. */
private const val TICK_MS = 20_000L

/**
 * Drives [LocalNowMs].
 *
 * Ticks only while the lifecycle is at least STARTED — a backgrounded app has
 * nothing on screen to keep honest, and two wake-ups a minute for nobody is
 * exactly the kind of cost this app refuses to pay elsewhere. Re-reads the clock
 * immediately on resume, so coming back to the app never shows a stale figure
 * while it waits for the next tick.
 */
@Composable
fun rememberNowMs(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
    }
    return now
}
