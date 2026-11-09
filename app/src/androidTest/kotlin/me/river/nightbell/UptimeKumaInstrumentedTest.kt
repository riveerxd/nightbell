package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.util.Base64
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Uptime Kuma guide on the website, done the way it tells people to: an API
 * key as the password with the username left blank, `monitor_status` not equal
 * to 0. The server here answers the way Kuma 2.5.9 did in Docker, 401 without
 * the key and its real metric lines with it, with one Kuma monitor down.
 *
 * Two fixes ride on it. The form used to refuse a password with no username.
 * And a check that failed on the value read back, after the server had answered
 * in a few milliseconds, showed "nothing answered to measure" on the detail
 * screen with an average of "-".
 */
@RunWith(AndroidJUnit4::class)
class UptimeKumaInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private val key = "uk1_device_test_key"
    private val authorizations = mutableListOf<String>()

    private val kuma = TinyHttpServer { request ->
        val auth = request.headers["authorization"].orEmpty()
        synchronized(authorizations) { authorizations += auth }
        if (auth == "Basic " + Base64.getEncoder().encodeToString(":$key".toByteArray())) {
            TinyHttpServer.Response(body = METRICS)
        } else {
            TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Unauthorized")
        }
    }

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
        kuma.close()
    }

    private fun type(field: String, text: String) {
        composeRule.onNodeWithContentDescription(field).performScrollTo().performTextInput(text)
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun toBottom() {
        composeRule.onNodeWithTag("setup-scroll").performScrollToNode(hasTestTag("setup-bottom"))
        composeRule.waitForIdle()
    }

    @Test
    fun aKumaKeyAloneSignsInAndADownKumaMonitorIsStillMeasured() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Prometheus").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        type("Name", "Uptime Kuma")
        type("Metrics URL", kuma.url("/metrics"))
        type("Password", key)
        composeRule.onAllNodesWithText("needs a username", substring = true).fetchSemanticsNodes().let {
            assertTrue("a password alone must not be refused", it.isEmpty())
        }
        composeRule.captureScreenshot("kuma-01-sign-in-key-only")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        type("Metric", "monitor_status")
        composeRule.onNodeWithText("Not equal").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Threshold").performScrollTo().performTextReplacement("0")
        Espresso.closeSoftKeyboard()
        toBottom()
        composeRule.onNodeWithText("Test now").performClick()
        // Naming the down Kuma monitor proves the key got through: a 401 reads
        // as a sign-in failure, never as a series.
        waitForText("monitor_name=\"API\"")
        toBottom()
        composeRule.captureScreenshot("kuma-02-test-names-api")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create monitor").performClick()
        composeRule.waitForIdle()
        NightbellTestSupport.awaitTrue(description = "the monitor to be stored and checked") {
            runBlocking { Nightbell.require().store.currentSnapshot().runtimes.values.any { it.samples.isNotEmpty() } }
        }
        val monitor = runBlocking { Nightbell.require().store.currentSnapshot().monitors.single() }
        assertEquals("", monitor.prometheus.username)
        assertTrue(
            "every request carried the key alone: $authorizations",
            synchronized(authorizations) {
                val expected = "Basic " + Base64.getEncoder().encodeToString(":$key".toByteArray())
                authorizations.isNotEmpty() && authorizations.all { it == expected }
            },
        )

        waitForText("Uptime Kuma")
        composeRule.onAllNodesWithText("Uptime Kuma").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasTestTag("detail-list")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasContentDescription("Configuration"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("basic auth, password only").assertExists()
        composeRule.onAllNodesWithText("nothing answered to measure", substring = true).fetchSemanticsNodes().let {
            assertTrue("Kuma answered; the check failed on the value, not on the round trip", it.isEmpty())
        }
        composeRule.onAllNodesWithText("inside it", substring = true).onFirst().assertExists()
        composeRule.captureScreenshot("kuma-03-detail-measured")
    }

    private companion object {
        val METRICS = """
            # HELP monitor_status Monitor Status (1 = UP, 0= DOWN, 2= PENDING, 3= MAINTENANCE)
            # TYPE monitor_status gauge
            monitor_status{monitor_id="1",monitor_name="Website",monitor_type="http",monitor_url="http://localhost:3001/",monitor_hostname="null",monitor_port="null"} 1
            monitor_status{monitor_id="2",monitor_name="API",monitor_type="http",monitor_url="http://127.0.0.1:9/",monitor_hostname="null",monitor_port="null"} 0
        """.trimIndent() + "\n"
    }
}
