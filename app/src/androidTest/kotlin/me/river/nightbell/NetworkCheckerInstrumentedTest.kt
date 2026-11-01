package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.IcmpPinger
import me.river.nightbell.data.check.NetworkChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.IpFamily
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real sockets, from the app's own UID and SELinux domain, which is the one
 * thing the JVM tests cannot say anything about: whether Android lets an
 * ordinary app open an ICMP socket at all.
 *
 * Loopback only for the assertions. The emulator answers pings to its host as
 * well, but whether it does depends on the host machine's own ping settings, and
 * a test that depends on the laptop it runs on is not a test of the app.
 */
@RunWith(AndroidJUnit4::class)
class NetworkCheckerInstrumentedTest {

    private val checker = NetworkChecker()

    private fun ping(host: String, packets: Int = 3, family: IpFamily = IpFamily.ANY, timeout: Int = 2) = Monitor(
        id = "ping-$host",
        kind = MonitorKind.PING,
        network = NetworkWatch(host = host, packets = packets, family = family),
        timeoutSeconds = timeout,
    )

    private fun tcp(host: String, port: Int, timeout: Int = 2) = Monitor(
        id = "tcp-$host-$port",
        kind = MonitorKind.TCP_PORT,
        network = NetworkWatch(host = host, port = port),
        timeoutSeconds = timeout,
    )

    @Test
    fun anOrdinaryAppCanPingIpv4Loopback() = runBlocking {
        val result = checker.check(ping("127.0.0.1"))
        assertTrue("${result.failureKind}: ${result.message} ${result.detail}", result.ok)
        val reading = assertNotNull(result.ping).let { result.ping!! }
        assertEquals(3, reading.sent)
        assertEquals(3, reading.received)
        assertTrue(reading.minUs in 1..reading.avgUs)
        assertTrue(reading.maxUs >= reading.avgUs)
        assertFalse(result.degraded)
        assertTrue(result.message, result.message.startsWith("3 of 3 replies"))
    }

    @Test
    fun anOrdinaryAppCanPingIpv6Loopback() = runBlocking {
        val result = checker.check(ping("::1", packets = 2))
        assertTrue("${result.failureKind}: ${result.message} ${result.detail}", result.ok)
        assertEquals(2, result.ping!!.received)
    }

    @Test
    fun aNameIsResolvedInTheFamilyAskedFor() = runBlocking {
        val v4 = checker.check(ping("localhost", packets = 1, family = IpFamily.V4))
        assertTrue("${v4.failureKind}: ${v4.message}", v4.ok)
        assertTrue(v4.message, v4.ping!!.received == 1)
    }

    /**
     * 192.0.2.0/24 is TEST-NET-1: routable to nothing, so every echo request
     * goes unanswered and the check must say so as NO_REPLY within its timeout
     * plus the gaps, not hang.
     */
    @Test
    fun anAddressThatNeverAnswersIsNoReplyWithinTheTimeout() = runBlocking {
        val started = System.currentTimeMillis()
        val result = checker.check(ping("192.0.2.10", packets = 2, timeout = 1))
        val took = System.currentTimeMillis() - started
        assertFalse(result.ok)
        // On a network with no route at all the send itself fails, which is the
        // same verdict reached sooner.
        assertTrue(
            "${result.failureKind}: ${result.message}",
            result.failureKind == FailureKind.NO_REPLY || result.failureKind == FailureKind.CONNECT,
        )
        assertTrue("took $took ms", took < (IcmpPinger.GAP_MS + 1_000L + 2_000L))
    }

    @Test
    fun anOpenPortIsUpAndAClosedOneIsRefused() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val open = checker.check(tcp("127.0.0.1", port))
        assertTrue("${open.failureKind}: ${open.message}", open.ok)
        assertTrue(open.message, open.message.startsWith("Port $port open"))
        server.close()

        val closed = checker.check(tcp("127.0.0.1", port))
        assertFalse(closed.ok)
        assertEquals(FailureKind.CONNECT, closed.failureKind)
        assertTrue(closed.message, closed.message.startsWith("Connection refused"))
    }

    @Test
    fun aPortOverIpv6LoopbackConnects() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("::1"))
        try {
            val result = checker.check(tcp("[::1]:${server.localPort}", server.localPort))
            assertTrue("${result.failureKind}: ${result.message}", result.ok)
        } finally {
            server.close()
        }
    }

    @Test
    fun aNameThatDoesNotExistIsDns() = runBlocking {
        val result = checker.check(tcp("no-such-host.invalid", 443))
        assertFalse(result.ok)
        assertEquals(FailureKind.DNS, result.failureKind)
    }
}
