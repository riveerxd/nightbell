package me.river.pulse

import android.Manifest
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import me.river.pulse.PulseTestSupport.captureScreenshot
import me.river.pulse.data.Pulse
import me.river.pulse.data.PulseSnapshot
import me.river.pulse.domain.AlertPolicy
import me.river.pulse.domain.AssertionMode
import me.river.pulse.domain.BodyAssertion
import me.river.pulse.domain.ElementMode
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.GlobalSettings
import me.river.pulse.domain.Health
import me.river.pulse.domain.HeaderPair
import me.river.pulse.domain.HttpMethod
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import me.river.pulse.domain.MonitorRuntime
import me.river.pulse.domain.Sample
import me.river.pulse.domain.SoundChoice
import me.river.pulse.domain.StatusExpectation
import me.river.pulse.domain.StatusMode
import me.river.pulse.domain.VibrationStyle
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion suite — this drives the app into each interesting state and
 * writes a PNG, so the visual result can be reviewed outside the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var server: TinyHttpServer

    @After
    fun tearDown() {
        scenario?.close()
        if (this::server.isInitialized) server.close()
    }

    private fun seedDemoData() {
        val now = System.currentTimeMillis()
        fun history(count: Int, seed: Int, failures: Set<Int> = emptySet(), base: Long = 180): List<Sample> {
            val rng = Random(seed)
            return (0 until count).map { i ->
                val wobble = (sin(i / 3.4) * 45).toLong() + rng.nextInt(-25, 40)
                val ok = i !in failures
                Sample(
                    at = now - (count - i) * 15 * 60_000L,
                    ok = ok,
                    latencyMs = if (ok) (base + wobble).coerceAtLeast(35) else base * 6,
                    code = if (ok) 200 else 503,
                    note = if (ok) "" else "Got 503, expected = 200",
                )
            }
        }

        val monitors = listOf(
            Monitor(
                id = "demo-api",
                name = "Checkout API",
                kind = MonitorKind.ADVANCED_REQUEST,
                url = "https://api.river.com/v1/health",
                method = HttpMethod.POST,
                headers = listOf(HeaderPair("Authorization", "Bearer •••"), HeaderPair("Accept", "application/json")),
                body = "{\n  \"probe\": \"deep\"\n}",
                assertion = BodyAssertion(AssertionMode.JSON_FIELD_EQUALS, value = "green", jsonPath = "data.health"),
                intervalMinutes = 5,
                accent = 0,
                createdAt = now,
            ),
            Monitor(
                id = "demo-site",
                name = "Marketing site",
                kind = MonitorKind.HTTP_STATUS,
                url = "https://river.com",
                intervalMinutes = 15,
                accent = 1,
                createdAt = now,
            ),
            Monitor(
                id = "demo-shop",
                name = "Price watch · Widget Deluxe",
                kind = MonitorKind.WEBSITE_ELEMENT,
                url = "https://shop.example.com/widget-deluxe",
                element = ElementTarget(
                    cssSelector = "span[data-testid=\"price\"]",
                    xpath = "/html/body/main[1]/div[2]/span[1]",
                    tagName = "span",
                    textSnippet = "£42.00",
                    mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                ),
                intervalMinutes = 60,
                accent = 2,
                createdAt = now,
            ),
            Monitor(
                id = "demo-cdn",
                name = "Asset CDN",
                kind = MonitorKind.HTTP_STATUS,
                url = "https://cdn.river.com/build/app.js",
                status = StatusExpectation(StatusMode.ANY_SUCCESS),
                intervalMinutes = 30,
                accent = 3,
                createdAt = now,
            ),
            Monitor(
                id = "demo-legacy",
                name = "Legacy redirect",
                kind = MonitorKind.HTTP_STATUS,
                url = "https://old.river.com",
                status = StatusExpectation(StatusMode.EXACT, code = 301),
                followRedirects = false,
                enabled = false,
                intervalMinutes = 120,
                accent = 4,
                createdAt = now,
            ),
        )

        val runtimes = mapOf(
            "demo-api" to MonitorRuntime(
                health = Health.UP,
                lastCheckedAt = now - 90_000,
                lastLatencyMs = 168,
                lastCode = 200,
                consecutiveSuccesses = 34,
                samples = history(40, seed = 11, base = 170),
            ),
            "demo-site" to MonitorRuntime(
                health = Health.UP,
                lastCheckedAt = now - 240_000,
                lastLatencyMs = 342,
                lastCode = 200,
                consecutiveSuccesses = 12,
                samples = history(40, seed = 22, failures = setOf(9, 10), base = 320),
            ),
            "demo-shop" to MonitorRuntime(
                health = Health.DOWN,
                lastCheckedAt = now - 60_000,
                lastLatencyMs = 1_240,
                lastCode = 0,
                lastMessage = "Element text changed",
                lastDetail = "Snapshot was \"£42.00\", now \"£58.50\".\nPage: Widget Deluxe — Example Shop\nDOM nodes: 1184",
                consecutiveFailures = 2,
                alerting = true,
                lastAlertAt = now - 55_000,
                lastElementText = "£58.50",
                samples = history(40, seed = 33, failures = setOf(37, 38, 39), base = 900),
            ),
            "demo-cdn" to MonitorRuntime(
                health = Health.DEGRADED,
                lastCheckedAt = now - 420_000,
                lastLatencyMs = 3_180,
                lastCode = 200,
                consecutiveSuccesses = 3,
                samples = history(40, seed = 44, base = 1_400),
            ),
            "demo-legacy" to MonitorRuntime(
                health = Health.PAUSED,
                lastCheckedAt = now - 86_400_000,
                lastLatencyMs = 92,
                lastCode = 301,
                samples = history(18, seed = 55, base = 95),
            ),
        )

        runBlocking {
            Pulse.install(PulseTestSupport.appContext).store.replaceAll(
                PulseSnapshot(
                    monitors = monitors,
                    runtimes = runtimes,
                    settings = GlobalSettings(
                        motionIntensity = 0f,
                        // Past the pager-setup gate, or every capture below is a
                        // photograph of the permissions screen.
                        hasSeenPagerSetup = true,
                        defaultAlert = AlertPolicy(
                            sound = SoundChoice.ALARM,
                            vibrationStyle = VibrationStyle.HEARTBEAT,
                            failureThreshold = 2,
                            repeatEnabled = true,
                            quietHoursEnabled = true,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun capturesTheWholeProduct() {
        seedDemoData()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        // 1 — populated dashboard
        composeRule.captureScreenshot("10-dashboard")

        // 2 — monitor detail (the failing element monitor is the interesting one)
        composeRule.onNodeWithTag("dashboard-list")
            .performScrollToNode(hasText("Price watch · Widget Deluxe"))
        composeRule.onNodeWithText("Price watch · Widget Deluxe").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("11-detail-failing")
        composeRule.onNodeWithTag("detail-list").performScrollToNode(hasContentDescription("Configuration"))
        composeRule.captureScreenshot("12-detail-config")
        // The header (and its Back button) is recycled once the list scrolls.
        composeRule.onNodeWithTag("detail-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        // 3 — settings + alert policy (the list kept its scroll position)
        composeRule.onNodeWithTag("dashboard-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("13-settings-top")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Heartbeat"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("14-settings-haptics")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Silence overnight"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("15-settings-escalation")
        composeRule.onNodeWithTag("settings-list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        // 4 — setup wizard
        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("16-setup-kind")
        composeRule.onNodeWithText("Request & response").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Name").performTextInput("Orders service")
        composeRule.onNodeWithContentDescription("URL").performTextInput("https://api.example.com/orders/health")
        composeRule.waitForIdle()
        composeRule.captureScreenshot("17-setup-target")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("18-setup-expectations")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.captureScreenshot("19-setup-cadence-alerts")
        composeRule.onNodeWithContentDescription("Cancel setup").performClick()
        composeRule.waitForIdle()
    }

    /**
     * Fires a genuine down alert and deliberately leaves it in the shade so it
     * can be captured with `adb shell cmd statusbar expand-notifications`.
     */
    @Test
    fun raisesARealDownAlertAndLeavesItPosted() {
        server = TinyHttpServer {
            TinyHttpServer.Response(code = 503, reason = "Service Unavailable", body = "maintenance")
        }
        PulseTestSupport.resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                defaultAlert = AlertPolicy(
                    sound = SoundChoice.ALARM,
                    vibrate = true,
                    vibrationStyle = VibrationStyle.HEARTBEAT,
                ),
            ),
        )
        val graph = Pulse.install(PulseTestSupport.appContext)
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "screenshot-down",
                    name = "Checkout API",
                    url = server.url("/v1/health"),
                    timeoutSeconds = 10,
                ),
            )
            graph.engine.run("screenshot-down")
        }
        PulseTestSupport.awaitTrue(description = "down alert posted") {
            PulseTestSupport.appContext
                .getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications.isNotEmpty()
        }
    }

    /**
     * Favicon badges on the dashboard.
     *
     * Two monitors on the same local site — one page-element, one status check —
     * so the screenshot shows both that the icon is used *and* that it is scoped
     * to the kind that benefits from it.
     */
    @Test
    fun capturesFaviconBadges() {
        val icon = ByteArrayOutputStream().use { out ->
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(android.graphics.Color.rgb(255, 92, 0))
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                }
                drawCircle(size / 2f, size / 2f, size * 0.30f, paint)
                paint.color = android.graphics.Color.rgb(255, 92, 0)
                drawCircle(size / 2f, size / 2f, size * 0.14f, paint)
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        server = TinyHttpServer { request ->
            when (request.path) {
                "/brand.png" -> TinyHttpServer.Response(contentType = "image/png", bytes = icon)
                else -> TinyHttpServer.Response(
                    contentType = "text/html; charset=utf-8",
                    body = """
                        <!doctype html><html><head><title>Example Shop</title>
                        <link rel="icon" href="/brand.png" sizes="96x96"></head>
                        <body><span data-testid="price">&pound;42.00</span></body></html>
                    """.trimIndent(),
                )
            }
        }

        PulseTestSupport.resetApp()
        val graph = Pulse.install(PulseTestSupport.appContext)
        val now = System.currentTimeMillis()
        runBlocking {
            graph.store.upsert(
                Monitor(
                    id = "favicon-element",
                    name = "Price watch · Widget Deluxe",
                    kind = MonitorKind.WEBSITE_ELEMENT,
                    url = server.url("/widget-deluxe"),
                    element = ElementTarget(
                        cssSelector = "span[data-testid=\"price\"]",
                        tagName = "span",
                        textSnippet = "£42.00",
                        mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                    ),
                    intervalMinutes = 60,
                    createdAt = now,
                ),
            )
            graph.store.upsert(
                Monitor(
                    id = "favicon-status",
                    name = "Shop homepage",
                    kind = MonitorKind.HTTP_STATUS,
                    url = server.url("/"),
                    intervalMinutes = 15,
                    createdAt = now,
                ),
            )
            // Warm the cache before the activity exists, so the badge is painted
            // on the first frame instead of arriving a few frames later.
            graph.favicons.clear()
            graph.favicons.load(server.url("/widget-deluxe"))
        }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.captureScreenshot("36-favicon-badges")
    }

    /** The live element picker, rendering a real page in the embedded browser. */
    @Test
    fun capturesTheElementPicker() {
        server = TinyHttpServer {
            TinyHttpServer.Response(
                contentType = "text/html; charset=utf-8",
                body = """
                    <!doctype html><html><head><meta name="viewport"
                      content="width=device-width, initial-scale=1"><title>Example Shop</title>
                    <style>
                      body{font-family:system-ui,sans-serif;margin:0;background:#f6f7fb;color:#12141c}
                      header{padding:18px 20px;background:#fff;border-bottom:1px solid #e6e8f0;font-weight:700}
                      .card{margin:18px;padding:20px;background:#fff;border-radius:16px;
                            box-shadow:0 6px 20px rgba(20,24,60,.08)}
                      h2{margin:0 0 6px;font-size:22px}
                      .price{font-size:30px;font-weight:800;color:#1b8a5a}
                      .stock{display:inline-block;margin-top:10px;padding:6px 12px;border-radius:999px;
                             background:#e7f7ef;color:#1b8a5a;font-weight:600;font-size:14px}
                      .muted{color:#6b7280;font-size:14px;margin-top:10px}
                    </style></head>
                    <body>
                      <header>Example Shop</header>
                      <div class="card">
                        <h2>Widget Deluxe</h2>
                        <span data-testid="price" class="price">&pound;42.00</span>
                        <div><span class="stock" data-testid="stock-state">In stock</span></div>
                        <p class="muted">Free delivery on orders over &pound;30.</p>
                      </div>
                      <div class="card">
                        <h2>Widget Mini</h2>
                        <span class="price">&pound;18.00</span>
                        <div><span class="stock">In stock</span></div>
                      </div>
                    </body></html>
                """.trimIndent(),
            )
        }

        PulseTestSupport.resetApp()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Page element").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Name").performTextInput("Widget price")
        composeRule.onNodeWithContentDescription("URL").performTextInput(server.url("/shop"))
        composeRule.waitForIdle()
        composeRule.captureScreenshot("20-setup-element-target")

        composeRule.onNodeWithText("Open live preview").performClick()
        composeRule.waitForIdle()
        Thread.sleep(3_500) // let the page paint
        composeRule.captureScreenshot("21-element-picker")
    }
}
