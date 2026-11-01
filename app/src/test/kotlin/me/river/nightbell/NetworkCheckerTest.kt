package me.river.nightbell

import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification half of ping and port checks, with the sockets faked. The
 * sockets themselves are proved on a device by `NetworkCheckerInstrumentedTest`.
 */
class NetworkCheckerTest {

    private val v4 = InetAddress.getByAddress("db.lan", byteArrayOf(192.toByte(), 0, 2, 10))
    private val v6 = InetAddress.getByName("2001:db8::1")

    private var askedFamily: IpFamily? = null
    private val resolver = NetworkChecker.Resolver { host, family ->
        askedFamily = family
        when (host) {
            "db.lan", "192.0.2.10" -> if (family == IpFamily.V6) throw UnknownHostException("$host has no IPv6") else v4
            "2001:db8::1" -> v6
            else -> throw UnknownHostException(host)
        }
    }

    private fun checker(
        pinger: NetworkChecker.Pinger = NetworkChecker.Pinger { _, count, _ -> NetworkChecker.PingOutcome.Replies(List(count) { 1_500L }) },
        connector: NetworkChecker.Connector = NetworkChecker.Connector { _, _ -> 23_400_000L },
    ) = NetworkChecker(resolver, pinger, connector, nowMs = { 42L })

    private fun ping(host: String = "db.lan", packets: Int = 3, family: IpFamily = IpFamily.ANY) =
        Monitor(id = "p", kind = MonitorKind.PING, network = NetworkWatch(host = host, packets = packets, family = family), timeoutSeconds = 2)

    private fun tcp(host: String = "db.lan", port: Int = 5432) =
        Monitor(id = "t", kind = MonitorKind.TCP_PORT, network = NetworkWatch(host = host, port = port), timeoutSeconds = 3)

    @Test
    fun `every reply back is up, with the average as the latency`() = runBlocking {
        val result = checker(pinger = { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(1_000L, 2_000L, 3_000L)) }).check(ping())
        assertTrue(result.ok)
        assertFalse(result.degraded)
        assertEquals(2L, result.latencyMs)
        val reading = assertNotNull(result.ping).let { result.ping!! }
        assertEquals(3, reading.received)
        assertEquals(1_000L, reading.minUs)
        assertEquals(3_000L, reading.maxUs)
        assertEquals("3 of 3 replies, 2.00 ms average", result.message)
        assertEquals(42L, result.at)
    }

    @Test
    fun `a sub millisecond reply is never a zero latency`() = runBlocking {
        val result = checker(pinger = { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(280L)) }).check(ping(packets = 1))
        assertEquals(1L, result.latencyMs)
        assertEquals("1 of 1 reply, 0.28 ms average", result.message)
    }

    @Test
    fun `some replies lost is degraded, not down`() = runBlocking {
        val result = checker(pinger = { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(1_000L, null, 1_000L)) }).check(ping())
        assertTrue(result.ok)
        assertTrue(result.degraded)
        assertEquals(33, result.ping!!.lossPercent)
        assertEquals("2 of 3 replies, 33% lost, 1.00 ms average", result.message)
    }

    @Test
    fun `no reply at all is down as NO_REPLY`() = runBlocking {
        val result = checker(pinger = { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(null, null)) }).check(ping(packets = 2))
        assertFalse(result.ok)
        assertEquals(FailureKind.NO_REPLY, result.failureKind)
        assertEquals("No reply from db.lan (192.0.2.10)", result.message)
        assertEquals(100, result.ping!!.lossPercent)
    }

    @Test
    fun `a refused send is the pinger's own verdict`() = runBlocking {
        val result = checker(pinger = { _, _, _ ->
            NetworkChecker.PingOutcome.Failed(FailureKind.CONNECT, "Network unreachable")
        }).check(ping())
        assertFalse(result.ok)
        assertEquals(FailureKind.CONNECT, result.failureKind)
        assertNull(result.ping)
    }

    @Test
    fun `the packet count is clamped`() = runBlocking {
        var asked = 0
        checker(pinger = { _, count, _ -> asked = count; NetworkChecker.PingOutcome.Replies(List(count) { 1L }) }).check(ping(packets = 99))
        assertEquals(10, asked)
    }

    @Test
    fun `a literal is the family it is written in whatever the chips say`() = runBlocking {
        checker().check(ping(host = "192.0.2.10", family = IpFamily.V6))
        assertEquals(IpFamily.V4, askedFamily)
    }

    @Test
    fun `a name with no address of the family asked for says so`() = runBlocking {
        val result = checker().check(ping(family = IpFamily.V6))
        assertFalse(result.ok)
        assertEquals(FailureKind.DNS, result.failureKind)
        assertEquals("db.lan has no IPv6 address", result.message)
    }

    @Test
    fun `an unknown name is DNS`() = runBlocking {
        val result = checker().check(tcp(host = "nope.lan"))
        assertEquals(FailureKind.DNS, result.failureKind)
        assertEquals("Can't resolve nope.lan", result.message)
    }

    @Test
    fun `no host is a configuration problem, not an outage of something`() = runBlocking {
        val result = checker().check(ping(host = ""))
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
    }

    @Test
    fun `an open port is up with the handshake time`() = runBlocking {
        val result = checker().check(tcp())
        assertTrue(result.ok)
        assertEquals(23L, result.latencyMs)
        assertEquals("Port 5432 open, connected in 23 ms", result.message)
        assertNull(result.ping)
    }

    @Test
    fun `refused, timed out and unroutable are told apart`() = runBlocking {
        val refused = checker(connector = { _, _ -> throw ConnectException("Connection refused") }).check(tcp())
        assertEquals(FailureKind.CONNECT, refused.failureKind)
        assertEquals("Connection refused by db.lan:5432 (192.0.2.10)", refused.message)

        val timeout = checker(connector = { _, _ -> throw SocketTimeoutException("connect timed out") }).check(tcp())
        assertEquals(FailureKind.TIMEOUT, timeout.failureKind)
        assertEquals("No answer from db.lan:5432 (192.0.2.10) in 3s", timeout.message)

        val route = checker(connector = { _, _ -> throw NoRouteToHostException("No route") }).check(tcp())
        assertEquals(FailureKind.CONNECT, route.failureKind)
        assertTrue(route.message.startsWith("No route to"))
    }

    @Test
    fun `the timeout is passed through in milliseconds`() = runBlocking {
        var asked = 0
        checker(connector = { _, ms -> asked = ms; 1_000_000L }).check(tcp())
        assertEquals(3_000, asked)
    }

    @Test
    fun `no port is a configuration problem`() = runBlocking {
        assertEquals(FailureKind.BAD_CONFIG, checker().check(tcp(port = 0)).failureKind)
        assertEquals(FailureKind.BAD_CONFIG, checker().check(tcp(port = 70_000)).failureKind)
    }

    @Test
    fun `an IPv6 port target reads with brackets`() = runBlocking {
        val result = checker(connector = { _, _ -> throw ConnectException("Connection refused") }).check(tcp(host = "2001:db8::1", port = 22))
        assertEquals("Connection refused by [2001:db8::1]:22", result.message)
    }

    @Test
    fun `a pasted host and port still resolves the host`() = runBlocking {
        val result = checker().check(tcp(host = "db.lan:5432"))
        assertTrue(result.message, result.ok)
    }

    // ---- the packets themselves --------------------------------------------

    @Test
    fun `an echo request carries its sequence and the salt`() {
        val packet = IcmpPinger.echoRequest(v6 = false, sequence = 0x0102, salt = 0xBEEF)
        assertEquals(8, packet[0].toInt())
        assertEquals(1, packet[6].toInt())
        assertEquals(2, packet[7].toInt())
        val v6 = IcmpPinger.echoRequest(v6 = true, sequence = 0, salt = 0)
        assertEquals(128, v6[0].toInt() and 0xff)
    }

    @Test
    fun `only our own echo reply is matched`() {
        val reply = IcmpPinger.echoRequest(v6 = false, sequence = 7, salt = 0x1234).also { it[0] = 0 }
        assertEquals(7, IcmpPinger.replySequence(reply, reply.size, v6 = false, salt = 0x1234))
        assertNull("another check's salt", IcmpPinger.replySequence(reply, reply.size, v6 = false, salt = 0x1235))
        assertNull("a request is not a reply", IcmpPinger.replySequence(IcmpPinger.echoRequest(false, 7, 0x1234), 24, v6 = false, salt = 0x1234))
        assertNull("too short", IcmpPinger.replySequence(reply, 10, v6 = false, salt = 0x1234))
        val v6reply = IcmpPinger.echoRequest(v6 = true, sequence = 3, salt = 9).also { it[0] = 129.toByte() }
        assertEquals(3, IcmpPinger.replySequence(v6reply, v6reply.size, v6 = true, salt = 9))
        assertNull("a v4 reply on a v6 socket", IcmpPinger.replySequence(reply, reply.size, v6 = true, salt = 0x1234))
    }
}
