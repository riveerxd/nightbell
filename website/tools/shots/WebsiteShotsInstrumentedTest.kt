package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.screenshotDir
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.check.CheckEngine
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.data.work.NightbellMonitorService
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.ElementMode
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GitHubWatch
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MetricComparison
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PromQueryRule
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.SoundChoice
import me.river.nightbell.domain.StatusExpectation
import me.river.nightbell.domain.StatusMode
import me.river.nightbell.domain.Summary
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UpdateSource
import me.river.nightbell.domain.VibrationStyle
import me.river.nightbell.ui.theme.NightbellTheme
import me.river.nightbell.ui.urgent.UrgentAlertScreen
import me.river.nightbell.ui.urgent.UrgentAlertUi
import me.river.nightbell.ui.urgent.UrgentAlertVariant
import me.river.nightbell.widget.NightbellWidgetProvider
import me.river.nightbell.widget.WidgetConfig
import java.io.File
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Website captures. Kept outside app/ between runs; copy it into androidTest,
 * run one method at a time, pull files/screenshots, delete it again.
 *
 * Run on the Pixel_6_API_34 AVD with its display overridden to 1440x3200 at
 * 560 dpi, the same 411dp canvas as stock. Built from a copy of the tree with
 * src/devMark and src/debug/res removed, so the bell is the release blue and the
 * system shows the app as Nightbell rather than Nightbell debug.
 */
@RunWith(AndroidJUnit4::class)
class WebsiteShotsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val manager: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun deviceShot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(screenshotDir(), "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun history(
        now: Long,
        count: Int,
        seed: Int,
        base: Long,
        failingTail: Int = 0,
        intervalMinutes: Int = 5,
    ): List<Sample> {
        val rng = Random(seed)
        return (0 until count).map { i ->
            val wobble = (sin(i / 3.4) * base * 0.18).toLong() + rng.nextInt(-12, 18)
            val ok = i < count - failingTail
            Sample(
                at = now - (count - i) * intervalMinutes * 60_000L,
                ok = ok,
                latencyMs = if (ok) (base + wobble).coerceAtLeast(30) else 0,
                code = if (ok) 200 else 0,
                note = if (ok) "" else "Connection refused",
            )
        }
    }

    private fun seed(checkoutDown: Boolean, theme: ThemeChoice = ThemeChoice.DARK) {
        val now = System.currentTimeMillis()
        val monitors = listOf(
            Monitor(
                id = "web-checkout",
                name = "Checkout API",
                url = "https://api.nightbell.app/health",
                intervalMinutes = 5,
                accent = 0,
                createdAt = now,
            ),
            Monitor(
                id = "web-auth",
                name = "Auth service",
                url = "https://auth.nightbell.app/livez",
                intervalMinutes = 5,
                accent = 1,
                createdAt = now,
            ),
            Monitor(
                id = "web-site",
                name = "Marketing site",
                url = "https://www.nightbell.app",
                intervalMinutes = 15,
                accent = 2,
                createdAt = now,
            ),
            Monitor(
                id = "web-shop",
                name = "Price watch · Widget Deluxe",
                kind = MonitorKind.WEBSITE_ELEMENT,
                url = "https://shop.nightbell.app/widget-deluxe",
                element = ElementTarget(
                    cssSelector = "span[data-testid=\"price\"]",
                    tagName = "span",
                    textSnippet = "£42.00",
                    mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                ),
                intervalMinutes = 60,
                accent = 3,
                createdAt = now,
            ),
            Monitor(
                id = "web-cdn",
                name = "Asset CDN",
                url = "https://cdn.nightbell.app/build/app.js",
                status = StatusExpectation(StatusMode.ANY_SUCCESS),
                intervalMinutes = 30,
                accent = 4,
                createdAt = now,
            ),
        )

        fun up(seedValue: Int, base: Long, latency: Long, ago: Long, interval: Int) = MonitorRuntime(
            health = Health.UP,
            lastCheckedAt = now - ago,
            lastLatencyMs = latency,
            lastCode = 200,
            consecutiveSuccesses = 40,
            samples = history(now, 40, seedValue, base, intervalMinutes = interval),
        )

        val checkout = if (checkoutDown) {
            MonitorRuntime(
                health = Health.DOWN,
                lastCheckedAt = now - 2 * 60_000L - 10_000L,
                lastLatencyMs = 0,
                lastCode = 0,
                lastMessage = "Connection refused",
                lastDetail = "Connect to api.nightbell.app:443 failed: Connection refused",
                consecutiveFailures = 4,
                alerting = true,
                lastAlertAt = now - 20 * 60_000L,
                samples = history(now, 40, 11, 140, failingTail = 4),
            )
        } else {
            up(11, 140, 132, 40_000, 5)
        }

        val runtimes = mapOf(
            "web-checkout" to checkout,
            "web-auth" to up(22, 95, 96, 70_000, 5),
            "web-site" to up(33, 310, 284, 4 * 60_000L, 15),
            "web-shop" to up(44, 820, 790, 12 * 60_000L, 60).copy(lastElementText = "£42.00", lastCode = 200),
            "web-cdn" to up(55, 60, 54, 9 * 60_000L, 30),
        )

        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = monitors,
                    runtimes = runtimes,
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = theme,
                        // Stored order, so Checkout API leads the list the way the hero copy reads.
                        dashboardSort = me.river.nightbell.domain.MonitorQuery.Sort.MANUAL,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        updateSourceChosen = true,
                        updateSourceMigratedToSite = true,
                        defaultAlert = AlertPolicy(
                            sound = SoundChoice.ALARM,
                            vibrationStyle = VibrationStyle.HEARTBEAT,
                            failureThreshold = 2,
                            repeatEnabled = true,
                        ),
                    ),
                ),
            )
        }
    }

    private fun launch() {
        // Nothing has touched the screen yet, so the window starts out of touch
        // mode and every button the test clicks wears its keyboard focus disc.
        instrumentation.setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.waitForIdle()
    }

    @Test
    fun calmDashboard() {
        seed(checkoutDown = false)
        launch()
        composeRule.captureScreenshot("hd-calm-dashboard")
        deviceShot("hd-calm-dashboard-device")
    }

    @Test
    fun calmDashboardLight() {
        seed(checkoutDown = false, theme = ThemeChoice.LIGHT)
        launch()
        composeRule.captureScreenshot("hd-calm-dashboard-light")
        deviceShot("hd-calm-dashboard-light-device")
    }

    @Test
    fun downDashboardAndDetail() {
        seed(checkoutDown = true)
        launch()
        composeRule.captureScreenshot("hd-dashboard-down")
        deviceShot("hd-dashboard-down-device")
        composeRule.onNodeWithText("Checkout API").performClick()
        composeRule.waitForIdle()
        Thread.sleep(600)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("hd-detail-failing")
        deviceShot("hd-detail-failing-device")
    }

    @Test
    fun wizard() {
        seed(checkoutDown = false)
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("hd-wizard-1")
        deviceShot("hd-wizard-1-device")
        composeRule.onNodeWithText("Request & response").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Name").performTextInput("Orders service")
        composeRule.onNodeWithContentDescription("URL").performTextInput("https://api.nightbell.app/orders/health")
        composeRule.waitForIdle()
        // The keyboard is not part of the picture; the field still shows what was typed.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("hd-wizard-2")
        deviceShot("hd-wizard-2-device")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("hd-wizard-3")
        deviceShot("hd-wizard-3-device")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("hd-wizard-4")
        deviceShot("hd-wizard-4-device")
        composeRule.onNodeWithContentDescription("Cancel setup").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun pagerSetup() {
        NightbellTestSupport.resetAppAtPagerSetup(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
        launch()
        composeRule.captureScreenshot("hd-pager-setup")
        deviceShot("hd-pager-setup-device")
    }

    /**
     * A real urgent outage: port 1 refuses at once, the engine folds it, the
     * foreground service turns its notification into the red page. The outage
     * clock is pushed back so the card reads like a page that has been ringing.
     */
    private fun raiseUrgentPage() {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                strictForegroundMonitoring = true,
                defaultAlert = AlertPolicy(
                    sound = SoundChoice.ALARM,
                    vibrate = true,
                    vibrationStyle = VibrationStyle.HEARTBEAT,
                    failureThreshold = 1,
                ),
            ),
        )
        manager.cancelAll()
        val graph = Nightbell.install(appContext)
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "web-urgent",
                    name = "Checkout API",
                    url = "http://127.0.0.1:1/health",
                    urgent = true,
                    urgentRepeatMinutes = 5,
                    timeoutSeconds = 5,
                    intervalMinutes = 5,
                ),
            )
            graph.engine.run("web-urgent")
            graph.store.updateRuntime("web-urgent") {
                it.copy(urgentSinceAt = System.currentTimeMillis() - (20 * 60_000L + 8_000L))
            }
        }
        NightbellMonitorService.sync(appContext)
        NightbellTestSupport.awaitTrue(timeoutMs = 20_000, description = "service is paging") {
            NightbellMonitorService.isPaging()
        }
    }

    private fun logPosted(label: String) {
        manager.activeNotifications.forEach {
            android.util.Log.i(
                "WebsiteShots",
                "$label id=${it.id} tag=${it.tag} channel=${it.notification.channelId} " +
                    "title=${it.notification.extras.getCharSequence("android.title")}",
            )
        }
    }

    private fun standDown() {
        runBlocking {
            Nightbell.install(appContext).engine.acknowledgeUrgent("web-urgent")
            NightbellTestSupport.resetApp()
        }
        manager.cancelAll()
    }

    /**
     * Sets the wallpaper the lockscreen and home screen captures are taken over.
     * The emulator's stock one read as an unfinished demo. The picture is pushed
     * into the app's own files as wallpaper.png before this runs; setting it
     * borrows the shell's SET_WALLPAPER, which the app does not hold.
     */
    @Test
    fun setWallpaper() {
        val bitmap = BitmapFactory.decodeFile(java.io.File(appContext.filesDir, "wallpaper.png").path)
            ?: error("push files/wallpaper.png first")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.SET_WALLPAPER)
        try {
            WallpaperManager.getInstance(appContext)
                .setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun urgentOnLockscreen() {
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1_500)
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(1_500)
        try {
            raiseUrgentPage()
            Thread.sleep(2_500)
            deviceShot("hd-lockscreen-alert-collapsed")
            // The red card's expand chevron, on a 1440x3200 display with the
            // ordinary down alert stacked above it. Expanded is what shows the
            // Ack and Re-check buttons, which is the point of the picture.
            val args = InstrumentationRegistry.getArguments()
            shell("input tap ${args.getString("tapX", "1284")} ${args.getString("tapY", "1233")}")
            for (i in 1..3) {
                Thread.sleep(1_200)
                logPosted("lock $i")
                deviceShot("hd-lockscreen-alert-$i")
            }
        } finally {
            standDown()
        }
    }

    /** The page arriving while the screen is off, then the screen coming on. */
    @Test
    fun urgentWakesLockscreen() {
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1_500)
        try {
            raiseUrgentPage()
            Thread.sleep(1_000)
            shell("input keyevent KEYCODE_WAKEUP")
            for ((i, wait) in listOf(300L, 500L, 700L, 1_000L, 1_500L).withIndex()) {
                Thread.sleep(wait)
                deviceShot("hd-lock-wake-${i + 1}")
            }
        } finally {
            standDown()
        }
    }

    /** Unlocked, the page in the pulled-down shade, where the red card opens with its buttons. */
    @Test
    fun urgentInShade() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
        Thread.sleep(1_500)
        try {
            raiseUrgentPage()
            Thread.sleep(6_000)
            shell("cmd statusbar expand-notifications")
            for (i in 1..3) {
                Thread.sleep(1_000)
                logPosted("shade $i")
                deviceShot("hd-urgent-shade-$i")
            }
            shell("cmd statusbar collapse")
        } finally {
            standDown()
        }
    }

    @Test
    fun urgentOverHomeScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Thread.sleep(800)
        // Named rather than KEYCODE_HOME: the default home on the capture AVD
        // is a dock app that looks like a lock screen.
        shell("am start -n com.google.android.apps.nexuslauncher/.NexusLauncherActivity")
        Thread.sleep(1_500)
        try {
            raiseUrgentPage()
            for (i in 1..4) {
                Thread.sleep(700)
                logPosted("home $i")
                deviceShot("hd-urgent-homescreen-$i")
            }
        } finally {
            standDown()
        }
    }

    /** The real full-screen activity, fed the extras a ringing page would carry. */
    @Test
    fun alarmActivity() {
        val intent = Intent(appContext, UrgentAlertActivity::class.java).apply {
            putExtra(UrgentAlertActivity.EXTRA_MONITOR_ID, "web-checkout")
            putExtra(UrgentAlertActivity.EXTRA_MONITOR_NAME, "Checkout API")
            putExtra(UrgentAlertActivity.EXTRA_URL, "https://api.nightbell.app/health")
            putExtra(UrgentAlertActivity.EXTRA_HEADLINE, "Connection refused")
            putExtra(UrgentAlertActivity.EXTRA_DOWN_FOR_MS, 20 * 60_000L + 8_000L)
            putExtra(UrgentAlertActivity.EXTRA_FAILED_CHECKS, 4)
            putExtra(UrgentAlertActivity.EXTRA_REMINDER, 0)
            putExtra(UrgentAlertActivity.EXTRA_REPEAT_MINUTES, 5)
        }
        val alarm = ActivityScenario.launch<UrgentAlertActivity>(intent)
        try {
            for (i in 1..3) {
                Thread.sleep(1_200)
                deviceShot("hd-alarm-page-device-$i")
            }
        } finally {
            alarm.close()
        }
    }

    /**
     * Settings for the kind captures. The latency reference is off because it
     * times a public endpoint, and these checks run against a local server
     * whose round trip that comparison would only blur.
     */
    private fun seedSettings() {
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = ThemeChoice.DARK,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        updateSource = UpdateSource.GITHUB,
                        updateSourceChosen = true,
                        updateSourceMigratedToSite = true,
                        latencyBaselineEnabled = false,
                        defaultAlert = AlertPolicy(
                            sound = SoundChoice.ALARM,
                            vibrationStyle = VibrationStyle.HEARTBEAT,
                            failureThreshold = 2,
                            repeatEnabled = true,
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * The engine runs the checks against a local server at machine speed, so
     * the history it wrote is spread back over the cadence the monitor
     * actually has, newest sample at [end]. Nothing else in the runtime moves.
     */
    private fun spreadHistory(id: String, end: Long, spacingMs: Long) {
        runBlocking {
            Nightbell.install(appContext).store.updateRuntime(id) { runtime ->
                val last = runtime.samples.lastIndex
                runtime.copy(
                    lastCheckedAt = end,
                    samples = runtime.samples.mapIndexed { i, sample ->
                        sample.copy(at = end - (last - i) * spacingMs)
                    },
                    github = if (runtime.github.seeded) {
                        runtime.github.copy(
                            lastPolledAt = end,
                            downloadsReadAt = end,
                        )
                    } else {
                        runtime.github
                    },
                )
            }
        }
    }

    private fun pointAt(id: String, url: String) {
        runBlocking {
            val store = Nightbell.install(appContext).store
            val monitor = store.currentSnapshot().monitors.first { it.id == id }
            store.upsert(monitor.copy(url = url))
        }
    }

    private fun openDetail(name: String) {
        launch()
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
        Thread.sleep(600)
        composeRule.waitForIdle()
    }

    private class Release(val tag: String, val id: Long, val publishedAt: String, val asset: String, val downloads: Int)

    /**
     * The repository's 43 releases as GitHub listed them on 11 October 2026,
     * newest first, with each one's single file and its download count. The
     * newest is held at 4, which is what the maintainer's own phone showed for
     * it at the time, and that puts the totals at 1677 overall and 1662 for the
     * files carrying the current name.
     */
    private val releases: List<Release> = """
        v3.18.0|409746123|2026-10-11T15:17:41Z|Nightbell-3.18.0-release.apk=4;v3.17.0|408861644|2026-10-10T10:08:47Z|Nightbell-3.17.0-release.apk=82;v3.16.0|406133880|2026-10-07T20:24:23Z|Nightbell-3.16.0-release.apk=101;v3.15.0|403383711|2026-10-05T05:12:24Z|Nightbell-3.15.0-release.apk=86;v3.14.0|397770921|2026-09-27T18:57:30Z|Nightbell-3.14.0-release.apk=131;v3.13.0|392360565|2026-09-20T08:16:22Z|Nightbell-3.13.0-release.apk=315;v3.12.0|390715415|2026-09-17T12:54:10Z|Nightbell-3.12.0-release.apk=125;v3.11.0|390004646|2026-09-16T14:32:01Z|Nightbell-3.11.0-release.apk=144;v3.10.0|384307117|2026-09-07T20:37:20Z|Nightbell-3.10.0-release.apk=109;v3.9.0|382931761|2026-09-04T18:16:15Z|Nightbell-3.9.0-release.apk=205;v3.8.0|382671907|2026-09-04T11:23:47Z|Nightbell-3.8.0-release.apk=53;v3.7.0|379742548|2026-08-31T12:34:04Z|Nightbell-3.7.0-release.apk=101;v3.6.0|378996168|2026-08-29T13:32:13Z|Nightbell-3.6.0-release.apk=67;v3.5.0|378299812|2026-08-28T06:36:09Z|Nightbell-3.5.0-release.apk=17;v3.4.0|377909607|2026-08-27T14:54:50Z|Nightbell-3.4.0-release.apk=16;v3.3.0|377727414|2026-08-27T10:13:19Z|Nightbell-3.3.0-release.apk=11;v3.2.1|377665018|2026-08-27T08:21:53Z|Nightbell-3.2.1-release.apk=5;v3.2.0|377657459|2026-08-27T08:09:35Z|Nightbell-3.2.0-release.apk=2;v3.1.1|377361469|2026-08-26T19:11:44Z|Nightbell-3.1.1-release.apk=20;v3.1.0|377240271|2026-08-26T15:18:19Z|Nightbell-3.1.0-release.apk=7;v3.0.5|368612912|2026-08-11T13:59:59Z|Nightbell-3.0.5-release.apk=39;v3.0.4|368212957|2026-08-10T21:43:09Z|Nightbell-3.0.4-release.apk=5;v3.0.3|368204717|2026-08-10T21:22:57Z|Nightbell-3.0.3-release.apk=1;v3.0.2|367901212|2026-08-10T12:42:19Z|Nightbell-3.0.2-release.apk=12;v3.0.1|367495955|2026-08-09T14:12:15Z|Nightbell-3.0.1-release.apk=3;v3.0.0|367311232|2026-08-08T21:49:55Z|Nightbell-3.0.0-release.apk=1;v2.4.3|367012664|2026-08-07T21:20:01Z|Pulse-2.4.3-release.apk=5;v2.4.2|367012645|2026-08-07T21:19:58Z|Pulse-2.4.2-release.apk=0;v2.4.1|367012631|2026-08-07T21:19:56Z|Pulse-2.4.1-release.apk=0;v2.4.0|367012531|2026-08-07T21:19:54Z|Pulse-2.4.0-release.apk=0;v2.3.0|367012519|2026-08-07T21:19:52Z|Pulse-2.3.0-release.apk=0;v2.2.2|367012507|2026-08-07T21:19:49Z|Pulse-2.2.2-release.apk=5;v2.2.1|367012495|2026-08-07T21:19:47Z|Pulse-2.2.1-release.apk=0;v2.2.0|367012484|2026-08-07T21:19:45Z|Pulse-2.2.0-release.apk=0;v2.1.0|367012473|2026-08-07T21:19:43Z|Pulse-2.1.0-release.apk=0;v2.0.0|367012460|2026-08-07T21:19:40Z|Pulse-2.0.0-release.apk=0;v1.7.0|367012444|2026-08-07T21:19:38Z|Pulse-1.7.0-release.apk=0;v1.6.0|367012433|2026-08-07T21:19:35Z|Pulse-1.6.0-release.apk=5;v1.5.0|367012414|2026-08-07T21:19:33Z|Pulse-1.5.0-release.apk=0;v1.4.0|367012401|2026-08-07T21:19:31Z|Pulse-1.4.0-release.apk=0;v1.3.0|367012386|2026-08-07T21:19:28Z|Pulse-1.3.0-release.apk=0;v1.2.0|367012380|2026-08-07T21:19:26Z|Pulse-1.2.0-release.apk=0;v1.1.2|367012365|2026-08-07T21:19:24Z|Pulse-1.1.2-release.apk=0
    """.trim().split(';').map { row ->
        val (tag, id, at, file) = row.split('|')
        Release(tag, id.toLong(), at, file.substringBefore('='), file.substringAfter('=').toInt())
    }

    private fun Release.json(downloads: Int = this.downloads): String {
        val version = tag.removePrefix("v")
        val product = if (asset.startsWith("Pulse")) "Pulse" else "Nightbell"
        return """{"id":$id,"tag_name":"$tag","name":"$product $version",""" +
            """"html_url":"https://github.com/riveerxd/nightbell/releases/tag/$tag",""" +
            """"prerelease":false,"draft":false,"published_at":"$publishedAt",""" +
            """"assets":[{"name":"$asset","download_count":$downloads}]}"""
    }

    /**
     * The repository monitor over the fifteen hours before the capture, the
     * span the maintainer's phone covered: sixty checks at fifteen minutes.
     *
     * A local server answers as api.github.com did, and the real engine and
     * checker read it, so every field on the screen is one a check wrote. Two
     * things happened in that window and both are real: v3.18.0 was published
     * at 15:17 UTC and issue 20 was closed at 16:25 UTC. The star count did
     * not move, so it is 61 throughout rather than a climb nobody saw.
     */
    @Test
    fun githubDetail() {
        seedSettings()
        manager.cancelAll()
        val now = System.currentTimeMillis()
        val end = now - 3 * 60_000L - 20_000L
        val checks = 60
        // A fifteen minute periodic job lands a little late most times, which is
        // why sixty of them covered fifteen hours on the maintainer's phone.
        val spacing = 15 * 60_000L + 18_000L
        val times = (0 until checks).map { end - (checks - 1 - it) * spacing }
        val released = Instant.parse("2026-10-11T15:17:41Z").toEpochMilli()
        val closed = Instant.parse("2026-10-11T16:25:01Z").toEpochMilli()
        var at = times.first()
        val firstAfterRelease = times.indexOfFirst { it >= released }

        val server = TinyHttpServer { request ->
            val path = request.path.substringBefore('?')
            val headers = mapOf(
                "x-ratelimit-limit" to "60",
                "x-ratelimit-remaining" to "54",
                "x-ratelimit-reset" to ((now + 41 * 60_000L) / 1000).toString(),
            )
            val out = at >= released
            // The new release's count climbs to the 4 it read at the end.
            val newest = if (out) (times.indexOf(at) - firstAfterRelease + 1).coerceIn(0, 4) else 0
            val listed = releases.filter { out || it.tag != "v3.18.0" }
            fun json(body: String) =
                TinyHttpServer.Response(body = body, contentType = "application/json", extraHeaders = headers)
            when {
                path.endsWith("/issues/comments") -> json("[]")
                path.endsWith("/issues") -> json(
                    if (at >= closed) {
                        "[]"
                    } else {
                        """[{"id":5741554295,"number":20,"title":"[Feature] Immediate retries","body":"",""" +
                            """"created_at":"2026-10-07T08:03:11Z",""" +
                            """"html_url":"https://github.com/riveerxd/nightbell/issues/20",""" +
                            """"user":{"login":"reporter"}}]"""
                    },
                )
                path.endsWith("/releases/latest") -> json(listed.first().json(newest))
                path.endsWith("/releases") -> json(
                    listed.joinToString(",", "[", "]") { if (it.tag == "v3.18.0") it.json(newest) else it.json() },
                )
                else -> json(
                    """{"full_name":"riveerxd/nightbell","stargazers_count":61,""" +
                        """"open_issues_count":${if (at >= closed) 0 else 1},"forks_count":0,""" +
                        """"subscribers_count":1,"pushed_at":"${if (out) "2026-10-11T15:18:51Z" else "2026-10-10T10:09:12Z"}"}""",
                )
            }
        }
        try {
            val graph = Nightbell.install(appContext)
            val engine = CheckEngine(
                store = graph.store,
                http = HttpChecker(),
                element = ElementChecker(appContext),
                alerts = graph.alerts,
                github = GitHubChecker(
                    settingsFor = { graph.store.snapshot.value.settings },
                    apiBase = server.baseUrl,
                    minGapMs = 0L,
                ),
                updates = UpdateChecker(githubBase = server.baseUrl, fdroidBase = server.baseUrl),
                installedVersion = { "3.18.0" },
            )
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "kind-github",
                        name = "Nightbell",
                        kind = MonitorKind.GITHUB_REPO,
                        url = "https://github.com/riveerxd/nightbell",
                        github = GitHubWatch(
                            owner = "riveerxd",
                            repo = "nightbell",
                            notifyOnComments = true,
                            trackDownloads = true,
                        ),
                        intervalMinutes = 15,
                        accent = 0,
                        createdAt = times.first(),
                    ),
                )
                for (t in times) {
                    at = t
                    engine.run("kind-github")
                }
            }
        } finally {
            server.close()
        }
        spreadHistory("kind-github", end, spacing)
        manager.cancelAll()
        openDetail("Nightbell")
        composeRule.captureScreenshot("kind-github")
        deviceShot("kind-github-device")
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("github-download-metrics"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("kind-github-scrolled")
    }

    /**
     * A PromQL monitor reading an error rate, forty checks at five minutes. The
     * server answers the instant query the way Prometheus does, with a little
     * latency so the chart is not a row of 2 ms bars, and the monitor is then
     * pointed back at the host it was set up with.
     */
    @Test
    fun prometheusDetail() {
        seedSettings()
        manager.cancelAll()
        val rng = Random(42)
        val values = List(40) { i -> if (i == 39) 0.42 else 0.30 + rng.nextInt(0, 22) / 100.0 }
        var i = 0
        val server = TinyHttpServer { request ->
            if (request.path.startsWith("/api/v1/query")) {
                val value = values[i.coerceAtMost(values.lastIndex)]
                TinyHttpServer.Response(
                    body = """{"status":"success","data":{"resultType":"vector","result":""" +
                        """[{"metric":{},"value":[${System.currentTimeMillis() / 1000.0},"$value"]}]}}""",
                    contentType = "application/json",
                    delayMs = 60L + rng.nextLong(0, 70),
                )
            } else {
                TinyHttpServer.Response(code = 404, reason = "Not Found", body = "404 page not found")
            }
        }
        try {
            val graph = Nightbell.install(appContext)
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "kind-prom",
                        name = "Checkout errors",
                        kind = MonitorKind.PROMETHEUS,
                        url = server.baseUrl,
                        prometheus = PrometheusWatch(
                            source = PrometheusSource.QUERY,
                            query = "sum(rate(errors_total[5m]))",
                            queryRule = PromQueryRule.VALUE_PASSES,
                            comparison = MetricComparison.BELOW,
                            threshold = 1.0,
                        ),
                        intervalMinutes = 5,
                        accent = 1,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                while (i < values.size) {
                    graph.engine.run("kind-prom")
                    i++
                }
            }
        } finally {
            server.close()
        }
        pointAt("kind-prom", "https://metrics.nightbell.app")
        spreadHistory("kind-prom", System.currentTimeMillis() - 70_000L, 5 * 60_000L)
        manager.cancelAll()
        openDetail("Checkout errors")
        composeRule.captureScreenshot("kind-prometheus")
        deviceShot("kind-prometheus-device")
    }

    /**
     * The full example from docs/health-format.md, its timestamps moved to
     * the minutes before the capture, served with the 503 such a service
     * sends. Before it the same service reported itself up for three hours.
     */
    @Test
    fun healthDetail() {
        seedSettings()
        manager.cancelAll()
        val now = System.currentTimeMillis()
        fun ago(ms: Long) = Instant.ofEpochMilli(now - ms).toString()
        val full = HealthPayloads.FULL
            .replace("2026-10-09T14:02:11Z", ago(110_000L))
            .replace("2026-10-09T13:58:00Z", ago(5 * 60_000L + 30_000L))
            .replace("2026-10-09T14:01:40Z", ago(141_000L))
        val up = """{"healthReport":1,"status":"up","checkedAt":"${ago(0)}",""" +
            """"service":"payments-api","version":"4.12.0","environment":"production","components":[""" +
            """{"id":"payments","name":"Payments","status":"up","message":"No failed card payments in the last 5 minutes","value":0,"unit":"failures"},""" +
            """{"id":"database","name":"Database","status":"up","value":11,"unit":"ms"},""" +
            """{"id":"email-queue","name":"Email queue","status":"up","value":24}]}"""
        val rng = Random(7)
        val checks = 36
        var i = 0
        val server = TinyHttpServer {
            val down = i >= checks - 2
            TinyHttpServer.Response(
                code = if (down) 503 else 200,
                reason = if (down) "Service Unavailable" else "OK",
                body = if (down) full else up,
                contentType = "application/json",
                delayMs = 70L + rng.nextLong(0, 60),
            )
        }
        try {
            val graph = Nightbell.install(appContext)
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "kind-health",
                        name = "Payments API",
                        kind = MonitorKind.HEALTH_ENDPOINT,
                        url = server.url("/health"),
                        intervalMinutes = 5,
                        accent = 2,
                        createdAt = now,
                    ),
                )
                while (i < checks) {
                    graph.engine.run("kind-health")
                    i++
                }
            }
        } finally {
            server.close()
        }
        pointAt("kind-health", "https://api.nightbell.app/health")
        spreadHistory("kind-health", now - 80_000L, 5 * 60_000L)
        manager.cancelAll()
        openDetail("Payments API")
        composeRule.captureScreenshot("kind-health-top")
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("health-report"))
        composeRule.waitForIdle()
        Thread.sleep(400)
        composeRule.captureScreenshot("kind-health")
        deviceShot("kind-health-device")
    }

    /**
     * The price watch from the dashboard seed, run by the real element checker
     * in a real WebView. A local page plays the shop: the price holds at
     * £42.00, then the last three checks find £58.50, which is the drift the
     * snapshot mode exists to catch. The related grid is there so the page has
     * the weight of a real product page rather than a fixture's thirty nodes.
     */
    @Test
    fun elementDetail() {
        seedSettings()
        manager.cancelAll()
        val related = (1..180).joinToString("") {
            """<li class="card"><a href="/p/$it"><img alt="" src="data:,"><span class="name">Widget $it</span>""" +
                """<span class="price">£${10 + it % 40}.00</span></a></li>"""
        }
        fun page(price: String) = """
            <!doctype html>
            <html><head><title>Widget Deluxe · Nightbell Shop</title></head>
            <body><main><div class="product">
              <h1>Widget Deluxe</h1>
              <span data-testid="price">$price</span>
              <span class="stock">In stock</span>
            </div><ul class="related">$related</ul></main></body></html>
        """.trimIndent()
        val rng = Random(9)
        val checks = 40
        var i = 0
        val server = TinyHttpServer {
            TinyHttpServer.Response(
                body = page(if (i >= checks - 3) "£58.50" else "£42.00"),
                contentType = "text/html; charset=utf-8",
                delayMs = 600L + rng.nextLong(0, 220),
            )
        }
        try {
            val graph = Nightbell.install(appContext)
            runBlocking {
                graph.store.upsert(
                    Monitor(
                        id = "web-shop",
                        name = "Price watch · Widget Deluxe",
                        kind = MonitorKind.WEBSITE_ELEMENT,
                        url = server.url("/widget-deluxe"),
                        element = ElementTarget(
                            cssSelector = "span[data-testid=\"price\"]",
                            tagName = "span",
                            textSnippet = "£42.00",
                            mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                        ),
                        timeoutSeconds = 20,
                        intervalMinutes = 60,
                        accent = 3,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                while (i < checks) {
                    graph.engine.run("web-shop")
                    i++
                }
            }
        } finally {
            server.close()
        }
        pointAt("web-shop", "https://shop.nightbell.app/widget-deluxe")
        spreadHistory("web-shop", System.currentTimeMillis() - 60_000L, 15 * 60_000L)
        manager.cancelAll()
        openDetail("Price watch · Widget Deluxe")
        composeRule.captureScreenshot("hd-detail-element")
        deviceShot("hd-detail-element-device")
    }

    private fun widget(name: String, config: WidgetConfig, widthDp: Int, heightDp: Int, fleet: Summary.Fleet) {
        val density = appContext.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val views = NightbellWidgetProvider.build(appContext, config, fleet, 7, widthDp, heightDp)
        val root = views.apply(appContext, FrameLayout(appContext))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(screenshotDir(), "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun widgets() {
        fun monitor(id: String, name: String) = Monitor(id = id, name = name, url = "https://$id.nightbell.app/health")
        fun runtime(health: Health, latency: Long = 120, message: String = "") = MonitorRuntime(
            health = health,
            lastCheckedAt = System.currentTimeMillis(),
            lastLatencyMs = latency,
            lastMessage = message,
        )
        val fleet = Summary.of(
            listOf(
                monitor("a", "Checkout API"),
                monitor("b", "Auth service"),
                monitor("c", "Marketing site"),
                monitor("d", "Search index"),
                monitor("e", "Asset CDN"),
                monitor("f", "Webhooks"),
            ),
            mapOf(
                "a" to runtime(Health.DOWN, message = "Connection refused"),
                "b" to runtime(Health.UP, latency = 96L),
                "c" to runtime(Health.UP, latency = 284L),
                "d" to runtime(Health.DEGRADED, latency = 3_370L),
                "e" to runtime(Health.UP, latency = 54L),
                "f" to runtime(Health.UP, latency = 204L),
            ),
        )
        widget("hd-widget-tall", WidgetConfig(maxRows = 6), 250, 250, fleet)
        widget("hd-widget-wide", WidgetConfig(maxRows = 6, columns = 2), 320, 180, fleet)
        widget("hd-widget-flat", WidgetConfig(maxRows = 6), 400, 90, fleet)
    }
}

/** Fallback for the alarm page: the same composable, frozen, captured by Compose. */
@RunWith(AndroidJUnit4::class)
class WebsiteAlarmShotInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun alarmPage() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = ThemeChoice.DARK) {
                UrgentAlertScreen(
                    variant = UrgentAlertVariant.BRIEF,
                    ui = UrgentAlertUi(
                        monitorName = "Checkout API",
                        url = "https://api.nightbell.app/health",
                        headline = "Connection refused",
                        downForMs = 20 * 60_000L + 8_000L,
                        failedChecks = 4,
                        reminderNumber = 0,
                        repeatMinutes = 5,
                    ),
                    onAcknowledge = {},
                    onOpen = {},
                    onRecheck = {},
                    modifier = Modifier.fillMaxSize(),
                    animate = false,
                )
            }
        }
        composeRule.captureScreenshot("hd-alarm-page")
    }
}
