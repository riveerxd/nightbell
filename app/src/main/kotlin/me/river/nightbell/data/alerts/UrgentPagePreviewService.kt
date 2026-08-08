package me.river.nightbell.data.alerts

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import me.river.nightbell.MainActivity

/**
 * Posts a sample URGENT page as a genuine foreground-service notification.
 *
 * Exists because `setColorized(true)` — the only way to ask the system to paint
 * the whole notification card in the down colour — is honoured *only* for
 * foreground-service, media and call notifications. Posting the same builder
 * through `NotificationManager.notify` gets the flag silently dropped, which is
 * why the shipped `notifyUrgent` has never rendered red despite asking to.
 *
 * Used by the design harness to compare the colorised card against the other
 * candidates, and reusable behind a "preview the urgent page" control in
 * Settings for the same reason [AlertCenter.previewPolicy] exists: nobody should
 * have to wait for a real outage to find out what their pager looks like.
 *
 * Stops itself after [TIMEOUT_MS] so a preview can never become a permanent
 * notification the user has to hunt down.
 */
class UrgentPagePreviewService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val style = intent?.getStringExtra(EXTRA_STYLE)
            ?.let { runCatching { UrgentPageStyle.valueOf(it) }.getOrNull() }
            ?: UrgentPageStyle.RED_BANNER
        val channelId = intent?.getStringExtra(EXTRA_CHANNEL) ?: return stopped()

        val content = UrgentPageContent(
            monitorName = intent.getStringExtra(EXTRA_NAME) ?: "A monitor",
            headline = intent.getStringExtra(EXTRA_HEADLINE) ?: "Not responding",
            url = intent.getStringExtra(EXTRA_URL) ?: "",
            downFor = intent.getStringExtra(EXTRA_DOWN_FOR) ?: "",
            failedChecks = intent.getIntExtra(EXTRA_FAILED, 1),
            reminderNumber = intent.getIntExtra(EXTRA_REMINDER, 0),
            repeatMinutes = intent.getIntExtra(EXTRA_REPEAT, 5),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, UrgentPagePreviewService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (intent.action == ACTION_STOP) return stopped()

        val notification = UrgentPageStyles.build(
            context = this,
            style = style,
            channelId = channelId,
            content = content,
            actions = UrgentPageActions(
                acknowledge = stop,
                open = open,
                recheck = open,
                mute = stop,
            ),
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.e(TAG, "Could not post the preview page", it) }
        return START_NOT_STICKY
    }

    private fun stopped(): Int {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "UrgentPagePreview"
        const val NOTIFICATION_ID = 4244
        const val TIMEOUT_MS = 30_000L
        const val ACTION_STOP = "me.river.nightbell.action.PREVIEW_STOP"
        const val EXTRA_STYLE = "style"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NAME = "name"
        const val EXTRA_HEADLINE = "headline"
        const val EXTRA_URL = "url"
        const val EXTRA_DOWN_FOR = "down_for"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_REPEAT = "repeat"

        fun intent(
            context: Context,
            style: UrgentPageStyle,
            channelId: String,
            content: UrgentPageContent,
        ): Intent = Intent(context, UrgentPagePreviewService::class.java)
            .putExtra(EXTRA_STYLE, style.name)
            .putExtra(EXTRA_CHANNEL, channelId)
            .putExtra(EXTRA_NAME, content.monitorName)
            .putExtra(EXTRA_HEADLINE, content.headline)
            .putExtra(EXTRA_URL, content.url)
            .putExtra(EXTRA_DOWN_FOR, content.downFor)
            .putExtra(EXTRA_FAILED, content.failedChecks)
            .putExtra(EXTRA_REMINDER, content.reminderNumber)
            .putExtra(EXTRA_REPEAT, content.repeatMinutes)

        fun stopIntent(context: Context): Intent =
            Intent(context, UrgentPagePreviewService::class.java).setAction(ACTION_STOP)
    }
}
