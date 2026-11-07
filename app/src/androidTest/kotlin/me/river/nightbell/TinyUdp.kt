package me.river.nightbell

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A UDP server in a thread, for the device tests. Answers each datagram with
 * whatever [answer] returns, or stays quiet when it returns null, which is how
 * a WireGuard endpoint or a firewalled service looks from outside.
 *
 * Closing it unbinds the port, so the next datagram to it gets the kernel's
 * ICMP port unreachable: the refusal the UDP checker reads.
 */
class TinyUdp private constructor(
    bind: InetAddress,
    port: Int,
    private val answer: (ByteArray, java.net.SocketAddress) -> ByteArray?,
) : AutoCloseable {

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bind, port))
    }
    private val count = AtomicInteger()

    val port: Int get() = socket.localPort
    val received: Int get() = count.get()

    private val thread = Thread {
        val buffer = ByteArray(65_507)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                count.incrementAndGet()
                val reply = answer(packet.data.copyOfRange(0, packet.length), packet.socketAddress) ?: continue
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            } catch (_: SocketException) {
                return@Thread
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        socket.close()
        thread.join(2_000)
    }

    companion object {
        private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

        /** NOERROR with one A record, 192.0.2.1, for whatever is asked. */
        fun dns(port: Int = 0, bind: InetAddress = loopback) = TinyUdp(bind, port) { query, _ ->
            if (query.size < 12) return@TinyUdp null
            val reply = query.copyOf()
            reply[2] = 0x81.toByte()
            reply[3] = 0x80.toByte()
            reply[7] = 1
            reply + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 1)
        }

        /** Echoes every datagram back unchanged. */
        fun echo(port: Int = 0, bind: InetAddress = loopback) = TinyUdp(bind, port) { bytes, _ -> bytes }

        /** Reads and never answers. */
        fun quiet(port: Int = 0, bind: InetAddress = loopback) = TinyUdp(bind, port) { _, _ -> null }

        /**
         * A Source server's A2S_INFO, with the challenge keyed on the address
         * and port it was sent to, as real servers key it. An answer from any
         * other port is challenged again.
         */
        fun sourceServer(bind: InetAddress = loopback): TinyUdp {
            val issued = java.util.concurrent.ConcurrentHashMap<java.net.SocketAddress, ByteArray>()
            var next = 0x11223344
            return TinyUdp(bind, 0) { query, from ->
                val ffff = byteArrayOf(-1, -1, -1, -1)
                if (query.size < 5 || query[4] != 0x54.toByte()) return@TinyUdp null
                val answered = if (query.size >= 29) query.copyOfRange(query.size - 4, query.size) else null
                val expected = issued[from]
                if (answered != null && expected != null && answered.contentEquals(expected)) {
                    ffff + byteArrayOf(0x49, 17) + "Tiny\u0000de_dust2\u0000cstrike\u0000Counter-Strike\u0000".toByteArray() +
                        byteArrayOf(10, 0, 3, 16)
                } else {
                    val challenge = java.nio.ByteBuffer.allocate(4).putInt(next++).array()
                    issued[from] = challenge
                    ffff + byteArrayOf(0x41) + challenge
                }
            }
        }
    }
}
