package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.update.UpdateInstaller
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UpdateState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taking the release from inside the app.
 *
 * The APK served here is the one this test is running against, read back off
 * disk. That is not a shortcut: it is the only archive on the device whose
 * package name and signing certificate match the installed copy, which is
 * exactly what [UpdateInstaller] checks before it hands anything to Android. A
 * fixture APK would pass the download and fail the check, and the check is the
 * interesting half.
 *
 * The install prompt itself belongs to the system and is not driven from here.
 * What is asserted is everything up to it: the transfer runs and reports, the
 * archive is accepted, and the session reaches the point where Android is
 * asking.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var server: TinyHttpServer
    private val graph get() = Nightbell.install(appContext)
    private val now = System.currentTimeMillis()

    /** A version newer than this build, whatever this build happens to be. */
    private val newer: String
        get() {
            val core = BuildConfig.VERSION_NAME.trim().removePrefix("v")
                .takeWhile { it.isDigit() || it == '.' }
            val major = core.split('.').mapNotNull { it.toIntOrNull() }.getOrElse(0) { 0 }
            return "${major + 1}.0.0"
        }

    /**
     * The platform grant this whole surface needs.
     *
     * Set through the shell rather than assumed: `canRequestPackageInstalls` is
     * an app op, `GrantPermissionRule` cannot touch it, and a fresh emulator has
     * it off, so without this the suite would assert against the Settings button
     * every time and never exercise a download at all.
     */
    private fun allowInstalls() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${appContext.packageName} REQUEST_INSTALL_PACKAGES allow",
        ).close()
        awaitTrue(description = "the install app op is on") { graph.installer.canRequestInstall() }
    }

    @Before
    fun setUp() {
        graph.installer.cancel()
        server = TinyHttpServer { request ->
            when {
                request.path.startsWith("/nightbell.apk") -> TinyHttpServer.Response(
                    bytes = File(appContext.applicationInfo.sourceDir).readBytes(),
                    contentType = "application/vnd.android.package-archive",
                )

                request.path.startsWith("/junk.apk") -> TinyHttpServer.Response(
                    bytes = ByteArray(4096) { it.toByte() },
                    contentType = "application/vnd.android.package-archive",
                )

                else -> TinyHttpServer.Response(code = 404, reason = "Not Found")
            }
        }
    }

    @After
    fun tearDown() {
        allowInstalls()
        graph.installer.cancel()
        runCatching { scenario?.close() }
        scenario = null
        server.close()
    }

    private fun seed(
        apkPath: String,
        size: Long = 0L,
        withMonitor: Boolean = true,
        theme: ThemeChoice = ThemeChoice.DARK,
    ) {
        val version = newer
        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(
                    monitors = if (!withMonitor) emptyList() else listOf(
                        Monitor(
                            id = "site",
                            name = "Marketing site",
                            kind = MonitorKind.HTTP_STATUS,
                            url = "https://example.com",
                            createdAt = now,
                        ),
                    ),
                    runtimes = if (!withMonitor) emptyMap() else mapOf(
                        "site" to MonitorRuntime(
                            health = Health.UP,
                            lastCheckedAt = now - 60_000,
                            lastLatencyMs = 180,
                            lastCode = 200,
                            samples = List(6) {
                                Sample(at = now - (6 - it) * 900_000L, ok = true, latencyMs = 180, code = 200)
                            },
                        ),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = theme,
                        hasSeenPagerSetup = true,
                        updateChecksEnabled = true,
                    ),
                    update = UpdateState(
                        lastCheckedAt = now,
                        latestVersion = version,
                        latestUrl = "https://github.com/riveerxd/nightbell/releases/tag/v$version",
                        latestApkUrl = server.url(apkPath),
                        latestApkSize = size,
                    ),
                ),
            )
        }
    }

    private fun stage(): UpdateInstaller.Stage = graph.installer.stage.value

    @Test
    fun the_popup_offers_both_the_notes_and_the_release() {
        allowInstalls()
        seed("/nightbell.apk", size = File(appContext.applicationInfo.sourceDir).length())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-banner").assertIsDisplayed()
        composeRule.onNodeWithTag("update-whats-new").assertIsDisplayed()
        composeRule.onNodeWithTag("update-install").assertIsDisplayed()
        // The fleet is still readable underneath, which is the whole reason the
        // popup pads the list down instead of floating over it.
        composeRule.onNodeWithText("Marketing site").assertIsDisplayed()
        composeRule.captureDeviceScreenshot("73-update-popup")
    }

    /**
     * Settings makes the same offer, for someone who came looking rather than
     * being found. One composable behind both, so this is really a check that it
     * is wired in and reachable by scrolling.
     */
    @Test
    fun settings_makes_the_same_offer() {
        allowInstalls()
        seed("/nightbell.apk", size = File(appContext.applicationInfo.sourceDir).length())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // Closed first, because the modal is over the whole dashboard and
        // Settings is behind it. Closing defers this version for a day and
        // touches nothing else: the Settings offer is gated on the version being
        // newer, which a deferral does not change, and that is the point of
        // checking the offer from here at all.
        composeRule.onNodeWithContentDescription("Not now").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("update-install"))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-install").assertIsDisplayed()
        composeRule.onNodeWithTag("update-whats-new").assertIsDisplayed()
        composeRule.captureDeviceScreenshot("76-settings-update-offer")
    }

    /**
     * A fresh install has no monitors and its own first-run layout under the
     * popup. Worth a look because the popup pads the list down by its own height,
     * and an empty list is the one case where there is nothing to push.
     */
    @Test
    fun the_popup_sits_over_the_first_run_screen_without_covering_it() {
        allowInstalls()
        seed("/nightbell.apk", withMonitor = false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-banner").assertIsDisplayed()
        composeRule.captureDeviceScreenshot("78-update-popup-first-run")
    }

    /**
     * The same notice in the light scheme.
     *
     * Sky and the primary gradient were both chosen against black, and a surface
     * that floats over the grid has to keep an edge when the ground under it is
     * pale rather than dark.
     */
    @Test
    fun the_popup_holds_its_edge_in_the_light_scheme() {
        allowInstalls()
        seed("/nightbell.apk", size = 15_400_000L, theme = ThemeChoice.LIGHT)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-banner").assertIsDisplayed()
        composeRule.captureDeviceScreenshot("80-update-popup-light")
    }

    /**
     * The half of the install a test can own.
     *
     * This asserts the transfer runs and reports, the archive passes the package
     * name and certificate checks, and the session is handed to Android. It stops
     * there, because what comes next is a system dialog this process cannot see,
     * let alone answer, and a test named after a prompt it never observes is a
     * test lying about its own coverage.
     *
     * The other half was driven by hand on an API 34 emulator: a debug build at
     * versionCode 34 was served a genuine versionCode 35 over loopback, Install
     * was tapped, Android's "Do you want to update this app?" appeared, and after
     * confirming it `dumpsys package` read versionCode=35, versionName=3.6.0-debug
     * and `installerPackageName=me.river.nightbell.debug`, which is the line that
     * proves the session came from the app rather than from adb. Monitors
     * survived and the notice removed itself, installed now equalling latest.
     */
    @Test
    fun installing_downloads_the_release_and_hands_the_session_to_android() {
        allowInstalls()
        seed("/nightbell.apk", size = File(appContext.applicationInfo.sourceDir).length())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // Every stage the run passes through, recorded as it happens. Sampling
        // `stage.value` after the fact would miss the transfer entirely on
        // loopback, where 15 MB arrives faster than a test can look twice.
        val seen = CopyOnWriteArrayList<UpdateInstaller.Stage>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            graph.installer.stage.collect { seen += it }
        }
        try {
            composeRule.onNodeWithTag("update-install").performClick()

            awaitTrue(timeoutMs = 60_000, description = "the archive is accepted and handed over") {
                seen.any { it is UpdateInstaller.Stage.Installing }
            }
            assertTrue(
                "the transfer should report bytes as they arrive, saw $seen",
                seen.any { it is UpdateInstaller.Stage.Downloading && it.received > 0L },
            )
            assertTrue(
                "nothing should have failed, saw ${seen.filterIsInstance<UpdateInstaller.Stage.Failed>()}",
                seen.none { it is UpdateInstaller.Stage.Failed },
            )
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun something_that_is_not_a_package_is_refused_and_says_so() {
        allowInstalls()
        seed("/junk.apk", size = 4096)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("update-install").performClick()
        awaitTrue(timeoutMs = 30_000, description = "the download is refused") {
            stage() is UpdateInstaller.Stage.Failed
        }
        composeRule.waitForIdle()
        // The reason is on the screen rather than in logcat, because a download
        // that silently does nothing is the failure this whole surface replaces.
        composeRule.onNodeWithTag("update-failure").assertIsDisplayed()
        composeRule.captureDeviceScreenshot("75-update-refused")
    }

}
