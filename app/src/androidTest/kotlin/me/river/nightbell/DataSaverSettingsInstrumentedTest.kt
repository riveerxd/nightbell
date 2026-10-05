package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data saver switch, and the two numbers it is there to show.
 *
 * The fleet is the one from the report, with the per-load costs measured by
 * [RealDataSaverInstrumentedTest] rather than invented: a page monitor cost about
 * 240 KB cold and a few KB warm.
 */
@RunWith(AndroidJUnit4::class)
class DataSaverSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private val fleet = (1..10).map {
        Monitor(
            id = "p$it",
            name = "Site $it",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = "https://site$it.example.com",
            element = ElementTarget(cssSelector = "a.cta"),
            intervalMinutes = 15,
        )
    }

    private fun seed(saver: Boolean, measured: (Int) -> MonitorRuntime) {
        runBlocking {
            Nightbell.install(appContext).store.replaceAll(
                NightbellSnapshot(
                    monitors = fleet,
                    runtimes = fleet.mapIndexed { i, m -> m.id to measured(i) }.toMap(),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        // So no check lands while the screen is open and moves the numbers.
                        backgroundChecksEnabled = false,
                        dataSaver = saver,
                    ),
                ),
            )
        }
    }

    private fun openChecksTab() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("Checks")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("data-saver"))
        composeRule.waitForIdle()
    }

    private fun saverSwitch() = composeRule.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("data-saver")),
        useUnmergedTree = true,
    )

    @Test
    fun bothNumbersShowAndTheSwitchTurnsTheSaverOff() {
        seed(saver = true) { MonitorRuntime(health = Health.UP, bytesFull = 240_000L, bytesSaver = 5_000L) }
        openChecksTab()
        composeRule.onNodeWithText("About 6.9 GB a month off, about 214 MB on.", substring = true)
            .assertExists()
        saverSwitch().assertIsOn()
        composeRule.captureScreenshot("data-saver-01-on")

        composeRule.onNodeWithTag("data-saver").performClick()
        composeRule.waitForIdle()
        saverSwitch().assertIsOff()
        val stored = runBlocking { Nightbell.require().store.currentSnapshot().settings.dataSaver }
        assertFalse("the switch did not reach the store", stored)
        composeRule.captureScreenshot("data-saver-02-off")
    }

    @Test
    fun withTheSaverOffItSaysWhatItCannotKnowYet() {
        seed(saver = false) { MonitorRuntime(health = Health.UP, bytesFull = 240_000L) }
        openChecksTab()
        composeRule.onNodeWithText(
            "About 6.9 GB a month off. Turn it on to measure what it saves.",
            substring = true,
        ).assertExists()
        composeRule.captureScreenshot("data-saver-03-unmeasured-on")
    }

    @Test
    fun aFreshFleetSaysItIsMeasuring() {
        seed(saver = true) { MonitorRuntime() }
        openChecksTab()
        composeRule.onNodeWithText("Measuring", substring = true).assertExists()
        composeRule.captureScreenshot("data-saver-04-fresh")
    }
}
