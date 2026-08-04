package me.river.pulse

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
import me.river.pulse.data.alerts.UrgentPageActions
import me.river.pulse.data.alerts.UrgentPageContent
import me.river.pulse.data.alerts.UrgentPagePreviewService
import me.river.pulse.data.alerts.UrgentPageStyle
import me.river.pulse.data.alerts.UrgentPageStyles
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Second pass over the URGENT heads-up, driven by what the first pass showed on
 * a real device rather than by what the documentation implies:
 *
 *  - `CallStyle` renders the call container, but the app's status icon is not an
 *    avatar and the platform stretched it into a large white triangle.
 *  - `setColorized(true)` was dropped outright — the card stayed on the system
 *    surface colour and only the small icon was red.
 *  - A custom `RemoteViews` **did** paint red, but the heads-up slot is roughly
 *    two short lines tall and the monitor name was sliced in half.
 *
 * So this pass fixes the avatar, compacts the custom layout, and posts two of the
 * candidates through a genuine foreground service — the one context in which the
 * platform honours colorisation.
 */
@RunWith(AndroidJUnit4::class)
class UrgentHeadsUpRoundTwoTest {

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
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Urgent page (design)", NotificationManager.IMPORTANCE_HIGH).apply {
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
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1_200)
    }

    private fun shell(command: String) =
        instrumentation.uiAutomation.executeShellCommand(command).close()

    private fun actions(): UrgentPageActions {
        fun noop(request: Int) = PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreen = PendingIntent.getActivity(
            context,
            190,
            Intent(context, UrgentAlertActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return UrgentPageActions(
            acknowledge = noop(191),
            open = noop(192),
            recheck = noop(193),
            mute = noop(194),
            fullScreen = fullScreen,
        )
    }

    private fun save(name: String) {
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Posted the ordinary way, through `NotificationManager.notify`. */
    private fun capturePlain(style: UrgentPageStyle, id: Int, name: String) {
        manager.cancelAll()
        Thread.sleep(400)
        manager.notify(id, UrgentPageStyles.build(context, style, CHANNEL, content, actions()))
        Thread.sleep(1_800)
        save(name)
        manager.cancel(id)
    }

    /**
     * Posted as a foreground-service notification, which is what makes
     * `setColorized(true)` mean anything.
     */
    private fun captureForeground(style: UrgentPageStyle, name: String) {
        manager.cancelAll()
        Thread.sleep(400)
        context.startForegroundService(
            UrgentPagePreviewService.intent(context, style, CHANNEL, content),
        )
        Thread.sleep(2_200)
        save(name)
        context.startService(UrgentPagePreviewService.stopIntent(context))
        Thread.sleep(600)
    }

    @Test
    fun callWithAvatar() = capturePlain(UrgentPageStyle.CALL_AVATAR, 9_101, "r2-1-call-avatar")

    @Test
    fun redBandCompact() =
        capturePlain(UrgentPageStyle.CUSTOM_DECORATED, 9_102, "r2-2-red-band-compact")

    @Test
    fun redFullBleed() = capturePlain(UrgentPageStyle.RED_FULL_BLEED, 9_103, "r2-3-red-full-bleed")

    @Test
    fun colorizedForegroundBanner() =
        captureForeground(UrgentPageStyle.RED_BANNER, "r2-4-red-colorized-fgs")

    @Test
    fun colorizedForegroundCall() =
        captureForeground(UrgentPageStyle.CALL_AVATAR, "r2-5-call-colorized-fgs")

    /** The chosen look, rebuilt so the buttons say what they actually do. */
    @Test
    fun callCustomWhiteOnRed() =
        captureForeground(UrgentPageStyle.CALL_CUSTOM, "r3-1-call-custom")

    /** Same card drawn by the platform, dark enough to get white type. */
    @Test
    fun callDarkRedSystem() =
        captureForeground(UrgentPageStyle.CALL_DARK_RED, "r3-2-call-dark-red")

    /** The chosen card posted the ORDINARY way, to see if it still reads red. */
    @Test
    fun callCustomPlainPost() =
        capturePlain(UrgentPageStyle.CALL_CUSTOM, 9_110, "r6-call-custom-plain")

    /** The call card with its button labels rewritten after build(). */
    @Test
    fun callRelabelled() = captureForeground(UrgentPageStyle.CALL_RELABELLED, "r5-call-relabelled")

    /** System-drawn deep-red card with our three actions. */
    @Test
    fun redCardSystem() = captureForeground(UrgentPageStyle.RED_CARD, "r4-red-card")

    private companion object {
        const val CHANNEL = "pulse.design.urgentpage"
    }
}
