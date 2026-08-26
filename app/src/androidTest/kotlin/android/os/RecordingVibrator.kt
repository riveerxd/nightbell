package android.os

import android.media.AudioAttributes
import java.util.concurrent.atomic.AtomicInteger

/**
 * A vibrator that records instead of buzzing.
 *
 * Lives in `android.os` because [Vibrator]'s only constructor is package-private,
 * and it has to exist at all because no emulator reports a vibrator: the crash
 * this covers sits past a `hasVibrator()` guard, so on a stock emulator the line
 * that used to take the process down is simply never reached. Everything else
 * about the test stays real, including the API level and the missing class.
 *
 * Only the calls
 * [me.river.nightbell.data.alerts.UrgentAlarm.startVibration] actually makes are
 * overridden.
 */
class RecordingVibrator : Vibrator() {

    val vibrations = AtomicInteger(0)

    /** Attributes of the last request, so a test can say which branch ran. */
    @Volatile
    var lastAudioAttributes: AudioAttributes? = null
        private set

    override fun hasVibrator(): Boolean = true

    override fun hasAmplitudeControl(): Boolean = false

    override fun cancel() = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun vibrate(vibe: VibrationEffect, attributes: AudioAttributes?) {
        lastAudioAttributes = attributes
        vibrations.incrementAndGet()
    }

    override fun vibrate(vibe: VibrationEffect, attributes: VibrationAttributes) {
        vibrations.incrementAndGet()
    }
}
