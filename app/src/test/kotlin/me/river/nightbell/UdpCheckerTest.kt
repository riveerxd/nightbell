package me.river.nightbell

import me.river.nightbell.domain.CheckProgress
import me.river.nightbell.data.check.ProgressSink
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.check.DnsCodec
import me.river.nightbell.data.check.NetworkChecker
import me.river.nightbell.data.check.UdpChecker
import me.river.nightbell.data.check.UdpChecker.Exchange
import me.river.nightbell.data.check.UdpCodec
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.Reachability
import me.river.nightbell.domain.ReplyMatch
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every verdict a UDP check can reach, with the socket faked. Real sockets are
 * proved on a device by `UdpCheckerInstrumentedTest`.
 */
class UdpCheckerTest {

    private val v4 = InetAddress.getByAddress("dns.lan", byteArrayOf(192.toByte(), 0, 2, 53))
    private val resolver = NetworkChecker.Resolver { host, _ ->
        if (host == "dns.lan" || host == "192.0.2.53") v4 else throw UnknownHostException(host)
    }

    /**
     * Each datagram sent takes the next scripted answer; a reply is built from
     * the request it answers. A challenge answer goes out inside the same
     * exchange, which is the same socket in production.
     */
    private class Script(vararg steps: (ByteArray) -> Exchange) {
        val queue = ArrayDeque(steps.toList())
        val sent = mutableListOf<ByteArray>()
        var exchanges = 0
        val datagrams = UdpChecker.Datagrams { _, request, _, answer ->
            exchanges++
            var outgoing = request
            while (true) {
                sent += outgoing
                val step = queue.removeFirstOrNull() ?: return@Datagrams Exchange.Silence
                val out = step(outgoing)
                if (out !is Exchange.Reply) return@Datagrams out
                when (val next = answer(out.bytes)) {
                    UdpChecker.Step.Keep -> return@Datagrams out
                    UdpChecker.Step.Skip -> return@Datagrams Exchange.Silence
                    is UdpChecker.Step.Send -> outgoing = next.bytes
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Exchange.Silence
        }
    }

    private val pauses = mutableListOf<Long>()
    private var clock = 1_000_000L

    private fun checker(
        script: Script,
        echo: NetworkChecker.Pinger = NetworkChecker.Pinger { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(900L)) },
        community: String = "",
    ) = NetworkChecker(
        resolver = resolver,
        pinger = echo,
        nowMs = { clock },
        udp = UdpChecker(script.datagrams, nowMs = { clock }, random = { it.fill(7) }, pause = { pauses += it; clock += it }),
        community = { community },
    )

    private fun udp(watch: UdpWatch = UdpWatch(), port: Int = 53, host: String = "dns.lan") =
        Monitor(id = "u", kind = MonitorKind.UDP_PORT, network = NetworkWatch(host = host, port = port), udp = watch, timeoutSeconds = 2)

    /** A NOERROR reply with one A record, for whatever DNS request it is handed. */
    private val dnsAnswer: (ByteArray) -> Exchange = { request ->
        val reply = request.copyOf()
        reply[2] = 0x81.toByte()
        reply[3] = 0x80.toByte()
        reply[7] = 1
        Exchange.Reply(reply + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 1), 410_000L)
    }

    @Test
    fun `a dns answer on the first try is up, with the round trip in microseconds`() = runBlocking {
        val result = checker(Script(dnsAnswer)).check(udp())
        assertTrue(result.message, result.ok)
        assertFalse(result.degraded)
        assertEquals("NOERROR, 1 answer, 0.41 ms", result.message)
        assertEquals(1L, result.latencyMs)
        val reading = result.udp!!
        assertEquals(1, reading.answeredOn)
        assertEquals(410L, reading.rttUs)
        assertTrue(result.udpFacts.any { it.label == "Returned" && it.value == "192.0.2.1" })
    }

    @Test
    fun `silence twice then an answer is up on try three, a second apart`() = runBlocking {
        val script = Script({ Exchange.Silence }, { Exchange.Silence }, dnsAnswer)
        val result = checker(script).check(udp())
        assertTrue(result.ok)
        assertEquals(3, result.udp!!.answeredOn)
        assertEquals(3, script.sent.size)
        assertTrue(result.udpFacts.any { it.label == "Answered on" && it.value == "try 3 of 3" })
        // Silence from the fake takes no time, so each later try waited out the gap.
        assertEquals(listOf(1000L, 1000L), pauses)
    }

    @Test
    fun `a refusal is down at once and says the port is closed`() = runBlocking {
        val script = Script({ Exchange.Refused }, dnsAnswer)
        val result = checker(script).check(udp())
        assertFalse(result.ok)
        assertEquals(FailureKind.PORT_CLOSED, result.failureKind)
        assertEquals("UDP port 53 closed on dns.lan (192.0.2.53)", result.message)
        assertEquals(1, script.sent.size)
        assertTrue(result.udp!!.refused)
        assertFalse("a refusal proves the network works", Reachability.neverReachedAnything(result.failureKind))
    }

    @Test
    fun `silence on every try is no reply`() = runBlocking {
        val result = checker(Script()).check(udp(UdpWatch(attempts = 2)))
        assertFalse(result.ok)
        assertEquals(FailureKind.NO_REPLY, result.failureKind)
        assertEquals("No reply from dns.lan:53 (192.0.2.53) after 2 tries", result.message)
        assertTrue(result.advice.contains("block outgoing UDP"))
    }

    @Test
    fun `a stray datagram is ignored and the try carries on`() = runBlocking {
        // A reply to some other query id: the socket's accept drops it, so the
        // fake reports silence for that try.
        val stray: (ByteArray) -> Exchange = { request ->
            val reply = request.copyOf()
            reply[0] = (reply[0] + 1).toByte()
            reply[2] = 0x81.toByte()
            Exchange.Reply(reply, 1000)
        }
        val result = checker(Script(stray, dnsAnswer)).check(udp())
        assertTrue(result.ok)
        assertEquals(2, result.udp!!.answeredOn)
    }

    @Test
    fun `dns REFUSED is a wrong reply, and wrong replies prove the network`() = runBlocking {
        val refusedAnswer: (ByteArray) -> Exchange = { request ->
            val reply = request.copyOf()
            reply[2] = 0x81.toByte()
            reply[3] = 0x85.toByte()
            Exchange.Reply(reply, 2000)
        }
        val result = checker(Script(refusedAnswer)).check(udp())
        assertFalse(result.ok)
        assertEquals(FailureKind.BAD_REPLY, result.failureKind)
        assertEquals("REFUSED for example.com", result.message)
        assertFalse(Reachability.neverReachedAnything(result.failureKind))
    }

    @Test
    fun `dns SERVFAIL is up but degraded, and the reason is the message`() = runBlocking {
        val servfail: (ByteArray) -> Exchange = { request ->
            val reply = request.copyOf()
            reply[2] = 0x81.toByte()
            reply[3] = 0x82.toByte()
            Exchange.Reply(reply, 2000)
        }
        val result = checker(Script(servfail)).check(udp())
        assertTrue(result.ok)
        assertTrue(result.degraded)
        assertTrue(result.message.startsWith("SERVFAIL for example.com"))
    }

    @Test
    fun `silent mode with no refusal and a ping reply is up, worded as not refused`() = runBlocking {
        val result = checker(Script()).check(udp(UdpWatch(probe = UdpProbe.SILENT, attempts = 1), port = 51820))
        assertTrue(result.ok)
        assertEquals("Not refused, host answers ping, 0.90 ms", result.message)
        assertEquals(true, result.udp!!.echo)
    }

    @Test
    fun `silent mode with no ping reply either is down`() = runBlocking {
        val noEcho = NetworkChecker.Pinger { _, _, _ -> NetworkChecker.PingOutcome.Replies(listOf(null)) }
        val result = checker(Script(), echo = noEcho).check(udp(UdpWatch(probe = UdpProbe.SILENT, attempts = 1), port = 51820))
        assertFalse(result.ok)
        assertEquals(FailureKind.NO_REPLY, result.failureKind)
        assertEquals(false, result.udp!!.echo)
    }

    @Test
    fun `test now hears every try and then the ping, in the order they happen`() = runBlocking {
        // Silent mode waits out every try before it pings. Without this the setup
        // screen showed "Running check..." for most of a minute and nothing else.
        val seen = mutableListOf<CheckProgress>()
        val result = withContext(ProgressSink { seen += it }) {
            checker(Script()).check(udp(UdpWatch(probe = UdpProbe.SILENT, attempts = 3), port = 51820))
        }
        assertTrue(result.ok)
        assertEquals(listOf(1, 2, 3), seen.filterIsInstance<CheckProgress.UdpTry>().map { it.attempt })
        assertTrue(seen.filterIsInstance<CheckProgress.UdpTry>().all { it.silent && it.tries == 3 && it.timeoutMs == 2_000L })
        assertTrue("the ping comes last", seen.last() is CheckProgress.UdpPing)
    }

    @Test
    fun `a refusal ends the progress at the try that was refused`() = runBlocking {
        val seen = mutableListOf<CheckProgress>()
        withContext(ProgressSink { seen += it }) {
            checker(Script({ Exchange.Silence }, { Exchange.Refused })).check(udp(UdpWatch(probe = UdpProbe.SILENT), port = 51820))
        }
        assertEquals(listOf(1, 2), seen.map { (it as CheckProgress.UdpTry).attempt })
    }

    @Test
    fun `a check with no sink in its context reports nowhere and still works`() = runBlocking {
        // Scheduled checks run like this, on the same engine as Test now.
        val result = checker(Script()).check(udp(UdpWatch(probe = UdpProbe.SILENT, attempts = 1), port = 51820))
        assertTrue(result.ok)
    }

    @Test
    fun `silent mode still goes down on a refusal`() = runBlocking {
        val result = checker(Script({ Exchange.Refused })).check(udp(UdpWatch(probe = UdpProbe.SILENT), port = 51820))
        assertEquals(FailureKind.PORT_CLOSED, result.failureKind)
    }

    @Test
    fun `silent mode sends one zero byte unless told otherwise`() = runBlocking {
        val script = Script()
        checker(script).check(udp(UdpWatch(probe = UdpProbe.SILENT, attempts = 1), port = 51820))
        assertEquals(listOf<Byte>(0), script.sent.single().toList())
    }

    @Test
    fun `source query answers its challenge inside the same try`() = runBlocking {
        val script = Script(
            { Exchange.Reply(byteArrayOf(-1, -1, -1, -1, 0x41, 9, 8, 7, 6), 1000) },
            { request ->
                assertEquals(listOf<Byte>(9, 8, 7, 6), request.takeLast(4))
                Exchange.Reply(byteArrayOf(-1, -1, -1, -1, 0x49, 17) + "Srv\u0000map\u0000f\u0000g\u0000".toByteArray() + byteArrayOf(0, 0, 1, 8), 3000)
            },
        )
        val result = checker(script).check(udp(UdpWatch(probe = UdpProbe.SOURCE_QUERY), port = 27015))
        assertTrue(result.message, result.ok)
        assertEquals(1, result.udp!!.answeredOn)
        assertTrue(result.message.startsWith("Srv, 1 of 8 players"))
        assertEquals("the answer left from the socket the challenge came to", 1, script.exchanges)
    }

    @Test
    fun `a server that challenges the answer too is a wrong reply, not silence`() = runBlocking {
        val challenge: (ByteArray) -> Exchange = { Exchange.Reply(byteArrayOf(-1, -1, -1, -1, 0x41, 9, 8, 7, 6), 1000) }
        val result = checker(Script(challenge, challenge)).check(udp(UdpWatch(probe = UdpProbe.SOURCE_QUERY, attempts = 1), port = 27015))
        assertEquals(FailureKind.BAD_REPLY, result.failureKind)
        assertEquals("Challenged again by dns.lan (192.0.2.53)", result.message)
    }

    @Test
    fun `a parser that throws gives a wrong reply instead of escaping the check`() {
        val broken = object : UdpCodec {
            override fun request(ctx: UdpCodec.Context) = byteArrayOf(0)
            override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read = throw IndexOutOfBoundsException()
        }
        val ctx = UdpCodec.Context(UdpWatch(probe = UdpProbe.QUIC), ByteArray(16), "h", 0, "")
        val read = UdpChecker.readSafely(broken, byteArrayOf(0x80.toByte(), 0, 0), ctx)
        assertTrue(read is UdpCodec.Read.Wrong)
        assertEquals("Unreadable HTTP/3 reply", (read as UdpCodec.Read.Wrong).message)
    }

    @Test
    fun `snmp without a saved community is a setup problem, and nothing is sent`() = runBlocking {
        val script = Script()
        val result = checker(script).check(udp(UdpWatch(probe = UdpProbe.SNMP), port = 161))
        assertEquals(FailureKind.BAD_CONFIG, result.failureKind)
        assertTrue(script.sent.isEmpty())
    }

    @Test
    fun `custom reply that does not match is wrong`() = runBlocking {
        val watch = UdpWatch(probe = UdpProbe.CUSTOM, payload = "hello", expect = ReplyMatch.CONTAINS, expectValue = "world")
        val result = checker(Script({ Exchange.Reply("nope".toByteArray(), 1000) })).check(udp(watch, port = 9999))
        assertEquals(FailureKind.BAD_REPLY, result.failureKind)
        assertTrue(result.udpFacts.any { it.label == "Text" && it.value == "nope" })
    }

    @Test
    fun `no port and an unknown name fail before anything is sent`() = runBlocking {
        val script = Script()
        assertEquals(FailureKind.BAD_CONFIG, checker(script).check(udp(port = 0)).failureKind)
        assertEquals(FailureKind.DNS, checker(script).check(udp(host = "nowhere.lan")).failureKind)
        assertTrue(script.sent.isEmpty())
    }

    @Test
    fun `a send that fails is a connect failure with the target named`() = runBlocking {
        val result = checker(Script({ Exchange.Failed(FailureKind.CONNECT, "No route") })).check(udp())
        assertEquals(FailureKind.CONNECT, result.failureKind)
        assertEquals("No route to dns.lan:53 (192.0.2.53)", result.message)
        assertNull(result.udp)
        assertNotNull(result.message)
    }

    @Test
    fun `the request carries the random id it is matched on`() = runBlocking {
        val script = Script(dnsAnswer)
        checker(script).check(udp())
        assertEquals(7, script.sent.single()[0].toInt())
        assertEquals(DnsCodec.encodeName("example.com").size, script.sent.single().size - 16)
    }
}
