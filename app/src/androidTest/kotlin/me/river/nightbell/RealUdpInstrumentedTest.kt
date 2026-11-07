package me.river.nightbell

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.NetworkChecker
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The probes against real public servers, so the codecs are held to what the
 * servers people point them at actually send, not only to the bytes written
 * in the other tests. Skipped offline.
 */
@RunWith(AndroidJUnit4::class)
class RealUdpInstrumentedTest {

    private val checker = NetworkChecker()

    @Before
    fun online() {
        assumeTrue("device is offline", Nightbell.install(appContext).network.isOnline())
    }

    private fun check(host: String, port: Int, watch: UdpWatch): CheckResult = runBlocking {
        checker.check(
            Monitor(id = "real", kind = MonitorKind.UDP_PORT, network = NetworkWatch(host = host, port = port), udp = watch, timeoutSeconds = 4),
        ).also { Log.i("UdpProbe", "$host:$port ${watch.probe}: ${it.message} ${it.udpFacts}") }
    }

    @Test
    fun cloudflareDnsAnswers() {
        val result = check("1.1.1.1", 53, UdpWatch(dnsName = "example.com"))
        assertTrue(result.message, result.ok)
        assertTrue(result.message, result.message.startsWith("NOERROR"))
    }

    @Test
    fun googleTimeAnswers() {
        val result = check("time.google.com", 123, UdpWatch(probe = UdpProbe.NTP))
        assertTrue(result.message, result.ok)
        assertTrue(result.message, result.message.startsWith("Stratum 1"))
    }

    @Test
    fun googleStunSeesAPublicAddress() {
        val result = check("stun.l.google.com", 19302, UdpWatch(probe = UdpProbe.STUN))
        assertTrue(result.message, result.ok)
        assertTrue(result.udpFacts.toString(), result.udpFacts.any { it.label == "Public address" })
    }

    @Test
    fun cloudflareSpeaksQuic() {
        val result = check("cloudflare.com", 443, UdpWatch(probe = UdpProbe.QUIC))
        assertTrue(result.message, result.ok)
        assertTrue(result.message, result.message.contains("QUIC v1"))
    }
}
