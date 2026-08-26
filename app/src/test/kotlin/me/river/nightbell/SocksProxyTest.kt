package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.ProxyRoute
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing a check through a SOCKS5 proxy, which is the only way to reach a Tor
 * or I2P hidden service from a device that is not itself in VPN mode.
 *
 * The proxy here is real enough to be worth the lines: it speaks the SOCKS5
 * handshake and records what it was asked to connect to, which is the one detail
 * the whole feature depends on. If OkHttp resolved the hostname on this side
 * before dialling, an .onion address would die at the lookup and this would show
 * up as an address type of IPv4 rather than a name.
 */
class SocksProxyTest {

    private fun monitor(url: String, proxied: Boolean) = Monitor(
        id = "socks",
        name = "Socks",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 10,
        useProxy = proxied,
    )

    private fun checkerVia(settings: GlobalSettings) = HttpChecker(settingsFor = { settings })

    private fun settingsFor(proxy: SocksServer) = GlobalSettings(
        socksProxyEnabled = true,
        socksProxyHost = "127.0.0.1",
        socksProxyPort = proxy.port,
    )

    @Test
    fun `an onion address is handed to the proxy as a name, not resolved here`() {
        SocksServer().use { proxy ->
            val checker = checkerVia(settingsFor(proxy))
            val onion = "http://nightbelltestaddressabcdefghijklmnopqrstuvwxyz234567abcd.onion/status"

            val result = runBlocking { checker.check(monitor(onion, proxied = true)) }

            assertTrue(result.message, result.ok)
            assertEquals(200, result.statusCode)
            // The point of the whole test. 0x03 is SOCKS5's "domain name": the
            // proxy did the lookup. 0x01 would mean this device resolved it first,
            // which for an onion address is not possible and for a clearnet one
            // routed on purpose would leak the name onto the local resolver.
            assertEquals(ADDRESS_TYPE_NAME, proxy.requestedAddressType)
            assertEquals(
                "nightbelltestaddressabcdefghijklmnopqrstuvwxyz234567abcd.onion",
                proxy.requestedHost,
            )
            assertEquals(80, proxy.requestedPort)
        }
    }

    @Test
    fun `a monitor that did not ask for the proxy still goes out directly`() {
        SocksServer().use { proxy ->
            TinyHttpServer { TinyHttpServer.Response(body = "direct") }.use { site ->
                val checker = checkerVia(settingsFor(proxy))

                val result = runBlocking { checker.check(monitor(site.url("/health"), proxied = false)) }

                assertTrue(result.message, result.ok)
                assertEquals("direct", result.bodyPreview)
                assertTrue("the proxy was used by a monitor that never asked", proxy.connections.isEmpty())
            }
        }
    }

    @Test
    fun `turning the proxy off refuses the routed check instead of sending it in the clear`() {
        SocksServer().use { proxy ->
            TinyHttpServer { TinyHttpServer.Response(body = "direct") }.use { site ->
                val checker = checkerVia(settingsFor(proxy).copy(socksProxyEnabled = false))

                val result = runBlocking { checker.check(monitor(site.url("/health"), proxied = true)) }

                // The earlier version of this test asserted the opposite, and the
                // opposite is a privacy hole: a monitor that asked to be routed and
                // was quietly sent direct publishes the hostname the proxy existed
                // to hide, to this device's resolver, once per interval, forever.
                assertFalse("a routed monitor was sent out in the clear", result.ok)
                assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
                assertTrue(proxy.connections.isEmpty())
                // And it did not reach the site either, by any route.
                assertTrue("the request was sent anyway", site.received.isEmpty())
            }
        }
    }

    @Test
    fun `a blank proxy address refuses the check rather than downgrading it`() {
        TinyHttpServer { TinyHttpServer.Response(body = "direct") }.use { site ->
            val checker = checkerVia(
                GlobalSettings(socksProxyEnabled = true, socksProxyHost = "   ", socksProxyPort = 9_050),
            )

            val result = runBlocking { checker.check(monitor(site.url("/health"), proxied = true)) }

            assertFalse(result.ok)
            assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
            assertTrue("the request was sent anyway", site.received.isEmpty())
        }
    }

    @Test
    fun `a monitor can name its own proxy, so two hidden networks can be watched at once`() {
        SocksServer().use { tor ->
            SocksServer().use { i2p ->
                val settings = settingsFor(tor)
                val checker = checkerVia(settings)

                val viaShared = monitor("http://sharedaddress${"a".repeat(43)}.onion/", proxied = true)
                val viaOwn = monitor("http://ownaddress${"b".repeat(46)}.i2p/", proxied = true)
                    .copy(proxyHost = "127.0.0.1", proxyPort = i2p.port)

                assertTrue(runBlocking { checker.check(viaShared) }.ok)
                assertTrue(runBlocking { checker.check(viaOwn) }.ok)

                // Each went where it was told, which is the whole point of the
                // override: one address cannot serve both networks.
                assertEquals(1, tor.connections.size)
                assertEquals(1, i2p.connections.size)
                assertTrue(tor.requestedHost.endsWith(".onion"))
                assertTrue(i2p.requestedHost.endsWith(".i2p"))
            }
        }
    }

    @Test
    fun `a monitor override borrows the shared port when it names only a host`() {
        SocksServer().use { proxy ->
            val settings = settingsFor(proxy)
            val named = monitor("http://borrow${"c".repeat(51)}.onion/", proxied = true)
                .copy(proxyHost = "127.0.0.1")

            assertEquals(
                ProxyRoute.Endpoint("127.0.0.1", proxy.port),
                ProxyRoute.override(named, settings),
            )
        }
    }

    @Test
    fun `a proxied monitor fails as a connection problem when the proxy is not there`() {
        // The failure a user meets when Orbot is not running. It has to be a plain
        // failed check like any other, not a crash and not a checker fault.
        val deadPort = ServerSocket(0).use { it.localPort }
        val checker = HttpChecker(
            settingsFor = {
                GlobalSettings(
                    socksProxyEnabled = true,
                    socksProxyHost = "127.0.0.1",
                    socksProxyPort = deadPort,
                )
            },
        )

        val result = runBlocking { checker.check(monitor("http://example.onion/", proxied = true)) }

        assertFalse(result.ok)
        assertEquals(FailureKind.CONNECT, result.failureKind)
    }

    @Test
    fun `an IPv6 loopback proxy works in both the forms the address can be written`() {
        // Straight out of the report, which asks for "127.0.0.1:9050 or
        // [::1]:9050". The Settings helper promises both spellings work; this is
        // what makes that a fact rather than a claim. Java parses the bracketed
        // form as well as the bare one, so the field can accept whichever the user
        // copied out of their proxy's config.
        SocksServer(loopbackV6 = true).use { proxy ->
            listOf("::1", "[::1]").forEach { host ->
                val checker = checkerVia(
                    GlobalSettings(
                        socksProxyEnabled = true,
                        socksProxyHost = host,
                        socksProxyPort = proxy.port,
                    ),
                )
                val result = runBlocking {
                    checker.check(monitor("http://v6${"d".repeat(52)}.onion/", proxied = true))
                }
                assertTrue("$host failed: ${result.message} / ${result.detail}", result.ok)
            }
            assertEquals(2, proxy.connections.size)
        }
    }

    @Test
    fun `a half-typed proxy address routes nothing rather than throwing`() {
        val blank = GlobalSettings(socksProxyEnabled = true, socksProxyHost = "  ", socksProxyPort = 9_050)
        val badPort = GlobalSettings(socksProxyEnabled = true, socksProxyHost = "127.0.0.1", socksProxyPort = 0)
        val off = GlobalSettings(socksProxyHost = "127.0.0.1", socksProxyPort = 9_050)

        assertNull(ProxyRoute.endpoint(blank))
        assertNull(ProxyRoute.endpoint(badPort))
        assertNull(ProxyRoute.endpoint(off))
        assertEquals(
            ProxyRoute.Endpoint("127.0.0.1", 9_050),
            ProxyRoute.endpoint(off.copy(socksProxyEnabled = true)),
        )
    }

    @Test
    fun `a page element monitor is routed like any other`() {
        val settings = GlobalSettings(
            socksProxyEnabled = true,
            socksProxyHost = "127.0.0.1",
            socksProxyPort = 9_050,
        )
        val page = monitor("http://example.onion/", proxied = true).copy(kind = MonitorKind.WEBSITE_ELEMENT)

        // This asserted the opposite until the WebView API was actually read.
        // ProxyConfig documents the scheme as HTTP, HTTPS or SOCKS, so the page
        // load can go through the tunnel like everything else, and refusing to
        // route it was a limitation this app invented for itself.
        assertEquals(
            ProxyRoute.Route.Via(ProxyRoute.Endpoint("127.0.0.1", 9_050)),
            ProxyRoute.forMonitor(page, settings),
        )
        assertEquals(
            ProxyRoute.Route.Via(ProxyRoute.Endpoint("127.0.0.1", 9_050)),
            ProxyRoute.forMonitor(page.copy(kind = MonitorKind.HTTP_STATUS), settings),
        )
        // And an element monitor that asked to be routed with nothing configured
        // is refused rather than loaded in the clear, same as the others.
        assertEquals(
            ProxyRoute.Route.Unconfigured,
            ProxyRoute.forMonitor(page, settings.copy(socksProxyEnabled = false)),
        )
    }

    @Test
    fun `a routed check gets a longer budget than a direct one`() {
        val settings = GlobalSettings(
            socksProxyEnabled = true,
            socksProxyHost = "127.0.0.1",
            socksProxyPort = 9_050,
        )
        val m = monitor("http://x${"a".repeat(55)}.onion/", proxied = true).copy(timeoutSeconds = 15)

        // Direct is unchanged: nothing about routing should slow down the checks
        // that are not routed.
        assertEquals(15, m.effectiveTimeoutSeconds(settings, proxied = false))
        // Routed inherits the shared default, which is longer because most of the
        // wait is Tor building a circuit rather than the service answering.
        assertEquals(60, m.effectiveTimeoutSeconds(settings, proxied = true))
    }

    @Test
    fun `a monitor can set its own routed budget, and a raised ordinary one is never cut back`() {
        val settings = GlobalSettings(
            socksProxyEnabled = true,
            socksProxyHost = "127.0.0.1",
            socksProxyPort = 9_050,
            proxiedTimeoutSeconds = 60,
        )
        val base = monitor("http://y${"b".repeat(55)}.onion/", proxied = true)

        assertEquals(120, base.copy(proxyTimeoutSeconds = 120).effectiveTimeoutSeconds(settings, true))
        // A monitor that has deliberately been given 90s keeps it rather than
        // being quietly reduced to the 60s default.
        assertEquals(90, base.copy(timeoutSeconds = 90).effectiveTimeoutSeconds(settings, true))
        // And an explicit routed budget wins over both.
        assertEquals(
            30,
            base.copy(timeoutSeconds = 90, proxyTimeoutSeconds = 30)
                .effectiveTimeoutSeconds(settings, true),
        )
    }

    @Test
    fun `the live preview is refused in both the cases a direct load would leak`() {
        val endpoint = ProxyRoute.Endpoint("127.0.0.1", 9_050)
        val onion = "http://x${"a".repeat(55)}.onion/"
        val clearnet = "https://status.example.com/health"

        // The two refusals. One is the monitor asking to be routed with nowhere to
        // route through, the other is an address that has no meaning outside Tor
        // or I2P being opened straight from the device. Both leak at the lookup,
        // which happens before any page loads and cannot be taken back.
        assertNotNull(ProxyRoute.previewRefusal(onion, ProxyRoute.Route.Unconfigured))
        assertNotNull(ProxyRoute.previewRefusal(clearnet, ProxyRoute.Route.Unconfigured))
        assertNotNull(ProxyRoute.previewRefusal(onion, ProxyRoute.Route.Direct))

        // And the two that are allowed. A routed preview of anything, and an
        // ordinary direct preview of a clearnet page, which is what nearly every
        // page-element monitor is.
        assertNull(ProxyRoute.previewRefusal(onion, ProxyRoute.Route.Via(endpoint)))
        assertNull(ProxyRoute.previewRefusal(clearnet, ProxyRoute.Route.Via(endpoint)))
        assertNull(ProxyRoute.previewRefusal(clearnet, ProxyRoute.Route.Direct))
    }

    @Test
    fun `a page element monitor gives the picker the same route as the check`() {
        val settings = GlobalSettings(
            socksProxyEnabled = true,
            socksProxyHost = "127.0.0.1",
            socksProxyPort = 9_050,
        )
        val page = monitor("http://y${"b".repeat(55)}.onion/", proxied = true)
            .copy(kind = MonitorKind.WEBSITE_ELEMENT)

        // The whole of the picker bug in one line: the route the check uses is the
        // route the picker has to use, because it is the same page fetched from the
        // same device. 3.1.0 computed this for the check only.
        val route = ProxyRoute.forMonitor(page, settings)
        assertEquals(ProxyRoute.Route.Via(ProxyRoute.Endpoint("127.0.0.1", 9_050)), route)
        assertNull(ProxyRoute.previewRefusal(page.url, route))
        // Routing off, and the address is one only the proxy can reach: no preview.
        assertNotNull(
            ProxyRoute.previewRefusal(
                page.url,
                ProxyRoute.forMonitor(page.copy(useProxy = false), settings),
            ),
        )
    }

    @Test
    fun `hidden services are recognised through the shapes a URL actually arrives in`() {
        assertTrue(ProxyRoute.isHiddenService("http://abc.onion"))
        assertTrue(ProxyRoute.isHiddenService("http://abc.onion/path?q=1"))
        assertTrue(ProxyRoute.isHiddenService("http://abc.onion:8080/"))
        assertTrue(ProxyRoute.isHiddenService("http://user:pw@abc.onion/"))
        assertTrue(ProxyRoute.isHiddenService("http://ABC.ONION/"))
        assertTrue(ProxyRoute.isHiddenService("http://abc.i2p/"))
        assertFalse(ProxyRoute.isHiddenService("https://example.com/onion"))
        assertFalse(ProxyRoute.isHiddenService("https://notonion.example.com/"))
    }

    private companion object {
        const val ADDRESS_TYPE_NAME = 3
    }
}

/**
 * A SOCKS5 proxy that gets as far as the connect request and then answers HTTP
 * on the same socket.
 *
 * It never dials anything. Pretending the connection succeeded is enough to
 * drive a whole check through the handshake, and it means the test does not
 * depend on a second server or on any name resolving anywhere.
 */
private class SocksServer(loopbackV6: Boolean = false) : AutoCloseable {

    private val server =
        if (loopbackV6) {
            ServerSocket(0, 0, java.net.InetAddress.getByName("::1"))
        } else {
            ServerSocket(0)
        }

    val connections = CopyOnWriteArrayList<String>()

    @Volatile var requestedAddressType: Int = -1
        private set

    @Volatile var requestedHost: String = ""
        private set

    @Volatile var requestedPort: Int = -1
        private set

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "SocksServer") {
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

            // Greeting: version, method count, then that many methods.
            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            val methods = input.readUnsignedByte()
            repeat(methods) { input.readUnsignedByte() }
            // No authentication, which is what a local Tor daemon offers.
            out.write(byteArrayOf(5, 0))
            out.flush()

            // Connect request.
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

            // Granted, bound to 0.0.0.0:0. Nothing reads the bound address.
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // From here the client believes it is talking to the target, so this
            // is just an HTTP server on an already-open socket.
            val reader = client.getInputStream().bufferedReader()
            while (true) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                }
                val body = "hidden"
                val head = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
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
