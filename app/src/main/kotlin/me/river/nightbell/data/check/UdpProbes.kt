package me.river.nightbell.data.check

import me.river.nightbell.domain.DnsType
import me.river.nightbell.domain.PingReading
import me.river.nightbell.domain.UdpFact
import me.river.nightbell.domain.UdpPayload
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpWatch
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Bytes out and bytes in for each [UdpProbe]. No sockets here: every request
 * and every reply parser runs on the JVM against bytes from the protocols'
 * own documents, which is where the bugs in this kind would live.
 */
interface UdpCodec {

    /**
     * What one try knows: the random bytes its reply has to echo, the time it
     * went out (NTP needs it), and the things a request is built from that are
     * not in [UdpWatch].
     */
    class Context(
        val watch: UdpWatch,
        val nonce: ByteArray,
        val host: String,
        val sentAtMs: Long,
        val community: String = "",
        /** Set by the checker once a reply is in, from the measured round trip. */
        var receivedAtMs: Long = sentAtMs,
    )

    fun request(ctx: Context): ByteArray

    fun read(reply: ByteArray, ctx: Context): Read

    sealed interface Read {
        /** The service answered as it should. [degraded] says why amber, when it is. */
        data class Good(val headline: String, val facts: List<UdpFact>, val degraded: String? = null) : Read

        /** Clearly the service, clearly a bad answer. Down, never silence. */
        data class Wrong(val message: String, val detail: String = "", val facts: List<UdpFact> = emptyList()) : Read

        /** Send [next] instead and keep waiting. Source query's anti-spoofing challenge. */
        data class Challenge(val next: ByteArray) : Read

        /** Not an answer to this try: an earlier try's late reply, or someone else's packet. */
        data object NotMine : Read
    }

    companion object {
        fun of(probe: UdpProbe): UdpCodec = when (probe) {
            UdpProbe.DNS -> DnsCodec
            UdpProbe.NTP -> NtpCodec
            UdpProbe.STUN -> StunCodec
            UdpProbe.SNMP -> SnmpCodec
            UdpProbe.SIP -> SipCodec
            UdpProbe.QUIC -> QuicCodec
            UdpProbe.SOURCE_QUERY -> SourceQueryCodec
            UdpProbe.BEDROCK -> BedrockCodec
            UdpProbe.CUSTOM -> CustomCodec
            UdpProbe.SILENT -> SilentCodec
        }

        internal fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
        internal fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
        internal fun u32(b: ByteArray, i: Int): Long =
            (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

        internal fun ByteArray.same(at: Int, other: ByteArray, from: Int = 0, length: Int = other.size - from): Boolean =
            at + length <= size && (0 until length).all { this[at + it] == other[from + it] }
    }
}

// ---- DNS ---------------------------------------------------------------------

/** RFC 1035. One question, recursion desired, matched on the id. */
internal object DnsCodec : UdpCodec {
    private val rcodes = mapOf(0 to "NOERROR", 1 to "FORMERR", 2 to "SERVFAIL", 3 to "NXDOMAIN", 4 to "NOTIMP", 5 to "REFUSED")

    fun rcodeName(code: Int) = rcodes[code] ?: "RCODE $code"

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ctx.nonce[0].toInt())
        out.write(ctx.nonce[1].toInt())
        out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.write(encodeName(ctx.watch.dnsName))
        val type = ctx.watch.dnsType.code
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0x00, 0x01))
        return out.toByteArray()
    }

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trim().removeSuffix(".").split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII).take(63).toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        if (reply.size < 12) return UdpCodec.Read.NotMine
        if (reply[0] != ctx.nonce[0] || reply[1] != ctx.nonce[1]) return UdpCodec.Read.NotMine
        if (UdpCodec.u8(reply, 2) and 0x80 == 0) return UdpCodec.Read.NotMine
        val rcode = UdpCodec.u8(reply, 3) and 0x0f
        val questions = UdpCodec.u16(reply, 4)
        val answerCount = UdpCodec.u16(reply, 6)
        val name = ctx.watch.dnsName.trim().removeSuffix(".").ifEmpty { "." }
        val answers = runCatching { answers(reply, questions, answerCount) }.getOrDefault(emptyList())
        val rname = rcodeName(rcode)
        val facts = buildList {
            add(UdpFact("Answer", rname))
            add(UdpFact("Records", answerCount.toString()))
            if (answers.isNotEmpty()) add(UdpFact("Returned", answers.take(4).joinToString(", ")))
        }
        val headline = "$rname, $answerCount ${if (answerCount == 1) "answer" else "answers"}"
        return when (rcode) {
            0, 3 -> {
                val expected = ctx.watch.dnsExpected.trim().removeSuffix(".")
                if (rcode == 3 && expected.isNotEmpty()) {
                    UdpCodec.Read.Good(headline, facts, degraded = "$name does not exist (NXDOMAIN), expected $expected")
                } else if (rcode == 0 && expected.isNotEmpty() &&
                    answers.none { it.removeSuffix(".").equals(expected, ignoreCase = true) || it.endsWith(" $expected", true) }
                ) {
                    UdpCodec.Read.Good(
                        headline, facts,
                        degraded = "$name answered ${answers.firstOrNull() ?: "nothing"}, expected $expected",
                    )
                } else {
                    UdpCodec.Read.Good(headline, facts)
                }
            }
            2 -> UdpCodec.Read.Good(headline, facts, degraded = "SERVFAIL for $name. The server answers but can't resolve it")
            5 -> UdpCodec.Read.Wrong(
                "REFUSED for $name",
                "The server answered and won't resolve for this phone. Check its allowed clients.",
                facts,
            )
            else -> UdpCodec.Read.Wrong("$rname for $name", "", facts)
        }
    }

    private fun answers(b: ByteArray, questions: Int, count: Int): List<String> {
        var at = 12
        repeat(questions) { at = skipName(b, at) + 4 }
        val out = mutableListOf<String>()
        repeat(count) {
            at = skipName(b, at)
            val type = UdpCodec.u16(b, at)
            val length = UdpCodec.u16(b, at + 8)
            val data = at + 10
            when (type) {
                DnsType.A.code -> if (length == 4) out += InetAddress.getByAddress(b.copyOfRange(data, data + 4)).hostAddress.orEmpty()
                DnsType.AAAA.code -> if (length == 16) out += InetAddress.getByAddress(b.copyOfRange(data, data + 16)).hostAddress.orEmpty()
                DnsType.MX.code -> out += "${UdpCodec.u16(b, data)} ${readName(b, data + 2)}"
                DnsType.NS.code, 5 -> out += readName(b, data)
                DnsType.TXT.code -> {
                    val len = UdpCodec.u8(b, data)
                    out += String(b, data + 1, minOf(len, length - 1), Charsets.UTF_8)
                }
            }
            at = data + length
        }
        return out
    }

    private fun skipName(b: ByteArray, start: Int): Int {
        var at = start
        while (true) {
            val len = UdpCodec.u8(b, at)
            if (len == 0) return at + 1
            if (len and 0xc0 == 0xc0) return at + 2
            at += len + 1
        }
    }

    internal fun readName(b: ByteArray, start: Int): String {
        val labels = mutableListOf<String>()
        var at = start
        var hops = 0
        while (hops < 32) {
            val len = UdpCodec.u8(b, at)
            if (len == 0) break
            if (len and 0xc0 == 0xc0) {
                at = ((len and 0x3f) shl 8) or UdpCodec.u8(b, at + 1)
                hops++
                continue
            }
            labels += String(b, at + 1, len, Charsets.US_ASCII)
            at += len + 1
        }
        return labels.joinToString(".").ifEmpty { "." }
    }
}

// ---- NTP ---------------------------------------------------------------------

/** RFC 4330 SNTP. Matched on the originate timestamp the server copies back. */
internal object NtpCodec : UdpCodec {
    private const val EPOCH_OFFSET_S = 2_208_988_800L

    fun timestamp(ms: Long, nonce: ByteArray): ByteArray {
        val seconds = ms / 1000 + EPOCH_OFFSET_S
        val fraction = (ms % 1000) * 0x1_0000_0000L / 1000
        val buffer = ByteBuffer.allocate(8).putInt(seconds.toInt()).putInt(fraction.toInt()).array()
        // The low two bytes of the fraction are 15 microseconds of precision
        // nobody can measure over a phone network, and random there they make
        // a late reply to an earlier try impossible to mistake for this one's.
        buffer[6] = nonce[0]
        buffer[7] = nonce[1]
        return buffer
    }

    fun millis(b: ByteArray, at: Int): Long {
        val seconds = UdpCodec.u32(b, at) - EPOCH_OFFSET_S
        val fraction = UdpCodec.u32(b, at + 4)
        return seconds * 1000 + fraction * 1000 / 0x1_0000_0000L
    }

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val packet = ByteArray(48)
        packet[0] = 0x23 // leap 0, version 4, mode 3 (client)
        timestamp(ctx.sentAtMs, ctx.nonce).copyInto(packet, 40)
        return packet
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        if (reply.size < 48) return UdpCodec.Read.NotMine
        if (UdpCodec.u8(reply, 0) and 0x07 != 4) return UdpCodec.Read.NotMine
        val sent = timestamp(ctx.sentAtMs, ctx.nonce)
        if (!reply.copyOfRange(24, 32).contentEquals(sent)) return UdpCodec.Read.NotMine
        val leap = UdpCodec.u8(reply, 0) shr 6
        val stratum = UdpCodec.u8(reply, 1)
        val refBytes = reply.copyOfRange(12, 16)
        val reference = if (stratum <= 1) {
            String(refBytes, Charsets.US_ASCII).trimEnd('\u0000').ifBlank { "none" }
        } else {
            InetAddress.getByAddress(refBytes).hostAddress.orEmpty()
        }
        if (stratum == 0) {
            return UdpCodec.Read.Good(
                "Kiss-o'-death $reference",
                listOf(UdpFact("Stratum", "0, kiss-o'-death"), UdpFact("Code", reference)),
                degraded = "The server answered with kiss-o'-death $reference and asks this phone to back off",
            )
        }
        val t2 = millis(reply, 32)
        val t3 = millis(reply, 40)
        val offset = ((t2 - ctx.sentAtMs) + (t3 - ctx.receivedAtMs)) / 2
        val facts = listOf(
            UdpFact("Stratum", stratum.toString()),
            UdpFact("Reference", reference),
            UdpFact("Clock offset", offsetText(offset)),
        )
        if (stratum >= 16 || leap == 3) {
            return UdpCodec.Read.Wrong(
                "Time server is not synchronized",
                "It answered, and says it does not know the time itself (stratum $stratum, leap $leap).",
                facts,
            )
        }
        val limit = ctx.watch.ntpMaxOffsetMs
        val headline = "Stratum $stratum, offset ${offsetText(offset)}"
        return if (limit > 0 && abs(offset) > limit) {
            UdpCodec.Read.Good(headline, facts, degraded = "Clock off by ${offsetText(offset)}, over the ${limit} ms allowed")
        } else {
            UdpCodec.Read.Good(headline, facts)
        }
    }

    fun offsetText(ms: Long): String {
        val sign = if (ms < 0) "-" else "+"
        val a = abs(ms)
        return if (a >= 1000) {
            String.format(Locale.ROOT, "%s%.1f s", sign, a / 1000.0)
        } else {
            "$sign$a ms"
        }
    }
}

// ---- STUN --------------------------------------------------------------------

/** RFC 8489 Binding request. Matched on the transaction id. */
internal object StunCodec : UdpCodec {
    private val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val packet = ByteArray(20)
        packet[1] = 0x01
        cookie.copyInto(packet, 4)
        ctx.nonce.copyInto(packet, 8, 0, 12)
        return packet
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        if (reply.size < 20) return UdpCodec.Read.NotMine
        if (!reply.copyOfRange(8, 20).contentEquals(ctx.nonce.copyOfRange(0, 12))) return UdpCodec.Read.NotMine
        val type = UdpCodec.u16(reply, 0)
        val length = UdpCodec.u16(reply, 2)
        var mapped: String? = null
        var error: String? = null
        var at = 20
        while (at + 4 <= minOf(reply.size, 20 + length)) {
            val attr = UdpCodec.u16(reply, at)
            val len = UdpCodec.u16(reply, at + 2)
            val v = at + 4
            if (v + len > reply.size) break
            when (attr) {
                0x0020 -> mapped = address(reply, v, xor = true) ?: mapped
                0x0001 -> if (mapped == null) mapped = address(reply, v, xor = false)
                0x0009 -> if (len >= 4) {
                    val code = UdpCodec.u8(reply, v + 2) * 100 + UdpCodec.u8(reply, v + 3)
                    val reason = String(reply, v + 4, len - 4, Charsets.UTF_8)
                    error = "$code $reason".trim()
                }
            }
            at = v + ((len + 3) and 3.inv())
        }
        return when (type) {
            0x0101 -> UdpCodec.Read.Good(
                if (mapped != null) "Sees this phone as $mapped" else "Binding answered",
                listOfNotNull(mapped?.let { UdpFact("Public address", it) }),
            )
            0x0111 -> UdpCodec.Read.Wrong("STUN error ${error ?: ""}".trim(), "The server answered the binding request with an error.")
            else -> UdpCodec.Read.NotMine
        }
    }

    private fun address(b: ByteArray, at: Int, xor: Boolean): String? {
        val family = UdpCodec.u8(b, at + 1)
        var port = UdpCodec.u16(b, at + 2)
        if (xor) port = port xor 0x2112
        val size = when (family) {
            1 -> 4
            2 -> 16
            else -> return null
        }
        if (at + 4 + size > b.size) return null
        val raw = b.copyOfRange(at + 4, at + 4 + size)
        if (xor) {
            val mask = cookie + b.copyOfRange(8, 20)
            for (i in raw.indices) raw[i] = (raw[i].toInt() xor mask[i].toInt()).toByte()
        }
        val ip = InetAddress.getByAddress(raw).hostAddress.orEmpty()
        return if (family == 2) "[$ip]:$port" else "$ip:$port"
    }
}

// ---- SNMP --------------------------------------------------------------------

/** SNMP v2c GetRequest, BER encoded by hand. Matched on the request id. */
internal object SnmpCodec : UdpCodec {

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val requestId = requestId(ctx.nonce)
        val varbind = tlv(0x30, tlv(0x30, oid(ctx.watch.snmpOid) + byteArrayOf(0x05, 0x00)))
        val pdu = tlv(0xa0, integer(requestId) + integer(0) + integer(0) + varbind)
        return tlv(0x30, integer(1) + tlv(0x04, ctx.community.toByteArray(Charsets.UTF_8)) + pdu)
    }

    private fun requestId(nonce: ByteArray): Long =
        ((UdpCodec.u8(nonce, 0) and 0x7f).toLong() shl 16) or (UdpCodec.u8(nonce, 1).toLong() shl 8) or UdpCodec.u8(nonce, 2).toLong()

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        val parsed = runCatching { parse(reply) }.getOrNull() ?: return UdpCodec.Read.NotMine
        if (parsed.requestId != requestId(ctx.nonce)) return UdpCodec.Read.NotMine
        if (parsed.errorStatus != 0) {
            return UdpCodec.Read.Wrong(
                "SNMP error ${errorName(parsed.errorStatus)}",
                "The agent answered and refused the request for ${ctx.watch.snmpOid}.",
            )
        }
        return when (parsed.valueTag) {
            0x80, 0x81, 0x82 -> UdpCodec.Read.Wrong(
                "Nothing at ${ctx.watch.snmpOid}",
                "The agent answered, and has no value at that OID.",
            )
            else -> {
                val facts = mutableListOf(UdpFact("OID", ctx.watch.snmpOid), UdpFact("Value", parsed.value))
                val headline = if (parsed.valueTag == 0x43) "Up for ${parsed.value}" else parsed.value
                UdpCodec.Read.Good(headline, facts)
            }
        }
    }

    private fun errorName(code: Int) = when (code) {
        1 -> "tooBig"
        2 -> "noSuchName"
        5 -> "genErr"
        6 -> "noAccess"
        16 -> "authorizationError"
        else -> code.toString()
    }

    class Parsed(val requestId: Long, val errorStatus: Int, val valueTag: Int, val value: String)

    fun parse(b: ByteArray): Parsed {
        val reader = Ber(b)
        reader.enter(0x30)
        reader.integer() // version
        reader.skip() // community
        reader.enter(0xa2)
        val id = reader.integer()
        val error = reader.integer().toInt()
        reader.integer()
        reader.enter(0x30)
        reader.enter(0x30)
        reader.skip() // oid
        val (tag, bytes) = reader.next()
        return Parsed(id, error, tag, render(tag, bytes))
    }

    private fun render(tag: Int, v: ByteArray): String = when (tag) {
        0x02, 0x41, 0x42, 0x46 -> unsigned(v, signed = tag == 0x02).toString()
        0x43 -> duration(unsigned(v, false) / 100)
        0x04 -> if (v.all { it in 0x20..0x7e || it == 0x0a.toByte() }) String(v, Charsets.UTF_8) else UdpPayload.hexOf(v)
        0x40 -> if (v.size == 4) InetAddress.getByAddress(v).hostAddress.orEmpty() else UdpPayload.hexOf(v)
        0x06 -> decodeOid(v)
        0x05 -> "null"
        else -> UdpPayload.hexOf(v)
    }

    fun duration(seconds: Long): String {
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }

    private fun unsigned(v: ByteArray, signed: Boolean): Long {
        var n = if (signed && v.isNotEmpty() && v[0] < 0) -1L else 0L
        v.forEach { n = (n shl 8) or (it.toLong() and 0xff) }
        return n
    }

    fun oid(text: String): ByteArray {
        val arcs = text.trim().removePrefix(".").split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        arcs.drop(2).forEach { arc ->
            val groups = mutableListOf<Int>()
            var n = arc
            do {
                groups.add(0, (n and 0x7f).toInt())
                n = n shr 7
            } while (n > 0)
            groups.forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun decodeOid(v: ByteArray): String {
        if (v.isEmpty()) return ""
        val first = v[0].toInt() and 0xff
        val arcs = mutableListOf((first / 40).toLong(), (first % 40).toLong())
        var n = 0L
        for (i in 1 until v.size) {
            val b = v[i].toInt() and 0xff
            n = (n shl 7) or (b and 0x7f).toLong()
            if (b and 0x80 == 0) {
                arcs += n
                n = 0
            }
        }
        return arcs.joinToString(".")
    }

    private fun integer(value: Long): ByteArray {
        var bytes = ByteBuffer.allocate(8).putLong(value).array().dropWhile { it == 0.toByte() }.toByteArray()
        if (bytes.isEmpty()) bytes = byteArrayOf(0)
        if (bytes[0] < 0) bytes = byteArrayOf(0) + bytes
        return tlv(0x02, bytes)
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val len = value.size
        val header = when {
            len < 0x80 -> byteArrayOf(tag.toByte(), len.toByte())
            len < 0x100 -> byteArrayOf(tag.toByte(), 0x81.toByte(), len.toByte())
            else -> byteArrayOf(tag.toByte(), 0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return header + value
    }

    private class Ber(val b: ByteArray) {
        var at = 0

        fun header(): Pair<Int, Int> {
            val tag = UdpCodec.u8(b, at++)
            var len = UdpCodec.u8(b, at++)
            if (len and 0x80 != 0) {
                val count = len and 0x7f
                require(count in 1..3)
                len = 0
                repeat(count) { len = (len shl 8) or UdpCodec.u8(b, at++) }
            }
            require(at + len <= b.size)
            return tag to len
        }

        fun enter(expected: Int) {
            val (tag, _) = header()
            require(tag == expected)
        }

        fun next(): Pair<Int, ByteArray> {
            val (tag, len) = header()
            val value = b.copyOfRange(at, at + len)
            at += len
            return tag to value
        }

        fun skip() {
            next()
        }

        fun integer(): Long {
            val (tag, v) = next()
            require(tag == 0x02)
            var n = if (v.isNotEmpty() && v[0] < 0) -1L else 0L
            v.forEach { n = (n shl 8) or (it.toLong() and 0xff) }
            return n
        }
    }
}

// ---- SIP ---------------------------------------------------------------------

/** RFC 3261 OPTIONS. Any status line answers it; matched on the Call-ID. */
internal object SipCodec : UdpCodec {

    private fun hex(b: ByteArray, from: Int, to: Int) = b.copyOfRange(from, to).joinToString("") { "%02x".format(it) }

    private fun callId(ctx: UdpCodec.Context) = hex(ctx.nonce, 0, 8) + "@nightbell.invalid"

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val host = if (ctx.host.contains(':')) "[${ctx.host}]" else ctx.host
        val text = "OPTIONS sip:$host SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP nightbell.invalid;branch=z9hG4bK${hex(ctx.nonce, 8, 14)};rport\r\n" +
            "Max-Forwards: 70\r\n" +
            "From: <sip:nightbell@nightbell.invalid>;tag=${hex(ctx.nonce, 14, 16)}\r\n" +
            "To: <sip:$host>\r\n" +
            "Call-ID: ${callId(ctx)}\r\n" +
            "CSeq: 1 OPTIONS\r\n" +
            "Accept: application/sdp\r\n" +
            "User-Agent: Nightbell\r\n" +
            "Content-Length: 0\r\n\r\n"
        return text.toByteArray(Charsets.US_ASCII)
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        val text = String(reply, Charsets.UTF_8)
        if (!text.startsWith("SIP/2.0 ")) return UdpCodec.Read.NotMine
        val lines = text.split("\r\n", "\n")
        val headers = lines.drop(1).takeWhile { it.isNotBlank() }
        fun header(vararg names: String) = headers.firstOrNull { line ->
            names.any { line.startsWith("$it:", ignoreCase = true) }
        }?.substringAfter(':')?.trim()
        val id = header("Call-ID", "i")
        if (id != null && id != callId(ctx)) return UdpCodec.Read.NotMine
        val status = lines.first().removePrefix("SIP/2.0 ").trim()
        val agent = header("Server", "User-Agent")
        return UdpCodec.Read.Good(
            "SIP $status",
            listOfNotNull(UdpFact("Status", status), agent?.let { UdpFact("Server", it) }),
        )
    }
}

// ---- QUIC --------------------------------------------------------------------

/**
 * RFC 9000 version negotiation. A long header packet with a reserved version
 * that no server speaks, padded to the 1200 bytes a server needs before it may
 * answer, gets back the list of versions the server does speak. Proves a QUIC
 * stack is listening without a single line of TLS.
 */
internal object QuicCodec : UdpCodec {
    private val reserved = byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a)

    override fun request(ctx: UdpCodec.Context): ByteArray {
        val packet = ByteArray(1200)
        packet[0] = 0xc0.toByte()
        reserved.copyInto(packet, 1)
        packet[5] = 8
        ctx.nonce.copyInto(packet, 6, 0, 8)
        packet[14] = 8
        ctx.nonce.copyInto(packet, 15, 8, 16)
        return packet
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        // Flags, version, both id lengths and our 8 byte id, before anything is read.
        if (reply.size < 15 || UdpCodec.u8(reply, 0) and 0x80 == 0) return UdpCodec.Read.NotMine
        if (UdpCodec.u32(reply, 1) != 0L) return UdpCodec.Read.NotMine
        val dcidLen = UdpCodec.u8(reply, 5)
        // The server's destination is our source id.
        if (dcidLen != 8 || !reply.copyOfRange(6, 14).contentEquals(ctx.nonce.copyOfRange(8, 16))) return UdpCodec.Read.NotMine
        val scidLen = UdpCodec.u8(reply, 6 + dcidLen)
        var at = 7 + dcidLen + scidLen
        val versions = mutableListOf<String>()
        while (at + 4 <= reply.size) {
            versions += versionName(UdpCodec.u32(reply, at))
            at += 4
        }
        val named = versions.filterNot { it.startsWith("grease") }.distinct()
        return UdpCodec.Read.Good(
            if (named.isEmpty()) "QUIC answered" else "Offers ${named.joinToString(", ")}",
            listOf(UdpFact("Versions", named.joinToString(", ").ifEmpty { "none listed" })),
        )
    }

    fun versionName(v: Long): String = when {
        v == 1L -> "QUIC v1"
        v == 0x6b3343cfL -> "QUIC v2"
        v and 0x0f0f0f0fL == 0x0a0a0a0aL -> "grease"
        v ushr 8 == 0xff0000L -> "draft-${v and 0xff}"
        else -> "0x%08x".format(v)
    }
}

// ---- Source query ------------------------------------------------------------

/** Valve's A2S_INFO, with the challenge round trip servers have required since 2020. */
internal object SourceQueryCodec : UdpCodec {
    private val head = byteArrayOf(-1, -1, -1, -1)
    private val query = head + byteArrayOf(0x54) + "Source Engine Query".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    override fun request(ctx: UdpCodec.Context): ByteArray = query

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        if (reply.size < 5) return UdpCodec.Read.NotMine
        if (reply[0] == 0xfe.toByte() && reply.copyOfRange(1, 4).all { it == (-1).toByte() }) {
            return UdpCodec.Read.Good("Answered in a split reply", listOf(UdpFact("Reply", "split, ${reply.size} bytes")))
        }
        if (!reply.copyOfRange(0, 4).contentEquals(head)) return UdpCodec.Read.NotMine
        return when (reply[4].toInt()) {
            0x41 -> if (reply.size >= 9) UdpCodec.Read.Challenge(query + reply.copyOfRange(5, 9)) else UdpCodec.Read.NotMine
            0x49 -> info(reply)
            0x6d -> UdpCodec.Read.Good("GoldSrc server answered", listOf(UdpFact("Engine", "GoldSrc")))
            else -> UdpCodec.Read.NotMine
        }
    }

    private fun info(b: ByteArray): UdpCodec.Read {
        var at = 6
        fun string(): String {
            if (at >= b.size) return ""
            val end = (at until b.size).firstOrNull { b[it] == 0.toByte() } ?: b.size
            val s = String(b, at, end - at, Charsets.UTF_8)
            at = end + 1
            return s
        }
        val name = string()
        val map = string()
        string() // folder
        val game = string()
        at += 2 // app id
        val players = if (at < b.size) UdpCodec.u8(b, at) else -1
        val max = if (at + 1 < b.size) UdpCodec.u8(b, at + 1) else -1
        val facts = buildList {
            add(UdpFact("Name", name))
            if (map.isNotEmpty()) add(UdpFact("Map", map))
            if (game.isNotEmpty()) add(UdpFact("Game", game))
            if (players >= 0 && max >= 0) add(UdpFact("Players", "$players of $max"))
        }
        val headline = if (players >= 0) "$name, $players of $max players" else name
        return UdpCodec.Read.Good(headline, facts)
    }
}

// ---- Bedrock -----------------------------------------------------------------

/** RakNet unconnected ping, which every Bedrock server answers with its MOTD. */
internal object BedrockCodec : UdpCodec {
    val magic = byteArrayOf(
        0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
        0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78,
    )

    override fun request(ctx: UdpCodec.Context): ByteArray =
        byteArrayOf(0x01) + ctx.nonce.copyOfRange(0, 8) + magic + ctx.nonce.copyOfRange(8, 16)

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        if (reply.size < 35 || reply[0] != 0x1c.toByte()) return UdpCodec.Read.NotMine
        if (!reply.copyOfRange(1, 9).contentEquals(ctx.nonce.copyOfRange(0, 8))) return UdpCodec.Read.NotMine
        val length = UdpCodec.u16(reply, 33)
        val text = String(reply, 35, minOf(length, reply.size - 35), Charsets.UTF_8)
        val parts = text.split(';')
        val motd = parts.getOrNull(1).orEmpty()
        val version = parts.getOrNull(3).orEmpty()
        val players = parts.getOrNull(4)
        val max = parts.getOrNull(5)
        val facts = buildList {
            if (motd.isNotEmpty()) add(UdpFact("MOTD", motd))
            if (version.isNotEmpty()) add(UdpFact("Version", version))
            if (players != null && max != null) add(UdpFact("Players", "$players of $max"))
        }
        return UdpCodec.Read.Good(
            listOfNotNull(motd.ifEmpty { null }, if (players != null && max != null) "$players of $max players" else null)
                .joinToString(", ").ifEmpty { "Bedrock server answered" },
            facts,
        )
    }
}

// ---- Custom and silent -------------------------------------------------------

internal object CustomCodec : UdpCodec {
    override fun request(ctx: UdpCodec.Context): ByteArray =
        (UdpPayload.parse(ctx.watch.payload, ctx.watch.payloadFormat) as? UdpPayload.Parsed.Bytes)?.value ?: ByteArray(0)

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read {
        val facts = replyFacts(reply)
        val watch = ctx.watch
        return when (UdpPayload.matches(reply, watch.expect, watch.expectValue)) {
            true -> UdpCodec.Read.Good("${reply.size} byte reply", facts)
            false -> UdpCodec.Read.Wrong(
                "Reply did not match",
                "Expected it to ${watch.expect.label.lowercase()} \"${watch.expectValue}\". " +
                    "Got: ${UdpPayload.textOf(reply, 48)}",
                facts,
            )
            null -> UdpCodec.Read.Wrong("The expected reply can't be read", "Fix the reply pattern in the monitor's settings.", facts)
        }
    }

    fun replyFacts(reply: ByteArray) = listOf(
        UdpFact("Reply", "${reply.size} bytes"),
        UdpFact("Text", UdpPayload.textOf(reply)),
        UdpFact("Hex", UdpPayload.hexOf(reply)),
    )
}

/**
 * Silent mode sends a datagram nothing is expected to answer. A reply is still
 * read, since it is better news than silent mode needs, and said so.
 */
internal object SilentCodec : UdpCodec {
    override fun request(ctx: UdpCodec.Context): ByteArray {
        val custom = (UdpPayload.parse(ctx.watch.payload, ctx.watch.payloadFormat) as? UdpPayload.Parsed.Bytes)?.value
        return if (custom == null || custom.isEmpty()) byteArrayOf(0) else custom
    }

    override fun read(reply: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read =
        UdpCodec.Read.Good("It answered, ${reply.size} bytes", CustomCodec.replyFacts(reply))
}

/** For the check's message: the round trip the way ping prints it. */
internal fun rttText(us: Long): String = PingReading.formatUs(us)

/** Whole milliseconds for the chart, rounded up so an answer is never a zero. */
internal fun rttMs(us: Long): Long = ((us + 999) / 1000).coerceAtLeast(1)
