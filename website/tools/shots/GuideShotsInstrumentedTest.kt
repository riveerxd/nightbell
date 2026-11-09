package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureDeviceScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.domain.TlsTrust
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures for the guide pages, taken against real services on the machine
 * running the emulator, which the emulator reaches as 10.0.2.2. None of them
 * is hermetic and none belongs in the app's suite: run each method on its own,
 * with the services up, the same way as WebsiteShotsInstrumentedTest.
 *
 *   Uptime Kuma 2.5.9 on :3901 with an API key, one monitor up, one down
 *   linuxserver/wireguard on 51820/udp
 *   nginx on :8481 (http) and :8444 (self-signed https), CoreDNS on 1053/udp,
 *   the host's own sshd on 22
 *
 * Kuma's key comes in as an instrumentation argument, `-e kumaKey uk1_...`, so
 * it is never written into the file.
 *
 * The Kuma and WireGuard monitors are built through the wizard with the taps a
 * person makes. The homelab dashboard is seeded, because five wizards would
 * only prove what the other suites already do, and then every monitor on it is
 * run against the real service before the capture. Addresses are changed to a
 * home network's after the checks and before the capture, as the other site
 * captures do with their hosts.
 */
@RunWith(AndroidJUnit4::class)
class GuideShotsInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val graph get() = Nightbell.install(appContext)

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun reset() = NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))

    private fun launch() {
        instrumentation.setInTouchMode(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.waitForIdle()
    }

    private fun openWizardOn(kind: String) {
        launch()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(kind).performClick()
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

    private fun waitForText(text: String, timeoutMs: Long = 25_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Test now, then the card scrolled clear of the floating footer. */
    private fun testNowUntil(expected: String, timeoutMs: Long = 25_000) {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Test now").performClick()
        try {
            waitForText(expected, timeoutMs)
        } catch (error: Throwable) {
            composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
            composeRule.captureDeviceScreenshot("guide-diagnostic-timeout")
            throw error
        }
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        Thread.sleep(400)
    }

    private fun toBottom() {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
        Thread.sleep(400)
    }

    private fun createAndAwait(count: Int) {
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        NightbellTestSupport.awaitTrue(description = "the monitor to be stored") {
            runBlocking { graph.store.currentSnapshot().monitors.size } == count
        }
    }

    private fun only(): Monitor = runBlocking { graph.store.currentSnapshot().monitors.single() }

    private fun openDetail(title: String) {
        waitForText(title)
        composeRule.onAllNodesWithText(title, substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        Thread.sleep(800)
        composeRule.waitForIdle()
    }

    private fun relaunchOnDashboard() {
        scenario?.close()
        launch()
    }

    // ---------------------------------------------------------------- kuma

    @Test
    fun kumaGuide() {
        val key = InstrumentationRegistry.getArguments().getString("kumaKey")
        checkNotNull(key) { "pass -e kumaKey <the Kuma API key>" }
        reset()
        openWizardOn("Prometheus")
        continueStep()
        type("Name", "Uptime Kuma")
        type("Metrics URL", "http://10.0.2.2:3901/metrics")
        type("Password", key)
        composeRule.onNodeWithContentDescription("Metrics URL").performScrollTo()
        composeRule.waitForIdle()
        composeRule.captureDeviceScreenshot("kuma-sign-in")
        continueStep()

        type("Metric", "monitor_status")
        composeRule.onNodeWithText("Not equal").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Threshold").performScrollTo().performTextReplacement("0")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()

        testNowUntil("monitor_name=\"API\"")
        composeRule.captureDeviceScreenshot("kuma-test")

        continueStep()
        createAndAwait(1)
        val monitor = only()
        assertTrue(monitor.prometheus.metricName == "monitor_status")
        // Three failing checks against the real Kuma, before the address swap
        // below, so the alarm further down quotes what Kuma actually said.
        repeat(3) { runBlocking { graph.engine.run(monitor.id) } }

        // The URL the page tells people to type, so the screen matches the text.
        runBlocking { graph.store.upsert(monitor.copy(url = "https://kuma.example.net/metrics")) }
        relaunchOnDashboard()
        openDetail("Uptime Kuma")
        waitForText("monitor_name=\"API\"")
        composeRule.captureDeviceScreenshot("kuma-detail")

        // The page a ringing alarm opens, fed from the stored checks the way
        // AlertCenter feeds it.
        alarmFor(monitor.id, "kuma-alarm")
    }

    /** The page a ringing alarm opens, from the intent the app itself builds for it. */
    private fun alarmFor(id: String, shot: String) {
        val snapshot = runBlocking { graph.store.currentSnapshot() }
        val monitor = snapshot.monitors.first { it.id == id }
        val runtime = snapshot.runtimes.getValue(id)
        val failing = runtime.samples.takeLastWhile { !it.ok }
        check(failing.isNotEmpty()) { "${monitor.name} has no failing checks to page about" }
        scenario?.close()
        val intent = graph.alerts.urgentPageIntent(
            monitor = monitor,
            result = graph.engine.pageEvidence(runtime),
            downForMs = System.currentTimeMillis() - failing.first().at,
            failedChecks = runtime.consecutiveFailures,
        ).setFlags(0)
        val alarm = ActivityScenario.launch<UrgentAlertActivity>(intent)
        try {
            Thread.sleep(2_000)
            composeRule.captureDeviceScreenshot(shot)
        } finally {
            alarm.close()
        }
    }

    // ----------------------------------------------------------- wireguard

    /** Builds the monitor through the wizard with the server running. */
    @Test
    fun wireguardUp() {
        reset()
        openWizardOn("UDP port")
        continueStep()
        type("Name", "WireGuard")
        type("Host", "10.0.2.2")
        composeRule.onNodeWithText("51820 WireGuard").performScrollTo().performClick()
        composeRule.waitForIdle()
        Thread.sleep(400)
        composeRule.captureDeviceScreenshot("wireguard-chip")
        continueStep()
        composeRule.onNodeWithText("This service won't answer a stranger").assertExists()
        toBottom()
        composeRule.captureDeviceScreenshot("wireguard-before")

        composeRule.onNodeWithText("Test now").performClick()
        waitForText("TRY 2 OF 3", 25_000)
        Thread.sleep(7_000)
        toBottom()
        // The panel's ticker runs on the test clock between actions; without
        // this the capture shows the countdown as of the last wait.
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()
        composeRule.captureDeviceScreenshot("wireguard-try")

        // Silent mode hears nothing on purpose, so every try runs to its timeout
        // before the ping. That is most of a minute at the defaults.
        waitForText("Not refused", 60_000)
        toBottom()
        continueStep()
        createAndAwait(1)
        val monitor = only()
        assertTrue(monitor.udp.probe == UdpProbe.SILENT)
        repeat(6) { runBlocking { graph.engine.run(monitor.id) } }
        rename(monitor.id, "vpn.example.net")
        relaunchOnDashboard()
        openDetail("WireGuard")
        composeRule.captureDeviceScreenshot("wireguard-up")
    }

    // No down capture. A stopped container still reads up from the emulator,
    // because its NAT drops the host's ICMP port unreachable before the guest
    // sees it. The refused path is covered by UdpMonitorInstrumentedTest against
    // a server inside the device, where nothing sits in between.

    private fun rename(id: String, host: String) = runBlocking {
        val m = graph.store.currentSnapshot().monitors.first { it.id == id }
        graph.store.upsert(m.copy(network = m.network.copy(host = host)))
    }

    // ------------------------------------------------------------- homelab

    @Test
    fun homelabDashboard() {
        seedLab(webUp = true)
        launch()
        waitForText("Admin UI")
        composeRule.captureDeviceScreenshot("homelab-dashboard")
        // The two web UIs fill the first screen, so the host checks get their own.
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(hasText("SSH"))
        composeRule.waitForIdle()
        // Scrolled to SSH, the DNS card stops behind the status bar. A slow drag
        // back down brings its header clear of the clock.
        composeRule.onNodeWithTag("dashboard-list").performTouchInput {
            swipeDown(startY = height * 0.3f, endY = height * 0.3f + 90.dp.toPx(), durationMillis = 800)
        }
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.captureDeviceScreenshot("homelab-hosts")
    }

    /** Run after `docker stop nb-seo-web`: both web UIs down, the host checks still up. */
    @Test
    fun homelabDown() {
        seedLab(webUp = false)
        launch()
        waitForText("Admin UI")
        composeRule.captureDeviceScreenshot("homelab-down")
        openDetail("Admin UI")
        composeRule.captureDeviceScreenshot("homelab-down-detail")
    }

    private fun seedLab(webUp: Boolean) {
        reset()
        val now = System.currentTimeMillis()
        val lab = listOf(
            Monitor(id = "lab-ping", name = "Lab server", kind = MonitorKind.PING, network = NetworkWatch(host = "10.0.2.2"), createdAt = now),
            Monitor(id = "lab-ssh", name = "SSH", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "10.0.2.2", port = 22), createdAt = now, accent = 1),
            Monitor(
                id = "lab-dns", name = "DNS", kind = MonitorKind.UDP_PORT,
                network = NetworkWatch(host = "10.0.2.2", port = 1053),
                udp = UdpWatch(probe = UdpProbe.DNS, dnsName = "lab.home.arpa"), createdAt = now, accent = 2,
            ),
            Monitor(id = "lab-web", name = "Dashboard", kind = MonitorKind.HTTP_STATUS, url = "http://10.0.2.2:8481/", createdAt = now, accent = 3),
            Monitor(
                id = "lab-admin", name = "Admin UI", kind = MonitorKind.HTTP_STATUS, url = "https://10.0.2.2:8444/",
                tlsTrust = TlsTrust.PINNED, createdAt = now, accent = 4,
            ),
        )
        runBlocking {
            lab.forEach { graph.store.upsert(it) }
            repeat(8) { lab.forEach { m -> graph.engine.run(m.id) } }
            val results = graph.store.currentSnapshot().runtimes
            lab.forEach { m ->
                val last = results[m.id]?.samples?.lastOrNull()
                val web = m.kind == MonitorKind.HTTP_STATUS
                val expected = webUp || !web
                assertTrue("${m.name} should be ${if (expected) "up" else "down"}: $last", last?.ok == expected)
            }
            // A home network's addresses, after the checks and before the capture.
            graph.store.currentSnapshot().monitors.forEach { m ->
                val moved = when (m.kind) {
                    MonitorKind.HTTP_STATUS -> m.copy(url = m.url.replace("10.0.2.2", "192.168.1.20"))
                    MonitorKind.UDP_PORT -> m.copy(network = m.network.copy(host = "192.168.1.2", port = 53))
                    else -> m.copy(network = m.network.copy(host = "192.168.1.20"))
                }
                graph.store.upsert(moved)
            }
            // A refused connection quotes the address it tried, so the stored
            // failures move house with the monitors.
            graph.store.updateAllRuntimes { r ->
                r.copy(
                    lastMessage = r.lastMessage.replace("10.0.2.2", "192.168.1.20"),
                    lastDetail = r.lastDetail.replace("10.0.2.2", "192.168.1.20"),
                    samples = r.samples.map { it.copy(note = it.note.replace("10.0.2.2", "192.168.1.20")) },
                )
            }
        }
    }
}
