package me.river.nightbell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.nightbell.data.alerts.UrgentPageActions
import me.river.nightbell.data.alerts.UrgentPageContent
import me.river.nightbell.data.alerts.UrgentPageStyle
import me.river.nightbell.data.alerts.UrgentPageStyles
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion suite — posts each candidate URGENT heads-up for real and
 * captures the whole screen, so the five containers can be compared as the
 * platform actually draws them.
 *
 * A screen capture rather than a Compose capture on purpose: the heads-up is
 * drawn by SystemUI, not by us, and the entire question is what SystemUI does
 * with each mechanism. `CallStyle`, `setColorized` and custom `RemoteViews` are
 * all quietly overridden in ways only a real post reveals.
 */
@RunWith(AndroidJUnit4::class)
class UrgentHeadsUpDesignTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val content = UrgentPageContent(
        monitorName = "Checkout API",
        headline = "Connection refused",
        url = "https://api.river.com/v1/health",
        downFor = "22m 14s",
        failedChecks = 4,
        reminderNumber = 3,
        repeatMinutes = 5,
    )

    @Before
    fun setUp() {
        // A fresh channel id per run of the suite is not needed, but the channel
        // has to exist and has to be HIGH or nothing peeks at all.
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Urgent page (design)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Throwaway channel for the heads-up design comparison."
                    enableVibration(true)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(
                        Settings.System.DEFAULT_ALARM_ALERT_URI,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build(),
                    )
                },
            )
        }
        // Home screen behind the heads-up, so the capture looks like the real
        // thing arriving over whatever the user was doing.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        Thread.sleep(1_200)
    }

    private fun actions(): UrgentPageActions {
        fun noop(request: Int) = PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreen = PendingIntent.getActivity(
            context,
            90,
            Intent(context, UrgentAlertActivity::class.java).apply {
                putExtra(UrgentAlertActivity.EXTRA_MONITOR_NAME, content.monitorName)
                putExtra(UrgentAlertActivity.EXTRA_HEADLINE, content.headline)
                putExtra(UrgentAlertActivity.EXTRA_URL, content.url)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return UrgentPageActions(
            acknowledge = noop(91),
            open = noop(92),
            recheck = noop(93),
            fullScreen = fullScreen,
        )
    }

    private fun capture(style: UrgentPageStyle, id: Int, name: String) {
        manager.cancelAll()
        Thread.sleep(400)
        manager.notify(
            id,
            UrgentPageStyles.build(context, style, CHANNEL, content, actions()),
        )
        // Long enough for the peek to animate in, short enough to still be up:
        // SystemUI retires a heads-up after roughly five seconds.
        Thread.sleep(1_800)
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        manager.cancel(id)
    }

    @Test
    fun callIncoming() = capture(UrgentPageStyle.CALL_INCOMING, 9_001, "headsup-1-call-incoming")

    @Test
    fun callOngoing() = capture(UrgentPageStyle.CALL_ONGOING, 9_002, "headsup-2-call-ongoing")

    @Test
    fun redBanner() = capture(UrgentPageStyle.RED_BANNER, 9_003, "headsup-3-red-banner")

    @Test
    fun loudStandard() = capture(UrgentPageStyle.LOUD_STANDARD, 9_004, "headsup-4-loud-standard")

    @Test
    fun customDecorated() =
        capture(UrgentPageStyle.CUSTOM_DECORATED, 9_005, "headsup-5-custom-decorated")

    private companion object {
        const val CHANNEL = "nightbell.design.urgentpage"
    }
}
