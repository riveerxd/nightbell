package me.river.nightbell

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What an Alertmanager outage looks like on each of the three surfaces.
 *
 * Issue 14 shipped a list on the detail screen and a sentence everywhere else,
 * and the follow-up on 20 September said the sentence was the problem: "5
 * firing ...." is small, it ends in an ellipsis, and it is the same shape
 * whether one thing is broken or forty. So the thing under test here is not the
 * check, which the JVM suite already pins. It is whether a person who is paged
 * can read what is firing without opening a browser.
 *
 * The fixture is the case a name alone cannot describe: one rule firing on two
 * pods. Every assertion below would pass on a broken build if the rows were
 * allowed to say only `KubePodCrashLooping`.
 */
@RunWith(AndroidJUnit4::class)
class FiringAlertsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var server: TinyHttpServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private var payload: String = PrometheusPayloads.ALERTS_ONE_RULE

    private val graph get() = Nightbell.install(NightbellTestSupport.appContext)
    private val notifications: NotificationManager
        get() = NightbellTestSupport.appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        notifications.cancelAll()
        server = TinyHttpServer { request ->
            if (request.path.startsWith("/api/v2/alerts")) {
                TinyHttpServer.Response(body = payload, contentType = "application/json")
            } else {
                TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }
    }

    @After
    fun tearDown() {
        notifications.cancelAll()
        scenario?.close()
        server.close()
    }

    /** A monitor already pointed at the fixture, checked once, and down. */
    private fun seedAndCheck() {
        // Dark, because this is read at 03:00 and that is the screen the
        // screenshots have to hold up in.
        NightbellTestSupport.resetApp(
            GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK),
        )
        val monitor = Monitor(
            id = "am-test",
            name = "Alertmanager",
            kind = MonitorKind.PROMETHEUS,
            url = server.baseUrl,
            timeoutSeconds = 10,
            useGlobalAlerts = true,
            prometheus = PrometheusWatch(source = PrometheusSource.ALERTMANAGER),
        )
        runBlocking {
            graph.store.upsert(monitor)
            graph.engine.run("am-test")
        }
        awaitTrue(description = "the check to land with its alerts") {
            runBlocking { graph.store.currentSnapshot() }
                .runtimes["am-test"]?.lastAlerts?.isNotEmpty() == true
        }
    }

    private fun openDashboard() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Alertmanager").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Every line the notification drew, in order. */
    private fun postedLines(): List<String> {
        val posted = notifications.activeNotifications.firstOrNull {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString().orEmpty().contains("Alertmanager")
        }
        val lines = posted?.notification?.extras
            ?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            .orEmpty()
        return lines.map { it.toString() }
    }

    @Test
    fun theNotificationDrawsOneRowPerAlert() {
        seedAndCheck()
        awaitTrue(description = "the page to be posted") { postedLines().isNotEmpty() }

        val lines = postedLines()
        // Three alerts, three lines. The paragraph this replaces put the first
        // three names in one sentence and cut the rest at 320 characters.
        assertEquals(lines.joinToString(" | "), 3, lines.size)
        assertEquals(
            listOf(
                "KubePodCrashLooping (critical) · checkout-7f9c · payments",
                "KubePodCrashLooping (critical) · ledger-2b41 · payments",
                "CertificateExpiringSoon (ticket) · gw-01",
            ),
            lines,
        )
        // Worst first here too, and an unranked severity still gets a row rather
        // than being dropped to keep the list tidy.
        val posted = notifications.activeNotifications.first()
        assertEquals(
            "the shade should use the template that lists rows",
            "android.app.Notification\$InboxStyle",
            posted.notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
    }

    @Test
    fun aLongOutageSaysHowManyRowsItIsNotShowing() {
        payload = manyAlerts(62)
        seedAndCheck()
        awaitTrue(description = "the page to be posted") { postedLines().isNotEmpty() }

        assertEquals(7, postedLines().size)
        val summary = notifications.activeNotifications.first()
            .notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        assertEquals("and 55 more", summary)
    }

    @Test
    fun theDashboardCardNamesTheAlertsInsteadOfCountingThem() {
        seedAndCheck()
        openDashboard()

        composeRule.onNodeWithText("3 alerts firing").assertIsDisplayed()
        // The pod is the whole point. Two rows reading KubePodCrashLooping and
        // nothing else is the report this change answers.
        composeRule.onNodeWithText("KubePodCrashLooping · checkout-7f9c · payments")
            .assertIsDisplayed()
        composeRule.onNodeWithText("KubePodCrashLooping · ledger-2b41 · payments")
            .assertIsDisplayed()
        composeRule.onNodeWithText("CertificateExpiringSoon · gw-01").assertIsDisplayed()
        composeRule.captureScreenshot("alerts-01-dashboard-card")
    }

    @Test
    fun aCardStopsAtThreeRowsAndCountsTheRest() {
        payload = manyAlerts(9)
        seedAndCheck()
        openDashboard()

        composeRule.onNodeWithText("9 alerts firing").assertIsDisplayed()
        // A card that grew with the outage would push the next monitor off the
        // screen exactly when somebody needs to see whether it is up.
        composeRule.onNodeWithText("and 6 more").assertIsDisplayed()
        composeRule.captureScreenshot("alerts-02-card-capped")
    }

    @Test
    fun anAlertOpensToShowWhatItSaysAndEveryLabelItCarries() {
        seedAndCheck()
        openDashboard()
        composeRule.onAllNodesWithText("Alertmanager").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("3 alerts firing"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("3 alerts firing").assertIsDisplayed()
        // Closed, each row says which alert it is.
        composeRule.onNodeWithText("checkout-7f9c · payments").assertIsDisplayed()
        composeRule.onNodeWithText("ledger-2b41 · payments").assertIsDisplayed()
        composeRule.captureScreenshot("alerts-03-detail-closed")

        // Open, it says what the alert says. The description used to be read
        // only when the summary was missing, so this sentence existed nowhere in
        // the app for an alert that carried both.
        composeRule.onNodeWithText("checkout-7f9c · payments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("checkout-7f9c has restarted 14 times in the last hour")
            .assertIsDisplayed()
        composeRule.onNodeWithText("kube-state-metrics").assertIsDisplayed()
        composeRule.onNodeWithText("eu-west-1").assertIsDisplayed()
        composeRule.captureScreenshot("alerts-04-detail-open")

        // And the one beside it stays closed, or every outage opens as a wall.
        assertTrue(
            "opening one alert must not open the next",
            composeRule.onAllNodesWithText("ledger-2b41 has restarted 6 times in the last hour")
                .fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithText("checkout-7f9c · payments").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "tapping it again should close it",
            composeRule.onAllNodesWithText("checkout-7f9c has restarted 14 times in the last hour")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun oneAlertIsNotWrittenAsIfItWereSeveral() {
        payload = manyAlerts(1)
        seedAndCheck()
        openDashboard()

        composeRule.onNodeWithText("1 alert firing").assertIsDisplayed()
        composeRule.onNodeWithText("NodeDown · host-1").assertIsDisplayed()
        assertTrue(
            "one alert must not be counted down to nothing",
            composeRule.onAllNodesWithText("and 0 more").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aCheckThatNeverReachedAlertmanagerListsNothing() {
        seedAndCheck()
        // Same monitor, same screen, and now the server is broken. The list has
        // to go, not go stale: rows left behind next to "couldn't connect" would
        // be the app asserting an outage it has no evidence for.
        payload = ""
        server.close()
        openDashboard()
        composeRule.onNodeWithText("3 alerts firing").assertIsDisplayed()

        runBlocking { graph.engine.run("am-test") }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("3 alerts firing").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "the rows have to go with the evidence for them",
            composeRule.onAllNodesWithText(
                "KubePodCrashLooping · checkout-7f9c · payments",
            ).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.captureScreenshot("alerts-06-unreachable")
    }

    @Test
    fun anOpenAlertStaysOpenWhenTheScreenIsRebuilt() {
        seedAndCheck()
        openDashboard()
        composeRule.onAllNodesWithText("Alertmanager").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("3 alerts firing"))
        composeRule.onNodeWithText("checkout-7f9c · payments").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("checkout-7f9c has restarted 14 times in the last hour")
            .assertIsDisplayed()

        // What a rotation does to the screen. An open row that closes itself on
        // the way round is the app throwing away the only thing the person on
        // the screen had asked it for.
        scenario?.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("3 alerts firing"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("checkout-7f9c has restarted 14 times in the last hour")
            .assertIsDisplayed()
    }

    @Test
    fun aCappedListSaysItIsCapped() {
        payload = manyAlerts(62)
        seedAndCheck()
        openDashboard()
        composeRule.onAllNodesWithText("Alertmanager").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("62 alerts firing"))
        composeRule.waitForIdle()
        // A header reading "50 alerts firing" over a list that quietly dropped
        // twelve would be the app understating the outage.
        composeRule.onNodeWithText("Showing the worst 50 of 62.").assertIsDisplayed()
        composeRule.captureScreenshot("alerts-05-detail-capped")
    }

    /** [count] alerts on one rule, one per host, all critical. */
    private fun manyAlerts(count: Int): String =
        (1..count).joinToString(",", prefix = "[", postfix = "]") { index ->
            """
            {"labels":{"alertname":"NodeDown","severity":"critical","instance":"host-$index"},
             "annotations":{"summary":"host-$index stopped answering"},
             "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
             "startsAt":"2026-09-14T18:00:00.000Z","fingerprint":"fp$index"}
            """
        }
}
