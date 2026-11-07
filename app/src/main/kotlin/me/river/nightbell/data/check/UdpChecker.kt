package me.river.nightbell.data.check

import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.NetworkWatch
import me.river.nightbell.domain.UdpFact
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.UdpReading
import me.river.nightbell.domain.UdpWatch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom

/**
 * The [me.river.nightbell.domain.MonitorKind.UDP_PORT] check.
 *
 * Three things can come back from a datagram, and only a reply proves a
 * service is alive. ICMP port unreachable proves the host is up and the port
 * is not, which the kernel only reports to a *connected* socket, so the socket
 * is always connected first. Silence proves nothing, so it is down everywhere
 * except silent mode, which asks the host for an ICMP echo to tell a quiet
 * service on a live host from a host that is gone.
 *
 * Sockets are behind [Datagrams] and echo behind [NetworkChecker.Pinger], so
 * every verdict below runs on the JVM with fakes.
 */
class UdpChecker(
    private val datagrams: Datagrams = Datagrams.System,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    /** Between tries. A sleep in production, recorded in tests. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** What to do with a datagram that came back. */
    sealed interface Step {
        /** The reply: stop here. */
        data object Keep : Step

        /** Not ours, a late answer to an earlier try: keep listening. */
        data object Skip : Step

        /**
         * Send this from the same socket and listen again, with a fresh
         * timeout. A server that challenges keys the challenge on the address
         * and port it issued it to, so the answer cannot come from a new one.
         */
        class Send(val bytes: ByteArray) : Step
    }

    fun interface Datagrams {
        /** One request, then read until [answer] keeps a reply, a refusal, or [timeoutMs] runs out. */
        fun exchange(address: InetSocketAddress, request: ByteArray, timeoutMs: Int, answer: (ByteArray) -> Step): Exchange

        companion object {
            /**
             * A non-blocking channel and a selector rather than a plain
             * `DatagramSocket`. `DatagramSocket.receive` ignores a thread
             * interrupt, so a cancelled check sat out every try's timeout, up
             * to a minute, which `UdpCheckerInstrumentedTest` caught. A
             * selector wakes on the interrupt `runInterruptible` sends.
             */
            val System = Datagrams { address, request, timeoutMs, answer ->
                try {
                    DatagramChannel.open().use { channel ->
                        channel.configureBlocking(false)
                        channel.connect(address)
                        Selector.open().use { selector ->
                            channel.register(selector, SelectionKey.OP_READ)
                            var started = java.lang.System.nanoTime()
                            var deadline = started + timeoutMs * 1_000_000L
                            channel.write(ByteBuffer.wrap(request))
                            val buffer = ByteBuffer.allocate(MAX_DATAGRAM)
                            var outcome: Exchange = Exchange.Silence
                            while (true) {
                                val left = (deadline - java.lang.System.nanoTime()) / 1_000_000L
                                if (left <= 0) break
                                selector.select(left)
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                selector.selectedKeys().clear()
                                buffer.clear()
                                val read = channel.read(buffer)
                                if (read <= 0) continue
                                buffer.flip()
                                val bytes = ByteArray(read).also { buffer.get(it) }
                                when (val step = answer(bytes)) {
                                    Step.Keep -> {
                                        outcome = Exchange.Reply(bytes, java.lang.System.nanoTime() - started)
                                        break
                                    }
                                    Step.Skip -> continue
                                    is Step.Send -> {
                                        started = java.lang.System.nanoTime()
                                        deadline = started + timeoutMs * 1_000_000L
                                        channel.write(ByteBuffer.wrap(step.bytes))
                                    }
                                }
                            }
                            outcome
                        }
                    }
                } catch (_: PortUnreachableException) {
                    Exchange.Refused
                } catch (error: NoRouteToHostException) {
                    Exchange.Failed(FailureKind.CONNECT, "No route", error.message.orEmpty())
                } catch (error: ClosedByInterruptException) {
                    throw InterruptedException(error.message)
                } catch (error: IOException) {
                    // Some kernels report the refusal on the write rather than
                    // the read, as ECONNREFUSED.
                    if (error.message.orEmpty().contains("ECONNREFUSED") ||
                        error.message.orEmpty().contains("refused", ignoreCase = true)
                    ) {
                        Exchange.Refused
                    } else {
                        Exchange.Failed(FailureKind.CONNECT, "Could not send", error.message.orEmpty())
                    }
                }
            }
        }
    }

    sealed interface Exchange {
        class Reply(val bytes: ByteArray, val rttNs: Long) : Exchange

        data object Refused : Exchange

        data object Silence : Exchange

        data class Failed(val kind: FailureKind, val message: String, val detail: String = "") : Exchange
    }

    /**
     * @param address already resolved by [NetworkChecker], which owns the name
     *   lookup and its failure words for all three host kinds.
     * @param where the target as a person reads it, "db.lan:53 (192.0.2.10)".
     * @param host the same without the port, for a sentence that already names it.
     * @param pinger for silent mode's echo.
     */
    fun check(
        monitor: Monitor,
        address: InetAddress,
        where: String,
        host: String,
        community: String,
        pinger: NetworkChecker.Pinger,
    ): CheckResult {
        val watch = monitor.udp
        val network = monitor.network
        val codec = UdpCodec.of(watch.probe)
        val tries = watch.attempts.coerceIn(1, UdpWatch.MAX_ATTEMPTS)
        val timeoutMs = monitor.timeoutSeconds.coerceIn(1, UdpWatch.MAX_WAIT_SECONDS) * 1000
        val target = InetSocketAddress(address, network.port)
        if (watch.probe == UdpProbe.SNMP && community.isEmpty()) {
            return failed(FailureKind.BAD_CONFIG, "No SNMP community saved", "Type the community in the monitor's settings.")
        }
        var lastSentAt = 0L
        for (attempt in 1..tries) {
            val since = nowMs() - lastSentAt
            if (attempt > 1 && since < MIN_GAP_MS) pause(MIN_GAP_MS - since)
            val nonce = ByteArray(16).also(random)
            val ctx = UdpCodec.Context(watch, nonce, network.cleanHost, nowMs(), community)
            lastSentAt = ctx.sentAtMs
            var challenged = false
            var verdict: UdpCodec.Read? = null
            val exchange = datagrams.exchange(target, codec.request(ctx), timeoutMs) { bytes ->
                when (val read = readSafely(codec, bytes, ctx)) {
                    UdpCodec.Read.NotMine -> Step.Skip
                    is UdpCodec.Read.Challenge -> if (challenged) {
                        // One challenge per try. A server that challenges the
                        // answer too is not going to answer the query.
                        verdict = UdpCodec.Read.Wrong(
                            "Challenged again by $host",
                            "The server asked for a second challenge answer instead of replying.",
                        )
                        Step.Keep
                    } else {
                        challenged = true
                        Step.Send(read.next)
                    }
                    else -> {
                        verdict = read
                        Step.Keep
                    }
                }
            }
            when (exchange) {
                is Exchange.Refused -> return refused(network, host, attempt)
                is Exchange.Failed -> return failed(exchange.kind, "${exchange.message} to $where", exchange.detail)
                is Exchange.Silence -> continue
                is Exchange.Reply -> {
                    val rttUs = (exchange.rttNs / 1000).coerceAtLeast(1)
                    ctx.receivedAtMs = ctx.sentAtMs + rttUs / 1000
                    val reading = UdpReading(sent = attempt, answeredOn = attempt, rttUs = rttUs, bytes = exchange.bytes.size)
                    when (val read = verdict) {
                        is UdpCodec.Read.Good -> return good(watch, read, reading, tries)
                        is UdpCodec.Read.Wrong -> return CheckResult(
                            ok = false,
                            latencyMs = rttMs(rttUs),
                            failureKind = FailureKind.BAD_REPLY,
                            message = read.message,
                            detail = read.detail,
                            udp = reading,
                            udpFacts = read.facts + tryFact(reading, tries),
                            at = nowMs(),
                        )
                        else -> continue
                    }
                }
            }
        }
        return if (watch.probe == UdpProbe.SILENT) silentVerdict(monitor, address, where, tries, pinger) else silence(where, tries, monitor)
    }

    private fun good(watch: UdpWatch, read: UdpCodec.Read.Good, reading: UdpReading, tries: Int): CheckResult {
        val headline = if (watch.probe == UdpProbe.SILENT) "Not refused, and it answered" else read.headline
        return CheckResult(
            ok = true,
            latencyMs = rttMs(reading.rttUs),
            message = read.degraded ?: "$headline, ${rttText(reading.rttUs)}",
            degraded = read.degraded != null,
            udp = reading,
            udpFacts = read.facts + UdpFact("Round trip", rttText(reading.rttUs)) + tryFact(reading, tries),
            at = nowMs(),
        )
    }

    private fun tryFact(reading: UdpReading, tries: Int): List<UdpFact> =
        if (reading.answeredOn > 1) listOf(UdpFact("Answered on", "try ${reading.answeredOn} of $tries")) else emptyList()

    private fun refused(network: NetworkWatch, host: String, attempt: Int) = CheckResult(
        ok = false,
        latencyMs = 0,
        failureKind = FailureKind.PORT_CLOSED,
        message = "UDP port ${network.port} closed on $host",
        detail = "The host answered that nothing is listening on that port.",
        udp = UdpReading(sent = attempt, refused = true),
        udpFacts = listOf(UdpFact("Outcome", "refused, ICMP port unreachable")),
        at = nowMs(),
    )

    private fun silence(where: String, tries: Int, monitor: Monitor) = CheckResult(
        ok = false,
        latencyMs = 0,
        failureKind = FailureKind.NO_REPLY,
        message = "No reply from $where after $tries ${if (tries == 1) "try" else "tries"}",
        detail = "Nothing came back, not even a refusal, within ${monitor.timeoutSeconds}s a try.",
        hint = "The service may be down, or a firewall drops the packets. Some networks block outgoing UDP altogether.",
        udp = UdpReading(sent = tries),
        udpFacts = listOf(UdpFact("Outcome", "no reply to $tries ${if (tries == 1) "try" else "tries"}")),
        at = nowMs(),
    )

    private fun silentVerdict(
        monitor: Monitor,
        address: InetAddress,
        where: String,
        tries: Int,
        pinger: NetworkChecker.Pinger,
    ): CheckResult {
        val echo = runCatching {
            pinger.ping(address, 1, monitor.timeoutSeconds.coerceAtLeast(1) * 1000L)
        }.getOrNull()
        val rtt = (echo as? NetworkChecker.PingOutcome.Replies)?.rttsUs?.firstOrNull { it != null }
        return if (rtt != null) {
            CheckResult(
                ok = true,
                latencyMs = rttMs(rtt),
                message = "Not refused, host answers ping, ${rttText(rtt)}",
                udp = UdpReading(sent = tries, echo = true, rttUs = rtt),
                udpFacts = listOf(
                    UdpFact("Port", "not refused after $tries ${if (tries == 1) "try" else "tries"}"),
                    UdpFact("Host", "answers ping in ${rttText(rtt)}"),
                ),
                at = nowMs(),
            )
        } else {
            CheckResult(
                ok = false,
                latencyMs = 0,
                failureKind = FailureKind.NO_REPLY,
                message = "No refusal and no ping reply from $where",
                detail = "Silent mode can't tell whether the service is there when the host doesn't answer ping either.",
                hint = "The host may be down. If it filters ping on purpose, use a probe that gets an answer.",
                udp = UdpReading(sent = tries, echo = false),
                udpFacts = listOf(
                    UdpFact("Port", "not refused"),
                    UdpFact("Host", "no ping reply"),
                ),
                at = nowMs(),
            )
        }
    }

    private fun failed(kind: FailureKind, message: String, detail: String = ""): CheckResult =
        CheckResult(ok = false, latencyMs = 0, failureKind = kind, message = message, detail = detail, at = nowMs())

    companion object {
        /**
         * A parser that throws on a reply it did not expect, a truncated QUIC
         * packet once, would otherwise escape the check: Test now has no catch
         * around it and the app went down. A reply nobody can read is a wrong
         * reply from a server that is there.
         */
        internal fun readSafely(codec: UdpCodec, bytes: ByteArray, ctx: UdpCodec.Context): UdpCodec.Read = try {
            codec.read(bytes, ctx)
        } catch (_: RuntimeException) {
            UdpCodec.Read.Wrong(
                "Unreadable ${ctx.watch.probe.label} reply",
                "${bytes.size} bytes came back that don't parse as one.",
                listOf(UdpFact("Bytes", me.river.nightbell.domain.UdpPayload.hexOf(bytes, 32))),
            )
        }

        /** The largest payload a UDP datagram over IPv4 can carry. */
        const val MAX_DATAGRAM = 65_507

        /**
         * Linux sends at most one ICMP port unreachable a second per
         * destination by default, so a second try inside that second can get
         * silence where the first got a refusal.
         */
        const val MIN_GAP_MS = 1000L
    }
}
