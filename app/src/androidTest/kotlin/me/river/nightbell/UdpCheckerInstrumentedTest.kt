package me.river.nightbell

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.river.nightbell.data.check.NetworkChecker
import me.river.nightbell.data.check.UdpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.ReplyMatch
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The socket half of the UDP checker, against the real Android kernel. The JVM
 * tests fake [UdpChecker.Datagrams]; this proves the one assumption the kind
 * rests on: a connected datagram socket on Android hears ICMP port unreachable
 * and libcore turns it into a refusal.
 */
@RunWith(AndroidJUnit4::class)
class UdpCheckerInstrumentedTest {

    private val checker = NetworkChecker()

    private fun monitor(host: String, port: Int, watch: UdpWatch, timeout: Int = 2) = Monitor(
        id = "u",
        kind = MonitorKind.UDP_PORT,
        network = NetworkWatch(host = host, port = port),
        udp = watch,
        timeoutSeconds = timeout,
    )

    /** A port nothing is bound to: bound once to learn a free number, then let go. */
    private fun closedPort(bind: InetAddress): Int = TinyUdp.quiet(bind = bind).use { it.port }

    @Test
    fun aDnsAnswerOverIpv4LoopbackIsUp() = runBlocking {
        TinyUdp.dns().use { server ->
            val result = checker.check(monitor("127.0.0.1", server.port, UdpWatch()))
            assertTrue(result.message, result.ok)
            assertTrue(result.message, result.message.startsWith("NOERROR, 1 answer"))
            assertTrue("a loopback round trip is under 100 ms", result.udp!!.rttUs in 1..100_000)
        }
    }

    @Test
    fun aDnsAnswerOverIpv6LoopbackIsUp() = runBlocking {
        val v6 = InetAddress.getByName("::1")
        TinyUdp.dns(bind = v6).use { server ->
            val result = checker.check(monitor("::1", server.port, UdpWatch()))
            assertTrue(result.message, result.ok)
        }
    }

    @Test
    fun aClosedPortIsRefusedOverIpv4() = runBlocking {
        val port = closedPort(InetAddress.getByName("127.0.0.1"))
        val result = checker.check(monitor("127.0.0.1", port, UdpWatch(attempts = 3)))
        assertEquals(result.message, FailureKind.PORT_CLOSED, result.failureKind)
        assertEquals("stopped at the first try", 1, result.udp!!.sent)
    }

    @Test
    fun aClosedPortIsRefusedOverIpv6() = runBlocking {
        val v6 = InetAddress.getByName("::1")
        val port = closedPort(v6)
        val result = checker.check(monitor("::1", port, UdpWatch(attempts = 1)))
        assertEquals(result.message, FailureKind.PORT_CLOSED, result.failureKind)
    }

    @Test
    fun aListenerThatNeverAnswersIsSilenceForEveryTry() = runBlocking {
        TinyUdp.quiet().use { server ->
            val result = checker.check(monitor("127.0.0.1", server.port, UdpWatch(probe = UdpProbe.CUSTOM, payload = "x", attempts = 2), timeout = 1))
            assertEquals(result.message, FailureKind.NO_REPLY, result.failureKind)
            assertEquals(2, server.received)
        }
    }

    @Test
    fun anEchoServerSatisfiesACustomProbe() = runBlocking {
        TinyUdp.echo().use { server ->
            val watch = UdpWatch(probe = UdpProbe.CUSTOM, payload = "PING\\r\\n", expect = ReplyMatch.STARTS_WITH, expectValue = "PING")
            val result = checker.check(monitor("127.0.0.1", server.port, watch))
            assertTrue(result.message, result.ok)
        }
    }

    @Test
    fun aSourceServerGetsItsChallengeAnsweredFromTheSamePort() = runBlocking {
        TinyUdp.sourceServer().use { server ->
            val result = checker.check(monitor("127.0.0.1", server.port, UdpWatch(probe = UdpProbe.SOURCE_QUERY, attempts = 1)))
            assertTrue(result.message, result.ok)
            assertTrue(result.message, result.message.startsWith("Tiny, 3 of 16 players"))
            assertEquals("the query and the one answer, nothing more", 2, server.received)
        }
    }

    @Test
    fun silentModeOnAQuietLoopbackListenerIsUpByEcho() = runBlocking {
        TinyUdp.quiet().use { server ->
            val result = checker.check(monitor("127.0.0.1", server.port, UdpWatch(probe = UdpProbe.SILENT, attempts = 1), timeout = 1))
            assertTrue(result.message, result.ok)
            assertEquals(true, result.udp!!.echo)
        }
    }

    @Test
    fun aCancelledCheckStopsWaiting() {
        TinyUdp.quiet().use { server ->
            val started = System.currentTimeMillis()
            val outcome = runCatching {
                runBlocking {
                    withTimeout(1_500) {
                        checker.check(monitor("127.0.0.1", server.port, UdpWatch(probe = UdpProbe.CUSTOM, payload = "x", attempts = 5), timeout = 10))
                    }
                }
            }
            assertTrue(outcome.isFailure)
            assertTrue("gave up near the timeout, not after 50 s", System.currentTimeMillis() - started < 5_000)
        }
    }

    /**
     * Not a pass or fail about Nightbell: what the emulator's own network does
     * with a closed UDP port on the host side. Logged so the run says whether a
     * refusal crosses the emulator's NAT, which decides how far silent mode can
     * be trusted on it. Only a reply would be wrong, nothing is listening.
     */
    @Test
    fun whatAClosedPortThroughTheEmulatorNatLooksLike() {
        val outcome = UdpChecker.Datagrams.System.exchange(InetSocketAddress("10.0.2.2", 9), byteArrayOf(0), 2_000) { UdpChecker.Step.Keep }
        Log.i("UdpProbe", "closed host port through the emulator NAT: $outcome")
        assertTrue(outcome !is UdpChecker.Exchange.Reply)
    }
}
