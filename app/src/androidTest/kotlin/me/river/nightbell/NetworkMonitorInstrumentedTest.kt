package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.NetworkChecker
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue 21 the way a person meets it: a ping and a port check made through the
 * wizard, checked for real against loopback, failing, recovering, and read side
 * by side on the detail screen.
 */
@RunWith(AndroidJUnit4::class)
class NetworkMonitorInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private var scenario: ActivityScenario<MainActivity>? = null
    private var server: ServerSocket? = null
    private val realChecker = NetworkChecker()

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
    }

    @After
    fun tearDown() {
        scenario?.close()
        server?.close()
        graph.engine.network = realChecker
        notifications.cancelAll()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    /**
     * Scrolled the whole way down first: the footer floats over the form, so a
     * kind row scrolled only just into view sits under Continue and the tap
     * walks the wizard on instead of choosing the kind.
     */
    private fun pickKind(label: String) {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).performClick()
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

    private fun runtime(id: String): MonitorRuntime =
        runBlocking { graph.store.currentSnapshot().runtimes[id] } ?: MonitorRuntime()

    private fun monitors(): List<Monitor> = runBlocking { graph.store.currentSnapshot().monitors }

    private fun check(id: String) = runBlocking { graph.engine.run(id, force = true) }

    private fun titled(fragment: String): Boolean = notifications.activeNotifications.any {
        it.notification.extras.getCharSequence("android.title")?.contains(fragment) == true
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollDetailTo(tag: String) {
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun openServer(port: Int = 0): ServerSocket =
        ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).also { server = it }

    @Test
    fun aPingMonitorIsMadeThroughTheWizardAndPingsForReal() {
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        pickKind("Ping")
        composeRule.captureScreenshot("net-01-kind-picker")
        continueStep()

        type("Name", "Loopback")
        type("Host", "127.0.0.1")
        composeRule.onNodeWithText("IPv4 address. Checked over IPv4 only.").assertExists()
        composeRule.captureScreenshot("net-02-ping-target")
        // No proxy and no certificate section for a ping: nothing on this step
        // may offer a setting the check never reads.
        composeRule.onAllNodesWithText("Route through SOCKS5", substring = true).fetchSemanticsNodes().let {
            assertTrue("a routing control on a ping monitor", it.isEmpty())
        }
        continueStep()

        composeRule.onNodeWithTag("network-packets").assertExists()
        composeRule.onNodeWithText("What counts as up").assertExists()
        composeRule.captureScreenshot("net-03-ping-expectations")
        continueStep()

        composeRule.onNodeWithText("Reply timeout").performScrollTo()
        composeRule.onAllNodesWithText("Follow redirects").fetchSemanticsNodes().let {
            assertTrue("follow redirects on a ping monitor", it.isEmpty())
        }
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        waitForText("3 of 3 replies")
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.captureScreenshot("net-04-ping-test-now")

        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        val saved = monitors().single()
        assertEquals(MonitorKind.PING, saved.kind)
        assertEquals("127.0.0.1", saved.network.host)
        assertEquals("", saved.url)
        assertEquals(3, saved.network.packets)
        awaitTrue(description = "the first check to land") { runtime(saved.id).lastCheckedAt > 0 }
        assertEquals(Health.UP, runtime(saved.id).health)
        val reading = assertNotNull(runtime(saved.id).samples.last().ping).let { runtime(saved.id).samples.last().ping!! }
        assertEquals(3, reading.received)

        waitForText("Loopback")
        composeRule.onNodeWithText("127.0.0.1").assertExists()
        composeRule.onAllNodesWithText("GET").fetchSemanticsNodes().let {
            assertTrue("a method chip on a ping card", it.isEmpty())
        }
        composeRule.captureScreenshot("net-05-ping-dashboard")

        composeRule.onNodeWithText("Loopback").performClick()
        composeRule.waitForIdle()
        waitForText("3 of 3 replies")
        composeRule.captureScreenshot("net-06-ping-detail-top")
        scrollDetailTo("ping-card")
        composeRule.onNodeWithTag("ping-last").assertExists()
        composeRule.captureScreenshot("net-07-ping-detail-replies")
        scrollDetailTo("same-host-card")
        composeRule.onNodeWithText("Nothing else watches 127.0.0.1", substring = true).assertExists()
        composeRule.captureScreenshot("net-08-ping-detail-host")
    }

    @Test
    fun aPortCheckGoesDownWhenTheListenerCloses() {
        val socket = openServer()
        val port = socket.localPort
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        pickKind("TCP port")
        continueStep()
        type("Name", "Local service")
        // Pasted the way it comes out of a config file. Leaving the field moves
        // the port across.
        type("Host", "127.0.0.1:$port")
        composeRule.onNodeWithContentDescription("Name").performClick()
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Port").performScrollTo()
        // The pasted port moved across and the host field kept only the host.
        composeRule.onNodeWithText(port.toString()).assertExists()
        composeRule.onNodeWithText("127.0.0.1").assertExists()
        // The whole screen: the tap that left the host field leaves a cursor
        // handle up, which is a second window for the Compose capture.
        composeRule.captureDeviceScreenshot("net-10-tcp-target")
        continueStep()
        composeRule.onNodeWithTag("network-tcp-explainer").assertExists()
        continueStep()
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()

        val saved = monitors().single()
        assertEquals(MonitorKind.TCP_PORT, saved.kind)
        assertEquals("127.0.0.1", saved.network.host)
        assertEquals(port, saved.network.port)
        awaitTrue(description = "the first check to land") { runtime(saved.id).lastCheckedAt > 0 }
        assertEquals(Health.UP, runtime(saved.id).health)
        waitForText("127.0.0.1:$port")
        composeRule.captureScreenshot("net-11-tcp-dashboard-up")

        socket.close()
        val down = check(saved.id)!!
        assertFalse(down.ok)
        assertTrue(down.message, down.message.startsWith("Connection refused"))
        awaitTrue(description = "a down notification") { titled("Local service") }
        waitForText("Connection refused")
        composeRule.captureScreenshot("net-12-tcp-dashboard-down")

        openServer(port)
        assertTrue(check(saved.id)!!.ok)
        assertFalse(runtime(saved.id).alerting)
        composeRule.onNodeWithText("Local service").performClick()
        composeRule.waitForIdle()
        scrollDetailTo("outages-card")
        composeRule.onNodeWithText("1 failed check · Connection refused", substring = true).assertExists()
        composeRule.captureScreenshot("net-13-tcp-detail-outages")
    }

    /**
     * Issue 21, 2026-11-08: TCP up and UDP refused on one host and port, both
     * checked for real, and the note that the two disagree read off each one's
     * own screen.
     */
    @Test
    fun tcpUpAndUdpRefusedOnOnePortAreCalledOut() {
        val port = openServer().localPort
        runBlocking {
            graph.store.upsert(
                Monitor(id = "tcp", name = "Box tcp", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "127.0.0.1", port = port), timeoutSeconds = 2),
            )
            graph.store.upsert(
                Monitor(
                    id = "udp", name = "Box udp", kind = MonitorKind.UDP_PORT,
                    network = NetworkWatch(host = "127.0.0.1", port = port),
                    udp = me.river.nightbell.domain.UdpWatch(), timeoutSeconds = 2,
                ),
            )
        }
        check("tcp")
        check("udp")
        assertEquals(Health.UP, runtime("tcp").health)
        assertEquals(Health.DOWN, runtime("udp").health)

        val line = "$port/tcp accepts connections but $port/udp does not answer. The service may only be listening on TCP."
        launch()
        composeRule.onNodeWithText("Box tcp").performClick()
        composeRule.waitForIdle()
        scrollDetailTo("same-host-card")
        composeRule.onNodeWithText(line, substring = true).assertExists()
        composeRule.captureScreenshot("net-23-tcp-udp-split-from-tcp")
    }

    @Test
    fun twoChecksOnOneHostAreReadTogetherAndAThirdIsOneTapAway() {
        val closed = openServer().localPort.also { server?.close() }
        runBlocking {
            graph.store.upsert(
                Monitor(id = "ping", name = "Box", kind = MonitorKind.PING, network = NetworkWatch(host = "127.0.0.1", packets = 2), timeoutSeconds = 2),
            )
            graph.store.upsert(
                Monitor(id = "port", name = "Box web", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "127.0.0.1", port = closed), timeoutSeconds = 2),
            )
        }
        check("ping")
        check("port")
        assertEquals(Health.UP, runtime("ping").health)
        assertEquals(Health.DOWN, runtime("port").health)

        launch()
        composeRule.onNodeWithText("Box").performClick()
        composeRule.waitForIdle()
        scrollDetailTo("same-host-card")
        composeRule.onNodeWithTag("same-host-reading")
            .assertExists()
        composeRule.onNodeWithText(
            "The host answers ping, but port $closed is down. The network is fine; look at the service.",
        ).assertExists()
        composeRule.captureScreenshot("net-20-same-host")

        composeRule.onNodeWithTag("same-host-add").performClick()
        composeRule.waitForIdle()
        // Past the kind question, which the pairing answers: a ping wants a port
        // beside it, so this lands on a port field with the host already in.
        composeRule.onNodeWithContentDescription("Port").assertExists()
        composeRule.onNodeWithText("127.0.0.1").assertExists()
        composeRule.captureScreenshot("net-22-same-host-add-target")
    }

    @Test
    fun aRecoveryThresholdHoldsTheOutageUntilEnoughChecksPass() {
        val socket = openServer()
        val port = socket.localPort
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "flappy",
                    name = "Flappy",
                    kind = MonitorKind.TCP_PORT,
                    network = NetworkWatch(host = "127.0.0.1", port = port),
                    timeoutSeconds = 2,
                    useGlobalAlerts = false,
                    alert = AlertPolicy(failureThreshold = 1, recoveryThreshold = 3, cooldownMinutes = 0, quickRetry = false),
                ),
            )
        }
        assertTrue(check("flappy")!!.ok)
        socket.close()
        assertFalse(check("flappy")!!.ok)
        awaitTrue(description = "the down notification") { titled("Flappy") }
        assertTrue(runtime("flappy").alerting)

        openServer(port)
        assertTrue(check("flappy")!!.ok)
        assertTrue("one pass ended the outage", runtime("flappy").alerting)
        assertTrue("the down notice was taken down early", titled("Flappy is down"))
        launch()
        waitForText("Answering again · 1 of 3 good checks before the all-clear")
        composeRule.captureScreenshot("net-30-recovering-card")

        assertTrue(check("flappy")!!.ok)
        assertTrue(runtime("flappy").alerting)
        assertTrue(check("flappy")!!.ok)
        assertFalse("three passes and still alerting", runtime("flappy").alerting)
        awaitTrue(description = "the recovery notification") { titled("Flappy is back") }
        assertFalse(titled("Flappy is down"))
        composeRule.captureScreenshot("net-31-recovered-card")
    }

    /**
     * Loss cannot be produced on loopback, so the echo replies are faked here and
     * everything after them, the amber, the card's line and the notification, is
     * the real thing.
     */
    @Test
    fun aPingThatLosesSomeRepliesIsAmberAndSaysWhy() {
        graph.engine.network = NetworkChecker(
            pinger = { _, count, _ -> NetworkChecker.PingOutcome.Replies(List(count) { if (it == 1) null else 12_400L }) },
        )
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "lossy",
                    name = "Edge router",
                    kind = MonitorKind.PING,
                    network = NetworkWatch(host = "127.0.0.1", packets = 3),
                    useGlobalAlerts = false,
                    alert = AlertPolicy(alertOnDegraded = true, degradedCooldownMinutes = 0, cooldownMinutes = 0),
                ),
            )
        }
        val result = check("lossy")!!
        assertTrue(result.ok)
        assertTrue(result.degraded)
        assertEquals(Health.DEGRADED, runtime("lossy").health)
        awaitTrue(description = "the packet loss notification") { titled("Edge router is losing packets") }

        launch()
        waitForText("2 of 3 replies, 33% lost")
        composeRule.captureScreenshot("net-40-loss-dashboard")
        composeRule.onNodeWithText("Edge router").performClick()
        composeRule.waitForIdle()
        scrollDetailTo("ping-card")
        composeRule.onNodeWithText("Last check: 2 of 3 replies, 33% lost").assertExists()
        composeRule.captureScreenshot("net-41-loss-detail")
    }
}
