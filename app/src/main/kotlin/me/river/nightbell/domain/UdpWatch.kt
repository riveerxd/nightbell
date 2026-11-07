package me.river.nightbell.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a [MonitorKind.UDP_PORT] monitor sends, and what it takes for an answer.
 *
 * UDP has no handshake, so "is the port open" has no honest answer on its own:
 * a live service ignoring a stray datagram and a firewall dropping it both come
 * back as silence. Up is only ever proved by a reply, which is why most probes
 * speak the service's own protocol. Issue 21 asked for this after a TCP check
 * called a UDP-only service down.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = UdpWatchSerializer::class)
data class UdpWatch(
    val probe: UdpProbe = UdpProbe.DNS,
    /**
     * Tries per check, one after another. UDP loses packets on a healthy
     * network, and one lost datagram must not be an outage.
     */
    val attempts: Int = 3,
    /** Custom and silent only. Text with escapes, or hex, per [payloadFormat]. */
    val payload: String = "",
    val payloadFormat: PayloadFormat = PayloadFormat.TEXT,
    /** Custom only. */
    val expect: ReplyMatch = ReplyMatch.ANY,
    val expectValue: String = "",
    val dnsName: String = "example.com",
    val dnsType: DnsType = DnsType.A,
    /** An address or name the answer has to contain. Empty when any answer will do. */
    val dnsExpected: String = "",
    /** 0 shows the offset and never judges it: a phone's own clock can be the wrong one. */
    val ntpMaxOffsetMs: Int = 0,
    /** The community is a credential and lives in the vault, never here. */
    val snmpOid: String = SYS_UPTIME_OID,
    /**
     * A probe a newer build wrote. The monitor runs as [UdpProbe.CUSTOM] here and
     * the name goes back out unchanged, so upgrading again finds it as it was.
     */
    val unknownProbe: String = "",
) {
    companion object {
        const val SYS_UPTIME_OID = "1.3.6.1.2.1.1.3.0"
        const val MAX_ATTEMPTS = 5

        /** Tries times timeout. A check that can wait longer starves the rest of the run. */
        const val MAX_WAIT_SECONDS = 60

        /** Numbers and dots, at least two arcs, the first two in BER's range. */
        fun isOid(text: String): Boolean {
            val arcs = text.trim().removePrefix(".").split('.')
            return arcs.size >= 2 && arcs.all { a -> a.isNotEmpty() && a.all(Char::isDigit) && a.length <= 10 } &&
                arcs[0].toLong() <= 2 && arcs[1].toLong() < 40
        }
    }
}

@Serializable
enum class UdpProbe(val label: String, val defaultPort: Int, val blurb: String) {
    @SerialName("dns")
    DNS("DNS", 53, "Asks for a name and checks the server answers it."),

    @SerialName("ntp")
    NTP("NTP", 123, "Asks for the time. Shows the stratum and how far off the clock is."),

    @SerialName("stun")
    STUN("STUN", 3478, "A binding request. Shows the public address the server sees."),

    @SerialName("snmp")
    SNMP("SNMP", 161, "Reads one value with a v2c community, uptime by default."),

    @SerialName("sip")
    SIP("SIP", 5060, "An OPTIONS request. Any SIP answer counts, 401 included."),

    @SerialName("quic")
    QUIC("HTTP/3", 443, "A QUIC packet the server must answer with the versions it speaks."),

    @SerialName("source")
    SOURCE_QUERY("Source game", 27015, "A2S_INFO. Shows the server name, map and players."),

    @SerialName("bedrock")
    BEDROCK("Bedrock", 19132, "A Minecraft Bedrock ping. Shows the MOTD and players."),

    @SerialName("custom")
    CUSTOM("Custom", 0, "Your bytes, and what the reply has to look like."),

    @SerialName("silent")
    SILENT("Silent", 0, "For services that never answer, like WireGuard. Listens for a refusal and pings the host."),
    ;

    companion object {
        /** The probe a port is usually spoken to with, for the port chips and a kind switch. */
        fun forPort(port: Int): UdpProbe? = when (port) {
            51820, 1194, 514, 8125 -> SILENT
            else -> entries.firstOrNull { it.defaultPort == port }
        }
    }
}

@Serializable
enum class PayloadFormat(val label: String) {
    @SerialName("text")
    TEXT("Text"),

    @SerialName("hex")
    HEX("Hex"),
}

@Serializable
enum class ReplyMatch(val label: String) {
    @SerialName("any")
    ANY("Any reply"),

    @SerialName("starts_with")
    STARTS_WITH("Starts with"),

    @SerialName("contains")
    CONTAINS("Contains"),

    @SerialName("regex")
    REGEX("Regex"),

    @SerialName("hex_prefix")
    HEX_PREFIX("Hex prefix"),
}

@Serializable
enum class DnsType(val code: Int) {
    A(1),
    AAAA(28),
    MX(15),
    TXT(16),
    NS(2),
}

/**
 * Moves an unknown probe name aside instead of failing the decode.
 *
 * The same reasoning as [MonitorSerializer]: one value this build does not
 * know must cost that one setting, not the whole store.
 */
object UdpWatchSerializer : JsonTransformingSerializer<UdpWatch>(UdpWatch.generatedSerializer()) {
    private val known: Set<String> = UdpProbe.serializer().descriptor.let { probes ->
        (0 until probes.elementsCount).map(probes::getElementName).toSet()
    }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val name = (element["probe"] as? JsonPrimitive)?.contentOrNull ?: return element
        if (name in known) return element
        return JsonObject(element + mapOf("probe" to JsonPrimitive("custom"), "unknownProbe" to JsonPrimitive(name)))
    }

    override fun transformSerialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val original = element["unknownProbe"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rest = JsonObject(element - "unknownProbe")
        if (original.isBlank()) return rest
        return JsonObject(rest + ("probe" to JsonPrimitive(original)))
    }
}

/**
 * What one UDP check got back.
 *
 * Microseconds for the same reason as [PingReading]: a DNS server on the LAN
 * answers in a third of a millisecond.
 */
@Serializable
data class UdpReading(
    val sent: Int,
    /** 0 when nothing answered, else which try did. */
    val answeredOn: Int = 0,
    val rttUs: Long = 0L,
    val refused: Boolean = false,
    /** Silent mode only: whether the host answered ICMP echo. Null on every other probe. */
    val echo: Boolean? = null,
    val bytes: Int = 0,
)

/** One line of a probe's readout, "Stratum" and "2". Kept on the runtime, not per sample. */
@Serializable
data class UdpFact(val label: String, val value: String)

/**
 * Reading the payload field. Shared by setup, which shows the bytes as they are
 * typed, and the checker, which sends them, so the two can never disagree.
 */
object UdpPayload {

    sealed interface Parsed {
        data class Bytes(val value: ByteArray) : Parsed

        data class Invalid(val message: String) : Parsed
    }

    fun parse(text: String, format: PayloadFormat): Parsed = when (format) {
        PayloadFormat.HEX -> hex(text)
        PayloadFormat.TEXT -> escaped(text)
    }

    fun hex(text: String): Parsed {
        val digits = text.filterNot { it.isWhitespace() || it == ':' }.removePrefix("0x")
        if (digits.isEmpty()) return Parsed.Bytes(ByteArray(0))
        if (digits.any { Character.digit(it, 16) < 0 }) return Parsed.Invalid("Hex takes 0 to 9 and a to f only")
        if (digits.length % 2 != 0) return Parsed.Invalid("Hex needs two digits per byte")
        return Parsed.Bytes(ByteArray(digits.length / 2) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
    }

    /** `\n`, `\r`, `\t`, `\0`, `\\` and `\xNN`; everything else is UTF-8. */
    fun escaped(text: String): Parsed {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\') {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            if (i + 1 >= text.length) return Parsed.Invalid("A backslash at the end escapes nothing")
            when (val next = text[i + 1]) {
                'n' -> out.write(0x0a)
                'r' -> out.write(0x0d)
                't' -> out.write(0x09)
                '0' -> out.write(0x00)
                '\\' -> out.write('\\'.code)
                'x' -> {
                    val pair = text.substring(i + 2, minOf(i + 4, text.length))
                    val value = pair.takeIf { it.length == 2 }?.toIntOrNull(16)
                        ?: return Parsed.Invalid("\\x needs two hex digits after it")
                    out.write(value)
                    i += 2
                }
                else -> return Parsed.Invalid("\\$next is not an escape. Use \\\\ for a backslash")
            }
            i += 2
        }
        return Parsed.Bytes(out.toByteArray())
    }

    fun hexOf(bytes: ByteArray, limit: Int = 64): String =
        bytes.take(limit).joinToString(" ") { "%02x".format(it.toInt() and 0xff) } +
            if (bytes.size > limit) " …" else ""

    /** Printable ASCII as itself, everything else as a dot, the way hexdump shows it. */
    fun textOf(bytes: ByteArray, limit: Int = 64): String =
        bytes.take(limit).joinToString("") { b ->
            val v = b.toInt() and 0xff
            if (v in 0x20..0x7e) v.toChar().toString() else "."
        }

    /** Whether [reply] satisfies a custom probe's expectation. Null when the expectation itself is broken. */
    fun matches(reply: ByteArray, match: ReplyMatch, value: String): Boolean? {
        val text = String(reply, Charsets.ISO_8859_1)
        return when (match) {
            ReplyMatch.ANY -> true
            ReplyMatch.STARTS_WITH -> (escaped(value) as? Parsed.Bytes)?.value?.let { reply.startsWith(it) }
            ReplyMatch.CONTAINS -> (escaped(value) as? Parsed.Bytes)?.value?.let { reply.contains(it) }
            ReplyMatch.HEX_PREFIX -> (hex(value) as? Parsed.Bytes)?.value?.let { reply.startsWith(it) }
            ReplyMatch.REGEX -> runCatching { Regex(value).containsMatchIn(text) }.getOrNull()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.contains(part: ByteArray): Boolean {
        if (part.isEmpty()) return true
        for (start in 0..size - part.size) {
            if (part.indices.all { this[start + it] == part[it] }) return true
        }
        return false
    }
}
