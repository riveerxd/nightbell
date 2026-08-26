package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A check driven through a SOCKS5 proxy that actually forwards.
 *
 * The proxy in [SocksProxyTest] answers HTTP on the socket it was handed and
 * never dials anything, which proves this app speaks SOCKS5 correctly and proves
 * nothing about what happens afterwards. This one talks to a real forwarding
 * proxy running outside the emulator, reached over `adb reverse tcp:9050`, which
 * resolves a name only it knows about and connects onward to a separate HTTP
 * server. That is the shape of a Tor hidden service: the address means nothing
 * to this device, the proxy is the only thing that can find it, and the bytes
 * come back down the tunnel.
 *
 * Run against a real Tor daemon publishing a real v3 hidden service, which is
 * the only arrangement that proves the whole chain: a name no resolver on earth
 * can answer, a rendezvous circuit built through six relays, and an origin server
 * the device has no other route to.
 *
 * Skips itself when nothing is listening on [PROXY_PORT], so the suite still
 * passes on a machine that has not set it up. To set it up:
 *
 *   tor -f torrc            # SocksPort 9050, HiddenServicePort 80 -> a local server
 *   adb reverse tcp:9050 tcp:9050
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.onion=$(cat hs/hostname)
 */
@RunWith(AndroidJUnit4::class)
class RealSocksProxyInstrumentedTest {

    @Before
    fun setUp() {
        assumeTrue("no onion address given: pass -P...onion=<addr>", ONION_URL != null)
        assumeTrue("no forwarding SOCKS5 proxy on 127.0.0.1:$PROXY_PORT", proxyIsListening())
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

    @Test
    fun anOnionAddressIsReachedThroughAProxyThatActuallyForwards() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val monitor = Monitor(
            id = "real-onion",
            name = "Hidden",
            kind = MonitorKind.HTTP_STATUS,
            // 56 base32 characters, the length a v3 hidden service actually has.
            url = ONION_URL!!,
            timeoutSeconds = 20,
            proxyTimeoutSeconds = 120,
            useProxy = true,
        )

        val result = runBlocking { graph.engine.dryRun(monitor) }

        assertTrue("proxied check failed: ${result.message} / ${result.detail}", result.ok)
        assertEquals(200, result.statusCode)
        // The body is served by a separate process the app has no route to except
        // through the proxy, so receiving it is the proof the tunnel carried data
        // rather than the proxy merely answering the handshake.
        // Served by a process the device has no route to except through the
        // circuit, so receiving these bytes is the proof the tunnel carried data
        // rather than something merely answering a handshake.
        assertTrue(
            "unexpected body through the tunnel: ${result.bodyPreview}",
            result.bodyPreview.contains("service-reached"),
        )
    }

    @Test
    fun theSameAddressWithoutTheProxyCannotResolve() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        val direct = Monitor(
            id = "direct-onion",
            name = "Hidden",
            kind = MonitorKind.HTTP_STATUS,
            url = ONION_URL!!,
            timeoutSeconds = 20,
            proxyTimeoutSeconds = 120,
            useProxy = false,
        )

        val result = runBlocking { graph.engine.dryRun(direct) }

        // The control for the test above. If this passed, the address would be
        // resolving somewhere on the clear net and the proxy would be proving
        // nothing at all.
        assertTrue("an onion address resolved without a proxy", !result.ok)
    }

    private companion object {
        /** Tor's own default SOCKS port, reached with `adb reverse tcp:9050`. */
        const val PROXY_PORT = 9_050

        /**
         * A hidden service published by a real Tor daemon on the build host.
         *
         * Overridable with `-Pandroid.testInstrumentationRunnerArguments.onion=...`
         * so the address can be regenerated without editing the test. The default
         * is whatever was live when this was last run, and the test skips rather
         * than fails when nothing is listening on [PROXY_PORT].
         */
        /** Supplied per run. A hidden service is ephemeral, so there is no default. */
        val ONION_URL: String? = InstrumentationRegistry.getArguments()
            .getString("onion")
            ?.let { "http://$it/" }
    }
}
