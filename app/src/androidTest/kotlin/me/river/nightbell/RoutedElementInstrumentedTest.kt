package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.WebViewProxy
import me.river.nightbell.domain.ElementMode
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page-element monitor watching a real Tor hidden service.
 *
 * This app claimed for a while that a page element could not be routed, on the
 * grounds that Android's WebView only takes an HTTP proxy. That was wrong:
 * `ProxyConfig.Builder.addProxyRule` documents the scheme as HTTP, HTTPS or
 * SOCKS, and Chromium's SOCKS5 resolves the hostname at the proxy, which is the
 * one property an onion address depends on. This test is what settles it, by
 * loading a page that exists nowhere except behind a real circuit.
 *
 * Needs the same setup as [RealSocksProxyInstrumentedTest], and skips without it.
 */
@RunWith(AndroidJUnit4::class)
class RoutedElementInstrumentedTest {

    @Before
    fun setUp() {
        assumeTrue("no onion address given: pass -P...onion=<addr>", ONION_URL != null)
        assumeTrue("no SOCKS5 proxy on 127.0.0.1:$PROXY_PORT", proxyIsListening())
        assumeTrue(
            "this WebView cannot be pointed at a proxy",
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
        )
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                socksProxyEnabled = true,
                socksProxyHost = "127.0.0.1",
                socksProxyPort = PROXY_PORT,
            ),
        )
    }

    private fun proxyIsListening(): Boolean = runCatching {
        Socket("127.0.0.1", PROXY_PORT).use { true }
    }.getOrDefault(false)

    private fun pageMonitor(proxied: Boolean) = Monitor(
        id = "routed-element",
        name = "Hidden page",
        kind = MonitorKind.WEBSITE_ELEMENT,
        url = ONION_URL!!,
        timeoutSeconds = 30,
        proxyTimeoutSeconds = 120,
        useProxy = proxied,
        elements = listOf(
            ElementTarget(
                cssSelector = "body",
                tagName = "body",
                mode = ElementMode.TEXT_CONTAINS,
                expectedText = "service-reached",
            ),
        ),
    )

    @Test
    fun aPageElementOnAHiddenServiceIsWatchedThroughTheCircuit() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }

        val result = runBlocking { graph.engine.dryRun(pageMonitor(proxied = true)) }

        assertTrue(
            "routed page-element check failed: ${result.message} / ${result.detail}",
            result.ok,
        )
    }

    @Test
    fun theSamePageWithoutRoutingCannotBeLoaded() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }

        val result = runBlocking { graph.engine.dryRun(pageMonitor(proxied = false)) }

        // The control. If this passed, the address would be resolving on the clear
        // net and the test above would be proving nothing.
        assertFalse("an onion page loaded without the proxy", result.ok)
    }

    @Test
    fun theOverrideIsAlwaysHandedBack() {
        // The override is process-wide, so a routed check that failed to clear it
        // would silently route every later WebView in the app, including the
        // element picker on a clearnet monitor.
        val endpoint = me.river.nightbell.domain.ProxyRoute.Endpoint("127.0.0.1", PROXY_PORT)
        runBlocking {
            runCatching { WebViewProxy.routed(endpoint) { error("boom") } }
        }
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val direct = runBlocking { graph.engine.dryRun(pageMonitor(proxied = false)) }

        // Still unreachable, i.e. still not going through the proxy left behind.
        assertFalse("the proxy override survived a failed routed load", direct.ok)
    }

    private companion object {
        const val PROXY_PORT = 9_050

        /** Supplied per run. A hidden service is ephemeral, so there is no default. */
        val ONION_URL: String? = InstrumentationRegistry.getArguments()
            .getString("onion")
            ?.let { "http://$it/" }
    }
}
