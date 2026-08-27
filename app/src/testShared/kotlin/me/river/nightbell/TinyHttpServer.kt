package me.river.nightbell

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ServerSocketFactory
import kotlin.concurrent.thread

/**
 * A ~100-line HTTP server for tests.
 *
 * Deliberately dependency-free: it gives full control over status codes, bodies,
 * headers and artificial latency without pulling MockWebServer (and its okhttp
 * version coupling) into the build.
 *
 * [socketFactory] is how it serves HTTPS. Pass one from [TinyTls] and the same
 * server speaks TLS with a certificate no CA has ever heard of, which is the whole
 * subject of issue #6. Left null it is plain HTTP, exactly as before.
 */
class TinyHttpServer(
    private val socketFactory: ServerSocketFactory? = null,
    private val handler: (Request) -> Response,
) : AutoCloseable {

    /** Plain HTTP, which is what almost every caller wants. */
    constructor(handler: (Request) -> Response) : this(null, handler)

    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Response(
        val code: Int = 200,
        val reason: String = "OK",
        val body: String = "",
        val contentType: String = "text/plain; charset=utf-8",
        val delayMs: Long = 0,
        val extraHeaders: Map<String, String> = emptyMap(),
        /**
         * Raw payload, taking precedence over [body] when set.
         *
         * Needed for anything that is not text: [body] is encoded as UTF-8, which
         * silently mangles binary — a PNG served through it never decodes.
         */
        val bytes: ByteArray? = null,
    )

    private val server: ServerSocket = (socketFactory ?: ServerSocketFactory.getDefault())
        .createServerSocket(0)
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val received: MutableList<Request> = CopyOnWriteArrayList()

    val port: Int get() = server.localPort
    val scheme: String get() = if (socketFactory == null) "http" else "https"

    /**
     * Addressed by name rather than by 127.0.0.1 when serving TLS.
     *
     * `localhost` is the name [TinyTls] issues its certificate for, so a test
     * about trust anchors fails on the anchor rather than on the hostname not
     * matching, which is a different bug wearing the same exception.
     */
    val baseUrl: String
        get() = if (socketFactory == null) "http://127.0.0.1:$port" else "https://localhost:$port"

    fun url(path: String): String = baseUrl + if (path.startsWith("/")) path else "/$path"

    init {
        thread(isDaemon = true, name = "TinyHttpServer-$port") {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) {
                    // Swallowed on purpose, and it has to be swallowed here.
                    //
                    // A client that refuses the certificate aborts the handshake,
                    // and this thread then throws out of `readLine`. On the JVM
                    // that quietly kills one thread. On Android an uncaught
                    // exception in any thread kills the *process*, so the TLS trust
                    // tests took the whole instrumentation run down with them and
                    // reported "Process crashed" instead of a result.
                    //
                    // There is nothing to report either way: a connection that
                    // never became a request is the client's verdict, and the test
                    // is asserting on that verdict from the other side.
                    runCatching { serve(socket) }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buffer = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(buffer, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read)
            } else {
                ""
            }

            val request = Request(method, path, headers, body)
            received += request
            val response = try {
                handler(request)
            } catch (error: Throwable) {
                Response(code = 500, reason = "Internal Error", body = error.message.orEmpty())
            }

            if (response.delayMs > 0) Thread.sleep(response.delayMs)

            val payload = response.bytes ?: response.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.code} ${response.reason}\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${payload.size}\r\n")
                response.extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (method != "HEAD") out.write(payload)
            out.flush()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
