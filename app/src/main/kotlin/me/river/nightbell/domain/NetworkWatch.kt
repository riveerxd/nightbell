package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * Which address family a ping or port check uses when the host is a name.
 *
 * A literal address decides for itself and this is ignored. It exists for the
 * dual-stack host, where "is it up over IPv6" is a different question from "is
 * it up", and resolving to whichever the phone prefers would answer the second
 * while the user was asking the first.
 */
@Serializable
enum class IpFamily(val label: String) {
    @SerialName("any")
    ANY("Either"),

    @SerialName("v4")
    V4("IPv4"),

    @SerialName("v6")
    V6("IPv6"),
}

/**
 * What a [MonitorKind.PING] or [MonitorKind.TCP_PORT] monitor reaches.
 *
 * Its own field rather than a `tcp://` string in [Monitor.url], because every
 * reader of that field assumes HTTP: the favicon fetch, the certificate track,
 * the proxy routing and the URL validator would each have had to learn to leave
 * it alone. Defaulted, so a store written before this existed still decodes.
 */
@Serializable
data class NetworkWatch(
    /** An IPv4 or IPv6 literal, or a host name. Stored without brackets. */
    val host: String = "",
    /** TCP only. 0 until somebody sets it. */
    val port: Int = 0,
    /**
     * Echo requests per ping check.
     *
     * Three by default: one lost packet in three is something a phone on mobile
     * data sees all day, and with one packet that same loss is an outage.
     */
    val packets: Int = 3,
    val family: IpFamily = IpFamily.ANY,
) {
    /**
     * The host, whatever was typed around it: a scheme, a path, a port, the
     * brackets an IPv6 literal carries next to one. Setup tidies the field when
     * it is left, and this reads it the same way in case it never was.
     */
    val cleanHost: String get() = NetworkTarget.parse(host).host

    /** A literal address decides the family itself, so the chips only matter for a name. */
    val isName: Boolean get() = cleanHost.isNotEmpty() && NetworkTarget.literalFamily(cleanHost) == null
}

/**
 * One ping check's echo replies.
 *
 * Microseconds, because a host on the same network answers in a third of a
 * millisecond and [CheckResult.latencyMs] would round every one of those to 0.
 */
@Serializable
data class PingReading(
    val sent: Int,
    val received: Int,
    val minUs: Long = 0L,
    val avgUs: Long = 0L,
    val maxUs: Long = 0L,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)

    /** Whole per cent, rounded the way ping(8) prints it. */
    val lossPercent: Int get() = if (sent <= 0) 0 else (lost * 100.0 / sent).roundToLong().toInt()

    val answered: Boolean get() = received > 0

    /** Some replies came back and some did not. */
    val partial: Boolean get() = received in 1 until sent

    /** "3 of 3 replies", the sentence the card and the history row lead with. */
    val repliesLine: String get() = "$received of $sent ${if (sent == 1) "reply" else "replies"}"

    companion object {
        /** Folds a list of round trips (null for a lost packet) into a reading. */
        fun of(rttsUs: List<Long?>): PingReading {
            val got = rttsUs.filterNotNull()
            return PingReading(
                sent = rttsUs.size,
                received = got.size,
                minUs = got.minOrNull() ?: 0L,
                avgUs = if (got.isEmpty()) 0L else got.sum() / got.size,
                maxUs = got.maxOrNull() ?: 0L,
            )
        }

        /**
         * A round trip for people: two decimals under ten milliseconds, one under
         * a hundred, none above. "0.28 ms" and "214 ms" are both what ping says.
         */
        fun formatUs(us: Long): String {
            val ms = us / 1000.0
            return when {
                ms < 10 -> String.format(java.util.Locale.ROOT, "%.2f ms", ms)
                ms < 100 -> String.format(java.util.Locale.ROOT, "%.1f ms", ms)
                else -> "${ms.roundToLong()} ms"
            }
        }
    }
}

/**
 * Reading a host out of whatever got typed or pasted into the host field.
 *
 * People paste what they have: a URL from the browser, `host:port` from a
 * config file, `[2001:db8::1]:443` from a log. Refusing those as "not a host"
 * would be correct and useless, so the host field takes them and keeps the part
 * that is a host. Pure, so the setup screen and the checker read it the same way.
 */
object NetworkTarget {

    data class Parsed(val host: String, val port: Int?)

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val label = Regex("""^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$""")

    /**
     * Splits typed input into a host and, when one was written, a port.
     *
     * A scheme and anything after the authority are dropped. A bare IPv6 literal
     * is never split on its last colon: `2001:db8::1` is an address, not host
     * `2001:db8:` on port 1, which is why a port next to IPv6 needs brackets
     * everywhere else in the world too.
     */
    fun parse(raw: String): Parsed {
        var text = raw.trim()
        val schemeAt = text.indexOf("://")
        if (schemeAt in 1..10) text = text.substring(schemeAt + 3)
        text = text.substringBefore('/').substringBefore('?').substringBefore('#')
        text = text.substringAfterLast('@')
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close > 0) {
                val host = text.substring(1, close)
                val port = text.substring(close + 1).removePrefix(":").toIntOrNull()
                return Parsed(host, port)
            }
            return Parsed(text.removePrefix("["), null)
        }
        val colons = text.count { it == ':' }
        if (colons == 1) {
            val port = text.substringAfter(':').toIntOrNull()
            if (port != null) return Parsed(text.substringBefore(':'), port)
        }
        return Parsed(text, null)
    }

    /** [IpFamily.V4] or [IpFamily.V6] for a literal address, null for a name or junk. */
    fun literalFamily(host: String): IpFamily? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (isIpv4(h)) return IpFamily.V4
        if (isIpv6(h)) return IpFamily.V6
        return null
    }

    fun isIpv4(host: String): Boolean {
        val m = ipv4.matchEntire(host) ?: return false
        return m.groupValues.drop(1).all { part ->
            part.toInt() <= 255 && (part.length == 1 || !part.startsWith("0"))
        }
    }

    /**
     * RFC 4291 text forms, including `::` and an embedded IPv4 tail. A zone
     * (`fe80::1%wlan0`) is allowed, because link-local is the only address some
     * homelab boxes have, and Java resolves the zone itself.
     */
    fun isIpv6(host: String): Boolean {
        val h = host.substringBefore('%')
        if (h.isEmpty() || !h.contains(':')) return false
        if (h.count { it == ':' } > 7) return false
        if (h.indexOf("::") != h.lastIndexOf("::")) return false
        if (h.contains(":::")) return false
        var groups = h.split(':')
        var budget = 8
        val tail = groups.last()
        if (tail.contains('.')) {
            if (!isIpv4(tail)) return false
            groups = groups.dropLast(1)
            budget = 6
        }
        val compressed = h.contains("::")
        val real = groups.filter { it.isNotEmpty() }
        if (real.any { it.length > 4 || !it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }) return false
        // A lone leading or trailing colon is malformed; only the double form may
        // leave an empty group at either end.
        if (!compressed && groups.any { it.isEmpty() }) return false
        if (h.startsWith(":") && !h.startsWith("::")) return false
        if (h.endsWith(":") && !h.endsWith("::")) return false
        return if (compressed) real.size < budget else real.size == budget
    }

    fun isHostName(host: String): Boolean {
        val h = host.trim().removeSuffix(".")
        if (h.isEmpty() || h.length > 253) return false
        val labels = h.split('.')
        // All digits and dots is an IPv4 address that failed its own check, like
        // 300.1.1.1. Reading it as a name would send it to DNS and report
        // "host not found" for what is really a typo in a number.
        if (labels.all { it.all(Char::isDigit) }) return false
        return labels.all { label.matches(it) }
    }

    /** `host`, `host:port`, or `[v6]:port`: how a target is written for a person. */
    fun display(host: String, port: Int?): String {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (port == null || port <= 0) return h
        return if (h.contains(':')) "[$h]:$port" else "$h:$port"
    }

    /**
     * The host a monitor talks to, for telling which monitors share one.
     *
     * Lowercased, brackets off, trailing dot off. Names are not resolved: two
     * names for one address are two names as far as anybody reading the
     * dashboard is concerned, and a DNS lookup per row to find out otherwise
     * would be a network request to draw a screen.
     */
    fun hostOf(monitor: Monitor): String = when (monitor.kind) {
        MonitorKind.PING, MonitorKind.TCP_PORT -> monitor.network.cleanHost
        MonitorKind.GITHUB_REPO, MonitorKind.UNSUPPORTED -> ""
        else -> parse(monitor.url).host
    }.trim().removeSuffix(".").lowercase()
}
