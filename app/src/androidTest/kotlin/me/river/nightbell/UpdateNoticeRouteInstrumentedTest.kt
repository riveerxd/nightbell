package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.alerts.AlertCenter
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the update notification sends somebody.
 *
 * It sent them to a web page, always. The app has been able to download and
 * hand over the APK itself since the in-app installer shipped, the dashboard
 * banner has offered exactly that ever since, and the notification routed around
 * all of it into a browser, so the user fetched the file by hand and installed
 * it by hand.
 *
 * **Why this is a device test and not only a JVM one.** The decision lives in
 * `AppUpdate.noticeRoute` and is covered on the JVM, but a test of the decision
 * would have passed the whole time the notification was wrong: the bug was that
 * `notifyUpdate` never asked. So this asserts the notification, built by the real
 * `AlertCenter`, posted to the real shade, and the actual `Intent` a tap fires.
 */
@RunWith(AndroidJUnit4::class)
class UpdateNoticeRouteInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val alerts: AlertCenter get() = Nightbell.install(appContext).alerts

    private val notificationManager: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private val installable = AppUpdate.Release(
        version = "3.9.0",
        url = "https://github.com/riveerxd/nightbell/releases/tag/v3.9.0",
        source = UpdateSource.GITHUB,
        apkUrl = "https://github.com/riveerxd/nightbell/releases/download/v3.9.0/app.apk",
        apkSize = 15_000_000L,
    )

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        alerts.cancelUpdate()
    }

    @After
    fun tearDown() {
        alerts.cancelUpdate()
    }

    // ---- the intent a tap actually fires ------------------------------------

    @Test
    fun tappingTheNoticeOpensNightbellWhenThereIsAnApkToInstall() {
        val intent = alerts.updateTapIntent(installable)
        // The app, by class, not "some activity in our package".
        assertEquals(
            MainActivity::class.java.name,
            intent.component?.className,
        )
        assertEquals(appContext.packageName, intent.component?.packageName)
        // No browser is involved: an http data URI is exactly what the old code
        // built, and this one is the app's own scheme.
        assertEquals("nightbell", intent.data?.scheme)
        assertEquals("update", intent.data?.host)
        // And it asks to be taken to the update rather than only asking for the
        // app, because resuming the task lands wherever the user left it.
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_SHOW_UPDATE, false))
    }

    /**
     * The update intent and a monitor page must not look alike to the activity
     * manager.
     *
     * `Intent.filterEquals` ignores extras, so two intents that differ only by
     * one are the same request: the second brings the task to the front and
     * `onNewIntent` never fires, which would leave the update tap doing nothing
     * at all for anybody who had tapped a page notification first.
     */
    @Test
    fun theUpdateIntentIsDistinctFromAMonitorPage() {
        val update = alerts.updateTapIntent(installable)
        val monitorPage = Intent(update).apply {
            data = android.net.Uri.parse("nightbell://monitor/abc123")
        }
        assertFalse(update.filterEquals(monitorPage))
    }

    @Test
    fun tappingTheNoticeOpensThePageWhenThereIsNothingToInstall() {
        val intent = alerts.updateTapIntent(installable.copy(apkUrl = ""))
        // The one case where a page is the only route there is.
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data?.scheme)
        assertTrue(intent.component == null)
    }

    @Test
    fun aReleaseWithNoPageAndNoApkStillGoesSomewhere() {
        val intent = alerts.updateTapIntent(
            installable.copy(apkUrl = "", url = ""),
        )
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nightbell.app", intent.data?.host)
    }

    // ---- the notification as it reaches the shade ---------------------------

    @Test
    fun theNoticeReachesTheShadeAndOffersTheReleaseNotesRatherThanADownload() {
        alerts.notifyUpdate(installable, installedVersion = "3.8.0", policy = AlertPolicy())
        awaitTrue(description = "the update notification reached the shade") {
            notificationManager.activeNotifications.any { it.id == UPDATE_ID }
        }
        val posted = notificationManager.activeNotifications.first { it.id == UPDATE_ID }
        val titles = posted.notification.actions.orEmpty().map { it.title.toString() }

        // "Open download" was the button that walked out of the app. It is gone
        // when there is something to install, replaced by the one thing a page
        // is still good for.
        assertFalse("the browser button is still there: $titles", titles.contains("Open download"))
        assertTrue("no way to read the notes: $titles", titles.contains("What's new"))
        // The answers the user can give about a version are untouched.
        assertTrue(titles.contains("Remind later"))
        assertTrue(titles.contains("Ignore this version"))
        assertNotNull(posted.notification.contentIntent)
    }

    @Test
    fun aReleaseWithNothingToInstallKeepsItsDownloadButton() {
        alerts.notifyUpdate(
            installable.copy(apkUrl = ""),
            installedVersion = "3.8.0",
            policy = AlertPolicy(),
        )
        awaitTrue(description = "the update notification reached the shade") {
            notificationManager.activeNotifications.any { it.id == UPDATE_ID }
        }
        val posted = notificationManager.activeNotifications.first { it.id == UPDATE_ID }
        val titles = posted.notification.actions.orEmpty().map { it.title.toString() }
        assertTrue("the only route out was removed: $titles", titles.contains("Open download"))
    }

    /**
     * Android's own answer to "who would receive this", rather than this app's.
     *
     * Everything above asserts an object this process built. This asks the
     * package manager to resolve it, which is the same lookup the system does
     * when somebody taps the notification, so a component that pointed at
     * nothing or at somebody else would fail here.
     *
     * Sending the `PendingIntent` for real was tried and does not work from a
     * test: the launch is correct and Android blocks it anyway, because a test
     * process has no visible window and background activity launch hardening
     * refuses it. Logcat says so in as many words, "Without BAL hardening this
     * activity start would be allowed", with the right component on the intent.
     * A notification tap comes from SystemUI, which does have that privilege.
     */
    @Test
    fun androidResolvesTheNoticeToNightbellItself() {
        val intent = alerts.updateTapIntent(installable)
        val resolved = appContext.packageManager.resolveActivity(intent, 0)
        assertNotNull("nothing would handle the notification tap", resolved)
        assertEquals(appContext.packageName, resolved?.activityInfo?.packageName)
        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }

    /** And the page case resolves to something that is not this app. */
    @Test
    fun androidResolvesAPageOnlyReleaseAwayFromNightbell() {
        val intent = alerts.updateTapIntent(installable.copy(apkUrl = ""))
        val resolved = appContext.packageManager.resolveActivity(intent, 0)
        // An image with no browser resolves to nothing at all, which is still
        // not this app, and is the case the in-app route exists to avoid.
        assertTrue(
            "a page-only release was routed back into Nightbell",
            resolved?.activityInfo?.packageName != appContext.packageName,
        )
    }

    @Test
    fun theBodyDescribesWhereTheTapGoes() {
        alerts.notifyUpdate(installable, installedVersion = "3.8.0", policy = AlertPolicy())
        awaitTrue(description = "the update notification reached the shade") {
            notificationManager.activeNotifications.any { it.id == UPDATE_ID }
        }
        val posted = notificationManager.activeNotifications.first { it.id == UPDATE_ID }
        val big = posted.notification.extras
            .getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        // The sentence and the destination were allowed to disagree once: the
        // body promised a download page while the app could install it itself.
        assertTrue("body was: $big", big.contains("open Nightbell and install it from there"))
        assertFalse(big.contains("opens the download page"))
    }

    private companion object {
        val UPDATE_ID = AlertCenter.UPDATE_NOTIFICATION_ID
    }
}
