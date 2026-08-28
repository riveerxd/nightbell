package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end journeys driven through the real Compose UI on a real device,
 * against a real HTTP server running inside the test process (reachable from the
 * app over loopback).
 */
@RunWith(AndroidJUnit4::class)
class NightbellE2ETest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var server: TinyHttpServer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        server = TinyHttpServer { request ->
            when {
                request.path.startsWith("/ok") -> TinyHttpServer.Response(body = "NIGHTBELL-OK")
                request.path.startsWith("/broken") -> TinyHttpServer.Response(
                    code = 503,
                    reason = "Service Unavailable",
                    body = "maintenance",
                )
                request.path.startsWith("/json") -> TinyHttpServer.Response(
                    body = """{"status":"green","build":42}""",
                    contentType = "application/json",
                )
                else -> TinyHttpServer.Response(code = 404, reason = "Not Found", body = "nope")
            }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    // ---- 1. launch & empty state -------------------------------------------

    @Test
    fun appLaunchesToAPolishedEmptyState() {
        launchApp()
        composeRule.onNodeWithText("NIGHTBELL").assertIsDisplayed()
        // The empty state is no longer a single "Create a monitor" button over a
        // "Nothing on the radar" headline. It offers templates that pre-fill the
        // wizard, so what has to be on screen is the invitation plus a way past it.
        composeRule.onNodeWithText("Watch something").assertIsDisplayed()
        composeRule.onNodeWithText("A website").assertIsDisplayed()
        composeRule.onNodeWithText("A health endpoint").assertIsDisplayed()
        composeRule.onNodeWithText("Start from scratch").assertIsDisplayed()
        composeRule.onNodeWithText("No monitors yet").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add a monitor").assertIsDisplayed()
        composeRule.captureScreenshot("01-empty-state")
    }

    /**
     * A template has to land on the URL field with its decisions already made.
     *
     * The whole value of picking one is skipping step 0, so this asserts on the
     * step counter as well as the pre-filled expectation — a template that opened
     * the wizard at the beginning would look like it had done nothing.
     */
    @Test
    fun aTemplateSeedsTheWizardAndSkipsTheKindStep() {
        launchApp()
        composeRule.onNodeWithText("A health endpoint").performClick()
        composeRule.waitForIdle()
        // Step 2 of 4: "Target".
        composeRule.onNodeWithText("2/4").assertIsDisplayed()
        composeRule.onNodeWithText("Target").assertIsDisplayed()
        composeRule.captureScreenshot("38-template-seeded-setup")

        // Nothing is saved by picking a template.
        assertEquals(0, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
    }

    // ---- 2. create a passing 200 monitor ------------------------------------

    @Test
    fun createsAndRunsAPassingStatusMonitor() {
        launchApp()
        createMonitor(name = "Healthy API", url = server.url("/ok"))

        awaitTrue(description = "monitor stored") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }
        awaitTrue(description = "monitor reports UP") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.health == Health.UP
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Healthy API").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Operational").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Operational").onFirst().assertIsDisplayed()
        // The fleet banner's verdict, straight from Summary.headline.
        composeRule.onNodeWithText("All 1 operational").assertIsDisplayed()
        composeRule.captureScreenshot("02-dashboard-passing")

        val snapshot = runBlocking { Nightbell.require().store.currentSnapshot() }
        val runtime = snapshot.runtimes.values.first()
        assertEquals(200, runtime.lastCode)
        assertTrue("expected a recorded sample", runtime.samples.isNotEmpty())
    }

    // ---- 3. failing status code shows clean failure UX ----------------------

    @Test
    fun failingStatusMonitorShowsAReadableFailure() {
        launchApp()
        createMonitor(name = "Broken service", url = server.url("/broken"))

        awaitTrue(description = "monitor reports DOWN") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.health == Health.DOWN
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Broken service").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Down").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 of 1 is down").assertIsDisplayed()

        val runtime = runBlocking { Nightbell.require().store.currentSnapshot().runtimes.values.first() }
        assertEquals(503, runtime.lastCode)
        assertTrue(
            "failure message should name the code, was '${runtime.lastMessage}'",
            runtime.lastMessage.contains("503"),
        )
        composeRule.captureScreenshot("03-dashboard-failing")
    }

    // ---- 4. body expectation monitor ----------------------------------------

    @Test
    fun bodyExpectationDrivesPassAndFail() {
        launchApp()
        // Passing: the /ok endpoint really does contain NIGHTBELL-OK.
        createMonitor(
            name = "Body match",
            url = server.url("/ok"),
            bodyContains = "NIGHTBELL-OK",
        )
        awaitTrue(description = "body assertion passes") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.health == Health.UP
            }
        }
        composeRule.captureScreenshot("04-body-assertion-pass")

        // Failing: same endpoint, text that isn't there.
        NightbellTestSupport.resetApp()
        composeRule.waitForIdle()
        createMonitor(
            name = "Body mismatch",
            url = server.url("/ok"),
            bodyContains = "TOTALLY-ABSENT",
        )
        awaitTrue(description = "body assertion fails") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.health == Health.DOWN
            }
        }
        val runtime = runBlocking { Nightbell.require().store.currentSnapshot().runtimes.values.first() }
        assertTrue(
            "expected a body-mismatch message, was '${runtime.lastMessage}'",
            runtime.lastMessage.contains("does not contain"),
        )
        assertEquals(200, runtime.lastCode)
    }

    // ---- 5. detail screen ----------------------------------------------------

    @Test
    fun detailScreenShowsHistoryAndConfiguration() {
        launchApp()
        createMonitor(name = "Detail target", url = server.url("/json"))
        awaitTrue(description = "first check completed") {
            runBlocking {
                (Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.samples?.size ?: 0) > 0
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Detail target").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Configuration"))
        composeRule.onNodeWithContentDescription("Configuration").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Recent checks"))
        composeRule.onNodeWithContentDescription("Recent checks").assertIsDisplayed()
        composeRule.onNodeWithTag("detail-list").performScrollToIndex(0)
        composeRule.captureScreenshot("05-detail")

        // By tag, not by label. The label is "Check now" where there is room for
        // it and "Check" where there is not, and which one a 5-inch emulator gets
        // is not what this test is about.
        composeRule.onNodeWithTag("detail-check").performClick()
        awaitTrue(description = "second sample recorded") {
            runBlocking {
                (Nightbell.require().store.currentSnapshot().runtimes.values.firstOrNull()?.samples?.size ?: 0) >= 2
            }
        }
    }

    // ---- 6. persistence across activity restart ------------------------------

    @Test
    fun monitorsSurviveAnAppRestart() {
        launchApp()
        createMonitor(name = "Persistent one", url = server.url("/ok"))
        awaitTrue(description = "monitor persisted") {
            runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
        }

        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Persistent one").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Persistent one").assertIsDisplayed()
        val stored = runBlocking { Nightbell.require().store.currentSnapshot() }
        assertEquals(1, stored.monitors.size)
        assertEquals("Persistent one", stored.monitors.first().name)
        assertTrue(stored.runtimes.values.first().samples.isNotEmpty())
    }

    // ---- 7. settings, alert policy, vibration & sound ------------------------

    @Test
    fun settingsExposeAlertSoundVibrationAndEscalation() {
        launchApp()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        scrollSettingsTo(hasContentDescription("Default alert policy"))
        composeRule.onNodeWithContentDescription("Default alert policy").assertIsDisplayed()

        // Sound choices
        scrollSettingsTo(hasText("Silent"))
        composeRule.onNodeWithText("Silent").assertIsDisplayed()
        scrollSettingsTo(hasText("Alarm tone"))
        composeRule.onNodeWithText("Alarm tone").assertIsDisplayed()

        // Haptic styles
        scrollSettingsTo(hasText("Heartbeat"))
        composeRule.onNodeWithText("Heartbeat").assertIsDisplayed()
        scrollSettingsTo(hasText("S · O · S"))
        composeRule.onNodeWithText("S · O · S").performClick()
        composeRule.waitForIdle()
        awaitTrue(description = "vibration style persisted") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().settings.defaultAlert.vibrationStyle.name == "SOS"
            }
        }

        scrollSettingsTo(hasText("Alarm tone"))
        composeRule.onNodeWithText("Alarm tone").performClick()
        awaitTrue(description = "sound choice persisted") {
            runBlocking {
                Nightbell.require().store.currentSnapshot().settings.defaultAlert.sound.name == "ALARM"
            }
        }

        scrollSettingsTo(hasText("Keep reminding me"))
        composeRule.onNodeWithText("Keep reminding me").performClick()
        awaitTrue(description = "repeat toggle persisted") {
            runBlocking { Nightbell.require().store.currentSnapshot().settings.defaultAlert.repeatEnabled }
        }

        scrollSettingsTo(hasText("Silence overnight"))
        composeRule.onNodeWithText("Silence overnight").performClick()
        awaitTrue(description = "quiet hours persisted") {
            runBlocking { Nightbell.require().store.currentSnapshot().settings.defaultAlert.quietHoursEnabled }
        }

        composeRule.captureScreenshot("06-settings-alerts")

        scrollSettingsTo(hasText("Send a test alert"))
        composeRule.onNodeWithText("Send a test alert").performClick()
        composeRule.waitForIdle()
    }

    // ---- 8. setup wizard validation -----------------------------------------

    @Test
    fun setupWizardBlocksInvalidInputAndExplainsWhy() {
        launchApp()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What to watch").assertIsDisplayed()
        composeRule.onNodeWithText("Request & response").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        // A bare host: valid characters, but no scheme.
        composeRule.onNodeWithContentDescription("URL").performTextInput("example.com")
        composeRule.waitForIdle()
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        // The message shows twice by design: inline under the field, and again in
        // the test panel explaining why "Test now" is unavailable.
        val schemeError = composeRule.onAllNodes(hasText("Start with http:// or https://"))
        assertEquals(2, schemeError.fetchSemanticsNodes().size)
        schemeError.onFirst().performScrollTo().assertIsDisplayed()
        composeRule.captureScreenshot("07-setup-validation")

        // The wizard refuses to advance while the URL is unusable.
        val storedBefore = runBlocking { Nightbell.require().store.currentSnapshot().monitors.size }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("URL").assertIsDisplayed()
        assertEquals(storedBefore, runBlocking { Nightbell.require().store.currentSnapshot().monitors.size })
    }

    // ---- helpers -------------------------------------------------------------

    private fun scrollSettingsTo(matcher: SemanticsMatcher) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(matcher)
        composeRule.waitForIdle()
    }


    /** Walks the four-step wizard with the Compose UI, exactly as a user would. */
    private fun createMonitor(name: String, url: String, bodyContains: String? = null) {
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()

        if (bodyContains != null) {
            composeRule.onNodeWithText("Request & response").performClick()
        } else {
            composeRule.onNodeWithText("Status check").performClick()
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Name").performTextInput(name)
        composeRule.onNodeWithContentDescription("URL").performTextInput(url)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        if (bodyContains != null) {
            composeRule.onNodeWithText("Contains").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Expected text")
                .performScrollTo()
                .performTextInput(bodyContains)
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
    }
}
