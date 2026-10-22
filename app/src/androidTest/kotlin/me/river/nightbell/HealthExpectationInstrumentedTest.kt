package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.HealthStatus
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A staging service that is red on purpose: one part marked as expected from
 * the detail screen stops the pages, and the first change to that part pages
 * again. Real checks through the engine against a local server, real taps.
 */
@RunWith(AndroidJUnit4::class)
class HealthExpectationInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var server: TinyHttpServer? = null
    private val body = AtomicReference("")
    @Volatile private var served = 0

    @Before
    fun setUp() {
        notifications.cancelAll()
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                theme = ThemeChoice.DARK,
                masterAlertsEnabled = true,
                defaultAlert = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0, quickRetry = false),
                confirmOutagesEnabled = false,
                latencyBaselineEnabled = false,
            ),
        )
        server = TinyHttpServer {
            served++
            TinyHttpServer.Response(code = 503, reason = "Service Unavailable", body = body.get(), contentType = "application/json")
        }
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "staging",
                    name = "Staging API",
                    kind = MonitorKind.HEALTH_ENDPOINT,
                    url = server!!.url("/health"),
                    timeoutSeconds = 5,
                    useGlobalAlerts = true,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        server?.close()
        notifications.cancelAll()
    }

    /** The message changes on every check, the way a staging report's latency does. */
    private fun serve(db: String, mail: String = "up") {
        body.set(
            """{"healthReport":1,"status":"${if (db == "up" && mail == "up") "up" else "down"}",""" +
                """"components":[{"id":"db","name":"Database","status":"$db","message":"Replica lag ${served * 7 + 3}s"},""" +
                """{"id":"mail","name":"Mail","status":"$mail"}]}""",
        )
    }

    private fun check() = runBlocking { graph.engine.run("staging") }

    private fun runtime(): MonitorRuntime = runBlocking { graph.store.currentSnapshot().runtimes["staging"] } ?: MonitorRuntime()

    private fun paged(): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence("android.title")?.contains("Staging API") == true
    }

    private fun openDetail() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Staging API").performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    @Test
    fun markedAsExpectedItStopsPagingAndAChangePagesAgain() {
        serve(db = "down")
        check()
        assertEquals(Health.DOWN, runtime().health)
        awaitTrue(description = "a down page for Staging API") { paged() }

        openDetail()
        scrollTo("health-expect-db")
        composeRule.captureScreenshot("expected-01-down-before")
        composeRule.onNodeWithTag("health-expect-db").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the part recorded as expected") { runtime().expectedParts == mapOf("db" to HealthStatus.DOWN) }
        val marked = runtime()
        assertEquals(Health.UP, marked.health)
        assertFalse(marked.alerting)
        awaitTrue(description = "the down page taken down") { !paged() }
        // Out of Needs you, which is gone with nothing left in it, and into its own group.
        composeRule.onNodeWithTag("health-needs-you").assertDoesNotExist()
        scrollTo("health-unexpect-db")
        composeRule.onNodeWithTag("health-expected-db").assertExists()
        composeRule.captureScreenshot("expected-02-marked")
        composeRule.onNodeWithTag("detail-list").performScrollToIndex(0)
        composeRule.onNodeWithTag("health-verdict-word").assertTextEquals("Operational")
        composeRule.onNodeWithTag("health-verdict-reason")
            .assertTextEquals("Database is down, and you marked that expected. Nothing else needs you.")
        composeRule.onNodeWithTag("health-parts-summary").assertTextEquals("1 expected · 1 up")
        composeRule.captureScreenshot("expected-02b-hero")

        // Still down, with a new message: not a change, so nothing pages and
        // nothing says it is back up either.
        serve(db = "down")
        val quiet = check()!!
        assertTrue(quiet.ok)
        assertFalse("a still-down expected part paged", paged())
        assertEquals(Health.UP, runtime().health)

        pressBack()
        composeRule.onNodeWithText("1 of 2 OK · 1 expected").assertExists()
        composeRule.captureScreenshot("expected-03-dashboard")

        // Another part going down is not covered and pages.
        serve(db = "down", mail = "down")
        assertFalse(check()!!.ok)
        awaitTrue(description = "a page for the mail part") { paged() }
        notifications.cancelAll()

        // The expected part recovers, which drops the expectation, and its next
        // outage pages like any other.
        serve(db = "up")
        check()
        assertEquals(emptyMap<String, HealthStatus>(), runtime().expectedParts)
        notifications.cancelAll()
        serve(db = "down")
        assertFalse(check()!!.ok)
        awaitTrue(description = "a page for the next outage") { paged() }
        composeRule.captureScreenshot("expected-04-paged-again")
    }

    @Test
    fun stopExpectingTurnsItBackDownAndTheNextCheckPages() {
        serve(db = "down")
        check()
        runBlocking { graph.engine.expectPart("staging", "db") }
        notifications.cancelAll()
        assertEquals(Health.UP, runtime().health)

        openDetail()
        // On the row itself, no expanding first.
        scrollTo("health-unexpect-db")
        composeRule.captureScreenshot("expected-05-open-to-undo")
        composeRule.onNodeWithTag("health-unexpect-db").performClick()
        composeRule.waitForIdle()

        awaitTrue(description = "the expectation removed") { runtime().expectedParts.isEmpty() }
        assertEquals(Health.DOWN, runtime().health)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("expected-06-undone")

        serve(db = "down")
        assertFalse(check()!!.ok)
        awaitTrue(description = "a page after Stop expecting") { paged() }
    }

    @Test
    fun anExpectedPartSurvivesRotation() {
        serve(db = "down")
        check()
        runBlocking { graph.engine.expectPart("staging", "db") }
        openDetail()
        scrollTo("health-parts-summary")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        scrollTo("health-parts-summary")
        composeRule.onNodeWithTag("health-parts-summary").assertTextEquals("1 expected · 1 up")
        composeRule.captureScreenshot("expected-07-landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        composeRule.waitForIdle()
    }

    @Test
    fun markedFromThePartSheetAndUndoneFromTheToast() {
        serve(db = "down")
        check()
        notifications.cancelAll()
        openDetail()
        scrollTo("health-part-db")
        composeRule.onNodeWithTag("health-part-db").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health-sheet-expect").assertExists()
        composeRule.onNode(hasText("Replica lag", substring = true) and hasAnyAncestor(hasTestTag("health-sheet"))).assertExists()
        composeRule.captureDeviceScreenshot("expected-08-sheet")

        // The toast takes itself down on a timer, which the test clock would run
        // through in one pump: stepped by hand from here.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("health-sheet-expect").performClick()
            stepUntil("the Undo toast") { composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
            stepUntil("the part recorded as expected") { runtime().expectedParts.isNotEmpty() }
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.captureScreenshot("expected-09-toast")
            composeRule.onNodeWithText("Undo").performClick()
            stepUntil("the undo to land") { runtime().expectedParts.isEmpty() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertEquals(Health.DOWN, runtime().health)
        composeRule.waitForIdle()
        scrollTo("health-expect-db")
        composeRule.captureScreenshot("expected-10-undone")
    }

    /**
     * Reported from real use: a monitor whose sign-in had once ended kept saying
     * "Not checking" over a history of checks that had passed since, because
     * nothing ever cleared the problem a failed sign-in wrote.
     */
    @Test
    fun aCheckThatReachesTheServiceEndsAnOldSignInProblem() {
        runBlocking {
            graph.store.updateRuntime("staging") {
                it.copy(report = it.report.copy(auth = HealthAuth(problem = AuthProblem.SESSION_ENDED, detail = "invalid_grant", since = 1L)))
            }
        }
        serve(db = "down")
        assertFalse(check()!!.ok)
        assertEquals(AuthProblem.NONE, runtime().report.auth.problem)

        openDetail()
        composeRule.onNodeWithTag("health-verdict-word").assertTextEquals("Down")
        composeRule.onNodeWithTag("health-expect-db").assertExists()
        composeRule.captureScreenshot("expected-11-sign-in-cleared")
    }

    private fun stepUntil(what: String, budgetFrames: Int = 300, condition: () -> Boolean) {
        repeat(budgetFrames) {
            if (condition()) return
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
        throw AssertionError("never happened within $budgetFrames frames: $what")
    }

    private fun pressBack() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }
}
