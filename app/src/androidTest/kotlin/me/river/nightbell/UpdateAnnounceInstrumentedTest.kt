package me.river.nightbell

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UpdateState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the notes are after an update.
 *
 * Taking an update from the dashboard notice closed the notice, and with it the
 * only What's new button the app had, so someone who had just updated had no way
 * back to what changed. Now the first launch on a newer version says so with the
 * notes one tap away, and Settings keeps a button for the installed version.
 *
 * [AppUpdateTest] owns when a launch counts as an update. This is the wiring: the
 * toast appears on the dashboard, its action opens the changelog entry for this
 * build, the record is written so it is said once, and the Settings button exists.
 */
@RunWith(AndroidJUnit4::class)
class UpdateAnnounceInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val release = AppUpdate.releaseName(BuildConfig.VERSION_NAME)

    @After
    fun tearDown() {
        scenario?.close()
        monitor?.let(instrumentation::removeMonitor)
    }

    private fun seed(update: UpdateState) {
        val now = System.currentTimeMillis()
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(
                        Monitor(id = "site", name = "Marketing site", kind = MonitorKind.HTTP_STATUS, url = "https://example.com", createdAt = now),
                    ),
                    runtimes = mapOf(
                        "site" to MonitorRuntime(health = Health.UP, lastCheckedAt = now - 60_000, lastLatencyMs = 180, lastCode = 200),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        theme = ThemeChoice.DARK,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        // Off, so the launch check cannot write update state under the test.
                        updateChecksEnabled = false,
                    ),
                    update = update,
                ),
            )
        }
    }

    private fun stored(): UpdateState = runBlocking { Nightbell.install(appContext).store.currentSnapshot().update }

    private fun launch() {
        // The toast dismisses itself on a delay, and an auto-advancing test clock
        // runs that delay out inside the first idle pump. Frozen, it stays up.
        composeRule.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
        NightbellTestSupport.awaitTrue(description = "the launch wrote the version record") {
            stored().lastRunVersion == release
        }
        composeRule.mainClock.advanceTimeBy(500)
    }

    @Test
    fun theFirstLaunchAfterAnUpdateOffersTheNotes() {
        seed(UpdateState(lastRunVersion = "3.0.0", censusSent = true))
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        monitor = instrumentation.addMonitor(filter, null, true)

        launch()
        composeRule.onNodeWithText("Updated to $release").assertExists()
        composeRule.captureDeviceScreenshot("update-announced")

        composeRule.onNodeWithText("What's new").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        // A blocking monitor answers the start itself and no browser opens, so the
        // hit count is what says the link went out.
        NightbellTestSupport.awaitTrue(description = "What's new opened a link") { monitor!!.hits == 1 }
        assertEquals(release, stored().lastRunVersion)
    }

    @Test
    fun theLinkIsTheChangelogEntryForThisRelease() {
        assertEquals("https://nightbell.app/changelog#v$release", AppUpdate.changelogUrl(BuildConfig.VERSION_NAME))
    }

    @Test
    fun aFreshInstallSaysNothingAndStartsTheRecord() {
        seed(UpdateState())
        launch()
        composeRule.onNodeWithText("Updated to $release").assertDoesNotExist()
    }

    @Test
    fun theSameVersionAgainSaysNothing() {
        seed(UpdateState(lastRunVersion = "3.0.0", censusSent = true))
        launch()
        scenario?.close()
        // A second launch on the same version, the record already written.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText("Updated to $release").assertDoesNotExist()
    }

    @Test
    fun settingsKeepsTheNotesForTheInstalledVersion() {
        seed(UpdateState(lastRunVersion = release))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("About")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("installed-changelog"))
        composeRule.waitForIdle()
        assertNotNull(composeRule.onNodeWithText("What's new in $release").fetchSemanticsNode())
        composeRule.captureDeviceScreenshot("settings-installed-changelog")
    }
}
