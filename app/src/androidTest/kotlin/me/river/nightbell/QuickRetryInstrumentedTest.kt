package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.data.work.MonitorWorker
import me.river.nightbell.data.work.SweepWorker
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.QuickRetry
import me.river.nightbell.domain.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue 20, on a device: the failures that confirm an outage are taken thirty
 * seconds apart inside one background run, not one WorkManager period apart.
 *
 * The engine tests shrink the gap to a second so a threshold of three does not
 * cost a minute each. The worker test does not: it runs the real gap through the
 * real MonitorWorker, because the whole claim is about what one background run
 * does before it lets the phone sleep.
 */
@RunWith(AndroidJUnit4::class)
class QuickRetryInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var server: TinyHttpServer? = null
    private val online = graph.engine.isOnline

    /** A port with nothing behind it. */
    private val deadUrl: String by lazy {
        val port = ServerSocket(0).use { it.localPort }
        "http://127.0.0.1:$port/down"
    }

    @Before
    fun setUp() {
        notifications.cancelAll()
    }

    @After
    fun tearDown() {
        scenario?.close()
        server?.close()
        graph.engine.quickRetryGapMs = QuickRetry.GAP_MS
        graph.engine.isOnline = online
        notifications.cancelAll()
    }

    private fun seed(url: String, policy: AlertPolicy) {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                masterAlertsEnabled = true,
                defaultAlert = policy,
                // The reachability probe is a different feature with its own
                // tests. Off, so a dead port reads as a dead site and nothing else.
                confirmOutagesEnabled = false,
                latencyBaselineEnabled = false,
            ),
        )
        runBlocking {
            graph.store.upsert(
                Monitor(id = "api", name = "Checkout API", url = url, timeoutSeconds = 5, useGlobalAlerts = true),
            )
        }
    }

    private fun runtime(): MonitorRuntime =
        runBlocking { graph.store.currentSnapshot().runtimes["api"] } ?: MonitorRuntime()

    private fun paged(): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence("android.title")?.contains("Checkout API") == true
    }

    @Test
    fun aFailureIsConfirmedInsideTheSameRunAndPages() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3, cooldownMinutes = 0))
        graph.engine.quickRetryGapMs = 1_000L

        val ran = runBlocking {
            graph.engine.run("api", force = false)
            assertFalse("one failure of three must not page", paged())
            graph.engine.confirmPending(only = "api")
        }

        val after = runtime()
        // At least one, not exactly two: a reconnect pass or the monitor's own
        // worker may run a confirming check first, and the in-lock due gate then
        // lets this loop skip it. Who ran it is not the claim; the streak is.
        assertTrue("no confirming check ran here at all", ran >= 1)
        assertEquals(3, after.consecutiveFailures)
        assertEquals(3, after.samples.size)
        assertTrue("the third failure should have alerted", after.alerting)
        awaitTrue(description = "a down notification for Checkout API") { paged() }
        after.samples.zipWithNext().forEach { (a, b) ->
            assertTrue("checks ${b.at - a.at}ms apart, under the gap", b.at - a.at >= 1_000L)
        }
    }

    @Test
    fun switchedOffItWaitsForTheSchedule() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3, quickRetry = false))
        graph.engine.quickRetryGapMs = 1_000L

        val ran = runBlocking {
            graph.engine.run("api", force = false)
            graph.engine.confirmPending(only = "api")
        }

        assertEquals(0, ran)
        assertEquals(1, runtime().consecutiveFailures)
        assertFalse(paged())
    }

    /** What the threshold is for: a blip that is over before the next check. */
    @Test
    fun aBlipThatClearsIsNeverPaged() {
        // Failing until the first check has been recorded, not for exactly one
        // request. Counting requests raced every other check path: a reconnect
        // pass that reached the server first took the one 503 itself, and the
        // test then saw nothing left to confirm.
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val hits = AtomicInteger()
        server = TinyHttpServer {
            hits.incrementAndGet()
            if (failing.get()) {
                TinyHttpServer.Response(code = 503, reason = "Service Unavailable")
            } else {
                TinyHttpServer.Response()
            }
        }
        seed(server!!.url("/health"), AlertPolicy(failureThreshold = 3))
        graph.engine.quickRetryGapMs = 1_000L

        runBlocking {
            graph.engine.run("api", force = false)
            assertEquals("the first check should have failed", 1, runtime().consecutiveFailures)
            failing.set(false)
            graph.engine.confirmPending(only = "api")
        }

        val after = runtime()
        assertTrue("the site was only asked ${hits.get()} times", hits.get() >= 2)
        assertEquals(0, after.consecutiveFailures)
        assertEquals(Health.UP, after.health)
        assertFalse(after.alerting)
        assertFalse(paged())
    }

    /**
     * Losing the network mid-streak is not two more failures. It also must not
     * spin: a check that comes back with no verdict leaves the monitor owed and
     * overdue, and a loop that does not drop it runs as fast as it can return.
     */
    @Test
    fun goingOfflineEndsTheRetriesWithoutCountingThem() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3))
        graph.engine.quickRetryGapMs = 1_000L

        val started = System.currentTimeMillis()
        val ran = runBlocking {
            graph.engine.run("api", force = false)
            graph.engine.isOnline = { false }
            graph.engine.confirmPending(only = "api")
        }

        assertEquals(0, ran)
        assertEquals(1, runtime().consecutiveFailures)
        assertFalse(paged())
        assertTrue("the loop should give up at once", System.currentTimeMillis() - started < 10_000L)
    }

    /**
     * The issue as reported, end to end. A threshold of three run through the
     * real MonitorWorker with the real thirty second gap pages about a minute
     * after the first failure, inside the one doWork. Before this, the two
     * missing failures were two more periodic runs, fifteen minutes each at the
     * very least.
     *
     * Driven with TestListenableWorkerBuilder rather than enqueued. Enqueued, the
     * claim under test was hostage to when WorkManager chose to start the job: on
     * a loaded emulator it sat unstarted for the whole timeout, then ran during
     * the next test and paged into it. When the platform starts work is not
     * what changed here; what one run does once started is.
     */
    @Test
    fun theBackgroundWorkerPagesAboutAMinuteAfterTheFirstFailure() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3, cooldownMinutes = 0))
        val worker = TestListenableWorkerBuilder<MonitorWorker>(appContext)
            .setInputData(workDataOf(MonitorWorker.KEY_MONITOR_ID to "api"))
            .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        awaitTrue(description = "the worker paging for Checkout API") { paged() }
        val samples = runtime().samples
        assertEquals(3, samples.size)
        val span = samples.last().at - samples.first().at
        assertTrue("first to third failure took ${span}ms", span in 60_000L..90_000L)
    }

    /**
     * "Check all now" is a pass of its own, and a monitor it finds failing has to
     * be confirmed like any other. Before this it sat on "1 of 3 failures" until
     * the schedule came round, under a card claiming it was re-checking.
     */
    @Test
    fun checkAllNowConfirmsWhatItFinds() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3, cooldownMinutes = 0))
        graph.engine.quickRetryGapMs = 1_000L
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Check all now").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Check all now").performClick()

        awaitTrue(timeoutMs = 30_000, description = "Check all now confirming and paging") { paged() }
        // Waited for, not read once: the engine posts the notification and then
        // writes the runtime, so the page can be on screen a few milliseconds
        // before the third failure is in the store.
        awaitTrue(description = "the third failure recorded") { runtime().consecutiveFailures == 3 }
        composeRule.waitForIdle()
        composeRule.captureScreenshot("quick-retry-06-check-all-paged")
    }

    /**
     * Code review: a loop that lost the race for a retry used to give the monitor
     * up and return. When the winner was a tapped check's loop on the app scope,
     * which holds no wake lock, the worker that did hold one returned and let the
     * phone sleep through the rest of the streak.
     */
    @Test
    fun aLoopThatLosesTheRaceForARetryKeepsWaiting() {
        seed(deadUrl, AlertPolicy(failureThreshold = 4, cooldownMinutes = 0))
        graph.engine.quickRetryGapMs = 1_000L

        val returnedAt = runBlocking {
            graph.engine.run("api", force = false)
            List(2) {
                async(Dispatchers.Default) {
                    graph.engine.confirmPending(only = "api")
                    System.currentTimeMillis()
                }
            }.awaitAll()
        }

        val after = runtime()
        assertEquals(4, after.consecutiveFailures)
        assertTrue(after.alerting)
        val streakEnded = after.samples.last().at
        returnedAt.forEach {
            assertTrue("a loop gave up ${streakEnded - it}ms before the streak ended", it >= streakEnded)
        }
    }

    /**
     * Code review: the sweep confirmed before its schedule repair and urgent tick,
     * so a page already repeating for another monitor waited minutes behind a
     * failure that had not even alerted yet.
     */
    @Test
    fun theSweepFinishesItsOwnWorkBeforeItConfirms() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3, cooldownMinutes = 0))
        graph.engine.quickRetryGapMs = 1_000L
        val worker = TestListenableWorkerBuilder<SweepWorker>(appContext).build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(3, runtime().consecutiveFailures)
        val lines = Diag.recent()
        val sweep = lines.subList(lines.indexOfLast { "sched.sweep.start" in it }, lines.size)
        val done = sweep.indexOfFirst { "sched.sweep.done ran=" in it }
        val retry = sweep.indexOfFirst { "check.retry" in it }
        assertTrue("the sweep never confirmed", retry >= 0)
        assertTrue("the sweep never finished", done >= 0)
        assertTrue("a retry ran before the sweep's own work was done", done < retry)
    }

    private fun openAlertSettings() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Failures before alerting"))
        composeRule.waitForIdle()
    }

    /**
     * The state lives on the Switch inside the row, not on the tagged row, which
     * is how every ToggleRow in the app is built.
     */
    private fun quickRetrySwitch() = composeRule.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("policy-quick-retry")),
        useUnmergedTree = true,
    )

    @Test
    fun theSwitchAppearsOnlyWhenThereIsSomethingToRetry() {
        seed(deadUrl, AlertPolicy(failureThreshold = 1))
        openAlertSettings()
        composeRule.captureScreenshot("quick-retry-01-threshold-one")
        assertTrue(
            "a threshold of one has nothing to retry, so no switch",
            composeRule.onAllNodesWithTag("policy-quick-retry").fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithContentDescription("Increase Failures before alerting").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("policy-quick-retry"))
        composeRule.onNodeWithTag("policy-quick-retry").assertIsDisplayed()
        quickRetrySwitch().assertIsOn()
        composeRule.onNodeWithText("alerts about 30s after the first failed check", substring = true)
            .assertIsDisplayed()
        composeRule.captureScreenshot("quick-retry-02-threshold-two-on")

        composeRule.onNodeWithTag("policy-quick-retry").performClick()
        composeRule.waitForIdle()
        quickRetrySwitch().assertIsOff()
        composeRule.onNodeWithText("Waits for the next scheduled check each time", substring = true)
            .assertIsDisplayed()
        composeRule.captureScreenshot("quick-retry-03-off")
        awaitTrue(description = "the switch persisting") {
            runBlocking { graph.store.currentSnapshot().settings.defaultAlert }.let {
                it.failureThreshold == 2 && !it.quickRetry
            }
        }
    }

    /**
     * A red card with no notification behind it reads as the pager being broken,
     * unless it says why it has not spoken.
     */
    @Test
    fun theCardSaysWhyItHasNotAlertedYet() {
        seed(deadUrl, AlertPolicy(failureThreshold = 3))
        val at = System.currentTimeMillis()
        runBlocking {
            graph.store.updateRuntime("api") {
                it.copy(
                    health = Health.DOWN,
                    lastCheckedAt = at,
                    lastMessage = "Connection refused",
                    consecutiveFailures = 1,
                    samples = listOf(Sample(at = at, ok = false, latencyMs = 12L, note = "Connection refused")),
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("1 of 3 failures before it alerts", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.captureScreenshot("quick-retry-04-card")

        composeRule.onNodeWithText("Checkout API").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Connection refused · 1 of 3 failures", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.captureScreenshot("quick-retry-05-detail")
    }
}
