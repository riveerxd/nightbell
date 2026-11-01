package me.river.nightbell.data.check

import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.IpFamily
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.NetworkTarget
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.PingReading
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Checks for the two kinds that reach a host rather than fetch a URL:
 * [MonitorKind.PING] and [MonitorKind.TCP_PORT].
 *
 * No HTTP client, no TLS, no proxy. ICMP cannot be carried by SOCKS at all, and a
 * port check through a proxy measures the proxy, so both go straight out of the
 * device; setup does not offer routing for them and [Monitor.useProxy] is not read
 * here.
 *
 * The parts that touch the operating system sit behind [Resolver], [Pinger] and
 * [Connector], so the classification, which is where the bugs would be, runs on
 * the JVM with fakes.
 */
class NetworkChecker(
    private val resolver: Resolver = Resolver.System,
    private val pinger: Pinger = Pinger.System,
    private val connector: Connector = Connector.System,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Name to address. Throws [UnknownHostException] when there is none of the family asked for. */
    fun interface Resolver {
        fun resolve(host: String, family: IpFamily): InetAddress

        companion object {
            val System = Resolver { host, family ->
                val all = InetAddress.getAllByName(host).toList()
                val wanted = when (family) {
                    IpFamily.ANY -> all
                    IpFamily.V4 -> all.filterIsInstance<Inet4Address>()
                    IpFamily.V6 -> all.filterIsInstance<Inet6Address>()
                }
                wanted.firstOrNull() ?: throw UnknownHostException(
                    "$host has no ${family.label} address",
                )
            }
        }
    }

    /** Sends [count] echo requests and reports each round trip, null for one that never came back. */
    fun interface Pinger {
        fun ping(address: InetAddress, count: Int, timeoutMs: Long): PingOutcome

        companion object {
            val System: Pinger = IcmpPinger()
        }
    }

    sealed interface PingOutcome {
        /** One entry per request sent, in order. */
        data class Replies(val rttsUs: List<Long?>) : PingOutcome

        /** Nothing could be sent: no route, no socket, no ping binary. */
        data class Failed(val kind: FailureKind, val message: String, val detail: String = "") : PingOutcome
    }

    /** Connects and closes. Returns the time the handshake took, in nanoseconds. */
    fun interface Connector {
        fun connect(address: InetSocketAddress, timeoutMs: Int): Long

        companion object {
            val System = Connector { address, timeoutMs ->
                Socket().use { socket ->
                    val started = java.lang.System.nanoTime()
                    socket.connect(address, timeoutMs)
                    java.lang.System.nanoTime() - started
                }
            }
        }
    }

    suspend fun check(monitor: Monitor): CheckResult = runInterruptible(Dispatchers.IO) {
        when (monitor.kind) {
            MonitorKind.PING -> ping(monitor)
            MonitorKind.TCP_PORT -> tcp(monitor)
            else -> failed(FailureKind.BAD_CONFIG, "Not a ping or port monitor")
        }
    }

    private fun ping(monitor: Monitor): CheckResult {
        val watch = monitor.network
        val address = resolve(watch) ?: return dnsFailure(watch)
        val count = watch.packets.coerceIn(1, MAX_PACKETS)
        val timeoutMs = monitor.timeoutSeconds.coerceAtLeast(1) * 1000L
        val outcome = try {
            pinger.ping(address, count, timeoutMs)
        } catch (error: IOException) {
            PingOutcome.Failed(FailureKind.CONNECT, error.message ?: "Could not send the echo request")
        }
        return when (outcome) {
            is PingOutcome.Failed -> failed(outcome.kind, outcome.message, outcome.detail)
            is PingOutcome.Replies -> {
                val reading = PingReading.of(outcome.rttsUs)
                val where = describe(watch, address)
                if (!reading.answered) {
                    CheckResult(
                        ok = false,
                        latencyMs = 0,
                        failureKind = FailureKind.NO_REPLY,
                        message = "No reply from $where",
                        detail = "${reading.sent} echo ${if (reading.sent == 1) "request" else "requests"} " +
                            "sent, none answered within ${monitor.timeoutSeconds}s.",
                        ping = reading,
                        at = nowMs(),
                    )
                } else {
                    CheckResult(
                        ok = true,
                        // Rounded up rather than down, so a reply that came back is
                        // never a zero on the chart: zero there reads as "no data".
                        latencyMs = ((reading.avgUs + 999) / 1000).coerceAtLeast(1),
                        message = pingLine(reading),
                        // Some answered and some did not. The host is there, and
                        // the path to it is losing packets, which is worth amber
                        // and not worth a page: it is the same treatment a reply
                        // over the latency budget gets.
                        degraded = reading.partial,
                        ping = reading,
                        at = nowMs(),
                    )
                }
            }
        }
    }

    private fun tcp(monitor: Monitor): CheckResult {
        val watch = monitor.network
        if (watch.port !in 1..65535) {
            return failed(FailureKind.BAD_CONFIG, "No port set", "Pick a port between 1 and 65535.")
        }
        val address = resolve(watch) ?: return dnsFailure(watch)
        val where = describe(watch, address, watch.port)
        val timeoutMs = monitor.timeoutSeconds.coerceAtLeast(1) * 1000
        return try {
            val nanos = connector.connect(InetSocketAddress(address, watch.port), timeoutMs)
            val ms = (nanos / 1_000_000L).coerceAtLeast(1)
            CheckResult(
                ok = true,
                latencyMs = ms,
                message = "Port ${watch.port} open, connected in $ms ms",
                at = nowMs(),
            )
        } catch (error: SocketTimeoutException) {
            failed(
                FailureKind.TIMEOUT,
                "No answer from $where in ${monitor.timeoutSeconds}s",
                "Nothing accepted or refused the connection. A firewall that drops rather than " +
                    "rejects looks exactly like this.",
            )
        } catch (error: ConnectException) {
            val refused = error.message.orEmpty().contains("refused", ignoreCase = true)
            failed(
                FailureKind.CONNECT,
                if (refused) "Connection refused by $where" else "Could not connect to $where",
                if (refused) {
                    "The host answered and nothing is listening on port ${watch.port}."
                } else {
                    error.message.orEmpty()
                },
            )
        } catch (error: NoRouteToHostException) {
            failed(FailureKind.CONNECT, "No route to $where", error.message.orEmpty())
        } catch (error: IOException) {
            failed(FailureKind.CONNECT, "Could not connect to $where", error.message.orEmpty())
        }
    }

    private fun resolve(watch: NetworkWatch): InetAddress? = try {
        val host = watch.cleanHost
        // A literal is parsed, never looked up: the family it is written in is the
        // family it is, whatever the chips say.
        val family = NetworkTarget.literalFamily(host) ?: watch.family
        resolver.resolve(host, family)
    } catch (_: UnknownHostException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun dnsFailure(watch: NetworkWatch): CheckResult {
        val host = watch.cleanHost
        if (host.isEmpty()) return failed(FailureKind.BAD_CONFIG, "No host set", "Enter an IP address or a host name.")
        val only = if (watch.family != IpFamily.ANY && NetworkTarget.literalFamily(host) == null) {
            " has no ${watch.family.label} address"
        } else {
            ""
        }
        return failed(
            FailureKind.DNS,
            if (only.isEmpty()) "Can't resolve $host" else "$host$only",
            if (only.isEmpty()) "" else "The name resolves, but not to the family this monitor asks for.",
        )
    }

    private fun failed(kind: FailureKind, message: String, detail: String = ""): CheckResult =
        CheckResult(ok = false, latencyMs = 0, failureKind = kind, message = message, detail = detail, at = nowMs())

    companion object {
        const val MAX_PACKETS = me.river.nightbell.domain.Validation.NETWORK_MAX_PACKETS

        /**
         * The target as typed, port included, and for a name the address it went
         * to: "db.lan:5432 (192.0.2.10)". A literal is never followed by itself,
         * which for IPv6 would be the same address again in its long form.
         */
        internal fun describe(watch: NetworkWatch, address: InetAddress, port: Int? = null): String {
            val host = watch.cleanHost
            val target = NetworkTarget.display(host, port)
            if (NetworkTarget.literalFamily(host) != null) return target
            val ip = address.hostAddress.orEmpty().substringBefore('%')
            return if (ip.isEmpty()) target else "$target ($ip)"
        }

        internal fun pingLine(reading: PingReading): String = buildString {
            append(reading.repliesLine)
            if (reading.lost > 0) append(", ${reading.lossPercent}% lost")
            append(", ").append(PingReading.formatUs(reading.avgUs)).append(" average")
        }
    }
}
