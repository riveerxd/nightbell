package me.river.nightbell

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A SOCKS5 proxy that actually forwards.
 *
 * The one in `SocksProxyTest` answers HTTP on the socket it was handed and never
 * dials anything, which proves this app speaks SOCKS5 correctly and proves nothing
 * about what happens after the tunnel opens. That was enough while everything
 * routed was plain HTTP. It is not enough for a certificate: a TLS handshake has to
 * run end to end *through* the tunnel, and whether a custom trust manager and a
 * SOCKS route survive each other is not something either half's tests can answer.
 *
 * Which is the arrangement issue #6 is actually about. The client is handed a name
 * it cannot resolve, the proxy is the only thing that can find the target, and the
 * bytes come back down the tunnel with the origin's certificate inside them. That
 * is a Tor hidden service with the six relays taken out.
 *
 * [resolve] stands in for the hidden network. It maps the name the client asked
 * for onto something this machine can dial, so a test can use a `.onion` address
 * that no resolver on earth would answer.
 */
class TinySocks5(
    private val resolve: (host: String, port: Int) -> InetSocketAddress,
) : AutoCloseable {

    private val server = ServerSocket(0)
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)

    /** Every "host:port" the proxy was asked to reach, in order. */
    val requested: MutableList<String> = CopyOnWriteArrayList()

    /** SOCKS5 address type of the last request. 3 is "domain name". */
    @Volatile
    var requestedAddressType: Int = -1
        private set

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "TinySocks5-$port") {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                // Swallowed for the same reason TinyHttpServer swallows: a client
                // that gives up mid-handshake throws out of a read here, and on
                // Android an uncaught exception on any thread kills the process and
                // takes the whole instrumentation run with it.
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            // No authentication, which is what a local Tor daemon offers.
            out.write(byteArrayOf(5, 0))
            out.flush()

            require(input.readUnsignedByte() == 5) { "not SOCKS5" }
            require(input.readUnsignedByte() == 1) { "not CONNECT" }
            input.readUnsignedByte()
            val type = input.readUnsignedByte()
            val host = when (type) {
                1 -> ByteArray(4).also { input.readFully(it) }
                    .joinToString(".") { (it.toInt() and 0xFF).toString() }

                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    .toString(Charsets.US_ASCII)

                4 -> ByteArray(16).also { input.readFully(it) }.joinToString(":")
                else -> error("unknown address type $type")
            }
            val targetPort = input.readUnsignedShort()
            requestedAddressType = type
            requested += "$host:$targetPort"

            val target = try {
                Socket().apply { connect(resolve(host, targetPort), 5_000) }
            } catch (_: Throwable) {
                // 0x05 is "connection refused by destination host".
                out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                out.flush()
                return
            }

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            // Two pumps, because a TLS handshake talks in both directions before
            // either side has said anything an HTTP proxy would recognise.
            target.use { upstream ->
                val up = thread(isDaemon = true) {
                    runCatching { socket.getInputStream().copyTo(upstream.getOutputStream()) }
                    runCatching { upstream.shutdownOutput() }
                }
                runCatching { upstream.getInputStream().copyTo(out) }
                runCatching { socket.shutdownOutput() }
                up.join(5_000)
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
