package me.river.nightbell

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.resetApp
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.ProxyRoute
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
 * The other half of the request: an I2P hidden service.
 *
 * The report asks for "tor/i2p hidden services", and everything else here proves
 * Tor. I2P is the same SOCKS5 mechanism on a different port, which is an argument
 * rather than evidence, so this runs against a real i2pd router with a real
 * server tunnel and a real `.b32.i2p` address published to the I2P netDb.
 *
 * It also exercises the per-monitor address override for the reason that feature
 * exists: I2P's SOCKS proxy is on 4447 and Tor's on 9050, so watching one service
 * on each at the same time needs two addresses, which one global setting cannot
 * express.
 *
 * Skips when no router is listening. To set one up:
 *
 *   i2pd --datadir=... --conf=... --tunconf=...   # socksproxy 4447, server tunnel
 *   adb reverse tcp:4447 tcp:4447
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.i2p=$(cat i2p.txt)
 */
@RunWith(AndroidJUnit4::class)
class RealI2pInstrumentedTest {

    @Before
    fun setUp() {
        assumeTrue("no i2p address given: pass -P...i2p=<addr>", I2P_URL != null)
        assumeTrue("no I2P router on 127.0.0.1:$I2P_PORT", listening(I2P_PORT))
        resetApp(GlobalSettings(motionIntensity = 0f))
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)

    private fun monitor(proxied: Boolean, ownPort: Int? = null) = Monitor(
        id = "i2p",
        name = "I2P service",
        kind = MonitorKind.HTTP_STATUS,
        url = I2P_URL!!,
        timeoutSeconds = 30,
        proxyTimeoutSeconds = 120,
        useProxy = proxied,
        proxyHost = if (ownPort == null) "" else "127.0.0.1",
        proxyPort = ownPort ?: 0,
    )

    @Test
    fun anI2pAddressIsReachedThroughTheRouter() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                socksProxyEnabled = true,
                socksProxyHost = "127.0.0.1",
                socksProxyPort = I2P_PORT,
            ),
        )

        val result = runBlocking { graph.engine.dryRun(monitor(proxied = true)) }
        Log.i(TAG, "i2p check: ${result.latencyMs}ms ok=${result.ok} detail=${result.detail}")

        assertTrue("i2p check failed: ${result.message} / ${result.detail}", result.ok)
        assertEquals(200, result.statusCode)
        assertTrue(result.bodyPreview.contains("service-reached"))
    }

    @Test
    fun theSameAddressWithoutTheRouterCannotResolve() {
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }

        val result = runBlocking { graph.engine.dryRun(monitor(proxied = false)) }

        // A .b32.i2p name means nothing to any resolver outside I2P, so this is
        // the control that stops the test above passing for the wrong reason.
        assertFalse("an i2p address resolved without the router", result.ok)
    }

    @Test
    fun aMonitorCanNameTheI2pRouterWhileTheSharedProxyPointsAtTor() {
        // The case the per-monitor override was built for, with both networks
        // live at once rather than argued about.
        val graph = Nightbell.install(appContext).also { it.engine.isOnline = { true } }
        resetApp(
            GlobalSettings(
                motionIntensity = 0f,
                socksProxyEnabled = true,
                socksProxyHost = "127.0.0.1",
                // The shared address is Tor's. This monitor is not on Tor.
                socksProxyPort = TOR_PORT,
            ),
        )

        val viaOwnRouter = monitor(proxied = true, ownPort = I2P_PORT)
        assertEquals(
            ProxyRoute.Route.Via(ProxyRoute.Endpoint("127.0.0.1", I2P_PORT)),
            ProxyRoute.forMonitor(viaOwnRouter, graph.store.snapshot.value.settings),
        )

        val result = runBlocking { graph.engine.dryRun(viaOwnRouter) }
        Log.i(TAG, "i2p via per-monitor override: ok=${result.ok} detail=${result.detail}")

        assertTrue(
            "the per-monitor I2P address did not work: ${result.message} / ${result.detail}",
            result.ok,
        )
    }

    private companion object {
        const val TAG = "RealI2pTest"

        /** i2pd's default SOCKS port. Tor's is 9050, which is the whole point. */
        val I2P_PORT: Int =
            InstrumentationRegistry.getArguments().getString("i2pPort")?.toIntOrNull() ?: 4447
        const val TOR_PORT = 9_050

        /** Supplied per run. A server tunnel is ephemeral, so there is no default. */
        val I2P_URL: String? = InstrumentationRegistry.getArguments().getString("i2p")
            ?.let { "http://$it/" }
    }
}
