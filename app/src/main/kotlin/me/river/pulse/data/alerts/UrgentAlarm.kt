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
 * ### Which stream
 * By default the page follows the **ringer**: sound and haptics on Normal,
 * haptics only on Vibrate and Silent, with the sound on the ringtone usage so
 * ring volume applies. The alarm stream — which is exempt from the ringer, so a
 * phone set to vibrate got a full-volume siren — is used only when the user turns
 * [me.river.pulse.domain.GlobalSettings.urgentRespectsRingerMode] off and asks
 * for a pager that answers to nothing.
 *
 * Haptics always use the alarm vibration usage, because vibrating is precisely
 * what a phone set to vibrate is asking for.
 *
 * Single instance, owned by [me.river.pulse.data.work.NightbellMonitorService], which
 * is alive for exactly as long as a page is unacknowledged. [stop] is idempotent
 * and safe to call from any of the several paths that end an outage.
 */
class UrgentAlarm(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrating = false

    /** The style of the haptic loop that should be running, kept so it can be re-issued
     *  after the system cancels it on screen-off. Null when nothing should vibrate. */
    private var vibeStyle: VibrationStyle? = null

    /** Which usage [player] was built for, so a ringer flip can rebuild it. */
    private var usage: Int? = null

    val isPlaying: Boolean get() = player != null

    /**
     * What the ringer switch says this page is allowed to do.
     *
     * The page loops on the alarm stream, which the platform exempts from the
     * ringer entirely — right for an alarm clock, wrong here: a phone set to
     * vibrate got a full-volume siren out of it. When [respectRinger] is on the
     * output is chosen from [AudioManager.getRingerMode] instead, and the sound
     * moves to the ringtone usage so the *ring* volume applies to it.
     *
     * Silent still vibrates. A page with no sound and no buzz cannot be told
     * apart from a broken pager.
     */
    private data class Output(val sound: Boolean, val vibrate: Boolean, val usage: Int)

    private fun outputFor(respectRinger: Boolean, vibratePreferred: Boolean): Output {
        if (!respectRinger) {
            return Output(sound = true, vibrate = vibratePreferred, usage = AudioAttributes.USAGE_ALARM)
        }
        val mode = runCatching {
            context.getSystemService(AudioManager::class.java)?.ringerMode
        }.getOrNull() ?: AudioManager.RINGER_MODE_NORMAL
        return when (mode) {
            AudioManager.RINGER_MODE_SILENT ->
                Output(sound = false, vibrate = true, usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            AudioManager.RINGER_MODE_VIBRATE ->
                Output(sound = false, vibrate = true, usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            else ->
                Output(sound = true, vibrate = vibratePreferred, usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        }
    }

    /**
     * Starts looping, or does nothing if already looping.
     *
     * Deliberately not restarted on a repeat: the noise is continuous until
     * acknowledged, so re-starting it would only introduce a gap.
     *
     * Re-evaluates the ringer on every call, though, so flipping the phone to
     * vibrate during an outage quietens the page it is already making — and
     * flipping it back makes it loud again — without waiting for the next repeat.
     */
    fun start(style: VibrationStyle, vibrate: Boolean, respectRinger: Boolean = true) {
        val output = outputFor(respectRinger, vibrate)
        if (output.sound) {
            if (player == null || usage != output.usage) {
                stopSound()
                startSound(output.usage)
            }
        } else {
            stopSound()
        }
        if (output.vibrate) {
            vibeStyle = style
            if (!vibrating) startVibration(style)
        } else if (vibrating) {
            runCatching { resolveVibrator()?.cancel() }
            vibrating = false
            vibeStyle = null
        }
    }

    /**
     * Re-issues the haptic loop if one should be running.
     *
     * The system cancels an ongoing vibration the instant the screen turns off — the
     * vibrator history logs it verbatim as `cancelled_by_screen_off` — and because
     * [start] is idempotent on [vibrating], the service loop never revives it. So a page
     * set to vibrate went silent the moment the user pressed the power button, which is
     * the opposite of a pager. A vibration *started* while the screen is already off runs
     * normally, so re-issuing it from a screen-off receiver keeps it buzzing until the
     * page is actually acknowledged. Idempotent and a no-op when nothing should vibrate.
     */
    fun reassertVibration() {
        val style = vibeStyle ?: return
        runCatching { resolveVibrator()?.cancel() }
        vibrating = false
        startVibration(style)
    }

    fun stop() {
        stopSound()
        vibeStyle = null
        if (vibrating) {
            runCatching { resolveVibrator()?.cancel() }
            vibrating = false
        }
    }

    private fun stopSound() {
        player?.let { active ->
            runCatching {
                if (active.isPlaying) active.stop()
                active.release()
            }.onFailure { Log.w(TAG, "Alarm would not stop cleanly", it) }
        }
        player = null
        usage = null
    }

    private fun startSound(soundUsage: Int) {
        val uri = Settings.System.DEFAULT_ALARM_ALERT_URI ?: return
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(soundUsage)
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
                usage = soundUsage
            }
        }.onFailure {
            Log.e(TAG, "Could not start the urgent alarm", it)
            player = null
            usage = null
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
        // Alarm usage for the haptics regardless of the sound's usage: this is
        // the one output that must survive a phone set to vibrate, which is
        // exactly the case the ringer check quietens the sound for.
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
    fun alarmStreamAudible(respectRinger: Boolean = true): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching true
        val stream = if (respectRinger) AudioManager.STREAM_RING else AudioManager.STREAM_ALARM
        audio.getStreamVolume(stream) > 0
    }.getOrDefault(true)

    /** Human-readable account of what the ringer is currently allowing. */
    fun ringerSummary(): String = runCatching {
        when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent — pages vibrate only"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate — pages vibrate only"
            else -> "Normal — pages ring and vibrate"
        }
    }.getOrDefault("Normal — pages ring and vibrate")

    private companion object {
        const val TAG = "UrgentAlarm"
    }
}
