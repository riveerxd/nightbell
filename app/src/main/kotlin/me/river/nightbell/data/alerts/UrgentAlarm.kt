package me.river.nightbell.data.alerts

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
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
import me.river.nightbell.domain.VibrationStyle

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
 * [me.river.nightbell.domain.GlobalSettings.urgentRespectsRingerMode] off and asks
 * for a pager that answers to nothing.
 *
 * Haptics always use the alarm vibration usage, because vibrating is precisely
 * what a phone set to vibrate is asking for.
 *
 * Single instance, owned by [me.river.nightbell.data.work.NightbellMonitorService], which
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

    private var ducked = false

    /** True while an announcement is being read over the top of the siren. */
    val isDucked: Boolean get() = ducked

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
    @Synchronized
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
    @Synchronized
    fun reassertVibration() {
        val style = vibeStyle ?: return
        runCatching { resolveVibrator()?.cancel() }
        vibrating = false
        startVibration(style)
    }

    /**
     * Mutes the siren without tearing it down, for as long as something is
     * speaking over it.
     *
     * Volume rather than [MediaPlayer.pause]: the page's whole contract is that it
     * keeps making noise until acknowledged, and a paused player is one bug away
     * from a page that never resumes. A muted one is still looping, still owned by
     * the same service, and restored by a single call that cannot fail
     * differently. Idempotent, so the speaker's `finally` can call it blindly.
     *
     * Haptics are left alone. A phone buzzing under an announcement is still
     * carrying the same message, and stopping the vibration is the one part of a
     * page a sleeping user might actually need.
     */
    @Synchronized
    fun setDucked(quiet: Boolean) {
        ducked = quiet
        runCatching { player?.setVolume(if (quiet) 0f else 1f, if (quiet) 0f else 1f) }
            .onFailure { Diag.log(LogEvent.ALERT_URGENT_FAILED, LogField.tag("at", "volume"), LogField.error("why", it)) }
    }

    @Synchronized
    fun stop() {
        Diag.log(LogEvent.ALERT_URGENT_STOP)
        stopSound()
        ducked = false
        vibeStyle = null
        if (vibrating) {
            runCatching { resolveVibrator()?.cancel() }
            vibrating = false
        }
    }

    /**
     * Releases the player, at most once.
     *
     * The field is cleared *before* the player is touched, and every public entry
     * point above is `@Synchronized`, because two threads genuinely do stop this
     * at the same moment: acknowledging a page calls `alarm.stop()` directly from
     * whichever thread the tap arrived on (so the phone goes quiet immediately
     * rather than at the loop's next tick) and the service loop then calls it
     * again when it notices nothing is paging. Both used to read the same non-null
     * player, and the second one called `isPlaying` on an already released
     * instance: `IllegalStateException`, caught and logged, "Alarm would not stop
     * cleanly", once per acknowledged page.
     */
    private fun stopSound() {
        val active = player
        player = null
        usage = null
        if (active == null) return
        runCatching {
            if (active.isPlaying) active.stop()
            active.release()
        }.onFailure {
            stopFaults++
            Diag.log(LogEvent.ALERT_URGENT_FAILED, LogField.tag("at", "stop"), LogField.error("why", it))
        }
    }

    /**
     * How many times a player refused to be released.
     *
     * Zero is the only acceptable value and it is asserted under concurrent stops,
     * because the race above cannot be seen any other way: the failure is caught,
     * so without a count it shows up as a line in logcat that a green test run
     * says nothing about.
     */
    @Volatile
    var stopFaults: Int = 0
        private set

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
                // Built muted when an announcement is in progress: a ringer flip
                // rebuilds the player, and a fresh one at full volume would talk
                // over the sentence that muted the last one.
                if (ducked) setVolume(0f, 0f)
                start()
                player = this
                usage = soundUsage
            }
        }.onFailure {
            Diag.logError(LogEvent.ALERT_URGENT_FAILED, it, LogField.tag("at", "start"))
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
        //
        // Built inside the branch because the branch is the only place it is
        // usable. The class arrived in API 30 and minSdk here is 26, so hoisting
        // it above the branch was a hard crash on Android 10 and below: nothing to
        // load, and the construction sat above the runCatching so nothing caught
        // it either.
        //
        // The gate is 33, not 30, and it has to stay 33. What is called with these
        // attributes is Vibrator.vibrate(VibrationEffect, VibrationAttributes),
        // which is API 33, and VibratorManager.vibrate(CombinedVibration,
        // VibrationAttributes), which is 31. Loosening this to R to match the
        // class's own API level would trade the old crash for NoSuchMethodError on
        // 30 through 32.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
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
        }.onFailure { Diag.log(LogEvent.ALERT_URGENT_FAILED, LogField.tag("at", "haptics"), LogField.error("why", it)) }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /**
     * The [AudioAttributes] usage a spoken announcement should use right now, or
     * null when nothing should be spoken at all.
     *
     * Asks exactly the question the siren asks itself, so speech cannot become the
     * loophole that makes a phone set to vibrate start talking. Null also when the
     * relevant volume is at zero: the words would not be heard, and synthesising
     * them anyway would only cost the battery.
     */
    fun speechUsage(respectRinger: Boolean): Int? {
        val output = outputFor(respectRinger, vibratePreferred = true)
        if (!output.sound) return null
        if (!alarmStreamAudible(respectRinger)) return null
        return output.usage
    }

    /**
     * Whether a sound would be heard at all right now.
     *
     * The same question [speechUsage] asks, without the page's answer about which
     * stream to use, for the ordinary alerts that are spoken from
     * [me.river.nightbell.data.Nightbell] on notification usage. Lives here so
     * there is exactly one place that reads the ringer.
     */
    fun ringerAllowsSound(): Boolean {
        val allowed = speechUsage(respectRinger = true) != null
        // A pager that stayed silent is the hardest report to answer, and half
        // the time the answer is the ringer switch rather than the app.
        Diag.log(LogEvent.ALERT_RINGER, LogField.of("allows_sound", allowed))
        return allowed
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
