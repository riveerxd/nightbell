package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.PrometheusSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Building each of the three Prometheus monitors the way a person does.
 *
 * The JVM suite proves what a body means. This proves the feature is reachable:
 * that the source picker changes the form under it, that the URL this app builds
 * is shown before anybody commits to it, and that Test now comes back with the
 * metric's value rather than with an HTTP status. A check that works perfectly
 * and cannot be set up is not a feature.
 *
 * Each test serves the payload from a real socket in the app's own process, so
 * the request under test is a real request.
 */
@RunWith(AndroidJUnit4::class)
class PrometheusSetupInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        // Dark on purpose. This is a monitor that wakes people at 03:00, so dark
        // is the theme it is read in, and the chip centring this suite now
        // asserts was reported from a dark screenshot.
        NightbellTestSupport.resetApp(
            GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK),
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    /** Opens the wizard and picks the Prometheus kind. */
    private fun openWizardOnPrometheus() {
        launchApp()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Prometheus").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun continueStep() {
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
    }

    private fun type(field: String, text: String) {
        composeRule.onNodeWithContentDescription(field).performScrollTo().performTextInput(text)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    /**
     * Runs Test now, waits for the card, and scrolls it into view.
     *
     * The scroll is not decoration. The result card renders below the button
     * that produced it, so a screenshot taken the moment the assertion passes
     * shows the form and not the answer, which is the exact shape of a
     * screenshot that proves nothing.
     */
    private fun testNowUntil(expected: String) {
        // The whole way down, not just far enough to see it. The footer floats
        // over the form, so a node scrolled just into the viewport can still be
        // underneath it, and the tap then lands on Continue and walks the wizard
        // forward instead. That is what happened here, and the screenshot of
        // step 4 was the only thing that said so.
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Test now").performClick()
        try {
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithText(expected, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            composeRule.captureScreenshot("prom-diagnostic-timeout")
            composeRule.onRoot().printToLog("promTimeout")
            throw error
        }
        // To the bottom anchor again rather than to the card. The card is the
        // last thing in the form, so scrolling *to* it stops with it against the
        // footer and the screenshot shows a sliver. The anchor sits below it and
        // is exactly the footer's height, which puts the whole card in clear air.
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        // First match: the card repeats the series in its detail line, so an
        // exact-node lookup here is ambiguous by design rather than by accident.
        composeRule.onAllNodesWithText(expected, substring = true).onFirst().assertIsDisplayed()
    }


    /**
     * Opens the saved monitor and photographs its Configuration card.
     *
     * The config rows are the only place the built request URL and the chosen
     * expectation are readable after the wizard closes, and a row that renders
     * blank or says the wrong thing is invisible to every assertion in the
     * domain suite.
     */
    private fun openDetailAndCaptureConfig(title: String, shot: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(title, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(title, substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("detail-list")
            .performScrollToNode(hasContentDescription("Configuration"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Configuration").assertIsDisplayed()
        composeRule.captureScreenshot(shot)
    }

    // ---------------------------------------------------------------- metrics

    @Test
    fun aMetricsMonitorReadsTheValueOffARealScrape() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER)
        }.use { server ->
            openWizardOnPrometheus()
            composeRule.onNodeWithText("Scrape metrics, run PromQL, or watch Alertmanager.")
                .assertIsDisplayed()
            composeRule.captureScreenshot("prom-01-kind")
            continueStep()

            // Metrics is the default source, and the screen says which one it is
            // rather than leaving the segmented control to be decoded.
            composeRule.onNodeWithText("Scrape a metrics endpoint").assertIsDisplayed()
            type("Metrics URL", server.url("/metrics"))
            composeRule.captureScreenshot("prom-02-target-metrics")
            continueStep()

            type("Metric", "node_load1")
            composeRule.onNodeWithContentDescription("Threshold").performScrollTo()
                .performTextReplacement("2")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()

            // The live summary is what makes the comparison chips unambiguous.
            assertTrue(
                "the healthy-while summary should name the series and the threshold",
                composeRule.onAllNodesWithText("node_load1 < 2", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.captureScreenshot("prom-03-expectation-metrics")

            testNowUntil("node_load1 = 0.29")
            composeRule.captureScreenshot("prom-04-test-passed")

            continueStep()
            composeRule.onNodeWithText("Create monitor").performClick()
            composeRule.waitForIdle()

            awaitTrue(description = "the metrics monitor to be stored") {
                runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
            }
            val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
            assertEquals(MonitorKind.PROMETHEUS, monitor.kind)
            assertEquals(PrometheusSource.METRICS, monitor.prometheus.source)
            assertEquals("node_load1", monitor.prometheus.metricName)
            assertEquals(2.0, monitor.prometheus.threshold, 1e-9)
            // An unnamed metrics monitor is titled by its series, because several
            // exporters on one host would otherwise all read as the same host.
            assertEquals("node_load1", monitor.displayName)

            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("node_load1").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.captureScreenshot("prom-05-dashboard")

            openDetailAndCaptureConfig("node_load1", "prom-05b-detail-metrics")
            // The request row has to name the endpoint actually fetched. More than
            // one node carries it: the header repeats the monitor's own URL, and
            // for a metrics monitor those two are the same string by design.
            composeRule.onAllNodesWithText("/metrics", substring = true)
                .onFirst().performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aLoadSpikeTakesTheMonitorDownAndNamesTheNumber() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER_HOT)
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/metrics"))
            continueStep()
            type("Metric", "node_load1")
            composeRule.onNodeWithContentDescription("Threshold").performScrollTo()
                .performTextReplacement("2")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()

            // The failure has to name the value and the range, not say "body
            // mismatch", which would send somebody looking at the wrong thing.
            testNowUntil("node_load1 = 7.42")
            composeRule.onAllNodesWithText("Adjust the threshold", substring = true)
                .onFirst().performScrollTo().assertIsDisplayed()
            composeRule.captureScreenshot("prom-06-test-failed")
        }
    }

    // ----------------------------------------------------------------- promql

    @Test
    fun aPromqlMonitorShowsTheUrlItWillBuildAndRunsTheQuery() {
        TinyHttpServer { request ->
            // Answers only the query API, so a request to the wrong path would
            // fail the test rather than quietly pass it.
            if (request.path.startsWith("/api/v1/query")) {
                TinyHttpServer.Response(
                    body = PrometheusPayloads.EMPTY_VECTOR,
                    contentType = "application/json",
                )
            } else {
                TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()

            composeRule.onNodeWithText("PromQL").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Query a Prometheus server").assertIsDisplayed()

            type("Prometheus URL", server.baseUrl)

            // The field takes a server address and the app adds the path, so the
            // path it added is shown before anybody commits to it.
            composeRule.onNodeWithTag("prom-request-preview").performScrollTo().assertIsDisplayed()
            assertTrue(
                "the preview should show the query API path this app appends",
                composeRule.onAllNodesWithText("/api/v1/query", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.captureScreenshot("prom-07-target-promql")
            continueStep()

            type("PromQL", "up{job=\"api\"} == 0")
            composeRule.onNodeWithText("Returns nothing").performScrollTo().assertIsDisplayed()
            composeRule.captureScreenshot("prom-08-expectation-promql")

            testNowUntil("Query matched nothing")
            composeRule.captureScreenshot("prom-09-promql-passed")

            continueStep()
            composeRule.onNodeWithText("Create monitor").performClick()
            composeRule.waitForIdle()

            awaitTrue(description = "the promql monitor to be stored") {
                runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
            }
            val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
            assertEquals(PrometheusSource.QUERY, monitor.prometheus.source)
            assertEquals("""up{job="api"} == 0""", monitor.prometheus.query)
            // The typed URL stays the server address. Only the request adds a path.
            assertEquals(server.baseUrl, monitor.url)
            assertTrue(
                "the query API should have been the only thing asked for",
                server.received.all { it.path.startsWith("/api/v1/query") },
            )

            openDetailAndCaptureConfig("127.0.0.1", "prom-09b-detail-promql")
            // The path this app appends, readable after the wizard has closed.
            composeRule.onAllNodesWithText("/api/v1/query", substring = true)
                .onFirst().performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Reopening a saved monitor, which is where a form usually loses something.
     *
     * The threshold is the field at risk: it is held as typed text beside the
     * draft rather than derived from it, so seeding it when an existing monitor
     * loads is a separate step that can silently not happen. A wizard that
     * reopens showing an empty threshold would overwrite a working monitor with
     * whatever the default is on the next save.
     */
    @Test
    fun aSavedMetricsMonitorReopensWithEveryFieldStillInIt() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER)
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/metrics"))
            continueStep()
            type("Metric", "node_load1")
            type("Label filter", """cpu="0"""")
            composeRule.onNodeWithContentDescription("Threshold").performScrollTo()
                .performTextReplacement("0.75")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()
            continueStep()
            composeRule.onNodeWithText("Create monitor").performClick()
            composeRule.waitForIdle()

            awaitTrue(description = "the monitor to be stored") {
                runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
            }

            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("node_load1", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("node_load1", substring = true).onFirst().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Edit monitor").performClick()
            composeRule.waitForIdle()

            // Straight to the expectations step, where the fragile field lives.
            continueStep()
            continueStep()
            composeRule.onNodeWithContentDescription("Metric").performScrollTo()
                .assertTextContains("node_load1")
            composeRule.onNodeWithContentDescription("Label filter").performScrollTo()
                .assertTextContains("""cpu="0"""")
            // A blank here is the bug this test exists for.
            composeRule.onNodeWithContentDescription("Threshold").performScrollTo()
                .assertTextContains("0.75")
            composeRule.captureScreenshot("prom-14-edit-reopened")

            // The save button only exists on the last step.
            continueStep()
            // And it still saves what it was showing rather than a default.
            // "Save changes" at full width, "Save" when the footer is narrow, and
            // this emulator is narrow. Matching the substring takes both.
            composeRule.onAllNodesWithText("Save", substring = true).onFirst().performClick()
            composeRule.waitForIdle()
            awaitTrue(description = "the edit to persist without resetting the threshold") {
                runBlocking {
                    Nightbell.require().store.currentSnapshot().monitors.single()
                }.prometheus.threshold == 0.75
            }
        }
    }

    /**
     * Picking a metric off the endpoint instead of remembering its name.
     *
     * The point of the feature: the exact string exists in one place and it is
     * the thing being monitored, so the app reads it rather than asking. This
     * drives it with a filter, which is how anybody finds one name in the four
     * hundred a default node_exporter exposes.
     */
    @Test
    fun theMetricBrowserListsTheEndpointAndFillsTheField() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER)
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/metrics"))
            continueStep()

            composeRule.onNodeWithText("Browse this endpoint").performScrollTo().performClick()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithTag("metric-browser-list").fetchSemanticsNodes().isNotEmpty()
            }
            // Every distinct name in the scrape, and the multi-series one says so
            // rather than showing one value out of three.
            // The Metric field's own placeholder is "node_load1" too, so this
            // string legitimately appears twice while the sheet is open.
            composeRule.onAllNodesWithText("node_load1").onFirst().assertIsDisplayed()
            composeRule.onNodeWithText("node_cpu_seconds_total").assertIsDisplayed()
            assertTrue(
                "a multi-series metric should say how many and on which labels",
                composeRule.onAllNodesWithText("3 series", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            composeRule.captureDeviceScreenshot("prom-15-browser")

            composeRule.onNodeWithContentDescription("Filter").performTextInput("load")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("node_load1").onFirst().assertIsDisplayed()
            assertTrue(
                "the filter should exclude what does not match",
                composeRule.onAllNodesWithText("node_cpu_seconds_total").fetchSemanticsNodes().isEmpty(),
            )
            composeRule.captureDeviceScreenshot("prom-16-browser-filtered")

            composeRule.onAllNodesWithText("node_load1").onLast().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Metric").performScrollTo()
                .assertTextContains("node_load1")
            // One series with no labels, so there is nothing to prefill and it
            // must not invent one.
            composeRule.onNodeWithContentDescription("Label filter").performScrollTo()
                .assertTextContains("")
            composeRule.captureDeviceScreenshot("prom-17-browser-picked")
        }
    }

    @Test
    fun pickingAMetricWithOneLabelledSeriesFillsTheFilterToo() {
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER)
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/metrics"))
            continueStep()
            composeRule.onNodeWithText("Browse this endpoint").performScrollTo().performClick()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithTag("metric-browser-list").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Filter").performTextInput("filesystem")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("node_filesystem_avail_bytes").performClick()
            composeRule.waitForIdle()

            // Exactly one series, so its labels are not a choice and typing them
            // out by hand would be work the app could have done.
            composeRule.onNodeWithContentDescription("Label filter").performScrollTo()
                .assertTextContains("""device="/dev/sda1"""", substring = true)
        }
    }

    @Test
    fun theBrowserSaysSoWhenTheUrlIsNotAnExporter() {
        // The state somebody reaches by pointing this at a dashboard, a login
        // page or the Prometheus server itself. An empty list with no
        // explanation would read as "this endpoint has no metrics", which is a
        // different and wrong claim.
        TinyHttpServer {
            TinyHttpServer.Response(
                body = "<!DOCTYPE html><html><body><h1>Sign in</h1></body></html>",
                contentType = "text/html",
            )
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/login"))
            continueStep()
            composeRule.onNodeWithText("Browse this endpoint").performScrollTo().performClick()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithText("OpenMetrics", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Try again").assertIsDisplayed()
            composeRule.captureDeviceScreenshot("prom-20-browser-not-an-exporter")
        }
    }

    @Test
    fun aBrokenLabelFilterStaysQuietUntilTheFieldIsLeft() {
        // Reward early, punish late: `mode="idle"` is invalid for its first four
        // keystrokes, so flagging it live means the error is on screen for most
        // of the typing and gone by the time it mattered.
        TinyHttpServer {
            TinyHttpServer.Response(body = PrometheusPayloads.NODE_EXPORTER)
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()
            type("Metrics URL", server.url("/metrics"))
            continueStep()
            composeRule.onNodeWithContentDescription("Metric").performScrollTo()
                .performTextInput("node_load1")
            composeRule.onNodeWithContentDescription("Label filter").performScrollTo()
                .performTextInput("mode")
            composeRule.waitForIdle()
            // One occurrence, not none: the Test panel says why Test is
            // unavailable no matter which field has focus, and that is the app's
            // existing pattern. What must not be there yet is the second one,
            // the note under the field being typed into.
            val whileTyping = composeRule
                .onAllNodesWithText("isn't a label filter", substring = true)
                .fetchSemanticsNodes().size
            assertEquals(
                "the field's own error must wait until the field is left",
                1,
                whileTyping,
            )
            composeRule.captureDeviceScreenshot("prom-18-filter-mid-typing")

            // Leaving it is when it gets to complain, and it still blocks the save.
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithContentDescription("Metric").performScrollTo().performClick()
            composeRule.waitForIdle()
            assertEquals(
                "on blur the field carries the note as well",
                2,
                composeRule.onAllNodesWithText("isn't a label filter", substring = true)
                    .fetchSemanticsNodes().size,
            )
            composeRule.captureDeviceScreenshot("prom-19-filter-after-blur")
        }
    }

    // ----------------------------------------------------------- alertmanager

    @Test
    fun anAlertmanagerMonitorPagesOnWhatIsFiring() {
        TinyHttpServer { request ->
            if (request.path.startsWith("/api/v2/alerts")) {
                TinyHttpServer.Response(
                    body = PrometheusPayloads.ALERTS,
                    contentType = "application/json",
                )
            } else {
                TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }.use { server ->
            openWizardOnPrometheus()
            continueStep()

            composeRule.onNodeWithText("Alerts").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Watch an Alertmanager").assertIsDisplayed()
            type("Alertmanager URL", server.baseUrl)
            composeRule.captureScreenshot("prom-10-target-alertmanager")
            continueStep()

            composeRule.onNodeWithText("Count silenced alerts").performScrollTo().assertIsDisplayed()
            composeRule.captureScreenshot("prom-11-expectation-alertmanager")

            // Two of the three fixtures fire. The third is silenced and must not
            // be counted, which is the whole of the silenced rule.
            testNowUntil("DiskFillingUp")
            assertTrue(
                "a silenced alert must not be named",
                composeRule.onAllNodesWithText("SilencedNoise", substring = true)
                    .fetchSemanticsNodes().isEmpty(),
            )
            composeRule.captureScreenshot("prom-12-alerts-firing")

            continueStep()
            composeRule.onNodeWithText("Create monitor").performClick()
            composeRule.waitForIdle()

            awaitTrue(description = "the alertmanager monitor to be stored") {
                runBlocking { Nightbell.require().store.currentSnapshot().monitors.size } == 1
            }
            val monitor = runBlocking { Nightbell.require().store.currentSnapshot() }.monitors.single()
            assertEquals(PrometheusSource.ALERTMANAGER, monitor.prometheus.source)
            assertTrue(
                "silenced alerts must stay excluded by default",
                !monitor.prometheus.includeSilenced,
            )

            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("Down", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("Down", substring = true).onFirst().assertIsDisplayed()
            composeRule.captureScreenshot("prom-13-dashboard-alerting")

            // The list, not the sentence. Issue 14 named two alert viewers, so
            // the detail screen has to show what is firing and how bad it is.
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("127.0.0.1", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("127.0.0.1", substring = true).onFirst().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("detail-list")
                .performScrollToNode(hasContentDescription("2 alerts firing"))
            composeRule.onNodeWithContentDescription("2 alerts firing").assertIsDisplayed()
            composeRule.onNodeWithText("DiskFillingUp").assertIsDisplayed()
            composeRule.onNodeWithText("Disk will fill in 4 hours").assertIsDisplayed()
            composeRule.onNodeWithText("HighLatency").assertIsDisplayed()
            composeRule.onNodeWithText("p99 over 2s for 10 minutes").assertIsDisplayed()
            // Silenced stays out of the viewer too, not just out of the verdict.
            assertTrue(
                "a silenced alert must not be listed",
                composeRule.onAllNodesWithText("SilencedNoise").fetchSemanticsNodes().isEmpty(),
            )
            composeRule.captureScreenshot("prom-13c-alerts-card")

            composeRule.onNodeWithTag("detail-list")
                .performScrollToNode(hasContentDescription("Configuration"))
            composeRule.waitForIdle()
            composeRule.captureScreenshot("prom-13b-detail-alertmanager")
            composeRule.onAllNodesWithText("/api/v2/alerts", substring = true)
                .onFirst().performScrollTo().assertIsDisplayed()
        }
    }
}
