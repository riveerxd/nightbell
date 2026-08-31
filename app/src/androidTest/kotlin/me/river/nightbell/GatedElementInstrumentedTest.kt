package me.river.nightbell

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.domain.BrowserState
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
 * Issue #8, driven the way it was reported: a page you cannot see until you have
 * pressed something, and the element worth watching one link further in.
 *
 * The fixture is the shape of the site in the report. Every path is refused
 * until the visitor has been through the gate, the gate is dismissed by a click
 * that writes a first-party cookie from JavaScript and reloads, and the thing
 * worth watching is on a page reached by following a link afterwards.
 *
 * Two things have to be right before anything here passes. The monitor has to
 * end up pointed at the page the element was picked on, and the check has to
 * arrive carrying the session that gets it past the gate. Take either away and
 * the check loads the gate and calls the element missing, which is what was
 * filed.
 */
@RunWith(AndroidJUnit4::class)
class GatedElementInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var server: TinyHttpServer
    private var scenario: ActivityScenario<MainActivity>? = null

    private val gate = """
        <!doctype html>
        <html><head><title>Are you over 18?</title></head>
        <body style="margin:0">
          <div id="gate" style="position:fixed;top:0;left:0;right:0;bottom:0;background:#fff">
            <p>You must be 18 to view this site.</p>
            <button id="enter" onclick="document.cookie='entered=1;path=/';location.reload();">Yes I am 18</button>
          </div>
        </body></html>
    """.trimIndent()

    private val shop = """
        <!doctype html>
        <html><head><title>Fixture Shop</title></head>
        <body>
          <h1 id="headline">Fixture Shop</h1>
          <span id="price">£42.00</span>
          <a id="go" href="/cellar">See the cellar</a>
        </body></html>
    """.trimIndent()

    private val cellar = """
        <!doctype html>
        <html><head><title>The Cellar</title></head>
        <body>
          <h1 id="cellar-headline">The Cellar</h1>
          <span id="cellar-stock">6 bottles left</span>
        </body></html>
    """.trimIndent()

    /** A gate a page enforces itself, with a `localStorage` flag and no cookie. */
    private val storageGate = """
        <!doctype html>
        <html><head><title>Members only</title></head>
        <body style="margin:0">
          <div id="wall" style="position:fixed;top:0;left:0;right:0;bottom:0;background:#fff">
            <button id="accept" onclick="localStorage.setItem('member','yes');location.reload();">Accept</button>
          </div>
          <script>
            if (localStorage.getItem('member') === 'yes') {
              document.getElementById('wall').remove();
              var span = document.createElement('span');
              span.id = 'members-price';
              span.textContent = 'GBP 12.00';
              document.body.appendChild(span);
            }
          </script>
        </body></html>
    """.trimIndent()

    private fun html(body: String) =
        TinyHttpServer.Response(body = body, contentType = "text/html; charset=utf-8")

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        clearBrowsingData()
        server = TinyHttpServer { request ->
            val entered = request.headers["cookie"].orEmpty().contains("entered=1")
            when {
                // Served unconditionally: the wall it draws is enforced in the
                // page, not by this server, which is the other half of the report.
                request.path.startsWith("/members") -> html(storageGate)
                !entered -> html(gate)
                request.path.startsWith("/cellar") -> html(cellar)
                request.path.startsWith("/shop") -> html(shop)
                else -> TinyHttpServer.Response(code = 404, reason = "Not Found", body = "no")
            }
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
        clearBrowsingData()
    }

    // ---- plumbing ------------------------------------------------------------

    private fun clearBrowsingData() {
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().removeAllCookies { latch.countDown() }
            WebStorage.getInstance().deleteAllData()
        }
        latch.await(10, TimeUnit.SECONDS)
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
    }

    private fun findWebView(view: View): WebView? = when {
        view is WebView -> view
        view is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    /** The preview's WebView, once the picker has put one on screen. */
    private fun previewWebView(): WebView? {
        var found: WebView? = null
        scenario?.onActivity { found = findWebView(it.window.decorView) }
        return found
    }

    /**
     * Runs [script] in the preview and hands back what it evaluated to.
     *
     * The taps a person makes inside the page cannot be sent through Compose,
     * because the page is not Compose. Synthesising them in the document is what
     * the existing picker tests do, and it reaches the same listeners a real
     * touch would.
     */
    private fun inPreview(script: String): String {
        val view = previewWebView() ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { value ->
                result = value.orEmpty()
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        composeRule.waitForIdle()
        return result
    }

    private fun awaitInPreview(description: String, script: String) {
        awaitTrue(timeoutMs = 30_000, description = description) {
            inPreview("(function(){ return !!($script); })();") == "true"
        }
    }

    private fun toggleSelectMode() {
        composeRule.onNodeWithTag("picker-select-mode").performClick()
        composeRule.waitForIdle()
        // The mode is pushed into the page by a LaunchedEffect, so what follows
        // has to wait for the page to have heard about it, not for the switch to
        // have drawn.
        awaitTrue(description = "select mode never reached the page") {
            inPreview("(function(){ return window.__nightbellPickMode === true; })();").isNotBlank()
        }
    }

    private fun storedMonitors(): List<Monitor> = runBlocking {
        Nightbell.install(NightbellTestSupport.appContext).store.currentSnapshot().monitors
    }

    private fun checker() = ElementChecker(NightbellTestSupport.appContext)

    // ---- the reported journey, through the real UI ---------------------------

    /**
     * The whole of issue #8 in one pass: type the site's address, press through
     * its gate, follow a link, pick something, and have the check agree.
     */
    @Test
    fun anElementBehindAGateAndALinkIsMonitorable() {
        launchApp()

        composeRule.onNodeWithContentDescription("Add a monitor").performClick()
        composeRule.onNodeWithText("Page element").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithContentDescription("Name").performTextInput("Gated shop")
        composeRule.onNodeWithContentDescription("URL").performTextInput(server.url("/shop"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Open live preview").performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("picker-select-mode").fetchSemanticsNodes().size == 1
        }
        awaitTrue(timeoutMs = 30_000, description = "the preview never opened") {
            composeRule.waitForIdle()
            previewWebView() != null
        }
        awaitInPreview("the gate never rendered", "document.getElementById('enter')")
        composeRule.captureScreenshot("gate-01-preview-shows-the-gate")

        // Browsing mode, because select mode swallows the click by design.
        toggleSelectMode()
        inPreview("document.getElementById('enter').click();")
        awaitInPreview("the gate never let us through", "document.getElementById('price')")

        inPreview("document.getElementById('go').click();")
        awaitInPreview("the link never landed", "document.getElementById('cellar-stock')")
        composeRule.captureScreenshot("gate-02-past-the-gate-on-the-linked-page")

        toggleSelectMode()
        inPreview("document.getElementById('cellar-stock').click();")
        composeRule.waitForIdle()

        // The screen has to say the monitor is about to move before the button
        // that moves it is pressed.
        composeRule.onNodeWithTag("picker-moved-page").assertIsDisplayed()
        composeRule.onNodeWithText("You've moved to /cellar").assertIsDisplayed()
        composeRule.captureScreenshot("gate-03-picked-on-the-linked-page")
        composeRule.onNodeWithText("Watch this page").performClick()
        composeRule.waitForIdle()

        // Back on the setup screen: the captured session is disclosed rather
        // than hidden, because it is a thing with an expiry date.
        composeRule.onNodeWithTag("element-session-note").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Test now").performScrollTo().performClick()
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText("Check passed").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Check passed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Element matched via id in", substring = true).assertExists()
        composeRule.captureScreenshot("gate-04-check-passed")

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create monitor").performClick()
        awaitTrue(description = "the monitor was never stored") { storedMonitors().isNotEmpty() }

        val saved = storedMonitors().single()
        assertEquals(MonitorKind.WEBSITE_ELEMENT, saved.kind)
        assertEquals(
            "the monitor kept the typed URL instead of the page the element is on",
            server.url("/cellar"),
            saved.url,
        )
        assertFalse(
            "no session was captured, so the check is standing on a cookie nobody recorded",
            saved.browserState.isEmpty,
        )
        assertTrue(saved.browserState.cookies.contains("entered=1"))
        assertEquals("cellar-stock", saved.targets.single().elementId)
    }

    /**
     * The second half of the verification, and the one the first cannot make.
     *
     * A check run moments after the picker passes for a reason that has nothing
     * to do with this fix: the cookie is still in the process-wide store the
     * preview left it in. What the monitor is really standing on only shows once
     * that store is gone, which is what a reinstall, cleared app data and a phone
     * that sat idle past the cookie's expiry all look like.
     */
    @Test
    fun theCheckStillPassesAfterTheBrowserStoreIsWiped() {
        val monitor = Monitor(
            id = "gated",
            name = "Gated shop",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = server.url("/cellar"),
            timeoutSeconds = 30,
            browserState = BrowserState(
                origin = BrowserState.originOf(server.url("/cellar")),
                cookies = "entered=1",
                capturedAt = System.currentTimeMillis(),
            ),
        ).withTargets(listOf(ElementTarget(elementId = "cellar-stock")))

        clearBrowsingData()
        val passed = runBlocking { checker().check(monitor) }
        assertTrue("${passed.message} / ${passed.detail}", passed.ok)
        assertEquals("6 bottles left", passed.elementText)

        // The control. Without the captured session the same monitor on the same
        // page fails, so the assertion above is about the fix and not about the
        // fixture being open to anyone.
        clearBrowsingData()
        val blocked = runBlocking { checker().check(monitor.copy(browserState = BrowserState())) }
        assertFalse("the fixture let a check with no session through", blocked.ok)
    }

    /** The same, for a gate that leaves its flag in `localStorage` and no cookie. */
    @Test
    fun aStorageOnlyGateIsAlsoCarriedIntoTheCheck() {
        val url = server.url("/members")
        val monitor = Monitor(
            id = "members",
            name = "Members",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = url,
            timeoutSeconds = 30,
            browserState = BrowserState(
                origin = BrowserState.originOf(url),
                localStorage = """{"member":"yes"}""",
                capturedAt = System.currentTimeMillis(),
            ),
        ).withTargets(listOf(ElementTarget(elementId = "members-price")))

        clearBrowsingData()
        val passed = runBlocking { checker().check(monitor) }
        assertTrue("${passed.message} / ${passed.detail}", passed.ok)
        assertEquals("GBP 12.00", passed.elementText)

        clearBrowsingData()
        val blocked = runBlocking { checker().check(monitor.copy(browserState = BrowserState())) }
        assertFalse("the wall was not enforced, so the assertion above proves nothing", blocked.ok)
    }

    /**
     * What the check says when the gate has closed again, which it will: the
     * cookie on the site in the report lasts fourteen days.
     *
     * "Element not found" sends someone to look at their selector. The page is
     * what changed, so the page is what the message has to name.
     */
    @Test
    fun aClosedGateIsReportedAsAGateRatherThanAMissingElement() {
        val monitor = Monitor(
            id = "expired",
            name = "Gated shop",
            kind = MonitorKind.WEBSITE_ELEMENT,
            url = server.url("/cellar"),
            timeoutSeconds = 30,
        ).withTargets(listOf(ElementTarget(elementId = "cellar-stock")))

        clearBrowsingData()
        val result = runBlocking { checker().check(monitor) }
        assertFalse(result.ok)
        assertTrue(
            "the failure did not name what is standing over the page: ${result.detail}",
            result.detail.contains("Yes I am 18"),
        )
        assertTrue(result.detail.contains("live preview"))
    }

    /** A session belongs to the site it was taken on and travels nowhere else. */
    @Test
    fun aCapturedSessionIsNotOfferedToAnotherOrigin() {
        val state = BrowserState(
            origin = "https://gated.example",
            cookies = "entered=1",
            capturedAt = 1L,
        )
        assertTrue(state.appliesTo("https://gated.example/cellar?page=2"))
        assertFalse(state.appliesTo("https://gated.example.attacker.test/"))
        assertFalse(state.appliesTo("http://gated.example/"))

        clearBrowsingData()
        val located = runBlocking {
            checker().locateAll(
                server.url("/cellar"),
                listOf(ElementTarget(elementId = "cellar-stock")),
                timeoutSeconds = 30,
                state = state,
            )
        }
        assertNotNull(located)
        assertFalse(
            "a session for another host was pushed at this one",
            located!!.results.first().found,
        )
    }
}
