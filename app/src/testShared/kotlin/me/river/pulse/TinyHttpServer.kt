package me.river.pulse

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A ~100-line HTTP server for tests.
 *
 * Deliberately dependency-free: it gives full control over status codes, bodies,
 * headers and artificial latency without pulling MockWebServer (and its okhttp
 * version coupling) into the build.
 */
class TinyHttpServer(private val handler: (Request) -> Response) : AutoCloseable {

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
    )

    private val server = ServerSocket(0)
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val received: MutableList<Request> = CopyOnWriteArrayList()

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun url(path: String): String = baseUrl + if (path.startsWith("/")) path else "/$path"

    init {
        thread(isDaemon = true, name = "TinyHttpServer-$port") {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) { serve(socket) }
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

            val payload = response.body.toByteArray(Charsets.UTF_8)
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
