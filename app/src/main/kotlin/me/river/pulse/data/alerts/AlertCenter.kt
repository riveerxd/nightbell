package me.river.pulse.data.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.river.pulse.MainActivity
import me.river.pulse.R
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.SoundChoice
import me.river.pulse.domain.VibrationStyle

/**
 * Owns everything the user actually feels and hears.
 *
 * Android freezes a notification channel's sound and vibration at creation time,
 * so a single "alerts" channel could never honour per-monitor haptic/sound
 * choices. Instead we lazily materialise one channel per
 * (sound × vibration-style × severity) combination and route each alert to the
 * channel that matches its policy. Channels are grouped so the system settings
 * screen stays readable.
 */
class AlertCenter(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val compat = NotificationManagerCompat.from(context)

    init {
        ensureGroups()
    }

    // ---- public API ---------------------------------------------------------

    fun notifyDown(monitor: Monitor, result: CheckResult, policy: AlertPolicy, silent: Boolean, repeat: Boolean) {
        val channelId = channelFor(policy, Severity.DOWN, silent)
        val title = if (repeat) {
            "${monitor.displayName} is still down"
        } else {
            "${monitor.displayName} is down"
        }
        val body = result.message.ifBlank { result.failureKind.headline }
        val expanded = buildString {
            append(result.failureKind.headline)
            if (result.failureKind.hint.isNotBlank()) append("\n").append(result.failureKind.hint)
            if (result.detail.isNotBlank()) append("\n\n").append(result.detail.take(320))
            append("\n\n").append(monitor.url)
        }

        val notification = baseBuilder(channelId, monitor, silent)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .setColor(DOWN_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOngoing(false)
            .addAction(
                R.drawable.ic_stat_refresh,
                "Re-check now",
                AlertActionReceiver.pendingIntent(context, AlertActionReceiver.ACTION_RECHECK, monitor.id),
            )
            .addAction(
                R.drawable.ic_stat_mute,
                "Mute 1h",
                AlertActionReceiver.pendingIntent(context, AlertActionReceiver.ACTION_MUTE_1H, monitor.id),
            )
            .build()

        post(monitor.id.notificationId(), notification)
    }

    fun notifyRecovery(monitor: Monitor, result: CheckResult, policy: AlertPolicy, silent: Boolean) {
        val channelId = channelFor(policy, Severity.RECOVERY, silent)
        val notification = baseBuilder(channelId, monitor, silent)
            .setSmallIcon(R.drawable.ic_stat_ok)
            .setContentTitle("${monitor.displayName} is back")
            .setContentText(result.message.ifBlank { "Responding normally again" })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append("Recovered after a failure.")
                        if (result.latencyMs > 0) append("\nResponded in ${result.latencyMs} ms.")
                        append("\n\n").append(monitor.url)
                    },
                ),
            )
            .setColor(UP_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        post(monitor.id.notificationId(), notification)
    }

    fun cancel(monitorId: String) = compat.cancel(monitorId.notificationId())

    /**
     * Fires a sample alert so the user can feel/hear a policy before trusting it.
     * Vibration is triggered directly here (rather than via the channel) so the
     * preview works even when the notification itself is coalesced.
     */
    fun previewPolicy(policy: AlertPolicy, monitorName: String = "Preview monitor") {
        val channelId = channelFor(policy, Severity.DOWN, silent = false)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("$monitorName is down")
            .setContentText("This is what a real alert looks like.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Sound: ${policy.sound.label}\nHaptics: ${if (policy.vibrate) policy.vibrationStyle.label else "off"}"),
            )
            .setColor(DOWN_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        post(PREVIEW_NOTIFICATION_ID, notification)
        if (policy.vibrate) previewVibration(policy.vibrationStyle)
    }

    /** Plays a haptic pattern immediately — used by the style picker. */
    fun previewVibration(style: VibrationStyle) {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(style.pattern, style.amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(style.pattern, -1)
        }
        vibrator.vibrate(effect)
    }

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            compat.areNotificationsEnabled()
        }

    fun channelSettingsIntent(policy: AlertPolicy, severity: Severity = Severity.DOWN): Intent {
        val id = channelFor(policy, severity, silent = false)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, id)
    }

    // ---- channels ----------------------------------------------------------

    enum class Severity { DOWN, RECOVERY }

    private fun ensureGroups() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_DOWN, "Down alerts"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_RECOVERY, "Recovery alerts"),
        )
    }

    /** Materialises (once) the channel that matches this exact policy. */
    fun channelFor(policy: AlertPolicy, severity: Severity, silent: Boolean): String {
        val sound = if (silent) SoundChoice.SILENT else policy.sound
        val vibrateOn = policy.vibrate && !silent
        val style = policy.vibrationStyle
        val id = buildString {
            append(if (severity == Severity.DOWN) "pulse.down." else "pulse.recovery.")
            append(sound.name.lowercase())
            append('.')
            append(if (vibrateOn) style.name.lowercase() else "novib")
        }
        if (manager.getNotificationChannel(id) != null) return id

        val importance = when {
            severity == Severity.RECOVERY -> NotificationManager.IMPORTANCE_DEFAULT
            sound == SoundChoice.SILENT && !vibrateOn -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        val label = buildString {
            append(if (severity == Severity.DOWN) "Down · " else "Recovery · ")
            append(sound.label)
            if (vibrateOn) append(" + ${style.label}")
        }

        val channel = NotificationChannel(id, label, importance).apply {
            group = if (severity == Severity.DOWN) GROUP_DOWN else GROUP_RECOVERY
            description = if (severity == Severity.DOWN) {
                "Raised when a monitor starts failing (${sound.label.lowercase()})."
            } else {
                "Raised when a monitor recovers (${sound.label.lowercase()})."
            }
            enableVibration(vibrateOn)
            if (vibrateOn) vibrationPattern = style.pattern
            enableLights(severity == Severity.DOWN)
            lightColor = if (severity == Severity.DOWN) DOWN_COLOR else UP_COLOR
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            val uri = soundUri(sound)
            if (uri == null) {
                setSound(null, null)
            } else {
                setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(
                            if (sound == SoundChoice.ALARM) {
                                AudioAttributes.USAGE_ALARM
                            } else {
                                AudioAttributes.USAGE_NOTIFICATION_EVENT
                            },
                        )
                        .build(),
                )
            }
        }
        manager.createNotificationChannel(channel)
        return id
    }

    private fun soundUri(choice: SoundChoice): Uri? = when (choice) {
        SoundChoice.SILENT -> null
        SoundChoice.DEFAULT_NOTIFICATION -> Settings.System.DEFAULT_NOTIFICATION_URI
        SoundChoice.ALARM -> Settings.System.DEFAULT_ALARM_ALERT_URI
        SoundChoice.RINGTONE -> Settings.System.DEFAULT_RINGTONE_URI
    }

    // ---- plumbing ----------------------------------------------------------

    private fun baseBuilder(channelId: String, monitor: Monitor, silent: Boolean) =
        NotificationCompat.Builder(context, channelId)
            .setContentIntent(openMonitorIntent(monitor.id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(NOTIFICATION_GROUP)

    private fun openMonitorIntent(monitorId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
        }
        return PendingIntent.getActivity(
            context,
            monitorId.notificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) {
        if (!hasNotificationPermission()) return
        runCatching { compat.notify(id, notification) }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Plays a combined effect on every actuator — used for the strongest style. */
    @Suppress("unused")
    fun vibrateCombined(style: VibrationStyle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            previewVibration(style); return
        }
        val vm = context.getSystemService(VibratorManager::class.java) ?: return
        val effect = VibrationEffect.createWaveform(style.pattern, style.amplitudes, -1)
        vm.vibrate(CombinedVibration.createParallel(effect))
    }

    companion object {
        private const val GROUP_DOWN = "pulse.group.down"
        private const val GROUP_RECOVERY = "pulse.group.recovery"
        private const val NOTIFICATION_GROUP = "pulse.alerts"
        const val PREVIEW_NOTIFICATION_ID = 424242
        private const val DOWN_COLOR = 0xFFFF5A7A.toInt()
        private const val UP_COLOR = 0xFF3DE8B0.toInt()
    }
}

private fun String.notificationId(): Int = 100_000 + (hashCode() and 0x7FFF)
