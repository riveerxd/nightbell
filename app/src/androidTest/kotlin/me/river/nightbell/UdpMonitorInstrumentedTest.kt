package me.river.nightbell

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import me.river.nightbell.domain.Reachability
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The UDP port kind the way a person meets it: made through the wizard against
 * a real DNS responder on loopback, checked for real, refused once the
 * responder is gone, recovered, and read on the card and the detail screen.
 */
@RunWith(AndroidJUnit4::class)
class UdpMonitorInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private val notifications: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val responders = mutableListOf<TinyUdp>()

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
        responders.forEach { it.close() }
        notifications.cancelAll()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

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

    private fun type(field: String, text: String, clear: Boolean = false) {
        val node = composeRule.onNodeWithContentDescription(field).performScrollTo()
        if (clear) node.performTextClearance()
        node.performTextInput(text)
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

    private fun scrollSetupTo(tag: String) {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun scrollDetailTo(tag: String) {
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun dns(port: Int = 0) = TinyUdp.dns(port).also { responders += it }

    @Test
    fun aDnsServerIsWatchedThroughTheWizardRefusedWhenItStopsAndBackAfter() {
        val server = dns()
        val port = server.port
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        pickKind("UDP port")
        composeRule.captureScreenshot("udp-01-kind-picker")
        continueStep()

        type("Name", "Home DNS")
        type("Host", "127.0.0.1")
        // The DNS probe brought port 53 with it; this server is elsewhere.
        composeRule.onNodeWithText("53").assertExists()
        type("Port", port.toString(), clear = true)
        composeRule.onNodeWithText("53 DNS").assertExists()
        composeRule.onAllNodesWithText("Route through SOCKS5", substring = true).fetchSemanticsNodes().let {
            assertTrue("a routing control on a UDP monitor", it.isEmpty())
        }
        composeRule.captureScreenshot("udp-02-target")
        continueStep()

        composeRule.onNodeWithTag("udp-probe").assertExists()
        composeRule.onNodeWithTag("udp-dns-name").assertExists()
        composeRule.captureScreenshot("udp-03-dns-probe")
        // To the bottom, not just to the card: the footer floats over the form
        // and a card scrolled only into view sits under it.
        scrollSetupTo("setup-bottom")
        composeRule.onNodeWithText("What counts as up").assertExists()
        composeRule.onNodeWithText("SERVFAIL is degraded", substring = true).assertExists()
        composeRule.captureScreenshot("udp-04-dns-up-explainer")
        continueStep()

        composeRule.onNodeWithText("Reply timeout, per try").performScrollTo()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.onNodeWithText("Test now").performClick()
        waitForText("NOERROR, 1 answer")
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.captureScreenshot("udp-05-test-now")
        assertTrue("the responder saw the test", server.received > 0)

        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        val saved = monitors().single()
        assertEquals(MonitorKind.UDP_PORT, saved.kind)
        assertEquals(UdpProbe.DNS, saved.udp.probe)
        assertEquals(port, saved.network.port)
        awaitTrue(description = "the first check to land") { runtime(saved.id).lastCheckedAt > 0 }
        assertEquals(Health.UP, runtime(saved.id).health)
        waitForText("NOERROR, 1 answer")
        composeRule.onNodeWithText("127.0.0.1:$port/udp").assertExists()
        composeRule.onNodeWithText("DNS").assertExists()
        composeRule.captureScreenshot("udp-06-dashboard-up")

        server.close()
        val down = check(saved.id)!!
        assertFalse(down.ok)
        assertEquals(down.message, FailureKind.PORT_CLOSED, down.failureKind)
        assertTrue(down.message, down.message.startsWith("UDP port $port closed"))
        awaitTrue(description = "a down notification") { titled("Home DNS") }
        waitForText("UDP port $port closed")
        composeRule.captureScreenshot("udp-07-dashboard-refused")

        dns(port)
        assertTrue(check(saved.id)!!.ok)
        assertFalse(runtime(saved.id).alerting)
        waitForText("NOERROR, 1 answer")
        composeRule.onNodeWithText("Home DNS").performClick()
        composeRule.waitForIdle()
        waitForText("NOERROR")
        composeRule.captureScreenshot("udp-08-detail-top")
        scrollDetailTo("udp-readout")
        composeRule.onNodeWithText("192.0.2.1").assertExists()
        composeRule.captureScreenshot("udp-09-detail-readout")
        scrollDetailTo("outages-card")
        composeRule.captureScreenshot("udp-10-detail-outages")
    }

    @Test
    fun aServiceThatNeverAnswersIsDownForAProbeAndUpForSilentMode() {
        val quiet = TinyUdp.quiet().also { responders += it }
        val probe = Monitor(
            id = "custom",
            name = "Quiet custom",
            kind = MonitorKind.UDP_PORT,
            network = NetworkWatch(host = "127.0.0.1", port = quiet.port),
            udp = UdpWatch(probe = UdpProbe.CUSTOM, payload = "hello", attempts = 2),
            timeoutSeconds = 1,
        )
        val silent = probe.copy(id = "silent", name = "Quiet silent", udp = UdpWatch(probe = UdpProbe.SILENT, attempts = 1))
        runBlocking {
            graph.store.upsert(probe)
            graph.store.upsert(silent)
        }
        val noReply = check(probe.id)!!
        assertEquals(noReply.message, FailureKind.NO_REPLY, noReply.failureKind)
        assertTrue("both tries went out", quiet.received >= 2)
        val notRefused = check(silent.id)!!
        assertTrue(notRefused.message, notRefused.ok)
        assertTrue(notRefused.message, notRefused.message.startsWith("Not refused, host answers ping"))

        launch()
        waitForText("Not refused, host answers ping")
        waitForText("No reply from 127.0.0.1:${quiet.port}")
        composeRule.captureScreenshot("udp-11-dashboard-silent-and-quiet")
        composeRule.onNodeWithText("Quiet silent").performClick()
        composeRule.waitForIdle()
        scrollDetailTo("udp-readout")
        composeRule.onNodeWithContentDescription("What silent mode saw").assertExists()
        composeRule.captureScreenshot("udp-12-detail-silent")
    }

    @Test
    fun silentModeExplainsItselfAndAPortChipPicksTheProbe() {
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        pickKind("UDP port")
        continueStep()
        type("Name", "VPN")
        type("Host", "127.0.0.1")
        composeRule.onNodeWithText("51820 WireGuard").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("51820").assertExists()
        continueStep()
        composeRule.onNodeWithTag("udp-silent-explainer").assertExists()
        composeRule.onNodeWithText("This service won't answer a stranger").assertExists()
        composeRule.captureScreenshot("udp-13-silent-explainer")

        // Picking NTP from silent moves a default port only, and 51820 was typed by a chip.
        composeRule.onNodeWithText("NTP").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("udp-ntp-offset").assertExists()
        composeRule.captureScreenshot("udp-14-ntp-probe")
        composeRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeRule.waitForIdle()
        type("Send", "PING\\r\\n")
        composeRule.onNodeWithText("6 bytes: 50 49 4e 47 0d 0a").assertExists()
        composeRule.captureScreenshot("udp-15-custom-probe")
    }

    @Test
    fun aHalfMadeUdpMonitorSurvivesRotation() {
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        pickKind("UDP port")
        continueStep()
        type("Name", "Turned")
        type("Host", "192.0.2.53")
        composeRule.onNodeWithText("123 NTP").performScrollTo().performClick()
        composeRule.waitForIdle()
        continueStep()
        composeRule.onNodeWithTag("udp-ntp-offset").assertExists()

        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("udp-ntp-offset").assertExists()
        composeRule.captureScreenshot("udp-16-ntp-landscape")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Turned").assertExists()
        composeRule.onNodeWithText("123").assertExists()
    }

    /**
     * With the phone's network gone, a UDP check fails as something that
     * reached nothing, so the existing reachability probe holds it as the
     * phone being offline instead of paging about the server.
     */
    @Test
    fun anOfflinePhoneFailsAsReachingNothing() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(cmd: String) = ui.executeShellCommand(cmd).close()
        shell("cmd connectivity airplane-mode enable")
        try {
            Thread.sleep(3_000)
            val monitor = Monitor(
                id = "offline",
                name = "Public DNS",
                kind = MonitorKind.UDP_PORT,
                network = NetworkWatch(host = "1.1.1.1", port = 53),
                udp = UdpWatch(attempts = 1),
                timeoutSeconds = 2,
            )
            val result = runBlocking { graph.engine.network.check(monitor) }
            assertFalse(result.ok)
            assertTrue(
                "${result.failureKind}: ${result.message}",
                Reachability.neverReachedAnything(result.failureKind),
            )
        } finally {
            shell("cmd connectivity airplane-mode disable")
            Thread.sleep(3_000)
        }
    }
}
