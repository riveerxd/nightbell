package me.river.nightbell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.check.WebViewProxy
import me.river.nightbell.domain.ProxyRoute
import me.river.nightbell.ui.setup.ElementPickerOverlay
import me.river.nightbell.ui.theme.NightbellTheme
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The element picker obeying the monitor's route, which until now it did not.
 *
 * 3.1.0 routed a page-element *check* and left the *picker* alone, so setting up
 * a monitor on a hidden service resolved that hostname through the phone's own
 * DNS at pick time. The check being routed made no difference: the leak had
 * already happened, once, on the screen where the address was typed.
 *
 * Every test here is negative, because a positive one cannot see this bug. A
 * test that asserts the picker still renders a page passes with the leak present
 * and passes with it fixed. So what is asserted instead is what must **not**
 * happen: no connection to the target from this device, and no name handed to
 * this device's resolver.
 *
 * [aPreviewWithNoRouteDoesReachTheTargetDirectly] is the control that keeps the
 * rest honest. Without it, a WebView that failed to load anything at all in this
 * environment would satisfy every other assertion in the file.
 */
@RunWith(AndroidJUnit4::class)
class PickerRoutingInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var proxy: RecordingSocksProxy
    private lateinit var site: TinyHttpServer

    @Before
    fun setUp() {
        assumeTrue(
            "this WebView cannot be pointed at a proxy",
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
        )
        proxy = RecordingSocksProxy()
        site = TinyHttpServer {
            TinyHttpServer.Response(body = PAGE, contentType = "text/html; charset=utf-8")
        }
    }

    @After
    fun tearDown() {
        proxy.close()
        site.close()
    }

    private fun showPicker(url: String, route: ProxyRoute.Route) {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                ElementPickerOverlay(
                    visible = true,
                    url = url,
                    route = route,
                    existingSelector = "",
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
    }

    private fun via() = ProxyRoute.Route.Via(ProxyRoute.Endpoint("127.0.0.1", proxy.port))

    /**
     * The name goes to the proxy, not to this device's resolver.
     *
     * The address is 56 base32 characters and a `.onion` suffix, which no
     * resolver on earth can answer, so an address type of 3 carrying it verbatim
     * is proof that nothing here tried to look it up. Type 1 would mean this
     * device resolved something first, which for this name is impossible and for
     * a clearnet name routed on purpose is the leak.
     */
    @Test
    fun aRoutedPreviewHandsTheHostnameToTheProxy() {
        showPicker("http://$HIDDEN_HOST/", via())

        awaitTrue(description = "the picker to dial the proxy") { proxy.connections.isNotEmpty() }

        assertEquals(ADDRESS_TYPE_NAME, proxy.requestedAddressType)
        assertEquals(HIDDEN_HOST, proxy.requestedHost)
        assertEquals(80, proxy.requestedPort)
    }

    /**
     * Nothing reaches the target except through the tunnel.
     *
     * The target is a real server on this device, so a direct load is not merely
     * observable here, it is the easy path: loopback is what a proxy config's
     * implicit rules would send in the clear, which is why [WebViewProxy] removes
     * them. With the picker unrouted, as it shipped in 3.1.0, `site.received`
     * holds the request and this fails.
     */
    @Test
    fun aRoutedPreviewSendsNothingDirectToTheTarget() {
        showPicker(site.url("/page"), via())

        awaitTrue(description = "the picker to load the page by some route") {
            proxy.connections.isNotEmpty() || site.received.isNotEmpty()
        }

        assertTrue(
            "the picker fetched ${site.received.firstOrNull()?.path} directly, outside the proxy",
            site.received.isEmpty(),
        )
        assertEquals(site.port, proxy.requestedPort)
    }

    /**
     * The control. A direct route really does reach the server the tests above
     * assert is never reached, so those assertions are about the fix rather than
     * about a WebView that quietly loads nothing under test.
     */
    @Test
    fun aPreviewWithNoRouteDoesReachTheTargetDirectly() {
        showPicker(site.url("/page"), ProxyRoute.Route.Direct)

        awaitTrue(description = "the unrouted picker to reach the site") {
            site.received.isNotEmpty()
        }

        assertTrue("the proxy was used by a preview that never asked", proxy.connections.isEmpty())
    }

    /**
     * Asked to route, nowhere to route through: no page, and a reason.
     *
     * Refused rather than downgraded, which is the same rule the checker follows.
     * A fallback to a direct load would turn the bug into a designed behaviour.
     */
    @Test
    fun anUnconfiguredRouteRefusesToOpenThePreview() {
        showPicker(site.url("/page"), ProxyRoute.Route.Unconfigured)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("picker-refused").fetchSemanticsNodes().isNotEmpty()
        }
        // And it stayed refused rather than loading a moment later.
        Thread.sleep(1_500)
        assertTrue("a refused preview still loaded the page", site.received.isEmpty())
        assertTrue("a refused preview still dialled the proxy", proxy.connections.isEmpty())
    }

    /**
     * The same hole from the other side.
     *
     * An address that only exists inside Tor or I2P cannot be previewed directly
     * without publishing the name to this device's resolver, and the lookup is
     * the leak whether or not a page then loads. So the picker does not open.
     */
    @Test
    fun anUnroutedPreviewOfAHiddenServiceIsRefused() {
        showPicker("http://$HIDDEN_HOST/", ProxyRoute.Route.Direct)

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("picker-refused").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(proxy.connections.isEmpty())
    }

    /**
     * The override is handed back when the picker goes away.
     *
     * It is process-wide and this screen holds it for as long as it is open, so a
     * picker that closed without releasing it would leave every later WebView in
     * the app pointed at a proxy that may not be running, and would block every
     * routed check behind a gate nobody holds any more.
     */
    @Test
    fun closingThePreviewHandsTheOverrideBack() {
        var visible by mutableStateOf(true)
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f) {
                ElementPickerOverlay(
                    visible = visible,
                    url = site.url("/page"),
                    route = via(),
                    existingSelector = "",
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        awaitTrue(description = "the routed picker to dial the proxy") {
            proxy.connections.isNotEmpty()
        }

        composeRule.runOnUiThread { visible = false }
        // The overlay leaves composition at the end of its exit animation, and
        // under the test clock nothing advances that animation unless the test
        // asks. On a device the frames arrive on their own.
        composeRule.waitForIdle()

        // Taken with a short wait on purpose: if the picker were still holding it,
        // this would time out rather than succeed, and that is the failure.
        awaitTrue(description = "the proxy override to be free again") {
            runCatching {
                runBlocking { WebViewProxy.routed(via().endpoint, waitMs = 500) { true } }
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val ADDRESS_TYPE_NAME = 3

        /** 56 base32 characters, the length a v3 hidden service actually has. */
        const val HIDDEN_HOST = "nightbellpickertestaddressabcdefghijklmnopqrstuvwxyz23456.onion"

        val PAGE = """
            <!doctype html>
            <html><head><title>Picker fixture</title></head>
            <body><h1 id="headline">Routed</h1></body></html>
        """.trimIndent()
    }
}

/**
 * A SOCKS5 proxy that answers the handshake, records what it was asked for, and
 * serves a page on the same socket without dialling anything.
 *
 * The twins of this in `SocksProxyTest` and `ReportedIssuesInstrumentedTest`
 * serve `text/plain`, which a WebView will download rather than render, and they
 * keep only the last request. This one serves HTML and counts every connection,
 * because "how many times did anything reach the proxy" is half of what is being
 * asserted here.
 */
private class RecordingSocksProxy : AutoCloseable {

    private val server = ServerSocket(0)

    val connections = CopyOnWriteArrayList<String>()

    @Volatile var requestedAddressType: Int = -1
        private set

    @Volatile var requestedHost: String = ""
        private set

    @Volatile var requestedPort: Int = -1
        private set

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "RecordingSocksProxy") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val input = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            out.write(byteArrayOf(5, 0))
            out.flush()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            require(input.readUnsignedByte() == 1) { "not CONNECT" }
            input.readUnsignedByte()
            val type = input.readUnsignedByte()
            val host = when (type) {
                1 -> ByteArray(4).also { input.readFully(it) }
                    .joinToString(".") { (it.toInt() and 0xFF).toString() }
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    .toString(Charsets.US_ASCII)
                4 -> ByteArray(16).also { input.readFully(it) }.joinToString(":")
                else -> error("unknown address type $type")
            }
            val targetPort = input.readUnsignedShort()

            requestedAddressType = type
            requestedHost = host
            requestedPort = targetPort
            connections += "$host:$targetPort"

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // The client now believes it is talking to the target, so this is an
            // HTTP server on an already-open socket.
            val reader = client.getInputStream().bufferedReader()
            while (true) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                }
                val body = "<!doctype html><html><body><h1>Routed</h1></body></html>"
                val head = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${body.length}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
