package me.river.pulse.data.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import me.river.pulse.domain.VibrationStyle

/**
 * The sound and haptics of an unacknowledged page.
 *
 * ### Why this exists at all
 * A notification channel's sound plays **once** per post. With a five-minute
 * repeat gap that is one short chime and then five minutes of silence, which is
 * not a pager — it is a reminder. `FLAG_INSISTENT` would loop it, but that flag
 * is reserved for system apps, so an app that wants to keep making noise has to
 * own the player.
 *
 * Plays on [AudioAttributes.USAGE_ALARM] so it uses the alarm stream: the one
 * stream a user who silences their ringer still expects to hear from, and the one
 * Do Not Disturb's "alarms" allowance covers.
 *
 * Single instance, owned by [me.river.pulse.data.work.PulseMonitorService], which
 * is alive for exactly as long as a page is unacknowledged. [stop] is idempotent
 * and safe to call from any of the several paths that end an outage.
 */
class UrgentAlarm(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrating = false

    val isPlaying: Boolean get() = player != null

    /**
     * Starts looping, or does nothing if already looping.
     *
     * Deliberately not restarted on a repeat: the noise is continuous until
     * acknowledged, so re-starting it would only introduce a gap.
     */
    fun start(style: VibrationStyle, vibrate: Boolean) {
        if (player == null) startSound()
        if (vibrate && !vibrating) startVibration(style)
    }

    fun stop() {
        player?.let { active ->
            runCatching {
                if (active.isPlaying) active.stop()
                active.release()
            }.onFailure { Log.w(TAG, "Alarm would not stop cleanly", it) }
        }
        player = null
        if (vibrating) {
            runCatching { resolveVibrator()?.cancel() }
            vibrating = false
        }
    }

    private fun startSound() {
        val uri = Settings.System.DEFAULT_ALARM_ALERT_URI ?: return
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                // Prepared synchronously: this runs on the service's own
                // coroutine, and a page that starts making noise a second late
                // is worse than one that blocks a background thread briefly.
                prepare()
                start()
                player = this
            }
        }.onFailure {
            Log.e(TAG, "Could not start the urgent alarm", it)
            player = null
        }
    }

    /**
     * Repeats the monitor's chosen haptic pattern indefinitely.
     *
     * The repeat index is the last entry of the pattern, so the waveform loops
     * with its own trailing pause rather than buzzing continuously.
     */
    private fun startVibration(style: VibrationStyle) {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = style.pattern
        if (pattern.isEmpty()) return
        val repeatFrom = (pattern.size - 1).coerceAtLeast(0)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(pattern, style.amplitudes, repeatFrom)
        } else {
            VibrationEffect.createWaveform(pattern, repeatFrom)
        }
        // Alarm usage, so the haptics survive a silenced ringer for the same
        // reason the sound does.
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val manager = context.getSystemService(VibratorManager::class.java)
                if (manager != null) {
                    manager.vibrate(CombinedVibration.createParallel(effect), attributes)
                } else {
                    vibrator.vibrate(effect, attributes)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build(),
                )
            }
            vibrating = true
        }.onFailure { Log.w(TAG, "Could not start urgent haptics", it) }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /**
     * Whether the alarm stream can actually be heard.
     *
     * Surfaced so the app can tell the user their pager is muted instead of
     * silently failing to wake them.
     */
    fun alarmStreamAudible(): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching true
        audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }.getOrDefault(true)

    private companion object {
        const val TAG = "UrgentAlarm"
    }
}
