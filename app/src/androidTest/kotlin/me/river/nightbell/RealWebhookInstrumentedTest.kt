package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.NightbellTestSupport.openSettingsTab
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.AlertPolicy
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path against real receiving software: webhooks set up through the
 * app's own screens, a monitor that really goes down and comes back, and the
 * messages read back out of real ntfy and Gotify servers.
 *
 * Skipped unless the servers are passed as instrumentation arguments, like the
 * other `Real*` classes. From an emulator, the host is 10.0.2.2:
 *
 * ```
 * adb shell am instrument -w -e class me.river.nightbell.RealWebhookInstrumentedTest \
 *   -e ntfy http://10.0.2.2:18080 -e gotify http://10.0.2.2:18081 \
 *   -e gotifyApp <app token> -e gotifyClient <client token> \
 *   me.river.nightbell.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealWebhookInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val args = InstrumentationRegistry.getArguments()
    private val http = OkHttpClient()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = Nightbell.install(appContext)

    @Volatile
    private var serviceUp = true
    private val service = TinyHttpServer {
        if (serviceUp) TinyHttpServer.Response(body = "ok") else TinyHttpServer.Response(code = 503, reason = "Down")
    }

    @After
    fun tearDown() {
        scenario?.close()
        service.close()
        NightbellTestSupport.resetApp()
    }

    private fun get(url: String, key: String? = null): String = http.newCall(
        Request.Builder().url(url).apply { if (key != null) header("X-Gotify-Key", key) }.build(),
    ).execute().use { it.body.string() }

    private fun addThroughTheUi(format: String, address: String, name: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("webhook-add"))
        composeRule.onNodeWithTag("webhook-add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(format).performClick()
        composeRule.onNodeWithContentDescription("Name").performTextInput(name)
        composeRule.onNodeWithContentDescription(if (format == "ntfy") "Topic URL" else "Server URL with app token")
            .performTextInput(address)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("webhook-scroll").performScrollToNode(hasTestTag("webhook-test"))
        composeRule.onNodeWithTag("webhook-test").performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(hasTestTag("webhook-test-result")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("webhook-scroll").performScrollToNode(hasTestTag("webhook-bottom"))
        composeRule.onNodeWithText("Delivered.", substring = true).assertIsDisplayed()
        composeRule.captureScreenshot("real-webhook-test-$format")
        composeRule.onNodeWithTag("webhook-save").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun setUpInTheAppThenAnOutageReachesRealNtfyAndGotify() {
        val ntfy = args.getString("ntfy")
        val gotify = args.getString("gotify")
        val gotifyApp = args.getString("gotifyApp")
        val gotifyClient = args.getString("gotifyClient")
        assumeTrue("pass -e ntfy and -e gotify", ntfy != null && gotify != null && gotifyApp != null && gotifyClient != null)
        val topic = "nightbell-" + UUID.randomUUID().toString().take(8)

        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(
                    monitors = listOf(
                        Monitor(
                            id = "api",
                            name = "Payments API",
                            kind = MonitorKind.HTTP_STATUS,
                            url = service.url("/health"),
                            timeoutSeconds = 5,
                            useGlobalAlerts = false,
                            alert = AlertPolicy(failureThreshold = 1, cooldownMinutes = 0),
                        ),
                    ),
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        hasSeenPagerSetup = true,
                        pagerSetupSilenced = true,
                        updateChecksEnabled = false,
                        latencyBaselineEnabled = false,
                    ),
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.openSettingsTab("Alerts")

        addThroughTheUi("ntfy", "$ntfy/$topic", "Phone push")
        addThroughTheUi("Gotify", "$gotify/message?token=$gotifyApp", "Homelab Gotify")
        awaitTrue(description = "both saved") { runBlocking { graph.store.currentSnapshot().settings.webhooks.size } == 2 }

        // The outage, through the engine exactly as a scheduled check runs it.
        runBlocking { graph.engine.run("api", force = true) }
        serviceUp = false
        runBlocking { graph.engine.run("api", force = true) }
        Thread.sleep(1_000)
        serviceUp = true
        runBlocking { graph.engine.run("api", force = true) }

        awaitTrue(timeoutMs = 30_000, description = "the queue to drain") {
            runBlocking { graph.store.currentSnapshot().webhookState.outbox.isEmpty() } &&
                runBlocking { graph.store.currentSnapshot().webhookState.status.values.all { it.lastDeliveredAt > 0 } }
        }

        val ntfyTitles = get("$ntfy/$topic/json?poll=1").lines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["event"]!!.jsonPrimitive.content == "message" }
            .map { it["title"]!!.jsonPrimitive.content to it["priority"]?.jsonPrimitive?.int }
        assertEquals(
            listOf(
                "Test from Nightbell to Phone push" to 3,
                "Payments API is down" to 5,
                "Payments API is back up" to 3,
            ),
            ntfyTitles,
        )

        val gotifyTitles = Json.parseToJsonElement(get("$gotify/message?limit=50", gotifyClient))
            .jsonObject["messages"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        // Newest first, and the server already holds earlier runs' messages.
        assertEquals(
            listOf("Payments API is back up", "Payments API is down", "Test from Nightbell to Homelab Gotify"),
            gotifyTitles.take(3),
        )

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("webhook-sender"))
        composeRule.onNodeWithText("Phone push").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("real-webhook-settings-after")
    }
}
