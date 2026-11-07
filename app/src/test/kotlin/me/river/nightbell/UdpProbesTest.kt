package me.river.nightbell

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import me.river.nightbell.data.check.BedrockCodec
import me.river.nightbell.data.check.CustomCodec
import me.river.nightbell.data.check.DnsCodec
import me.river.nightbell.data.check.NtpCodec
import me.river.nightbell.data.check.QuicCodec
import me.river.nightbell.data.check.SipCodec
import me.river.nightbell.data.check.SnmpCodec
import me.river.nightbell.data.check.SourceQueryCodec
import me.river.nightbell.data.check.StunCodec
import me.river.nightbell.data.check.UdpCodec
import me.river.nightbell.domain.DnsType
import me.river.nightbell.domain.PayloadFormat
import me.river.nightbell.domain.ReplyMatch
import me.river.nightbell.domain.UdpWatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every probe's request against the bytes its protocol document says it must
 * be, and its parser against replies built the way a server builds them.
 * The servers here are hand-built from the RFCs and Valve's and RakNet's
 * notes, which is the point: the checker is held to the protocol, not to
 * itself.
 */
class UdpProbesTest {

    private val nonce = ByteArray(16) { (it * 17 + 3).toByte() }

    private fun ctx(watch: UdpWatch = UdpWatch(), sentAt: Long = 1_700_000_000_000L, community: String = "") =
        UdpCodec.Context(watch, nonce.copyOf(), "db.lan", sentAt, community)

    private fun good(read: UdpCodec.Read) = read as? UdpCodec.Read.Good ?: error("expected Good, got $read")

    private fun facts(read: UdpCodec.Read.Good) = read.facts.associate { it.label to it.value }

    // ---- DNS -----------------------------------------------------------------

    private fun dnsReply(id: ByteArray, rcode: Int, answers: List<ByteArray>, name: String = "example.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id[0].toInt())
        out.write(id[1].toInt())
        out.write(0x81)
        out.write(0x80 or rcode)
        out.write(byteArrayOf(0, 1, 0, answers.size.toByte(), 0, 0, 0, 0))
        out.write(DnsCodec.encodeName(name))
        out.write(byteArrayOf(0, 1, 0, 1))
        answers.forEach { rdata ->
            // A pointer back to the question's name at offset 12, as real servers write it.
            out.write(byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0x0e, 0x10, 0, rdata.size.toByte()))
            out.write(rdata)
        }
        return out.toByteArray()
    }

    @Test
    fun `dns request is one recursive question for the name and type`() {
        val request = DnsCodec.request(ctx(UdpWatch(dnsName = "example.com", dnsType = DnsType.AAAA)))
        assertEquals(nonce[0], request[0])
        assertEquals(nonce[1], request[1])
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0), request.copyOfRange(2, 12))
        val name = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        assertArrayEquals(name, request.copyOfRange(12, 12 + name.size))
        assertArrayEquals(byteArrayOf(0, 28, 0, 1), request.copyOfRange(12 + name.size, request.size))
    }

    @Test
    fun `dns NOERROR with addresses is up and lists them`() {
        val reply = dnsReply(nonce, 0, listOf(byteArrayOf(93.toByte(), 184.toByte(), 215.toByte(), 14), byteArrayOf(192.toByte(), 0, 2, 1)))
        val read = good(DnsCodec.read(reply, ctx()))
        assertNull(read.degraded)
        assertEquals("NOERROR, 2 answers", read.headline)
        assertEquals("93.184.215.14, 192.0.2.1", facts(read)["Returned"])
    }

    @Test
    fun `dns NXDOMAIN is still an answering server`() {
        val read = good(DnsCodec.read(dnsReply(nonce, 3, emptyList()), ctx()))
        assertNull(read.degraded)
        assertEquals("NXDOMAIN, 0 answers", read.headline)
    }

    @Test
    fun `dns SERVFAIL is degraded and REFUSED is wrong`() {
        val servfail = good(DnsCodec.read(dnsReply(nonce, 2, emptyList()), ctx()))
        assertTrue(servfail.degraded!!.startsWith("SERVFAIL for example.com"))
        val refused = DnsCodec.read(dnsReply(nonce, 5, emptyList()), ctx())
        assertTrue(refused is UdpCodec.Read.Wrong)
        assertEquals("REFUSED for example.com", (refused as UdpCodec.Read.Wrong).message)
    }

    @Test
    fun `dns answer that misses the expected address is degraded`() {
        val reply = dnsReply(nonce, 0, listOf(byteArrayOf(192.toByte(), 0, 2, 1)))
        val read = good(DnsCodec.read(reply, ctx(UdpWatch(dnsExpected = "192.0.2.99"))))
        assertEquals("example.com answered 192.0.2.1, expected 192.0.2.99", read.degraded)
        val ok = good(DnsCodec.read(reply, ctx(UdpWatch(dnsExpected = "192.0.2.1"))))
        assertNull(ok.degraded)
    }

    @Test
    fun `dns NXDOMAIN when an answer is expected is degraded, not up`() {
        val read = good(DnsCodec.read(dnsReply(nonce, 3, emptyList()), ctx(UdpWatch(dnsExpected = "192.0.2.10"))))
        assertEquals("example.com does not exist (NXDOMAIN), expected 192.0.2.10", read.degraded)
    }

    @Test
    fun `dns reply with another id or no QR bit is not ours`() {
        val other = dnsReply(byteArrayOf(9, 9), 0, emptyList())
        assertEquals(UdpCodec.Read.NotMine, DnsCodec.read(other, ctx()))
        val query = DnsCodec.request(ctx())
        assertEquals(UdpCodec.Read.NotMine, DnsCodec.read(query, ctx()))
        assertEquals(UdpCodec.Read.NotMine, DnsCodec.read(ByteArray(5), ctx()))
    }

    @Test
    fun `dns truncated answers still give the rcode`() {
        val reply = dnsReply(nonce, 0, listOf(byteArrayOf(1, 2, 3, 4)))
        val read = good(DnsCodec.read(reply.copyOfRange(0, reply.size - 3), ctx()))
        assertEquals("NOERROR, 1 answer", read.headline)
    }

    // ---- NTP -----------------------------------------------------------------

    private fun ntpReply(request: ByteArray, stratum: Int, serverMs: Long, leap: Int = 0, ref: ByteArray = byteArrayOf(10, 0, 0, 1)): ByteArray {
        val reply = ByteArray(48)
        reply[0] = ((leap shl 6) or (4 shl 3) or 4).toByte()
        reply[1] = stratum.toByte()
        ref.copyInto(reply, 12)
        request.copyInto(reply, 24, 40, 48)
        NtpCodec.timestamp(serverMs, byteArrayOf(0, 0)).copyInto(reply, 32)
        NtpCodec.timestamp(serverMs, byteArrayOf(0, 0)).copyInto(reply, 40)
        return reply
    }

    @Test
    fun `ntp request is a version 4 client packet carrying the send time`() {
        val c = ctx()
        val request = NtpCodec.request(c)
        assertEquals(48, request.size)
        assertEquals(0x23, request[0].toInt())
        assertArrayEquals(NtpCodec.timestamp(c.sentAtMs, nonce), request.copyOfRange(40, 48))
        assertEquals(c.sentAtMs / 1000, NtpCodec.millis(request, 40) / 1000)
    }

    @Test
    fun `ntp offset is read against the phone and judged only when asked`() {
        val c = ctx()
        val request = NtpCodec.request(c)
        c.receivedAtMs = c.sentAtMs + 20
        // A server 1500 ms ahead, answering at the middle of the round trip.
        val reply = ntpReply(request, stratum = 2, serverMs = c.sentAtMs + 10 + 1500)
        val shown = good(NtpCodec.read(reply, c))
        assertNull(shown.degraded)
        assertEquals("+1.5 s", facts(shown)["Clock offset"])
        assertEquals("2", facts(shown)["Stratum"])
        assertEquals("10.0.0.1", facts(shown)["Reference"])

        val judged = UdpCodec.Context(UdpWatch(ntpMaxOffsetMs = 500), c.nonce, "db.lan", c.sentAtMs).also { it.receivedAtMs = c.receivedAtMs }
        assertEquals("Clock off by +1.5 s, over the 500 ms allowed", good(NtpCodec.read(reply, judged)).degraded)
    }

    @Test
    fun `ntp stratum 16 or leap 3 is a server that does not know the time`() {
        val c = ctx()
        val request = NtpCodec.request(c)
        assertTrue(NtpCodec.read(ntpReply(request, 16, c.sentAtMs), c) is UdpCodec.Read.Wrong)
        assertTrue(NtpCodec.read(ntpReply(request, 3, c.sentAtMs, leap = 3), c) is UdpCodec.Read.Wrong)
    }

    @Test
    fun `ntp kiss of death is degraded and names its code`() {
        val c = ctx()
        val reply = ntpReply(NtpCodec.request(c), 0, c.sentAtMs, ref = "RATE".toByteArray())
        val read = good(NtpCodec.read(reply, c))
        assertEquals("Kiss-o'-death RATE", read.headline)
        assertNotNull(read.degraded)
    }

    @Test
    fun `ntp reply to another request is not ours`() {
        val c = ctx()
        val other = ntpReply(ByteArray(48), 2, c.sentAtMs)
        assertEquals(UdpCodec.Read.NotMine, NtpCodec.read(other, c))
    }

    // ---- STUN ----------------------------------------------------------------

    @Test
    fun `stun binding request carries the cookie and the transaction id`() {
        val request = StunCodec.request(ctx())
        assertEquals(20, request.size)
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xa4.toByte(), 0x42), request.copyOfRange(0, 8))
        assertArrayEquals(nonce.copyOfRange(0, 12), request.copyOfRange(8, 20))
    }

    @Test
    fun `stun success reads the xor mapped address`() {
        val request = StunCodec.request(ctx())
        // 203.0.113.5:41234, XORed with the cookie as RFC 8489 section 14.2 says.
        val port = 41234 xor 0x2112
        val ip = byteArrayOf(203.toByte(), 0, 113, 5)
        val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)
        val xored = ByteArray(4) { (ip[it].toInt() xor cookie[it].toInt()).toByte() }
        val attr = byteArrayOf(0, 0x20, 0, 8, 0, 1, (port shr 8).toByte(), port.toByte()) + xored
        val reply = byteArrayOf(0x01, 0x01, 0, attr.size.toByte()) + request.copyOfRange(4, 20) + attr
        val read = good(StunCodec.read(reply, ctx()))
        assertEquals("203.0.113.5:41234", facts(read)["Public address"])
    }

    @Test
    fun `stun error response is wrong`() {
        val request = StunCodec.request(ctx())
        val attr = byteArrayOf(0, 9, 0, 8, 0, 0, 4, 0) + "Bad!".toByteArray()
        val reply = byteArrayOf(0x01, 0x11, 0, attr.size.toByte()) + request.copyOfRange(4, 20) + attr
        val read = StunCodec.read(reply, ctx())
        assertTrue(read is UdpCodec.Read.Wrong)
        assertEquals("STUN error 400 Bad!", (read as UdpCodec.Read.Wrong).message)
    }

    // ---- SNMP ----------------------------------------------------------------

    @Test
    fun `snmp get request is BER encoded v2c with the community and the OID`() {
        val request = SnmpCodec.request(ctx(community = "secret"))
        assertEquals(0x30, request[0].toInt())
        // version 1 (v2c), then the community as an octet string
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x01, 0x04, 0x06) + "secret".toByteArray(), request.copyOfRange(2, 13))
        assertEquals(0xa0.toByte(), request[13])
        // sysUpTime.0 encoded the way RFC 1157 examples print it
        val oid = byteArrayOf(0x06, 0x08, 0x2b, 6, 1, 2, 1, 1, 3, 0)
        assertTrue(request.toList().windowed(oid.size).any { it == oid.toList() })
    }

    private fun snmpResponse(request: ByteArray, valueTag: Int, value: ByteArray, errorStatus: Int = 0): ByteArray {
        // Turn our own request into the agent's response: same request id, A2 PDU.
        fun tlv(tag: Int, v: ByteArray) = byteArrayOf(tag.toByte(), v.size.toByte()) + v
        val pduStart = 13 + 0 // header(2) + version(3) + community(2+6)
        val pdu = request.copyOfRange(pduStart + 2, request.size)
        val idLen = pdu[1].toInt()
        val id = pdu.copyOfRange(0, 2 + idLen)
        val varbind = tlv(0x30, tlv(0x30, SnmpCodec.oid(UdpWatch.SYS_UPTIME_OID) + tlv(valueTag, value)))
        val body = id + byteArrayOf(0x02, 0x01, errorStatus.toByte(), 0x02, 0x01, 0x00) + varbind
        return tlv(0x30, byteArrayOf(0x02, 0x01, 0x01) + tlv(0x04, "secret".toByteArray()) + tlv(0xa2, body))
    }

    @Test
    fun `snmp uptime comes back as a duration`() {
        val c = ctx(community = "secret")
        val request = SnmpCodec.request(c)
        // 12 days 3 hours in hundredths of a second.
        val ticks = (12L * 86_400 + 3 * 3600) * 100
        val reply = snmpResponse(request, 0x43, ByteBuffer.allocate(4).putInt(ticks.toInt()).array())
        val read = good(SnmpCodec.read(reply, c))
        assertEquals("Up for 12d 3h", read.headline)
    }

    @Test
    fun `snmp noSuchObject and error status are wrong`() {
        val c = ctx(community = "secret")
        val request = SnmpCodec.request(c)
        assertTrue(SnmpCodec.read(snmpResponse(request, 0x80, ByteArray(0)), c) is UdpCodec.Read.Wrong)
        assertTrue(SnmpCodec.read(snmpResponse(request, 0x05, ByteArray(0), errorStatus = 2), c) is UdpCodec.Read.Wrong)
    }

    @Test
    fun `snmp reply to another request id is not ours`() {
        val c = ctx(community = "secret")
        val other = SnmpCodec.request(UdpCodec.Context(UdpWatch(), ByteArray(16) { 1 }, "db.lan", 0, "secret"))
        assertEquals(UdpCodec.Read.NotMine, SnmpCodec.read(snmpResponse(other, 0x43, byteArrayOf(1)), c))
        assertEquals(UdpCodec.Read.NotMine, SnmpCodec.read(byteArrayOf(1, 2, 3), c))
    }

    @Test
    fun `oid check takes dotted numbers only`() {
        assertTrue(UdpWatch.isOid("1.3.6.1.2.1.1.3.0"))
        assertTrue(UdpWatch.isOid(".1.3.6.1.2.1.1.5.0"))
        assertTrue(!UdpWatch.isOid("1.3.x"))
        assertTrue(!UdpWatch.isOid("1"))
        assertTrue(!UdpWatch.isOid("3.1.2"))
        // Ten digits fit the arc length check and not an Int, which threw from
        // the setup screen's validation mid keystroke.
        assertTrue(!UdpWatch.isOid("3000000000.1"))
    }

    // ---- SIP -----------------------------------------------------------------

    @Test
    fun `sip options gets any status, 401 included`() {
        val c = ctx()
        val request = String(SipCodec.request(c))
        assertTrue(request.startsWith("OPTIONS sip:db.lan SIP/2.0\r\n"))
        val callId = request.lines().first { it.startsWith("Call-ID:") }.substringAfter(": ").trim()
        val reply = "SIP/2.0 401 Unauthorized\r\nCall-ID: $callId\r\nServer: Asterisk PBX 20\r\n\r\n".toByteArray()
        val read = good(SipCodec.read(reply, c))
        assertEquals("SIP 401 Unauthorized", read.headline)
        assertEquals("Asterisk PBX 20", facts(read)["Server"])
    }

    @Test
    fun `sip reply for another call is not ours`() {
        val reply = "SIP/2.0 200 OK\r\nCall-ID: someone-else\r\n\r\n".toByteArray()
        assertEquals(UdpCodec.Read.NotMine, SipCodec.read(reply, ctx()))
        assertEquals(UdpCodec.Read.NotMine, SipCodec.read("HTTP/1.1 200 OK\r\n\r\n".toByteArray(), ctx()))
    }

    // ---- QUIC ----------------------------------------------------------------

    @Test
    fun `quic probe is a padded long header with a reserved version`() {
        val request = QuicCodec.request(ctx())
        assertEquals(1200, request.size)
        assertEquals(0xc0, request[0].toInt() and 0xc0)
        assertArrayEquals(byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a), request.copyOfRange(1, 5))
    }

    @Test
    fun `quic version negotiation lists the versions`() {
        // The server swaps the connection ids, as RFC 9000 section 17.2.1 says.
        val reply = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + nonce.copyOfRange(8, 16) + byteArrayOf(8) + nonce.copyOfRange(0, 8) +
            byteArrayOf(0, 0, 0, 1, 0x6b, 0x33, 0x43, 0xcf.toByte(), 0x5a, 0x6a, 0x7a, 0x8a.toByte())
        val read = good(QuicCodec.read(reply, ctx()))
        assertEquals("Offers QUIC v1, QUIC v2", read.headline)
    }

    @Test
    fun `quic packet for another connection is not ours`() {
        val reply = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + ByteArray(8) + byteArrayOf(0, 0, 0, 0, 1)
        assertEquals(UdpCodec.Read.NotMine, QuicCodec.read(reply, ctx()))
    }

    // ---- Source query --------------------------------------------------------

    @Test
    fun `source query answers a challenge, then reads the info`() {
        val request = SourceQueryCodec.request(ctx())
        assertEquals("Source Engine Query", String(request, 5, 19))
        val challenge = SourceQueryCodec.read(byteArrayOf(-1, -1, -1, -1, 0x41, 1, 2, 3, 4), ctx())
        assertTrue(challenge is UdpCodec.Read.Challenge)
        val next = (challenge as UdpCodec.Read.Challenge).next
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), next.copyOfRange(next.size - 4, next.size))

        val info = ByteArrayOutputStream().apply {
            write(byteArrayOf(-1, -1, -1, -1, 0x49, 17))
            write("Frag Night\u0000de_dust2\u0000csgo\u0000Counter-Strike\u0000".toByteArray())
            write(byteArrayOf(0xda.toByte(), 0x02, 7, 24, 0))
        }.toByteArray()
        val read = good(SourceQueryCodec.read(info, ctx()))
        assertEquals("Frag Night, 7 of 24 players", read.headline)
        assertEquals("de_dust2", facts(read)["Map"])
    }

    // ---- Bedrock -------------------------------------------------------------

    @Test
    fun `bedrock pong gives motd and players`() {
        val request = BedrockCodec.request(ctx())
        assertEquals(0x01, request[0].toInt())
        assertArrayEquals(BedrockCodec.magic, request.copyOfRange(9, 25))
        val text = "MCPE;Block Party;712;1.21.40;3;10;1234567;Level;Survival;1;19132;19133;".toByteArray()
        val reply = byteArrayOf(0x1c) + request.copyOfRange(1, 9) + ByteArray(8) + BedrockCodec.magic +
            byteArrayOf((text.size shr 8).toByte(), text.size.toByte()) + text
        val read = good(BedrockCodec.read(reply, ctx()))
        assertEquals("Block Party, 3 of 10 players", read.headline)
        assertEquals("1.21.40", facts(read)["Version"])
    }

    // ---- Custom --------------------------------------------------------------

    @Test
    fun `custom sends the parsed payload and judges the reply`() {
        val watch = UdpWatch(payload = "PING\\r\\n", expect = ReplyMatch.STARTS_WITH, expectValue = "PONG")
        assertArrayEquals("PING\r\n".toByteArray(), CustomCodec.request(ctx(watch)))
        good(CustomCodec.read("PONG 1".toByteArray(), ctx(watch)))
        val wrong = CustomCodec.read("NOPE".toByteArray(), ctx(watch))
        assertTrue(wrong is UdpCodec.Read.Wrong)
        assertEquals("Reply did not match", (wrong as UdpCodec.Read.Wrong).message)
    }

    @Test
    fun `custom hex payload and hex prefix`() {
        val watch = UdpWatch(payload = "ff ff 00", payloadFormat = PayloadFormat.HEX, expect = ReplyMatch.HEX_PREFIX, expectValue = "ab cd")
        assertArrayEquals(byteArrayOf(-1, -1, 0), CustomCodec.request(ctx(watch)))
        good(CustomCodec.read(byteArrayOf(0xab.toByte(), 0xcd.toByte(), 1), ctx(watch)))
        assertTrue(CustomCodec.read(byteArrayOf(0xab.toByte()), ctx(watch)) is UdpCodec.Read.Wrong)
    }

    // ---- every parser, on what a broken or hostile server can send ------------

    /**
     * A truncated QUIC or A2S_INFO reply once threw out of the parser, and
     * nothing above it caught that: Test now crashed the app. Every codec gets
     * every prefix of a reply that starts the way its server's would, and
     * random tails after it, and must answer with a verdict, never a throw.
     */
    @Test
    fun `no parser throws on a short or garbled reply`() {
        val random = java.util.Random(21)
        val ffff = byteArrayOf(-1, -1, -1, -1)
        val heads = mapOf(
            DnsCodec to byteArrayOf(nonce[0], nonce[1], 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3),
            NtpCodec to byteArrayOf(0x24, 2, 0, 0),
            StunCodec to byteArrayOf(1, 1, 0, 12, 0x21, 0x12, 0xa4.toByte(), 0x42) + nonce.copyOfRange(0, 12),
            SnmpCodec to byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00, 0x02, 0x01, 0x01, 0x04, 0x06) + "public".toByteArray(),
            SipCodec to "SIP/2.0 200 OK\r\nCall-ID: ".toByteArray(),
            QuicCodec to byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + nonce.copyOfRange(8, 16) + byteArrayOf(8),
            SourceQueryCodec to ffff + byteArrayOf(0x49, 17, 0x41, 0x42),
            BedrockCodec to byteArrayOf(0x1c) + nonce.copyOfRange(0, 8),
            CustomCodec to "PONG".toByteArray(),
        )
        val watch = UdpWatch(payload = "PING", expect = ReplyMatch.STARTS_WITH, expectValue = "PONG")
        for ((codec, head) in heads) {
            repeat(200) { round ->
                val tail = ByteArray(random.nextInt(48)) { (if (round % 2 == 0) random.nextInt(255) + 1 else random.nextInt(256)).toByte() }
                val reply = head + tail
                for (cut in 0..reply.size) {
                    val bytes = reply.copyOf(cut)
                    val c = ctx(watch, community = "public")
                    runCatching { codec.read(bytes, c) }.onFailure {
                        throw AssertionError("${codec::class.simpleName} threw on ${bytes.joinToString(" ") { b -> "%02x".format(b) }}", it)
                    }
                }
            }
        }
    }
}
