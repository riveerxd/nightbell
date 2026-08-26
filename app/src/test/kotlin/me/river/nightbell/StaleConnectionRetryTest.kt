package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HttpMethod
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported "IOException: unexpected end of stream".
 *
 * [TinyHttpServer] answers every request with `Connection: close`, so nothing in
 * the rest of the suite ever puts a connection in the pool, and the one failure
 * this covers cannot happen there. This server keeps its connections alive and
 * then drops them the way a real one reaps an idle keep-alive, which is what a
 * monitor walks into when it comes back for its next check minutes later.
 */
class StaleConnectionRetryTest {

    private fun monitor(url: String) = Monitor(
        id = "stale",
        name = "Stale",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 10,
    )

    @Test
    fun `a keep-alive connection the server already dropped is not an outage`() {
        KeepAliveServer().use { server ->
            // One checker for both checks: the pool lives on the instance, and in
            // the app that instance is the whole process's.
            val checker = HttpChecker()
            val target = monitor("${server.baseUrl}/health")

            val first = runBlocking { checker.check(target) }
            assertTrue(first.message, first.ok)
            assertEquals(1, server.served.get())

            // The far end hangs up while nobody is looking. The socket stays in
            // OkHttp's pool: the pool evicts on age, not on liveness, and a remote
            // FIN sets none of the flags its cheap health check reads.
            server.dropOpenConnections()
            Thread.sleep(SETTLE_MS)

            val second = runBlocking { checker.check(target) }
            assertTrue(
                "a reaped keep-alive connection must not read as an outage: ${second.message}",
                second.ok,
            )
            assertEquals(200, second.statusCode)
            // Two requests reached the server, not three: the attempt written into
            // the dead socket never arrived anywhere.
            assertEquals(2, server.served.get())
        }
    }

    @Test
    fun `the retried check reports its own round trip, not the dead socket's`() {
        KeepAliveServer().use { server ->
            val checker = HttpChecker()
            val target = monitor("${server.baseUrl}/health")

            runBlocking { checker.check(target) }
            server.dropOpenConnections()
            Thread.sleep(SETTLE_MS)

            val second = runBlocking { checker.check(target) }
            assertTrue(second.message, second.ok)
            // Nothing waits on a closed socket, so the failed attempt costs
            // microseconds. A latency anywhere near the sleep above would mean the
            // clock is being carried across attempts and every retried check would
            // read as degraded.
            assertTrue(
                "retried check reported ${second.latencyMs}ms",
                second.latencyMs < SETTLE_MS,
            )
        }
    }

    @Test
    fun `a reaped connection on the far side of a redirect is retried too`() {
        // The case that made the first version of this fix useless in practice.
        // `followRedirects` is on by default, and the watcher's flags used to latch
        // for the whole call: the first hop's response set "a response arrived" and
        // it never cleared, so no failure after a redirect could ever qualify. One
        // http-to-https hop, which is most of the web, disabled the retry entirely.
        //
        // Two servers, because two hops to the same host share one pooled socket
        // and the second could not go stale on its own.
        KeepAliveServer().use { destination ->
            KeepAliveServer(redirect = { "${destination.baseUrl}/final" }).use { entry ->
                val checker = HttpChecker()
                val target = monitor("${entry.baseUrl}/start")

                val first = runBlocking { checker.check(target) }
                assertTrue(first.message, first.ok)
                assertEquals(1, entry.served.get())
                assertEquals(1, destination.served.get())

                // Only the far side hangs up, so the redirect still succeeds and the
                // failure lands on the second exchange.
                destination.dropOpenConnections()
                Thread.sleep(SETTLE_MS)

                val second = runBlocking { checker.check(target) }

                assertTrue(
                    "a dead connection after a redirect read as an outage: ${second.message}",
                    second.ok,
                )
                // Three at the entry, not two: a retry replays the whole call, so
                // the redirect is walked again. Harmless for the idempotent methods
                // this retry is now limited to, and worth pinning so it is a known
                // property rather than a surprise in someone's access log.
                assertEquals(3, entry.served.get())
                assertEquals(2, destination.served.get())
            }
        }
    }

    @Test
    fun `a POST is never replayed, because the server may already have acted on it`() {
        KeepAliveServer().use { server ->
            val checker = HttpChecker()
            val posting = monitor("${server.baseUrl}/submit").copy(
                kind = MonitorKind.ADVANCED_REQUEST,
                method = HttpMethod.POST,
                body = "{}",
            )

            runBlocking { checker.check(posting) }
            server.dropOpenConnections()
            Thread.sleep(SETTLE_MS)
            val second = runBlocking { checker.check(posting) }

            // The failure is reported instead. "The connection died before a
            // response" and "the server read it, acted on it, then died" are the
            // same bytes on the wire, and one of them is a duplicate submission.
            assertFalse("a POST was replayed after a dead connection", second.ok)
            assertEquals(1, server.served.get())
        }
    }

    @Test
    fun `a refused connection is still reported down rather than retried`() {
        // The negative half of the same rule. Nothing was reused and nothing was
        // this app's fault, so there is nothing to hide: a host that will not
        // answer is down, first time of asking.
        val port = ServerSocket(0).use { it.localPort }
        val result = runBlocking { HttpChecker().check(monitor("http://127.0.0.1:$port/health")) }

        assertFalse(result.message, result.ok)
        assertEquals(FailureKind.CONNECT, result.failureKind)
    }

    private companion object {
        /** Long enough for a loopback FIN to land before the next check. */
        const val SETTLE_MS = 250L
    }
}

/**
 * An HTTP server that keeps its connections open, and drops them on request.
 *
 * Everything else about it is deliberately minimal: it answers any request line
 * with the same 200, because what is under test is which socket the request
 * arrives on rather than anything in the response.
 */
private class KeepAliveServer(
    /** Status and Location for a path, or null for a plain 200. */
    private val redirect: (String) -> String? = { null },
) : AutoCloseable {

    private val server = ServerSocket(0)
    private val open = CopyOnWriteArrayList<Socket>()

    /** Requests that actually reached the server. */
    val served = AtomicInteger(0)

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "KeepAliveServer") {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                open += socket
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        // A dropped connection surfaces here as a read failure on a blocked
        // thread, which is the normal way this ends rather than an error.
        runCatching {
            val reader = socket.getInputStream().bufferedReader()
            val out = socket.getOutputStream()
            while (!socket.isClosed) {
                val requestLine = reader.readLine() ?: return
                if (requestLine.isBlank()) continue
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                }
                served.incrementAndGet()
                val path = requestLine.split(" ").getOrElse(1) { "/" }
                val location = redirect(path)
                val body = if (location == null) "ok" else ""
                val head = buildString {
                    if (location == null) {
                        append("HTTP/1.1 200 OK\r\n")
                    } else {
                        append("HTTP/1.1 302 Found\r\n")
                        append("Location: $location\r\n")
                    }
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${body.length}\r\n")
                    append("Connection: keep-alive\r\n\r\n")
                }
                out.write(head.toByteArray(Charsets.US_ASCII))
                if (body.isNotEmpty()) out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    /** Hangs up on every live connection, the way a server reaps idle keep-alives. */
    fun dropOpenConnections() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    override fun close() {
        dropOpenConnections()
        runCatching { server.close() }
    }
}
