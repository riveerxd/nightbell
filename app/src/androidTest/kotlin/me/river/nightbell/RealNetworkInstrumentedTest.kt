package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ping and port checks against the real internet: Cloudflare's resolver, which
 * answers ICMP and HTTPS and does not listen for SSH, and a TEST-NET address
 * that answers nothing. Needs a network, like the other Real* classes, and is
 * what the screenshots of the feature come from.
 */
@RunWith(AndroidJUnit4::class)
class RealNetworkInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val graph get() = Nightbell.install(appContext)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                theme = ThemeChoice.DARK,
                defaultAlert = AlertPolicy(cooldownMinutes = 0, quickRetry = false),
                confirmOutagesEnabled = false,
                latencyBaselineEnabled = false,
            ),
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun check(id: String) = runBlocking { graph.engine.run(id, force = true) }!!

    private fun health(id: String) = runBlocking { graph.store.currentSnapshot().runtimes[id]?.health }

    @Test
    fun aHostWatchedThreeWaysReadsAsOneStory() {
        runBlocking {
            graph.store.upsert(Monitor(id = "ping", name = "Cloudflare DNS", kind = MonitorKind.PING, network = NetworkWatch(host = "1.1.1.1", packets = 3), timeoutSeconds = 3))
            graph.store.upsert(Monitor(id = "https", name = "Cloudflare HTTPS", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "1.1.1.1", port = 443), timeoutSeconds = 5))
            graph.store.upsert(Monitor(id = "ssh", name = "Cloudflare SSH", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = "1.1.1.1", port = 22), timeoutSeconds = 3))
            graph.store.upsert(Monitor(id = "void", name = "Old office VPN", kind = MonitorKind.PING, network = NetworkWatch(host = "192.0.2.10", packets = 2), timeoutSeconds = 2))
        }
        // Twice each, so the history has more than one bar.
        repeat(2) {
            listOf("ping", "https", "ssh", "void").forEach { check(it) }
        }
        assertEquals(Health.UP, health("ping"))
        assertEquals(Health.UP, health("https"))
        assertEquals(Health.DOWN, health("ssh"))
        val void = check("void")
        assertTrue(void.failureKind == FailureKind.NO_REPLY || void.failureKind == FailureKind.CONNECT)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("real-01-dashboard")
        composeRule.onNodeWithTag("dashboard-list").performScrollToNode(androidx.compose.ui.test.hasText("Cloudflare DNS"))
        composeRule.captureScreenshot("real-01b-dashboard-ping")
        composeRule.onNodeWithText("Cloudflare DNS").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("real-02-ping-detail")
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("ping-card"))
        composeRule.captureScreenshot("real-03-ping-replies")
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasTestTag("same-host-card"))
        assertTrue(
            composeRule.onAllNodesWithText("The host answers ping, but port 22 is down", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.captureScreenshot("real-04-same-host")
    }
}
