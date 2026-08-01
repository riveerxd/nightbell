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

    /**
     * The URGENT nag.
     *
     * One notification per monitor, re-posted in place: `setOnlyAlertOnce(false)`
     * plus a stable id means every repeat rings and buzzes again without adding
     * a second row to the shade. It is [NotificationCompat.CATEGORY_ALARM] and
     * ongoing — you cannot swipe it away, you have to acknowledge it, which is
     * the entire point of the mode.
     */
    fun notifyUrgent(monitor: Monitor, result: CheckResult, policy: AlertPolicy, repeatCount: Int) {
        val channelId = urgentChannel(policy)
        val body = result.message.ifBlank { result.failureKind.headline }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("URGENT · ${monitor.displayName} is down")
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(result.failureKind.headline)
                        if (result.failureKind.hint.isNotBlank()) {
                            append("\n").append(result.failureKind.hint)
                        }
                        append("\n\nThis alert repeats every ")
                        append(monitor.urgentRepeatMinutes.coerceAtLeast(1))
                        append(" min until you acknowledge it.")
                        if (repeatCount > 0) append("\nReminder #$repeatCount.")
                        append("\n\n").append(monitor.url)
                    },
                ),
            )
            .setColor(DOWN_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openMonitorIntent(monitor.id))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(
                R.drawable.ic_stat_ok,
                "Acknowledge",
                AlertActionReceiver.pendingIntent(
                    context,
                    AlertActionReceiver.ACTION_ACK_URGENT,
                    monitor.id,
                ),
            )
            .addAction(
                R.drawable.ic_stat_refresh,
                "Re-check now",
                AlertActionReceiver.pendingIntent(context, AlertActionReceiver.ACTION_RECHECK, monitor.id),
            )
            .build()

        post(monitor.id.urgentNotificationId(), notification)
        // Channel vibration only fires on the first post of an id on some OEM
        // builds, so drive the actuator directly for every repeat.
        if (policy.vibrate) previewVibration(policy.vibrationStyle)
    }

    fun cancelUrgent(monitorId: String) = compat.cancel(monitorId.urgentNotificationId())

    /** "Up, but slow." Deliberately quieter than a down alert. */
    fun notifyDegraded(
        monitor: Monitor,
        result: CheckResult,
        policy: AlertPolicy,
        sloMs: Int,
        silent: Boolean,
        repeat: Boolean,
    ) {
        val channelId = channelFor(policy, Severity.DEGRADED, silent)
        val notification = baseBuilder(channelId, monitor, silent)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(
                if (repeat) {
                    "${monitor.displayName} is still slow"
                } else {
                    "${monitor.displayName} is slow"
                },
            )
            .setContentText("${result.latencyMs} ms — budget is $sloMs ms")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append("The check succeeded but took ${result.latencyMs} ms, ")
                        append("over the ${sloMs} ms latency budget.")
                        append("\n\n").append(monitor.url)
                    },
                ),
            )
            .setColor(DEGRADED_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
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
        post(monitor.id.degradedNotificationId(), notification)
    }

    fun notifyDegradedRecovery(
        monitor: Monitor,
        result: CheckResult,
        policy: AlertPolicy,
        sloMs: Int,
        silent: Boolean,
    ) {
        val channelId = channelFor(policy, Severity.RECOVERY, silent)
        val notification = baseBuilder(channelId, monitor, silent)
            .setSmallIcon(R.drawable.ic_stat_ok)
            .setContentTitle("${monitor.displayName} is fast again")
            .setContentText("${result.latencyMs} ms — back under the $sloMs ms budget")
            .setColor(UP_COLOR)
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        post(monitor.id.degradedNotificationId(), notification)
    }

    fun cancelDegraded(monitorId: String) = compat.cancel(monitorId.degradedNotificationId())

    fun cancel(monitorId: String) = compat.cancel(monitorId.notificationId())

    /** Down, degraded and urgent — everything a monitor can have on screen. */
    fun cancelAll(monitorId: String) {
        cancel(monitorId)
        cancelDegraded(monitorId)
        cancelUrgent(monitorId)
    }

    /**
     * Ids of Pulse's own alert notifications that are currently on screen.
     *
     * The source of truth for "what is the user actually looking at". Reasoning
     * from persisted monitor state alone cannot see a notification belonging to
     * a monitor that has since been deleted, or one left behind by an older
     * build — and an urgent notification is `ongoing`, so the user cannot clear
     * it by hand either.
     *
     * Restricted to the three alert id spaces so the foreground-service
     * notification, the policy preview and anything the system auto-groups are
     * never touched.
     */
    fun activeAlertIds(): Set<Int> = runCatching {
        manager.activeNotifications
            .filter { it.tag == null && it.id in ALERT_ID_MIN..ALERT_ID_MAX }
            .map { it.id }
            .toSet()
    }.getOrDefault(emptySet())

    /** Cancels an alert notification by raw id — used by the reconciliation sweep. */
    fun cancelById(id: Int) = compat.cancel(id)

    /**
     * Wipes every notification this app has posted.
     *
     * Only for the one-time upgrade repair. [activeAlertIds] is the surgical
     * tool, but it depends on `getActiveNotifications()`, which some OEM builds
     * are unhelpful about — and the whole point of the repair is to recover
     * from a state we cannot enumerate. Anything genuinely current re-posts on
     * the next check, and the foreground service re-posts its own immediately.
     */
    fun cancelEverything() = runCatching { compat.cancelAll() }.let { }

    // The sweep needs to compare live ids against the ids a monitor may hold.
    fun downIdOf(monitorId: String): Int = monitorId.notificationId()

    fun degradedIdOf(monitorId: String): Int = monitorId.degradedNotificationId()

    fun urgentIdOf(monitorId: String): Int = monitorId.urgentNotificationId()

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

    enum class Severity { DOWN, DEGRADED, RECOVERY }

    private fun ensureGroups() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_DOWN, "Down alerts"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_DEGRADED, "Latency alerts"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_RECOVERY, "Recovery alerts"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_URGENT, "Urgent"),
        )
    }

    /**
     * Urgent gets its own channel family so the user can leave it screaming
     * while turning ordinary down alerts down — and so Do Not Disturb can be
     * configured to let it through.
     */
    fun urgentChannel(policy: AlertPolicy): String {
        val style = policy.vibrationStyle
        val id = "pulse.urgent.${style.name.lowercase()}"
        if (manager.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(id, "Urgent · ${style.label}", NotificationManager.IMPORTANCE_HIGH).apply {
            group = GROUP_URGENT
            description = "Repeats until acknowledged when an URGENT monitor goes down."
            enableVibration(true)
            vibrationPattern = style.pattern
            enableLights(true)
            lightColor = DOWN_COLOR
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            setShowBadge(true)
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
        return id
    }

    /**
     * The strict-mode foreground-service channel: silent, un-dismissable, and
     * as far out of the way as a mandatory notification can be.
     */
    fun ensureServiceChannel(): String {
        if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL,
                    "Strict monitoring",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while Pulse keeps a strict check cadence in the background."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        return SERVICE_CHANNEL
    }

    /** The persistent notification strict mode runs behind. */
    fun serviceNotification(title: String, body: String, stopIntent: PendingIntent?): Notification {
        val builder = NotificationCompat.Builder(context, ensureServiceChannel())
            .setSmallIcon(R.drawable.ic_stat_refresh)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openMonitorIntent(""))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (stopIntent != null) {
            builder.addAction(R.drawable.ic_stat_mute, "Stop strict mode", stopIntent)
        }
        return builder.build()
    }

    /** Materialises (once) the channel that matches this exact policy. */
    fun channelFor(policy: AlertPolicy, severity: Severity, silent: Boolean): String {
        val sound = if (silent) SoundChoice.SILENT else policy.sound
        val vibrateOn = policy.vibrate && !silent
        val style = policy.vibrationStyle
        val prefix = when (severity) {
            Severity.DOWN -> "pulse.down."
            Severity.DEGRADED -> "pulse.degraded."
            Severity.RECOVERY -> "pulse.recovery."
        }
        val id = buildString {
            append(prefix)
            append(sound.name.lowercase())
            append('.')
            append(if (vibrateOn) style.name.lowercase() else "novib")
        }
        if (manager.getNotificationChannel(id) != null) return id

        val importance = when {
            severity == Severity.RECOVERY -> NotificationManager.IMPORTANCE_DEFAULT
            severity == Severity.DEGRADED -> NotificationManager.IMPORTANCE_DEFAULT
            sound == SoundChoice.SILENT && !vibrateOn -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        val label = buildString {
            append(
                when (severity) {
                    Severity.DOWN -> "Down · "
                    Severity.DEGRADED -> "Slow · "
                    Severity.RECOVERY -> "Recovery · "
                },
            )
            append(sound.label)
            if (vibrateOn) append(" + ${style.label}")
        }

        val channel = NotificationChannel(id, label, importance).apply {
            group = when (severity) {
                Severity.DOWN -> GROUP_DOWN
                Severity.DEGRADED -> GROUP_DEGRADED
                Severity.RECOVERY -> GROUP_RECOVERY
            }
            description = when (severity) {
                Severity.DOWN -> "Raised when a monitor starts failing (${sound.label.lowercase()})."
                Severity.DEGRADED -> "Raised when a monitor breaches its latency budget."
                Severity.RECOVERY -> "Raised when a monitor recovers (${sound.label.lowercase()})."
            }
            enableVibration(vibrateOn)
            if (vibrateOn) vibrationPattern = style.pattern
            enableLights(severity == Severity.DOWN)
            lightColor = when (severity) {
                Severity.DOWN -> DOWN_COLOR
                Severity.DEGRADED -> DEGRADED_COLOR
                Severity.RECOVERY -> UP_COLOR
            }
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
            if (monitorId.isNotBlank()) putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
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
        private const val GROUP_DEGRADED = "pulse.group.degraded"
        private const val GROUP_RECOVERY = "pulse.group.recovery"
        private const val GROUP_URGENT = "pulse.group.urgent"
        private const val NOTIFICATION_GROUP = "pulse.alerts"
        const val SERVICE_CHANNEL = "pulse.service.strict"
        const val SERVICE_NOTIFICATION_ID = 4242
        const val PREVIEW_NOTIFICATION_ID = 424242

        /** The three alert id spaces below, taken together. */
        private const val ALERT_ID_MIN = 100_000
        private const val ALERT_ID_MAX = 399_999

        private const val DOWN_COLOR = 0xFFFF5A7A.toInt()
        private const val DEGRADED_COLOR = 0xFFFFB020.toInt()
        private const val UP_COLOR = 0xFF3DE8B0.toInt()
    }
}

// Three disjoint id spaces so a monitor can hold a down, a slow and an urgent
// notification at once without one silently replacing another.
internal fun String.notificationId(): Int = 100_000 + (hashCode() and 0x7FFF)

internal fun String.urgentNotificationId(): Int = 200_000 + (hashCode() and 0x7FFF)

internal fun String.degradedNotificationId(): Int = 300_000 + (hashCode() and 0x7FFF)
