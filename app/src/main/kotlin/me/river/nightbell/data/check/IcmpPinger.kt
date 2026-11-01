package me.river.nightbell.data.check

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * ICMP echo without root.
 *
 * Linux has had unprivileged "ping sockets" since 3.0: a `SOCK_DGRAM` socket of
 * protocol `IPPROTO_ICMP` (or `IPPROTO_ICMPV6`), open to any group inside
 * `net.ipv4.ping_group_range`. Android sets that range to every group, which is
 * how its own `ping` works without being setuid. The kernel writes the identifier
 * and the checksum, and only hands this socket the replies that carry its
 * identifier, so all that is left to match is the sequence number.
 *
 * There is no fallback to running `/system/bin/ping`. Without root it opens the
 * same socket from the same SELinux domain, so it fails in exactly the cases a
 * fallback would be for. A refused socket is reported as what it is: this phone
 * not letting apps ping, which is a fact about the phone and not about the host.
 */
class IcmpPinger : NetworkChecker.Pinger {

    override fun ping(address: InetAddress, count: Int, timeoutMs: Long): NetworkChecker.PingOutcome {
        val v6 = address is Inet6Address
        val fd = try {
            Os.socket(
                if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM,
                if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
            )
        } catch (error: ErrnoException) {
            val errno = OsConstants.errnoName(error.errno) ?: error.errno.toString()
            Diag.log(LogEvent.CHECK_FAILED, LogField.tag("at", "icmp_socket"), LogField.text("errno", errno))
            return NetworkChecker.PingOutcome.Failed(
                FailureKind.BAD_CONFIG,
                "This phone does not let apps send ping",
                "Opening an ICMP socket failed with $errno. A TCP port check on the same host works without it.",
            )
        }
        try {
            return exchange(fd, address, v6, count, timeoutMs)
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun exchange(
        fd: FileDescriptor,
        address: InetAddress,
        v6: Boolean,
        count: Int,
        timeoutMs: Long,
    ): NetworkChecker.PingOutcome {
        val sentAt = LongArray(count)
        val rtts = arrayOfNulls<Long>(count)
        var sent = 0
        var nextSendAt = now()
        val gapNs = TimeUnit.MILLISECONDS.toNanos(GAP_MS)
        val waitNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buffer = ByteArray(RECEIVE_BYTES)
        // A per check salt in the payload, so a late reply to an earlier check that
        // happened to reuse this socket's identifier cannot be taken for ours.
        val salt = (System.nanoTime() and 0xffff).toInt()

        while (true) {
            val nowNs = now()
            if (sent < count && nowNs >= nextSendAt) {
                val packet = echoRequest(v6, sent, salt)
                try {
                    Os.sendto(fd, packet, 0, packet.size, 0, address, 0)
                } catch (error: ErrnoException) {
                    // The first send failing is the network saying no outright:
                    // no route, unreachable network. Later ones failing is loss.
                    if (sent == 0) {
                        return NetworkChecker.PingOutcome.Failed(
                            FailureKind.CONNECT,
                            sendFailure(error.errno),
                            OsConstants.errnoName(error.errno).orEmpty(),
                        )
                    }
                }
                sentAt[sent] = now()
                sent++
                nextSendAt = now() + gapNs
                continue
            }
            val outstanding = (0 until sent).any { rtts[it] == null }
            val lastDeadline = if (sent == 0) Long.MAX_VALUE else sentAt[sent - 1] + waitNs
            if (sent == count && (!outstanding || nowNs >= lastDeadline)) break

            val untilSend = if (sent < count) nextSendAt - nowNs else Long.MAX_VALUE
            val untilEnd = lastDeadline - nowNs
            val waitMs = TimeUnit.NANOSECONDS.toMillis(minOf(untilSend, untilEnd)).coerceIn(1, Int.MAX_VALUE.toLong())
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), waitMs.toInt())
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                throw error
            }
            if (ready <= 0) continue
            // Poll said a datagram is waiting, so this read does not block. One
            // per round: the next poll comes straight back if there is another.
            val read = try {
                Os.recvfrom(fd, buffer, 0, buffer.size, 0, null as InetSocketAddress?)
            } catch (error: ErrnoException) {
                // An ICMP error queued against the socket says nothing more than
                // a missing reply does, and is counted the same way.
                continue
            }
            val arrived = now()
            val seq = replySequence(buffer, read, v6, salt) ?: continue
            if (seq in 0 until sent && rtts[seq] == null) {
                rtts[seq] = TimeUnit.NANOSECONDS.toMicros(arrived - sentAt[seq]).coerceAtLeast(1)
            }
        }
        return NetworkChecker.PingOutcome.Replies(rtts.toList())
    }

    private fun now(): Long = System.nanoTime()

    companion object {
        /** Between two echo requests, as ping(8) does by default. */
        const val GAP_MS = 1_000L
        private const val PAYLOAD_BYTES = 16
        private const val RECEIVE_BYTES = 1500
        private const val ECHO_REQUEST_V4 = 8
        private const val ECHO_REPLY_V4 = 0
        private const val ECHO_REQUEST_V6 = 128
        private const val ECHO_REPLY_V6 = 129
        private val MAGIC = "nightbell".toByteArray()

        /**
         * Type, code, a zero checksum and identifier for the kernel to fill, the
         * sequence, then a payload carrying a marker and the per check salt.
         */
        internal fun echoRequest(v6: Boolean, sequence: Int, salt: Int): ByteArray {
            val packet = ByteArray(8 + PAYLOAD_BYTES)
            packet[0] = (if (v6) ECHO_REQUEST_V6 else ECHO_REQUEST_V4).toByte()
            packet[6] = (sequence shr 8 and 0xff).toByte()
            packet[7] = (sequence and 0xff).toByte()
            MAGIC.copyInto(packet, 8)
            packet[8 + MAGIC.size] = (salt shr 8 and 0xff).toByte()
            packet[9 + MAGIC.size] = (salt and 0xff).toByte()
            return packet
        }

        /** The sequence of an echo reply that is ours, or null for anything else. */
        internal fun replySequence(buffer: ByteArray, length: Int, v6: Boolean, salt: Int): Int? {
            if (length < 8 + MAGIC.size + 2) return null
            val type = buffer[0].toInt() and 0xff
            if (type != (if (v6) ECHO_REPLY_V6 else ECHO_REPLY_V4)) return null
            for (i in MAGIC.indices) if (buffer[8 + i] != MAGIC[i]) return null
            val gotSalt = (buffer[8 + MAGIC.size].toInt() and 0xff shl 8) or (buffer[9 + MAGIC.size].toInt() and 0xff)
            if (gotSalt != salt) return null
            return (buffer[6].toInt() and 0xff shl 8) or (buffer[7].toInt() and 0xff)
        }

        internal fun sendFailure(errno: Int): String = when (errno) {
            OsConstants.ENETUNREACH -> "Network unreachable"
            OsConstants.EHOSTUNREACH -> "Host unreachable"
            OsConstants.EACCES, OsConstants.EPERM -> "Sending ICMP is not allowed on this network"
            else -> "Could not send the echo request"
        }
    }
}
