package me.river.nightbell

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import androidx.test.espresso.Espresso
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AuthProblem
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthState
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The detail screen's report card across the states nobody asks about: no
 * parts, one, three mixed with their rules and log, sixty-four capped at fifty,
 * a disagreement, down, a monitor that cannot sign in, and a rotation.
 *
 * Each state is a real report read by [HealthFormat], seeded into the store, so
 * what is drawn is what a check would have left behind.
 */
@RunWith(AndroidJUnit4::class)
class HealthComponentsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val now = Instant.parse("2026-10-09T14:03:00Z")

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun report(body: String): Pair<Health, HealthState> {
        val reading = HealthFormat.parse(200, body, null, now) as HealthFormat.Reading.Report
        val health = when (reading.verdict) {
            me.river.nightbell.domain.HealthStatus.UP -> Health.UP
            me.river.nightbell.domain.HealthStatus.DEGRADED -> Health.DEGRADED
            me.river.nightbell.domain.HealthStatus.DOWN -> Health.DOWN
        }
        return health to reading.state
    }

    private fun seedAndOpen(name: String, health: Health, state: HealthState, watch: HealthWatch = HealthWatch(), message: String = "") {
        val graph = Nightbell.install(NightbellTestSupport.appContext)
        runBlocking {
            graph.store.upsert(
                Monitor(id = "h", name = name, kind = MonitorKind.HEALTH_ENDPOINT, url = "https://api.example.com/health", health = watch),
            )
            graph.store.updateRuntime("h") {
                MonitorRuntime(health = health, lastCheckedAt = now.toEpochMilli(), lastMessage = message, report = state)
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("health-card-${name.lowercase().replace(' ', '-')}")
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitForIdle()
    }

    private fun showReport() {
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("health-report"))
        composeRule.waitForIdle()
    }

    @Test
    fun noParts() {
        val (health, state) = report(HealthPayloads.MINIMAL)
        seedAndOpen("No parts", health, state)
        composeRule.captureScreenshot("health-detail-01-no-parts")
    }

    @Test
    fun oneDegradedPart() {
        val (health, state) = report(HealthPayloads.ASPNET_DEGRADED)
        seedAndOpen("One degraded", health, state)
        // The verdict names the part the service blamed, not a latency budget it never had.
        composeRule.onNodeWithTag("health-verdict-reason")
            .assertTextEquals("payments: 3 card payments failed in the last 5 minutes")
        showReport()
        composeRule.captureScreenshot("health-detail-02-one-degraded")
    }

    @Test
    fun threeMixedWithRulesAndLog() {
        val (health, state) = report(HealthPayloads.FULL)
        seedAndOpen("Payments API", health, state, message = "Card payments are failing")
        composeRule.onNodeWithTag("health-verdict-reason").assertTextEquals("Card payments are failing")
        composeRule.onNodeWithTag("health-parts-summary").assertTextEquals("1 down · 1 degraded · 1 up")
        showReport()
        // Both failing parts are rows with their action on them, worst first; the
        // rules and log stay off the list until a part is opened.
        composeRule.onNodeWithTag("health-expect-payments").assertExists()
        composeRule.onNodeWithTag("health-expect-email-queue").assertExists()
        composeRule.onNodeWithTag("health-rule-failed-payments").assertDoesNotExist()
        composeRule.captureScreenshot("health-detail-03-three-mixed")

        composeRule.onNodeWithTag("health-part-payments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health-rule-failed-payments").assertExists()
        composeRule.onNodeWithTag("health-rule-queue-depth").assertDoesNotExist()
        // The log shows time and message; the data pairs are behind a tap.
        composeRule.onNodeWithText("Gateway declined payment").assertExists()
        composeRule.onNodeWithText("code=card_declined_by_issuer", substring = true).assertDoesNotExist()
        composeRule.captureDeviceScreenshot("health-detail-04-sheet")
        composeRule.onNodeWithTag("health-sheet").performScrollToNode(hasTestTag("health-log-details-payments"))
        composeRule.onNodeWithTag("health-log-details-payments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("code=card_declined_by_issuer", substring = true).assertExists()
        composeRule.captureDeviceScreenshot("health-detail-05-log-details")

        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("health-part-email-queue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health-rule-queue-depth").assertExists()
        composeRule.onNodeWithTag("health-rule-failed-payments").assertDoesNotExist()
        composeRule.captureDeviceScreenshot("health-detail-06-degraded-sheet")
    }

    @Test
    fun aHealthyPartOpensFromItsChip() {
        val (health, state) = report(HealthPayloads.FULL)
        seedAndOpen("Chips", health, state, message = "Card payments are failing")
        val up = state.components.first { it.status == me.river.nightbell.domain.HealthStatus.UP }
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("health-healthy"))
        composeRule.onNodeWithTag("health-part-${up.id}").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("health-sheet").assertExists()
        // Nothing to mark on a part that is up.
        composeRule.onNodeWithTag("health-sheet-expect").assertDoesNotExist()
        composeRule.captureDeviceScreenshot("health-detail-07-healthy-sheet")
    }

    @Test
    fun sixtyFourCappedAtFifty() {
        val (health, state) = report(HealthPayloads.manyComponents(64))
        seedAndOpen("Sixty four", health, state)
        showReport()
        // The section header prints its title in capitals.
        composeRule.onNodeWithText("Showing 50 of 64 parts").assertExists()
        // A dozen names, then the rest behind one more chip.
        composeRule.onNodeWithTag("health-part-part-12").assertExists()
        composeRule.onNodeWithTag("health-part-part-13").assertDoesNotExist()
        composeRule.captureScreenshot("health-detail-08-capped")
        composeRule.onNodeWithTag("health-healthy-more").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("health-part-part-50"))
        composeRule.captureScreenshot("health-detail-08b-all")
    }

    @Test
    fun aDisagreement() {
        val (health, state) = report(
            """{"healthReport":1,"status":"up","components":[{"id":"pay","name":"Payments","status":"down","message":"gateway refused"},{"id":"db","status":"up"}]}""",
        )
        seedAndOpen("Disagrees", health, state)
        showReport()
        composeRule.onNodeWithTag("health-disagreement").assertExists()
        composeRule.captureScreenshot("health-detail-09-disagreement")
    }

    @Test
    fun notCheckingIsNeutral() {
        val state = HealthState(auth = HealthAuth(problem = AuthProblem.SESSION_ENDED, account = "alice"))
        seedAndOpen(
            "Signed out",
            Health.UNKNOWN,
            state,
            HealthWatch(signIn = HealthSignIn.SSO, issuer = "https://sso.example.com/realms/main", clientId = "admin"),
        )
        // Said in grey in the verdict, never as a last response time or a colour.
        composeRule.onNodeWithTag("health-verdict-word").assertTextEquals("Not checking")
        composeRule.onNodeWithTag("health-verdict-reason").assertTextEquals("Sign in needed: the sign-in has ended")
        composeRule.onNodeWithTag("health-report").assertDoesNotExist()
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("health-sign-in-card"))
        composeRule.onNodeWithTag("health-not-checking").assertExists()
        composeRule.captureScreenshot("health-detail-10-not-checking")
    }

    @Test
    fun rotationKeepsTheCard() {
        val (health, state) = report(HealthPayloads.FULL)
        seedAndOpen("Rotated", health, state, message = "Card payments are failing")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        showReport()
        composeRule.captureScreenshot("health-detail-11-landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        composeRule.waitForIdle()
    }
}
