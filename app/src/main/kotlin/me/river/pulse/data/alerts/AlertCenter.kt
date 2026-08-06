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
import me.river.pulse.UrgentAlertActivity
import me.river.pulse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.CertificateWatch
import me.river.pulse.domain.CheckResult
import me.river.pulse.domain.LiveTimeline
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

        val posted = post(monitor.id.urgentNotificationId(), notification)
        // Channel vibration only fires on the first post of an id on some OEM
        // builds, so drive the actuator directly for every repeat — but only when
        // the notification it belongs to actually arrived. A direct vibrate() call
        // obeys neither the channel being switched off nor POST_NOTIFICATIONS being
        // denied, so without these guards turning the urgent channel off left the
        // buzz behind with nothing on screen to explain it.
        if (posted && policy.vibrate && channelCanAlert(channelId)) {
            previewVibration(policy.vibrationStyle)
        }
    }

    fun cancelUrgent(monitorId: String) = compat.cancel(monitorId.urgentNotificationId())

    // ---- the URGENT page ---------------------------------------------------

    /**
     * Builds the page — the red call-shaped card the user actually gets paged by.
     *
     * Returned rather than posted, because *where* it is posted decides how it
     * looks. `setColorized(true)` is honoured only for a foreground-service
     * notification, so the fully red card exists only when
     * [me.river.pulse.data.work.PulseMonitorService] posts this as its own
     * foreground notification. Verified on a device: the identical builder sent
     * through `NotificationManager.notify` renders as a white card with a red
     * block inside it.
     *
     * @param otherPending how many *other* monitors are also unacknowledged, so
     *   one card can honestly stand for a multi-monitor incident.
     */
    fun urgentPage(
        monitor: Monitor,
        result: CheckResult,
        policy: AlertPolicy,
        downForMs: Long,
        pageCount: Int,
        otherPending: Int = 0,
        respectRinger: Boolean = true,
        /**
         * True when [UrgentAlarm] is looping the audio for this page, so the
         * channel must not also fire its own one-shot on top of it.
         */
        silent: Boolean = false,
    ): Notification {
        val headline = result.message.ifBlank { result.failureKind.headline }
        val content = UrgentPageContent(
            monitorName = monitor.displayName,
            headline = if (otherPending > 0) "$headline · +$otherPending more down" else headline,
            url = monitor.url,
            downFor = formatDownFor(downForMs),
            failedChecks = 0,
            reminderNumber = (pageCount - 1).coerceAtLeast(0),
            repeatMinutes = monitor.urgentRepeatMinutes,
        )
        return UrgentPageStyles.build(
            context = context,
            style = UrgentPageStyle.CALL_CUSTOM,
            channelId = urgentChannel(policy, respectRinger),
            content = content,
            actions = urgentActions(monitor),
            silent = silent,
        )
    }

    /** The page's three buttons, plus its locked-screen escalation. */
    private fun urgentActions(monitor: Monitor) = UrgentPageActions(
        acknowledge = AlertActionReceiver.pendingIntent(
            context,
            AlertActionReceiver.ACTION_ACK_URGENT,
            monitor.id,
        ),
        open = openMonitorIntent(monitor.id),
        recheck = AlertActionReceiver.pendingIntent(
            context,
            AlertActionReceiver.ACTION_RECHECK,
            monitor.id,
        ),
        mute = AlertActionReceiver.pendingIntent(
            context,
            AlertActionReceiver.ACTION_MUTE_1H,
            monitor.id,
        ),
        // Only takes the screen while the device is locked; unlocked, the heads-up
        // above is what arrives. Null when the permission is not granted so the
        // notification is never rejected for carrying an intent it may not use.
        fullScreen = if (canUseFullScreenIntent()) fullScreenPageIntent(monitor) else null,
    )

    private fun fullScreenPageIntent(monitor: Monitor): PendingIntent {
        val intent = Intent(context, UrgentAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(UrgentAlertActivity.EXTRA_MONITOR_ID, monitor.id)
            putExtra(UrgentAlertActivity.EXTRA_MONITOR_NAME, monitor.displayName)
            putExtra(UrgentAlertActivity.EXTRA_URL, monitor.url)
            putExtra(UrgentAlertActivity.EXTRA_REPEAT_MINUTES, monitor.urgentRepeatMinutes)
        }
        return PendingIntent.getActivity(
            context,
            monitor.id.urgentNotificationId(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The degraded page, for when the foreground service could not start.
     *
     * Android 12+ refuses a background `startForegroundService` without an
     * exemption, and the repeat loop and the red card both live in that service.
     * Posting the same card the ordinary way still interrupts the user — it just
     * arrives as a red block inside a system-coloured card and does not loop.
     * Losing the styling is acceptable; losing the page is not.
     */
    fun postUrgentPageFallback(
        monitor: Monitor,
        result: CheckResult,
        policy: AlertPolicy,
        downForMs: Long,
        pageCount: Int,
        respectRinger: Boolean = true,
    ): Boolean = post(
        monitor.id.urgentNotificationId(),
        // Not silent: with no service there is no looping player, so the
        // channel's one-shot is the only sound this page will make.
        urgentPage(
            monitor = monitor,
            result = result,
            policy = policy,
            downForMs = downForMs,
            pageCount = pageCount,
            respectRinger = respectRinger,
            silent = false,
        ),
    )

    /** Renders a duration the way the page says it. */
    fun formatDownFor(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    // ---- can this app actually page the user? ------------------------------

    /**
     * Whether a full-screen intent would be honoured.
     *
     * Not pre-granted above API 33 for anything but calling and alarm apps, so
     * this is normally false until the user grants it by hand.
     */
    fun canUseFullScreenIntent(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@runCatching true
        manager.canUseFullScreenIntent()
    }.getOrDefault(false)

    fun fullScreenIntentSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Whether `setBypassDnd(true)` on the urgent channel actually took effect.
     *
     * The setter needs notification-policy access, which is a separate grant.
     * Without it the flag is dropped silently and Do Not Disturb — bedtime mode
     * included — mutes the page completely.
     */
    fun urgentBypassesDnd(): Boolean = runCatching {
        manager.isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    fun dndAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Whether the urgent channel is still allowed to interrupt. */
    fun urgentChannelEnabled(policy: AlertPolicy, respectRinger: Boolean = true): Boolean = runCatching {
        val channel = manager.getNotificationChannel(urgentChannel(policy, respectRinger))
            ?: return@runCatching true
        channel.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(true)

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

    // ---- certificate expiry -------------------------------------------------

    /**
     * The certificate advisory.
     *
     * Always silent-by-nature rather than silent-by-flag: it takes the monitor's
     * chosen sound like any other alert, but it lands on a DEFAULT-importance
     * channel and carries CATEGORY_REMINDER, because it is a deadline and not an
     * event. No re-check action either — re-running the check cannot renew a
     * certificate, and offering it would imply otherwise.
     */
    fun notifyCertExpiry(
        monitor: Monitor,
        level: CertificateWatch.Level,
        daysLeft: Long,
        expiresAt: Long,
        issuer: String,
        policy: AlertPolicy,
        silent: Boolean,
    ) {
        val host = monitor.prettyHost.substringBefore('/')
        val channelId = channelFor(policy, Severity.CERT, silent)
        val notification = baseBuilder(channelId, monitor, silent)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(CertificateWatch.headline(level, daysLeft, host))
            .setContentText(
                if (level == CertificateWatch.Level.EXPIRED) {
                    "Clients are refusing this connection now"
                } else {
                    "Expires ${certDateFormat.format(Date(expiresAt))}"
                },
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        if (level == CertificateWatch.Level.EXPIRED) {
                            append("The TLS certificate for $host expired on ")
                            append(certDateFormat.format(Date(expiresAt)))
                            append(". Every client that checks it is refusing the connection.")
                        } else {
                            append("The TLS certificate for $host is valid until ")
                            append(certDateFormat.format(Date(expiresAt)))
                            append(". Renew it before then or the site stops answering.")
                        }
                        if (issuer.isNotBlank()) append("\n\nIssued by $issuer")
                        append("\n\n").append(monitor.url)
                    },
                ),
            )
            .setColor(
                if (level == CertificateWatch.Level.EXPIRED) DOWN_COLOR else DEGRADED_COLOR,
            )
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        post(monitor.id.certNotificationId(), notification)
    }

    fun cancelCert(monitorId: String) = compat.cancel(monitorId.certNotificationId())

    fun certIdOf(monitorId: String): Int = monitorId.certNotificationId()

    // ---- checker health ----------------------------------------------------

    /**
     * "Pulse's own checker is broken" — a different claim from "your site is
     * down", and now a different notification.
     *
     * Up to 1.5.0 this was posted through [notifyDown] with the monitor's name
     * and the words "Checker crashed", so a fault in Pulse (or, far more often,
     * a perfectly ordinary coroutine cancellation) read as an outage on the
     * user's website and escalated into the URGENT nag loop. See
     * [me.river.pulse.domain.CheckerHealth].
     *
     * One deterministic id and one channel per haptic choice, so this can always
     * be updated in place and always be cancelled. The id sits outside the
     * [ALERT_ID_MIN]..[ALERT_ID_MAX] monitor-alert range on purpose: the
     * reconciliation sweep must not treat it as an orphaned monitor alert.
     */
    fun notifyCheckerCrash(
        state: me.river.pulse.domain.CheckerHealth.State,
        monitorName: String,
        policy: AlertPolicy,
        silent: Boolean,
        repeat: Boolean,
    ) {
        // The channel is chosen from the *policy*, never from whether this
        // particular post makes a noise. Deriving it per-post made a repeat land
        // on the quiet channel while the first raise landed on the vibrating one —
        // two channels for one notification, so a user who muted the one they were
        // shown would still be interrupted by the other. A repeat is silenced with
        // `setSilent` instead, which is a property of the post rather than of the
        // channel.
        val channelId = healthChannel(policy.vibrate, policy.vibrationStyle)
        val quiet = silent || repeat
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Pulse can't complete its checks")
            .setContentText("${state.consecutiveErrors} checks in a row failed inside Pulse itself")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append("This is a fault in Pulse, not in the sites you are watching — ")
                        append("their status is unchanged and no outage is implied.")
                        append("\n\nLast error: ")
                        append(state.lastSignature.ifBlank { "unknown" })
                        if (state.lastDetail.isNotBlank()) {
                            append("\n").append(state.lastDetail.take(200))
                        }
                        append("\nWhile checking: ").append(monitorName)
                        append("\n\nThis clears itself as soon as one check completes.")
                    },
                ),
            )
            .setColor(DEGRADED_COLOR)
            .setColorized(true)
            .setPriority(
                if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openMonitorIntent(""))
            .setSilent(quiet)
            // Never ongoing. An un-dismissable notification about *our* bug would
            // be adding insult to injury, and the state behind it is
            // process-scoped anyway.
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
        val posted = post(CHECKER_HEALTH_NOTIFICATION_ID, notification)
        // Only the first raise is felt, and only if the notification explaining it
        // actually arrived. A repeat exists so the notice does not silently rot
        // after being swiped away, not to nag about our own bug.
        if (posted && policy.vibrate && !quiet && channelCanAlert(channelId)) {
            previewVibration(policy.vibrationStyle)
        }
    }

    fun cancelCheckerHealth() = compat.cancel(CHECKER_HEALTH_NOTIFICATION_ID)

    /** Vibration is frozen into a channel at creation, so there is one of each. */
    fun healthChannel(vibrate: Boolean, style: VibrationStyle): String {
        val id = if (vibrate) "$HEALTH_CHANNEL.${style.name.lowercase()}" else "$HEALTH_CHANNEL.novib"
        if (manager.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(
            id,
            if (vibrate) "Checker health · ${style.label}" else "Checker health",
            if (vibrate) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW,
        ).apply {
            group = GROUP_HEALTH
            description = "Raised only when Pulse's own checking code repeatedly fails. " +
                "Never raised for delayed background work, lost connectivity or battery saver."
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = style.pattern
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        return id
    }

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

    enum class Severity { DOWN, DEGRADED, RECOVERY, CERT }

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
            NotificationChannelGroup(GROUP_CERT, "Certificate alerts"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_URGENT, "Urgent"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_HEALTH, "Checker health"),
        )
    }

    /**
     * Urgent gets its own channel family so the user can leave it screaming
     * while turning ordinary down alerts down — and so Do Not Disturb can be
     * configured to let it through.
     */
    fun urgentChannel(policy: AlertPolicy, respectRinger: Boolean = true): String {
        val style = policy.vibrationStyle
        // v2, and the version suffix is load-bearing. Android freezes a channel's
        // importance, sound and DND bypass at creation and ignores every later
        // change, so the 1.1.0-era `pulse.urgent.*` channels on an existing
        // install could never be given `setBypassDnd`. A new id is the only way
        // the fix reaches a device that already ran an older build.
        // The ringer choice is part of the id because a channel's sound and its
        // audio attributes are frozen at creation. Without this, a user who turned
        // ringer-respect on kept the alarm-usage channel and still got one
        // full-volume chime per post on a phone set to vibrate.
        val streamTag = if (respectRinger) "ring" else "alarm"
        val id = "$URGENT_CHANNEL_V2.${style.name.lowercase()}.$streamTag"
        if (manager.getNotificationChannel(id) != null) return id
        runCatching { manager.deleteNotificationChannel("pulse.urgent.${style.name.lowercase()}") }
        runCatching { manager.deleteNotificationChannel("$URGENT_CHANNEL_V2.${style.name.lowercase()}") }
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
                    .setUsage(
                        if (respectRinger) {
                            AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                        } else {
                            AudioAttributes.USAGE_ALARM
                        },
                    )
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

    /**
     * The persistent notification strict mode runs behind.
     *
     * With a [timeline] on API 36+ this is a live update: the check history drawn
     * as `ProgressStyle`'s segmented line, a chip in the status bar, and an
     * expanded card on the lock screen. See [LiveCard] for what disqualifies that
     * — in short, nothing on this builder may be a custom view or colorised, and
     * neither is.
     */
    fun serviceNotification(
        title: String,
        body: String,
        stopIntent: PendingIntent?,
        timeline: LiveTimeline.Timeline? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(context, ensureServiceChannel())
            // The brand mark rather than a status glyph: this is the only notification
            // that reports on Pulse itself instead of on a monitor, so it is the only
            // one where "which app is this" is the useful thing to show. A refresh
            // arrow here read as a monitor being re-checked, which it never was.
            .setSmallIcon(R.drawable.ic_stat_brand)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openMonitorIntent(""))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // The line replaces the expanded paragraph rather than joining it: a
        // notification carries one style, and the platform draws no big text
        // alongside ProgressStyle.
        val live = timeline != null && LiveCard.apply(context, builder, timeline)
        if (!live) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        if (stopIntent != null) {
            builder.addAction(R.drawable.ic_stat_mute, "Stop strict mode", stopIntent)
        }
        // Built through LiveCard when there is a line, because whether the card has
        // to be colourised to earn its chip is a question only the device can
        // answer — see [LiveCard.earnPromotion]. The action above is added first so
        // both candidate builds carry it.
        return if (live && timeline != null) {
            LiveCard.earnPromotion(context, builder, timeline)
        } else {
            builder.build()
        }
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
            Severity.CERT -> "pulse.cert."
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
            // A certificate with nine days left has nothing to say at 3am. DEFAULT
            // means it lands in the shade and waits, which is the correct urgency
            // for a deadline rather than an outage.
            severity == Severity.CERT -> NotificationManager.IMPORTANCE_DEFAULT
            sound == SoundChoice.SILENT && !vibrateOn -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        val label = buildString {
            append(
                when (severity) {
                    Severity.DOWN -> "Down · "
                    Severity.DEGRADED -> "Slow · "
                    Severity.RECOVERY -> "Recovery · "
                    Severity.CERT -> "Certificate · "
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
                Severity.CERT -> GROUP_CERT
            }
            description = when (severity) {
                Severity.DOWN -> "Raised when a monitor starts failing (${sound.label.lowercase()})."
                Severity.DEGRADED -> "Raised when a monitor breaches its latency budget."
                Severity.RECOVERY -> "Raised when a monitor recovers (${sound.label.lowercase()})."
                Severity.CERT -> "Raised when a TLS certificate is approaching its expiry date."
            }
            enableVibration(vibrateOn)
            if (vibrateOn) vibrationPattern = style.pattern
            enableLights(severity == Severity.DOWN)
            lightColor = when (severity) {
                Severity.DOWN -> DOWN_COLOR
                Severity.DEGRADED, Severity.CERT -> DEGRADED_COLOR
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

    /** @return whether the notification actually reached the shade. */
    private fun post(id: Int, notification: Notification): Boolean {
        if (!hasNotificationPermission()) return false
        return runCatching { compat.notify(id, notification); true }.getOrDefault(false)
    }

    /**
     * Whether a channel is still allowed to interrupt.
     *
     * Needed because the actuator is driven directly for repeats (channel
     * vibration only fires on the first post of an id on some OEM builds), and a
     * direct `vibrate()` call answers to nothing — not the channel being turned
     * off, not `POST_NOTIFICATIONS` being denied. Without this check, the standard
     * user remedy for an unwanted buzz ("long-press → turn this channel off")
     * silences the notification and leaves the buzz: a vibration for an event the
     * user cannot see and has no remaining control over.
     */
    private fun channelCanAlert(channelId: String): Boolean = runCatching {
        if (!compat.areNotificationsEnabled()) return@runCatching false
        val channel = manager.getNotificationChannel(channelId) ?: return@runCatching true
        channel.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(true)

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
        private const val GROUP_CERT = "pulse.group.cert"
        /** Date only: an expiry to the second is precision nobody can act on. */
        private val certDateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        private const val GROUP_URGENT = "pulse.group.urgent"
        private const val GROUP_HEALTH = "pulse.group.health"
        private const val NOTIFICATION_GROUP = "pulse.alerts"
        const val SERVICE_CHANNEL = "pulse.service.strict"
        const val HEALTH_CHANNEL = "pulse.health.checker"

        /**
         * Versioned on purpose. A channel's importance, sound and DND bypass are
         * frozen at creation, so correcting any of them means a new id and
         * deleting the old one — see [urgentChannel].
         */
        const val URGENT_CHANNEL_V2 = "pulse.urgent.v2"
        const val SERVICE_NOTIFICATION_ID = 4242

        /**
         * Deliberately next to the service id and far away from the monitor-alert
         * ranges below, so [activeAlertIds] never sees it and the reconciliation
         * sweep never cancels it out from under us.
         */
        const val CHECKER_HEALTH_NOTIFICATION_ID = 4243
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

internal fun String.certNotificationId(): Int = 400_000 + (hashCode() and 0x7FFF)
