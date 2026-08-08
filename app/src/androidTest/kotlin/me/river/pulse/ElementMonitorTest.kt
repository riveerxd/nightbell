package me.river.pulse

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.pulse.data.check.ElementChecker
import me.river.pulse.data.web.PickerScripts
import me.river.pulse.domain.ElementMode
import me.river.pulse.domain.ElementTarget
import me.river.pulse.domain.FailureKind
import me.river.pulse.domain.Monitor
import me.river.pulse.domain.MonitorKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the website-element pipeline against a real page rendered by a real
 * WebView: selector capture (the picker) and selector re-resolution (the check).
 */
@RunWith(AndroidJUnit4::class)
class ElementMonitorTest {

    private lateinit var server: TinyHttpServer

    private val page = """
        <!doctype html>
        <html><head><title>Nightbell Fixture Shop</title></head>
        <body>
          <header class="site-header"><h1>Fixture Shop</h1></header>
          <main>
            <div class="product-card">
              <h2 class="product-title">Widget Deluxe</h2>
              <span id="price">£42.00</span>
              <span class="availability" data-testid="stock-state">In stock</span>
              <a class="cta" href="/buy">Buy now</a>
            </div>
            <ul class="reviews">
              <li class="review">Great</li>
              <li class="review">Also great</li>
            </ul>
          </main>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server = TinyHttpServer { request ->
            when {
                request.path.startsWith("/shop") -> TinyHttpServer.Response(
                    body = page,
                    contentType = "text/html; charset=utf-8",
                )
                request.path.startsWith("/gone") -> TinyHttpServer.Response(
                    body = page.replace("""<span id="price">£42.00</span>""", ""),
                    contentType = "text/html; charset=utf-8",
                )
                request.path.startsWith("/changed") -> TinyHttpServer.Response(
                    body = page.replace("£42.00", "£58.50"),
                    contentType = "text/html; charset=utf-8",
                )
                else -> TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun checker() = ElementChecker(NightbellTestSupport.appContext)

    private fun elementMonitor(path: String, target: ElementTarget) = Monitor(
        id = "element-test",
        name = "Shop",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = server.url(path),
        element = target,
        timeoutSeconds = 20,
    )

    @Test
    fun locatesAnElementByIdOnARenderedPage() {
        val located = runBlocking {
            checker().locate(server.url("/shop"), ElementTarget(elementId = "price"), timeoutSeconds = 20)
        }
        assertNotNull("locate() returned null — the page never rendered", located)
        assertTrue("element was not found: $located", located!!.found)
        assertEquals("id", located.strategy)
        assertEquals("£42.00", located.text)
        assertTrue(located.pageTitle.contains("Fixture Shop"))
    }

    @Test
    fun locatesAnElementByCssPathWhenThereIsNoId() {
        val located = runBlocking {
            checker().locate(
                server.url("/shop"),
                ElementTarget(cssSelector = "span[data-testid=\"stock-state\"]"),
                timeoutSeconds = 20,
            )
        }
        assertTrue("not found: $located", located!!.found)
        assertEquals("css", located.strategy)
        assertEquals("In stock", located.text)
    }

    @Test
    fun fallsBackThroughStrategies() {
        // A selector that cannot match, but a text fingerprint that can.
        val located = runBlocking {
            checker().locate(
                server.url("/shop"),
                ElementTarget(cssSelector = "#definitely-not-here", textSnippet = "Widget Deluxe"),
                timeoutSeconds = 20,
            )
        }
        assertTrue("not found: $located", located!!.found)
        assertTrue(located.strategy.startsWith("text"))
        assertEquals("Widget Deluxe", located.text)
    }

    @Test
    fun textEqualsExpectationPassesAndFails() {
        val passing = runBlocking {
            checker().check(
                elementMonitor(
                    "/shop",
                    ElementTarget(
                        elementId = "price",
                        mode = ElementMode.TEXT_EQUALS,
                        expectedText = "£42.00",
                        textSnippet = "£42.00",
                    ),
                ),
            )
        }
        assertTrue(passing.message, passing.ok)
        assertEquals("£42.00", passing.elementText)

        val failing = runBlocking {
            checker().check(
                elementMonitor(
                    "/changed",
                    ElementTarget(
                        elementId = "price",
                        mode = ElementMode.TEXT_EQUALS,
                        expectedText = "£42.00",
                        textSnippet = "£42.00",
                    ),
                ),
            )
        }
        assertFalse(failing.ok)
        assertEquals(FailureKind.ELEMENT, failing.failureKind)
        assertTrue(failing.message.contains("£58.50"))
    }

    @Test
    fun snapshotModeDetectsDrift() {
        val result = runBlocking {
            checker().check(
                elementMonitor(
                    "/changed",
                    ElementTarget(
                        elementId = "price",
                        mode = ElementMode.TEXT_MATCHES_SNAPSHOT,
                        textSnippet = "£42.00",
                    ),
                ),
            )
        }
        assertFalse(result.ok)
        assertTrue(result.message.contains("changed"))
    }

    @Test
    fun missingElementFailsTheCheck() {
        val result = runBlocking {
            checker().check(
                elementMonitor("/gone", ElementTarget(elementId = "price", mode = ElementMode.EXISTS)),
            )
        }
        assertFalse(result.ok)
        assertEquals(FailureKind.ELEMENT, result.failureKind)
        assertTrue(result.message.contains("not found"))
    }

    @Test
    fun notExistsModeInvertsTheCheck() {
        val gone = runBlocking {
            checker().check(
                elementMonitor("/gone", ElementTarget(elementId = "price", mode = ElementMode.NOT_EXISTS)),
            )
        }
        assertTrue(gone.message, gone.ok)

        val present = runBlocking {
            checker().check(
                elementMonitor("/shop", ElementTarget(elementId = "price", mode = ElementMode.NOT_EXISTS)),
            )
        }
        assertFalse(present.ok)
    }

    @Test
    fun uncapturedElementIsReportedAsMisconfiguration() {
        val result = runBlocking { checker().check(elementMonitor("/shop", ElementTarget())) }
        assertFalse(result.ok)
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
    }

    // ---- picker capture ------------------------------------------------------

    private class CaptureBridge(private val latch: CountDownLatch) {
        @Volatile
        var payload: String? = null

        @JavascriptInterface
        fun onPick(json: String) {
            payload = json
            latch.countDown()
        }

        @JavascriptInterface
        fun onReady(title: String) = Unit

        @JavascriptInterface
        fun onError(message: String) {
            payload = "ERROR:$message"
            latch.countDown()
        }
    }

    /**
     * Drives the exact JavaScript the in-app picker injects: bootstrap, enable
     * pick mode, synthesise a tap on a node, and assert the derived signature
     * that comes back over the bridge.
     */
    @Test
    fun pickerScriptDerivesAStableSignatureFromATap() {
        val latch = CountDownLatch(1)
        val bridge = CaptureBridge(latch)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var webView: WebView? = null

        instrumentation.runOnMainSync {
            val view = WebView(NightbellTestSupport.appContext)
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.addJavascriptInterface(bridge, PickerScripts.BRIDGE_NAME)
            view.measure(1080, 1920)
            view.layout(0, 0, 1080, 1920)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(PickerScripts.BOOTSTRAP) {
                        view.evaluateJavascript(PickerScripts.setPickMode(true)) {
                            view.evaluateJavascript(
                                "document.querySelector('[data-testid=\"stock-state\"]').click();",
                                null,
                            )
                        }
                    }
                }
            }
            view.loadUrl(server.url("/shop"))
        }

        val delivered = latch.await(30, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { webView?.destroy() }

        assertTrue("picker bridge never fired", delivered)
        val payload = bridge.payload
        assertNotNull(payload)
        assertFalse("bridge reported an error: $payload", payload!!.startsWith("ERROR:"))
        assertTrue("payload was $payload", payload.contains("stock-state"))
        assertTrue("payload was $payload", payload.contains("In stock"))
        assertTrue("payload was $payload", payload.contains("\"tagName\":\"span\""))
        assertTrue("selector should be unique: $payload", payload.contains("\"unique\":true"))
    }

    /** The signature captured by the picker must round-trip into a working check. */
    @Test
    fun capturedSignatureRoundTripsIntoAPassingCheck() {
        val captured = ElementTarget(
            cssSelector = "span[data-testid=\"stock-state\"]",
            xpath = "/html/body/main[1]/div[1]/span[2]",
            tagName = "span",
            textSnippet = "In stock",
            mode = ElementMode.TEXT_CONTAINS,
            expectedText = "In stock",
        )
        val result = runBlocking { checker().check(elementMonitor("/shop", captured)) }
        assertTrue(result.message, result.ok)
        assertEquals("In stock", result.elementText)
    }
}
